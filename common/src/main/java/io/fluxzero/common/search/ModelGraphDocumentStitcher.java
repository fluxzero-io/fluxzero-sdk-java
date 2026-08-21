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
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.SortableEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.fluxzero.common.search.JacksonInverter.isMetadataPath;
import static io.fluxzero.common.search.JacksonInverter.metadataPath;

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
        return stitch(roots, edges, documents, composition, -1L);
    }

    /**
     * Composes every root document and records the exact model-state boundary in its hidden graph manifest.
     */
    public static List<SerializedDocument> stitch(
            List<SerializedDocument> roots,
            Collection<ModelGraphEdge> edges,
            Map<String, SerializedDocument> documents,
            ModelGraphComposition composition,
            long stateIndex) {
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
            SerializedDocument rootDocument =
                    available == null ? root : available;
            Document composed = compose(
                    root.getId(),
                    rootDocument,
                    children, documents, bounds, 0,
                    new LinkedHashSet<>());
            composed = withManifest(
                    composed,
                    manifest(rootDocument, children,
                             documents, stateIndex));
            SerializedDocument serialized =
                    new SerializedDocument(composed);
            bounds.verifyOutputBytes(
                    serialized.bytes());
            result.add(serialized);
        }
        return List.copyOf(result);
    }

    private static ModelGraphDocumentManifest manifest(
            SerializedDocument root,
            Map<String, List<ModelGraphEdge>> children,
            Map<String, SerializedDocument> documents,
            long stateIndex) {
        List<String> types = new ArrayList<>();
        Map<String, Integer> typeIndexes = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        Map<String, Integer> pathIndexes = new LinkedHashMap<>();
        List<ModelGraphDocumentManifest.Node> nodes = new ArrayList<>();
        appendManifestNode(
                root.getId(), root, -1, null, 0, children,
                documents, types, typeIndexes,
                paths, pathIndexes, nodes,
                new LinkedHashSet<>());
        return new ModelGraphDocumentManifest(
                stateIndex, types, paths, nodes);
    }

    private static void appendManifestNode(
            String modelId,
            SerializedDocument root,
            int parent,
            String relationshipPath,
            int ordinal,
            Map<String, List<ModelGraphEdge>> children,
            Map<String, SerializedDocument> documents,
            List<String> types,
            Map<String, Integer> typeIndexes,
            List<String> paths,
            Map<String, Integer> pathIndexes,
            List<ModelGraphDocumentManifest.Node> nodes,
            Set<String> ancestry) {
        if (!ancestry.add(modelId)) {
            throw new IllegalArgumentException(
                    "Model graph composition encountered a cycle at "
                    + modelId);
        }
        try {
            SerializedDocument document = parent < 0
                    ? root : documents.get(modelId);
            if (document == null) {
                return;
            }
            int nodeIndex = nodes.size();
            String type = document.deserializeDocument().getType();
            int typeIndex = typeIndexes.computeIfAbsent(type, key -> {
                types.add(key);
                return types.size() - 1;
            });
            int pathIndex = relationshipPath == null ? -1
                    : pathIndexes.computeIfAbsent(relationshipPath, key -> {
                        paths.add(key);
                        return paths.size() - 1;
                    });
            nodes.add(new ModelGraphDocumentManifest.Node(
                    modelId, typeIndex, parent,
                    pathIndex, ordinal));
            Map<String, List<ModelGraphEdge>> byPath =
                    new LinkedHashMap<>();
            children.getOrDefault(modelId, List.of()).stream()
                    .filter(edge -> documents.containsKey(
                            edge.getChildId()))
                    .forEach(edge -> byPath.computeIfAbsent(
                                    edge.getPath(), ignored ->
                                            new ArrayList<>())
                            .add(edge));
            byPath.forEach((path, pathEdges) -> {
                pathEdges.sort(Comparator.comparing(
                        ModelGraphEdge::getChildId));
                for (int childOrdinal = 0;
                     childOrdinal < pathEdges.size(); childOrdinal++) {
                    ModelGraphEdge edge = pathEdges.get(childOrdinal);
                    appendManifestNode(
                            edge.getChildId(), root, nodeIndex, path, childOrdinal,
                            children, documents,
                            types, typeIndexes,
                            paths, pathIndexes,
                            nodes, ancestry);
                }
            });
        } finally {
            ancestry.remove(modelId);
        }
    }

    private static Document withManifest(
            Document document,
            ModelGraphDocumentManifest manifest) {
        Map<Document.Entry, List<Document.Path>> entries =
                new LinkedHashMap<>();
        String manifestPath = metadataPath(
                ModelGraphDocumentManifest.METADATA_KEY);
        document.getEntries().forEach((entry, paths) -> {
            List<Document.Path> retained = paths.stream()
                    .filter(path -> !path.getValue().equals(manifestPath)
                                    && !path.getValue().startsWith(
                            manifestPath + "/"))
                    .toList();
            if (!retained.isEmpty()) {
                entries.put(entry, new ArrayList<>(retained));
            }
        });
        new JacksonInverter().addMetadataEntries(
                entries,
                Metadata.of(ModelGraphDocumentManifest.METADATA_KEY,
                            manifest.serialize()));
        LinkedHashSet<FacetEntry> facets = new LinkedHashSet<>(
                document.getFacets());
        facets.add(new FacetEntry(
                ModelGraphDocumentManifest.FACET_NAME,
                "1"));
        return document.toBuilder()
                .entries(entries)
                .facets(Collections.unmodifiableSet(facets))
                .build();
    }

    private static String joinPath(
            String prefix, String path) {
        return prefix == null || prefix.isEmpty()
                ? path : append(prefix, path);
    }

    /**
     * Applies projection-local canonical path replacements to graph edges before composition.
     */
    public static List<ModelGraphEdge> applyPathOverrides(
            Collection<ModelGraphEdge> edges,
            Collection<ModelGraphPathOverride> overrides) {
        Objects.requireNonNull(edges, "Model graph edges");
        Objects.requireNonNull(
                overrides, "Model graph path overrides");
        if (overrides.isEmpty()) {
            return List.copyOf(edges);
        }
        Map<String, String> replacements =
                new LinkedHashMap<>();
        Map<String, String> canonicalByProjection =
                new LinkedHashMap<>();
        overrides.forEach(override -> {
            String previous =
                    replacements.put(
                            override.getPath(),
                            override.getProjectionPath());
            if (previous != null
                && !previous.equals(
                        override.getProjectionPath())) {
                throw new IllegalArgumentException(
                        "Multiple graph path overrides target canonical path "
                        + override.getPath());
            }
            String previousCanonical =
                    canonicalByProjection.put(
                            override.getProjectionPath(),
                            override.getPath());
            if (previousCanonical != null
                && !previousCanonical.equals(
                        override.getPath())) {
                throw new IllegalArgumentException(
                        "Multiple graph paths project to "
                        + override.getProjectionPath());
            }
        });
        return edges.stream()
                .map(edge -> {
                    String path =
                            replacements.getOrDefault(
                                    edge.getPath(),
                                    edge.getPath());
                    return Objects.equals(
                            path, edge.getPath())
                            ? edge
                            : edge.withPath(path);
                })
                .toList();
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
            bounds.reserveSourceBytes(
                    serialized.bytes());
            Document direct =
                    serialized.deserializeDocument();
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
            if (bounds.composition.getMaxDepth()
                != ModelGraphComposition.UNBOUNDED
                && depth >= bounds.composition
                        .getMaxDepth()) {
                throw new IllegalArgumentException(
                        "Model graph composition exceeds maxDepth "
                        + bounds.composition.getMaxDepth()
                        + "; narrow the result or remove the explicit composition limit");
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
                            summaries, bounds);
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
            List<String> summaries,
            Bounds bounds) {
        child.getEntries().forEach(
                (entry, paths) -> {
                    List<Document.Path> prefixed =
                            paths.stream()
                                    .filter(path ->
                                                    !isMetadataPath(
                                                            path.getValue()))
                                    .map(path -> {
                                        bounds.reservePrefix(
                                                prefix,
                                                path.getValue());
                                        return new Document.Path(
                                                append(
                                                        prefix,
                                                        path.getValue()));
                                    })
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
                .map(facet -> {
                    bounds.reservePrefix(
                            prefix,
                            facet.getName());
                    return facet.toBuilder()
                            .name(append(
                                    prefix,
                                    facet.getName()))
                            .build();
                })
                .forEach(facets::add);
        child.getSortables().stream()
                .map(sortable -> {
                    bounds.reservePrefix(
                            prefix,
                            sortable.getName());
                    return sortable.withName(
                            append(
                                    prefix,
                                    sortable.getName()));
                })
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
        private long reservedBytes;
        private long outputBytes;

        private Bounds(
                ModelGraphComposition composition) {
            this.composition = composition;
        }

        private void addPlacement() {
            placements++;
            if (composition.getMaxPlacements()
                != ModelGraphComposition.UNBOUNDED
                && placements
                   > composition.getMaxPlacements()) {
                throw new IllegalArgumentException(
                        "Model graph composition exceeds maxPlacements "
                        + composition
                                .getMaxPlacements()
                        + "; narrow the result or use a materialized graph projection");
            }
        }

        private void reserveSourceBytes(
                long additional) {
            if (composition.getMaxBytes()
                == ModelGraphComposition.UNBOUNDED) {
                return;
            }
            reservedBytes = add(
                    reservedBytes, additional);
            verifyTotalBytes();
        }

        private void reservePrefix(
                String prefix, String path) {
            if (composition.getMaxBytes()
                == ModelGraphComposition.UNBOUNDED) {
                return;
            }
            long additional =
                    utf8Length(prefix)
                    + (path == null || path.isEmpty()
                            ? 0L : 1L);
            reservedBytes = add(
                    reservedBytes, additional);
            verifyTotalBytes();
        }

        private static long utf8Length(
                String value) {
            long result = 0L;
            for (int index = 0;
                 index < value.length();
                 index++) {
                char current =
                        value.charAt(index);
                if (current <= 0x7f) {
                    result++;
                } else if (current <= 0x7ff) {
                    result += 2L;
                } else if (Character.isHighSurrogate(
                        current)
                           && index + 1
                              < value.length()
                           && Character.isLowSurrogate(
                        value.charAt(index + 1))) {
                    result += 4L;
                    index++;
                } else {
                    result += 3L;
                }
            }
            return result;
        }

        private void verifyOutputBytes(
                long additional) {
            if (composition.getMaxBytes()
                == ModelGraphComposition.UNBOUNDED) {
                return;
            }
            outputBytes = add(
                    outputBytes, additional);
            verifyTotalBytes();
        }

        private long add(
                long current, long additional) {
            try {
                return Math.addExact(
                        current, additional);
            } catch (ArithmeticException overflow) {
                throw maxBytesExceeded();
            }
        }

        private void verifyTotalBytes() {
            if (add(reservedBytes, outputBytes)
                > composition.getMaxBytes()) {
                throw maxBytesExceeded();
            }
        }

        private IllegalArgumentException
                maxBytesExceeded() {
            return new IllegalArgumentException(
                    "Model graph composition exceeds maxBytes "
                    + composition.getMaxBytes()
                    + "; narrow the result or use a materialized graph projection");
        }
    }
}
