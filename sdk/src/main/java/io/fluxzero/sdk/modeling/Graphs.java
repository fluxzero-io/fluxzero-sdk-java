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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
import io.fluxzero.sdk.persisting.repository.ModelReadBoundary;
import io.fluxzero.sdk.persisting.repository.ModelRepository;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Construction boundary for the single indexed {@link Graph} implementation.
 * <p>
 * Applications normally obtain graphs through injection or {@link ModelRepository#loadGraph(Id)}.
 */
public final class Graphs {
    private Graphs() {
    }

    /** Creates a lazy one-model graph without loading relationships. */
    public static <T> Graph<T> lazy(Entity<T> entity, long stateIndex, ModelRepository repository) {
        return GraphState.entity(entity, stateIndex, repository, Map.of(entity.id().toString(), entity),
                                 false, false, ModelReadBoundary.current(), Map.of()).root();
    }

    /** Creates a detached graph whose model and relationship state remain lazy. */
    public static <T> Graph<T> lazy(Object modelId, Class<T> modelType, ModelRepository repository) {
        EntityMetadata metadata = EntityMetadata.validate(modelType);
        return GraphState.identity(modelId, metadata.repositoryId(modelId), false, modelType, repository).root();
    }

    /** Creates a detached graph for an exact persisted identity. */
    public static <T> Graph<T> lazyRepositoryId(
            String repositoryId, Class<T> modelType, ModelRepository repository) {
        return GraphState.identity(repositoryId, repositoryId, true, modelType, repository).root();
    }

    /** Creates a detached graph for a parent-scoped model. */
    public static <T> Graph<T> lazy(
            Object parentId, Class<?> parentType, Object modelId, Class<T> modelType, ModelRepository repository) {
        String repositoryId = EntityMetadata.validate(modelType).repositoryId(modelId, parentId, parentType);
        return GraphState.identity(modelId, repositoryId, true, modelType, repository).root();
    }

    /** Creates a graph that reuses all values loaded for the same handler boundary. */
    static <T> Graph<T> lazy(Entity<T> entity, CommitAttempt context, ModelRepository repository) {
        return GraphState.entity(entity, context.readStateIndex(), repository, context.entities(), false, true,
                                 ModelReadBoundary.forGraph(context.readStateIndex(), true, false), Map.of()).root();
    }

    /** Creates a complete graph from one coherent repository reconstruction. */
    public static <T> Graph<T> compose(
            String rootId, long stateIndex, Map<String, Entity<?>> models, List<ModelGraphEdge> edges,
            ModelRepository repository, boolean historical) {
        return compose(
                rootId, stateIndex, models, edges, repository, historical,
                ModelReadBoundary.state(stateIndex, false));
    }

    /** Creates a complete graph while retaining its exact repository boundary for lazy relationship loads. */
    public static <T> Graph<T> compose(
            String rootId, long stateIndex, Map<String, Entity<?>> models, List<ModelGraphEdge> edges,
            ModelRepository repository, boolean historical, ModelReadBoundary boundary) {
        return GraphState.composed(
                rootId, stateIndex, models, edges, repository, historical,
                boundary, Map.of()).root();
    }

    /** Builds one indexed state with visible message-batch values applied before graph selection. */
    public static <T> Graph<T> compose(
            String rootId, long stateIndex, Map<String, Entity<?>> durableModels, List<ModelGraphEdge> durableEdges,
            ModelRepository repository, boolean historical, ModelReadBoundary boundary,
            String namespace, Class<T> rootType, Graph.Options options,
            Map<String, Entity<?>> staged,
            Function<Entity<?>, Graph<?>> supplementalLoader) {
        if (staged.isEmpty()) {
            return compose(
                    rootId, stateIndex, durableModels, durableEdges,
                    repository, historical, boundary);
        }
        Entity<?> stagedRoot = staged.get(rootId);
        if (stagedRoot != null && stagedRoot.get() == null) {
            Entity<?> durableRoot = durableModels.get(rootId);
            if (durableRoot == null) {
                durableRoot = ImmutableModelRoot.builder()
                        .id(rootId).type((Class) stagedRoot.type())
                        .idProperty(EntityMetadata.validate(stagedRoot.type())
                                            .entityId().orElseThrow().name())
                        .value(null).build();
            }
            Entity<?> deleted = ModelBatchScope.overlayCurrent(
                    namespace, rootId, (Class) stagedRoot.type(),
                    durableRoot);
            return GraphState.composed(
                    rootId, stateIndex, Map.of(rootId, deleted), List.of(),
                    repository, historical, boundary, Map.of()).root();
        }
        LinkedHashMap<String, Entity<?>> models = new LinkedHashMap<>(durableModels);
        LinkedHashSet<ModelGraphEdge> edges = new LinkedHashSet<>(durableEdges);
        edges.removeIf(edge -> staged.containsKey(edge.getChildId()));
        staged.forEach((modelId, value) -> {
            if (value.get() != null) {
                addParentEdges(modelId, value.type(), value.get(), edges);
            }
        });

        LinkedHashSet<String> selected = selectedIds(rootId, edges, options);
        for (String modelId : selected) {
            Entity<?> candidate = staged.get(modelId);
            if (candidate == null || models.containsKey(modelId)
                || !ModelBatchScope.existedBefore(candidate)) {
                continue;
            }
            GraphView<?> supplemental = adapt(supplementalLoader.apply(candidate));
            models.putAll(supplemental.state().knownModels);
            supplemental.state().edges.stream()
                    .filter(edge -> !staged.containsKey(edge.getChildId()))
                    .forEach(edges::add);
        }

        LinkedHashSet<String> finalSelection = selectedIds(rootId, edges, options);
        LinkedHashMap<String, Entity<?>> selectedModels = new LinkedHashMap<>();
        for (String modelId : finalSelection) {
            Entity<?> candidate = staged.get(modelId);
            Entity<?> entity = models.get(modelId);
            if (candidate != null) {
                if (entity == null) {
                    entity = ImmutableModelRoot.builder()
                            .id(modelId).type((Class) candidate.type())
                            .idProperty(EntityMetadata.validate(candidate.type())
                                                .entityId().orElseThrow().name())
                            .value(null).build();
                }
                entity = ModelBatchScope.overlayCurrent(
                        namespace, modelId, (Class) candidate.type(), entity);
            }
            if (entity == null) {
                throw new IllegalArgumentException(
                        "Message-batch model graph contains an unloaded node " + modelId);
            }
            selectedModels.put(modelId, entity);
        }
        Class<?> effectiveRootType = stagedRoot == null ? rootType : stagedRoot.type();
        if (!rootType.isAssignableFrom(effectiveRootType)) {
            throw new IllegalArgumentException(
                    "Message-batch graph root '%s' has staged type %s instead of %s"
                            .formatted(rootId, effectiveRootType.getName(), rootType.getName()));
        }
        List<ModelGraphEdge> selectedEdges = edges.stream()
                .filter(edge -> finalSelection.contains(edge.getParentId())
                                && finalSelection.contains(edge.getChildId()))
                .toList();
        return GraphState.composed(
                rootId, stateIndex, selectedModels, selectedEdges,
                repository, historical, boundary, Map.of()).root();
    }

    private static LinkedHashSet<String> selectedIds(
            String rootId, Collection<ModelGraphEdge> edges, Graph.Options options) {
        LinkedHashMap<String, List<ModelGraphEdge>> children = new LinkedHashMap<>();
        edges.forEach(edge -> children.computeIfAbsent(
                edge.getParentId(), ignored -> new ArrayList<>()).add(edge));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(rootId);
        List<String> frontier = List.of(rootId);
        for (int depth = 0; !frontier.isEmpty()
                            && (options.maxDepth() < 0 || depth < options.maxDepth()); depth++) {
            List<String> next = new ArrayList<>();
            frontier.forEach(parent -> children.getOrDefault(parent, List.of()).forEach(edge -> {
                if (result.add(edge.getChildId())) {
                    if (options.maxModels() >= 0 && result.size() > options.maxModels()) {
                        throw new IllegalArgumentException(
                                "Model graph exceeds maxModels " + options.maxModels());
                    }
                    next.add(edge.getChildId());
                }
            }));
            frontier = next;
        }
        return result;
    }

    private static void addParentEdges(
            String modelId, Class<?> modelType, Object value, Collection<ModelGraphEdge> edges) {
        for (EntityMetadata.ParentReference parent : EntityMetadata.validate(modelType).parentReferences()) {
            Object parentId = parent.read(value);
            if (parentId != null) {
                Class<?> parentType = parent.parentModelType(parentId);
                edges.add(new ModelGraphEdge(
                        modelId, parent.repositoryId(parentId),
                        parentType == null ? null : parentType.getName(),
                        parent.path().isEmpty() ? null : parent.path(), -1L, null));
            }
        }
    }

    /** One reachable parent identity in deterministic traversal order. */
    public record AncestorPlacement(
            String id, int depth, List<ModelGraphEdge> incoming) {
    }

    /** Indexes parent edges once and returns every ancestor reachable from the supplied model roots. */
    public static List<AncestorPlacement> ancestors(
            Collection<String> roots, List<ModelGraphEdge> edges,
            int maxDepth, int maxModels) {
        return GraphState.ancestors(roots, edges, maxDepth, maxModels);
    }

    /** Description of one placement in a materialized graph manifest. */
    public record MaterializedNode(
            String id, Class<?> type, int parent, String relationshipPath, Supplier<?> value) {
        public MaterializedNode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            if (parent < -1) {
                throw new IllegalArgumentException("Materialized graph parent must be at least -1");
            }
        }
    }

    /** Creates the same indexed graph view for a materialized search document. */
    public static <T> Graph<T> materialized(
            List<MaterializedNode> nodes, Class<T> rootType, long stateIndex, Long previousStateIndex,
            ModelRepository repository, Map<Class<?>, List<String>> declaredPaths,
            Map<String, String> pathOverrides) {
        Graph<T> result = GraphState.materialized(
                nodes, rootType, stateIndex, previousStateIndex, repository, declaredPaths).root();
        return pathOverrides.isEmpty() ? result : remapPaths(result, pathOverrides);
    }

    static List<Change> stagedChanges(Graph<?> graph) {
        return graph instanceof GraphView<?> view ? List.copyOf(view.state().stagedChanges().values()) : List.of();
    }

    static List<Graph<?>> refreshStaged(Graph<?> graph) {
        return stagedChanges(graph).stream().map(Graphs::refreshStaged).toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Graph<?> refreshStaged(Change change) {
        Graph current = Fluxzero.loadGraph(change.modelId(), change.modelType());
        return current.update(value -> change.replay().apply(
                ImmutableEntity.builder().id(change.modelId()).type((Class) change.modelType()).value(value).build())
                .get());
    }

    @FunctionalInterface
    interface StagedReplay {
        Entity<?> apply(Entity<?> current);
    }

    /** Returns a lazy view whose values are transformed independently on first access. */
    public static <T> Graph<T> mapValues(Graph<T> graph, Function<? super Graph<?>, ?> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        GraphView<T> source = adapt(graph);
        return source.context().mapValues(mapper).view(source.node());
    }

    /** Returns an immutable graph view with remapped direct relationship paths. */
    public static <T> Graph<T> remapPaths(Graph<T> graph, Map<String, String> pathOverrides) {
        Objects.requireNonNull(pathOverrides, "pathOverrides");
        if (pathOverrides.isEmpty()) {
            return graph;
        }
        Map<String, String> overrides = Map.copyOf(pathOverrides);
        GraphView<T> source = adapt(graph);
        return source.context().remapPaths(
                path -> path == null ? null : overrides.getOrDefault(path, path), overrides).view(source.node());
    }

    /** Returns an immutable graph view carrying response-wide typed context. */
    public static <T> Graph<T> withContext(Graph<T> graph, Collection<?> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return graph;
        }
        GraphView<T> source = adapt(graph);
        return source.context().withContext(values).view(source.node());
    }

    /** Returns a graph view containing matching branches and the ancestors required to reach them. */
    public static <T> Graph<T> filterBranches(Graph<T> graph, Predicate<? super Graph<?>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        GraphView<T> source = adapt(graph);
        if (!source.state().complete()) {
            return filterBranches(cast(source.expanded()), predicate);
        }
        Set<GraphState.Node> retained = identitySet();
        Set<GraphState.Node> inside = identitySet();
        source.stream().map(Graphs::asView).forEach(view -> {
            boolean matches = view.isPresent() && predicate.test(view);
            boolean inBranch = matches || view.node().parent() != null && inside.contains(view.node().parent());
            if (inBranch) {
                inside.add(view.node());
                retained.add(view.node());
            }
            if (matches) {
                for (GraphState.Node parent = view.node().parent(); parent != null; parent = parent.parent()) {
                    retained.add(parent);
                }
            }
        });
        return source.context().retain(retained, predicate).view(source.node());
    }

    /** Returns a lazy immutable view containing only selected serialized relationship paths. */
    public static <T> Graph<T> selectPaths(Graph<T> graph, Collection<String> paths) {
        Objects.requireNonNull(paths, "paths");
        if (paths.isEmpty()) {
            return graph;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            String value = Objects.requireNonNull(path, "Graph path").trim();
            if (value.isEmpty() || !value.equals(path) || value.startsWith("/") || value.endsWith("/")
                || value.contains("//")) {
                throw new IllegalArgumentException(
                        "Graph paths must be non-empty relative slash-separated paths: " + path);
            }
            normalized.add(value);
        }
        GraphView<T> source = adapt(graph);
        if (!source.state().complete()) {
            return selectPaths(cast(source.expanded()), normalized);
        }
        return source.context().select(Set.copyOf(normalized)).view(source.node());
    }

    /** Returns a graph-change view with an explicitly pinned preceding graph. */
    static <T> Graph<T> withPrevious(Graph<T> current, Graph<T> previous) {
        GraphView<T> source = adapt(current);
        return source.context().withPrevious(previous).view(source.node());
    }

    @SuppressWarnings("unchecked")
    private static <T> GraphView<T> adapt(Graph<T> graph) {
        Objects.requireNonNull(graph, "graph");
        return graph instanceof GraphView<?> view
                ? (GraphView<T>) view : GraphState.external(graph).rootView();
    }

    private static GraphView<?> asView(Graph<?> graph) {
        return (GraphView<?>) graph;
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
    static <T> Graph<T> cast(Graph<?> graph) {
        return (Graph<T>) graph;
    }
}

/** One immutable placement/index state shared by every graph source and view. */
final class GraphState {
    final long stateIndex;
    final ModelRepository repository;
    private final boolean complete;
    final boolean historical;
    private final boolean exactBoundary;
    private final ModelReadBoundary boundary;
    final Map<String, Entity<?>> knownModels;
    final List<ModelGraphEdge> edges;
    private final Map<String, Change> stagedChanges;
    private final Map<String, List<Node>> byId;
    private final Map<String, Node> detachedById;
    private final Map<Class<?>, List<String>> declaredPaths;
    private final Node root;
    private final Identity identity;
    private final NodeResolver nodeResolver;
    private final Map<String, Graph<?>> expansions = new ConcurrentHashMap<>();
    private final ViewContext canonical;

    private GraphState(
            long stateIndex, ModelRepository repository, boolean complete, boolean historical, boolean exactBoundary,
            ModelReadBoundary boundary, Map<String, Entity<?>> knownModels, List<ModelGraphEdge> edges,
            Map<String, Change> stagedChanges, List<Node> placements,
            Map<Class<?>, List<String>> declaredPaths, Node root, Identity identity) {
        this.stateIndex = stateIndex;
        this.repository = repository;
        this.complete = complete;
        this.historical = historical;
        this.exactBoundary = exactBoundary;
        this.boundary = boundary;
        this.knownModels = Map.copyOf(knownModels);
        this.edges = List.copyOf(edges);
        this.stagedChanges = stagedChanges.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stagedChanges));
        this.declaredPaths = Map.copyOf(declaredPaths);
        this.root = root;
        this.identity = identity;
        this.nodeResolver = new NodeResolver(repository, stateIndex);
        Set<NodeData> bound = Collections.newSetFromMap(new IdentityHashMap<>());
        placements.stream().map(Node::data).filter(bound::add).forEach(data -> data.bind(nodeResolver));
        LinkedHashMap<String, List<Node>> indexed = new LinkedHashMap<>();
        LinkedHashMap<String, Node> detached = new LinkedHashMap<>();
        placements.forEach(node -> {
            indexed.computeIfAbsent(node.data().indexId(), ignored -> new ArrayList<>()).add(node);
            if (node.detached()) {
                detached.putIfAbsent(node.data().indexId(), node);
            }
        });
        indexed.replaceAll((ignored, nodes) -> List.copyOf(nodes));
        this.byId = Collections.unmodifiableMap(indexed);
        this.detachedById = Collections.unmodifiableMap(detached);
        this.canonical = ViewContext.canonical(this);
    }

    static <T> GraphState entity(
            Entity<T> root, long stateIndex, ModelRepository repository, Map<String, Entity<?>> models,
            boolean historical, boolean exactBoundary, ModelReadBoundary boundary,
            Map<String, Change> changes) {
        LinkedHashMap<String, NodeData> data = entityData(models);
        data.putIfAbsent(root.id().toString(), NodeData.entity(root));
        Node rootNode = new Node(data.get(root.id().toString()), null, null, true);
        List<Node> placements = new ArrayList<>();
        placements.add(rootNode);
        data.forEach((modelId, value) -> {
            if (!modelId.equals(root.id().toString())) {
                placements.add(new Node(value, null, null, true));
            }
        });
        return indexed(stateIndex, repository, false, historical, exactBoundary, boundary, models, List.of(), changes,
                       placements, Map.of(), rootNode,
                       new Identity(root.id(), root.id().toString(), true, root.type(), false));
    }

    static GraphState identity(
            Object requestedId, String repositoryId, boolean exact, Class<?> modelType, ModelRepository repository) {
        Identity identity = new Identity(requestedId, repositoryId, exact, modelType, true);
        NodeData data = NodeData.identity(
                repositoryId, modelType, requestedId, exact,
                !exact && EntityMetadata.of(modelType).hasAliases());
        Node root = new Node(data, null, null, true);
        return indexed(-1L, repository, false, false, false, ModelReadBoundary.current(), Map.of(), List.of(), Map.of(),
                       List.of(root), Map.of(), root, identity);
    }

    static GraphState composed(
            String rootId, long stateIndex, Map<String, Entity<?>> models, List<ModelGraphEdge> edges,
            ModelRepository repository, boolean historical, ModelReadBoundary boundary,
            Map<String, Change> changes) {
        LinkedHashMap<String, NodeData> data = entityData(models);
        LinkedHashMap<String, List<ModelGraphEdge>> byParent = new LinkedHashMap<>();
        edges.forEach(edge -> byParent.computeIfAbsent(edge.getParentId(), ignored -> new ArrayList<>()).add(edge));
        List<Node> placements = new ArrayList<>();
        Node root = build(rootId, null, null, data, byParent, new LinkedHashSet<>(), placements);
        addDetached(data, placements);
        return indexed(stateIndex, repository, true, historical, true, boundary, models, edges,
                       changes, placements, Map.of(), root, null);
    }

    static GraphState materialized(
            List<Graphs.MaterializedNode> specifications, Class<?> rootType, long stateIndex, Long previousStateIndex,
            ModelRepository repository, Map<Class<?>, List<String>> declaredPaths) {
        if (specifications.isEmpty() || specifications.getFirst().parent() != -1) {
            throw new IllegalArgumentException("Materialized graph must start with one root placement");
        }
        List<Node> placements = new ArrayList<>(specifications.size());
        for (int index = 0; index < specifications.size(); index++) {
            Graphs.MaterializedNode specification = specifications.get(index);
            if (specification.parent() >= index) {
                throw new IllegalArgumentException(
                        "Invalid parent placement %d for model graph node %s"
                                .formatted(specification.parent(), specification.id()));
            }
            NodeData data = NodeData.materialized(
                    specification.id(), specification.type(), specification.value());
            Node parent = specification.parent() < 0 ? null : placements.get(specification.parent());
            Node node = new Node(data, parent, specification.relationshipPath(), false);
            if (parent != null) {
                parent.add(node);
            }
            placements.add(node);
        }
        placements.forEach(Node::freeze);
        Node root = placements.getFirst();
        if (!rootType.isAssignableFrom(root.data().type())) {
            throw new IllegalArgumentException(
                    "Materialized graph contains root type %s instead of %s"
                            .formatted(root.data().type().getName(), rootType.getName()));
        }
        GraphState state = indexed(stateIndex, repository, true, true, true, ModelReadBoundary.state(stateIndex, false), Map.of(),
                                   List.of(), Map.of(), placements, declaredPaths, root, null);
        state.root.data().previousStateIndex = previousStateIndex;
        return state;
    }

    static GraphState external(Graph<?> graph) {
        List<Graph<?>> graphs = graph.stream().toList();
        IdentityHashMap<Graph<?>, Node> nodes = new IdentityHashMap<>();
        List<Node> placements = new ArrayList<>(graphs.size());
        for (Graph<?> source : graphs) {
            Node parent = source == graph ? null : source.parent().map(nodes::get).orElse(null);
            Node node = new Node(NodeData.external(source), parent, source.relationshipPath(), false);
            if (parent != null) {
                parent.add(node);
            }
            nodes.put(source, node);
            placements.add(node);
        }
        placements.forEach(Node::freeze);
        return indexed(graph.stateIndex(), null, true, true, true, ModelReadBoundary.state(graph.stateIndex(), false), Map.of(),
                       List.of(), Map.of(), placements, Map.of(), placements.getFirst(), null);
    }

    private static GraphState indexed(
            long stateIndex, ModelRepository repository, boolean complete, boolean historical, boolean exactBoundary,
            ModelReadBoundary boundary, Map<String, Entity<?>> models, List<ModelGraphEdge> edges,
            Map<String, Change> changes, List<Node> placements,
            Map<Class<?>, List<String>> declaredPaths, Node root, Identity identity) {
        return new GraphState(stateIndex, repository, complete, historical, exactBoundary, boundary, models, edges,
                              changes, placements, declaredPaths, root, identity);
    }

    private static LinkedHashMap<String, NodeData> entityData(Map<String, Entity<?>> models) {
        LinkedHashMap<String, NodeData> result = new LinkedHashMap<>();
        models.forEach((id, entity) -> result.put(id, NodeData.entity(entity)));
        return result;
    }

    private static void addDetached(Map<String, NodeData> data, List<Node> placements) {
        data.values().forEach(value -> placements.add(new Node(value, null, null, true)));
    }

    private static Node build(
            String modelId, Node parent, String path, Map<String, NodeData> data,
            Map<String, List<ModelGraphEdge>> byParent, Set<String> visiting, List<Node> placements) {
        NodeData nodeData = data.get(modelId);
        if (nodeData == null) {
            throw new IllegalArgumentException("Graph contains edge to unloaded model " + modelId);
        }
        if (!visiting.add(modelId)) {
            throw new IllegalArgumentException("Graph contains a cycle through " + modelId);
        }
        Node result = new Node(nodeData, parent, path, false);
        placements.add(result);
        for (ModelGraphEdge edge : byParent.getOrDefault(modelId, List.of())) {
            result.add(build(edge.getChildId(), result, edge.getPath(), data, byParent, visiting, placements));
        }
        visiting.remove(modelId);
        result.freeze();
        return result;
    }

    static List<Graphs.AncestorPlacement> ancestors(
            Collection<String> roots, List<ModelGraphEdge> edges,
            int maxDepth, int maxModels) {
        LinkedHashMap<String, List<ModelGraphEdge>> parents = new LinkedHashMap<>();
        edges.forEach(edge -> parents.computeIfAbsent(
                edge.getChildId(), ignored -> new ArrayList<>()).add(edge));
        LinkedHashSet<String> visited = new LinkedHashSet<>(roots);
        LinkedHashMap<String, Integer> ancestors = new LinkedHashMap<>();
        LinkedHashMap<String, List<ModelGraphEdge>> incoming = new LinkedHashMap<>();
        List<String> frontier = List.copyOf(roots);
        for (int depth = 0; !frontier.isEmpty(); depth++) {
            if (maxDepth >= 0 && depth >= maxDepth) {
                if (frontier.stream().anyMatch(id -> !parents.getOrDefault(id, List.of()).isEmpty())) {
                    throw new IllegalStateException(
                            "Model ancestor graph exceeds maximum depth " + maxDepth);
                }
                break;
            }
            List<String> next = new ArrayList<>();
            for (String child : frontier) {
                for (ModelGraphEdge edge : parents.getOrDefault(child, List.of())) {
                    String parent = edge.getParentId();
                    incoming.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(edge);
                    ancestors.putIfAbsent(parent, depth + 1);
                    if (visited.add(parent)) {
                        if (maxModels >= 0 && visited.size() > maxModels) {
                            throw new IllegalStateException(
                                    "Model ancestor graph exceeds maxModels " + maxModels);
                        }
                        next.add(parent);
                    }
                }
            }
            frontier = next;
        }
        assertAcyclic(roots, parents, new LinkedHashSet<>(), new LinkedHashSet<>());
        return ancestors.entrySet().stream()
                .map(entry -> new Graphs.AncestorPlacement(
                        entry.getKey(), entry.getValue(),
                        List.copyOf(incoming.getOrDefault(entry.getKey(), List.of()))))
                .toList();
    }

    private static void assertAcyclic(
            Collection<String> roots,
            Map<String, List<ModelGraphEdge>> parents,
            Set<String> visiting,
            Set<String> complete) {
        for (String modelId : roots) {
            if (complete.contains(modelId)) {
                continue;
            }
            if (!visiting.add(modelId)) {
                throw new EventSourcingException(
                        "Model ancestor graph contains a cycle through " + modelId);
            }
            assertAcyclic(
                    parents.getOrDefault(modelId, List.of()).stream()
                            .map(ModelGraphEdge::getParentId).toList(),
                    parents, visiting, complete);
            visiting.remove(modelId);
            complete.add(modelId);
        }
    }

    @SuppressWarnings("unchecked")
    <T> Graph<T> root() {
        return (Graph<T>) canonical.view(root);
    }

    @SuppressWarnings("unchecked")
    <T> GraphView<T> rootView() {
        return (GraphView<T>) canonical.view(root);
    }

    Graph<?> expand(Node node) {
        if (complete) {
            return canonical.view(node);
        }
        return expansions.computeIfAbsent(node.data().id(), ignored -> {
            NodeData data = node.data();
            data.entity();
            Graph<?> loaded = boundary.loadGraph(repository, data.id(), data.type());
            if (!(loaded instanceof GraphView<?> graph) || !graph.state().complete() || knownModels.isEmpty()) {
                return loaded;
            }
            LinkedHashMap<String, Entity<?>> models = new LinkedHashMap<>(graph.state().knownModels);
            models.putAll(knownModels);
            LinkedHashSet<ModelGraphEdge> mergedEdges = new LinkedHashSet<>(graph.state().edges);
            mergedEdges.removeIf(edge -> knownModels.containsKey(edge.getChildId()));
            knownModels.forEach((modelId, known) -> addParentEdges(modelId, known, mergedEdges));
            return Graphs.compose(
                    data.id(), stateIndex, models, List.copyOf(mergedEdges),
                    repository, historical, boundary);
        });
    }

    private static void addParentEdges(String modelId, Entity<?> entity, Collection<ModelGraphEdge> edges) {
        Object value = entity.get();
        if (value == null) {
            return;
        }
        for (EntityMetadata.ParentReference parent : EntityMetadata.of(entity.type()).parentReferences()) {
            Object parentId = parent.read(value);
            if (parentId != null) {
                Class<?> parentType = parent.parentModelType(parentId);
                edges.add(new ModelGraphEdge(
                        modelId, parent.repositoryId(parentId), parentType == null ? null : parentType.getName(),
                        parent.path().isEmpty() ? null : parent.path(), -1L, null));
            }
        }
    }

    List<Graph<?>> directParents(Node node, ViewContext context) {
        Object value = node.data().value();
        if (value == null) {
            return List.of();
        }
        LinkedHashMap<String, Graph<?>> result = new LinkedHashMap<>();
        for (EntityMetadata.ParentReference reference : EntityMetadata.of(node.data().type()).parentReferences()) {
            Object parentId = reference.read(value);
            Class<?> parentType = parentId == null ? null : reference.parentModelType(parentId);
            if (parentType == null) {
                continue;
            }
            String repositoryId = reference.repositoryId(parentId);
            Node internal = byId.getOrDefault(repositoryId, List.of()).stream()
                    .filter(candidate -> !candidate.detached() && parentType.isAssignableFrom(candidate.data().type()))
                    .findFirst().orElse(detachedById.get(repositoryId));
            if (internal != null && parentType.isAssignableFrom(internal.data().type())) {
                result.putIfAbsent(repositoryId, context.view(internal));
                continue;
            }
            if (node.data().hasDurableProjection()) {
                Graph<?> durable = node.data().durable();
                if (durable != null) {
                    durable.parents().stream()
                            .filter(parent -> parentType.isAssignableFrom(parent.type()))
                            .filter(parent -> repositoryId.equals(parent.id().toString()))
                            .map(context::decorate)
                            .forEach(parent -> result.putIfAbsent(repositoryId, parent));
                }
                continue;
            }
            if (repository == null) {
                continue;
            }
            Graph<?> parent = historical || exactBoundary
                    ? boundary.loadGraph(repository, repositoryId, parentType)
                    : Graphs.lazy(repository.load(parentId, parentType), stateIndex, repository);
            if (parent.isPresent()) {
                result.putIfAbsent(repositoryId, context.decorate(parent));
            }
        }
        return List.copyOf(result.values());
    }

    <T> Graph<T> replace(Entity<T> entity, Graphs.StagedReplay replay) {
        LinkedHashMap<String, Entity<?>> updated = new LinkedHashMap<>(knownModels);
        String modelId = entity.id().toString();
        updated.put(modelId, entity);
        LinkedHashMap<String, Change> changes = new LinkedHashMap<>(stagedChanges);
        Long expectedStateIndex = entity instanceof ModelRoot<?> root && root.stateIndex() >= 0L
                ? root.stateIndex() : stateIndex >= 0L ? stateIndex : null;
        Change addition = Change.staged(
                modelId, entity.type(), expectedStateIndex, entity.get(), replay);
        changes.merge(modelId, addition, Change::then);
        return GraphState.entity(entity, stateIndex, repository, updated, historical, exactBoundary, boundary, changes)
                .root();
    }

    <T> Graph<T> replaceUnstaged(Entity<T> entity) {
        LinkedHashMap<String, Entity<?>> updated = new LinkedHashMap<>(knownModels);
        updated.put(entity.id().toString(), entity);
        return GraphState.entity(entity, stateIndex, repository, updated, historical, exactBoundary, boundary, Map.of())
                .root();
    }

    long stateIndex() {
        return stateIndex;
    }

    boolean complete() {
        return complete;
    }

    boolean historical() {
        return historical;
    }

    boolean exactBoundary() {
        return exactBoundary;
    }

    ModelRepository repository() {
        return repository;
    }

    ModelReadBoundary boundary() {
        return boundary;
    }

    Node rootNode() {
        return root;
    }

    Identity identity() {
        return identity;
    }

    Map<String, Change> stagedChanges() {
        return stagedChanges;
    }

    List<String> declaredPaths(Class<?> type) {
        return declaredPaths.getOrDefault(type, List.of());
    }

    record Identity(Object requestedId, String repositoryId, boolean exact, Class<?> type, boolean detachedLookup) {
    }

    static final class Node {
        private final NodeData data;
        private final Node parent;
        private final String path;
        private final boolean detached;
        private List<Node> children = new ArrayList<>();

        private Node(NodeData data, Node parent, String path, boolean detached) {
            this.data = data;
            this.parent = parent;
            this.path = path;
            this.detached = detached;
        }

        private void add(Node child) {
            children.add(child);
        }

        private void freeze() {
            children = List.copyOf(children);
        }

        NodeData data() {
            return data;
        }

        Node parent() {
            return parent;
        }

        String path() {
            return path;
        }

        boolean detached() {
            return detached;
        }

        List<Node> children() {
            return children;
        }
    }

    static final class NodeData {
        private final String id;
        private final Class<?> type;
        private final Resolution resolution;
        private final boolean resolveId;
        private NodeResolver resolver;
        private volatile boolean entityResolved;
        private Entity<?> entity;
        private volatile boolean valueResolved;
        private Object value;
        private volatile Graph<?> durable;
        private Long previousStateIndex;

        private NodeData(
                String id, Class<?> type, Resolution resolution, boolean resolveId) {
            this.id = id;
            this.type = type;
            this.resolution = resolution;
            this.resolveId = resolveId;
        }

        static NodeData entity(Entity<?> entity) {
            NodeData result = new NodeData(
                    entity.id().toString(), entity.type(), new Loaded(entity), false);
            result.entity = entity;
            result.entityResolved = true;
            return result;
        }

        static NodeData identity(
                String id, Class<?> type, Object requestedId, boolean exact, boolean resolveId) {
            return new NodeData(id, type, new LazyIdentity(requestedId, exact), resolveId);
        }

        static NodeData materialized(
                String id, Class<?> type, Supplier<?> value) {
            return new NodeData(id, type, new Materialized(value), false);
        }

        static NodeData external(Graph<?> graph) {
            return new NodeData(graph.id().toString(), graph.type(), new External(graph), false);
        }

        void bind(NodeResolver resolver) {
            if (this.resolver != null && this.resolver != resolver) {
                throw new IllegalStateException("Graph node belongs to multiple states");
            }
            this.resolver = resolver;
        }

        String id() {
            if (resolveId) {
                entity();
            }
            Entity<?> resolved = entityResolved ? entity : null;
            return resolved != null && resolved.id() != null ? resolved.id().toString() : id;
        }

        String indexId() {
            return id;
        }

        Class<?> type() {
            return type;
        }

        Entity<?> entity() {
            return resolver.entity(this);
        }

        Object value() {
            return resolver.value(this);
        }

        Graph<?> durable() {
            return resolver.durable(this);
        }

        boolean hasDurableProjection() {
            return resolution instanceof Materialized || resolution instanceof External;
        }

        Long previousStateIndex() {
            return previousStateIndex;
        }

        private interface Resolution {
        }

        private record Loaded(Entity<?> entity) implements Resolution {
        }

        private record LazyIdentity(Object requestedId, boolean exact) implements Resolution {
        }

        private record Materialized(Supplier<?> value) implements Resolution {
        }

        private record External(Graph<?> graph) implements Resolution {
        }
    }

    /** One lazy resolution lifecycle shared by every typed node view on this state. */
    private static final class NodeResolver {
        private final ModelRepository repository;
        private final long stateIndex;

        private NodeResolver(ModelRepository repository, long stateIndex) {
            this.repository = repository;
            this.stateIndex = stateIndex;
        }

        private Entity<?> entity(NodeData node) {
            if (node.resolution instanceof NodeData.Materialized
                || node.resolution instanceof NodeData.External) {
                return null;
            }
            if (!node.entityResolved) {
                synchronized (node) {
                    if (!node.entityResolved) {
                        NodeData.LazyIdentity identity = (NodeData.LazyIdentity) node.resolution;
                        node.entity = Objects.requireNonNull(
                                identity.exact()
                                        ? repository.load(node.id, node.type)
                                        : repository.load(identity.requestedId(), node.type),
                                "Resolved graph entity");
                        node.entityResolved = true;
                    }
                }
            }
            return node.entity;
        }

        private Object value(NodeData node) {
            Entity<?> entity = entity(node);
            if (entity != null) {
                return entity.get();
            }
            if (!node.valueResolved) {
                synchronized (node) {
                    if (!node.valueResolved) {
                        node.value = node.resolution instanceof NodeData.External external
                                ? external.graph().get()
                                : ((NodeData.Materialized) node.resolution).value().get();
                        node.valueResolved = true;
                    }
                }
            }
            return node.value;
        }

        private Graph<?> durable(NodeData node) {
            Graph<?> result = node.durable;
            if (result == null) {
                if (node.resolution instanceof NodeData.Loaded
                    || node.resolution instanceof NodeData.LazyIdentity
                    || repository == null && !(node.resolution instanceof NodeData.External)) {
                    throw new IllegalStateException("Graph history and updates require a model repository");
                }
                synchronized (node) {
                    result = node.durable;
                    if (result == null) {
                        if (node.resolution instanceof NodeData.External external) {
                            result = external.graph();
                        } else {
                            result = stateIndex >= 0L
                                    ? repository.loadGraphAt(
                                            node.id, node.type, stateIndex, Graph.Options.DEFAULT)
                                    : repository.loadGraph(
                                            node.id, node.type, Graph.Options.DEFAULT);
                        }
                        node.durable = result;
                    }
                }
            }
            return result;
        }
    }

    static final class ViewContext {
        private final GraphState state;
        private final Function<Node, Object> value;
        private final Function<String, String> path;
        private final List<?> values;
        private final ViewContext contextFallback;
        private final Set<Node> retained;
        private final Map<Node, Set<String>> selection;
        private final boolean hideEmpty;
        private final Graph<?> previousRoot;
        private final UnaryOperator<Graph<?>> decorator;
        private final Map<Node, GraphView<?>> views = new IdentityHashMap<>();

        private ViewContext(
                GraphState state, Function<Node, Object> value, Function<String, String> path, List<?> values,
                ViewContext contextFallback, Set<Node> retained, Map<Node, Set<String>> selection,
                boolean hideEmpty, Graph<?> previousRoot, UnaryOperator<Graph<?>> decorator) {
            this.state = state;
            this.value = value;
            this.path = path;
            this.values = values;
            this.contextFallback = contextFallback;
            this.retained = retained;
            this.selection = selection;
            this.hideEmpty = hideEmpty;
            this.previousRoot = previousRoot;
            this.decorator = decorator;
        }

        static ViewContext canonical(GraphState state) {
            return new ViewContext(state, node -> node.data().value(), Function.identity(), List.of(), null, null, null,
                                   false, null, UnaryOperator.identity());
        }

        synchronized <T> GraphView<T> view(Node node) {
            @SuppressWarnings("unchecked") GraphView<T> result = (GraphView<T>) views.computeIfAbsent(
                    node, ignored -> new GraphView<>(state, node, this));
            return result;
        }

        Object value(Node node) {
            return value.apply(node);
        }

        String path(Node node) {
            return path.apply(node.path());
        }

        boolean visible(Node node) {
            return retained == null || retained.contains(node);
        }

        List<Node> children(Node node) {
            if (selection == null) {
                return node.children().stream().filter(this::visible).toList();
            }
            Set<String> paths = selection.getOrDefault(node, Set.of());
            return node.children().stream().filter(child -> visible(child) && includes(paths, path(child))).toList();
        }

        List<String> childPaths(Node node) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            state.declaredPaths(node.data().type()).stream().map(path).forEach(result::add);
            children(node).stream().map(this::path).filter(value -> value != null && !value.isBlank()).forEach(result::add);
            if (selection != null) {
                Set<String> selected = selection.getOrDefault(node, Set.of());
                result.removeIf(candidate -> !includes(selected, candidate));
            }
            return List.copyOf(result);
        }

        <C> Optional<C> context(Class<C> type) {
            List<C> matches = values.stream().filter(Objects::nonNull).filter(type::isInstance).map(type::cast).toList();
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Graph context contains multiple values assignable to %s".formatted(type.getName()));
            }
            return matches.stream().findFirst().or(() -> contextFallback == null
                    ? Optional.empty() : contextFallback.context(type));
        }

        Graph<?> decorate(Graph<?> graph) {
            return decorator.apply(graph);
        }

        ViewContext mapValues(Function<? super Graph<?>, ?> mapper) {
            ViewContext source = this;
            return new ViewContext(state, node -> mapper.apply(source.view(node)), path, values, contextFallback,
                                   retained, selection, hideEmpty, previousRoot,
                                   graph -> Graphs.mapValues(Graphs.cast(decorator.apply(graph)), mapper));
        }

        ViewContext remapPaths(UnaryOperator<String> mapper, Map<String, String> overrides) {
            Function<String, String> previous = path;
            return new ViewContext(state, value, raw -> mapper.apply(previous.apply(raw)), values, contextFallback,
                                   retained, selection, hideEmpty, previousRoot,
                                   graph -> Graphs.remapPaths(Graphs.cast(decorator.apply(graph)), overrides));
        }

        ViewContext withContext(Collection<?> added) {
            List<?> stable = List.copyOf(added);
            return new ViewContext(state, value, path, stable, this, retained, selection, hideEmpty, previousRoot,
                                   graph -> Graphs.withContext(Graphs.cast(decorator.apply(graph)), stable));
        }

        ViewContext retain(Set<Node> retained, Predicate<? super Graph<?>> predicate) {
            ViewContext source = this;
            return new ViewContext(state, node -> retained.contains(node) ? source.value(node) : null, path, values,
                                   contextFallback, retained, selection, true, previousRoot,
                                   graph -> Graphs.filterBranches(Graphs.cast(decorator.apply(graph)), predicate));
        }

        ViewContext select(Set<String> selected) {
            Map<Node, Set<String>> byNode = new IdentityHashMap<>();
            collectSelection(state.root, selected, byNode);
            Set<Node> visible = Collections.newSetFromMap(new IdentityHashMap<>());
            visible.addAll(byNode.keySet());
            return new ViewContext(state, value, path, values, contextFallback, visible, byNode, hideEmpty, previousRoot,
                                   graph -> Graphs.selectPaths(Graphs.cast(decorator.apply(graph)), selected));
        }

        private void collectSelection(Node node, Set<String> selected, Map<Node, Set<String>> byNode) {
            byNode.put(node, selected);
            for (Node child : node.children()) {
                String childPath = path(child);
                if (includes(selected, childPath)) {
                    collectSelection(child, below(selected, childPath), byNode);
                }
            }
        }

        ViewContext withPrevious(Graph<?> previous) {
            return new ViewContext(state, value, path, values, contextFallback, retained, selection, hideEmpty, previous,
                                   UnaryOperator.identity());
        }

        Graph<?> previous(Node node) {
            if (previousRoot == null) {
                return null;
            }
            return node == state.root ? previousRoot
                    : previousRoot.find(node.data().id(), node.data().type()).orElse(null);
        }

        boolean selected() {
            return selection != null;
        }

        boolean hideEmpty() {
            return hideEmpty;
        }

        private static boolean includes(Set<String> selected, String path) {
            if (path == null) {
                return false;
            }
            String prefix = path + "/";
            return selected.stream().anyMatch(candidate -> candidate.equals(path) || candidate.startsWith(prefix));
        }

        private static Set<String> below(Set<String> selected, String path) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String prefix = path + "/";
            selected.stream().filter(candidate -> candidate.startsWith(prefix))
                    .map(candidate -> candidate.substring(prefix.length())).forEach(result::add);
            return Set.copyOf(result);
        }
    }
}

