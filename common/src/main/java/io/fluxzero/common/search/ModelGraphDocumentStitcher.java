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

package io.fluxzero.common.search;

import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.SortableEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.fluxzero.common.search.JacksonInverter.isMetadataPath;

/**
 * Deterministically composes direct current model documents using explicit relationship paths.
 * <p>
 * This implementation is shared by local SDK fixtures and the runtime so path, collision, ordering, and DAG behavior
 * cannot drift between them.
 */
public final class ModelGraphDocumentStitcher {

    private ModelGraphDocumentStitcher() {
    }

    /**
     * Composes every root document from the supplied current graph.
     *
     * @param roots       root documents in search-result order
     * @param edges       current graph edges reachable from those roots
     * @param documents   direct documents keyed by globally unique model ID, including the roots
     * @param composition traversal and output bounds
     * @return composed roots in the original result order
     */
    public static List<SerializedDocument> stitch(
            List<SerializedDocument> roots,
            Collection<ModelGraphEdge> edges,
            Map<String, SerializedDocument> documents,
            ModelGraphComposition composition) {
        Objects.requireNonNull(roots, "Root documents");
        Objects.requireNonNull(edges, "Model graph edges");
        Objects.requireNonNull(documents, "Model documents");
        Objects.requireNonNull(
                composition, "Model graph composition");

        Map<String, List<ModelGraphEdge>> children =
                new HashMap<>();
        edges.stream()
                .filter(edge -> edge.getPath() != null)
                .sorted(Comparator
                                .comparing(
                                        ModelGraphEdge::getParentId)
                                .thenComparing(
                                        ModelGraphEdge::getPath)
                                .thenComparing(
                                        ModelGraphEdge::getChildId))
                .forEach(edge ->
                                 children.computeIfAbsent(
                                                 edge.getParentId(),
                                                 ignored ->
                                                         new ArrayList<>())
                                         .add(edge));

        Bounds bounds = new Bounds(composition);
        List<SerializedDocument> result =
                new ArrayList<>(roots.size());
        for (SerializedDocument root : roots) {
            SerializedDocument available =
                    documents.get(root.getId());
            Document composed = compose(
                    root.getId(),
                    available == null
                            ? root : available,
                    children, documents, bounds, 0,
                    new LinkedHashSet<>());
            SerializedDocument serialized =
                    new SerializedDocument(composed);
            bounds.addBytes(serialized.bytes());
            result.add(serialized);
        }
        return List.copyOf(result);
    }

    private static Document compose(
            String modelId,
            SerializedDocument serialized,
            Map<String, List<ModelGraphEdge>> children,
            Map<String, SerializedDocument> documents,
            Bounds bounds,
            int depth,
            LinkedHashSet<String> ancestry) {
        if (!ancestry.add(modelId)) {
            throw new IllegalArgumentException(
                    "Model graph composition encountered a cycle at "
                    + modelId);
        }
        try {
            Document direct =
                    serialized.deserializeDocument();
            if (depth >= bounds.composition
                    .getMaxDepth()) {
                return direct;
            }
            Map<String, List<ModelGraphEdge>> byPath =
                    new LinkedHashMap<>();
            for (ModelGraphEdge edge :
                    children.getOrDefault(
                            modelId, List.of())) {
                if (documents.containsKey(
                        edge.getChildId())) {
                    byPath.computeIfAbsent(
                                    edge.getPath(),
                                    ignored ->
                                            new ArrayList<>())
                            .add(edge);
                }
            }
            if (byPath.isEmpty()) {
                return direct;
            }
            validatePaths(
                    modelId, direct, byPath.keySet());

            Map<Document.Entry, List<Document.Path>>
                    entries = new LinkedHashMap<>();
            direct.getEntries().forEach(
                    (entry, paths) ->
                            entries.put(
                                    entry,
                                    new ArrayList<>(
                                            paths)));
            LinkedHashSet<FacetEntry> facets =
                    new LinkedHashSet<>(
                            direct.getFacets());
            LinkedHashSet<SortableEntry> sortables =
                    new LinkedHashSet<>(
                            direct.getSortables());
            List<String> summaries = new ArrayList<>();
            if (direct.getSummary() != null
                && !direct.getSummary().isBlank()) {
                summaries.add(direct.getSummary());
            }

            byPath.forEach((path, pathEdges) -> {
                pathEdges.sort(Comparator.comparing(
                        ModelGraphEdge::getChildId));
                int ordinal = 0;
                for (ModelGraphEdge edge :
                        pathEdges) {
                    bounds.addPlacement();
                    SerializedDocument child =
                            documents.get(
                                    edge.getChildId());
                    Document childDocument = compose(
                            edge.getChildId(), child,
                            children, documents, bounds,
                            depth + 1, ancestry);
                    String prefix =
                            path + "/" + ordinal++;
                    append(
                            childDocument, prefix,
                            entries, facets, sortables,
                            summaries);
                }
            });
            String summary = summaries.isEmpty()
                    ? null
                    : String.join(" ", summaries);
            return direct.toBuilder()
                    .entries(entries)
                    .facets(Collections.unmodifiableSet(
                            facets))
                    .sortables(
                            Collections.unmodifiableSet(
                                    sortables))
                    .summary(() -> summary)
                    .build();
        } finally {
            ancestry.remove(modelId);
        }
    }

