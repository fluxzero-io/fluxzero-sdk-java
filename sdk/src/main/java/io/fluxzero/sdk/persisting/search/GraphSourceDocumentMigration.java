/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.RewriteModelSourceDocument;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.modeling.ModelDocumentProof;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.search.ModelSearchDocument;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.persisting.search.client.SearchClient;

import java.lang.reflect.Executable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Internal schema-only migration of the current canonical nodes selected by an injected Graph. */
public final class GraphSourceDocumentMigration {
    private GraphSourceDocumentMigration() { }

    /** Captures values during parameter injection, before application code can mutate any node. */
    static void capture(DeserializingMessage message, Executable method, Graph<?> graph, DocumentSerializer serializer) {
        if (message.getMetadata().get(ModelGraphDocumentManifest.TOMBSTONE_METADATA_KEY) != null) { return; }
        message.computeContextIfAbsent(Captures.class, ignored -> new Captures())
                .values.put(method, inspect(message, graph, serializer));
    }

    /** Validates the returned Graph and migrates only evolved nodes using their latest verified source state. */
    public static void finish(DeserializingMessage message, Executable method, Graph<?> graph,
                              DocumentSerializer serializer, SearchClient client) {
        if (message.getMetadata().get(ModelGraphDocumentManifest.TOMBSTONE_METADATA_KEY) != null) { return; }
        List<Node> before = message.getContext(Captures.class).map(c -> c.values.remove(method)).orElse(null);
        if (before == null) {
            throw new IllegalStateException("Graph source migration requires the captured injected Graph");
        }
        List<Node> after = inspect(message, graph, serializer);
        if (!before.equals(after)) {
            throw new IllegalArgumentException("Graph source migration cannot change business state; return the injected Graph unchanged");
        }
        Map<String, Node> evolved = new LinkedHashMap<>();
        for (Node node : before) {
            if (node.evolved()) { evolved.putIfAbsent(node.id(), node); }
        }
        if (evolved.isEmpty()) { return; }
        if (client == null) {
            throw new UnsupportedOperationException("Graph source migration has no configured search client");
        }
        String prefix = ApplicationProperties.getProperty(ApplicationProperties.MODEL_NAME_PREFIX_PROPERTY, "");
        evolved.values().forEach(node -> migrate(node, prefix, serializer, client));
    }

    private static List<Node> inspect(DeserializingMessage message, Graph<?> graph, DocumentSerializer serializer) {
        ModelGraphDocumentManifest manifest = ModelGraphDocumentManifest.from(message.getMetadata()).orElseThrow();
        if (!message.getMessageId().equals(graph.id().toString())
                || !manifest.nodes().getFirst().id().equals(graph.id().toString())
                || manifest.stateIndex() != graph.stateIndex()) {
            throw new IllegalArgumentException("Graph source migration must retain its handled root and boundary: "
                    + graph.id() + "/" + graph.stateIndex() + " vs " + message.getMessageId() + "/" + manifest.stateIndex());
        }
        List<MaterializedGraphDocumentMigration.Placement> placements = new ArrayList<>(manifest.nodes().size());
        MaterializedGraphDocumentMigration.addPlacements(graph, -1, null, 0, placements, manifest.nodes().size());
        if (placements.size() != manifest.nodes().size()) {
            throw new IllegalArgumentException("Graph source migration must retain every handled node");
        }
        List<Node> result = new ArrayList<>(placements.size());
        Map<String, Node> unique = new LinkedHashMap<>();
        for (int index = 0; index < placements.size(); index++) {
            var placement = placements.get(index);
            var source = manifest.nodes().get(index);
            MaterializedGraphDocumentMigration.validatePlacement(placement, source, manifest, serializer, index);
            Object value = placement.graph().get();
            Class<?> type = placement.graph().type();
            validateIdentity(EntityMetadata.validate(type), source.id(), value);
            SerializedDocument document = serializer.toDocument(value, source.id(), message.getTopic(), null, null);
            if (!type.getName().equals(document.getDocument().getType())) {
                throw new IllegalArgumentException("Graph source migration serialized an unexpected node type: " + source.id());
            }
            boolean evolved = !manifest.type(source).equals(document.getDocument().getType())
                    || source.revision() != document.getDocument().getRevision();
            Node node = new Node(source.id(), type, manifest.modelType(source),
                                 serializer.modelStateSnapshot(value), document.getDocument().getRevision(), evolved);
            Node previous = unique.putIfAbsent(node.id(), node);
            if (previous != null && !previous.equals(node)) {
                throw new IllegalArgumentException("Graph source migration has inconsistent shared node " + node.id());
            }
            result.add(node);
        }
        return List.copyOf(result);
    }

    private static void migrate(Node node, String prefix, DocumentSerializer serializer, SearchClient client) {
        EntityMetadata metadata = EntityMetadata.validate(node.type());
        String collection = metadata.modelSourceDocumentCollection(prefix).orElseThrow();
        // The wire operation deliberately returns no CAS outcome. Re-reading verifies completion and also ensures
        // that a retry upcasts the latest business state, never values copied from the handled projection.
        for (int attempt = 0; attempt <= 3; attempt++) {
            var inspected = client.fetchModelDocument(new GetDocument(node.id(), collection, true, true));
            var current = inspected.getDocument();
            var head = inspected.getModelHead();
            if (head != null && head.isDeleted()) { return; }
            if (current == null && head == null) { return; }
            if (current == null || head == null || !inspected.isModelStateVerified()) {
                throw new IllegalStateException("Graph node has no verified durable source: " + node.id());
            }
            if (!node.modelType().equals(head.getModelType())) {
                throw new IllegalStateException("Graph node Model type changed: " + node.id());
            }
            if (node.type().getName().equals(current.getDocument().getType())
                    && current.getDocument().getRevision() >= node.revision()) { return; }
            Object value = serializer.fromDocument(current, node.type());
            validateIdentity(metadata, node.id(), value);
            SerializedDocument replacement = serializer.toDocument(value, node.id(), collection,
                    instant(current.getTimestamp()), instant(current.getEnd()),
                    current.getMetadata().without(ModelSearchDocument.SUMMARY));
            replacement = metadata.isSearchable() ? ModelSearchDocument.preserveSummary(replacement)
                    : replacement.withoutSearchIndexes();
            if (replacement.getDocument().getType().equals(current.getDocument().getType())
                    && replacement.getDocument().getRevision() <= current.getDocument().getRevision()) { return; }
            if (attempt == 3) { break; }
            client.rewriteModelSourceDocument(new RewriteModelSourceDocument(
                    replacement, head, ModelDocumentProof.of(current, head), Guarantee.STORED)).join();
        }
        throw new ConcurrentModificationException("Graph node changed repeatedly during schema migration: " + node.id());
    }

    private static void validateIdentity(EntityMetadata metadata, String id, Object value) {
        if (value == null || !metadata.type().isInstance(value) || !id.equals(metadata.repositoryIdOf(value))) {
            throw new IllegalArgumentException("Graph source migration must retain node type and identity: " + id);
        }
    }

    private static Instant instant(Long millis) { return millis == null ? null : Instant.ofEpochMilli(millis); }

    private record Node(String id, Class<?> type, String modelType, Object state, int revision, boolean evolved) { }
    private static final class Captures {
        final Map<Executable, List<Node>> values = new ConcurrentHashMap<>();
    }
}
