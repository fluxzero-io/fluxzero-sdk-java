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

package io.fluxzero.common.modeling;

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelCommitValidator;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelRelationship;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.ModelRelationConstraint;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Shared graph, ancestor, lineage and relationship-query policies for every Model store. */
public final class ModelRelationshipQueries {
    private ModelRelationshipQueries() {
    }

    /** Resolves a current, state, commit or event selector against one visible store boundary. */
    public static long resolveBoundary(
            ModelReadBoundary boundary,
            boolean rejectNewer,
            LongSupplier currentState,
            BiFunction<String, Integer, Long> commitBoundary,
            Function<Long, Long> eventBoundary) {
        return resolveBoundaryWithEvidence(
                boundary, rejectNewer, currentState, commitBoundary, eventBoundary).stateIndex();
    }

    /** Resolves a boundary and records whether an event selector used its explicit current-state fallback. */
    public static ResolvedBoundary resolveBoundaryWithEvidence(
            ModelReadBoundary boundary,
            boolean rejectNewer,
            LongSupplier currentState,
            BiFunction<String, Integer, Long> commitBoundary,
            Function<Long, Long> eventBoundary) {
        long current = currentState.getAsLong();
        String commitId = boundary.commitId();
        Integer substep = boundary.substep();
        Long eventIndex = boundary.eventIndex();
        Long commit = commitId == null ? null : commitBoundary.apply(commitId, substep);
        Long event = eventIndex == null ? null : eventBoundary.apply(eventIndex);
        if (commitId != null && commit == null) {
            throw new IllegalArgumentException(
                    "Model commit boundary %s[%d] is not visible".formatted(commitId, substep));
        }
        if (eventIndex != null && event == null && !boundary.fallbackToCurrent()) {
            throw new IllegalArgumentException(
                    "Model event boundary %d is not visible".formatted(eventIndex));
        }
        long result = event != null ? event : commit != null ? commit
                : boundary.stateIndex() == null ? current : boundary.stateIndex();
        if (rejectNewer && result > current) {
            throw new IllegalArgumentException(
                    "Model maxStateIndex %d is newer than visible stateIndex %d"
                            .formatted(result, current));
        }
        return new ResolvedBoundary(result, eventIndex == null || event != null);
    }

    /** Resolves one bounded relationship graph and its first stream page. */
    public static <R extends Relationship> GetModelGraphResult graph(
            GetModelGraph request,
            long boundary,
            Function<Collection<String>, List<R>> relationships,
            Function<GetModelEvents, GetModelEventsResult> events) {
        return graph(request, boundary, true, relationships, events);
    }

    /** Resolves a graph while retaining evidence about the original selector that established its boundary. */
    public static <R extends Relationship> GetModelGraphResult graph(
            GetModelGraph request,
            long boundary,
            boolean exactBoundary,
            Function<Collection<String>, List<R>> relationships,
            Function<GetModelEvents, GetModelEventsResult> events) {
        boolean ancestors = request.getDirection() == GetModelGraph.TraversalDirection.ANCESTORS;
        ModelRelationshipTraversal.Result graph = ModelRelationshipTraversal.traverse(
                request.getModelIds(),
                new ModelRelationshipTraversal.Policy(
                        request.getMaxDepth(), request.getMaxModels(), ancestors, false,
                        "Model %s graph exceeds maxModels %d"
                                .formatted(ancestors ? "ancestor" : "descendant", request.getMaxModels()),
                        ancestors ? "Model ancestor graph exceeds maxDepth " + request.getMaxDepth() : null),
                frontier -> {
                    var selected = relationships.apply(frontier).stream();
                    return ancestors
                            ? selected.sorted(Comparator.comparing(Relationship::childId)
                                    .thenComparing(Relationship::parentId)
                                    .thenComparing(Relationship::path,
                                                   Comparator.nullsFirst(Comparator.naturalOrder())))
                                    .toList()
                            : selected.filter(relation -> !request.isComposableOnly() || relation.path() != null)
                                    .toList();
                },
                relation -> ancestors ? relation.parentId() : relation.childId(),
                Relationship::edge);
        return result(
                request.getRequestId(), boundary, graph,
                request.getMaxEventsPerModel(), request.getMaxBytes(), exactBoundary, events);
    }

