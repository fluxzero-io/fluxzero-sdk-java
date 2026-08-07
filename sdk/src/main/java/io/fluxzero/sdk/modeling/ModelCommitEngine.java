/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static io.fluxzero.common.handling.HandlerInspector.inspect;

/**
 * Evaluates a complete model commit without performing persistence.
 * <p>
 * Interceptor outputs form ordered substeps under one pinned read boundary. Every apply within one substep reads its
 * same immutable begin-state; only a successfully completed substep becomes visible to later substeps. A failure
 * produces no commit result. This class never publishes or stores an event, allowing its caller to commit the returned
 * commit atomically after evaluation.
 */
final class ModelCommitEngine {
    private static final int MAX_SUBSTEPS = 10_000;

    private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    private final Map<ModelMetadata.HandlerKind,
            Map<Executable, HandlerMatcher<Object, DeserializingMessage>>> matchers;
    private final Map<List<ModelMetadata.HandlerMethod>, HandlerPlan> handlerPlans =
            new ConcurrentHashMap<>();

    ModelCommitEngine(List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        List<ParameterResolver<? super DeserializingMessage>> resolvers =
                new ArrayList<>(parameterResolvers.size() + 1);
        @SuppressWarnings("unchecked")
        ParameterResolver<? super DeserializingMessage> modelResolver =
                (ParameterResolver<? super DeserializingMessage>) (ParameterResolver<?>)
                        new ModelEntityParameterResolver();
        resolvers.add(modelResolver);
        resolvers.addAll(parameterResolvers);
        this.parameterResolvers = List.copyOf(resolvers);
        this.matchers = new EnumMap<>(ModelMetadata.HandlerKind.class);
        for (ModelMetadata.HandlerKind kind : ModelMetadata.HandlerKind.values()) {
            matchers.put(kind, new ConcurrentHashMap<>());
        }
    }

