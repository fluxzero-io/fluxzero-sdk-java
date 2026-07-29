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

import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable begin-state of the direct and resolved ancestor models loaded for one action.
 * <p>
 * Direct identities come from a {@link ModelTargetResolver.TargetPlan}. Read-only ancestor dependencies are resolved
 * through one bounded temporal graph request before the context is created. All entries share one
 * {@link #readStateIndex()}.
 */
public final class ModelActionContext {
    private final long readStateIndex;
    private final List<Entry> entries;
    private final List<ModelTargetResolver.DeferredWriteTarget> deferredWrites;

    private ModelActionContext(
            long readStateIndex,
            List<Entry> entries,
            List<ModelTargetResolver.DeferredWriteTarget> deferredWrites) {
        this.readStateIndex = readStateIndex;
        this.entries = List.copyOf(entries);
        this.deferredWrites = List.copyOf(deferredWrites);
    }

    /**
     * Creates an action context from one resolved target set and its loaded begin-state.
     *
     * @param readStateIndex one global state boundary shared by every supplied model
     * @param resolution     deduplicated direct and already-resolved ancestor targets used for the load
     * @param loadedModels   loaded entities keyed by their exact persisted ID string
     * @throws IllegalArgumentException if a target is missing, has an incompatible type, or extra state was loaded
     */
    public static ModelActionContext create(
            long readStateIndex,
            ModelTargetResolver.Resolution resolution,
            Map<String, ? extends Entity<?>> loadedModels) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(loadedModels, "loadedModels");
        Map<String, Entity<?>> remaining = new LinkedHashMap<>(loadedModels);
        List<Entry> entries = new ArrayList<>(resolution.models().size());
        for (ModelTargetResolver.ResolvedModel target : resolution.models()) {
            Entity<?> entity = remaining.remove(target.modelId());
            if (entity == null) {
                throw new IllegalArgumentException(
                        "Missing loaded model '%s' of type %s at state index %d"
                                .formatted(target.modelId(), target.modelType().getName(), readStateIndex));
            }
            validateLoadedEntity(target, entity);
            entries.add(new Entry(target, entity));
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException(
                    "Action load returned unrelated model IDs %s; only resolved action targets may enter the context"
                            .formatted(remaining.keySet()));
        }
        return new ModelActionContext(readStateIndex, entries, resolution.deferredWrites());
    }

    private static void validateLoadedEntity(ModelTargetResolver.ResolvedModel target, Entity<?> entity) {
        Object loadedId = entity.id();
        if (loadedId == null || !target.modelId().equals(loadedId.toString())) {
            throw new IllegalArgumentException(
                    "Loaded model for '%s' reports ID '%s'"
                            .formatted(target.modelId(), loadedId));
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

    /**
     * Attaches this context to a deserializing message and returns that message.
     */
    public DeserializingMessage attachTo(DeserializingMessage message) {
        return Objects.requireNonNull(message, "message").putContext(ModelActionContext.class, this);
    }

    /**
     * Global state boundary at which every model in this context was read.
     */
    public long readStateIndex() {
        return readStateIndex;
    }

    /**
     * Direct loaded model entries in target-plan order.
     */
    public List<Entry> entries() {
        return entries;
    }

    Entity<?> resolve(Class<?> modelType, String sourceProperty) {
        String entityIdProperty = Objects.requireNonNull(
                ModelMetadata.of(modelType).entityIdName(),
                () -> modelType.getName() + " has no @EntityId");
        Entity<?> candidate = null;
        Entity<?> secondCandidate = null;
        Entity<?> exact = null;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Class<?> targetType = entry.target().modelType();
            if (!(modelType.isAssignableFrom(targetType) || targetType.isAssignableFrom(modelType))) {
                continue;
            }
            boolean propertyMatch = entry.target().sourceProperties().contains(
                    sourceProperty == null ? entityIdProperty : sourceProperty);
            if (propertyMatch) {
                if (exact != null) {
                    throw ambiguous(modelType, sourceProperty == null ? entityIdProperty : sourceProperty,
                                    List.of(exact.id().toString(), entry.target().modelId()));
                }
                exact = entry.entity();
            }
            if (candidate == null) {
                candidate = entry.entity();
            } else if (secondCandidate == null) {
                secondCandidate = entry.entity();
            }
        }
        if (exact != null || sourceProperty != null) {
            return exact;
        }
        if (secondCandidate != null) {
            throw ambiguous(
                    modelType, null, entries.stream()
                            .filter(entry -> {
                                Class<?> targetType = entry.target().modelType();
                                return modelType.isAssignableFrom(targetType)
                                       || targetType.isAssignableFrom(modelType);
                            })
                            .map(entry -> entry.target().modelId()).toList());
        }
        return candidate;
    }

    Entry entry(String modelId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.target().modelId().equals(modelId)) {
                return entry;
            }
        }
        return null;
    }

    boolean mayWrite(String modelId, Class<?> modelType, String handler) {
        Entry entry = entry(modelId);
        if (entry == null) {
            return false;
        }
        if (entry.target().access().writes()) {
            return true;
        }
        for (int i = 0; i < deferredWrites.size(); i++) {
            ModelTargetResolver.DeferredWriteTarget deferred = deferredWrites.get(i);
            if (deferred.handler().equals(handler)
                && deferred.modelType().isAssignableFrom(modelType)
                && deferred.candidateModelIds().contains(modelId)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    ModelActionContext withValues(Map<String, Object> values) {
        if (values.isEmpty()) {
            return this;
        }
        List<Entry> updated = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!values.containsKey(entry.target().modelId())) {
                updated.add(entry);
                continue;
            }
            Object value = values.get(entry.target().modelId());
            Class modelType = value == null ? entry.target().modelType() : value.getClass();
            Entity<?> entity = entry.entity() instanceof ImmutableEntity<?> immutable
                    ? immutable.toBuilder().type(modelType).value(value).build()
                    : ImmutableEntity.builder()
                            .id(entry.entity().id())
                            .type(modelType)
                            .value(value)
                            .idProperty(ModelMetadata.of(entry.target().modelType()).entityIdName())
                            .build();
            updated.add(new Entry(entry.target(), entity));
        }
        return new ModelActionContext(readStateIndex, updated, deferredWrites);
    }

    private static IllegalStateException ambiguous(
            Class<?> modelType, String sourceProperty, List<String> modelIds) {
        return new IllegalStateException(
                "Action context contains multiple %s models%s: %s. Qualify the handler parameter with "
                        .formatted(modelType.getName(),
                                   sourceProperty == null ? "" : " for payload property '" + sourceProperty + "'",
                                   modelIds)
                + "@Association(\"payloadProperty\").");
    }

    /**
     * One resolved direct target and its loaded begin-state.
     *
     * @param target resolved target descriptor
     * @param entity loaded model begin-state
     */
    public record Entry(ModelTargetResolver.ResolvedModel target, Entity<?> entity) {
        public Entry {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(entity, "entity");
        }
    }
}
