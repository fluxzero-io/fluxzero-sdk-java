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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.lang.reflect.Executable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    static CommitEvaluation apply(
            DeserializingMessage message,
            SubstepResolver resolver) {
        return apply(List.of(message), resolver);
    }

    static CommitEvaluation apply(
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return execute(messages, resolver, true, true, true, false);
    }

    static CommitEvaluation assertLegal(
            DeserializingMessage message,
            SubstepResolver resolver) {
        return execute(List.of(message), resolver, true, false, false, false);
    }

    static CommitEvaluation reapply(
            DeserializingMessage message,
            SubstepResolver resolver) {
        return reapply(List.of(message), resolver);
    }

    static CommitEvaluation reapply(
            List<DeserializingMessage> messages,
            SubstepResolver resolver) {
        return execute(messages, resolver, false, true, false, true);
    }

    /** Compatibility entry point; replay application itself is owned by the supplied definition. */
    public static Object replay(
            DeserializingMessage event,
            ModelCommitContext context,
            ModelDefinition definition,
            String targetModelId) {
        return definition.replay(event, context, targetModelId);
    }

    /** Executes every mutation form through one ordered substep pipeline. */
    private static CommitEvaluation execute(
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
                        new GraphChangeMessage(changeMessage.change.forRebase()), false));
            } else {
                pending.add(new PendingSubstep(message, interception));
            }
        }
        return evaluate(
                pending, resolver,
                reapply ? null : messages.getFirst(),
                applyHandlers, !reapply);
    }

    private static CommitEvaluation evaluate(
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

                List<Transition> transitions = resolved.mutation().apply(
                        current.message(), context,
                        applyHandlers, assertions);
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
            ModelDefinition.Mutation mutation) {
        ResolvedSubstep {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(mutation, "mutation");
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
        static TransitionEffect resolve(
                Class<?> modelType,
                Executable handler,
                Object before,
                Object after,
                boolean cascadedDeletion) {
            return resolve(
                    modelType, before, after, cascadedDeletion,
                    ModelDefinition.EffectOverrides.of(handler));
        }

        static TransitionEffect resolve(
                Class<?> modelType,
                Object before,
                Object after,
                boolean cascadedDeletion,
                ModelDefinition.EffectOverrides overrides) {
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

}
