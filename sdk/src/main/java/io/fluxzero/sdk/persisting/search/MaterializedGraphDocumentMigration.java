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

package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.search.ModelGraphDocumentStitcher;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.Graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.fluxzero.common.search.ModelGraphDocumentManifest.METADATA_KEY;
import static io.fluxzero.common.search.ModelGraphDocumentManifest.TOMBSTONE_METADATA_KEY;

/** Builds one canonical, projection-only replacement for an upcast materialized model graph. */
public final class MaterializedGraphDocumentMigration {
    private MaterializedGraphDocumentMigration() {
    }

    /**
     * Returns a replacement only when at least one node type or revision evolved. The returned document retains the
     * exact root, state boundary and graph placement manifest of the handled projection. Tombstones are observational
     * and never become writable graph documents.
     */
    public static Optional<Migration> create(
            Graph<?> graph,
            DeserializingMessage message,
            DocumentSerializer serializer) {
        Objects.requireNonNull(graph, "Graph result");
        Objects.requireNonNull(message, "Document message");
        Objects.requireNonNull(serializer, "Document serializer");
        if (message.getMetadata().get(TOMBSTONE_METADATA_KEY) != null) {
            return Optional.empty();
        }
        String expectedManifest = Objects.requireNonNull(
                message.getMetadata().get(METADATA_KEY),
                "A materialized graph document requires a typed graph manifest");
        ModelGraphDocumentManifest manifest = ModelGraphDocumentManifest.from(message.getMetadata())
                .orElseThrow();
        validateRoot(graph, message, manifest);

        List<Placement> placements = new ArrayList<>(manifest.nodes().size());
        addPlacements(graph, -1, null, 0, placements,
                      manifest.nodes().size());
        if (placements.size() != manifest.nodes().size()) {
            throw incompatible("returned %d nodes instead of %d"
                    .formatted(placements.size(), manifest.nodes().size()));
        }

        Instant start = instant(message.getMetadata().get("$start"));
        Instant end = instant(message.getMetadata().get("$end"));
        String collection = message.getTopic();
        Map<String, SerializedDocument> documents = new LinkedHashMap<>();
        Map<String, String> modelTypes = new LinkedHashMap<>();
        List<ModelGraphEdge> edges = new ArrayList<>();
        boolean evolved = false;
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            ModelGraphDocumentManifest.Node source = manifest.nodes().get(index);
            validatePlacement(placement, source, manifest, serializer, index);
            Object value = placement.graph().get();
            if (value == null) {
                throw incompatible("node %s is empty".formatted(source.id()));
            }
            SerializedDocument direct = serializer.toDocument(
                    value, source.id(), collection, start, end, Metadata.empty());
            if (!direct.getDocument().getType().equals(placement.graph().type().getName())) {
                throw incompatible("node %s serialized as %s instead of %s".formatted(
                        source.id(), direct.getDocument().getType(), placement.graph().type().getName()));
            }
            evolved |= !manifest.type(source).equals(direct.getDocument().getType())
                       || source.revision() != direct.getDocument().getRevision();
            SerializedDocument previous = documents.putIfAbsent(source.id(), direct);
            String modelType = manifest.modelType(source);
            String previousModelType = modelTypes.putIfAbsent(source.id(), modelType);
            if (previousModelType != null && !previousModelType.equals(modelType)) {
                throw incompatible("shared node %s has inconsistent Model types".formatted(source.id()));
            }
            if (previous != null
                && (!previous.getDocument().getType().equals(direct.getDocument().getType())
                    || previous.getDocument().getRevision() != direct.getDocument().getRevision()
                    || !Arrays.equals(previous.getDocument().getValue(),
                                      direct.getDocument().getValue()))) {
                throw incompatible("shared node %s has inconsistent values".formatted(source.id()));
            }
            if (placement.parent() >= 0) {
                ModelGraphDocumentManifest.Node parent = manifest.nodes().get(placement.parent());
                edges.add(new ModelGraphEdge(
                        source.id(), parent.id(), manifest.modelType(parent),
                        placement.path(), 0L, null, false));
            }
        }
        if (!evolved) {
            return Optional.empty();
        }
        SerializedDocument replacement = ModelGraphDocumentStitcher.stitch(
                List.of(documents.get(manifest.nodes().getFirst().id())),
                edges, documents, modelTypes, ModelGraphComposition.builder().build(),
                manifest.stateIndex()).getFirst().withCollection(collection);
        return Optional.of(new Migration(expectedManifest, replacement));
    }

    private static void validateRoot(
            Graph<?> graph,
            DeserializingMessage message,
            ModelGraphDocumentManifest manifest) {
        if (!graph.isRoot()
            || !message.getMessageId().equals(graph.id().toString())
            || !manifest.nodes().getFirst().id().equals(graph.id().toString())
            || manifest.stateIndex() != graph.stateIndex()) {
            throw incompatible(
                    "the returned Graph must retain the handled root identity and state boundary");
        }
    }

    private static void validatePlacement(
            Placement placement,
            ModelGraphDocumentManifest.Node source,
            ModelGraphDocumentManifest manifest,
            DocumentSerializer serializer,
            int index) {
        String currentType = serializer instanceof Serializer typed
                ? typed.upcastType(manifest.type(source)) : manifest.type(source);
        if (!source.id().equals(placement.graph().id().toString())
            || source.parent() != placement.parent()
            || !Objects.equals(manifest.relationshipPath(source), placement.path())
            || source.ordinal() != placement.ordinal()
            || !currentType.equals(placement.graph().type().getName())) {
            throw incompatible("node %d (%s) changed identity, type or placement"
                    .formatted(index, source.id()));
        }
    }

    private static void addPlacements(
            Graph<?> graph,
            int parent,
            String path,
            int ordinal,
            List<Placement> result,
            int maximum) {
        if (result.size() >= maximum) {
            throw incompatible("the returned Graph contains additional nodes or a cycle");
        }
        int index = result.size();
        result.add(new Placement(graph, parent, path, ordinal));
        Map<String, Integer> ordinals = new HashMap<>();
        for (Graph<?> child : graph.children()) {
            String childPath = child.relationshipPath();
            if (childPath == null || childPath.isBlank()) {
                throw incompatible("a materialized graph result contains a pathless child");
            }
            addPlacements(child, index, childPath,
                          ordinals.merge(childPath, 1, Integer::sum) - 1,
                          result, maximum);
        }
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        long epochMillis = value instanceof Number number
                ? number.longValue() : Long.parseLong(value.toString());
        return Instant.ofEpochMilli(epochMillis);
    }

    private static IllegalArgumentException incompatible(String reason) {
        return new IllegalArgumentException(
                "A @HandleDocument(modelGraph = ...) Graph result may only migrate the complete handled projection; "
                + reason);
    }

    /** One compare-and-replace operation against the manifest of the handled graph document. */
    public record Migration(
            String expectedManifest,
            SerializedDocument replacement) {
    }

    private record Placement(
            Graph<?> graph,
            int parent,
            String path,
            int ordinal) {
    }
}
