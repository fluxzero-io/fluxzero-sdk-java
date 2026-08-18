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
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.fluxzero.common.handling.HandlerInspector.inspect;
import static io.fluxzero.common.ObjectUtils.asStream;

/**
 * Immutable payload plan and its shared compiler for evaluating model commits without performing persistence.
 * <p>
 * Interceptor outputs form ordered substeps under one pinned read boundary. Every apply within one substep reads its
 * same immutable begin-state; only a successfully completed substep becomes visible to later substeps. A failure
 * produces no commit result. This class never publishes or stores an event, allowing its caller to commit the returned
 * commit atomically after evaluation.
 */
public final class ModelExecutionPlan {
    private final Compiler compiler;
    private final HandlerPlan handlers;
    private final ModelTargetResolver.TargetPlan targets;
    private final DirectSingleTargetApply directApply;
    private final ModelCommitPolicy commitPolicy;
    private final boolean commit;
    private final boolean automatic;

    ModelExecutionPlan(
            Compiler compiler,
            HandlerPlan handlers,
            ModelTargetResolver.TargetPlan targets,
            DirectSingleTargetApply directApply,
            ModelCommitPolicy commitPolicy,
            boolean commit,
            boolean automatic) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.directApply = directApply;
        this.commitPolicy = Objects.requireNonNull(commitPolicy, "commitPolicy");
        this.commit = commit;
        this.automatic = automatic;
    }

    HandlerPlan handlers() {
        return handlers;
    }

    ModelTargetResolver.TargetPlan targets() {
        return targets;
    }

    DirectSingleTargetApply directApply() {
        return directApply;
    }

    ModelCommitPolicy commitPolicy() {
        return commitPolicy;
    }

    boolean commit() {
        return commit;
    }

    boolean automatic() {
        return automatic;
    }

    /** Whether this plan contains no model handlers. */
    public boolean empty() {
        return handlers.all().isEmpty();
    }

    /** Whether this plan can replay through the precompiled direct single-target strategy. */
    public boolean direct() {
        return directApply != null;
    }

    /** Target accessors used when reconstructing one persisted model stream. */
    public ModelTargetResolver.TargetPlan replayTargets() {
        return targets;
    }

    CommitEvaluation execute(
            List<DeserializingMessage> messages,
            SubstepResolver resolver,
            ExecutionMode mode) {
        return compiler.execute(messages, resolver, mode);
    }

    CommitEvaluation evaluateDirectSingleTarget(
            DeserializingMessage message,
            long readStateIndex,
            String modelId,
            Class<?> modelType,
            Entity<?> entity) {
        return compiler.evaluateDirectSingleTarget(
                message, readStateIndex, modelId, modelType,
                entity, handlers, directApply);
    }

    public Object replay(
            DeserializingMessage event,
            ModelCommitContext context,
            String targetModelId) {
        return compiler.replay(event, context, this, targetModelId);
    }

    /** Compiles payload plans and evaluates their ordered substeps. */
    public static final class Compiler {
    private static final int MAX_SUBSTEPS = 10_000;

    private final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers;
    public Compiler(List<ParameterResolver<? super DeserializingMessage>> parameterResolvers) {
        List<ParameterResolver<? super DeserializingMessage>> resolvers =
                new ArrayList<>(parameterResolvers.size() + 1);
        @SuppressWarnings("unchecked")
        ParameterResolver<? super DeserializingMessage> modelResolver =
                (ParameterResolver<? super DeserializingMessage>) (ParameterResolver<?>)
                        new ModelEntityParameterResolver();
        resolvers.add(modelResolver);
        resolvers.addAll(parameterResolvers);
        this.parameterResolvers = List.copyOf(resolvers);
    }

    List<Transition> evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            Collection<EntityMetadata.HandlerMethod> selectedHandlers) {
        return evaluate(message, beginState, selectedHandlers, true, null);
    }

    List<Transition> evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            Collection<EntityMetadata.HandlerMethod> selectedHandlers,
            DirectSingleTargetApply directApply) {
        return evaluate(message, beginState, selectedHandlers, true, directApply);
    }

    private List<Transition> evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            Collection<EntityMetadata.HandlerMethod> selectedHandlers,
            boolean applyHandlers,
            DirectSingleTargetApply directApply) {
        return evaluate(
                message, beginState, compileHandlers(selectedHandlers),
                applyHandlers, directApply);
    }

    private List<Transition> evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            HandlerPlan handlers,
            boolean applyHandlers,
            DirectSingleTargetApply directApply) {
        return evaluate(
                message, beginState, handlers,
                applyHandlers, true, directApply);
    }

    private List<Transition> evaluate(
            DeserializingMessage message,
            ModelCommitContext beginState,
            HandlerPlan handlers,
            boolean applyHandlers,
            boolean assertions,
            DirectSingleTargetApply directApply) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(beginState, "beginState");
        Objects.requireNonNull(handlers, "handlers");
        beginState.attachTo(message);
        try {
            return message.apply(
                    ignored -> evaluateInContext(
                            message, beginState, handlers,
                            applyHandlers, assertions, directApply));
        } finally {
            beginState.attachTo(message);
        }
    }

    /** Executes live, validation-only and stored/retry forms through one ordered substep pipeline. */
    CommitEvaluation execute(
            DeserializingMessage message,
            SubstepResolver resolver,
            ExecutionMode mode) {
        return execute(List.of(message), resolver, mode);
    }

    CommitEvaluation evaluate(
            DeserializingMessage message,
            SubstepResolver resolver) {
        return execute(message, resolver, ExecutionMode.LIVE);
    }

    /** Executes live, validation-only and stored/retry forms through one ordered substep pipeline. */
    CommitEvaluation execute(
            List<DeserializingMessage> messages,
            SubstepResolver resolver,
            ExecutionMode mode) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(mode, "mode");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A model execution requires at least one message");
        }
        Deque<PendingSubstep> pending = new ArrayDeque<>(messages.size());
        for (DeserializingMessage message : messages) {
            Objects.requireNonNull(message, "message");
            if (mode == ExecutionMode.LIVE && message.getPayload() instanceof Graph<?> graph) {
                enqueueOutput(message, graph, pending, false);
            } else if (mode == ExecutionMode.REPLAY && message instanceof GraphChangeMessage changeMessage) {
                pending.add(new PendingSubstep(
                        new GraphChangeMessage(changeMessage.change.forRebase()), false));
            } else {
                pending.add(new PendingSubstep(message, mode.interception()));
            }
        }
        return evaluate(
                pending, resolver,
                mode == ExecutionMode.REPLAY ? null : messages.getFirst(),
                mode.applies(), mode.assertions());
    }

    private CommitEvaluation evaluate(
            Deque<PendingSubstep> pending,
            SubstepResolver resolver,
            DeserializingMessage initialMessage,
            boolean applyHandlers,
            boolean assertions) {
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
                                        stateIndexPinned
                                                ? Long.valueOf(readStateIndex)
                                                : graphChangeMessage.change.expectedStateIndex(),
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
                            context, readStateIndex,
                            stagedValues.containsKey(
                                    graphChangeMessage.change.modelId()));
                    stagedValues.put(
                            change.transitions().getFirst().modelId(),
                            change.transitions().getFirst().after());
                    mergeAppliedSubstep(appliedSubsteps, change);
                    continue;
                }
                List<CompiledHandler> interceptors =
                        resolved.handlers().interceptors();
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
                        enqueueOutputs(current.message(), intercepted, pending);
                        resolver.prefetch(
                                pending.stream()
                                        .map(PendingSubstep::message)
                                        .filter(message -> !(message instanceof GraphChangeMessage))
                                        .toList(),
                                readStateIndex, stagedValues);
                        continue;
                    }
                }

                List<Transition> transitions = evaluate(
                        current.message(), context,
                        resolved.handlers(), applyHandlers,
                        assertions, resolved.directApply());
                for (Transition transition : transitions) {
                    stagedValues.put(transition.modelId(), transition.after());
                    if (!readModelTypes.containsKey(transition.modelId())) {
                        readModelIds.add(transition.modelId());
                        readModelTypes.putIfAbsent(
                                transition.modelId(),
                                transition.modelType());
                    }
                }
                appliedSubsteps.add(new AppliedSubstep(
                        current.message(), transitions));
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
            long readStateIndex,
            boolean alreadyStaged) {
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
        Object after = change.expectedStateIndex() == null || alreadyStaged
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
            LinkedHashMap<String, Transition> transitions =
                    new LinkedHashMap<>();
            existing.transitions().forEach(
                    transition -> mergeGraphTransition(
                            transitions, transition));
            addition.transitions().forEach(
                    transition -> mergeGraphTransition(
                            transitions, transition));
            appliedSubsteps.set(i, new AppliedSubstep(
                    existing.message(), List.copyOf(transitions.values())));
            return;
        }
        appliedSubsteps.add(addition);
    }

    private static void mergeGraphTransition(
            Map<String, Transition> transitions,
            Transition addition) {
        Transition previous = transitions.get(addition.modelId());
        if (previous == null) {
            transitions.put(addition.modelId(), addition);
            return;
        }
        if (!Objects.equals(previous.modelType(), addition.modelType())) {
            throw new IllegalStateException(
                    "Graph changes target repository id '%s' as both %s and %s"
                            .formatted(
                                    addition.modelId(),
                                    previous.modelType().getName(),
                                    addition.modelType().getName()));
        }
        Graphs.StagedReplay previousReplay = Objects.requireNonNull(
                previous.stagedReplay(), "Merged graph transition has no replay");
        Graphs.StagedReplay additionReplay = Objects.requireNonNull(
                addition.stagedReplay(), "Merged graph transition has no replay");
        transitions.put(addition.modelId(), new Transition(
                previous.modelId(), previous.modelType(),
                previous.beforeSequenceNumber(), previous.beforeLastEventIndex(),
                previous.before(), addition.after(), addition.handler(),
                current -> additionReplay.apply(previousReplay.apply(current)),
                addition.cascadedDeletion()));
    }

    private List<Transition> evaluateInContext(
            DeserializingMessage message,
            ModelCommitContext beginState,
            HandlerPlan plan,
            boolean applyHandlers,
            boolean assertions,
            DirectSingleTargetApply directApply) {
        if (assertions) {
            for (int i = 0; i < plan.beforeAssertions().size(); i++) {
                invokeIfApplicable(plan.beforeAssertions().get(i), message, beginState);
            }
        }

        if (!applyHandlers) {
            return List.of();
        }

        Map<String, Transition> transitions = null;
        for (CompiledHandler compiledHandler : plan.applies()) {
            EntityMetadata.HandlerMethod handler = compiledHandler.method();
            ApplyResultShape resultShape = compiledHandler.resultShape();
            if (!resultShape.present()) {
                continue;
            }
            Object result;
            if (directApply != null && plan.applies().size() == 1) {
                Object receiver = directApply.receiver()
                        ? invocationTarget(handler, message, beginState)
                        : null;
                if (receiver == MissingTarget.INSTANCE) {
                    continue;
                }
                result = directApply.invoker().invoke(
                        receiver, (Object) message.getPayload());
            } else {
                HandlerInvoker invoker = invoker(
                        compiledHandler, message, beginState);
                if (invoker == null) {
                    continue;
                }
                result = invoker.invoke();
            }
            List<?> results = resultShape.values(handler, result);
            for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
                transitions = addApplyResult(
                        transitions, compiledHandler,
                        results.get(resultIndex), resultIndex,
                        beginState);
            }
        }
        List<Transition> transitionList;
        if (transitions == null) {
            transitionList = List.of();
        } else {
            Map<String, Object> values = new LinkedHashMap<>(transitions.size());
            transitions.forEach((id, transition) -> values.put(id, transition.after()));
            transitionList = List.copyOf(transitions.values());
            ModelCommitContext resultingState = beginState.withValues(values);
            if (assertions) {
                for (int i = 0; i < plan.afterAssertions().size(); i++) {
                    invokeIfApplicable(plan.afterAssertions().get(i), message, resultingState);
                }
            }
        }
        return transitionList;
    }

    public static DirectSingleTargetApply directSingleTargetApply(
            EntityMetadata.HandlerMethod handler,
            Class<?> payloadType) {
        if (handler.kind() != EntityMetadata.HandlerKind.APPLY
            || handler.targetModelTypes().size() != 1
            || handler.collectionApplyResult()
            || handler.dynamicApplyResult()
            || !handler.modelParameters().isEmpty()
            || handler.executable().getParameterCount() != 1) {
            return null;
        }
        Executable executable = handler.executable();
        Parameter parameter = executable.getParameters()[0];
        if (parameter.getAnnotations().length != 0
            || !parameter.getType().isAssignableFrom(payloadType)) {
            return null;
        }
        boolean receiver = !(executable instanceof Constructor<?>)
                           && !Modifier.isStatic(executable.getModifiers());
        if (receiver && handler.receiverModelType() == null) {
            return null;
        }
        MemberInvoker invoker = ReflectionUtils.getTypeMetadata(
                        executable.getDeclaringClass())
                .invoker(executable, true);
        return new DirectSingleTargetApply(invoker, receiver);
    }

    CommitEvaluation evaluateDirectSingleTarget(
            DeserializingMessage message,
            long readStateIndex,
            String modelId,
            Class<?> modelType,
            Entity<?> entity,
            HandlerPlan plan,
            DirectSingleTargetApply directApply) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelType, "modelType");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(directApply, "directApply");
        if (plan.applies().size() != 1) {
            throw new IllegalArgumentException(
                    "Direct single-target evaluation requires one compiled apply");
        }
        EntityMetadata.HandlerMethod handler =
                plan.applies().getFirst().method();
        Object before = entity.get();
        if (directApply.receiver() && before == null) {
            return null;
        }
        Object after = message.apply(ignored ->
                directApply.invoker().invoke(
                        directApply.receiver() ? before : null,
                        (Object) message.getPayload()));
        validateDirectSingleTargetResult(
                handler, modelId, modelType, after);
        Transition transition = new Transition(
                modelId,
                modelType,
                entity instanceof ModelRoot<?> root
                        ? root.sequenceNumber() : -1L,
                entity instanceof ModelRoot<?> root
                        ? root.lastEventIndex() : null,
                before,
                after,
                handler.executable(),
                null,
                false);
        return new CommitEvaluation(
                readStateIndex,
                List.of(modelId),
                Map.of(modelId, modelType),
                List.of(new AppliedSubstep(
                        message, List.of(transition))),
                Collections.singletonMap(
                        modelId, after));
    }

    private static void validateDirectSingleTargetResult(
            EntityMetadata.HandlerMethod handler,
            String expectedTargetId,
            Class<?> targetType,
            Object result) {
        if (result == null) {
            return;
        }
        if (!targetType.isInstance(result)) {
            throw new IllegalStateException(
                    "Apply %s returned %s instead of %s"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    result.getClass().getName(),
                                    targetType.getName()));
        }
        EntityMetadata metadata = EntityMetadata.of(result.getClass());
        Object resultId = metadata.entityId().orElseThrow().read(result);
        if (resultId == null) {
            throw new IllegalStateException(
                    "Apply %s returned a model with a null ID"
                            .formatted(
                                    handler.executable().toGenericString()));
        }
        String repositoryId = metadata.parentScopedEntityId()
                ? metadata.repositoryId(resultId, result)
                : metadata.repositoryId(resultId);
        if (!expectedTargetId.equals(repositoryId)) {
            throw new IllegalStateException(
                    "Apply %s returned model '%s', which is not replay target '%s'"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    resultId,
                                    expectedTargetId));
        }
    }

    /** Replays one stored event through the same compiled apply evaluator used for live model commits. */
    public Object replay(
            DeserializingMessage event,
            ModelCommitContext context,
            ModelExecutionPlan plan,
            String targetModelId) {
        Objects.requireNonNull(targetModelId, "targetModelId");
        List<Transition> transitions = evaluate(
                event, context, plan.handlers, true, false,
                plan.directApply());
        Transition selected = null;
        for (Transition transition : transitions) {
            if (!targetModelId.equals(transition.modelId())) {
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException(
                        "Stored model event produced more than one transition for " + targetModelId);
            }
            selected = transition;
        }
        if (selected == null) {
            throw new IllegalStateException(
                    "Stored model event produced no transition for " + targetModelId);
        }
        return selected.after();
    }

    /** Compiles target access and the optional direct strategy once for one replay payload/model pair. */
    public ModelExecutionPlan compileReplay(
            Class<?> payloadType,
            Class<?> modelType,
            List<EntityMetadata.HandlerMethod> handlers) {
        DirectSingleTargetApply direct = handlers.size() == 1
                && handlers.getFirst().targetModelTypes().size() == 1
                && compatible(
                        handlers.getFirst().targetModelTypes().getFirst(),
                        modelType)
                ? directSingleTargetApply(handlers.getFirst(), payloadType)
                : null;
        HandlerPlan compiledHandlers = compileHandlers(handlers);
        ModelTargetResolver.TargetPlan targets =
                ModelTargetResolver.compile(payloadType, handlers);
        return new ModelExecutionPlan(
                this, compiledHandlers, targets, direct,
                ModelCommitPolicy.SYNC_AFTER_HANDLER,
                !handlers.isEmpty(), false);
    }

    private static boolean compatible(Class<?> left, Class<?> right) {
        return left.isAssignableFrom(right) || right.isAssignableFrom(left);
    }

    HandlerPlan compileHandlers(
            Collection<EntityMetadata.HandlerMethod> selectedHandlers) {
        @SuppressWarnings("unchecked")
        List<EntityMetadata.HandlerMethod> handlers =
                selectedHandlers instanceof List<?> list
                        ? (List<EntityMetadata.HandlerMethod>) list
                        : List.copyOf(selectedHandlers);
        return new HandlerPlan(handlers, this);
    }

    private void invokeIfApplicable(
            CompiledHandler handler,
            DeserializingMessage message,
            ModelCommitContext context) {
        HandlerInvoker invoker = invoker(handler, message, context);
        if (invoker != null) {
            invoker.invoke();
        }
    }

    private HandlerInvoker invoker(
            CompiledHandler compiledHandler,
            DeserializingMessage message,
            ModelCommitContext context) {
        context.attachTo(message);
        EntityMetadata.HandlerMethod handler = compiledHandler.method();
        Object target = invocationTarget(handler, message, context);
        return target == MissingTarget.INSTANCE
                ? null : compiledHandler.matcher().getInvokerOrNull(target, message);
    }

    private Object invocationTarget(
            EntityMetadata.HandlerMethod handler,
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

    private HandlerMatcher<Object, DeserializingMessage> compileMatcher(
            EntityMetadata.HandlerMethod handler) {
        return inspect(
                        handler.executable().getDeclaringClass(), parameterResolvers,
                        HandlerConfiguration.<DeserializingMessage>builder()
                                .methodAnnotation(annotationType(handler.kind()))
                                .handlerFilter((type, executable) ->
                                                       executable.equals(handler.executable()))
                                .build());
    }

    private static Class<? extends java.lang.annotation.Annotation> annotationType(
            EntityMetadata.HandlerKind kind) {
        return switch (kind) {
            case APPLY -> Apply.class;
            case ASSERT_LEGAL -> AssertLegal.class;
            case INTERCEPT_APPLY -> InterceptApply.class;
        };
    }

    private static String resolveWriteTarget(
            EntityMetadata.HandlerMethod handler,
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
            EntityMetadata resultMetadata = EntityMetadata.of(result.getClass());
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
        List<EntityMetadata.ModelParameter> candidates = handler.modelParameters().stream()
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

    private Map<String, Transition> addApplyResult(
            Map<String, Transition> transitions,
            CompiledHandler compiledHandler,
            Object value,
            int resultIndex,
            ModelCommitContext beginState) {
        EntityMetadata.HandlerMethod handler = compiledHandler.method();
        Class<?> targetType = compiledHandler.resultShape()
                .targetType(handler, value, resultIndex);
        String targetId = resolveWriteTarget(
                handler, targetType, value, beginState);
        ModelCommitContext.Entry target =
                beginState.entry(targetId);
        boolean creation = target == null && value != null
                           && compiledHandler.resultShape().createsTargets();
        if (!creation && (target == null || !beginState.mayWrite(
                targetId, targetType,
                handler.executable()))) {
            throw new IllegalStateException(
                    "Apply %s returned model '%s', which is not a resolved write target"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    targetId));
        }
        Class<?> resolvedTargetType = target == null
                ? value.getClass() : target.target().modelType();
        if (value != null
            && !resolvedTargetType.isInstance(value)) {
            throw new IllegalStateException(
                    "Apply %s returned %s instead of the resolved target type %s"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    value.getClass().getName(),
                                    resolvedTargetType.getName()));
        }
        Object current = target == null
                ? null : target.entity().get();
        Class<?> persistedTargetType = current != null
                ? current.getClass()
                : value != null ? value.getClass()
                : resolvedTargetType;
        Transition transition = new Transition(
                targetId, persistedTargetType,
                target != null
                && target.entity() instanceof ModelRoot<?> modelRoot
                        ? modelRoot.sequenceNumber() : -1L,
                target != null
                && target.entity() instanceof ModelRoot<?> modelRoot
                        ? modelRoot.lastEventIndex() : null,
                current, value, handler.executable(),
                null, false,
                TransitionEffect.resolve(
                        persistedTargetType, current, value, false,
                        compiledHandler.effect()));
        Map<String, Transition> result = transitions;
        if (result == null) {
            result = new LinkedHashMap<>();
        }
        Transition previous = result.putIfAbsent(
                targetId, transition);
        if (previous != null) {
            throw new IllegalStateException(
                    "Model '%s' is written by both %s and %s in one substep"
                            .formatted(
                                    targetId,
                                    previous.handler().toGenericString(),
                                    handler.executable().toGenericString()));
        }
        return result;
    }

    private static int compareHandlers(
            EntityMetadata.HandlerMethod left, EntityMetadata.HandlerMethod right) {
        return left.executable().toGenericString().compareTo(right.executable().toGenericString());
    }

    private static int compareAssertions(
            EntityMetadata.HandlerMethod left, EntityMetadata.HandlerMethod right) {
        int priority = Integer.compare(assertionPriority(right), assertionPriority(left));
        return priority == 0 ? compareHandlers(left, right) : priority;
    }

    private static int assertionPriority(EntityMetadata.HandlerMethod handler) {
        return io.fluxzero.common.reflection.ReflectionUtils
                .<AssertLegal>getMethodAnnotation(handler.executable(), AssertLegal.class)
                .map(AssertLegal::priority).orElse(AssertLegal.DEFAULT_PRIORITY);
    }

    private static boolean assertAfterHandler(EntityMetadata.HandlerMethod handler) {
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
                            source.getMetadata().with(emitted.getMetadata())))
                    .withoutContext(ModelPipeline.ExplicitModelTarget.class);
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
        List<?> outputs = asStream(output).toList();
        for (int i = outputs.size() - 1; i >= 0; i--) {
            Object value = outputs.get(i);
            enqueueOutput(
                    source, value, pending,
                    i == 0 && value != null
                    && value.getClass().equals(source.getPayloadClass()));
        }
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
        if (!EntityMetadata.of(modelType).isModel()) {
            throw new IllegalStateException(
                    "Staged graph deletion target %s is not an independent @Model"
                            .formatted(modelType.getName()));
        }
        return List.of(new StagedGraphChange(
                modelId, modelType, graph.stateIndex(), null,
                current -> current.update(ignored -> null),
                Objects.requireNonNull(eventMessage, "eventMessage")));
    }

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
        }

    }

    record ResolvedSubstep(
            ModelCommitContext context,
            HandlerPlan handlers,
            DirectSingleTargetApply directApply) {
        ResolvedSubstep(
                ModelCommitContext context,
                HandlerPlan handlers) {
            this(context, handlers, null);
        }

        ResolvedSubstep {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(handlers, "handlers");
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

        ModelConflictPolicy conflictPolicy(ModelConflictPolicy configured) {
            ModelConflictPolicy application = ModelConflictPolicy.resolve(configured);
            List<Transition> transitions = transitions();
            if (transitions.size() == 1
                && readModelTypes.size() == 1
                && readModelTypes.containsKey(
                        transitions.getFirst().modelId())) {
                return transitionPolicy(
                        transitions.getFirst(), application);
            }
            ModelConflictPolicy result = ModelConflictPolicy.ACCEPT;
            Set<String> written = new java.util.HashSet<>();
            for (Transition transition : transitions) {
                written.add(transition.modelId());
                result = strictest(result, transitionPolicy(transition, application));
            }
            for (Map.Entry<String, Class<?>> entry : readModelTypes.entrySet()) {
                if (!written.contains(entry.getKey())) {
                    result = strictest(result, inherit(modelPolicy(entry.getValue()), application));
                }
            }
            return result;
        }

        private static ModelConflictPolicy transitionPolicy(
                Transition transition, ModelConflictPolicy application) {
            ModelConflictPolicy result = inherit(
                    transition.effect().conflictPolicy(), application);
            return transition.before() == null
                   && transition.beforeSequenceNumber() < 0L
                   && result == ModelConflictPolicy.ACCEPT
                    ? ModelConflictPolicy.FAIL : result;
        }

        private static ModelConflictPolicy modelPolicy(Class<?> type) {
            return EntityMetadata.of(type).rootConfiguration()
                    .filter(configuration -> configuration.kind() == EntityMetadata.RootKind.MODEL)
                    .map(EntityMetadata.RootConfiguration::conflictPolicy)
                    .orElse(ModelConflictPolicy.DEFAULT);
        }

        private static ModelConflictPolicy inherit(
                ModelConflictPolicy declared, ModelConflictPolicy application) {
            return declared == null || declared == ModelConflictPolicy.DEFAULT
                    ? application : declared;
        }

        private static ModelConflictPolicy strictest(
                ModelConflictPolicy left, ModelConflictPolicy right) {
            return rank(right) > rank(left) ? right : left;
        }

        private static int rank(ModelConflictPolicy policy) {
            return switch (ModelConflictPolicy.resolve(policy)) {
                case FAIL -> 3;
                case RETRY -> 2;
                case ACCEPT, DEFAULT -> 1;
            };
        }
    }

    record AppliedSubstep(
            DeserializingMessage message, List<Transition> transitions) {
        AppliedSubstep {
            transitions = List.copyOf(transitions);
        }
    }

    enum ExecutionMode {
        LIVE(true, true, true),
        ASSERT(true, false, true),
        REPLAY(false, true, false);

        private final boolean interception;
        private final boolean applies;
        private final boolean assertions;

        ExecutionMode(boolean interception, boolean applies, boolean assertions) {
            this.interception = interception;
            this.applies = applies;
            this.assertions = assertions;
        }

        boolean interception() {
            return interception;
        }

        boolean applies() {
            return applies;
        }

        boolean assertions() {
            return assertions;
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

    public record DirectSingleTargetApply(
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
            boolean cascadedDeletion,
            TransitionEffect effect) {
        Transition(
                String modelId,
                Class<?> modelType,
                long beforeSequenceNumber,
                Long beforeLastEventIndex,
                Object before,
                Object after,
                Executable handler,
                Graphs.StagedReplay stagedReplay,
                boolean cascadedDeletion) {
            this(modelId, modelType, beforeSequenceNumber, beforeLastEventIndex,
                 before, after, handler, stagedReplay, cascadedDeletion, null);
        }

        Transition {
            effect = effect == null
                    ? TransitionEffect.resolve(
                            modelType, handler, before, after, cascadedDeletion)
                    : effect;
        }

        Transition withEffect(
                boolean storeEvent,
                boolean publishEvent,
                boolean updateState) {
            return new Transition(
                    modelId, modelType, beforeSequenceNumber, beforeLastEventIndex,
                    before, after, handler, stagedReplay, cascadedDeletion,
                    effect.with(storeEvent, publishEvent, updateState));
        }
    }

    record TransitionEffect(
            EntityMetadata metadata,
            EntityMetadata.RootConfiguration model,
            EntityMetadata.SnapshotSettings snapshots,
            String directCollection,
            AggregateEventRouting eventRouting,
            ModelConflictPolicy conflictPolicy,
            boolean active,
            boolean storeEvent,
            boolean publishEvent,
            boolean updateState) {
        private static TransitionEffect resolve(
                Class<?> modelType,
                Executable handler,
                Object before,
                Object after,
                boolean cascadedDeletion) {
            return resolve(
                    modelType, before, after, cascadedDeletion,
                    EffectOverrides.of(handler));
        }

        private static TransitionEffect resolve(
                Class<?> modelType,
                Object before,
                Object after,
                boolean cascadedDeletion,
                EffectOverrides overrides) {
            Class<?> effectiveType = EntityMetadata.of(modelType).isModel()
                    ? modelType : after != null ? after.getClass()
                            : before != null ? before.getClass() : modelType;
            EffectDefaults defaults = EffectDefaults.of(effectiveType);
            EntityMetadata.TransitionSettings settings = defaults.model().transitionSettings(
                    overrides.publication(), overrides.strategy(), overrides.routing(), overrides.conflict());
            boolean modified = settings.forceModified() || !Objects.equals(before, after);
            return checked(
                    defaults, settings,
                    settings.decide(modified, cascadedDeletion, true));
        }

        private static TransitionEffect checked(
                EffectDefaults defaults,
                EntityMetadata.TransitionSettings settings,
                EntityMetadata.TransitionDecision decision) {
            return new TransitionEffect(
                    defaults.metadata(), defaults.model(), defaults.snapshots(), defaults.collection(),
                    settings.routing(), settings.conflict(),
                    decision.active(), decision.storeEvent(), decision.publishEvent(), decision.updateState());
        }

        void validate(Transition transition) {
            if (active && model.eventSourced() && updateState && !storeEvent) {
                throw new IllegalStateException(
                        "Event-sourced model %s cannot change through %s without storing its reconstructing event. "
                                .formatted(
                                        transition.modelType().getName(),
                                        transition.handler() == null ? "a direct graph change"
                                                : transition.handler().toGenericString())
                        + "Use STORE_ONLY or STORE_AND_PUBLISH, make the model document-loaded, or publish a no-op event.");
            }
        }

        private TransitionEffect with(
                boolean storeEvent,
                boolean publishEvent,
                boolean updateState) {
            return new TransitionEffect(
                    metadata, model, snapshots, directCollection, eventRouting, conflictPolicy,
                    active, storeEvent, publishEvent, updateState);
        }
    }

    private record EffectDefaults(
            EntityMetadata metadata,
            EntityMetadata.RootConfiguration model,
            EntityMetadata.SnapshotSettings snapshots,
            String collection) {
        private EffectDefaults(EntityMetadata metadata, Class<?> type) {
            this(metadata, metadata.rootConfiguration().orElseThrow(() ->
                         new IllegalStateException(type.getName() + " is not an independent model")), type);
        }

        private EffectDefaults(EntityMetadata metadata, EntityMetadata.RootConfiguration model, Class<?> type) {
            this(metadata, model, model.snapshotSettings(false),
                 model.searchable() ? Optional.of(model.collection()).filter(value -> !value.isEmpty())
                         .map(ApplicationProperties::substituteProperties).orElse(type.getSimpleName())
                         : metadata.participatesInGraphComposition()
                                 ? io.fluxzero.common.api.modeling.ModelDocumentMutation.GRAPH_COMPONENT_COLLECTION
                                 : null);
        }

        private static EffectDefaults of(Class<?> type) {
            EntityMetadata metadata = EntityMetadata.of(type);
            return ReflectionUtils.getTypeMetadata(type)
                    .specializedMetadata(
                            EffectDefaults.class,
                            ignored -> new EffectDefaults(metadata, type));
        }
    }

    private record EffectOverrides(
            EventPublication publication,
            EventPublicationStrategy strategy,
            AggregateEventRouting routing,
            ModelConflictPolicy conflict) {
        private static final EffectOverrides NONE = new EffectOverrides(
                EventPublication.DEFAULT, EventPublicationStrategy.DEFAULT,
                AggregateEventRouting.DEFAULT, ModelConflictPolicy.DEFAULT);

        private static EffectOverrides of(Executable handler) {
            Apply apply = handler == null ? null : handler.getAnnotation(Apply.class);
            return apply == null ? NONE : new EffectOverrides(
                    apply.eventPublication(), apply.publicationStrategy(),
                    apply.eventRouting(), apply.conflictPolicy());
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

    static record HandlerPlan(
            List<CompiledHandler> all,
            List<CompiledHandler> beforeAssertions,
            List<CompiledHandler> afterAssertions,
            List<CompiledHandler> applies,
            List<CompiledHandler> interceptors) {
        static final HandlerPlan EMPTY = new HandlerPlan(
                List.of(), List.of(), List.of(), List.of(), List.of());

        private HandlerPlan(
                List<EntityMetadata.HandlerMethod> handlers,
                Compiler compiler) {
            this(handlers.stream().map(handler -> new CompiledHandler(
                    handler, compiler.compileMatcher(handler),
                    handler.modelParameters(), ApplyResultShape.of(handler),
                    EffectOverrides.of(handler.executable()))).toList());
        }

        private HandlerPlan(List<CompiledHandler> handlers) {
            this(List.copyOf(handlers),
                 select(handlers, EntityMetadata.HandlerKind.ASSERT_LEGAL, false),
                 select(handlers, EntityMetadata.HandlerKind.ASSERT_LEGAL, true),
                 select(handlers, EntityMetadata.HandlerKind.APPLY, null),
                 select(handlers, EntityMetadata.HandlerKind.INTERCEPT_APPLY, null));
        }

        List<EntityMetadata.HandlerMethod> methods() {
            return all.stream().map(CompiledHandler::method).toList();
        }

        private static List<CompiledHandler> select(
                List<CompiledHandler> handlers,
                EntityMetadata.HandlerKind kind,
                Boolean afterHandler) {
            List<CompiledHandler> result = handlers.stream()
                    .filter(handler -> handler.method().kind() == kind)
                    .filter(handler -> afterHandler == null || Compiler.assertAfterHandler(
                            handler.method()) == afterHandler).collect(java.util.stream.Collectors.toList());
            result.sort((left, right) -> kind == EntityMetadata.HandlerKind.ASSERT_LEGAL
                    ? Compiler.compareAssertions(left.method(), right.method())
                    : Compiler.compareHandlers(left.method(), right.method()));
            return List.copyOf(result);
        }
    }

    private record CompiledHandler(
            EntityMetadata.HandlerMethod method,
            HandlerMatcher<Object, DeserializingMessage> matcher,
            List<EntityMetadata.ModelParameter> dependencyAccessors,
            ApplyResultShape resultShape,
            EffectOverrides effect) {
        private CompiledHandler {
            dependencyAccessors = List.copyOf(dependencyAccessors);
        }
    }

    private record ApplyResultShape(
            boolean present,
            boolean collection,
            boolean dynamic,
            Class<?> targetType) {
        private static ApplyResultShape of(EntityMetadata.HandlerMethod handler) {
            if (!handler.hasApplyResult()) {
                return new ApplyResultShape(false, false, false, null);
            }
            if (!handler.dynamicApplyResult() && handler.targetModelTypes().size() != 1) {
                throw new IllegalStateException(
                        "Apply %s targets more than one model type".formatted(handler.executable()));
            }
            return new ApplyResultShape(
                    true, handler.collectionApplyResult(), handler.dynamicApplyResult(),
                    handler.dynamicApplyResult() ? null : handler.targetModelTypes().getFirst());
        }

        private boolean createsTargets() {
            return collection || dynamic;
        }

        private List<?> values(EntityMetadata.HandlerMethod handler, Object result) {
            if (!collection) {
                return Collections.singletonList(result);
            }
            if (result == null) {
                throw new IllegalStateException(
                        "Apply %s returned null instead of a model collection"
                                .formatted(handler.executable().toGenericString()));
            }
            if (!(result instanceof Collection<?> values)) {
                throw new IllegalStateException(
                        "Apply %s returned %s instead of a model collection"
                                .formatted(handler.executable().toGenericString(), result.getClass().getName()));
            }
            List<Object> snapshot = new ArrayList<>(values.size());
            int index = 0;
            for (Object value : values) {
                if (value == null) {
                    throw new IllegalStateException(
                            "Apply %s returned a null model at collection index %d; use Graph.delete() for deletion"
                                    .formatted(handler.executable().toGenericString(), index));
                }
                snapshot.add(value);
                index++;
            }
            return snapshot;
        }

        private Class<?> targetType(
                EntityMetadata.HandlerMethod handler,
                Object result,
                int resultIndex) {
            if (!dynamic) {
                if (result != null && !targetType.isInstance(result)) {
                    throw new IllegalStateException(
                            "Apply %s returned %s instead of %s%s"
                                    .formatted(
                                            handler.executable().toGenericString(),
                                            result.getClass().getName(), targetType.getName(),
                                            collection ? " at collection index " + resultIndex : ""));
                }
                return targetType;
            }
            if (result == null) {
                throw new IllegalStateException(
                        "Apply %s returned null for a dynamically typed model result"
                                .formatted(handler.executable().toGenericString()));
            }
            EntityMetadata metadata = EntityMetadata.validate(result.getClass());
            if (!metadata.isModel()) {
                throw new IllegalStateException(
                        "Apply %s returned %s%s, which is not annotated with @Model"
                                .formatted(
                                        handler.executable().toGenericString(), result.getClass().getName(),
                                        collection ? " at collection index " + resultIndex : ""));
            }
            return result.getClass();
        }
    }

    private enum MissingTarget {
        INSTANCE
    }
}
