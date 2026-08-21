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
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Internal state of one model commit attempt, from its loaded begin-state through ordered changes and completion.
 * Instances used only as a handler read context leave the evaluation and completion portions empty.
 */
public final class CommitAttempt implements CommitDependency {
    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);
    private long readStateIndex = -1L;
    private Map<String, MutationPlan.ResolvedModel> targets = Map.of();
    private Map<String, Entity<?>> entities = Map.of();
    private List<MutationPlan.DeferredWriteTarget> deferredWrites = List.of();

    private Evaluation evaluation;
    private Lifecycle lifecycle;
    private Staging staging;

    CommitAttempt() {
        lifecycle = Lifecycle.direct();
    }

    static CommitAttempt detached() {
        return new CommitAttempt(false);
    }

    @Override
    public CommitAttempt attempt() {
        return this;
    }

    static CommitAttempt batched(
            ModelCommitPolicy policy,
            boolean released,
            Runnable flushBatch) {
        CommitAttempt result = new CommitAttempt(false);
        result.lifecycle = Lifecycle.batched(policy, released, flushBatch);
        return result;
    }

    private CommitAttempt(boolean lifecycle) {
        if (lifecycle) {
            this.lifecycle = Lifecycle.direct();
        }
    }

    static CommitAttempt fromSteps(
            long readStateIndex,
            Collection<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            List<Step> steps) {
        CommitAttempt result = new CommitAttempt(false);
        result.evaluated(
                readStateIndex, readModelIds, readModelTypes,
                steps);
        return result;
    }

    static CommitAttempt fromChanges(
            long readStateIndex,
            Collection<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            DeserializingMessage message,
            List<Change> changes) {
        return fromSteps(
                readStateIndex, readModelIds, readModelTypes,
                List.of(new Step(message, changes)));
    }

    /** Creates a loaded begin-state for one resolved target set. */
    public static CommitAttempt create(
            long readStateIndex,
            MutationPlan.Resolution resolution,
            Map<String, ? extends Entity<?>> loadedModels) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(loadedModels, "loadedModels");
        if (resolution.models().size() == 1 && loadedModels.size() == 1) {
            MutationPlan.ResolvedModel target = resolution.models().getFirst();
            Entity<?> entity = loadedModels.get(target.modelId());
            if (entity == null) {
                throw missing(target, readStateIndex);
            }
            validateLoadedEntity(target, entity);
            CommitAttempt result = new CommitAttempt(false);
            result.readStateIndex = readStateIndex;
            result.targets = Map.of(target.modelId(), target);
            result.entities = Map.of(target.modelId(), entity);
            result.deferredWrites = List.copyOf(resolution.deferredWrites());
            return result;
        }
        LinkedHashMap<String, MutationPlan.ResolvedModel> targets =
                new LinkedHashMap<>(resolution.models().size());
        LinkedHashMap<String, Entity<?>> entities =
                new LinkedHashMap<>(resolution.models().size());
        Map<String, Entity<?>> remaining = new LinkedHashMap<>(loadedModels);
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            Entity<?> entity = remaining.remove(target.modelId());
            if (entity == null) {
                throw missing(target, readStateIndex);
            }
            validateLoadedEntity(target, entity);
            targets.put(target.modelId(), target);
            entities.put(target.modelId(), entity);
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException(
                    "Commit load returned unrelated model IDs %s; only resolved commit targets may enter the context"
                            .formatted(remaining.keySet()));
        }
        CommitAttempt result = new CommitAttempt(false);
        result.readStateIndex = readStateIndex;
        result.targets = immutable(targets);
        result.entities = immutable(entities);
        result.deferredWrites = List.copyOf(resolution.deferredWrites());
        return result;
    }

    private static IllegalArgumentException missing(
            MutationPlan.ResolvedModel target, long readStateIndex) {
        return new IllegalArgumentException(
                "Missing loaded model '%s' of type %s at state index %d"
                        .formatted(target.modelId(), target.modelType().getName(), readStateIndex));
    }

    /** Creates the allocation-minimal begin-state used by a direct single-model strategy. */
    public static CommitAttempt createSingle(
            long readStateIndex,
            String modelId,
            Class<?> modelType,
            MutationPlan.Access access,
            List<String> sourceProperties,
            Entity<?> entity) {
        MutationPlan.ResolvedModel target = new MutationPlan.ResolvedModel(
                modelId, modelType, access, sourceProperties);
        validateLoadedEntity(target, entity);
        CommitAttempt result = new CommitAttempt(false);
        result.readStateIndex = readStateIndex;
        result.targets = Map.of(modelId, target);
        result.entities = Map.of(modelId, entity);
        return result;
    }

    private static void validateLoadedEntity(
            MutationPlan.ResolvedModel target, Entity<?> entity) {
        Object loadedId = entity.id();
        if (loadedId == null || !target.modelId().equals(loadedId.toString())) {
            throw new IllegalArgumentException(
                    "Loaded model for '%s' reports ID '%s'".formatted(target.modelId(), loadedId));
        }
        Class<?> loadedType = entity.type();
        if (loadedType == null || !target.modelType().isAssignableFrom(loadedType)) {
            throw new IllegalArgumentException(
                    "Loaded model '%s' has incompatible type %s; expected %s"
                            .formatted(target.modelId(),
                                       loadedType == null ? "null" : loadedType.getName(),
                                       target.modelType().getName()));
        }
    }

    public long readStateIndex() {
        return readStateIndex;
    }

    public List<String> modelIds() {
        return List.copyOf(targets.keySet());
    }

    public List<MutationPlan.ResolvedModel> targets() {
        return List.copyOf(targets.values());
    }

    public MutationPlan.ResolvedModel target(String modelId) {
        return targets.get(modelId);
    }

    public Entity<?> entity(String modelId) {
        return entities.get(modelId);
    }

    public Map<String, Entity<?>> entities() {
        return entities;
    }

    public DeserializingMessage attachTo(DeserializingMessage message) {
        return Objects.requireNonNull(message, "message").putContext(CommitAttempt.class, this);
    }

    Entity<?> resolve(Class<?> modelType, String sourceProperty) {
        String entityIdProperty = Objects.requireNonNull(
                EntityMetadata.of(modelType).entityIdName(),
                () -> modelType.getName() + " has no @EntityId");
        Entity<?> candidate = null;
        Entity<?> secondCandidate = null;
        Entity<?> exact = null;
        for (MutationPlan.ResolvedModel target : targets.values()) {
            Class<?> targetType = target.modelType();
            if (!(modelType.isAssignableFrom(targetType) || targetType.isAssignableFrom(modelType))) {
                continue;
            }
            boolean propertyMatch = target.sourceProperties().contains(
                    sourceProperty == null ? entityIdProperty : sourceProperty);
            if (propertyMatch) {
                if (exact != null) {
                    throw ambiguous(modelType, sourceProperty == null ? entityIdProperty : sourceProperty,
                                    List.of(exact.id().toString(), target.modelId()));
                }
                exact = entities.get(target.modelId());
            }
            if (candidate == null) {
                candidate = entities.get(target.modelId());
            } else if (secondCandidate == null) {
                secondCandidate = entities.get(target.modelId());
            }
        }
        if (exact != null || sourceProperty != null) {
            return exact;
        }
        if (secondCandidate != null) {
            throw ambiguous(modelType, null, targets.values().stream()
                    .filter(target -> compatible(modelType, target.modelType()))
                    .map(MutationPlan.ResolvedModel::modelId).toList());
        }
        return candidate;
    }

    boolean mayWrite(String modelId, Class<?> modelType, Executable handler) {
        MutationPlan.ResolvedModel target = targets.get(modelId);
        if (target == null) {
            return false;
        }
        if (target.access().writes()) {
            return true;
        }
        String handlerSignature = handler == null ? null : handler.toGenericString();
        for (MutationPlan.DeferredWriteTarget deferred : deferredWrites) {
            if (deferred.handler().equals(handlerSignature)
                && deferred.modelType().isAssignableFrom(modelType)
                && deferred.candidateModelIds().contains(modelId)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    CommitAttempt withValues(Map<String, Object> values) {
        if (values.isEmpty()) {
            return this;
        }
        LinkedHashMap<String, Entity<?>> updated = new LinkedHashMap<>(entities);
        values.forEach((modelId, value) -> {
            Entity<?> current = updated.get(modelId);
            MutationPlan.ResolvedModel target = targets.get(modelId);
            if (current == null || target == null) {
                return;
            }
            Class modelType = value == null ? target.modelType() : value.getClass();
            Entity<?> entity = current instanceof ImmutableEntity<?> immutable
                    ? immutable.toBuilder().type(modelType).value(value).build()
                    : ImmutableEntity.builder()
                            .id(current.id()).type(modelType).value(value)
                            .idProperty(EntityMetadata.of(target.modelType()).entityIdName()).build();
            updated.put(modelId, entity);
        });
        CommitAttempt result = new CommitAttempt(false);
        result.readStateIndex = readStateIndex;
        result.targets = targets;
        result.entities = immutable(updated);
        result.deferredWrites = deferredWrites;
        return result;
    }

    void evaluated(
            long stateIndex,
            Collection<String> readIds,
            Map<String, Class<?>> readTypes,
            List<Step> steps) {
        readStateIndex = stateIndex;
        List<String> copiedReadIds = List.copyOf(readIds);
        Map<String, Class<?>> copiedReadTypes = Map.copyOf(readTypes);
        List<Step> copiedSteps = List.copyOf(steps);
        ArrayList<Change> ordered = new ArrayList<>();
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Step step : copiedSteps) {
            ordered.addAll(step.changes());
            step.changes().forEach(change ->
                    values.put(change.modelId(), change.after()));
        }
        evaluation = new Evaluation(
                copiedReadIds, copiedReadTypes, copiedSteps, List.copyOf(ordered),
                immutable(values));
    }

    void cascadeRoots(Set<String> modelIds) {
        Objects.requireNonNull(evaluation, "Commit attempt has not been evaluated")
                .cascadeRootIds = Set.copyOf(modelIds);
    }

    public List<String> readModelIds() {
        return evaluation().readModelIds;
    }

    Map<String, Class<?>> readModelTypes() {
        return evaluation().readModelTypes;
    }

    public List<Step> steps() {
        return evaluation().steps;
    }

    public List<Change> transitions() {
        return evaluation().changes;
    }

    Map<String, Object> finalValues() {
        return evaluation().finalValues;
    }

    public Set<String> cascadeRootIds() {
        return evaluation().cascadeRootIds;
    }

    List<DeserializingMessage> rebaseMessages() {
        if (transitions().stream().noneMatch(Change::graphChange)) {
            return steps().stream().map(Step::message).toList();
        }
        List<DeserializingMessage> result = new ArrayList<>(steps().size() + 1);
        for (Step step : steps()) {
            List<Change> group = step.changes();
            if (group.isEmpty()) {
                continue;
            }
            if (group.stream().noneMatch(Change::graphChange)) {
                result.add(step.message());
                continue;
            }
            DeserializingMessage eventMessage = step.message();
            if (group.stream().anyMatch(change -> !change.graphChange())) {
                result.add(eventMessage);
            }
            group.stream().filter(Change::graphChange)
                    .map(change -> change.graphReplay(eventMessage))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    boolean hasCascadedDeletion() {
        return transitions().stream().anyMatch(Change::cascadedDeletion);
    }

    public CommitAttempt prepared(List<Step> steps) {
        CommitAttempt result = detached();
        result.evaluated(
                readStateIndex(), readModelIds(), readModelTypes(), steps);
        result.cascadeRoots(cascadeRootIds());
        return result;
    }

    ModelConflictPolicy conflictPolicy(ModelConflictPolicy configured) {
        ModelConflictPolicy application = ModelConflictPolicy.resolve(configured);
        Evaluation state = evaluation();
        if (state.changes.size() == 1 && state.readModelTypes.size() == 1
            && state.readModelTypes.containsKey(state.changes.getFirst().modelId())) {
            return transitionPolicy(state.changes.getFirst(), application);
        }
        ModelConflictPolicy result = ModelConflictPolicy.ACCEPT;
        Set<String> written = new HashSet<>();
        for (Change change : state.changes) {
            written.add(change.modelId());
            result = strictest(result, transitionPolicy(change, application));
        }
        for (Map.Entry<String, Class<?>> entry : state.readModelTypes.entrySet()) {
            if (!written.contains(entry.getKey())) {
                result = strictest(result, inherit(modelPolicy(entry.getValue()), application));
            }
        }
        return result;
    }

    void stageAt(int position, int segment, boolean tracked) {
        staging = new Staging(position, segment, tracked);
        if (tracked && lifecycle == null) {
            lifecycle = Lifecycle.direct();
        }
    }

    void stageModel(
            String modelId,
            Class<?> modelType,
            Object value,
            boolean existedBefore,
            long sequenceNumber,
            Set<String> beforeAliases,
            Set<String> afterAliases) {
        Staging state = staging();
        state.models.put(modelId, new StagedValue(
                value, modelType, sequenceNumber, existedBefore));
        beforeAliases.stream().filter(alias -> !afterAliases.contains(alias)).forEach(alias -> {
            state.availableAliases.remove(alias);
            state.removedAliases.put(alias, modelId);
        });
        afterAliases.forEach(alias -> {
            state.removedAliases.remove(alias);
            state.availableAliases.put(alias, modelId);
        });
    }

    Set<String> stagedModelIds() {
        return staging().models.keySet();
    }

    Set<String> stagedKeys() {
        Staging state = staging();
        LinkedHashSet<String> result = new LinkedHashSet<>(state.models.keySet());
        result.addAll(state.removedAliases.keySet());
        result.addAll(state.availableAliases.keySet());
        return result;
    }

    boolean exact(String requestedId) {
        return staging().models.containsKey(requestedId);
    }

    String stagedModelId(String requestedId) {
        if (exact(requestedId)) {
            return requestedId;
        }
        Staging state = staging();
        String result = state.availableAliases.get(requestedId);
        return result == null ? state.removedAliases.get(requestedId) : result;
    }

    boolean stagedAvailable(String requestedId) {
        Staging state = staging();
        return state.models.containsKey(requestedId)
               || state.availableAliases.containsKey(requestedId);
    }

    Object stagedValue(String requestedId) {
        String modelId = stagedModelId(requestedId);
        StagedValue value = stagedAvailable(requestedId) && modelId != null
                ? staging().models.get(modelId) : null;
        return value == null ? null : value.value;
    }

    Class<?> stagedType(String requestedId) {
        StagedValue value = staging().models.get(stagedModelId(requestedId));
        return value == null ? null : value.type;
    }

    boolean stagedExistedBefore(String requestedId) {
        StagedValue value = staging().models.get(stagedModelId(requestedId));
        return value != null && value.existedBefore;
    }

    long stagedSequence(String requestedId) {
        StagedValue value = staging().models.get(stagedModelId(requestedId));
        return value == null ? -1L : value.sequenceNumber;
    }

    int batchPosition() {
        return staging().position;
    }

    int batchSegment() {
        return staging().segment;
    }

    boolean trackedCompletion() {
        return staging().trackedCompletion;
    }

    void dependsOn(CommitAttempt producer) {
        if (producer != this) {
            lifecycle().dependencies().add(producer);
        }
    }

    CompletableFuture<Void> initialization() {
        return lifecycle == null ? COMPLETED : lifecycle.initialized;
    }

    void initialize(Collection<String> resolvedModelIds) {
        Lifecycle state = lifecycle();
        state.modelIds = state.batched ? Set.copyOf(resolvedModelIds) : null;
        state.initialized.complete(null);
    }

    void submitAfterRelease(
            Function<Boolean, CompletableFuture<Object>> action) {
        Lifecycle state = lifecycle();
        if (!state.arrived.compareAndSet(false, true)) {
            throw new IllegalStateException("Model commit attempt was awaited twice");
        }
        CompletableFuture<Object> submitted = state.release.thenCompose(ignored -> {
            boolean dependent = hasDependencies();
            if (dependent) {
                detachTransport();
            }
            return dependencyCompletion().thenCompose(unused ->
                    Objects.requireNonNull(action.apply(dependent), "Model commit attempt returned null"));
        }).whenComplete((ignored, failure) -> settleTransport());
        submitted.whenComplete((value, failure) -> {
            if (failure == null) {
                complete(value);
            } else {
                fail(failure);
            }
        });
    }

    void fail(Throwable failure) {
        Lifecycle state = lifecycle();
        state.completion.completeExceptionally(failure);
        state.initialized.completeExceptionally(failure);
        state.release.completeExceptionally(failure);
        settleTransport();
    }

    CompletableFuture<Object> completion() {
        return lifecycle().completion;
    }

    void complete(Object result) {
        lifecycle().completion.complete(result);
    }

    void release() {
        lifecycle().release.complete(null);
    }

    void transport(ModelCommitBatchingClient.ModelCommitBatch batch, int batchSlot) {
        Lifecycle state = lifecycle();
        state.transport = batch;
        state.slot = batchSlot;
    }

    synchronized void detachTransport() {
        settleTransport();
        Lifecycle state = lifecycle();
        state.transport = null;
        state.slot = -1;
    }

    void flushTransport() {
        if (lifecycle != null && lifecycle.flushBatch != null) {
            lifecycle.flushBatch.run();
        }
    }

    private void settleTransport() {
        if (lifecycle != null && lifecycle.transport != null) {
            lifecycle.transport.skip(lifecycle.slot);
        }
    }

    boolean batched() {
        return lifecycle != null && lifecycle.batched;
    }

    ModelCommitPolicy policy() {
        return lifecycle().policy;
    }

    Set<String> resolvedModelIds() {
        return lifecycle().modelIds;
    }

    boolean hasDependencies() {
        return lifecycle != null && lifecycle.dependencies != null
               && !lifecycle.dependencies.isEmpty();
    }

    int dependencyCount() {
        return lifecycle == null || lifecycle.dependencies == null
                ? 0 : lifecycle.dependencies.size();
    }

    CompletableFuture<Void> dependencyCompletion() {
        return lifecycle == null || lifecycle.dependencies == null
               || lifecycle.dependencies.isEmpty() ? COMPLETED
                : CompletableFuture.allOf(lifecycle.dependencies.stream()
                        .map(CommitAttempt::completion).toArray(CompletableFuture[]::new));
    }

    ModelCommitBatchingClient.ModelCommitBatch transportBatch() {
        return lifecycle().transport;
    }

    int transportSlot() {
        return lifecycle().slot;
    }

    private Evaluation evaluation() {
        return evaluation == null ? Evaluation.EMPTY : evaluation;
    }

    private Lifecycle lifecycle() {
        Lifecycle result = lifecycle;
        if (result == null) {
            synchronized (this) {
                result = lifecycle;
                if (result == null) {
                    lifecycle = result = Lifecycle.direct();
                }
            }
        }
        return result;
    }

    private Staging staging() {
        return Objects.requireNonNull(staging, "Commit attempt has not been staged");
    }

    private static IllegalStateException ambiguous(
            Class<?> modelType, String sourceProperty, List<String> modelIds) {
        return new IllegalStateException(
                "Commit context contains multiple %s models%s: %s. Qualify the handler parameter with "
                        .formatted(modelType.getName(),
                                   sourceProperty == null ? "" : " for payload property '" + sourceProperty + "'",
                                   modelIds)
                + "@Association(\"payloadProperty\").");
    }

    private static ModelConflictPolicy transitionPolicy(
            Change change, ModelConflictPolicy application) {
        ModelConflictPolicy result = inherit(change.conflictPolicy(), application);
        return change.before() == null && change.beforeSequenceNumber() < 0L
               && result == ModelConflictPolicy.ACCEPT ? ModelConflictPolicy.FAIL : result;
    }

    private static ModelConflictPolicy modelPolicy(Class<?> type) {
        return EntityMetadata.of(type).rootConfiguration()
                .filter(configuration -> configuration.kind() == EntityMetadata.RootKind.MODEL)
                .map(EntityMetadata.RootConfiguration::conflictPolicy)
                .orElse(ModelConflictPolicy.DEFAULT);
    }

    private static ModelConflictPolicy inherit(
            ModelConflictPolicy declared, ModelConflictPolicy application) {
        return declared == null || declared == ModelConflictPolicy.DEFAULT ? application : declared;
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

    private static boolean compatible(Class<?> left, Class<?> right) {
        return left.isAssignableFrom(right) || right.isAssignableFrom(left);
    }

    private static <K, V> Map<K, V> immutable(LinkedHashMap<K, V> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        if (values.size() == 1) {
            Map.Entry<K, V> entry = values.entrySet().iterator().next();
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    /** One ordered mutation journal entry. */
    public record Step(
            DeserializingMessage message,
            List<Change> changes) {
        public Step {
            changes = List.copyOf(changes);
        }
    }

    private static final class Evaluation {
        private static final Evaluation EMPTY = new Evaluation(
                List.of(), Map.of(), List.of(), List.of(), Map.of());

        private final List<String> readModelIds;
        private final Map<String, Class<?>> readModelTypes;
        private final List<Step> steps;
        private final List<Change> changes;
        private final Map<String, Object> finalValues;
        private Set<String> cascadeRootIds = Set.of();

        private Evaluation(
                List<String> readModelIds,
                Map<String, Class<?>> readModelTypes,
                List<Step> steps,
                List<Change> changes,
                Map<String, Object> finalValues) {
            this.readModelIds = readModelIds;
            this.readModelTypes = readModelTypes;
            this.steps = steps;
            this.changes = changes;
            this.finalValues = finalValues;
        }
    }

    private static final class Lifecycle {
        private final CompletableFuture<Object> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> initialized;
        private final CompletableFuture<Void> release;
        private final AtomicBoolean arrived = new AtomicBoolean();
        private final ModelCommitPolicy policy;
        private final boolean batched;
        private final Runnable flushBatch;
        private volatile Set<CommitAttempt> dependencies;
        private volatile Set<String> modelIds;
        private volatile ModelCommitBatchingClient.ModelCommitBatch transport;
        private volatile int slot = -1;

        private Lifecycle(
                CompletableFuture<Void> initialized,
                CompletableFuture<Void> release,
                ModelCommitPolicy policy,
                boolean batched,
                Runnable flushBatch) {
            this.initialized = initialized;
            this.release = release;
            this.policy = policy;
            this.batched = batched;
            this.flushBatch = flushBatch;
        }

        private static Lifecycle direct() {
            return new Lifecycle(COMPLETED, COMPLETED, null, false, null);
        }

        private static Lifecycle batched(
                ModelCommitPolicy policy, boolean released, Runnable flushBatch) {
            return new Lifecycle(
                    new CompletableFuture<>(),
                    released ? COMPLETED : new CompletableFuture<>(),
                    Objects.requireNonNull(policy, "policy"), true,
                    Objects.requireNonNull(flushBatch, "flushBatch"));
        }

        private Set<CommitAttempt> dependencies() {
            Set<CommitAttempt> result = dependencies;
            if (result == null) {
                synchronized (this) {
                    result = dependencies;
                    if (result == null) {
                        dependencies = result = ConcurrentHashMap.newKeySet();
                    }
                }
            }
            return result;
        }
    }

    private static final class Staging {
        private final int position;
        private final int segment;
        private final boolean trackedCompletion;
        private final Map<String, StagedValue> models = new LinkedHashMap<>();
        private final Map<String, String> availableAliases = new LinkedHashMap<>();
        private final Map<String, String> removedAliases = new LinkedHashMap<>();

        private Staging(int position, int segment, boolean trackedCompletion) {
            this.position = position;
            this.segment = segment;
            this.trackedCompletion = trackedCompletion;
        }
    }

    private record StagedValue(
            Object value,
            Class<?> type,
            long sequenceNumber,
            boolean existedBefore) {
    }
}

interface CommitDependency {
    CommitAttempt attempt();
}