/** The only concrete Graph implementation: a typed placement plus immutable view options on one state. */
final class GraphView<T> implements Graph<T> {
    private final GraphState state;
    private final GraphState.Node node;
    private final GraphState.ViewContext context;
    private volatile List<Graph<?>> directParents;
    private volatile boolean valueResolved;
    private T value;

    GraphView(GraphState state, GraphState.Node node, GraphState.ViewContext context) {
        this.state = state;
        this.node = node;
        this.context = context;
    }

    GraphState state() {
        return state;
    }

    GraphState.Node node() {
        return node;
    }

    GraphState.ViewContext context() {
        return context;
    }

    Graph<?> expanded() {
        return context.decorate(state.expand(node));
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        if (!valueResolved) {
            synchronized (this) {
                if (!valueResolved) {
                    value = (T) context.value(node);
                    valueResolved = true;
                }
            }
        }
        return value;
    }

    @Override
    public Object id() {
        return node.data().id();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<T> type() {
        return (Class<T>) node.data().type();
    }

    @Override
    public Collection<?> aliases() {
        Entity<?> entity = node.data().entity();
        Object value = node.data().value();
        return entity != null ? entity.aliases()
                : value == null ? List.of() : EntityMetadata.of(type()).aliases(value);
    }

    @Override
    public <C> Optional<C> context(Class<C> contextType) {
        return context.context(Objects.requireNonNull(contextType, "contextType"));
    }

    @Override
    public String relationshipPath() {
        return context.path(node);
    }

    @Override
    public long stateIndex() {
        return state.stateIndex();
    }

    @Override
    public long revisionStateIndex() {
        Entity<?> entity = node.data().entity();
        return entity instanceof ModelRoot<?> root ? root.stateIndex()
                : entity != null ? stateIndex() : node.data().durable().revisionStateIndex();
    }

    @Override
    public String lastEventId() {
        Entity<?> entity = node.data().entity();
        return entity == null ? node.data().durable().lastEventId() : entity.lastEventId();
    }

    @Override
    public Long lastEventIndex() {
        Entity<?> entity = node.data().entity();
        return entity == null ? node.data().durable().lastEventIndex() : entity.lastEventIndex();
    }

    @Override
    public long sequenceNumber() {
        Entity<?> entity = node.data().entity();
        return entity == null ? node.data().durable().sequenceNumber() : entity.sequenceNumber();
    }

    @Override
    public Instant timestamp() {
        Entity<?> entity = node.data().entity();
        return entity == null ? node.data().durable().timestamp() : entity.timestamp();
    }

    @Override
    public Graph<?> root() {
        if (node.parent() != null) {
            GraphState.Node result = node;
            while (result.parent() != null) {
                result = result.parent();
            }
            return context.view(result);
        }
        return parent().map(Graph::root).orElse(this);
    }

    @Override
    public Optional<Graph<?>> parent() {
        if (node.parent() != null) {
            return Optional.of(context.view(node.parent()));
        }
        if (context.selected()) {
            return Optional.empty();
        }
        List<Graph<?>> parents = directParents();
        if (parents.size() > 1) {
            throw new IllegalStateException("Model %s has multiple parents; request a typed parent".formatted(id()));
        }
        return parents.stream().findFirst();
    }

    @Override
    public List<Graph<?>> parents() {
        LinkedHashMap<String, Graph<?>> result = new LinkedHashMap<>();
        if (node.parent() != null) {
            Graph<?> placed = context.view(node.parent());
            result.put(placed.type().getName() + ':' + placed.id(), placed);
        }
        if (!context.selected()) {
            directParents().forEach(parent -> result.putIfAbsent(parent.type().getName() + ':' + parent.id(), parent));
        }
        return List.copyOf(result.values());
    }

    private List<Graph<?>> directParents() {
        List<Graph<?>> result = directParents;
        if (result == null) {
            synchronized (this) {
                result = directParents;
                if (result == null) {
                    directParents = result = state.directParents(node, context);
                }
            }
        }
        return result;
    }

    @Override
    public <P> Optional<Graph<P>> parent(Class<P> parentType) {
        if (node.parent() != null) {
            Graph<?> placed = context.view(node.parent());
            if (parentType.isAssignableFrom(placed.type())) {
                return Optional.of(Graphs.cast(placed));
            }
        }
        List<Graph<P>> matches = parents().stream().filter(candidate -> parentType.isAssignableFrom(candidate.type()))
                .map(Graphs::<P>cast).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Model %s has multiple parents assignable to %s".formatted(id(), parentType.getName()));
        }
        return matches.stream().findFirst();
    }

