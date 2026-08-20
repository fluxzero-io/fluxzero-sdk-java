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

import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;

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
 * Ordered commit orchestration for the mutation applicators owned by {@link ModelDefinition}.
 * <p>
 * Interceptor outputs form ordered substeps under one pinned read boundary. Every mutation within one substep reads
 * its same immutable begin-state; only a successfully completed substep becomes visible to later substeps. This class
 * owns ordering and staged Graph changes, but delegates handler invocation and result validation to the definition.
 */
public final class ModelExecutionPlan {
    private static final int MAX_SUBSTEPS = 10_000;

    private ModelExecutionPlan() {
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
        return execute(attempt, messages, resolver, true, true, true, false);
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
        return execute(attempt, List.of(message), resolver, true, false, false, false);
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
        return execute(attempt, messages, resolver, false, true, false, true);
    }

    /** Executes every mutation form through one ordered substep pipeline. */
    private static CommitAttempt execute(
            CommitAttempt attempt,
            List<DeserializingMessage> messages,
            SubstepResolver resolver,
            boolean interception,
            boolean applyHandlers,
            boolean stageGraphPayloads,
            boolean reapply) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A model execution requires at least one message");
        }
        Deque<PendingSubstep> pending = new ArrayDeque<>(messages.size());
        for (DeserializingMessage message : messages) {
            Objects.requireNonNull(message, "message");
            if (stageGraphPayloads && message.getPayload() instanceof Graph<?> graph) {
                enqueueOutput(message, graph, pending, false);
            } else if (reapply && message instanceof GraphChangeMessage changeMessage) {
                pending.add(new PendingSubstep(
                        new GraphChangeMessage(
                                changeMessage.change.forRebase(), changeMessage), false));
            } else {
                pending.add(new PendingSubstep(message, interception));
            }
        }
        return evaluate(
                attempt, pending, resolver,
                reapply ? null : messages.getFirst(),
                applyHandlers, !reapply);
    }

    private static CommitAttempt evaluate(
            CommitAttempt attempt,
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
        List<DeserializingMessage> stepMessages = new ArrayList<>();
        List<List<Change>> changesByStep = new ArrayList<>();
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
                    mergeGraphChange(
                            stepMessages, changesByStep,
                            graphChangeMessage, change);
                    continue;
                }
                if (current.interceptionAllowed()) {
                    Object interception =
                            resolved.mutation().intercept(
                                    current.message(), context);
                    if (resolved.mutation().intercepted(interception)) {
                        enqueueOutputs(
                                current.message(),
                                resolved.mutation().interceptionOutput(interception),
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

                List<Change> transitions = resolved.mutation().apply(
                        current.message(), context,
                        applyHandlers, assertions);
                for (Change transition : transitions) {
                    stagedValues.put(transition.modelId(), transition.after());
                    if (!readModelTypes.containsKey(transition.modelId())) {
                        readModelIds.add(transition.modelId());
                        readModelTypes.putIfAbsent(
                                transition.modelId(),
                                transition.modelType());
                    }
                }
                stepMessages.add(current.message());
                changesByStep.add(transitions);
            }
            attempt.evaluated(
                    readStateIndex, readModelIds, readModelTypes,
                    stepMessages, changesByStep);
            return attempt;
        } finally {
            CommitAttempt restore =
                    originalContext == null ? commitBeginContext : originalContext;
            if (restore != null && initialMessage != null) {
                restore.attachTo(initialMessage);
            }
        }
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
            List<DeserializingMessage> stepMessages,
            List<List<Change>> changesByStep,
            DeserializingMessage message,
            Change addition) {
        String eventMessageId = message.getMessageId();
        for (int i = stepMessages.size() - 1; i >= 0; i--) {
            DeserializingMessage existing = stepMessages.get(i);
            if (!Objects.equals(
                    existing.getMessageId(), eventMessageId)
                || (existing instanceof GraphChangeMessage)
                   != (message instanceof GraphChangeMessage)) {
                continue;
            }
            LinkedHashMap<String, Change> transitions =
                    new LinkedHashMap<>();
            changesByStep.get(i).forEach(
                    transition -> transitions.merge(
                            transition.modelId(), transition, Change::then));
            transitions.merge(addition.modelId(), addition, Change::then);
            changesByStep.set(i, List.copyOf(transitions.values()));
            return;
        }
        stepMessages.add(message);
        changesByStep.add(List.of(addition));
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
            ModelDefinition.Mutation mutation) {
        ResolvedSubstep {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(mutation, "mutation");
        }
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

}
