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

    MutationPlan.HandlerPlan handlers() {
        return handlers;
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
            if (directApply != null && handlers.applies().size() == 1) {
                Object receiver = directApply.receiver()
                        ? invocationTarget(handler, message, beginState) : null;
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
        Map<String, Object> values = new LinkedHashMap<>(transitions.size());
        transitions.forEach((id, transition) -> values.put(id, transition.after()));
        List<Change> result = List.copyOf(transitions.values());
        if (assertions) {
            CommitAttempt resultingState = beginState.withValues(values);
            for (int i = 0; i < handlers.afterAssertions().size(); i++) {
                invokeIfApplicable(
                        handlers.afterAssertions().get(i), message, resultingState);
            }
        }
        return result;
    }

    CommitAttempt evaluateDirectSingleTarget(
            CommitAttempt attempt,
            DeserializingMessage message,
            long readStateIndex,
            String modelId,
            Class<?> modelType,
            Entity<?> entity) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelType, "modelType");
        Objects.requireNonNull(entity, "entity");
        if (handlers.applies().size() != 1 || directApply == null) {
            throw new IllegalArgumentException(
                    "Direct single-target evaluation requires one compiled apply");
        }
        MutationPlan.CompiledHandler compiledHandler = handlers.applies().getFirst();
        EntityMetadata.HandlerMethod handler = compiledHandler.method();
        Object before = entity.get();
        if (directApply.receiver() && before == null) {
            return null;
        }
        Object after = message.apply(ignored -> directApply.invoker().invoke(
                directApply.receiver() ? before : null,
                (Object) message.getPayload()));
        if (after != null) {
            String resultId = resultModelId(handler, modelType, after);
            if (!modelId.equals(resultId)) {
                throw new IllegalStateException(
                        "Apply %s returned model '%s', which is not replay target '%s'"
                                .formatted(
                                        handler.executable().toGenericString(),
                                        EntityMetadata.of(after.getClass()).entityId()
                                                .orElseThrow().read(after),
                                        modelId));
            }
        }
        Change transition = transition(
                compiledHandler, modelId, modelType, modelType,
                entity, after);
        attempt.evaluated(
                readStateIndex, List.of(modelId),
                Map.of(modelId, modelType),
                List.of(new CommitAttempt.Step(message, List.of(transition))));
        return attempt;
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
        Change transition = transition(
                compiledHandler, targetId, resolvedTargetType,
                null, target, value);
        Map<String, Change> result = transitions;
        if (result == null) {
            result = new LinkedHashMap<>();
        }
        Change previous = result.putIfAbsent(
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
            DeserializingMessage message,
            SubstepResolver resolver) {
        return apply(CommitAttempt.detached(), List.of(message), resolver);
    }

    static CommitAttempt apply(
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return apply(CommitAttempt.detached(), messages, resolver);
    }

    static CommitAttempt apply(
            CommitAttempt attempt,
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return execute(attempt, messages, resolver, Mode.APPLY);
    }

    static CommitAttempt assertLegal(
            DeserializingMessage message,
            SubstepResolver resolver) {
        return assertLegal(CommitAttempt.detached(), message, resolver);
    }

    static CommitAttempt assertLegal(
            CommitAttempt attempt,
            DeserializingMessage message,
            SubstepResolver resolver) {
        return execute(attempt, List.of(message), resolver, Mode.ASSERT);
    }

    static CommitAttempt reapply(
            DeserializingMessage message,
            SubstepResolver resolver) {
        return reapply(CommitAttempt.detached(), List.of(message), resolver);
    }

    static CommitAttempt reapply(
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return reapply(CommitAttempt.detached(), messages, resolver);
    }

    static CommitAttempt reapply(
            CommitAttempt attempt,
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return execute(attempt, messages, resolver, Mode.REAPPLY);
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
            } else if (mode.rebaseGraphs && message instanceof GraphChangeMessage changeMessage) {
                pending.add(new PendingSubstep(
                        new GraphChangeMessage(
                                changeMessage.change.forRebase(), changeMessage), false));
            } else {
                pending.add(new PendingSubstep(message, mode.interception));
            }
        }
        return evaluate(
                attempt, pending, resolver,
                mode.rebaseGraphs ? null : messages.getFirst(), mode);
    }

    private static CommitAttempt evaluate(
            CommitAttempt attempt,
            Deque<PendingSubstep> pending,
            SubstepResolver resolver,
            DeserializingMessage initialMessage,
            Mode mode) {
        Objects.requireNonNull(resolver, "resolver");
        DirectPreparation preparation = prepareDirect(
                attempt, pending, resolver, initialMessage, mode);
        if (preparation.completed()) {
            return attempt;
        }
        ResolvedSubstep prepared = preparation.resolved();
        Map<String, Object> stagedValues = new LinkedHashMap<>();
        LinkedHashSet<String> readModelIds = new LinkedHashSet<>();
        Map<String, Class<?>> readModelTypes =
                new LinkedHashMap<>();
        List<CommitAttempt.Step> steps = new ArrayList<>();
        long readStateIndex = -1L;
        boolean stateIndexPinned = false;
        CommitAttempt originalContext = initialMessage == null ? null
                : initialMessage.getContext(CommitAttempt.class).orElse(null);
        CommitAttempt commitBeginContext = null;
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
                ResolvedSubstep resolved = prepared == null
                        ? Objects.requireNonNull(
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
                if (graphChangeMessage != null) {
                    Change change = evaluateGraphChange(
                            graphChangeMessage,
                            context, readStateIndex,
                            stagedValues.containsKey(
                                    graphChangeMessage.change.modelId()));
                    stagedValues.put(
                            change.modelId(), change.after());
                    mergeGraphChange(steps, graphChangeMessage, change);
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
                                        .map(PendingSubstep::message)
                                        .filter(message -> !(message instanceof GraphChangeMessage))
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

    private static DirectPreparation prepareDirect(
            CommitAttempt attempt,
            Deque<PendingSubstep> pending,
            SubstepResolver resolver,
            DeserializingMessage initialMessage,
            Mode mode) {
        if (pending.size() != 1 || initialMessage == null
            || mode != Mode.APPLY) {
            return DirectPreparation.NONE;
        }
        PendingSubstep step = pending.getFirst();
        if (!step.interceptionAllowed()
            || step.message() instanceof GraphChangeMessage) {
            return DirectPreparation.NONE;
        }
        ResolvedSubstep resolved = Objects.requireNonNull(
                resolver.resolve(step.message(), null, Map.of()),
                "Substep resolver returned null");
        if (!resolved.reducer().direct()
            || resolved.context().targets().size() != 1) {
            return new DirectPreparation(resolved, false);
        }
        MutationPlan.ResolvedModel target =
                resolved.context().targets().getFirst();
        Entity<?> entity = resolved.context().entity(target.modelId());
        if (!target.access().writes() || entity == null) {
            return new DirectPreparation(resolved, false);
        }
        CommitAttempt direct = resolved.reducer().evaluateDirectSingleTarget(
                attempt, step.message(), resolved.context().readStateIndex(),
                target.modelId(), target.modelType(), entity);
        return direct == null
                ? new DirectPreparation(resolved, false)
                : new DirectPreparation(null, true);
    }

    private static Change evaluateGraphChange(
            GraphChangeMessage message,
            CommitAttempt context,
            long readStateIndex,
            boolean alreadyStaged) {
        Change change = message.change;
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
                : change.after();
        return change.resolveAgainst(target, after);
    }

    private static long targetStateIndex(
            Entity<?> target,
            long fallback) {
        return target instanceof ModelRoot<?> root
                ? root.stateIndex() : fallback;
    }

    private static void mergeGraphChange(
            List<CommitAttempt.Step> steps,
            DeserializingMessage message,
            Change addition) {
        String eventMessageId = message.getMessageId();
        for (int i = steps.size() - 1; i >= 0; i--) {
            CommitAttempt.Step step = steps.get(i);
            DeserializingMessage existing = step.message();
            if (!Objects.equals(
                    existing.getMessageId(), eventMessageId)
                || (existing instanceof GraphChangeMessage)
                   != (message instanceof GraphChangeMessage)) {
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
            List<Change> changes = stagedChanges(graph);
            for (int index = changes.size() - 1; index >= 0; index--) {
                pending.addFirst(new PendingSubstep(
                        new GraphChangeMessage(changes.get(index), source), false));
            }
            return;
        }
        DeserializingMessage emitted = emittedMessage(
                source, output, preserveSourceIdentity);
        boolean reintercept =
                !emitted.getPayloadClass().equals(source.getPayloadClass());
        pending.addFirst(new PendingSubstep(emitted, reintercept));
    }

    private static List<Change> stagedChanges(Graph<?> graph) {
        Objects.requireNonNull(graph, "graph");
        List<Change> staged = Graphs.stagedChanges(graph);
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
        return List.of(Change.staged(
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

    static DeserializingMessage graphChangeReplay(
            DeserializingMessage eventMessage,
            String modelId,
            Class<?> modelType,
            Graphs.StagedReplay replay) {
        return new GraphChangeMessage(Change.staged(
                modelId, modelType, null, null, replay), eventMessage);
    }

    private static final class GraphChangeMessage extends DeserializingMessage {
        private final Change change;

        private GraphChangeMessage(
                Change change, DeserializingMessage eventMessage) {
            super(eventMessage);
            this.change = change;
        }
    }

    private record PendingSubstep(
            DeserializingMessage message, boolean interceptionAllowed) {
    }

    private record DirectPreparation(
            ResolvedSubstep resolved, boolean completed) {
        private static final DirectPreparation NONE =
                new DirectPreparation(null, false);
    }

    private enum Mode {
        APPLY(true, true, true, false),
        ASSERT(true, false, false, false),
        REAPPLY(false, true, false, true);

        private final boolean interception;
        private final boolean applyHandlers;
        private final boolean stageGraphPayloads;
        private final boolean rebaseGraphs;
        private final boolean assertions;

        Mode(
                boolean interception,
                boolean applyHandlers,
                boolean stageGraphPayloads,
                boolean rebaseGraphs) {
            this.interception = interception;
            this.applyHandlers = applyHandlers;
            this.stageGraphPayloads = stageGraphPayloads;
            this.rebaseGraphs = rebaseGraphs;
            this.assertions = !rebaseGraphs;
        }
    }

}