    private static GetModelGraphResult result(
            long requestId,
            long boundary,
            ModelRelationshipTraversal.Result graph,
            int maxEventsPerModel,
            long maxBytes,
            boolean exactBoundary,
            Function<GetModelEvents, GetModelEventsResult> eventLoader) {
        GetModelEventsResult events = eventLoader.apply(new GetModelEvents(
                graph.modelIds().stream()
                        .map(id -> new ModelEventStreamRequest(id, -1L, maxEventsPerModel))
                        .toList(),
                ModelReadBoundary.state(boundary, false), maxBytes));
        return new GetModelGraphResult(
                requestId, graph.edges(), events.withExactBoundary(exactBoundary));
    }

    /** State selected for a read and whether it came from the requested selector rather than an allowed fallback. */
    public record ResolvedBoundary(long stateIndex, boolean exact) {
    }

    /** Resolves models related at the configured depths and paths. */
    public static <R extends Relationship> Set<String> relatedModels(
            Set<String> relatedModelIds,
            ModelRelationConstraint constraint,
            Function<Collection<String>, List<R>> parentRelationships,
            Function<Collection<String>, List<R>> childRelationships) {
        if (relatedModelIds.size() > constraint.getMaxRelatedModels()) {
            throw new IllegalArgumentException(
                    "Related model IDs exceed maxRelatedModels " + constraint.getMaxRelatedModels());
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        boolean ancestors = constraint.getDirection() == ModelRelationConstraint.RelationDirection.ANCESTOR;
        ModelRelationshipTraversal.traverse(
                relatedModelIds,
                new ModelRelationshipTraversal.Policy(
                        constraint.getMaxDepth(), constraint.getMaxTraversedModels(), false, true,
                        "Model relation traversal exceeds maxTraversedModels "
                        + constraint.getMaxTraversedModels()
                        + "; narrow the query or use a materialized graph projection",
                        null),
                frontier -> (ancestors ? parentRelationships : childRelationships).apply(frontier).stream()
                        .filter(relation -> constraint.getPaths().isEmpty()
                                            || constraint.getPaths().contains(relation.path()))
                        .sorted(Comparator.comparing(Relationship::childId)
                                        .thenComparing(Relationship::parentId)
                                        .thenComparing(
                                                Relationship::path,
                                                Comparator.nullsFirst(Comparator.naturalOrder())))
                        .toList(),
                ancestors ? Relationship::childId : Relationship::parentId,
                null,
                ignored -> List.of(),
                (depth, models) -> {
                    if (depth >= constraint.getMinDepth()) {
                        result.addAll(models);
                    }
                });
        return Set.copyOf(result);
    }

    /** Resolves the current composable descendant graph for a root page. */
    public static <R extends Relationship> ModelRelationshipTraversal.Result currentGraph(
            Collection<String> rootModelIds,
            int maxDepth,
            int maxModels,
            Function<Collection<String>, List<R>> relationships) {
        if (rootModelIds == null || rootModelIds.isEmpty()) {
            throw new IllegalArgumentException("Model graph roots are required");
        }
        if (maxDepth != ModelGraphComposition.UNBOUNDED && maxDepth < 1) {
            throw new IllegalArgumentException(
                    "Model graph maxDepth must be positive or UNBOUNDED (-1)");
        }
        if (maxModels != ModelGraphComposition.UNBOUNDED && maxModels < 1) {
            throw new IllegalArgumentException(
                    "Model graph maxModels must be positive or UNBOUNDED (-1)");
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>(rootModelIds);
        roots.forEach(ModelCommitValidator::validateModelId);
        if (maxModels != ModelGraphComposition.UNBOUNDED && roots.size() > maxModels) {
            throw new IllegalArgumentException("Model graph roots exceed maxModels " + maxModels);
        }
        return ModelRelationshipTraversal.traverse(
                roots,
                new ModelRelationshipTraversal.Policy(
                        maxDepth, maxModels, true, false,
                        "Model graph exceeds maxModels " + maxModels
                        + "; narrow the result or use a materialized graph projection",
                        "Model graph exceeds maxDepth " + maxDepth
                        + "; narrow the result or remove the explicit composition limit"),
                frontier -> relationships.apply(frontier).stream()
                        .filter(relation -> relation.path() != null)
                        .sorted(Comparator.comparing(Relationship::parentId)
                                        .thenComparing(Relationship::path)
                                        .thenComparing(Relationship::childId))
                        .toList(),
                Relationship::childId,
                Relationship::edge);
    }

    /** Resolves current and lifecycle-retained descendants at one boundary. */
    public static <R extends Relationship> Set<String> descendantLineage(
            Collection<String> roots,
            long boundary,
            int maxDepth,
            int maxModels,
            Function<Collection<String>, List<R>> currentRelationships,
            Function<Collection<String>, List<R>> deletedRelationships,
            Function<Collection<String>, Collection<String>> protectedDescendants) {
        return descendantLineage(
                roots, boundary, maxDepth, maxModels,
                currentRelationships, deletedRelationships, protectedDescendants,
                "Model lineage exceeds maxModels " + maxModels,
                "Model lineage exceeds maxDepth " + maxDepth);
    }

    /** Resolves descendants with caller-specific bound failures. */
    public static <R extends Relationship> Set<String> descendantLineage(
            Collection<String> roots,
            long boundary,
            int maxDepth,
            int maxModels,
            Function<Collection<String>, List<R>> currentRelationships,
            Function<Collection<String>, List<R>> deletedRelationships,
            Function<Collection<String>, Collection<String>> protectedDescendants,
            String maxModelsExceeded,
            String maxDepthExceeded) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("Model lineage roots are required");
        }
        if (maxDepth < 0 || maxDepth > 1_024) {
            throw new IllegalArgumentException("Model lineage maxDepth must be between 0 and 1024");
        }
        if (maxModels < 1 || maxModels > 100_000) {
            throw new IllegalArgumentException("Model lineage maxModels must be between 1 and 100000");
        }
        roots.forEach(ModelCommitValidator::validateModelId);
        ModelCommitValidator.validateStateIndex(boundary);
        if (new LinkedHashSet<>(roots).size() > maxModels) {
            throw new IllegalArgumentException("Model lineage roots exceed maxModels " + maxModels);
        }
        List<ModelRelationshipTraversal.RelationshipLoader<R>> loaders = List.of(
                frontier -> currentRelationships.apply(frontier),
                frontier -> deletedRelationships.apply(frontier));
        return Set.copyOf(ModelRelationshipTraversal.traverse(
                roots,
                new ModelRelationshipTraversal.Policy(
                        maxDepth, maxModels, true, false,
                        maxModelsExceeded, maxDepthExceeded),
                loaders,
                Relationship::childId,
                null,
                protectedDescendants,
                null).modelIds());
    }

