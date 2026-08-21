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

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Internal state of one model commit attempt, from its loaded begin-state through ordered changes and completion.
 * Instances used only as a handler read context leave the evaluation and completion portions empty.
 */
public final class CommitAttempt {
    private static final MutationPlan.Resolution EMPTY_RESOLUTION =
            new MutationPlan.Resolution(List.of(), List.of());

    private long readStateIndex = -1L;
    private MutationPlan.Resolution resolution = EMPTY_RESOLUTION;
    private Map<String, Entity<?>> entities = Map.of();

    private List<String> readModelIds = List.of();
    private Map<String, Class<?>> readModelTypes = Map.of();
    private List<Step> steps = List.of();
    private List<Change> changes = List.of();
    private Set<String> cascadeRootIds = Set.of();
    private volatile CompletableFuture<Object> completion;
    private boolean submitted;

    CommitAttempt() {
    }

    static CommitAttempt fromSteps(
            long readStateIndex,
            Collection<String> readModelIds,
            Map<String, Class<?>> readModelTypes,
            List<Step> steps) {
        CommitAttempt result = new CommitAttempt();
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
            CommitAttempt result = new CommitAttempt();
            result.readStateIndex = readStateIndex;
            result.resolution = resolution;
            result.entities = Map.of(target.modelId(), entity);
            return result;
        }
        LinkedHashMap<String, Entity<?>> entities =
                new LinkedHashMap<>(resolution.models().size());
        Map<String, Entity<?>> remaining = new LinkedHashMap<>(loadedModels);
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            Entity<?> entity = remaining.remove(target.modelId());
            if (entity == null) {
                throw missing(target, readStateIndex);
            }
            validateLoadedEntity(target, entity);
            entities.put(target.modelId(), entity);
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException(
                    "Commit load returned unrelated model IDs %s; only resolved commit targets may enter the context"
                            .formatted(remaining.keySet()));
        }
        CommitAttempt result = new CommitAttempt();
        result.readStateIndex = readStateIndex;
        result.resolution = resolution;
        result.entities = immutable(entities);
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
        CommitAttempt result = new CommitAttempt();
        result.readStateIndex = readStateIndex;
        result.resolution = new MutationPlan.Resolution(List.of(target), List.of());
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
        ArrayList<String> result = new ArrayList<>(resolution.models().size());
        resolution.models().forEach(target -> result.add(target.modelId()));
        return List.copyOf(result);
    }

    public List<MutationPlan.ResolvedModel> targets() {
        return resolution.models();
    }

    public MutationPlan.ResolvedModel target(String modelId) {
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            if (target.modelId().equals(modelId)) {
                return target;
            }
        }
        return null;
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
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            Class<?> targetType = target.modelType();
            if (!EntityMetadata.compatibleTypes(modelType, targetType)) {
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
            throw ambiguous(modelType, null, resolution.models().stream()
                    .filter(target -> EntityMetadata.compatibleTypes(
                            modelType, target.modelType()))
                    .map(MutationPlan.ResolvedModel::modelId).toList());
        }
        return candidate;
    }

    MutationPlan.DirectReferences references(EntityMetadata.ModelParameter parameter) {
        return resolution.references().get(parameter);
    }

    boolean mayWrite(String modelId, Class<?> modelType, Executable handler) {
        MutationPlan.ResolvedModel target = target(modelId);
        if (target == null) {
            return false;
        }
        if (target.access().writes()) {
            return true;
        }
        String handlerSignature = handler == null ? null : handler.toGenericString();
        for (MutationPlan.DeferredWriteTarget deferred : resolution.deferredWrites()) {
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
            MutationPlan.ResolvedModel target = target(modelId);
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
        CommitAttempt result = new CommitAttempt();
        result.readStateIndex = readStateIndex;
        result.resolution = resolution;
        result.entities = immutable(updated);
        return result;
    }

    void evaluated(
            long stateIndex,
            Collection<String> readIds,
            Map<String, Class<?>> readTypes,
            List<Step> steps) {
        readStateIndex = stateIndex;
        readModelIds = List.copyOf(readIds);
        readModelTypes = Map.copyOf(readTypes);
        this.steps = List.copyOf(steps);
        ArrayList<Change> ordered = new ArrayList<>();
        for (Step step : this.steps) {
            ordered.addAll(step.changes());
        }
        changes = List.copyOf(ordered);
    }

    void cascadeRoots(Set<String> modelIds) {
        cascadeRootIds = Set.copyOf(modelIds);
    }

    public List<String> readModelIds() {
        return readModelIds;
    }

    Map<String, Class<?>> readModelTypes() {
        return readModelTypes;
    }

    public List<Step> steps() {
        return steps;
    }

    public List<Change> transitions() {
        return changes;
    }

    public Set<String> cascadeRootIds() {
        return cascadeRootIds;
    }

    ModelConflictPolicy conflictPolicy(ModelConflictPolicy configured) {
        ModelConflictPolicy application = ModelConflictPolicy.resolve(configured);
        if (changes.size() == 1 && readModelTypes.size() == 1
            && readModelTypes.containsKey(changes.getFirst().modelId())) {
            return transitionPolicy(changes.getFirst(), application);
        }
        ModelConflictPolicy result = ModelConflictPolicy.ACCEPT;
        Set<String> written = new HashSet<>();
        for (Change change : changes) {
            written.add(change.modelId());
            result = strictest(result, transitionPolicy(change, application));
        }
        for (Map.Entry<String, Class<?>> entry : readModelTypes.entrySet()) {
            if (!written.contains(entry.getKey())) {
                result = strictest(result, inherit(modelPolicy(entry.getValue()), application));
            }
        }
        return result;
    }

    CompletableFuture<Object> completion() {
        CompletableFuture<Object> result = completion;
        if (result == null) {
            synchronized (this) {
                result = completion;
                if (result == null) {
                    completion = result = new CompletableFuture<>();
                }
            }
        }
        return result;
    }

    void submit(Supplier<CompletableFuture<Object>> execution) {
        synchronized (this) {
            if (submitted) {
                throw new IllegalStateException("Model commit attempt was awaited twice");
            }
            submitted = true;
        }
        Objects.requireNonNull(execution.get(), "execution").whenComplete((value, failure) -> {
            if (failure == null) {
                completion().complete(value);
            } else {
                fail(failure);
            }
        });
    }

    void fail(Throwable failure) {
        completion().completeExceptionally(failure);
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

}
