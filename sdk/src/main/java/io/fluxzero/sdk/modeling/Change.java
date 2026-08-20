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
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.lang.reflect.Executable;
import java.util.Objects;
import java.util.Optional;

/** One immutable model mutation outcome, from a staged Graph operation through commit preparation. */
record Change(
        String modelId, Class<?> modelType, Long expectedStateIndex,
        long beforeSequenceNumber, Long beforeLastEventIndex,
        Object before, Object after, Executable handler,
        Graphs.StagedReplay replay, boolean cascadedDeletion,
        Defaults defaults,
        AggregateEventRouting eventRouting, ModelConflictPolicy conflictPolicy,
        boolean active, boolean storeEvent, boolean publishEvent,
        boolean updateState) {

    Change {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelType, "modelType");
        if (defaults == null) {
            Objects.requireNonNull(replay, "replay");
        }
    }

    static Change applied(
            String modelId, Class<?> modelType,
            long beforeSequenceNumber, Long beforeLastEventIndex,
            Object before, Object after, Executable handler,
            Graphs.StagedReplay replay, boolean cascadedDeletion) {
        return applied(
                modelId, modelType, beforeSequenceNumber, beforeLastEventIndex,
                before, after, handler, replay, cascadedDeletion,
                ModelDefinition.EffectOverrides.of(handler));
    }

    static Change applied(
            String modelId, Class<?> modelType,
            long beforeSequenceNumber, Long beforeLastEventIndex,
            Object before, Object after, Executable handler,
            Graphs.StagedReplay replay, boolean cascadedDeletion,
            ModelDefinition.EffectOverrides overrides) {
        return resolved(
                modelId, modelType, null,
                beforeSequenceNumber, beforeLastEventIndex,
                before, after, handler, replay, cascadedDeletion, overrides);
    }

    static Change staged(
            String modelId, Class<?> modelType, Long expectedStateIndex,
            Object after, Graphs.StagedReplay replay) {
        return new Change(
                modelId, modelType, expectedStateIndex, -1L, null,
                null, after, null, replay, false,
                null, null, null,
                false, false, false, false);
    }

    Change forRebase() {
        return staged(modelId, modelType, null, null, replay);
    }

    Change resolveAgainst(Entity<?> target, Object resolvedAfter) {
        Objects.requireNonNull(target, "target");
        return resolved(
                modelId, modelType, expectedStateIndex,
                target instanceof ModelRoot<?> root ? root.sequenceNumber() : -1L,
                target instanceof ModelRoot<?> root ? root.lastEventIndex() : null,
                target.get(), resolvedAfter, null, replay, false,
                ModelDefinition.EffectOverrides.of(null));
    }

    Change then(Change addition) {
        if (defaults != null && !modelType.equals(addition.modelType)) {
            throw new IllegalStateException(
                    "Graph changes target repository id '%s' as both %s and %s"
                            .formatted(addition.modelId, modelType.getName(),
                                       addition.modelType.getName()));
        }
        Graphs.StagedReplay combined = current ->
                addition.replay.apply(replay.apply(current));
        return defaults == null
                ? staged(modelId, addition.modelType, expectedStateIndex,
                         addition.after, combined)
                : applied(modelId, modelType,
                          beforeSequenceNumber, beforeLastEventIndex,
                          before, addition.after, addition.handler,
                          combined, addition.cascadedDeletion);
    }

    Change withEffects(
            boolean storeEvent, boolean publishEvent,
            boolean updateState) {
        return new Change(
                modelId, modelType, expectedStateIndex,
                beforeSequenceNumber, beforeLastEventIndex,
                before, after, handler, replay, cascadedDeletion,
                defaults,
                eventRouting, conflictPolicy, active,
                storeEvent, publishEvent, updateState);
    }

    void validate() {
        if (active && defaults.model().eventSourced() && updateState && !storeEvent) {
            throw new IllegalStateException(
                    "Event-sourced model %s cannot change through %s without storing its reconstructing event. "
                            .formatted(modelType.getName(), handler == null
                                    ? "a direct graph change" : handler.toGenericString())
                    + "Use STORE_ONLY or STORE_AND_PUBLISH, make the model document-loaded, or publish a no-op event.");
        }
    }

    private static Change resolved(
            String modelId, Class<?> declaredType, Long expectedStateIndex,
            long beforeSequenceNumber, Long beforeLastEventIndex,
            Object before, Object after, Executable handler,
            Graphs.StagedReplay replay, boolean cascadedDeletion,
            ModelDefinition.EffectOverrides overrides) {
        Class<?> effectiveType = EntityMetadata.of(declaredType).isModel()
                ? declaredType : after != null ? after.getClass()
                        : before != null ? before.getClass() : declaredType;
        Defaults defaults = Defaults.of(effectiveType);
        EntityMetadata.TransitionSettings settings =
                defaults.model().transitionSettings(
                        overrides.publication(), overrides.strategy(),
                        overrides.routing(), overrides.conflict());
        EntityMetadata.TransitionDecision decision = settings.decide(
                settings.forceModified() || !Objects.equals(before, after),
                cascadedDeletion, true);
        return new Change(
                modelId, declaredType, expectedStateIndex,
                beforeSequenceNumber, beforeLastEventIndex,
                before, after, handler, replay, cascadedDeletion,
                defaults, settings.routing(), settings.conflict(),
                decision.active(), decision.storeEvent(),
                decision.publishEvent(), decision.updateState());
    }

    record Defaults(
            EntityMetadata metadata,
            EntityMetadata.RootConfiguration model,
            EntityMetadata.SnapshotSettings snapshots,
            String collection) {

        private static Defaults of(Class<?> type) {
            return ReflectionUtils.getTypeMetadata(type).specializedMetadata(
                    Defaults.class, ignored -> {
                        EntityMetadata metadata = EntityMetadata.of(type);
                        EntityMetadata.RootConfiguration model = metadata.rootConfiguration()
                                .orElseThrow(() -> new IllegalStateException(
                                        type.getName() + " is not an independent model"));
                        String collection = model.searchable()
                                ? Optional.of(model.collection()).filter(value -> !value.isEmpty())
                                        .map(ApplicationProperties::substituteProperties)
                                        .orElse(type.getSimpleName())
                                : metadata.participatesInGraphComposition()
                                        ? io.fluxzero.common.api.modeling.ModelDocumentMutation
                                                .GRAPH_COMPONENT_COLLECTION
                                        : null;
                        return new Defaults(
                                metadata, model, model.snapshotSettings(false), collection);
                    });
        }
    }
}