    /** Resolves composable ancestors with one shared traversal policy. */
    public static <R extends Relationship> Set<String> graphAncestors(
            Collection<String> modelIds,
            int maxDepth,
            Function<Collection<String>, List<R>> relationships) {
        return ModelRelationshipTraversal.traverse(
                modelIds,
                new ModelRelationshipTraversal.Policy(
                        maxDepth, ModelGraphComposition.UNBOUNDED, true, false,
                        null, "Model graph projection exceeds maxDepth " + maxDepth),
                frontier -> () -> relationships.apply(frontier).stream()
                        .filter(relation -> relation.path() != null)
                        .iterator(),
                Relationship::parentId,
                null).without(modelIds);
    }

    /** Returns descendants whose complete path from a supplied root owns the child lifecycle. */
    public static List<String> ownedDescendants(
            Collection<String> roots, Collection<ModelGraphEdge> edges) {
        Map<String, List<ModelGraphEdge>> children = new LinkedHashMap<>();
        edges.stream().filter(ModelGraphEdge::isDeleteOnParentDeletion)
                .forEach(edge -> children.computeIfAbsent(
                        edge.getParentId(), ignored -> new ArrayList<>()).add(edge));
        return descendants(
                roots,
                frontier -> frontier.stream()
                        .flatMap(parent -> children.getOrDefault(parent, List.of()).stream())
                        .map(ModelGraphEdge::getChildId)
                        .toList());
    }