    @Override
    public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
        Objects.requireNonNull(ancestorType, "ancestorType");
        GraphState.Node placed = node;
        while (placed != null) {
            if (ancestorType.isAssignableFrom(placed.data().type())) {
                return Optional.of(Graphs.cast(context.view(placed)));
            }
            placed = placed.parent();
        }
        GraphState.Identity identity = state.identity();
        if (identity != null && EntityMetadata.of(type()).isModel()
            && state.repository() instanceof ModelAncestorResolver resolver) {
            Optional<Graph<A>> resolved = resolver.loadAncestorGraph(
                    identity.repositoryId(), identity.type(), ancestorType,
                    state.boundary());
            if (resolved.isPresent()) {
                return resolved.map(graph -> Graphs.cast(context.decorate(graph)));
            }
            node.data().entity();
            String resolvedId = node.data().id();
            if (identity.detachedLookup()) {
                resolved = resolver.loadAncestorGraph(
                        resolvedId, identity.type(), ancestorType,
                        state.boundary());
                if (resolved.isPresent()) {
                    return resolved.map(graph -> Graphs.cast(context.decorate(graph)));
                }
            }
        }
        List<Graph<?>> level = List.of(this);
        Set<String> visited = new LinkedHashSet<>();
        while (!level.isEmpty()) {
            List<Graph<A>> matches = level.stream().filter(candidate -> ancestorType.isAssignableFrom(candidate.type()))
                    .map(Graphs::<A>cast).toList();
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Model %s has multiple ancestors assignable to %s".formatted(id(), ancestorType.getName()));
            }
            if (!matches.isEmpty()) {
                return Optional.of(matches.getFirst());
            }
            List<Graph<?>> next = new ArrayList<>();
            for (Graph<?> candidate : level) {
                String key = candidate.type().getName() + ':' + candidate.id();
                if (visited.add(key)) {
                    next.addAll(candidate.parents());
                }
            }
            level = List.copyOf(next);
        }
        return Optional.empty();
    }

    @Override
    public List<Graph<?>> children() {
        if (!state.complete()) {
            return expanded().children();
        }
        var children = context.children(node).stream().<Graph<?>>map(context::view);
        return (context.hideEmpty() ? children.filter(Graph::isPresent) : children).toList();
    }

    @Override
    public List<String> childPaths() {
        return state.complete() ? context.childPaths(node) : expanded().childPaths();
    }

    @Override
    public <C> List<Graph<C>> children(Class<C> childType) {
        LinkedHashMap<String, List<Graph<C>>> byPath = new LinkedHashMap<>();
        children().stream().filter(child -> childType.isAssignableFrom(child.type())).map(Graphs::<C>cast)
                .forEach(child -> byPath.computeIfAbsent(child.relationshipPath(), ignored -> new ArrayList<>()).add(child));
        if (byPath.size() > 1) {
            throw new IllegalStateException("Model %s has %s children at multiple paths %s; request an explicit path"
                                                    .formatted(id(), childType.getName(), byPath.keySet()));
        }
        return byPath.values().stream().findFirst().map(List::copyOf).orElse(List.of());
    }

    @Override
    public <C> List<Graph<C>> children(String path, Class<C> childType) {
        return children().stream().filter(child -> Objects.equals(path, child.relationshipPath()))
                .filter(child -> childType.isAssignableFrom(child.type())).map(Graphs::<C>cast).toList();
    }

    @Override
    public <D> List<Graph<D>> descendants(Class<D> descendantType) {
        return descendants(null, descendantType);
    }

    @Override
    public <D> List<Graph<D>> descendants(String path, Class<D> descendantType) {
        String selectedPath = normalizePath(path);
        List<Graph<D>> result = new ArrayList<>();
        Deque<PathGraph> remaining = new ArrayDeque<>();
        children().forEach(child -> remaining.addLast(new PathGraph(child, child.relationshipPath())));
        while (!remaining.isEmpty()) {
            PathGraph candidate = remaining.removeFirst();
            if ((selectedPath == null || Objects.equals(selectedPath, candidate.path()))
                && descendantType.isAssignableFrom(candidate.graph().type())) {
                result.add(Graphs.cast(candidate.graph()));
            }
            if (selectedPath == null || candidate.path() != null && selectedPath.startsWith(candidate.path() + '/')) {
                candidate.graph().children().forEach(child -> remaining.addLast(new PathGraph(
                        child, candidate.path() == null || child.relationshipPath() == null ? null
                        : candidate.path() + '/' + child.relationshipPath())));
            }
        }
        return List.copyOf(result);
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String result = path.strip();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty() || result.contains("//")) {
            throw new IllegalArgumentException("Graph descendant path must contain non-empty segments");
        }
        return result;
    }

    private record PathGraph(Graph<?> graph, String path) {
    }

    @Override
    public Graph<T> apply(Object update) {
        return operate(entity -> entity.apply(update), graph -> graph.apply(update), false);
    }

    @Override
    public Graph<T> apply(Object update, Metadata metadata) {
        return operate(entity -> entity.apply(update, metadata), graph -> graph.apply(update, metadata), false);
    }

    @Override
    public Graph<T> apply(DeserializingMessage update) {
        return operate(entity -> entity.apply(update), graph -> graph.apply(update), false);
    }

    @Override
    public Graph<T> apply(Message update) {
        return operate(entity -> entity.apply(update), graph -> graph.apply(update), false);
    }

    @Override
    public Graph<T> apply(Object... updates) {
        Object[] stable = updates.clone();
        return operate(entity -> entity.apply(stable), graph -> graph.apply(stable), false);
    }

    @Override
    public Graph<T> apply(Collection<?> updates) {
        List<?> stable = List.copyOf(updates);
        return operate(entity -> entity.apply(stable), graph -> graph.apply(stable), false);
    }

    @Override
    public Graph<T> update(UnaryOperator<T> update) {
        Objects.requireNonNull(update, "update");
        return operate(entity -> entity.update(update), graph -> graph.update(update), true);
    }

    @SuppressWarnings("unchecked")
    private Graph<T> operate(
            Function<Entity<T>, Entity<T>> entityOperation, Function<Graph<T>, Graph<T>> graphOperation,
            boolean staged) {
        Entity<?> raw = node.data().entity();
        if (raw == null) {
            return Graphs.cast(context.decorate(graphOperation.apply((Graph<T>) node.data().durable())));
        }
        Entity<T> current = (Entity<T>) raw;
        Entity<T> next = entityOperation.apply(current);
        Graph<T> result = staged
                ? state.replace(next, entity -> entityOperation.apply((Entity<T>) entity))
                : state.replaceUnstaged(next);
        return Graphs.cast(context.decorate(result));
    }

    @Override
    public Graph<T> commit() {
        if (!state.stagedChanges().isEmpty() && Fluxzero.getOptionally().isPresent()
            && EntityMetadata.of(type()).isModel()) {
            Fluxzero.assertAndApply(this);
            return Graphs.cast(Fluxzero.loadGraph(id().toString()));
        }
        Entity<?> entity = node.data().entity();
        Graph<T> result = entity == null ? Graphs.cast(node.data().durable().commit())
                : state.replaceUnstaged(castEntity(entity).commit());
        return Graphs.cast(context.decorate(result));
    }

    @Override
    public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
        Entity<?> entity = node.data().entity();
        if (entity == null) {
            node.data().durable().assertLegal(update);
        } else {
            entity.assertLegal(update);
        }
        return this;
    }

    @Override
    public Graph<T> assertAndApply(Object update) {
        return assertAndApply(update, null);
    }

    @Override
    public Graph<T> assertAndApply(Object update, Metadata metadata) {
        if (Fluxzero.getOptionally().isPresent() && EntityMetadata.of(type()).isModel()) {
            return metadata == null ? Fluxzero.assertAndApply(this, update)
                    : Fluxzero.assertAndApply(this, update, metadata);
        }
        Entity<T> entity = castEntity(node.data().entity());
        Graph<T> result;
        if (entity == null) {
            Graph<T> durable = Graphs.cast(node.data().durable());
            result = metadata == null ? durable.assertAndApply(update) : durable.assertAndApply(update, metadata);
        } else {
            result = state.replaceUnstaged(metadata == null
                                                   ? entity.assertAndApply(update)
                                                   : entity.assertAndApply(update, metadata));
        }
        return Graphs.cast(context.decorate(result));
    }

    @Override
    public Graph<T> previous() {
        Graph<?> explicit = context.previous(node);
        if (explicit != null) {
            return Graphs.cast(explicit);
        }
        if (node.data().previousStateIndex() != null && node == state.rootNode()) {
            return Graphs.cast(context.decorate(state.repository().loadGraphAt(
                    id().toString(), type(), node.data().previousStateIndex(), Graph.Options.DEFAULT)));
        }
        Entity<T> entity = castEntity(node.data().entity());
        if (entity == null) {
            Graph<T> previous = Graphs.<T>cast(node.data().durable()).previous();
            return previous == null ? null : Graphs.cast(context.decorate(previous));
        }
        Entity<T> previous = entity.previous();
        if (previous == null) {
            return null;
        }
        long currentStateIndex = entity instanceof ModelRoot<?> root && root.stateIndex() >= -1L
                ? root.stateIndex() : state.stateIndex();
        ModelReadBoundary boundary = state.boundary();
        if (entity instanceof ModelRoot<?> current && previous instanceof ModelRoot<?> preceding
            && current.stateIndex() >= 0L && current.stateIndex() != preceding.stateIndex()) {
            boundary = !boundary.before() && Objects.equals(boundary.stateIndex(), current.stateIndex())
                    ? boundary.asBefore() : ModelReadBoundary.state(current.stateIndex(), false).asBefore();
        }
        Graph<T> result = GraphState.entity(
                previous, currentStateIndex, state.repository(), Map.of(previous.id().toString(), previous),
                state.historical(), true, boundary, Map.of()).root();
        return Graphs.cast(context.decorate(result));
    }

    @Override
    public Graph<T> atStateIndex(long stateIndex) {
        if (stateIndex < -1L) {
            throw new IllegalArgumentException("Graph stateIndex must be at least -1");
        }
        return Graphs.cast(context.decorate(state.repository().loadGraphAt(
                id().toString(), type(), stateIndex, Graph.Options.DEFAULT)));
    }

    @Override
    public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
        Entity<T> entity = castEntity(node.data().entity());
        Optional<Graph<T>> result = entity == null ? Graphs.<T>cast(node.data().durable())
                .playBackToEvent(eventIndex, eventId) : entity.playBackToEvent(eventIndex, eventId)
                .map(previous -> GraphState.entity(
                        previous, previous instanceof ModelRoot<?> root ? root.stateIndex() : state.stateIndex(),
                        state.repository(), Map.of(previous.id().toString(), previous), true, true,
                        ModelReadBoundary.state(state.stateIndex(), false), Map.of()).<T>root());
        return result.map(graph -> Graphs.cast(context.decorate(graph)));
    }

    @Override
    public Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition) {
        Graph<T> result = this;
        while (result != null && !condition.test(result)) {
            result = result.previous();
        }
        return Optional.ofNullable(result);
    }

    @SuppressWarnings("unchecked")
    private Entity<T> castEntity(Entity<?> entity) {
        return (Entity<T>) entity;
    }
}
