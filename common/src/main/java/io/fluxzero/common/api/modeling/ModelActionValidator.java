/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api.modeling;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Structural validation for the independent-model wire protocol.
 * <p>
 * Keeping this validation with the protocol types ensures local clients, test servers and runtimes reject malformed
 * model requests consistently.
 */
public final class ModelActionValidator {
    private static final int MAX_GRAPH_EVENTS_PER_MODEL = 8_192;

    private ModelActionValidator() {
    }

    /**
     * Validates the structural model-action wire contract.
     */
    public static void validate(CommitModelAction action) {
        if (action == null) {
            throw new IllegalArgumentException("Model action is required");
        }
        if (action.getActionId() == null || action.getActionId().isBlank()) {
            throw new IllegalArgumentException("Model actionId must not be blank");
        }
        if (action.getReadStateIndex() < -1L) {
            throw new IllegalArgumentException("Model readStateIndex must be at least -1");
        }
        if (action.getGuarantee() == null) {
            throw new IllegalArgumentException("Model action guarantee is required");
        }
        requireNonEmpty(action.getSubsteps(), "Model action must contain at least one substep");
        Set<String> readIds = uniqueIds(action.getReadModelIds(), "read model");
        for (int i = 0; i < action.getSubsteps().size(); i++) {
            ModelActionSubstep substep = action.getSubsteps().get(i);
            if (substep == null) {
                throw new IllegalArgumentException("Model action substep %d is null".formatted(i));
            }
            requireNonEmpty(substep.getTargets(), "Model action substep %d has no targets".formatted(i));
            boolean requiresEvent = substep.isPublishEvent()
                                    || substep.getTargets().stream().anyMatch(
                    target -> target != null && target.isStoreEvent());
            if (requiresEvent && substep.getEvent() == null) {
                throw new IllegalArgumentException(
                        "Model action substep %d requires an event".formatted(i));
            }
            if (substep.getEvent() != null && substep.getEvent().getIndex() != null) {
                throw new IllegalArgumentException(
                        "Model action substep %d event already has an event index".formatted(i));
            }
            Set<String> targetIds = new HashSet<>();
            for (ModelActionTarget target : substep.getTargets()) {
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Model action substep %d has a null target".formatted(i));
                }
                if (target.getModelId() == null || target.getModelId().isBlank()) {
                    throw new IllegalArgumentException(
                            "Model action substep %d has a blank target ID".formatted(i));
                }
                if (target.getModelType() != null && target.getModelType().isBlank()) {
                    throw new IllegalArgumentException(
                            "Model action substep %d has a blank target model type".formatted(i));
                }
                if (!targetIds.add(target.getModelId())) {
                    throw new IllegalArgumentException(
                            "Model action substep %d targets model %s more than once"
                                    .formatted(i, target.getModelId()));
                }
                if (!readIds.contains(target.getModelId())) {
                    throw new IllegalArgumentException(
                            "Target model %s is absent from readModelIds".formatted(target.getModelId()));
                }
                if (!target.isUpdateState()) {
                    throw new IllegalArgumentException(
                            "Target model %s does not update state".formatted(target.getModelId()));
                }
                if (target.getRelationships() == null) {
                    throw new IllegalArgumentException(
                            "Target model %s relationships are required".formatted(target.getModelId()));
                }
                if (target.isDelete() && !target.getRelationships().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Deleted target model %s must not retain parent relationships"
                                    .formatted(target.getModelId()));
                }
                if (target.isDelete() && !target.isUpdateRelationships()) {
                    throw new IllegalArgumentException(
                            "Deleted target model %s must update relationships".formatted(target.getModelId()));
                }
                if (!target.isUpdateRelationships() && !target.getRelationships().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Target model %s supplies relationships without update intent"
                                    .formatted(target.getModelId()));
                }
                validateDocument(target);
                validateSnapshot(target);
                Set<RelationshipKey> relationships = new HashSet<>();
                for (ModelRelationship relationship : target.getRelationships()) {
                    RelationshipKey key = relationshipKey(relationship);
                    if (!relationships.add(key)) {
                        throw new IllegalArgumentException(
                                "Target model %s contains duplicate parent relationship %s"
                                        .formatted(target.getModelId(), key));
                    }
                    if (target.getModelId().equals(relationship.getParentId())) {
                        throw new IllegalArgumentException(
                                "Target model %s cannot be its own parent".formatted(target.getModelId()));
                    }
                }
            }
        }
    }

    private static void validateDocument(ModelActionTarget target) {
        if (target.getDocument() == null) {
            return;
        }
        String collection = target.getDocument().getCollection();
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException(
                    "Target model %s has a blank document collection".formatted(target.getModelId()));
        }
        var document = target.getDocument().getDocument();
        if (document != null) {
            if (!target.getModelId().equals(document.getId())) {
                throw new IllegalArgumentException(
                        "Target model %s has document ID %s".formatted(target.getModelId(), document.getId()));
            }
            if (!collection.equals(document.getCollection())) {
                throw new IllegalArgumentException(
                        "Target model %s has inconsistent document collections %s and %s"
                                .formatted(target.getModelId(), collection, document.getCollection()));
            }
        }
    }

    private static void validateSnapshot(ModelActionTarget target) {
        if (target.getSnapshot() == null) {
            return;
        }
        var snapshot = target.getSnapshot();
        if (!target.isStoreEvent()) {
            throw new IllegalArgumentException(
                    "Target model %s has a snapshot without a stored event".formatted(target.getModelId()));
        }
        if (target.isDelete()) {
            throw new IllegalArgumentException(
                    "Deleted target model %s must not include a snapshot".formatted(target.getModelId()));
        }
        if (snapshot.getValue() == null || snapshot.getValue().getValue() == null) {
            throw new IllegalArgumentException(
                    "Target model %s has no snapshot value".formatted(target.getModelId()));
        }
        if (snapshot.getSnapshotPeriod() < 1) {
            throw new IllegalArgumentException(
                    "Target model %s has an invalid snapshot period".formatted(target.getModelId()));
        }
        if (snapshot.getMaxSnapshotCount() < 1) {
            throw new IllegalArgumentException(
                    "Target model %s has an invalid maximum snapshot count".formatted(target.getModelId()));
        }
    }

    /**
     * Validates one public batch model-stream read.
     */
    public static void validate(GetModelEvents request) {
        if (request == null) {
            throw new IllegalArgumentException("Model event request is required");
        }
        if (request.getMaxStateIndex() != null) {
            validateStateIndex(request.getMaxStateIndex());
        }
        validateEventBoundary(
                request.getMaxStateIndex(), request.getBoundaryActionId(), request.getBoundarySubstep());
        if (request.getRequests() == null) {
            throw new IllegalArgumentException("Model stream requests are required");
        }
        if (request.getMaxBytes() < 0L) {
            throw new IllegalArgumentException("Model event request maxBytes must not be negative");
        }
        Set<String> modelIds = new LinkedHashSet<>();
        for (ModelEventStreamRequest stream : request.getRequests()) {
            if (stream == null) {
                throw new IllegalArgumentException("Model stream request must not be null");
            }
            validateModelId(stream.getModelId());
            if (!modelIds.add(stream.getModelId())) {
                throw new IllegalArgumentException(
                        "Duplicate model stream request for " + stream.getModelId());
            }
            if (stream.getLastSequenceNumber() < -1L) {
                throw new IllegalArgumentException("Last model sequence number must be at least -1");
            }
            if (stream.getMaxSize() < 0) {
                throw new IllegalArgumentException("Model stream request maxSize must not be negative");
            }
        }
    }

    /**
     * Validates one bounded temporal model-graph request.
     */
    public static void validate(GetModelGraph request) {
        if (request == null) {
            throw new IllegalArgumentException("Model graph request is required");
        }
        validateModelId(request.getRootId());
        if (request.getMaxStateIndex() != null) {
            validateStateIndex(request.getMaxStateIndex());
        }
        validateEventBoundary(
                request.getMaxStateIndex(), request.getBoundaryActionId(), request.getBoundarySubstep());
        validateGraphBounds(
                "graph", request.getMaxDepth(), request.getMaxModels(),
                request.getMaxEventsPerModel(), request.getMaxBytes(), 0, 1, "1");
    }

    /**
     * Validates one bounded temporal ancestor-graph request.
     */
    public static void validate(GetModelAncestors request) {
        if (request == null) {
            throw new IllegalArgumentException("Model ancestor request is required");
        }
        if (request.getModelIds() == null || request.getModelIds().isEmpty()) {
            throw new IllegalArgumentException("Model ancestor roots are required");
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String modelId : request.getModelIds()) {
            validateModelId(modelId);
            if (!roots.add(modelId)) {
                throw new IllegalArgumentException("Duplicate model ancestor root " + modelId);
            }
        }
        if (request.getMaxStateIndex() != null) {
            validateStateIndex(request.getMaxStateIndex());
        }
        validateEventBoundary(
                request.getMaxStateIndex(), request.getBoundaryActionId(), request.getBoundarySubstep());
        validateGraphBounds(
                "ancestor", request.getMaxDepth(), request.getMaxModels(),
                request.getMaxEventsPerModel(), request.getMaxBytes(), 1, roots.size(), "root count");
    }

    private static void validateGraphBounds(
            String description,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            int minimumDepth,
            int minimumModels,
            String minimumModelsDescription) {
        if (maxDepth < minimumDepth || maxDepth > 1_024) {
            throw new IllegalArgumentException(
                    "Model %s maxDepth must be between %d and 1024".formatted(description, minimumDepth));
        }
        if (maxModels < minimumModels || maxModels > 100_000) {
            throw new IllegalArgumentException(
                    "Model %s maxModels must be between %s and 100000"
                            .formatted(description, minimumModelsDescription));
        }
        if (maxEventsPerModel < 0 || maxEventsPerModel > MAX_GRAPH_EVENTS_PER_MODEL) {
            throw new IllegalArgumentException(
                    "Model %s maxEventsPerModel must be between 0 and %d"
                            .formatted(description, MAX_GRAPH_EVENTS_PER_MODEL));
        }
        if (maxBytes < 0L) {
            throw new IllegalArgumentException(
                    "Model %s maxBytes must not be negative".formatted(description));
        }
    }

    /**
     * Validates one bounded model hard-deletion dry run.
     */
    public static void validate(PlanModelDeletion request) {
        if (request == null) {
            throw new IllegalArgumentException("Model deletion plan request is required");
        }
        validateModelId(request.getModelId());
        if (request.getCascade() == null) {
            throw new IllegalArgumentException("Model deletion cascade is required");
        }
        if (request.getMaxDepth() < 0 || request.getMaxDepth() > 1_024) {
            throw new IllegalArgumentException("Model deletion maxDepth must be between 0 and 1024");
        }
        if (request.getMaxModels() < 1 || request.getMaxModels() > 100_000) {
            throw new IllegalArgumentException("Model deletion maxModels must be between 1 and 100000");
        }
        if (request.getMaxSampleSize() < 0 || request.getMaxSampleSize() > 1_000) {
            throw new IllegalArgumentException("Model deletion maxSampleSize must be between 0 and 1000");
        }
    }

    /**
     * Validates one explicit model hard-deletion command.
     */
    public static void validate(DeleteModel request) {
        if (request == null) {
            throw new IllegalArgumentException("Model deletion request is required");
        }
        validateModelId(request.getDeletionId());
        validateModelId(request.getModelId());
        if (request.getCascade() == null) {
            throw new IllegalArgumentException("Model deletion cascade is required");
        }
        if (request.getGuarantee() == null) {
            throw new IllegalArgumentException("Model deletion guarantee is required");
        }
        if (request.getCascade() == ModelDeletionCascade.DESCENDANTS
            && (request.getPlanFingerprint() == null || request.getPlanFingerprint().isBlank())) {
            throw new IllegalArgumentException("Descendant model deletion requires a plan fingerprint");
        }
        if (request.getCascade() == ModelDeletionCascade.NONE && request.getPlanFingerprint() != null) {
            throw new IllegalArgumentException(
                    "Non-cascading model deletion must not include a plan fingerprint");
        }
        if (request.getMaxDepth() < 0 || request.getMaxDepth() > 1_024
            || request.getMaxModels() < 1 || request.getMaxModels() > 100_000) {
            throw new IllegalArgumentException("Invalid model deletion bounds");
        }
    }

    private static void validateEventBoundary(Long stateIndex, String actionId, Integer substep) {
        if (stateIndex != null && actionId != null) {
            throw new IllegalArgumentException(
                    "Specify either maxStateIndex or an action boundary, not both");
        }
        if ((actionId == null) != (substep == null)) {
            throw new IllegalArgumentException(
                    "Model action boundary requires both actionId and substep");
        }
        if (actionId != null && (actionId.isBlank() || substep < 0)) {
            throw new IllegalArgumentException(
                    "Model action boundary must be non-blank with a non-negative substep");
        }
    }

    /**
     * Validates an exact persisted model identity.
     */
    public static void validateModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Model ID must not be blank");
        }
    }

    /**
     * Validates a namespace model-state boundary.
     */
    public static void validateStateIndex(long stateIndex) {
        if (stateIndex < -1L) {
            throw new IllegalArgumentException("Model state index must be at least -1");
        }
    }

    private static RelationshipKey relationshipKey(ModelRelationship relationship) {
        if (relationship == null) {
            throw new IllegalArgumentException("Model relationship must not be null");
        }
        return new RelationshipKey(
                relationship.getParentId(), relationship.getParentType(), relationship.getPath());
    }

    private static Set<String> uniqueIds(Collection<String> values, String description) {
        if (values == null) {
            throw new IllegalArgumentException("Model action %s IDs are required".formatted(description));
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Model action has a blank %s ID".formatted(description));
            }
            if (!result.add(value)) {
                throw new IllegalArgumentException(
                        "Model action contains duplicate %s ID %s".formatted(description, value));
            }
        }
        return result;
    }

    private static void requireNonEmpty(Collection<?> values, String message) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private record RelationshipKey(String parentId, String parentType, String path) {
        private RelationshipKey {
            if (parentId == null || parentId.isBlank()) {
                throw new IllegalArgumentException("Model relationship parentId must not be blank");
            }
            if (parentType != null && parentType.isBlank()) {
                throw new IllegalArgumentException("Model relationship parentType must not be blank");
            }
            if (path != null && path.isBlank()) {
                throw new IllegalArgumentException("Model relationship path must not be blank");
            }
        }
    }
}