    Evaluation evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            Collection<ModelMetadata.HandlerMethod> selectedHandlers) {
        return evaluate(message, beginState, selectedHandlers, true);
    }

    private Evaluation evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            Collection<ModelMetadata.HandlerMethod> selectedHandlers,
            boolean applyHandlers) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(beginState, "beginState");
        Objects.requireNonNull(selectedHandlers, "selectedHandlers");
        beginState.attachTo(message);
        try {
            return message.apply(
                    ignored -> evaluateInContext(
                            message, beginState, selectedHandlers, applyHandlers));
        } finally {
            beginState.attachTo(message);
        }
    }

    CommitEvaluation evaluate(
            DeserializingMessage initialMessage, SubstepResolver resolver) {
        Objects.requireNonNull(initialMessage, "initialMessage");
        Deque<PendingSubstep> pending = new ArrayDeque<>();
        pending.add(new PendingSubstep(initialMessage, true));
        return evaluate(pending, resolver, initialMessage, true);
    }

    /**
     * Runs apply interceptors and immediate legality assertions without invoking applies or after-handler assertions.
     */
    void assertLegal(
            DeserializingMessage initialMessage,
            SubstepResolver resolver) {
        Objects.requireNonNull(initialMessage, "initialMessage");
        Deque<PendingSubstep> pending = new ArrayDeque<>();
        pending.add(new PendingSubstep(initialMessage, true));
        evaluate(pending, resolver, initialMessage, false);
    }

    private CommitEvaluation evaluate(
            Deque<PendingSubstep> pending,
            SubstepResolver resolver,
            DeserializingMessage initialMessage,
            boolean applyHandlers) {
        Objects.requireNonNull(resolver, "resolver");
        Map<String, Object> stagedValues = new LinkedHashMap<>();
        LinkedHashSet<String> readModelIds = new LinkedHashSet<>();
        Map<String, Class<?>> readModelTypes =
                new LinkedHashMap<>();
        List<AppliedSubstep> appliedSubsteps = new ArrayList<>();
        long readStateIndex = -1L;
        boolean stateIndexPinned = false;
        ModelCommitContext originalContext = initialMessage == null ? null
                : initialMessage.getContext(ModelCommitContext.class).orElse(null);
        ModelCommitContext commitBeginContext = null;
        int processed = 0;

        try {
            while (!pending.isEmpty()) {
                if (++processed > MAX_SUBSTEPS) {
                    throw new IllegalStateException(
                            "Model commit exceeded %d interceptor substeps".formatted(MAX_SUBSTEPS));
                }
                PendingSubstep current = pending.removeFirst();
                GraphChangeMessage graphChangeMessage =
                        current.message() instanceof GraphChangeMessage changeMessage
                                ? changeMessage : null;
                ResolvedSubstep resolved = Objects.requireNonNull(
                        graphChangeMessage == null
                                ? resolver.resolve(
                                        current.message(),
                                        stateIndexPinned ? readStateIndex : null,
                                        stagedValues)
                                : resolver.resolveGraph(
                                        graphChangeMessage.change.modelId(),
                                        graphChangeMessage.change.modelType(),
                                        stateIndexPinned ? readStateIndex : null,
                                        stagedValues),
                        "Substep resolver returned null");
                if (!stateIndexPinned) {
                    readStateIndex = resolved.context().readStateIndex();
                    stateIndexPinned = true;
                    commitBeginContext = resolved.context();
                } else if (resolved.context().readStateIndex() != readStateIndex) {
                    throw new IllegalStateException(
                            "Substep loaded at state index %d while commit is pinned at %d"
                                    .formatted(resolved.context().readStateIndex(), readStateIndex));
                }
                ModelCommitContext context = resolved.context().withValues(stagedValues);
                resolved.context().entries().forEach(entry -> {
                    readModelIds.add(entry.target().modelId());
                    readModelTypes.putIfAbsent(
                            entry.target().modelId(),
                            entry.target().modelType());
                });
                if (graphChangeMessage != null) {
                    AppliedSubstep change = evaluateGraphChange(
                            graphChangeMessage.change,
                            resolved.context(), readStateIndex);
                    stagedValues.put(
                            change.transitions().getFirst().modelId(),
                            change.transitions().getFirst().after());
                    mergeAppliedSubstep(appliedSubsteps, change);
                    continue;
                }
                List<ModelMetadata.HandlerMethod> interceptors =
                        handlerPlan(resolved.handlers()).interceptors();
                if (current.interceptionAllowed() && !interceptors.isEmpty()) {
                    HandlerInvoker applicable = null;
                    for (int i = 0; i < interceptors.size(); i++) {
                        HandlerInvoker candidate =
                                invoker(interceptors.get(i), current.message(), context);
                        if (candidate == null) {
                            continue;
                        }
                        if (applicable != null) {
                            throw new IllegalStateException(
                                    "Multiple @InterceptApply methods were selected for %s: %s and %s"
                                            .formatted(
                                                    current.message().getPayloadClass().getName(),
                                                    applicable.getMethod().toGenericString(),
                                                    candidate.getMethod().toGenericString()));
                        }
                        applicable = candidate;
                    }
                    if (applicable != null) {
                        HandlerInvoker selected = applicable;
                        Object intercepted = current.message().apply(
                                ignored -> selected.invoke());
                        int pendingBefore = pending.size();
                        enqueueOutputs(current.message(), intercepted, pending);
                        int added = pending.size() - pendingBefore;
                        if (added > 0) {
                            resolver.prefetch(
                                    pending.stream().limit(added)
                                            .map(PendingSubstep::message)
                                            .toList(),
                                    readStateIndex,
                                    stagedValues);
                        }
                        continue;
                    }
                }

                Evaluation evaluation = evaluate(
                        current.message(), context,
                        resolved.handlers(), applyHandlers);
                for (Transition transition : evaluation.transitions()) {
                    stagedValues.put(transition.modelId(), transition.after());
                }
                appliedSubsteps.add(new AppliedSubstep(
                        current.message(), evaluation.transitions()));
            }
            return new CommitEvaluation(
                    readStateIndex, List.copyOf(readModelIds),
                    readModelTypes, appliedSubsteps,
                    stagedValues);
        } finally {
            ModelCommitContext restore =
                    originalContext == null ? commitBeginContext : originalContext;
            if (restore != null && initialMessage != null) {
                restore.attachTo(initialMessage);
            }
        }
    }

    private static AppliedSubstep evaluateGraphChange(
            StagedGraphChange change,
            ModelCommitContext context,
            long readStateIndex) {
        String modelId = change.modelId();
        Class<?> modelType = change.modelType();
        long targetStateIndex = targetStateIndex(
                context.entry(change.modelId()), readStateIndex);
        if (change.expectedStateIndex() != null
            && change.expectedStateIndex() != targetStateIndex) {
            throw new IllegalStateException(
                    "Staged graph '%s' was loaded at model state index %d while the commit resolved model state index %d"
                            .formatted(modelId, change.expectedStateIndex(), targetStateIndex));
        }
        ModelCommitContext.Entry target = context.entry(modelId);
        if (target == null || !context.mayWrite(modelId, modelType, null)) {
            throw new IllegalStateException(
                    "Staged graph '%s' of type %s is not a resolved write target"
                            .formatted(modelId, modelType.getName()));
        }
        Object after = change.expectedStateIndex() == null
                ? change.replay().apply(target.entity()).get()
                : change.after();
        Transition transition = new Transition(
                modelId, modelType,
                target.entity() instanceof ModelRoot<?> modelRoot
                        ? modelRoot.sequenceNumber() : -1L,
                target.entity() instanceof ModelRoot<?> modelRoot
                        ? modelRoot.lastEventIndex() : null,
                target.entity().get(), after, null,
                change.replay(), false);
        return new AppliedSubstep(
                new GraphChangeMessage(change), List.of(transition));
    }

    private static long targetStateIndex(
            ModelCommitContext.Entry target,
            long fallback) {
        return target != null && target.entity() instanceof ModelRoot<?> root
                ? root.stateIndex() : fallback;
    }

    private static void mergeAppliedSubstep(
            List<AppliedSubstep> appliedSubsteps,
            AppliedSubstep addition) {
        String eventMessageId = addition.message().getMessageId();
        for (int i = appliedSubsteps.size() - 1; i >= 0; i--) {
            AppliedSubstep existing = appliedSubsteps.get(i);
            if (!Objects.equals(
                    existing.message().getMessageId(), eventMessageId)
                || (existing.message() instanceof GraphChangeMessage)
                   != (addition.message() instanceof GraphChangeMessage)) {
                continue;
            }
            List<Transition> transitions = new ArrayList<>(
                    existing.transitions().size()
                    + addition.transitions().size());
            transitions.addAll(existing.transitions());
            transitions.addAll(addition.transitions());
            appliedSubsteps.set(i, new AppliedSubstep(
                    existing.message(), transitions));
            return;
        }
        appliedSubsteps.add(addition);
    }

    /**
     * Re-applies already produced commit events against a new pinned model boundary.
     * <p>
     * Command handling, assertions, and interceptors are deliberately not invoked. The supplied messages are the
     * original post-interception substeps and only their {@link Apply @Apply} handlers contribute new derived state.
     */
    CommitEvaluation rebase(
            List<DeserializingMessage> appliedMessages,
            SubstepResolver resolver) {
        Objects.requireNonNull(appliedMessages, "appliedMessages");
        if (appliedMessages.isEmpty()) {
            throw new IllegalArgumentException(
                    "A model commit rebase requires at least one applied message");
        }
        Deque<PendingSubstep> pending = new ArrayDeque<>(appliedMessages.size());
        appliedMessages.forEach(message -> {
            if (message instanceof GraphChangeMessage changeMessage) {
                pending.add(new PendingSubstep(
                        new GraphChangeMessage(
                                changeMessage.change.forRebase()),
                        false));
            } else {
                pending.add(new PendingSubstep(message, false));
            }
        });
        return evaluate(pending, resolver, null, true);
    }

    private Evaluation evaluateInContext(
            DeserializingMessage message,
            ModelCommitContext beginState,
            Collection<ModelMetadata.HandlerMethod> selectedHandlers,
            boolean applyHandlers) {
        HandlerPlan plan = handlerPlan(selectedHandlers);

        for (int i = 0; i < plan.beforeAssertions().size(); i++) {
            invokeIfApplicable(plan.beforeAssertions().get(i), message, beginState);
        }

        if (!applyHandlers) {
            return new Evaluation(beginState, beginState, List.of());
        }

        Map<String, Transition> transitions = null;
        for (ModelMetadata.HandlerMethod handler : plan.applies()) {
            if (handler.targetModelTypes().isEmpty()) {
                continue;
            }
            if (handler.targetModelTypes().size() != 1) {
                throw new IllegalStateException(
                        "Apply %s targets more than one model type".formatted(handler.executable()));
            }
            HandlerInvoker invoker = invoker(handler, message, beginState);
            if (invoker == null) {
                continue;
            }
            Class<?> targetType = handler.targetModelTypes().getFirst();
            Object result = invoker.invoke();
            String targetId = resolveWriteTarget(handler, targetType, result, beginState);
            ModelCommitContext.Entry target = beginState.entry(targetId);
            if (target == null || !beginState.mayWrite(
                    targetId, targetType, handler.executable())) {
                throw new IllegalStateException(
                        "Apply %s returned model '%s', which is not a resolved write target"
                                .formatted(handler.executable().toGenericString(), targetId));
            }
            Transition transition = new Transition(
                    targetId, targetType,
                    target.entity() instanceof ModelRoot<?> modelRoot
                            ? modelRoot.sequenceNumber() : -1L,
                    target.entity() instanceof ModelRoot<?> modelRoot
                            ? modelRoot.lastEventIndex() : null,
                    target.entity().get(), result,
                    handler.executable());
            if (transitions == null) {
                transitions = new LinkedHashMap<>();
            }
            Transition previous = transitions.putIfAbsent(targetId, transition);
            if (previous != null) {
                throw new IllegalStateException(
                        "Model '%s' is written by both %s and %s in one substep"
                                .formatted(targetId, previous.handler().toGenericString(),
                                           handler.executable().toGenericString()));
            }
        }
        List<Transition> transitionList;
        ModelCommitContext resultingState;
        if (transitions == null) {
            transitionList = List.of();
            resultingState = beginState;
        } else {
            Map<String, Object> values = new LinkedHashMap<>(transitions.size());
            transitions.forEach((id, transition) -> values.put(id, transition.after()));
            transitionList = List.copyOf(transitions.values());
            resultingState = beginState.withValues(values);
        }
        if (transitions != null) {
            for (int i = 0; i < plan.afterAssertions().size(); i++) {
                invokeIfApplicable(plan.afterAssertions().get(i), message, resultingState);
            }
        }
        return new Evaluation(
                beginState, resultingState, transitionList);
    }

    SingleTargetEvaluation evaluateSingleTarget(
            DeserializingMessage message,
            ModelCommitContext beginState,
            ModelMetadata.HandlerMethod handler,
            String expectedTargetId) {
        return evaluateSingleTarget(
                message, beginState, handler, expectedTargetId, null);
    }

    SingleTargetEvaluation evaluateSingleTarget(
            DeserializingMessage message,
            ModelCommitContext beginState,
            ModelMetadata.HandlerMethod handler,
            String expectedTargetId,
            DirectSingleTargetApply directApply) {
        beginState.attachTo(message);
        return message.apply(
                ignored -> directApply == null
                        ? evaluateSingleTargetInContext(
                                message, beginState, handler, expectedTargetId)
                        : evaluateDirectSingleTargetInContext(
                                message, beginState, handler,
                                expectedTargetId, directApply));
    }

    SingleTargetEvaluation evaluateDirectSingleTarget(
            DeserializingMessage message,
            Entity<?> entity,
            ModelMetadata.HandlerMethod handler,
            String expectedTargetId,
            DirectSingleTargetApply directApply) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(directApply, "directApply");
        Object current = entity.get();
        if (directApply.receiver() && current == null) {
            return new SingleTargetEvaluation(false, null);
        }
        return message.apply(ignored -> {
            Object result = directApply.invoker().invoke(
                    directApply.receiver() ? current : null,
                    (Object) message.getPayload());
            if (result == null) {
                return new SingleTargetEvaluation(true, null);
            }
            Class<?> targetType = handler.targetModelTypes().getFirst();
            if (!targetType.isInstance(result)) {
                throw new IllegalStateException(
                        "Apply %s returned %s instead of %s"
                                .formatted(
                                        handler.executable().toGenericString(),
                                        result.getClass().getName(),
                                        targetType.getName()));
            }
            ModelMetadata resultMetadata = ModelMetadata.of(result.getClass());
            Object resultId = resultMetadata.entityId().orElseThrow().read(result);
            if (resultId == null
                || !expectedTargetId.equals(
                        resultMetadata.parentScopedEntityId()
                                ? resultMetadata.repositoryId(resultId, result)
                                : resultMetadata.repositoryId(resultId))) {
                throw new IllegalStateException(
                        "Apply %s returned model '%s', which is not replay target '%s'"
                                .formatted(
                                        handler.executable().toGenericString(),
                                        resultId,
                                        expectedTargetId));
            }
            return new SingleTargetEvaluation(true, result);
        });
    }

    static DirectSingleTargetApply directSingleTargetApply(
            ModelMetadata.HandlerMethod handler,
            Class<?> payloadType) {
        if (handler.kind() != ModelMetadata.HandlerKind.APPLY
            || handler.targetModelTypes().size() != 1
            || !handler.modelParameters().isEmpty()
            || !(handler.executable() instanceof Method method)
            || method.getParameterCount() != 1) {
            return null;
        }
        Parameter parameter = method.getParameters()[0];
        if (parameter.getAnnotations().length != 0
            || !parameter.getType().isAssignableFrom(payloadType)) {
            return null;
        }
        boolean receiver = !Modifier.isStatic(method.getModifiers());
        if (receiver && handler.receiverModelType() == null) {
            return null;
        }
        MemberInvoker invoker = ReflectionUtils.getTypeMetadata(
                        method.getDeclaringClass())
                .invoker(method, true);
        return new DirectSingleTargetApply(invoker, receiver);
    }

    private SingleTargetEvaluation evaluateDirectSingleTargetInContext(
            DeserializingMessage message,
            ModelCommitContext beginState,
            ModelMetadata.HandlerMethod handler,
            String expectedTargetId,
            DirectSingleTargetApply directApply) {
        ModelCommitContext.Entry expected = beginState.entry(expectedTargetId);
        if (expected == null
            || directApply.receiver() && expected.entity().get() == null) {
            return new SingleTargetEvaluation(
                    false, expected == null ? null : expected.entity().get());
        }
        Object result = directApply.invoker().invoke(
                directApply.receiver() ? expected.entity().get() : null,
                (Object) message.getPayload());
        return validateSingleTargetResult(
                beginState, handler, expectedTargetId, result);
    }

    private SingleTargetEvaluation evaluateSingleTargetInContext(
            DeserializingMessage message,
            ModelCommitContext beginState,
            ModelMetadata.HandlerMethod handler,
            String expectedTargetId) {
        if (handler.kind() != ModelMetadata.HandlerKind.APPLY
            || handler.targetModelTypes().size() != 1) {
            throw new IllegalArgumentException(
                    "Single-target replay requires one apply target");
        }
        HandlerInvoker invoker = invoker(handler, message, beginState);
        if (invoker == null) {
            return new SingleTargetEvaluation(
                    false,
                    beginState.entry(expectedTargetId).entity().get());
        }
        Class<?> targetType = handler.targetModelTypes().getFirst();
        Object result = invoker.invoke();
        return validateSingleTargetResult(
                beginState, handler, expectedTargetId, result);
    }

    private SingleTargetEvaluation validateSingleTargetResult(
            ModelCommitContext beginState,
            ModelMetadata.HandlerMethod handler,
            String expectedTargetId,
            Object result) {
        Class<?> targetType = handler.targetModelTypes().getFirst();
        String targetId =
                resolveWriteTarget(
                        handler, targetType, result, beginState);
        ModelCommitContext.Entry target = beginState.entry(targetId);
        if (!expectedTargetId.equals(targetId)
            || target == null
            || !beginState.mayWrite(
                    targetId,
                    targetType,
                    handler.executable())) {
            throw new IllegalStateException(
                    "Apply %s returned model '%s', which is not replay target '%s'"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    targetId,
                                    expectedTargetId));
        }
        return new SingleTargetEvaluation(true, result);
    }

    @SuppressWarnings("unchecked")
    private HandlerPlan handlerPlan(
            Collection<ModelMetadata.HandlerMethod> selectedHandlers) {
        List<ModelMetadata.HandlerMethod> key =
                selectedHandlers instanceof List<?> list
                        ? (List<ModelMetadata.HandlerMethod>) list
                        : List.copyOf(selectedHandlers);
        return handlerPlans.computeIfAbsent(key, HandlerPlan::new);
    }

    private void invokeIfApplicable(
            ModelMetadata.HandlerMethod handler,
            DeserializingMessage message,
            ModelCommitContext context) {
        HandlerInvoker invoker = invoker(handler, message, context);
        if (invoker != null) {
            invoker.invoke();
        }
    }

    private HandlerInvoker invoker(
            ModelMetadata.HandlerMethod handler,
            DeserializingMessage message,
            ModelCommitContext context) {
        context.attachTo(message);
        Object target = invocationTarget(handler, message, context);
        return target == MissingTarget.INSTANCE
                ? null : matcher(handler).getInvokerOrNull(target, message);
    }

    private Object invocationTarget(
            ModelMetadata.HandlerMethod handler,
            DeserializingMessage message,
            ModelCommitContext context) {
        Executable executable = handler.executable();
        if (executable instanceof Constructor<?> || Modifier.isStatic(executable.getModifiers())) {
            return null;
        }
        if (handler.receiverModelType() != null) {
            Entity<?> receiver = context.resolve(handler.receiverModelType(), null);
            if (receiver == null || receiver.get() == null) {
                return MissingTarget.INSTANCE;
            }
            return receiver.get();
        }
        Object payload = message.getPayload();
        if (executable.getDeclaringClass().isInstance(payload)) {
            return payload;
        }
        throw new IllegalStateException(
                "Non-static model handler %s must be declared on the payload or a model receiver"
                        .formatted(executable.toGenericString()));
    }

    private HandlerMatcher<Object, DeserializingMessage> matcher(
            ModelMetadata.HandlerMethod handler) {
        return matchers.get(handler.kind()).computeIfAbsent(
                handler.executable(),
                ignored -> inspect(
                        handler.executable().getDeclaringClass(), parameterResolvers,
                        HandlerConfiguration.<DeserializingMessage>builder()
                                .methodAnnotation(annotationType(handler.kind()))
                                .handlerFilter((type, executable) ->
                                                       executable.equals(handler.executable()))
                                .build()));
    }

    private static Class<? extends java.lang.annotation.Annotation> annotationType(
            ModelMetadata.HandlerKind kind) {
        return switch (kind) {
            case APPLY -> Apply.class;
            case ASSERT_LEGAL -> AssertLegal.class;
            case INTERCEPT_APPLY -> InterceptApply.class;
        };
    }

    private static String resolveWriteTarget(
            ModelMetadata.HandlerMethod handler,
            Class<?> targetType,
            Object result,
            ModelCommitContext context) {
        if (result != null) {
            if (!targetType.isInstance(result)) {
                throw new IllegalStateException(
                        "Apply %s returned %s instead of %s"
                                .formatted(handler.executable().toGenericString(),
                                           result.getClass().getName(), targetType.getName()));
            }
            ModelMetadata resultMetadata = ModelMetadata.of(result.getClass());
            Object id = resultMetadata.entityId().orElseThrow().read(result);
            if (id == null) {
                throw new IllegalStateException(
                        "Apply %s returned a model with a null ID"
                                .formatted(handler.executable().toGenericString()));
            }
            return resultMetadata.parentScopedEntityId()
                    ? resultMetadata.repositoryId(id, result)
                    : resultMetadata.repositoryId(id);
        }

        Entity<?> receiver = handler.receiverModelType() == null
                ? null : context.resolve(handler.receiverModelType(), null);
        if (receiver != null && targetType.isAssignableFrom(handler.receiverModelType())) {
            return receiver.id().toString();
        }
        List<ModelMetadata.ModelParameter> candidates = handler.modelParameters().stream()
                .filter(parameter -> targetType.equals(parameter.modelType())).toList();
        if (candidates.size() == 1) {
            Entity<?> entity = context.resolve(
                    targetType, candidates.getFirst().associationProperty());
            return entity == null ? null : entity.id().toString();
        }
        Entity<?> direct = candidates.isEmpty() ? context.resolve(targetType, null) : null;
        if (direct != null) {
            return direct.id().toString();
        }
        throw new IllegalStateException(
                "Apply %s returned null but its %s delete target is ambiguous"
                        .formatted(handler.executable().toGenericString(), targetType.getName()));
    }

    private static int compareHandlers(
            ModelMetadata.HandlerMethod left, ModelMetadata.HandlerMethod right) {
        return left.executable().toGenericString().compareTo(right.executable().toGenericString());
    }

    private static int compareAssertions(
            ModelMetadata.HandlerMethod left, ModelMetadata.HandlerMethod right) {
        int priority = Integer.compare(assertionPriority(right), assertionPriority(left));
        return priority == 0 ? compareHandlers(left, right) : priority;
    }

    private static int assertionPriority(ModelMetadata.HandlerMethod handler) {
        return io.fluxzero.common.reflection.ReflectionUtils
                .<AssertLegal>getMethodAnnotation(handler.executable(), AssertLegal.class)
                .map(AssertLegal::priority).orElse(AssertLegal.DEFAULT_PRIORITY);
    }

    private static boolean assertAfterHandler(ModelMetadata.HandlerMethod handler) {
        return io.fluxzero.common.reflection.ReflectionUtils
                .<AssertLegal>getMethodAnnotation(handler.executable(), AssertLegal.class)
                .map(AssertLegal::afterHandler).orElse(false);
    }

    private static DeserializingMessage emittedMessage(
            DeserializingMessage source, Object output,
            boolean preserveSourceIdentity) {
        if (output instanceof DeserializingMessage message) {
            return message.withMetadata(
                    source.getMetadata().with(message.getMetadata()));
        }
        if (output instanceof HasMessage hasMessage) {
            Message emitted = hasMessage.toMessage();
            return source.withMessage(emitted.withMetadata(
                    source.getMetadata().with(emitted.getMetadata())));
        }
        if (preserveSourceIdentity) {
            return source.withPayload(output);
        }
        return source.withMessage(new Message(
                output, source.getMetadata(), null,
                source.getTimestamp()));
    }

    private static void enqueueOutputs(
            DeserializingMessage source,
            Object output,
            Deque<PendingSubstep> pending) {
        if (output == null || output instanceof Optional<?> optional && optional.isEmpty()) {
            return;
        }
        if (output instanceof Optional<?> optional) {
            Object value = optional.orElseThrow();
            enqueueOutput(source, value, pending,
                          value.getClass().equals(source.getPayloadClass()));
            return;
        }
        if (output instanceof List<?> outputs) {
            for (int i = outputs.size() - 1; i >= 0; i--) {
                enqueueOutput(source, outputs.get(i), pending,
                              i == 0 && outputs.get(i) != null
                              && outputs.get(i).getClass().equals(source.getPayloadClass()));
            }
            return;
        }
        if (output instanceof Collection<?> outputs) {
            List<?> ordered = new ArrayList<>(outputs);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                enqueueOutput(source, ordered.get(i), pending,
                              i == 0 && ordered.get(i) != null
                              && ordered.get(i).getClass().equals(source.getPayloadClass()));
            }
            return;
        }
        if (output instanceof Stream<?> outputs) {
            List<?> ordered = outputs.toList();
            for (int i = ordered.size() - 1; i >= 0; i--) {
                enqueueOutput(source, ordered.get(i), pending,
                              i == 0 && ordered.get(i) != null
                              && ordered.get(i).getClass().equals(source.getPayloadClass()));
            }
            return;
        }
        enqueueOutput(source, output, pending,
                      output.getClass().equals(source.getPayloadClass()));
    }

    private static void enqueueOutput(
            DeserializingMessage source,
            Object output,
            Deque<PendingSubstep> pending,
            boolean preserveSourceIdentity) {
        if (output == null) {
            throw new IllegalStateException(
                    "@InterceptApply emitted a null element; return null directly to suppress the update");
        }
        if (output instanceof Graph<?> graph) {
            List<StagedGraphChange> changes = stagedChanges(graph, source);
            for (int index = changes.size() - 1; index >= 0; index--) {
                pending.addFirst(new PendingSubstep(
                        new GraphChangeMessage(changes.get(index)), false));
            }
            return;
        }
        DeserializingMessage emitted = emittedMessage(
                source, output, preserveSourceIdentity);
        boolean reintercept =
                !emitted.getPayloadClass().equals(source.getPayloadClass());
        pending.addFirst(new PendingSubstep(emitted, reintercept));
    }

    private static List<StagedGraphChange> stagedChanges(
            Graph<?> graph,
            DeserializingMessage eventMessage) {
        Objects.requireNonNull(graph, "graph");
        List<Graphs.StagedModelChange> staged =
                Graphs.stagedChanges(graph);
        if (!staged.isEmpty()) {
            return staged.stream().map(change -> new StagedGraphChange(
                    change.modelId(), change.modelType(),
                    change.expectedStateIndex(), change.after(),
                    change.replay(), eventMessage)).toList();
        }
        if (graph.get() != null) {
            throw new IllegalStateException(
                    "@InterceptApply returned an unchanged Graph; call apply(), update(), or delete() first");
        }
        String modelId = Objects.requireNonNull(
                graph.id(), "A staged graph deletion must have a model ID").toString();
        Class<?> modelType = Objects.requireNonNull(
                graph.type(), "A staged graph deletion must have a model type");
        if (!ModelMetadata.of(modelType).isModel()) {
            throw new IllegalStateException(
                    "Staged graph deletion target %s is not an independent @Model"
                            .formatted(modelType.getName()));
        }
        return List.of(new StagedGraphChange(
                modelId, modelType, graph.stateIndex(), null,
                current -> current.update(ignored -> null),
                Objects.requireNonNull(eventMessage, "eventMessage")));
    }

    @FunctionalInterface
    interface SubstepResolver {
        ResolvedSubstep resolve(
                DeserializingMessage message,
                Long readStateIndex,
                Map<String, Object> stagedValues);

        default ResolvedSubstep resolveGraph(
                String modelId,
                Class<?> modelType,
                Long readStateIndex,
                Map<String, Object> stagedValues) {
            throw new IllegalStateException(
                    "Staged Graph results are not supported by this model commit resolver");
        }

        default void prefetch(
                List<DeserializingMessage> messages,
                long readStateIndex,
                Map<String, Object> stagedValues) {
            // Optional batch optimization.
        }
    }

    record ResolvedSubstep(
            ModelCommitContext context, List<ModelMetadata.HandlerMethod> handlers) {
        ResolvedSubstep {
            Objects.requireNonNull(context, "context");
            handlers = List.copyOf(handlers);
        }
    }

    record CommitEvaluation(
            long readStateIndex,
            List<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            List<AppliedSubstep> substeps,
            Map<String, Object> finalValues,
            Set<String> cascadeRootIds) {
        CommitEvaluation(
                long readStateIndex,
                List<String> readModelIds,
                Map<String, Class<?>> readModelTypes,
                List<AppliedSubstep> substeps,
                Map<String, Object> finalValues) {
            this(
                    readStateIndex, readModelIds, readModelTypes,
                    substeps, finalValues, Set.of());
        }

        CommitEvaluation {
            readModelIds = List.copyOf(readModelIds);
            readModelTypes = Map.copyOf(readModelTypes);
            substeps = List.copyOf(substeps);
            cascadeRootIds = Set.copyOf(cascadeRootIds);
            if (finalValues.isEmpty()) {
                finalValues = Map.of();
            } else if (finalValues.size() == 1) {
                Map.Entry<String, Object> entry =
                        finalValues.entrySet().iterator().next();
                finalValues = Collections.singletonMap(
                        entry.getKey(), entry.getValue());
            } else {
                finalValues = Collections.unmodifiableMap(
                        new LinkedHashMap<>(finalValues));
            }
        }

        List<Transition> transitions() {
            if (substeps.isEmpty()) {
                return List.of();
            }
            if (substeps.size() == 1) {
                return substeps.getFirst().transitions();
            }
            List<Transition> result = new ArrayList<>();
            for (AppliedSubstep substep : substeps) {
                result.addAll(substep.transitions());
            }
            return List.copyOf(result);
        }
    }

    record AppliedSubstep(
            DeserializingMessage message, List<Transition> transitions) {
        AppliedSubstep {
            transitions = List.copyOf(transitions);
        }
    }

    record StagedGraphChange(
            String modelId,
            Class<?> modelType,
            Long expectedStateIndex,
            Object after,
            Graphs.StagedReplay replay,
            DeserializingMessage eventMessage) {
        StagedGraphChange {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(modelType, "modelType");
            Objects.requireNonNull(eventMessage, "eventMessage");
            Objects.requireNonNull(replay, "replay");
        }

        StagedGraphChange forRebase() {
            return new StagedGraphChange(
                    modelId, modelType, null, null,
                    replay, eventMessage);
        }
    }

    static DeserializingMessage graphChangeReplay(
            DeserializingMessage eventMessage,
            String modelId,
            Class<?> modelType,
            Graphs.StagedReplay replay) {
        return new GraphChangeMessage(new StagedGraphChange(
                modelId, modelType, null, null,
                replay, eventMessage));
    }

    record Evaluation(
            ModelCommitContext beginState,
            ModelCommitContext resultingState,
            List<Transition> transitions) {
        Evaluation {
            transitions = List.copyOf(transitions);
        }
    }

    record SingleTargetEvaluation(boolean applied, Object value) {
    }

    record DirectSingleTargetApply(
            MemberInvoker invoker,
            boolean receiver) {
    }

    record Transition(
            String modelId,
            Class<?> modelType,
            long beforeSequenceNumber,
            Long beforeLastEventIndex,
            Object before,
            Object after,
            Executable handler,
            Graphs.StagedReplay stagedReplay,
            boolean cascadedDeletion) {
        Transition(
                String modelId,
                Class<?> modelType,
                long beforeSequenceNumber,
                Long beforeLastEventIndex,
                Object before,
                Object after,
                Executable handler) {
            this(
                    modelId, modelType, beforeSequenceNumber,
                    beforeLastEventIndex, before, after, handler, null, false);
        }

        Transition(
                String modelId,
                Class<?> modelType,
                long beforeSequenceNumber,
                Long beforeLastEventIndex,
                Object before,
                Object after,
                Executable handler,
                boolean cascadedDeletion) {
            this(
                    modelId, modelType, beforeSequenceNumber,
                    beforeLastEventIndex, before, after, handler,
                    null, cascadedDeletion);
        }

        Transition(
                String modelId,
                Class<?> modelType,
                long beforeSequenceNumber,
                Object before,
                Object after,
                Executable handler) {
            this(
                    modelId, modelType, beforeSequenceNumber,
                    null, before, after, handler, null, false);
        }
    }

    private static final class GraphChangeMessage extends DeserializingMessage {
        private final StagedGraphChange change;

        private GraphChangeMessage(StagedGraphChange change) {
            super(change.eventMessage());
            this.change = change;
        }
    }

    private record PendingSubstep(
            DeserializingMessage message, boolean interceptionAllowed) {
    }

    private record HandlerPlan(
            List<ModelMetadata.HandlerMethod> beforeAssertions,
            List<ModelMetadata.HandlerMethod> afterAssertions,
            List<ModelMetadata.HandlerMethod> applies,
            List<ModelMetadata.HandlerMethod> interceptors) {
        private HandlerPlan(List<ModelMetadata.HandlerMethod> handlers) {
            this(assertions(handlers, false), assertions(handlers, true),
                 handlers(handlers, ModelMetadata.HandlerKind.APPLY),
                 handlers(handlers, ModelMetadata.HandlerKind.INTERCEPT_APPLY));
        }

        private static List<ModelMetadata.HandlerMethod> assertions(
                List<ModelMetadata.HandlerMethod> handlers, boolean afterHandler) {
            List<ModelMetadata.HandlerMethod> result = new ArrayList<>();
            for (int i = 0; i < handlers.size(); i++) {
                ModelMetadata.HandlerMethod handler = handlers.get(i);
                if (handler.kind() == ModelMetadata.HandlerKind.ASSERT_LEGAL
                    && assertAfterHandler(handler) == afterHandler) {
                    result.add(handler);
                }
            }
            result.sort(ModelCommitEngine::compareAssertions);
            return List.copyOf(result);
        }

        private static List<ModelMetadata.HandlerMethod> handlers(
                List<ModelMetadata.HandlerMethod> handlers,
                ModelMetadata.HandlerKind kind) {
            List<ModelMetadata.HandlerMethod> result = new ArrayList<>();
            for (int i = 0; i < handlers.size(); i++) {
                ModelMetadata.HandlerMethod handler = handlers.get(i);
                if (handler.kind() == kind) {
                    result.add(handler);
                }
            }
            result.sort(ModelCommitEngine::compareHandlers);
            return List.copyOf(result);
        }
    }

    private enum MissingTarget {
        INSTANCE
    }
}