    /** Traverses an already selected child index without recreating frontier and visit semantics. */
    public static List<String> descendants(
            Collection<String> roots,
            ModelRelationshipTraversal.RelationshipLoader<String> children) {
        Set<String> rootIds = new LinkedHashSet<>(roots);
        return ModelRelationshipTraversal.traverse(
                        rootIds,
                        new ModelRelationshipTraversal.Policy(
                                ModelGraphComposition.UNBOUNDED,
                                ModelGraphComposition.UNBOUNDED,
                                false, false, null, null),
                        children,
                        Function.identity(),
                        null)
                .modelIds().stream().filter(modelId -> !rootIds.contains(modelId)).toList();
    }

    /** Creates the transient owned-relationship view used to validate recursive cascade plans. */
    public static OwnedRelationshipGraph ownedRelationshipGraph() {
        return new OwnedRelationshipGraph();
    }

    /**
     * Mutable commit-scoped view of relationships that own the child lifecycle.
     * Deleted children deliberately remain until the caller has verified that every current descendant is a delete
     * target; {@link #removeModel(String)} advances the view after an accepted plan in a commit batch.
     */
    public static final class OwnedRelationshipGraph {
        private final Map<String, LinkedHashSet<String>> childrenByParent =
                new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<String>> parentsByChild =
                new LinkedHashMap<>();

        /** Adds one current owned child edge. */
        public void add(String parentId, String childId) {
            childrenByParent.computeIfAbsent(
                    parentId, ignored -> new LinkedHashSet<>()).add(childId);
            parentsByChild.computeIfAbsent(
                    childId, ignored -> new LinkedHashSet<>()).add(parentId);
        }

        /** Adds a relationship only when deletion of its parent owns the child lifecycle. */
        public void add(Relationship relationship) {
            if (relationship.deleteOnParentDeletion()) {
                add(relationship.parentId(), relationship.childId());
            }
        }

        /** Applies the final non-deletion relationship values from ordered commit steps. */
        public void overlayChanges(
                Collection<ModelCommitAssignment.RelationshipStep> steps) {
            steps.stream().flatMap(step -> step.changes().stream())
                    .filter(change -> !change.deleted())
                    .forEach(change -> {
                        removeChild(change.childId());
                        change.desired().stream()
                                .filter(ModelRelationship::isDeleteOnParentDeletion)
                                .forEach(relationship -> add(
                                        relationship.getParentId(), change.childId()));
                    });
        }

        /** Removes every incoming and outgoing owned edge for an accepted deleted Model. */
        public void removeModel(String modelId) {
            removeChild(modelId);
            removeParent(modelId);
        }

        /** Returns all transitive owned descendants in deterministic traversal order. */
        public List<String> descendants(Collection<String> roots) {
            return ModelRelationshipQueries.descendants(
                    roots,
                    frontier -> frontier.stream()
                            .map(childrenByParent::get)
                            .filter(java.util.Objects::nonNull)
                            .flatMap(Set::stream)
                            .toList());
        }

        private void removeChild(String childId) {
            Set<String> parents = parentsByChild.remove(childId);
            if (parents == null) {
                return;
            }
            parents.forEach(parentId -> {
                LinkedHashSet<String> children = childrenByParent.get(parentId);
                if (children != null) {
                    children.remove(childId);
                    if (children.isEmpty()) {
                        childrenByParent.remove(parentId);
                    }
                }
            });
        }

        private void removeParent(String parentId) {
            Set<String> children = childrenByParent.remove(parentId);
            if (children == null) {
                return;
            }
            children.forEach(childId -> {
                LinkedHashSet<String> parents = parentsByChild.get(childId);
                if (parents != null) {
                    parents.remove(parentId);
                    if (parents.isEmpty()) {
                        parentsByChild.remove(childId);
                    }
                }
            });
        }
    }

    /** Storage-independent view of one temporal child-to-parent relationship interval. */
    public interface Relationship {
        String childId();

        String parentId();

        String parentType();

        String path();

        long validFrom();

        Long validUntil();

        boolean deleteOnParentDeletion();

        default ModelGraphEdge edge() {
            return new ModelGraphEdge(
                    childId(), parentId(), parentType(), path(), validFrom(), validUntil(),
                    deleteOnParentDeletion());
        }
    }

    /** Stable fingerprint for one ordered hard-deletion selection. */
    public static String deletionFingerprint(
            String rootId,
            ModelDeletionCascade cascade,
            List<String> orderedIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, rootId);
            updateDigest(digest, cascade.name());
            orderedIds.forEach(value -> updateDigest(digest, value));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
