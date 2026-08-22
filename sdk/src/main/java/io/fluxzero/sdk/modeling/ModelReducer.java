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

import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.fluxzero.common.ObjectUtils.asStream;

/**
 * Sole executor for the immutable handler and target knowledge in {@link MutationPlan}.
 * <p>
 * Interceptor outputs form ordered substeps under one pinned read boundary. Every mutation within one substep reads
 * its same immutable begin-state; only a successfully completed substep becomes visible to later substeps. This class
 * owns handler invocation, result validation, ordering and staged Graph changes for live handling, retry, rebase and
 * reconstruction.
 */
public final class ModelReducer {
    private static final int MAX_SUBSTEPS = 10_000;

    static final ModelReducer EMPTY = new ModelReducer(MutationPlan.HandlerPlan.EMPTY, null);
    private static final Object NO_INTERCEPTION = new Object();
    private static final Object SUPPRESSED = new Object();

    private final MutationPlan.HandlerPlan handlers;
    private final MutationPlan.DirectSingleTargetApply directApply;

    ModelReducer(
            MutationPlan.HandlerPlan handlers,
            MutationPlan.DirectSingleTargetApply directApply) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.directApply = directApply;
    }

    boolean empty() {
        return handlers.all().isEmpty();
    }

    boolean direct() {
        return directApply != null;
    }

    List<EntityMetadata.HandlerMethod> methods() {
        return handlers.methods();
    }

    Object intercept(
            DeserializingMessage message,
            CommitAttempt context) {
        HandlerInvoker applicable = null;
        for (int i = 0; i < handlers.interceptors().size(); i++) {
            HandlerInvoker candidate = invoker(
                    handlers.interceptors().get(i), message, context);
            if (candidate == null) {
                continue;
            }
            if (applicable != null) {
                throw new IllegalStateException(
                        "Multiple @InterceptApply methods were selected for %s: %s and %s"
                                .formatted(
                                        message.getPayloadClass().getName(),
                                        applicable.getMethod().toGenericString(),
                                        candidate.getMethod().toGenericString()));
            }
            applicable = candidate;
        }
        if (applicable == null) {
            return NO_INTERCEPTION;
        }
        HandlerInvoker selected = applicable;
        Object output = message.apply(ignored -> selected.invoke());
        return output == null ? SUPPRESSED : output;
    }

    boolean intercepted(Object result) {
        return result != NO_INTERCEPTION;
    }

    Object interceptionOutput(Object result) {
        return result == SUPPRESSED ? null : result;
    }

    List<Change> apply(
            DeserializingMessage message,
            CommitAttempt beginState,
            boolean applyHandlers,
            boolean assertions) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(beginState, "beginState");
        if (directApply != null && applyHandlers) {
            MutationPlan.CompiledHandler compiledHandler = handlers.applies().getFirst();
            EntityMetadata.HandlerMethod handler = compiledHandler.method();
            Object receiver = directApply.receiver()
                    ? invocationTarget(handler, message, beginState) : null;
            if (receiver == MissingTarget.INSTANCE) {
                return List.of();
            }
            Object result = message.apply(ignored -> directApply.invoker().invoke(
                    receiver, (Object) message.getPayload()));
            return finishApply(
                    message, beginState,
                    List.of(applyResult(
                            compiledHandler, result, 0, beginState)),
                    assertions);
        }
        beginState.attachTo(message);
        try {
            return message.apply(ignored -> applyInContext(
                    message, beginState, applyHandlers, assertions));
        } finally {
            beginState.attachTo(message);
        }
    }

    private List<Change> applyInContext(
            DeserializingMessage message,
            CommitAttempt beginState,
            boolean applyHandlers,
            boolean assertions) {
        if (assertions) {
            for (int i = 0; i < handlers.beforeAssertions().size(); i++) {
                invokeIfApplicable(
                        handlers.beforeAssertions().get(i), message, beginState);
            }
        }
        if (!applyHandlers) {
            return List.of();
        }

        Map<String, Change> transitions = null;
        for (MutationPlan.CompiledHandler compiledHandler : handlers.applies()) {
            EntityMetadata.HandlerMethod handler = compiledHandler.method();
            if (!handler.hasApplyResult()) {
                continue;
            }
            Object result;
            HandlerInvoker invoker = invoker(
                    compiledHandler, message, beginState);
            if (invoker == null) {
                continue;
            }
            result = invoker.invoke();
            List<?> results = MutationPlan.applyResults(handler, result);
            for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
                transitions = addApplyResult(
                        transitions, compiledHandler,
                        results.get(resultIndex), resultIndex, beginState);
            }
        }
        if (transitions == null) {
            return List.of();
        }
        return finishApply(
                message, beginState,
                List.copyOf(transitions.values()), assertions);
    }

    private List<Change> finishApply(
            DeserializingMessage message,
            CommitAttempt beginState,
            List<Change> transitions,
            boolean assertions) {
        if (assertions && !handlers.afterAssertions().isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>(transitions.size());
            transitions.forEach(transition -> values.put(
                    transition.modelId(), transition.after()));
            CommitAttempt resultingState = beginState.withValues(values);
            for (int i = 0; i < handlers.afterAssertions().size(); i++) {
                invokeIfApplicable(
                        handlers.afterAssertions().get(i), message, resultingState);
            }
        }
        return transitions;
    }

    Object replay(
            DeserializingMessage event,
            CommitAttempt context,
            String targetModelId) {
        Objects.requireNonNull(targetModelId, "targetModelId");
        List<Change> transitions = apply(
                event, context, true, false);
        Change selected = null;
        for (Change transition : transitions) {
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

    private static void invokeIfApplicable(
            MutationPlan.CompiledHandler handler,
            DeserializingMessage message,
            CommitAttempt context) {
        HandlerInvoker invoker = invoker(handler, message, context);
        if (invoker != null) {
            invoker.invoke();
        }
    }

    private static HandlerInvoker invoker(
            MutationPlan.CompiledHandler compiledHandler,
            DeserializingMessage message,
            CommitAttempt context) {
        context.attachTo(message);
        EntityMetadata.HandlerMethod handler = compiledHandler.method();
        Object target = invocationTarget(handler, message, context);
        return target == MissingTarget.INSTANCE
                ? null : compiledHandler.matcher().getInvokerOrNull(target, message);
    }

    private static Object invocationTarget(
            EntityMetadata.HandlerMethod handler,
            DeserializingMessage message,
            CommitAttempt context) {
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

    private static String resolveWriteTarget(
            EntityMetadata.HandlerMethod handler,
            Class<?> targetType,
            Object result,
            CommitAttempt context) {
        if (result != null) {
            return resultModelId(handler, targetType, result);
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

    private static String resultModelId(
            EntityMetadata.HandlerMethod handler,
            Class<?> targetType,
            Object result) {
        if (!targetType.isInstance(result)) {
            throw new IllegalStateException(
                    "Apply %s returned %s instead of %s"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    result.getClass().getName(),
                                    targetType.getName()));
        }
        EntityMetadata metadata = EntityMetadata.of(result.getClass());
        Object id = metadata.entityId().orElseThrow().read(result);
        if (id == null) {
            throw new IllegalStateException(
                    "Apply %s returned a model with a null ID"
                            .formatted(handler.executable().toGenericString()));
        }
        return metadata.parentScopedEntityId()
                ? metadata.repositoryId(id, result) : metadata.repositoryId(id);
    }

    private static Map<String, Change> addApplyResult(
            Map<String, Change> transitions,
            MutationPlan.CompiledHandler compiledHandler,
            Object value,
            int resultIndex,
            CommitAttempt beginState) {
        Change transition = applyResult(
                compiledHandler, value, resultIndex, beginState);
        Map<String, Change> result = transitions;
        if (result == null) {
            result = new LinkedHashMap<>();
        }
        Change previous = result.putIfAbsent(
                transition.modelId(), transition);
        if (previous != null) {
            throw new IllegalStateException(
                    "Model '%s' is written by both %s and %s in one substep"
                            .formatted(
                                    transition.modelId(),
                                    previous.handler().toGenericString(),
                                    compiledHandler.method().executable().toGenericString()));
        }
        return result;
    }

    private static Change applyResult(
            MutationPlan.CompiledHandler compiledHandler,
            Object value,
            int resultIndex,
            CommitAttempt beginState) {
        EntityMetadata.HandlerMethod handler = compiledHandler.method();
        Class<?> targetType = MutationPlan.applyTargetType(handler, value, resultIndex);
        String targetId = resolveWriteTarget(handler, targetType, value, beginState);
        Entity<?> target = beginState.entity(targetId);
        boolean creation = target == null && value != null
                           && (handler.collectionApplyResult() || handler.dynamicApplyResult());
        if (!creation && (target == null || !beginState.mayWrite(
                targetId, targetType, handler.executable()))) {
            throw new IllegalStateException(
                    "Apply %s returned model '%s', which is not a resolved write target"
                            .formatted(handler.executable().toGenericString(), targetId));
        }
        Class<?> resolvedTargetType = target == null
                ? value.getClass() : beginState.target(targetId).modelType();
        return transition(
                compiledHandler, targetId, resolvedTargetType,
                null, target, value);
    }

    private static Change transition(
            MutationPlan.CompiledHandler compiledHandler,
            String targetId,
            Class<?> resolvedTargetType,
            Class<?> persistedTargetType,
            Entity<?> target,
            Object value) {
        EntityMetadata.HandlerMethod handler = compiledHandler.method();
        if (value != null && !resolvedTargetType.isInstance(value)) {
            throw new IllegalStateException(
                    "Apply %s returned %s instead of the resolved target type %s"
                            .formatted(
                                    handler.executable().toGenericString(),
                                    value.getClass().getName(),
                                    resolvedTargetType.getName()));
        }
        Object current = target == null ? null : target.get();
        Class<?> effectiveTargetType = persistedTargetType != null
                ? persistedTargetType
                : current != null ? current.getClass()
                        : value != null ? value.getClass() : resolvedTargetType;
        return Change.applied(
                targetId, effectiveTargetType,
                target instanceof ModelRoot<?> modelRoot
                        ? modelRoot.sequenceNumber() : -1L,
                target instanceof ModelRoot<?> modelRoot
                        ? modelRoot.lastEventIndex() : null,
                current, value, handler.executable(), null, false,
                compiledHandler.effect());
    }

    private enum MissingTarget {
        INSTANCE
    }


    static CommitAttempt apply(
            CommitAttempt attempt,
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return execute(attempt, messages, resolver, Mode.APPLY);
    }

    static CommitAttempt assertLegal(
            CommitAttempt attempt,
            DeserializingMessage message,
            SubstepResolver resolver) {
        return execute(attempt, List.of(message), resolver, Mode.ASSERT);
    }

    static CommitAttempt reapply(
            CommitAttempt attempt,
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return execute(attempt, messages, resolver, Mode.REAPPLY);
    }

    static CommitAttempt reapplySteps(
            CommitAttempt attempt,
            List<CommitAttempt.Step> steps,
            SubstepResolver resolver) {
        Objects.requireNonNull(steps, "steps");
        Deque<PendingSubstep> pending = new ArrayDeque<>(steps.size());
        for (CommitAttempt.Step step : steps) {
            Objects.requireNonNull(step, "step");
            List<Change> changes = step.changes();
            if (!step.directMutation()) {
                pending.add(new PendingSubstep(step.message(), null, false));
            }
            changes.stream().filter(Change::directMutation).forEach(change ->
                    pending.add(new PendingSubstep(
                            step.message(), change.forRebase(), false)));
        }
        if (pending.isEmpty()) {
            throw new IllegalArgumentException(
                    "A model rebase requires at least one effective step");
        }
        return evaluate(attempt, pending, resolver, null, Mode.REAPPLY);
    }

    /** Executes every mutation form through one ordered substep pipeline. */
    private static CommitAttempt execute(
            CommitAttempt attempt,
            List<DeserializingMessage> messages,
            SubstepResolver resolver,
            Mode mode) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A model execution requires at least one message");
        }
        Deque<PendingSubstep> pending = new ArrayDeque<>(messages.size());
        for (DeserializingMessage message : messages) {
            Objects.requireNonNull(message, "message");
            if (mode.stageGraphPayloads && message.getPayload() instanceof Graph<?> graph) {
                enqueueOutput(message, graph, pending, false);
            } else {
                pending.add(new PendingSubstep(message, null, mode.interception));
            }
        }
        return evaluate(
                attempt, pending, resolver,
                mode == Mode.REAPPLY ? null : messages.getFirst(), mode);
    }

    private static CommitAttempt evaluate(
            CommitAttempt attempt,
            Deque<PendingSubstep> pending,
            SubstepResolver resolver,
            DeserializingMessage initialMessage,
            Mode mode) {
        Objects.requireNonNull(resolver, "resolver");
        CommitAttempt originalContext = initialMessage == null ? null
                : initialMessage.getContext(CommitAttempt.class).orElse(null);
        CommitAttempt commitBeginContext = null;
        ResolvedSubstep prepared = null;

        try {
            if (pending.size() == 1 && initialMessage != null
                && mode == Mode.APPLY) {
                PendingSubstep current = pending.getFirst();
                if (current.interceptionAllowed()
                    && current.stagedChange() == null) {
                    prepared = Objects.requireNonNull(
                            resolver.resolve(current.message(), null, Map.of()),
                            "Substep resolver returned null");
                    if (prepared.reducer().direct()
                        && prepared.context().targets().size() == 1) {
                        MutationPlan.ResolvedModel target =
                                prepared.context().targets().getFirst();
                        if (target.access().writes()
                            && prepared.context().entity(target.modelId()) != null) {
                            commitBeginContext = prepared.context();
                            List<Change> transitions = prepared.reducer().apply(
                                    current.message(), prepared.context(), true, true);
                            attempt.evaluated(
                                    prepared.context().readStateIndex(),
                                    List.of(target.modelId()),
                                    Map.of(target.modelId(), target.modelType()),
                                    List.of(new CommitAttempt.Step(
                                            current.message(), transitions)));
                            return attempt;
                        }
                    }
                }
            }

            Map<String, Object> stagedValues = new LinkedHashMap<>();
            LinkedHashSet<String> readModelIds = new LinkedHashSet<>();
            Map<String, Class<?>> readModelTypes =
                    new LinkedHashMap<>();
            List<CommitAttempt.Step> steps = new ArrayList<>();
            long readStateIndex = -1L;
            boolean stateIndexPinned = false;
            int processed = 0;
            while (!pending.isEmpty()) {
                if (++processed > MAX_SUBSTEPS) {
                    throw new IllegalStateException(
                            "Model commit exceeded %d interceptor substeps".formatted(MAX_SUBSTEPS));
                }
                PendingSubstep current = pending.removeFirst();
                GraphMutation graphMutation = current.stagedChange();
                ResolvedSubstep resolved = prepared == null
                        ? Objects.requireNonNull(
                        graphMutation == null
                                ? resolver.resolve(
                                        current.message(),
                                        stateIndexPinned ? readStateIndex : null,
                                        stagedValues)
                                : resolver.resolveGraph(
                                        graphMutation.modelId(),
                                        graphMutation.modelType(),
                                        stateIndexPinned
                                                ? Long.valueOf(readStateIndex)
                                                : graphMutation.expectedStateIndex(),
                                        stagedValues),
                        "Substep resolver returned null")
                        : prepared;
                prepared = null;
                if (!stateIndexPinned) {
                    readStateIndex = resolved.context().readStateIndex();
                    stateIndexPinned = true;
                    commitBeginContext = resolved.context();
                } else if (resolved.context().readStateIndex() != readStateIndex) {
                    throw new IllegalStateException(
                            "Substep loaded at state index %d while commit is pinned at %d"
                                    .formatted(resolved.context().readStateIndex(), readStateIndex));
                }
                CommitAttempt context = resolved.context().withValues(stagedValues);
                resolved.context().targets().forEach(target -> {
                    readModelIds.add(target.modelId());
                    readModelTypes.putIfAbsent(
                            target.modelId(), target.modelType());
                });
                if (graphMutation != null) {
                    Change change = evaluateGraphMutation(
                            graphMutation,
                            context, readStateIndex,
                            stagedValues.containsKey(
                                    graphMutation.modelId()));
                    stagedValues.put(
                            change.modelId(), change.after());
                    mergeDirectMutation(steps, current.message(), change);
                    continue;
                }
                if (current.interceptionAllowed()) {
                    Object interception =
                            resolved.reducer().intercept(
                                    current.message(), context);
                    if (resolved.reducer().intercepted(interception)) {
                        enqueueOutputs(
                                current.message(),
                                resolved.reducer().interceptionOutput(interception),
                                pending);
                        resolver.prefetch(
                                pending.stream()
                                        .filter(substep -> substep.stagedChange() == null)
                                        .map(PendingSubstep::message)
                                        .toList(),
                                readStateIndex, stagedValues);
                        continue;
                    }
                }

                List<Change> transitions = resolved.reducer().apply(
                        current.message(), context,
                        mode.applyHandlers, mode.assertions);
                for (Change transition : transitions) {
                    stagedValues.put(transition.modelId(), transition.after());
                    if (!readModelTypes.containsKey(transition.modelId())) {
                        readModelIds.add(transition.modelId());
                        readModelTypes.putIfAbsent(
                                transition.modelId(),
                                transition.modelType());
                    }
                }
                steps.add(new CommitAttempt.Step(current.message(), transitions));
            }
            attempt.evaluated(
                    readStateIndex, readModelIds, readModelTypes,
                    steps);
            return attempt;
        } finally {
            CommitAttempt restore =
                    originalContext == null ? commitBeginContext : originalContext;
            if (restore != null && initialMessage != null) {
                restore.attachTo(initialMessage);
            }
        }
    }

    private static Change evaluateGraphMutation(
            GraphMutation change,
            CommitAttempt context,
            long readStateIndex,
            boolean alreadyStaged) {
        String modelId = change.modelId();
        Class<?> modelType = change.modelType();
        long targetStateIndex = targetStateIndex(
                context.entity(change.modelId()), readStateIndex);
        if (change.expectedStateIndex() != null
            && change.expectedStateIndex() != targetStateIndex) {
            throw new IllegalStateException(
                    "Staged graph '%s' was loaded at model state index %d while the commit resolved model state index %d"
                            .formatted(modelId, change.expectedStateIndex(), targetStateIndex));
        }
        Entity<?> target = context.entity(modelId);
        if (target == null || !context.mayWrite(modelId, modelType, null)) {
            throw new IllegalStateException(
                    "Staged graph '%s' of type %s is not a resolved write target"
                            .formatted(modelId, modelType.getName()));
        }
        Object after = change.expectedStateIndex() == null || alreadyStaged
                ? change.replay().apply(target).get()
                : change.preview();
        return Change.resolve(change, target, after);
    }

    private static long targetStateIndex(
            Entity<?> target,
            long fallback) {
        return target instanceof ModelRoot<?> root
                ? root.stateIndex() : fallback;
    }

    private static void mergeDirectMutation(
            List<CommitAttempt.Step> steps,
            DeserializingMessage message,
            Change addition) {
        String eventMessageId = message.getMessageId();
        for (int i = steps.size() - 1; i >= 0; i--) {
            CommitAttempt.Step step = steps.get(i);
            DeserializingMessage existing = step.message();
            if (!Objects.equals(
                    existing.getMessageId(), eventMessageId)
                || step.changes().isEmpty()
                || !step.directMutation()) {
                continue;
            }
            LinkedHashMap<String, Change> transitions =
                    new LinkedHashMap<>();
            step.changes().forEach(
                    transition -> transitions.merge(
                            transition.modelId(), transition, Change::then));
            transitions.merge(addition.modelId(), addition, Change::then);
            steps.set(i, new CommitAttempt.Step(
                    existing, List.copyOf(transitions.values())));
            return;
        }
        steps.add(new CommitAttempt.Step(message, List.of(addition)));
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
            List<GraphMutation> changes = stagedChanges(graph);
            for (int index = changes.size() - 1; index >= 0; index--) {
                pending.addFirst(new PendingSubstep(
                        source, changes.get(index), false));
            }
            return;
        }
        DeserializingMessage emitted = emittedMessage(
                source, output, preserveSourceIdentity);
        boolean reintercept =
                !emitted.getPayloadClass().equals(source.getPayloadClass());
        pending.addFirst(new PendingSubstep(emitted, null, reintercept));
    }

    private static List<GraphMutation> stagedChanges(Graph<?> graph) {
        Objects.requireNonNull(graph, "graph");
        List<GraphMutation> staged = Graphs.stagedChanges(graph);
        if (!staged.isEmpty()) {
            return staged;
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
        return List.of(new GraphMutation(
                modelId, modelType, graph.stateIndex(), null,
                current -> current.update(ignored -> null)));
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
            CommitAttempt context,
            ModelReducer reducer) {
        ResolvedSubstep {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(reducer, "reducer");
        }
    }

    public static Object replay(
            MutationPlan plan,
            DeserializingMessage event,
            CommitAttempt context,
            String targetModelId) {
        return plan.reducer().replay(event, context, targetModelId);
    }

    private record PendingSubstep(
            DeserializingMessage message,
            GraphMutation stagedChange,
            boolean interceptionAllowed) {
    }

    private enum Mode {
        APPLY(true, true, true, true),
        ASSERT(true, false, false, true),
        REAPPLY(false, true, false, false);

        private final boolean interception;
        private final boolean applyHandlers;
        private final boolean stageGraphPayloads;
        private final boolean assertions;

        Mode(
                boolean interception,
                boolean applyHandlers,
                boolean stageGraphPayloads,
                boolean assertions) {
            this.interception = interception;
            this.applyHandlers = applyHandlers;
            this.stageGraphPayloads = stageGraphPayloads;
            this.assertions = assertions;
        }
    }

}
