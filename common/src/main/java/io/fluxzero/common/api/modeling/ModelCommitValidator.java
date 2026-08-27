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

import static io.fluxzero.common.api.search.ModelGraphComposition.UNBOUNDED;

/**
 * Structural validation for the independent-model wire protocol.
 * <p>
 * Keeping this validation with the protocol types ensures local clients, test servers and runtimes reject malformed
 * model requests consistently.
 */
public final class ModelCommitValidator {
    private static final int MAX_GRAPH_EVENTS_PER_MODEL = 8_192;

    private ModelCommitValidator() {
    }

    /**
     * Validates the structural model-commit wire contract.
     */
    public static void validate(CommitModels commit) {
        if (commit == null) {
            throw new IllegalArgumentException("Model commit is required");
        }
        if (commit.getCommitId() == null || commit.getCommitId().isBlank()) {
            throw new IllegalArgumentException("Model commitId must not be blank");
        }
        if (commit.getReadStateIndex() < -1L) {
            throw new IllegalArgumentException("Model readStateIndex must be at least -1");
        }
        if (commit.getGuarantee() == null) {
            throw new IllegalArgumentException("Model commit guarantee is required");
        }
        requireNonEmpty(commit.getSubsteps(), "Model commit must contain at least one substep");
        if (validateSimpleCommit(commit)) {
            return;
        }
        Set<String> readIds = uniqueIds(commit.getReadModelIds(), "read model");
        Long existingEventIndex = null;
        for (int i = 0; i < commit.getSubsteps().size(); i++) {
            ModelCommitStep substep = commit.getSubsteps().get(i);
            if (substep == null) {
                throw new IllegalArgumentException("Model commit substep %d is null".formatted(i));
            }
            if (substep.getTargets() == null
                || substep.getTargets().isEmpty()) {
                throw new IllegalArgumentException(
                        "Model commit substep %d has no targets"
                                .formatted(i));
            }
            boolean requiresEvent = substep.isPublishEvent();
            if (!requiresEvent) {
                for (ModelCommitTarget target : substep.getTargets()) {
                    if (target != null && target.isStoreEvent()) {
                        requiresEvent = true;
                        break;
                    }
                }
            }
            if (requiresEvent && substep.getEvent() == null) {
                throw new IllegalArgumentException(
                        "Model commit substep %d requires an event".formatted(i));
            }
            if (substep.getEvent() != null && substep.getEvent().getIndex() != null) {
                long eventIndex = substep.getEvent().getIndex();
                if (substep.isPublishEvent()) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d cannot republish existing event %d"
                                    .formatted(i, eventIndex));
                }
                if (eventIndex < 0L) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d has an invalid existing event index %d"
                                    .formatted(i, eventIndex));
                }
                if (!commit.getCommitId().equals(substep.getEvent().getMessageId())) {
                    throw new IllegalArgumentException(
                            "Model commit %s must use the existing event message ID as commit ID"
                                    .formatted(commit.getCommitId()));
                }
                if (existingEventIndex != null) {
                    throw new IllegalArgumentException(
                            "Model commit may reference only one existing global event");
                }
                existingEventIndex = eventIndex;
            }
            Set<String> targetIds =
                    substep.getTargets().size() > 1 ? new HashSet<>() : null;
            for (ModelCommitTarget target : substep.getTargets()) {
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d has a null target".formatted(i));
                }
                if (target.getModelId() == null || target.getModelId().isBlank()) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d has a blank target ID".formatted(i));
                }
                if (target.getModelType() != null && target.getModelType().isBlank()) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d has a blank target model type".formatted(i));
                }
                if (target.getExpectedSequenceNumber() != null
                    && target.getExpectedSequenceNumber() < -1L) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d target %s has an invalid expected sequence number"
                                    .formatted(i, target.getModelId()));
                }
                if (targetIds != null && !targetIds.add(target.getModelId())) {
                    throw new IllegalArgumentException(
                            "Model commit substep %d targets model %s more than once"
                                    .formatted(i, target.getModelId()));
                }
                if (!readIds.contains(target.getModelId())) {
                    throw new IllegalArgumentException(
                            "Target model %s is absent from readModelIds".formatted(target.getModelId()));
                }
                if (!target.isUpdateState()) {
                    if (!target.isStoreEvent() && !substep.isPublishEvent()) {
                        throw new IllegalArgumentException(
                                "Target model %s neither updates state nor emits an event"
                                        .formatted(target.getModelId()));
                    }
                    if (target.isDelete() || target.isUpdateRelationships()
                        || target.isCascadeDelete()
                        || target.getDocument() != null || target.getSnapshot() != null
                        || target.getRelationships() == null || !target.getRelationships().isEmpty()
                        || target.getAliases() != null) {
                        throw new IllegalArgumentException(
                                "Event-only target model %s contains a state mutation"
                                        .formatted(target.getModelId()));
                    }
                    continue;
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
                if (target.isCascadeDelete() && !target.isDelete()) {
                    throw new IllegalArgumentException(
                            "Cascade target model %s must be deleted".formatted(target.getModelId()));
                }
                if (target.isDelete() && target.getAliases() != null
                    && !target.getAliases().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Deleted target model %s must not retain aliases"
                                    .formatted(target.getModelId()));
                }
                if (!target.isUpdateRelationships() && !target.getRelationships().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Target model %s supplies relationships without update intent"
                                    .formatted(target.getModelId()));
                }
                validateDocument(target);
                validateSnapshot(target);
                validateAliases(target);
                Set<RelationshipKey> relationships =
                        target.getRelationships().size() > 1 ? new HashSet<>() : null;
                for (ModelRelationship relationship : target.getRelationships()) {
                    RelationshipKey key = relationshipKey(relationship);
                    if (relationships != null && !relationships.add(key)) {
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
        if (commit.isMigration() != (existingEventIndex != null)) {
            throw new IllegalArgumentException(
                    commit.isMigration()
                            ? "Model migration commits must reference one existing global event"
                            : "Existing global events require an explicit Model migration commit");
        }
    }

    private static void validateDocument(ModelCommitTarget target) {
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

    private static void validateSnapshot(ModelCommitTarget target) {
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

    private static void validateAliases(ModelCommitTarget target) {
        if (target.getAliases() == null) {
            return;
        }
        Set<String> aliases = new HashSet<>();
        for (String alias : target.getAliases()) {
            validateModelId(alias);
            if (!aliases.add(alias)) {
                throw new IllegalArgumentException(
                        "Target model %s contains duplicate alias %s"
                                .formatted(target.getModelId(), alias));
            }
        }
    }

    /**
     * Validates one public batch model-stream read.
     */
    public static void validate(GetModelEvents request) {
        if (request == null) {
            throw new IllegalArgumentException("Model event request is required");
        }
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
     * Validates one temporal model-graph request, including optional caller-imposed bounds.
     */
    public static void validate(GetModelGraph request) {
        if (request == null) {
            throw new IllegalArgumentException("Model graph request is required");
        }
        if (request.getModelIds() == null || request.getModelIds().isEmpty()) {
            throw new IllegalArgumentException("Model graph roots are required");
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String modelId : request.getModelIds()) {
            validateModelId(modelId);
            if (!roots.add(modelId)) {
                throw new IllegalArgumentException("Duplicate model graph root " + modelId);
            }
        }
        boolean ancestors = request.getDirection() == GetModelGraph.TraversalDirection.ANCESTORS;
        if (ancestors && request.getBoundary().before()) {
            throw new IllegalArgumentException("Model ancestor graphs do not support before-boundaries");
        }
        if (ancestors && request.isComposableOnly()) {
            throw new IllegalArgumentException("Composable-only traversal is only supported for descendant graphs");
        }
        validateGraphBounds(
                ancestors ? "ancestor" : "graph",
                request.getMaxDepth(), request.getMaxModels(),
                request.getMaxEventsPerModel(), request.getMaxBytes(),
                ancestors ? 1 : 0, roots.size(), "root count", true);
    }

    /** Validates one durable model-change lookup. */
    public static void validate(GetModelChange request) {
        if (request == null) {
            throw new IllegalArgumentException("Model change request is required");
        }
        if (request.getCommitId() == null || request.getCommitId().isBlank()) {
            throw new IllegalArgumentException("Model commit ID is required");
        }
        if (request.getSubstep() < 0) {
            throw new IllegalArgumentException("Model commit substep must be non-negative");
        }
    }

    private static void validateGraphBounds(
            String description,
            int maxDepth,
            int maxModels,
            int maxEventsPerModel,
            long maxBytes,
            int minimumDepth,
            int minimumModels,
            String minimumModelsDescription,
            boolean unboundedAllowed) {
        boolean unboundedDepth = unboundedAllowed
                                 && maxDepth == UNBOUNDED;
        boolean unboundedModels = unboundedAllowed
                                  && maxModels == UNBOUNDED;
        if (!unboundedDepth && (maxDepth < minimumDepth
                                || !unboundedAllowed && maxDepth > 1_024)) {
            throw new IllegalArgumentException(
                    unboundedAllowed
                            ? "Model %s maxDepth must be at least %d or UNBOUNDED (-1)"
                                    .formatted(description, minimumDepth)
                            : "Model %s maxDepth must be between %d and 1024"
                                    .formatted(description, minimumDepth));
        }
        if (!unboundedModels && (maxModels < minimumModels
                                 || !unboundedAllowed && maxModels > 100_000)) {
            throw new IllegalArgumentException(
                    unboundedAllowed
                            ? "Model %s maxModels must be at least %s or UNBOUNDED (-1)"
                                    .formatted(description, minimumModelsDescription)
                            : "Model %s maxModels must be between %s and 100000"
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
            throw new IllegalArgumentException("Model commit %s IDs are required".formatted(description));
        }
        if (values.size() == 1) {
            String value = values.iterator().next();
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Model commit has a blank %s ID".formatted(description));
            }
            return Set.of(value);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Model commit has a blank %s ID".formatted(description));
            }
            if (!result.add(value)) {
                throw new IllegalArgumentException(
                        "Model commit contains duplicate %s ID %s".formatted(description, value));
            }
        }
        return result;
    }

    private static boolean validateSimpleCommit(CommitModels commit) {
        ModelCommitTarget target = commit.singleTarget();
        if (target == null || commit.getReadModelIds() == null
            || commit.getReadModelIds().size() != 1) {
            return false;
        }
        ModelCommitStep substep = commit.getSubsteps().getFirst();
        if (!substep.isPublishEvent() || substep.getEvent() == null) {
            return false;
        }
        if (!target.isStoreEvent() || !target.isUpdateState()
            || target.isDelete() || target.isUpdateRelationships()
            || target.getRelationships() == null || !target.getRelationships().isEmpty()
            || target.getAliases() != null
            || target.getDocument() != null || target.getSnapshot() != null
            || !java.util.Objects.equals(
                    target.getModelId(), commit.getReadModelIds().getFirst())) {
            return false;
        }
        if (substep.getEvent().getIndex() != null) {
            throw new IllegalArgumentException(
                    "Model commit substep 0 event already has an event index");
        }
        if (target.getModelId() == null || target.getModelId().isBlank()) {
            throw new IllegalArgumentException("Model commit substep 0 has a blank target ID");
        }
        if (target.getModelType() != null && target.getModelType().isBlank()) {
            throw new IllegalArgumentException(
                    "Model commit substep 0 has a blank target model type");
        }
        if (target.getExpectedSequenceNumber() != null
            && target.getExpectedSequenceNumber() < -1L) {
            throw new IllegalArgumentException(
                    "Model commit substep 0 target %s has an invalid expected sequence number"
                            .formatted(target.getModelId()));
        }
        return true;
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