    private static void append(
            Document child,
            String prefix,
            Map<Document.Entry, List<Document.Path>>
                    entries,
            Set<FacetEntry> facets,
            Set<SortableEntry> sortables,
            List<String> summaries) {
        child.getEntries().forEach(
                (entry, paths) -> {
                    List<Document.Path> prefixed =
                            paths.stream()
                                    .filter(path ->
                                                    !isMetadataPath(
                                                            path.getValue()))
                                    .map(path ->
                                                 new Document.Path(
                                                         append(
                                                                 prefix,
                                                                 path.getValue())))
                                    .toList();
                    if (!prefixed.isEmpty()) {
                        entries.computeIfAbsent(
                                        entry,
                                        ignored ->
                                                new ArrayList<>())
                                .addAll(prefixed);
                    }
                });
        child.getFacets().stream()
                .map(facet -> facet.toBuilder()
                        .name(append(
                                prefix,
                                facet.getName()))
                        .build())
                .forEach(facets::add);
        child.getSortables().stream()
                .map(sortable -> sortable.withName(
                        append(
                                prefix,
                                sortable.getName())))
                .forEach(sortables::add);
        if (child.getSummary() != null
            && !child.getSummary().isBlank()) {
            summaries.add(child.getSummary());
        }
    }

    private static String append(
            String prefix, String path) {
        return path == null || path.isEmpty()
                ? prefix : prefix + "/" + path;
    }

    private static void validatePaths(
            String modelId,
            Document direct,
            Set<String> compositionPaths) {
        List<String> distinct =
                compositionPaths.stream()
                        .sorted()
                        .toList();
        for (int i = 0; i < distinct.size(); i++) {
            String path = distinct.get(i);
            validateCompositionPath(
                    modelId, path);
            for (int j = i + 1;
                 j < distinct.size(); j++) {
                String other = distinct.get(j);
                if (overlaps(path, other)) {
                    throw collision(
                            modelId, path, other);
                }
            }
            for (List<Document.Path> paths :
                    direct.getEntries().values()) {
                for (Document.Path directPath :
                        paths) {
                    if (!isMetadataPath(
                            directPath.getValue())
                        && overlaps(
                                path,
                                directPath.getValue())) {
                        throw collision(
                                modelId, path,
                                directPath.getValue());
                    }
                }
            }
        }
    }

    private static boolean overlaps(
            String first, String second) {
        return first.isEmpty()
               || second.isEmpty()
               || first.equals(second)
               || first.startsWith(second + "/")
               || second.startsWith(first + "/");
    }

    private static void validateCompositionPath(
            String modelId, String path) {
        if (path.isBlank()
            || isMetadataPath(path)
            || Document.Path.split(path)
                    .anyMatch(
                            io.fluxzero.common.SearchUtils
                                    ::isInteger)) {
            throw new IllegalArgumentException(
                    "Model graph composition path '%s' for %s must be a non-reserved collection path without numeric segments"
                            .formatted(path, modelId));
        }
    }

    private static IllegalArgumentException collision(
            String modelId,
            String compositionPath,
            String existingPath) {
        return new IllegalArgumentException(
                "Model graph composition path '%s' for %s collides with '%s'; use a distinct @ParentId path or a registered materialized projection"
                        .formatted(
                                compositionPath,
                                modelId, existingPath));
    }

    private static final class Bounds {
        private final ModelGraphComposition
                composition;
        private int placements;
        private long bytes;

        private Bounds(
                ModelGraphComposition composition) {
            this.composition = composition;
        }

        private void addPlacement() {
            if (++placements
                > composition.getMaxPlacements()) {
                throw new IllegalArgumentException(
                        "Model graph composition exceeds maxPlacements "
                        + composition
                                .getMaxPlacements()
                        + "; narrow the result or use a materialized graph projection");
            }
        }

        private void addBytes(long additional) {
            bytes = Math.addExact(bytes, additional);
            if (bytes > composition.getMaxBytes()) {
                throw new IllegalArgumentException(
                        "Model graph composition exceeds maxBytes "
                        + composition.getMaxBytes()
                        + "; narrow the result or use a materialized graph projection");
            }
        }
    }
}
