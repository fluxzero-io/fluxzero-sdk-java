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
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static io.fluxzero.common.handling.HandlerInspector.inspect;

/**
 * Evaluates a complete model action without performing persistence.
 * <p>
 * Interceptor outputs form ordered substeps under one pinned read boundary. Every apply within one substep reads its
 * same immutable begin-state; only a successfully completed substep becomes visible to later substeps. A failure
 * produces no action result. This class never publishes or stores an event, allowing its caller to commit the returned
 * action atomically after evaluation.
 */
final class ModelActionEngine {
    private static final int MAX_SUBSTEPS = 10_000;

    private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    private final Map<ModelMetadata.HandlerKind,
            Map<Executable, HandlerMatcher<Object, DeserializingMessage>>> matchers;
    private final Map<List<ModelMetadata.HandlerMethod>, HandlerPlan> handlerPlans =
            new ConcurrentHashMap<>();

    ModelActionEngine(List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
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
            ModelActionContext beginState,
            Collection<ModelMetadata.HandlerMethod> selectedHandlers) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(beginState, "beginState");
        Objects.requireNonNull(selectedHandlers, "selectedHandlers");
        beginState.attachTo(message);
        try {
            return message.apply(
                    ignored -> evaluateInContext(message, beginState, selectedHandlers));
        } finally {
            beginState.attachTo(message);
        }
    }

    ActionEvaluation evaluate(
            DeserializingMessage initialMessage, SubstepResolver resolver) {
        Objects.requireNonNull(initialMessage, "initialMessage");
        Deque<PendingSubstep> pending = new ArrayDeque<>();
        pending.add(new PendingSubstep(initialMessage, true));
        return evaluate(pending, resolver, initialMessage);
    }

    private ActionEvaluation evaluate(
            Deque<PendingSubstep> pending,
            SubstepResolver resolver,
            DeserializingMessage initialMessage) {
        Objects.requireNonNull(resolver, "resolver");
        Map<String, Object> stagedValues = new LinkedHashMap<>();
        LinkedHashSet<String> readModelIds = new LinkedHashSet<>();
        Map<String, Class<?>> readModelTypes =
                new LinkedHashMap<>();
        List<AppliedSubstep> appliedSubsteps = new ArrayList<>();
        long readStateIndex = -1L;
        boolean stateIndexPinned = false;
        ModelActionContext originalContext = initialMessage == null ? null
                : initialMessage.getContext(ModelActionContext.class).orElse(null);
        ModelActionContext actionBeginContext = null;
        int processed = 0;

        try {
            while (!pending.isEmpty()) {
                if (++processed > MAX_SUBSTEPS) {
                    throw new IllegalStateException(
                            "Model action exceeded %d interceptor substeps".formatted(MAX_SUBSTEPS));
                }
                PendingSubstep current = pending.removeFirst();
                ResolvedSubstep resolved = Objects.requireNonNull(
                        resolver.resolve(
                                current.message(),
                                stateIndexPinned ? readStateIndex : null,
                                stagedValues),
                        "Substep resolver returned null");
                if (!stateIndexPinned) {
                    readStateIndex = resolved.context().readStateIndex();
                    stateIndexPinned = true;
                    actionBeginContext = resolved.context();
                } else if (resolved.context().readStateIndex() != readStateIndex) {
                    throw new IllegalStateException(
                            "Substep loaded at state index %d while action is pinned at %d"
                                    .formatted(resolved.context().readStateIndex(), readStateIndex));
                }
                ModelActionContext context = resolved.context().withValues(stagedValues);
                resolved.context().entries().forEach(entry -> {
                    readModelIds.add(entry.target().modelId());
                    readModelTypes.putIfAbsent(
                            entry.target().modelId(),
                            entry.target().modelType());
                });
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

                Evaluation evaluation = evaluate(current.message(), context, resolved.handlers());
                for (Transition transition : evaluation.transitions()) {
                    stagedValues.put(transition.modelId(), transition.after());
                }
                appliedSubsteps.add(new AppliedSubstep(
                        current.message(), evaluation.transitions()));
            }
            return new ActionEvaluation(
                    readStateIndex, List.copyOf(readModelIds),
                    readModelTypes, appliedSubsteps,
                    stagedValues);
        } finally {
            ModelActionContext restore =
                    originalContext == null ? actionBeginContext : originalContext;
            if (restore != null && initialMessage != null) {
                restore.attachTo(initialMessage);
            }
        }
    }

    /**
     * Re-applies already produced action events against a new pinned model boundary.
     * <p>
     * Command handling, assertions, and interceptors are deliberately not invoked. The supplied messages are the
     * original post-interception substeps and only their {@link Apply @Apply} handlers contribute new derived state.
     */
    ActionEvaluation rebase(
            List<DeserializingMessage> appliedMessages,
            SubstepResolver resolver) {
        Objects.requireNonNull(appliedMessages, "appliedMessages");
        if (appliedMessages.isEmpty()) {
            throw new IllegalArgumentException(
                    "A model action rebase requires at least one applied message");
        }
        Deque<PendingSubstep> pending = new ArrayDeque<>(appliedMessages.size());
        appliedMessages.forEach(message -> pending.add(new PendingSubstep(message, false)));
        return evaluate(pending, resolver, null);
    }

    private Evaluation evaluateInContext(
            DeserializingMessage message,
            ModelActionContext beginState,
            Collection<ModelMetadata.HandlerMethod> selectedHandlers) {
        HandlerPlan plan = handlerPlan(selectedHandlers);

        for (int i = 0; i < plan.beforeAssertions().size(); i++) {
            invokeIfApplicable(plan.beforeAssertions().get(i), message, beginState);
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
            ModelActionContext.Entry target = beginState.entry(targetId);
            if (target == null || !beginState.mayWrite(
                    targetId, targetType, handler.executable().toGenericString())) {
                throw new IllegalStateException(
                        "Apply %s returned model '%s', which is not a resolved write target"
                                .formatted(handler.executable().toGenericString(), targetId));
            }
            Transition transition = new Transition(
                    targetId, targetType,
                    target.entity() instanceof ModelRoot<?> modelRoot
                            ? modelRoot.sequenceNumber() : -1L,
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
        ModelActionContext resultingState;
        if (transitions == null) {
            transitionList = List.of();
            resultingState = beginState;
        } else {
            Map<String, Object> values = new LinkedHashMap<>(transitions.size());
            transitions.forEach((id, transition) -> values.put(id, transition.after()));
            transitionList = List.copyOf(transitions.values());
            resultingState = beginState.withValues(values);
        }
        for (int i = 0; i < plan.afterAssertions().size(); i++) {
            invokeIfApplicable(plan.afterAssertions().get(i), message, resultingState);
        }
        return new Evaluation(
                beginState, resultingState, transitionList);
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
            ModelActionContext context) {
        HandlerInvoker invoker = invoker(handler, message, context);
        if (invoker != null) {
            invoker.invoke();
        }
    }

    private HandlerInvoker invoker(
            ModelMetadata.HandlerMethod handler,
            DeserializingMessage message,
            ModelActionContext context) {
        context.attachTo(message);
        Object target = invocationTarget(handler, message, context);
        return target == MissingTarget.INSTANCE
                ? null : matcher(handler).getInvokerOrNull(target, message);
    }

    private Object invocationTarget(
            ModelMetadata.HandlerMethod handler,
            DeserializingMessage message,
            ModelActionContext context) {
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
            ModelActionContext context) {
        if (result != null) {
            if (!targetType.isInstance(result)) {
                throw new IllegalStateException(
                        "Apply %s returned %s instead of %s"
                                .formatted(handler.executable().toGenericString(),
                                           result.getClass().getName(), targetType.getName()));
            }
            Object id = ModelMetadata.of(result.getClass()).entityId().orElseThrow().read(result);
            String idString = id == null ? null : id.toString();
            if (idString == null) {
                throw new IllegalStateException(
                        "Apply %s returned a model with a null ID"
                                .formatted(handler.executable().toGenericString()));
            }
            return idString;
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
            DeserializingMessage source, Object output) {
        if (output instanceof DeserializingMessage message) {
            return message.withMetadata(
                    source.getMetadata().with(message.getMetadata()));
        }
        if (output instanceof HasMessage hasMessage) {
            Message emitted = hasMessage.toMessage();
            return source.withPayload(emitted.getPayload())
                    .withMetadata(source.getMetadata().with(emitted.getMetadata()));
        }
        return source.withPayload(output);
    }

    private static void enqueueOutputs(
            DeserializingMessage source,
            Object output,
            Deque<PendingSubstep> pending) {
        if (output == null || output instanceof Optional<?> optional && optional.isEmpty()) {
            return;
        }
        if (output instanceof Optional<?> optional) {
            enqueueOutput(source, optional.orElseThrow(), pending);
            return;
        }
        if (output instanceof List<?> outputs) {
            for (int i = outputs.size() - 1; i >= 0; i--) {
                enqueueOutput(source, outputs.get(i), pending);
            }
            return;
        }
        if (output instanceof Collection<?> outputs) {
            List<?> ordered = new ArrayList<>(outputs);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                enqueueOutput(source, ordered.get(i), pending);
            }
            return;
        }
        if (output instanceof Stream<?> outputs) {
            List<?> ordered = outputs.toList();
            for (int i = ordered.size() - 1; i >= 0; i--) {
                enqueueOutput(source, ordered.get(i), pending);
            }
            return;
        }
        enqueueOutput(source, output, pending);
    }

    private static void enqueueOutput(
            DeserializingMessage source,
            Object output,
            Deque<PendingSubstep> pending) {
        if (output == null) {
            throw new IllegalStateException(
                    "@InterceptApply emitted a null element; return null directly to suppress the update");
        }
        DeserializingMessage emitted = emittedMessage(source, output);
        boolean reintercept =
                !emitted.getPayloadClass().equals(source.getPayloadClass());
        pending.addFirst(new PendingSubstep(emitted, reintercept));
    }

    @FunctionalInterface
    interface SubstepResolver {
        ResolvedSubstep resolve(
                DeserializingMessage message,
                Long readStateIndex,
                Map<String, Object> stagedValues);

        default void prefetch(
                List<DeserializingMessage> messages,
                long readStateIndex,
                Map<String, Object> stagedValues) {
            // Optional batch optimization.
        }
    }

    record ResolvedSubstep(
            ModelActionContext context, List<ModelMetadata.HandlerMethod> handlers) {
        ResolvedSubstep {
            Objects.requireNonNull(context, "context");
            handlers = List.copyOf(handlers);
        }
    }

    record ActionEvaluation(
            long readStateIndex,
            List<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            List<AppliedSubstep> substeps,
            Map<String, Object> finalValues) {
        ActionEvaluation {
            readModelIds = List.copyOf(readModelIds);
            readModelTypes =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    readModelTypes));
            substeps = List.copyOf(substeps);
            finalValues = Collections.unmodifiableMap(new LinkedHashMap<>(finalValues));
        }

        List<Transition> transitions() {
            return substeps.stream().map(AppliedSubstep::transitions)
                    .flatMap(Collection::stream).toList();
        }
    }

    record AppliedSubstep(
            DeserializingMessage message, List<Transition> transitions) {
        AppliedSubstep {
            transitions = List.copyOf(transitions);
        }
    }

    record Evaluation(
            ModelActionContext beginState,
            ModelActionContext resultingState,
            List<Transition> transitions) {
        Evaluation {
            transitions = List.copyOf(transitions);
        }
    }

    record Transition(
            String modelId,
            Class<?> modelType,
            long beforeSequenceNumber,
            Object before,
            Object after,
            Executable handler) {
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
            result.sort(ModelActionEngine::compareAssertions);
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
            result.sort(ModelActionEngine::compareHandlers);
            return List.copyOf(result);
        }
    }

    private enum MissingTarget {
        INSTANCE
    }
}
