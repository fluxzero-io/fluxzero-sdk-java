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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.SearchUtils;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.ModelNames;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.persisting.repository.ModelTypeResolver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Adapts a composed search-document manifest directly into the canonical indexed Graph state. */
final class MaterializedGraphFactory {

    private MaterializedGraphFactory() {
    }

    static <T> Graph<T> create(
            SerializedDocument document, Class<T> rootType, DocumentSerializer documentSerializer,
            Supplier<ModelRepository> repositorySupplier, Collection<Class<?>> registeredModelTypes,
            Map<String, String> pathOverrides) {
        ModelGraphDocumentManifest manifest = ModelGraphDocumentManifest.from(document)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Graph document %s has no typed model graph manifest".formatted(document.getId())));
        return create(new Source(
                document.getId(), document.getCollection(), document.getTimestamp(), document.getEnd(),
                documentSerializer, repositorySupplier, registeredModelTypes, pathOverrides, manifest, null,
                () -> Source.rawJson(document, documentSerializer)), rootType);
    }

    static <T> Graph<T> create(
            JsonNode document, String documentId, String collection, Long timestamp, Long end,
            ModelGraphDocumentManifest manifest, Class<T> rootType, DocumentSerializer documentSerializer,
            Supplier<ModelRepository> repositorySupplier, Collection<Class<?>> registeredModelTypes,
            Map<String, String> pathOverrides) {
        return create(document, documentId, collection, timestamp, end, manifest, rootType, documentSerializer,
                      repositorySupplier, registeredModelTypes, pathOverrides, null);
    }

    static <T> Graph<T> create(
            JsonNode document, String documentId, String collection, Long timestamp, Long end,
            ModelGraphDocumentManifest manifest, Class<T> rootType, DocumentSerializer documentSerializer,
            Supplier<ModelRepository> repositorySupplier, Collection<Class<?>> registeredModelTypes,
            Map<String, String> pathOverrides, Long previousStateIndex) {
        return create(new Source(
                documentId, collection, timestamp, end, documentSerializer, repositorySupplier,
                registeredModelTypes, pathOverrides, manifest, previousStateIndex, () -> document), rootType);
    }

    private static <T> Graph<T> create(Source source, Class<T> rootType) {
        if (source.repository instanceof ModelTypeResolver resolver) {
            resolver.modelName(rootType);
            source.registeredModelTypes.forEach(resolver::modelName);
        }
        List<Graphs.MaterializedNode> nodes = source.nodes();
        if (nodes.isEmpty() || !rootType.isAssignableFrom(nodes.getFirst().type())) {
            throw new IllegalArgumentException(
                    "Graph document %s contains root type %s instead of %s".formatted(
                            source.documentId, nodes.isEmpty() ? null : nodes.getFirst().type().getName(),
                            rootType.getName()));
        }
        return Graphs.materialized(
                nodes, rootType, source.manifest.stateIndex(), source.previousStateIndex, source.repository,
                source.declaredPaths, source.pathOverrides);
    }

    private static final class Source {
        private final String documentId;
        private final String collection;
        private final Long timestamp;
        private final Long end;
        private final DocumentSerializer documentSerializer;
        private final ModelRepository repository;
        private final ModelGraphDocumentManifest manifest;
        private final Long previousStateIndex;
        private final Supplier<JsonNode> jsonSupplier;
        private final Map<String, String> pathOverrides;
        private final List<Class<?>> registeredModelTypes;
        private final Map<Class<?>, List<String>> declaredPaths;
        private volatile JsonNode json;

        private Source(
                String documentId, String collection, Long timestamp, Long end,
                DocumentSerializer documentSerializer, Supplier<ModelRepository> repositorySupplier,
                Collection<Class<?>> registeredModelTypes, Map<String, String> pathOverrides,
                ModelGraphDocumentManifest manifest, Long previousStateIndex, Supplier<JsonNode> jsonSupplier) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.collection = collection;
            this.timestamp = timestamp;
            this.end = end;
            this.documentSerializer = Objects.requireNonNull(documentSerializer, "documentSerializer");
            this.repository = Objects.requireNonNull(repositorySupplier, "repositorySupplier").get();
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.previousStateIndex = previousStateIndex;
            this.jsonSupplier = Objects.requireNonNull(jsonSupplier, "jsonSupplier");
            this.pathOverrides = Map.copyOf(pathOverrides);
            this.registeredModelTypes = List.copyOf(registeredModelTypes);
            this.declaredPaths = declaredPaths(registeredModelTypes, pathOverrides);
        }

