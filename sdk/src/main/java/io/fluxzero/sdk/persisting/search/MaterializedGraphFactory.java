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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.persisting.repository.ModelRepository;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Reconstructs typed lazy {@link Graph} views from composed search documents. */
final class MaterializedGraphFactory {

    private MaterializedGraphFactory() {
    }

    static <T> Graph<T> create(
            SerializedDocument document,
            Class<T> rootType,
            DocumentSerializer documentSerializer,
            Supplier<ModelRepository> repositorySupplier,
            Collection<Class<?>> registeredModelTypes,
            Map<String, String> pathOverrides) {
        ModelGraphDocumentManifest manifest =
                ModelGraphDocumentManifest.from(document)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Graph document %s has no typed model graph manifest"
                                        .formatted(document.getId())));
        Context context = new Context(
                document, documentSerializer,
                repositorySupplier, registeredModelTypes,
                pathOverrides, manifest);
        Graph<?> root = context.view(0);
        if (!rootType.isAssignableFrom(root.type())) {
            throw new IllegalArgumentException(
                    "Graph document %s contains root type %s instead of %s"
                            .formatted(document.getId(), root.type().getName(),
                                       rootType.getName()));
        }
        return cast(root);
    }

    static <T> Graph<T> create(
            JsonNode document,
            String documentId,
            String collection,
            Long timestamp,
            Long end,
            ModelGraphDocumentManifest manifest,
            Class<T> rootType,
            DocumentSerializer documentSerializer,
            Supplier<ModelRepository> repositorySupplier,
            Collection<Class<?>> registeredModelTypes,
            Map<String, String> pathOverrides) {
        Context context = new Context(
                document, documentId, collection,
                timestamp, end, documentSerializer,
                repositorySupplier, registeredModelTypes,
                pathOverrides, manifest);
        Graph<?> root = context.view(0);
        if (!rootType.isAssignableFrom(root.type())) {
            throw new IllegalArgumentException(
                    "Graph document %s contains root type %s instead of %s"
                            .formatted(documentId, root.type().getName(),
                                       rootType.getName()));
        }
        return cast(root);
    }

    private static final class Context {
        private final String documentId;
        private final String collection;
        private final Long timestamp;
        private final Long end;
        private final DocumentSerializer documentSerializer;
        private final Supplier<ModelRepository> repositorySupplier;
        private final Supplier<JsonNode> jsonSupplier;
        private final long stateIndex;
        private final List<Node> nodes;
        private final Map<Class<?>, List<String>> declaredPaths;
        private volatile JsonNode json;

        private Context(
                SerializedDocument document,
                DocumentSerializer documentSerializer,
                Supplier<ModelRepository> repositorySupplier,
                Collection<Class<?>> registeredModelTypes,
                Map<String, String> pathOverrides,
                ModelGraphDocumentManifest manifest) {
            this(document.getId(), document.getCollection(),
                 document.getTimestamp(), document.getEnd(),
                 documentSerializer, repositorySupplier,
                 registeredModelTypes, pathOverrides, manifest,
                 () -> documentSerializer.fromDocument(
                         document, JsonNode.class));
        }

        private Context(
                JsonNode document,
                String documentId,
                String collection,
                Long timestamp,
                Long end,
                DocumentSerializer documentSerializer,
                Supplier<ModelRepository> repositorySupplier,
                Collection<Class<?>> registeredModelTypes,
                Map<String, String> pathOverrides,
                ModelGraphDocumentManifest manifest) {
            this(documentId, collection, timestamp, end,
                 documentSerializer, repositorySupplier,
                 registeredModelTypes, pathOverrides, manifest,
                 () -> document);
        }

        private Context(
                String documentId,
                String collection,
                Long timestamp,
                Long end,
                DocumentSerializer documentSerializer,
                Supplier<ModelRepository> repositorySupplier,
                Collection<Class<?>> registeredModelTypes,
                Map<String, String> pathOverrides,
                ModelGraphDocumentManifest manifest,
                Supplier<JsonNode> jsonSupplier) {
            this.documentId = Objects.requireNonNull(
                    documentId, "documentId");
            this.collection = collection;
            this.timestamp = timestamp;
            this.end = end;
            this.documentSerializer = Objects.requireNonNull(
                    documentSerializer, "documentSerializer");
            this.repositorySupplier = Objects.requireNonNull(
                    repositorySupplier, "repositorySupplier");
            this.jsonSupplier = Objects.requireNonNull(
                    jsonSupplier, "jsonSupplier");
            this.stateIndex = manifest.stateIndex();
            this.declaredPaths = declaredPaths(
                    registeredModelTypes, pathOverrides);
            List<Node> mutable = new ArrayList<>(manifest.nodes().size());
            for (int i = 0; i < manifest.nodes().size(); i++) {
                ModelGraphDocumentManifest.Node manifestNode =
                        manifest.nodes().get(i);
                String typeName = manifest.type(manifestNode);
                Class<?> type = ReflectionUtils.classForName(typeName, null);
                if (type == null) {
                    throw new IllegalArgumentException(
                            "Could not resolve materialized graph model type "
                            + typeName);
                }
                String relationshipPath =
                        manifest.relationshipPath(manifestNode);
                String documentPath;
                if (manifestNode.parent() < 0) {
                    documentPath = "";
                } else {
                    Node parent = mutable.get(manifestNode.parent());
                    documentPath = joinPath(
                            joinPath(parent.documentPath,
                                     relationshipPath),
                            Integer.toString(manifestNode.ordinal()));
                }
                mutable.add(new Node(
                        i, manifestNode, type,
                        relationshipPath, documentPath));
            }
            for (Node node : mutable) {
                int parent = node.manifest.parent();
                if (parent >= node.index || parent >= mutable.size()) {
                    throw new IllegalArgumentException(
                            "Invalid parent placement %s for model graph node %s"
                                    .formatted(parent, node.manifest.id()));
                }
                if (parent >= 0) {
                    mutable.get(parent).children.add(node.index);
                }
            }
            mutable.forEach(Node::freeze);
            this.nodes = List.copyOf(mutable);
        }

        private Graph<?> view(int index) {
            Node node = nodes.get(index);
            Graph<?> result = node.view;
            if (result == null) {
                synchronized (node) {
                    result = node.view;
                    if (result == null) {
                        node.view = result = new MaterializedGraph<>(this, node);
                    }
                }
            }
            return result;
        }

        private JsonNode json() {
            JsonNode result = json;
            if (result == null) {
                synchronized (this) {
                    result = json;
                    if (result == null) {
                        json = result = jsonSupplier.get();
                    }
                }
            }
            return result;
        }

        private JsonNode json(Node node) {
            JsonNode result = json();
            if (node.documentPath.isEmpty()) {
                return result;
            }
            for (String rawSegment : node.documentPath.split("/")) {
                String segment = SearchUtils.unescapeFieldName(rawSegment);
                result = SearchUtils.isInteger(segment)
                        ? result.path(Integer.parseInt(segment))
                        : result.path(segment);
            }
            return result;
        }

        private Object convert(Node node) {
            JsonNode value = json(node);
            if (value.isMissingNode() || value.isNull()) {
                return null;
            }
            if (documentSerializer instanceof Serializer serializer) {
                return serializer.convert(value, node.type);
            }
            SerializedDocument nested = documentSerializer.toDocument(
                    value, node.manifest.id(), collection,
                    timestamp == null ? null
                            : Instant.ofEpochMilli(timestamp),
                    end == null ? null
                            : Instant.ofEpochMilli(end),
                    Metadata.empty());
            return documentSerializer.fromDocument(nested, node.type);
        }

        private ModelRepository repository() {
            ModelRepository result = repositorySupplier.get();
            if (result == null) {
                throw new IllegalStateException(
                        "Materialized graph history and updates require a model repository");
            }
            return result;
        }

        private static Map<Class<?>, List<String>> declaredPaths(
                Collection<Class<?>> modelTypes,
                Map<String, String> pathOverrides) {
            Map<Class<?>, LinkedHashSet<String>> mutable =
                    new LinkedHashMap<>();
            for (Class<?> childType : modelTypes) {
                ModelMetadata metadata;
                try {
                    metadata = ModelMetadata.of(childType);
                } catch (RuntimeException ignored) {
                    continue;
                }
                for (ModelMetadata.ParentReference reference :
                        metadata.parentReferences()) {
                    Class<?> parentType = reference.parentModelType();
                    String path = reference.path();
                    if (parentType != null && path != null
                        && !path.isBlank()) {
                        mutable.computeIfAbsent(parentType,
                                                ignored -> new LinkedHashSet<>())
                                .add(pathOverrides.getOrDefault(path, path));
                    }
                }
            }
            Map<Class<?>, List<String>> result = new LinkedHashMap<>();
            mutable.forEach((type, paths) ->
                    result.put(type, List.copyOf(paths)));
            return Map.copyOf(result);
        }

        private static String joinPath(
                String prefix,
                String path) {
            return prefix == null || prefix.isEmpty()
                    ? path : prefix + "/" + path;
        }
    }

    private static final class Node {
        private final int index;
        private final ModelGraphDocumentManifest.Node manifest;
        private final Class<?> type;
        private final String relationshipPath;
        private final String documentPath;
        private List<Integer> children = new ArrayList<>();
        private volatile Graph<?> view;

        private Node(
                int index,
                ModelGraphDocumentManifest.Node manifest,
                Class<?> type,
                String relationshipPath,
                String documentPath) {
            this.index = index;
            this.manifest = manifest;
            this.type = type;
            this.relationshipPath = relationshipPath;
            this.documentPath = documentPath;
        }

        private void freeze() {
            children = List.copyOf(children);
        }
    }

    private static final class MaterializedGraph<T>
            implements Graph<T> {
        private final Context context;
        private final Node node;
        private volatile boolean materialized;
        private T value;
        private volatile Graph<T> durable;

        private MaterializedGraph(Context context, Node node) {
            this.context = context;
            this.node = node;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            if (!materialized) {
                synchronized (this) {
                    if (!materialized) {
                        value = (T) context.convert(node);
                        materialized = true;
                    }
                }
            }
            return value;
        }

        @Override public Object id() { return node.manifest.id(); }
        @Override @SuppressWarnings("unchecked") public Class<T> type() {
            return (Class<T>) node.type;
        }
        @Override public Collection<?> aliases() {
            T value = get();
            return value == null ? List.of()
                    : ModelMetadata.of(type()).aliases(value);
        }
        @Override public String relationshipPath() {
            return node.relationshipPath;
        }
        @Override public long stateIndex() { return context.stateIndex; }
        @Override public long revisionStateIndex() { return durable().revisionStateIndex(); }
        @Override public String lastEventId() { return durable().lastEventId(); }
        @Override public Long lastEventIndex() { return durable().lastEventIndex(); }
        @Override public long sequenceNumber() { return durable().sequenceNumber(); }
        @Override public Instant timestamp() { return durable().timestamp(); }
        @Override public Graph<?> root() {
            Node current = node;
            while (current.manifest.parent() >= 0) {
                current = context.nodes.get(current.manifest.parent());
            }
            return context.view(current.index);
        }
        @Override public Optional<Graph<?>> parent() {
            return node.manifest.parent() < 0
                    ? durable().parent()
                    : Optional.of(context.view(node.manifest.parent()));
        }
        @Override public List<Graph<?>> parents() {
            if (node.manifest.parent() < 0) {
                return durable().parents();
            }
            Graph<?> placedParent = context.view(node.manifest.parent());
            LinkedHashMap<String, Graph<?>> result = new LinkedHashMap<>();
            result.put(placedParent.type().getName() + ':' + placedParent.id(), placedParent);
            durable().parents().forEach(parent -> result.putIfAbsent(
                    parent.type().getName() + ':' + parent.id(), parent));
            return List.copyOf(result.values());
        }
        @Override public <P> Optional<Graph<P>> parent(Class<P> parentType) {
            if (node.manifest.parent() >= 0) {
                Graph<?> placedParent = context.view(node.manifest.parent());
                if (parentType.isAssignableFrom(placedParent.type())) {
                    return Optional.of(cast(placedParent));
                }
            }
            return durable().parent(parentType);
        }
        @Override public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
            Graph<?> candidate = this;
            while (candidate != null) {
                if (ancestorType.isAssignableFrom(candidate.type())) {
                    return Optional.of(cast(candidate));
                }
                int parent = ((MaterializedGraph<?>) candidate).node.manifest.parent();
                candidate = parent < 0 ? null : context.view(parent);
            }
            return durable().ancestor(ancestorType);
        }
        @Override public List<Graph<?>> children() {
            return node.children.stream().<Graph<?>>map(context::view).toList();
        }
        @Override public List<String> childPaths() {
            LinkedHashSet<String> result = new LinkedHashSet<>(
                    context.declaredPaths.getOrDefault(type(), List.of()));
            Graph.super.childPaths().forEach(result::add);
            return List.copyOf(result);
        }
        @Override public <C> List<Graph<C>> children(Class<C> childType) {
            Map<String, List<Graph<C>>> byPath = new LinkedHashMap<>();
            for (Graph<?> child : children()) {
                if (childType.isAssignableFrom(child.type())) {
                    byPath.computeIfAbsent(child.relationshipPath(),
                                           ignored -> new ArrayList<>())
                            .add(cast(child));
                }
            }
            if (byPath.size() > 1) {
                throw new IllegalStateException(
                        "Model %s has %s children at multiple paths %s; request an explicit path"
                                .formatted(id(), childType.getName(), byPath.keySet()));
            }
            return byPath.values().stream().findFirst().map(List::copyOf)
                    .orElse(List.of());
        }
        @Override public <C> List<Graph<C>> children(
                String path, Class<C> childType) {
            return children().stream()
                    .filter(child -> Objects.equals(path, child.relationshipPath()))
                    .filter(child -> childType.isAssignableFrom(child.type()))
                    .map(MaterializedGraphFactory::<C>cast).toList();
        }
        @Override public <D> List<Graph<D>> descendants(Class<D> descendantType) {
            return descendants(null, descendantType);
        }
        @Override public <D> List<Graph<D>> descendants(
                String path, Class<D> descendantType) {
            List<Graph<D>> result = new ArrayList<>();
            Deque<PathGraph> remaining = new ArrayDeque<>();
            children().forEach(child -> remaining.addLast(
                    new PathGraph(child, child.relationshipPath())));
            while (!remaining.isEmpty()) {
                PathGraph candidate = remaining.removeFirst();
                if ((path == null || Objects.equals(path, candidate.path))
                    && descendantType.isAssignableFrom(candidate.graph.type())) {
                    result.add(cast(candidate.graph));
                }
                candidate.graph.children().forEach(child ->
                        remaining.addLast(new PathGraph(
                                child, candidate.path == null
                                        || child.relationshipPath() == null
                                        ? null : candidate.path + "/"
                                                 + child.relationshipPath())));
            }
            return List.copyOf(result);
        }

        @Override public Graph<T> apply(Object update) { return durable().apply(update); }
        @Override public Graph<T> apply(Object update, Metadata metadata) {
            return durable().apply(update, metadata);
        }
        @Override public Graph<T> apply(DeserializingMessage update) {
            return durable().apply(update);
        }
        @Override public Graph<T> apply(Message update) { return durable().apply(update); }
        @Override public Graph<T> apply(Object... updates) { return durable().apply(updates); }
        @Override public Graph<T> apply(Collection<?> updates) { return durable().apply(updates); }
        @Override public Graph<T> update(UnaryOperator<T> update) { return durable().update(update); }
        @Override public Graph<T> commit() { return durable().commit(); }
        @Override public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
            durable().assertLegal(update);
            return this;
        }
        @Override public Graph<T> assertAndApply(Object update) {
            return durable().assertAndApply(update);
        }
        @Override public Graph<T> assertAndApply(Object update, Metadata metadata) {
            return durable().assertAndApply(update, metadata);
        }
        @Override public Graph<T> previous() { return durable().previous(); }
        @Override public Graph<T> atStateIndex(long stateIndex) {
            return context.repository().loadGraphAt(
                    id().toString(), type(), stateIndex, Options.DEFAULT);
        }
        @Override public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
            return durable().playBackToEvent(eventIndex, eventId);
        }
        @Override public Optional<Graph<T>> playBackToCondition(
                Predicate<Graph<T>> condition) {
            return durable().playBackToCondition(condition);
        }

        private Graph<T> durable() {
            Graph<T> result = durable;
            if (result == null) {
                synchronized (this) {
                    result = durable;
                    if (result == null) {
                        ModelRepository repository = context.repository();
                        durable = result = context.stateIndex >= 0L
                                ? repository.loadGraphAt(
                                        id().toString(), type(),
                                        context.stateIndex, Options.DEFAULT)
                                : repository.loadGraph(
                                        id().toString(), type(), Options.DEFAULT);
                    }
                }
            }
            return result;
        }

        private record PathGraph(Graph<?> graph, String path) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Graph<T> cast(Graph<?> graph) {
        return (Graph<T>) graph;
    }
}