        private List<Graphs.MaterializedNode> nodes() {
            List<Graphs.MaterializedNode> result = new ArrayList<>(manifest.nodes().size());
            List<String> documentPaths = new ArrayList<>(manifest.nodes().size());
            for (int index = 0; index < manifest.nodes().size(); index++) {
                ModelGraphDocumentManifest.Node manifestNode = manifest.nodes().get(index);
                if (manifestNode.parent() >= index) {
                    throw new IllegalArgumentException(
                            "Invalid parent placement %s for model graph node %s"
                                    .formatted(manifestNode.parent(), manifestNode.id()));
                }
                String modelTypeName = manifest.modelType(manifestNode);
                Class<?> type = repository instanceof ModelTypeResolver resolver
                        ? resolver.modelType(modelTypeName, manifestNode.id())
                        : registeredModelTypes.stream()
                                .filter(candidate -> ModelNames.name(candidate).equals(modelTypeName))
                                .findFirst().orElse(null);
                if (type == null) {
                    throw new IllegalArgumentException(
                            "Could not resolve materialized graph Model type " + modelTypeName);
                }
                String relationshipPath = manifest.relationshipPath(manifestNode);
                String documentPath = manifestNode.parent() < 0 ? "" : joinPath(
                        joinPath(documentPaths.get(manifestNode.parent()), relationshipPath),
                        Integer.toString(manifestNode.ordinal()));
                documentPaths.add(documentPath);
                int nodeIndex = index;
                result.add(new Graphs.MaterializedNode(
                        manifestNode.id(), type, manifestNode.parent(), relationshipPath,
                        () -> convert(manifest.nodes().get(nodeIndex), type, documentPaths.get(nodeIndex))));
            }
            return List.copyOf(result);
        }

        private Object convert(ModelGraphDocumentManifest.Node node, Class<?> type, String documentPath) {
            JsonNode value = json(documentPath);
            if (value.isMissingNode() || value.isNull()) {
                return null;
            }
            if (documentSerializer instanceof Serializer serializer) {
                if (manifest.type(node).equals(type.getName())
                    && node.revision() == EntityMetadata.of(type).revision()) {
                    return serializer.convert(value, type);
                }
                Data<JsonNode> nested = new Data<>(
                        value, manifest.type(node), node.revision(), Data.JSON_FORMAT);
                return serializer.deserialize(serializer.normalize(nested), type);
            }
            SerializedDocument nested = documentSerializer.toDocument(
                    value, node.id(), collection,
                    timestamp == null ? null : Instant.ofEpochMilli(timestamp),
                    end == null ? null : Instant.ofEpochMilli(end), Metadata.empty());
            return documentSerializer.fromDocument(
                    nested.withData(() -> nested.getDocument()
                            .withType(manifest.type(node))
                            .withRevision(node.revision())), type);
        }

        private static JsonNode rawJson(
                SerializedDocument document,
                DocumentSerializer documentSerializer) {
            SerializedDocument raw = document.withData(() -> document.getDocument()
                    .withType(JsonNode.class.getName())
                    .withRevision(0));
            return documentSerializer.fromDocument(raw, JsonNode.class);
        }

        private JsonNode json(String documentPath) {
            JsonNode result = json;
            if (result == null) {
                synchronized (this) {
                    result = json;
                    if (result == null) {
                        json = result = jsonSupplier.get();
                    }
                }
            }
            if (documentPath.isEmpty()) {
                return result;
            }
            for (String rawSegment : documentPath.split("/")) {
                String segment = SearchUtils.unescapeFieldName(rawSegment);
                result = SearchUtils.isInteger(segment)
                        ? result.path(Integer.parseInt(segment)) : result.path(segment);
            }
            return result;
        }

        private static Map<Class<?>, List<String>> declaredPaths(
                Collection<Class<?>> modelTypes, Map<String, String> pathOverrides) {
            Map<Class<?>, LinkedHashSet<String>> mutable = new LinkedHashMap<>();
            for (Class<?> childType : modelTypes) {
                EntityMetadata metadata;
                try {
                    metadata = EntityMetadata.of(childType);
                } catch (RuntimeException ignored) {
                    continue;
                }
                for (EntityMetadata.ParentReference reference : metadata.parentReferences()) {
                    String path = reference.pathInParent();
                    if (path != null && !path.isBlank()) {
                        for (Class<?> parentType : reference.parentModelTypes()) {
                            mutable.computeIfAbsent(parentType, ignored -> new LinkedHashSet<>())
                                    .add(pathOverrides.getOrDefault(path, path));
                        }
                    }
                }
            }
            Map<Class<?>, List<String>> result = new LinkedHashMap<>();
            mutable.forEach((type, paths) -> result.put(type, List.copyOf(paths)));
            return Map.copyOf(result);
        }

        private static String joinPath(String prefix, String path) {
            return prefix == null || prefix.isEmpty() ? path : prefix + "/" + path;
        }
    }
}
