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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.repository.ModelAncestorResolver;
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
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Internal construction bridge for repository-backed {@link Graph} implementations.
 * <p>
 * Applications normally obtain graphs through injection or {@link ModelRepository#loadGraph(Id)}.
 */
public final class Graphs {
    private Graphs() {
    }

    /** Creates a lazy one-model graph without loading any relationships. */
    public static <T> Graph<T> lazy(
            Entity<T> entity,
            long stateIndex,
            ModelRepository repository) {
        return lazy(
                entity, stateIndex, repository,
                Map.of(entity.id().toString(), entity),
                false, false);
    }

    /**
     * Creates a detached graph whose source model is loaded only when its value, relationships, history or update
     * operations are requested. A typed ancestor lookup can use {@link ModelAncestorResolver} directly from the
     * supplied identity and therefore need not materialize the source or intermediate parent values.
     */
    public static <T> Graph<T> lazy(
            Object modelId,
            Class<T> modelType,
            ModelRepository repository) {
        return new IdentityGraph<>(modelId, null, modelType, repository);
    }

    /**
     * Creates a detached graph for a parent-scoped model without eagerly loading its source value.
     */
    public static <T> Graph<T> lazy(
            Object parentId,
            Class<?> parentType,
            Object modelId,
            Class<T> modelType,
            ModelRepository repository) {
        String primaryId = ModelMetadata.validate(modelType)
                .repositoryId(modelId, parentId, parentType);
        return new IdentityGraph<>(modelId, primaryId, modelType, repository);
    }

    /** Creates a lazy graph that reuses every model already loaded for the same handler boundary. */
    static <T> Graph<T> lazy(
            Entity<T> entity,
            ModelCommitContext commitContext,
            ModelRepository repository) {
        LinkedHashMap<String, Entity<?>> models = new LinkedHashMap<>();
        commitContext.entries().forEach(entry -> models.put(entry.target().modelId(), entry.entity()));
        return lazy(
                entity, commitContext.readStateIndex(), repository,
                models, false, true);
    }

    private static <T> Graph<T> lazy(
            Entity<T> entity,
            long stateIndex,
            ModelRepository repository,
            Map<String, Entity<?>> models,
            boolean historical,
            boolean exactBoundary) {
        Objects.requireNonNull(entity, "entity");
        Context context = new Context(
                stateIndex,
                Collections.unmodifiableMap(new LinkedHashMap<>(models)),
                List.of(), repository, false, historical,
                exactBoundary,
                Boundary.current(stateIndex));
        Placement root = context.detached(entity.id().toString());
        context.root = root;
        return context.view(root);
    }

    /** Creates a complete graph from one coherent repository reconstruction. */
    public static <T> Graph<T> compose(
            String rootId,
            long stateIndex,
            Map<String, Entity<?>> models,
            List<ModelGraphEdge> edges,
            ModelRepository repository,
            boolean historical) {
        Context context = new Context(
                stateIndex,
                Collections.unmodifiableMap(new LinkedHashMap<>(models)),
                List.copyOf(edges), repository, true, historical,
                true,
                Boundary.state(stateIndex));
        Map<String, List<ModelGraphEdge>> byParent = new LinkedHashMap<>();
        for (ModelGraphEdge edge : edges) {
            byParent.computeIfAbsent(edge.getParentId(), ignored -> new ArrayList<>()).add(edge);
        }
        context.root = build(rootId, null, null, context.models, byParent, new LinkedHashSet<>());
        return context.view(context.root);
    }

    /** Returns a lazy graph view whose model values are transformed independently on first access. */
    public static <T> Graph<T> mapValues(
            Graph<T> graph,
            Function<? super Graph<?>, ?> mapper) {
        return new MappedContext(Objects.requireNonNull(mapper, "mapper"), List.of())
                .view(Objects.requireNonNull(graph, "graph"));
    }

    /** Returns an immutable graph view carrying response-wide typed context. */
    public static <T> Graph<T> withContext(
            Graph<T> graph,
            Collection<?> values) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return graph;
        }
        return new MappedContext(Graph::get, values).view(graph);
    }

    /** Returns a graph view containing matching branches and the ancestors required to reach them. */
    public static <T> Graph<T> filterBranches(
            Graph<T> graph,
            Predicate<? super Graph<?>> predicate) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(predicate, "predicate");
        Set<Graph<?>> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Graph<?>, Boolean> insideMatchedBranch = new IdentityHashMap<>();
        graph.stream().forEach(node -> {
            boolean matches = node.isPresent() && predicate.test(node);
            boolean inside = matches || node.parent()
                    .map(parent -> Boolean.TRUE.equals(insideMatchedBranch.get(parent)))
                    .orElse(false);
            insideMatchedBranch.put(node, inside);
            if (inside) {
                retained.add(node);
            }
            if (matches) {
                Graph<?> ancestor = node;
                while ((ancestor = ancestor.parent().orElse(null)) != null) {
                    retained.add(ancestor);
                }
            }
        });
        return mapValues(graph, node -> retained.contains(node) ? node.get() : null);
    }

    /** Returns a lazy immutable view containing only selected serialized relationship paths. */
    public static <T> Graph<T> selectPaths(
            Graph<T> graph,
            Collection<String> paths) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(paths, "paths");
        if (paths.isEmpty()) {
            return graph;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            String value = Objects.requireNonNull(path, "Graph path").trim();
            if (value.isEmpty() || !value.equals(path)
                || value.startsWith("/") || value.endsWith("/")
                || value.contains("//")) {
                throw new IllegalArgumentException(
                        "Graph paths must be non-empty relative slash-separated paths: " + path);
            }
            normalized.add(value);
        }
        return new SelectedContext(Set.copyOf(normalized)).root(graph);
    }

    /**
     * Returns a graph-change view whose {@link Graph#previous()} graph is pinned explicitly.
     * Model nodes are shared; only the lightweight graph placement views are wrapped.
     */
    static <T> Graph<T> withPrevious(
            Graph<T> current,
            Graph<T> previous) {
        return new ChangeContext(current, previous).root();
    }

    private static Placement build(
            String modelId,
            Placement parent,
            String path,
            Map<String, Entity<?>> models,
            Map<String, List<ModelGraphEdge>> edgesByParent,
            Set<String> visiting) {
        if (!models.containsKey(modelId)) {
            throw new IllegalArgumentException("Graph contains edge to unloaded model " + modelId);
        }
        if (!visiting.add(modelId)) {
            throw new IllegalArgumentException("Graph contains a cycle through " + modelId);
        }
        Placement result = new Placement(modelId, parent, path);
        for (ModelGraphEdge edge : edgesByParent.getOrDefault(modelId, List.of())) {
            Placement child = build(
                    edge.getChildId(), result, edge.getPath(), models, edgesByParent, visiting);
            result.children.computeIfAbsent(edge.getPath(), ignored -> new ArrayList<>()).add(child);
        }
        visiting.remove(modelId);
        result.freeze();
        return result;
    }

    private static final class Context {
        private final long stateIndex;
        private final Map<String, Entity<?>> models;
        private final List<ModelGraphEdge> edges;
        private final ModelRepository repository;
        private final boolean complete;
        private final boolean historical;
        private final boolean exactBoundary;
        private final Boundary boundary;
        private final Map<String, Placement> detachedPlacements = new ConcurrentHashMap<>();
        private final Map<String, Graph<?>> expansions = new ConcurrentHashMap<>();
        private Placement root;

        private Context(
                long stateIndex,
                Map<String, Entity<?>> models,
                List<ModelGraphEdge> edges,
                ModelRepository repository,
                boolean complete,
                boolean historical,
                boolean exactBoundary,
                Boundary boundary) {
            this.stateIndex = stateIndex;
            this.models = models;
            this.edges = edges;
            this.repository = Objects.requireNonNull(repository, "repository");
            this.complete = complete;
            this.historical = historical;
            this.exactBoundary = exactBoundary;
            this.boundary = Objects.requireNonNull(
                    boundary, "boundary");
        }

        private Placement detached(String modelId) {
            return detachedPlacements.computeIfAbsent(modelId, id -> {
                Placement placement = new Placement(id, null, null);
                placement.freeze();
                return placement;
            });
        }

        @SuppressWarnings("unchecked")
        private <T> DefaultGraph<T> view(Placement placement) {
            DefaultGraph<?> known = placement.view;
            if (known == null) {
                synchronized (placement) {
                    known = placement.view;
                    if (known == null) {
                        placement.view = known = new DefaultGraph<>(this, placement);
                    }
                }
            }
            return (DefaultGraph<T>) known;
        }

        private Graph<?> expansion(Placement placement) {
            if (complete) {
                return view(placement);
            }
            return expansions.computeIfAbsent(placement.modelId, modelId -> {
                Entity<?> entity = models.get(modelId);
                if (entity == null) {
                    throw new IllegalStateException("No model loaded for graph placement " + modelId);
                }
                Graph<?> loaded = boundary.load(
                        repository, modelId, entity.type(),
                        historical);
                return overlayKnownModels(modelId, loaded);
            });
        }

        private Graph<?> overlayKnownModels(String rootId, Graph<?> loaded) {
            if (!(loaded instanceof DefaultGraph<?> graph) || !graph.context.complete) {
                return loaded;
            }
            LinkedHashMap<String, Entity<?>> mergedModels =
                    new LinkedHashMap<>(graph.context.models);
            mergedModels.putAll(models);
            LinkedHashSet<ModelGraphEdge> mergedEdges =
                    new LinkedHashSet<>(graph.context.edges);
            mergedEdges.removeIf(edge -> models.containsKey(edge.getChildId()));
            models.forEach((modelId, known) -> {
                Object value = known.get();
                if (value == null) {
                    return;
                }
                for (ModelMetadata.ParentReference parent :
                        ModelMetadata.of(known.type()).parentReferences()) {
                    Object parentId = parent.read(value);
                    if (parentId != null) {
                        mergedEdges.add(new ModelGraphEdge(
                                modelId, parent.repositoryId(parentId),
                                parent.parentModelType() == null
                                        ? null : parent.parentModelType().getName(),
                                parent.path().isEmpty() ? null : parent.path(), -1L, null));
                    }
                }
            });
            return Graphs.compose(
                    rootId, stateIndex, mergedModels, List.copyOf(mergedEdges),
                    repository, historical);
        }

        private <T> Graph<T> replace(Entity<T> entity, long replacementStateIndex) {
            LinkedHashMap<String, Entity<?>> updated = new LinkedHashMap<>(models);
            updated.put(entity.id().toString(), entity);
            return Graphs.lazy(
                    entity, replacementStateIndex, repository,
                    updated, historical, exactBoundary);
        }
    }

    private static final class Placement {
        private final String modelId;
        private final Placement parent;
        private final String path;
        private Map<String, List<Placement>> children = new LinkedHashMap<>();
        private volatile DefaultGraph<?> view;

        private Placement(String modelId, Placement parent, String path) {
            this.modelId = modelId;
            this.parent = parent;
            this.path = path;
        }

        private void freeze() {
            LinkedHashMap<String, List<Placement>> immutable = new LinkedHashMap<>();
            children.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            children = Collections.unmodifiableMap(immutable);
        }
    }

    private static final class DefaultGraph<T> implements Graph<T> {
        private final Context context;
        private final Placement placement;
        private volatile List<Graph<?>> directParents;

        private DefaultGraph(Context context, Placement placement) {
            this.context = context;
            this.placement = placement;
        }

        @SuppressWarnings("unchecked")
        private Entity<T> entity() {
            return (Entity<T>) context.models.get(placement.modelId);
        }

        @Override
        public T get() {
            return entity().get();
        }

        @Override
        public Object id() {
            return entity().id();
        }

        @Override
        public Class<T> type() {
            return entity().type();
        }

        @Override
        public Collection<?> aliases() {
            return entity().aliases();
        }

        @Override
        public String relationshipPath() {
            return placement.path;
        }

        @Override
        public long stateIndex() {
            return context.stateIndex;
        }

        @Override
        public long revisionStateIndex() {
            return entity() instanceof ModelRoot<?> root
                    ? root.stateIndex() : context.stateIndex;
        }

        @Override
        public String lastEventId() {
            return entity().lastEventId();
        }

        @Override
        public Long lastEventIndex() {
            return entity().lastEventIndex();
        }

        @Override
        public long sequenceNumber() {
            return entity().sequenceNumber();
        }

        @Override
        public Instant timestamp() {
            return entity().timestamp();
        }

        @Override
        public Graph<?> root() {
            if (placement.parent != null) {
                Placement result = placement;
                while (result.parent != null) {
                    result = result.parent;
                }
                return context.view(result);
            }
            return parent().map(Graph::root).orElse(this);
        }

        @Override
        public Optional<Graph<?>> parent() {
            if (placement.parent != null) {
                return Optional.of(context.view(placement.parent));
            }
            List<Graph<?>> parents = directParents(null);
            if (parents.size() > 1) {
                throw new IllegalStateException(
                        "Model %s has multiple parents; request a typed parent"
                                .formatted(placement.modelId));
            }
            return parents.stream().findFirst();
        }

        @Override
        public List<Graph<?>> parents() {
            return allDirectParents();
        }

        @Override
        public <P> Optional<Graph<P>> parent(Class<P> parentType) {
            Objects.requireNonNull(parentType, "parentType");
            if (placement.parent != null) {
                Graph<?> parent = context.view(placement.parent);
                return parentType.isAssignableFrom(parent.type())
                        ? Optional.of(cast(parent)) : Optional.empty();
            }
            List<Graph<?>> matches = directParents(parentType);
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Model %s has multiple parents assignable to %s"
                                .formatted(placement.modelId, parentType.getName()));
            }
            return matches.stream().map(Graphs::<P>cast).findFirst();
        }

        private List<Graph<?>> directParents(Class<?> expectedType) {
            List<Graph<?>> parents = directParents;
            if (parents == null) {
                synchronized (this) {
                    parents = directParents;
                    if (parents == null) {
                        directParents = parents = loadDirectParents();
                    }
                }
            }
            if (expectedType == null) {
                return parents;
            }
            return parents.stream()
                    .filter(parent -> expectedType.isAssignableFrom(parent.type()))
                    .toList();
        }

        private List<Graph<?>> loadDirectParents() {
            Object value = get();
            if (value == null) {
                return List.of();
            }
            LinkedHashMap<String, Graph<?>> result = new LinkedHashMap<>();
            for (ModelMetadata.ParentReference reference : ModelMetadata.of(type()).parentReferences()) {
                Class<?> parentType = reference.parentModelType();
                Object parentId = reference.read(value);
                if (parentId == null || parentType == null) {
                    continue;
                }
                String persistedParentId = reference.repositoryId(parentId);
                Entity<?> parent = context.models.get(persistedParentId);
                if (parent != null && !parentType.isAssignableFrom(parent.type())) {
                    parent = null;
                }
                if (parent != null) {
                    result.putIfAbsent(
                            persistedParentId,
                            context.view(context.detached(persistedParentId)));
                    continue;
                }
                if (context.historical || context.exactBoundary) {
                    Graph<?> historicalParent = context.boundary.load(
                            context.repository, persistedParentId,
                            parentType, true);
                    if (historicalParent.isPresent()) {
                        result.putIfAbsent(persistedParentId, historicalParent);
                    }
                    continue;
                }
                parent = context.repository.load(parentId, parentType);
                if (parent.isPresent()) {
                    result.putIfAbsent(
                            parent.id().toString(),
                            Graphs.lazy(parent, context.stateIndex, context.repository));
                }
            }
            return List.copyOf(result.values());
        }

        @Override
        public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
            Objects.requireNonNull(ancestorType, "ancestorType");
            if (ancestorType.isAssignableFrom(type())) {
                return Optional.of(cast(this));
            }
            if (!context.complete
                && placement.parent == null
                && context.models.size() == 1
                && !context.boundary.before
                && ModelMetadata.of(type()).isModel()
                && context.repository instanceof ModelAncestorResolver resolver) {
                Optional<Graph<A>> resolved = resolver.loadAncestorGraph(
                        placement.modelId, type(), ancestorType,
                        context.boundary.ancestorBoundary(
                                context.stateIndex,
                                context.exactBoundary,
                                context.historical));
                if (resolved.isPresent()) {
                    return resolved;
                }
            }
            List<Graph<?>> level = List.of(this);
            Set<String> visited = new LinkedHashSet<>();
            while (!level.isEmpty()) {
                List<Graph<A>> matches = level.stream()
                        .filter(candidate -> ancestorType.isAssignableFrom(candidate.type()))
                        .map(Graphs::<A>cast)
                        .toList();
                if (matches.size() > 1) {
                    throw new IllegalStateException(
                            "Model %s has multiple ancestors assignable to %s"
                                    .formatted(placement.modelId, ancestorType.getName()));
                }
                if (!matches.isEmpty()) {
                    return Optional.of(matches.getFirst());
                }
                List<Graph<?>> next = new ArrayList<>();
                for (Graph<?> candidate : level) {
                    String key = candidate.type().getName() + ':' + candidate.id();
                    if (visited.add(key)) {
                        next.addAll(candidate instanceof DefaultGraph<?> graph
                                            ? graph.allDirectParents()
                                            : candidate.parent().stream().toList());
                    }
                }
                level = List.copyOf(next);
            }
            return Optional.empty();
        }

        private List<Graph<?>> allDirectParents() {
            return placement.parent == null
                    ? directParents(null)
                    : List.of(context.view(placement.parent));
        }

        @Override
        public List<Graph<?>> children() {
            Graph<?> expanded = expanded();
            if (expanded != this) {
                return expanded.children();
            }
            return placement.children.values().stream()
                    .flatMap(Collection::stream)
                    .<Graph<?>>map(context::view)
                    .toList();
        }

        @Override
        public <C> List<Graph<C>> children(Class<C> childType) {
            Graph<?> expanded = expanded();
            if (expanded != this) {
                return cast(expanded).children(childType);
            }
            LinkedHashMap<String, List<Graph<C>>> byPath = new LinkedHashMap<>();
            placement.children.forEach((path, children) -> {
                List<Graph<C>> matches = children.stream().map(context::view)
                        .filter(child -> childType.isAssignableFrom(child.type()))
                        .map(Graphs::<C>cast).toList();
                if (!matches.isEmpty()) {
                    byPath.put(path, matches);
                }
            });
            if (byPath.size() > 1) {
                throw new IllegalStateException(
                        "Model %s has %s children at multiple paths %s; request an explicit path"
                                .formatted(placement.modelId, childType.getName(), byPath.keySet()));
            }
            return byPath.values().stream().findFirst().orElse(List.of());
        }

        @Override
        public <C> List<Graph<C>> children(String path, Class<C> childType) {
            Objects.requireNonNull(path, "path");
            Graph<?> expanded = expanded();
            if (expanded != this) {
                return cast(expanded).children(path, childType);
            }
            return placement.children.getOrDefault(path, List.of()).stream()
                    .map(context::view)
                    .filter(child -> childType.isAssignableFrom(child.type()))
                    .map(Graphs::<C>cast).toList();
        }

        @Override
        public <D> List<Graph<D>> descendants(Class<D> descendantType) {
            return descendants(null, descendantType);
        }

        @Override
        public <D> List<Graph<D>> descendants(String path, Class<D> descendantType) {
            Objects.requireNonNull(descendantType, "descendantType");
            String selectedPath = normalizePath(path);
            Graph<?> expanded = expanded();
            if (expanded != this) {
                return cast(expanded).descendants(selectedPath, descendantType);
            }
            List<Graph<D>> result = new ArrayList<>();
            Deque<PathPlacement> remaining = new ArrayDeque<>();
            placement.children.forEach((childPath, children) -> children.forEach(
                    child -> remaining.addLast(new PathPlacement(child, childPath))));
            while (!remaining.isEmpty()) {
                PathPlacement candidate = remaining.removeFirst();
                Graph<?> graph = context.view(candidate.placement());
                if ((selectedPath == null || Objects.equals(selectedPath, candidate.path()))
                    && descendantType.isAssignableFrom(graph.type())) {
                    result.add(cast(graph));
                }
                if (selectedPath == null
                    || candidate.path() != null
                    && selectedPath.startsWith(candidate.path() + '/')) {
                    candidate.placement().children.forEach((childPath, children) -> {
                        String descendantPath = candidate.path() == null || childPath == null
                                ? null : candidate.path() + '/' + childPath;
                        children.forEach(child -> remaining.addLast(
                                new PathPlacement(child, descendantPath)));
                    });
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

        private record PathPlacement(Placement placement, String path) {
        }

        private Graph<?> expanded() {
            return context.complete ? this : context.expansion(placement);
        }

        @Override
        public Graph<T> apply(Object update) {
            return next(entity().apply(update));
        }

        @Override
        public Graph<T> apply(Object update, Metadata metadata) {
            return next(entity().apply(update, metadata));
        }

        @Override
        public Graph<T> apply(DeserializingMessage update) {
            return next(entity().apply(update));
        }

        @Override
        public Graph<T> apply(Message update) {
            return next(entity().apply(update));
        }

        @Override
        public Graph<T> apply(Object... updates) {
            return next(entity().apply(updates));
        }

        @Override
        public Graph<T> apply(Collection<?> updates) {
            return next(entity().apply(updates));
        }

        @Override
        public Graph<T> update(UnaryOperator<T> update) {
            return next(entity().update(update));
        }

        @Override
        public Graph<T> commit() {
            return next(entity().commit());
        }

        @Override
        public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
            entity().assertLegal(update);
            return this;
        }

        @Override
        public Graph<T> assertAndApply(Object update) {
            return next(entity().assertAndApply(update));
        }

        @Override
        public Graph<T> assertAndApply(Object update, Metadata metadata) {
            return next(entity().assertAndApply(update, metadata));
        }

        private Graph<T> next(Entity<T> next) {
            // Applying an in-memory update does not establish a new durable boundary. In particular, a child model
            // can have an older own state index than the complete root graph from which it was obtained. Retain that
            // graph boundary so returning child.delete() from an interceptor remains part of the same atomic commit.
            return context.replace(next, context.stateIndex);
        }

        @Override
        public Graph<T> previous() {
            Entity<T> previous = entity().previous();
            if (previous == null) {
                return null;
            }
            long currentStateIndex =
                    stateIndex(entity(), context.stateIndex);
            Boundary boundary = context.boundary;
            if (entity() instanceof ModelRoot<?> current
                && previous instanceof ModelRoot<?> preceding
                && current.stateIndex() >= 0L
                && current.stateIndex()
                   != preceding.stateIndex()) {
                boundary = !boundary.before
                           && boundary.stateIndex
                              == current.stateIndex()
                        ? boundary.asBefore()
                        : Boundary.state(
                                current.stateIndex())
                                .asBefore();
            }
            return lazy(
                    previous, currentStateIndex,
                    context.repository,
                    Map.of(previous.id().toString(), previous),
                    context.historical,
                    boundary);
        }

        @Override
        public Graph<T> atStateIndex(long stateIndex) {
            if (stateIndex < -1L) {
                throw new IllegalArgumentException("Graph stateIndex must be at least -1");
            }
            return context.repository.loadGraphAt(id().toString(), type(), stateIndex, Graph.Options.DEFAULT);
        }

        @Override
        public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
            return entity().playBackToEvent(eventIndex, eventId)
                    .map(previous -> Graphs.lazy(
                            previous, stateIndex(previous, context.stateIndex), context.repository,
                            Map.of(previous.id().toString(), previous), true, true));
        }

        @Override
        public Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition) {
            Objects.requireNonNull(condition, "condition");
            Graph<T> result = this;
            while (result != null && !condition.test(result)) {
                result = result.previous();
            }
            return Optional.ofNullable(result);
        }

        private static long stateIndex(Entity<?> entity, long fallback) {
            return entity instanceof ModelRoot<?> root && root.stateIndex() >= -1L
                    ? root.stateIndex() : fallback;
        }
    }

    /** Detached identity-only graph used by the public loadGraph conveniences. */
    private static final class IdentityGraph<T> implements Graph<T> {
        private final Object requestedId;
        private final String primaryId;
        private final Object lookupId;
        private final boolean primaryIdKnown;
        private final Class<T> modelType;
        private final ModelRepository repository;
        private final Boundary boundary;
        private volatile Graph<T> delegate;

        private IdentityGraph(
                Object requestedId,
                String primaryId,
                Class<T> modelType,
                ModelRepository repository) {
            this.requestedId = Objects.requireNonNull(requestedId, "Model ID must not be null");
            this.modelType = Objects.requireNonNull(modelType, "Model type must not be null");
            this.repository = Objects.requireNonNull(repository, "Model repository must not be null");
            ModelMetadata metadata = ModelMetadata.validate(modelType);
            this.primaryId = primaryId == null ? metadata.repositoryId(requestedId) : primaryId;
            this.lookupId = primaryId == null ? requestedId : primaryId;
            this.primaryIdKnown = primaryId != null || requestedId instanceof Id<?> || !metadata.hasAliases();
            this.boundary = Boundary.current(-1L);
        }

        private Graph<T> delegate() {
            Graph<T> result = delegate;
            if (result == null) {
                synchronized (this) {
                    result = delegate;
                    if (result == null) {
                        Entity<T> entity = primaryIdKnown
                                ? repository.load(primaryId, modelType)
                                : repository.load(lookupId, modelType);
                        long stateIndex = entity instanceof ModelRoot<?> root
                                ? root.stateIndex() : -1L;
                        delegate = result = Graphs.lazy(
                                entity, stateIndex, repository,
                                Map.of(entity.id().toString(), entity),
                                false, false);
                    }
                }
            }
            return result;
        }

        @Override
        public T get() {
            return delegate().get();
        }

        @Override
        public Object id() {
            return primaryIdKnown ? primaryId : delegate().id();
        }

        @Override
        public Class<T> type() {
            return modelType;
        }

        @Override
        public Collection<?> aliases() {
            return delegate().aliases();
        }

        @Override
        public String relationshipPath() {
            return null;
        }

        @Override
        public long stateIndex() {
            return delegate().stateIndex();
        }

        @Override
        public long revisionStateIndex() {
            return delegate().revisionStateIndex();
        }

        @Override
        public String lastEventId() {
            return delegate().lastEventId();
        }

        @Override
        public Long lastEventIndex() {
            return delegate().lastEventIndex();
        }

        @Override
        public long sequenceNumber() {
            return delegate().sequenceNumber();
        }

        @Override
        public Instant timestamp() {
            return delegate().timestamp();
        }

        @Override
        public Graph<?> root() {
            return delegate().root();
        }

        @Override
        public Optional<Graph<?>> parent() {
            return delegate().parent();
        }

        @Override
        public List<Graph<?>> parents() {
            return delegate().parents();
        }

        @Override
        public <P> Optional<Graph<P>> parent(Class<P> parentType) {
            return delegate().parent(parentType);
        }

        @Override
        public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
            Objects.requireNonNull(ancestorType, "ancestorType");
            if (ancestorType.isAssignableFrom(modelType)) {
                return Optional.of(cast(this));
            }
            Graph<T> materialized = delegate;
            if (materialized != null) {
                return materialized.ancestor(ancestorType);
            }
            if (repository instanceof ModelAncestorResolver resolver) {
                ModelAncestorResolver.Boundary ancestorBoundary = boundary.ancestorBoundary(
                        -1L, false, false);
                Optional<Graph<A>> result = resolver.loadAncestorGraph(
                        primaryId, modelType, ancestorType, ancestorBoundary);
                if (result.isPresent()) {
                    return result;
                }
            }
            return delegate().ancestor(ancestorType);
        }

        @Override
        public List<Graph<?>> children() {
            return delegate().children();
        }

        @Override
        public <C> List<Graph<C>> children(Class<C> childType) {
            return delegate().children(childType);
        }

        @Override
        public <C> List<Graph<C>> children(String path, Class<C> childType) {
            return delegate().children(path, childType);
        }

        @Override
        public <D> List<Graph<D>> descendants(Class<D> descendantType) {
            return delegate().descendants(descendantType);
        }

        @Override
        public <D> List<Graph<D>> descendants(String path, Class<D> descendantType) {
            return delegate().descendants(path, descendantType);
        }

        @Override
        public Graph<T> apply(Object update) {
            return delegate().apply(update);
        }

        @Override
        public Graph<T> apply(Object update, Metadata metadata) {
            return delegate().apply(update, metadata);
        }

        @Override
        public Graph<T> apply(DeserializingMessage update) {
            return delegate().apply(update);
        }

        @Override
        public Graph<T> apply(Message update) {
            return delegate().apply(update);
        }

        @Override
        public Graph<T> apply(Object... updates) {
            return delegate().apply(updates);
        }

        @Override
        public Graph<T> apply(Collection<?> updates) {
            return delegate().apply(updates);
        }

        @Override
        public Graph<T> update(UnaryOperator<T> update) {
            return delegate().update(update);
        }

        @Override
        public Graph<T> commit() {
            return delegate().commit();
        }

        @Override
        public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
            delegate().assertLegal(update);
            return this;
        }

        @Override
        public Graph<T> assertAndApply(Object update) {
            return delegate().assertAndApply(update);
        }

        @Override
        public Graph<T> assertAndApply(Object update, Metadata metadata) {
            return delegate().assertAndApply(update, metadata);
        }

        @Override
        public Graph<T> previous() {
            return delegate().previous();
        }

        @Override
        public Graph<T> atStateIndex(long stateIndex) {
            return delegate().atStateIndex(stateIndex);
        }

        @Override
        public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
            return delegate().playBackToEvent(eventIndex, eventId);
        }

        @Override
        public Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition) {
            return delegate().playBackToCondition(condition);
        }
    }

    private static <T> Graph<T> lazy(
            Entity<T> entity,
            long stateIndex,
            ModelRepository repository,
            Map<String, Entity<?>> models,
            boolean historical,
            Boundary boundary) {
        Objects.requireNonNull(entity, "entity");
        Context context = new Context(
                stateIndex,
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(models)),
                List.of(), repository, false, historical,
                true,
                boundary);
        Placement root = context.detached(
                entity.id().toString());
        context.root = root;
        return context.view(root);
    }

    private record Boundary(
            long stateIndex,
            String commitId,
            Integer substep,
            Long eventIndex,
            boolean before,
            Metadata messageMetadata,
            boolean eventMessage) {

        private static Boundary state(long stateIndex) {
            return new Boundary(
                    stateIndex, null, null, null,
                    false, null, false);
        }

        private static Boundary current(long stateIndex) {
            DeserializingMessage message =
                    DeserializingMessage.getCurrent();
            return message == null
                    ? state(stateIndex)
                    : new Boundary(
                            stateIndex, null, null,
                            null, false,
                            message.getMetadata(),
                            message.getMessageType()
                            == MessageType.EVENT
                            || message.getMessageType()
                               == MessageType.NOTIFICATION);
        }

        private Boundary asBefore() {
            Boundary resolved = resolve();
            return resolved.before ? resolved
                    : new Boundary(
                            resolved.stateIndex,
                            resolved.commitId,
                            resolved.substep,
                            resolved.eventIndex,
                            true, null,
                            resolved.eventMessage);
        }

        private Graph<?> load(
                ModelRepository repository,
                String rootId,
                Class<?> rootType,
                boolean historical) {
            Boundary resolved = resolve();
            if (resolved.commitId != null) {
                return resolved.before
                        ? repository.loadGraphBeforeCommit(
                                rootId, rootType,
                                resolved.stateIndex,
                                resolved.commitId,
                                resolved.substep,
                                Graph.Options.DEFAULT)
                        : repository.loadGraphAtCommit(
                                rootId, rootType,
                                resolved.stateIndex,
                                resolved.commitId,
                                resolved.substep,
                                Graph.Options.DEFAULT);
            }
            if (resolved.eventIndex != null) {
                return resolved.before
                        ? repository.loadGraphBeforeEvent(
                                rootId, rootType,
                                resolved.stateIndex,
                                resolved.eventIndex,
                                Graph.Options.DEFAULT)
                        : repository.loadGraphAtEvent(
                                rootId, rootType,
                                resolved.stateIndex,
                                resolved.eventIndex,
                                Graph.Options.DEFAULT);
            }
            if (resolved.before) {
                return repository.loadGraphBefore(
                        rootId, rootType,
                        resolved.stateIndex,
                        Graph.Options.DEFAULT);
            }
            return historical
                    ? repository.loadGraphAt(
                            rootId, rootType,
                            resolved.stateIndex,
                            Graph.Options.DEFAULT)
                    : repository.loadGraph(
                            rootId, rootType,
                            Graph.Options.DEFAULT);
        }

        private Boundary resolve() {
            if (messageMetadata == null) {
                return this;
            }
            Object commit = messageMetadata.get(
                    ModelEventMetadata.COMMIT_ID);
            Object step = messageMetadata.get(
                    ModelEventMetadata.SUBSTEP);
            if (commit instanceof String id
                && !id.isBlank()
                && step != null) {
                return new Boundary(
                        stateIndex, id,
                        parseSubstep(step), null,
                        before, null,
                        eventMessage);
            }
            return new Boundary(
                    stateIndex, null, null, null,
                    before, null,
                    eventMessage);
        }

        private ModelAncestorResolver.Boundary ancestorBoundary(
                long fallbackStateIndex,
                boolean exact,
                boolean historical) {
            if (!exact) {
                return ModelAncestorResolver.Boundary.current();
            }
            Boundary resolved = resolve();
            if (resolved.commitId != null) {
                return ModelAncestorResolver.Boundary.commit(
                        resolved.commitId, resolved.substep);
            }
            if (resolved.eventIndex != null) {
                return ModelAncestorResolver.Boundary.event(
                        resolved.eventIndex);
            }
            return ModelAncestorResolver.Boundary.state(
                    resolved.stateIndex >= -1L
                            ? resolved.stateIndex
                            : fallbackStateIndex,
                    !historical && !resolved.eventMessage);
        }

        private static int parseSubstep(Object value) {
            int result;
            if (value instanceof Number number) {
                result = number.intValue();
            } else {
                result = Integer.parseInt(
                        value.toString());
            }
            if (result < 0) {
                throw new IllegalArgumentException(
                        "Model event commit substep must be non-negative");
            }
            return result;
        }
    }

    private static final class MappedContext {
        private final Function<? super Graph<?>, ?> mapper;
        private final List<?> values;
        private final Map<Graph<?>, MappedGraph<?>> views = new IdentityHashMap<>();

        private MappedContext(Function<? super Graph<?>, ?> mapper, Collection<?> values) {
            this.mapper = mapper;
            this.values = List.copyOf(values);
        }

        private <C> Optional<C> value(Class<C> contextType) {
            List<C> matches = values.stream()
                    .filter(Objects::nonNull)
                    .filter(contextType::isInstance)
                    .map(contextType::cast).toList();
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Graph context contains multiple values assignable to %s"
                                .formatted(contextType.getName()));
            }
            return matches.stream().findFirst();
        }

        @SuppressWarnings("unchecked")
        private synchronized <T> Graph<T> view(Graph<T> graph) {
            return (Graph<T>) views.computeIfAbsent(
                    graph, ignored -> new MappedGraph<>(this, graph));
        }
    }

    private static final class MappedGraph<T> implements Graph<T> {
        private final MappedContext context;
        private final Graph<T> delegate;
        private volatile boolean mapped;
        private T value;

        private MappedGraph(MappedContext context, Graph<T> delegate) {
            this.context = context;
            this.delegate = delegate;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            if (!mapped) {
                synchronized (this) {
                    if (!mapped) {
                        value = (T) context.mapper.apply(delegate);
                        mapped = true;
                    }
                }
            }
            return value;
        }

        @Override public Object id() { return delegate.id(); }
        @Override public Class<T> type() { return delegate.type(); }
        @Override public Collection<?> aliases() { return delegate.aliases(); }
        @Override public <C> Optional<C> context(Class<C> contextType) {
            Objects.requireNonNull(contextType, "contextType");
            return context.value(contextType).or(() -> delegate.context(contextType));
        }
        @Override public String relationshipPath() { return delegate.relationshipPath(); }
        @Override public long stateIndex() { return delegate.stateIndex(); }
        @Override public long revisionStateIndex() { return delegate.revisionStateIndex(); }
        @Override public String lastEventId() { return delegate.lastEventId(); }
        @Override public Long lastEventIndex() { return delegate.lastEventIndex(); }
        @Override public long sequenceNumber() { return delegate.sequenceNumber(); }
        @Override public Instant timestamp() { return delegate.timestamp(); }
        @Override public Graph<?> root() { return context.view(delegate.root()); }
        @Override public Optional<Graph<?>> parent() {
            return delegate.parent().map(context::view).map(graph -> (Graph<?>) graph);
        }
        @Override public List<Graph<?>> parents() {
            return delegate.parents().stream().<Graph<?>>map(context::view).toList();
        }
        @Override public <P> Optional<Graph<P>> parent(Class<P> parentType) {
            return delegate.parent(parentType).map(context::view);
        }
        @Override public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
            return delegate.ancestor(ancestorType).map(context::view);
        }
        @Override public List<Graph<?>> children() {
            return delegate.children().stream()
                    .<Graph<?>>map(context::view).toList();
        }
        @Override public List<String> childPaths() { return delegate.childPaths(); }
        @Override public <C> List<Graph<C>> children(Class<C> childType) {
            return delegate.children(childType).stream().map(context::view).toList();
        }
        @Override public <C> List<Graph<C>> children(String path, Class<C> childType) {
            return delegate.children(path, childType).stream().map(context::view).toList();
        }
        @Override public <D> List<Graph<D>> descendants(Class<D> descendantType) {
            return delegate.descendants(descendantType).stream().map(context::view).toList();
        }
        @Override public <D> List<Graph<D>> descendants(String path, Class<D> descendantType) {
            return delegate.descendants(path, descendantType).stream().map(context::view).toList();
        }
        @Override public Graph<T> apply(Object update) { return context.view(delegate.apply(update)); }
        @Override public Graph<T> apply(Object update, Metadata metadata) {
            return context.view(delegate.apply(update, metadata));
        }
        @Override public Graph<T> apply(DeserializingMessage update) {
            return context.view(delegate.apply(update));
        }
        @Override public Graph<T> apply(Message update) { return context.view(delegate.apply(update)); }
        @Override public Graph<T> apply(Object... updates) { return context.view(delegate.apply(updates)); }
        @Override public Graph<T> apply(Collection<?> updates) { return context.view(delegate.apply(updates)); }
        @Override public Graph<T> update(UnaryOperator<T> update) { return context.view(delegate.update(update)); }
        @Override public Graph<T> commit() { return context.view(delegate.commit()); }
        @Override public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
            delegate.assertLegal(update);
            return this;
        }
        @Override public Graph<T> assertAndApply(Object update) {
            return context.view(delegate.assertAndApply(update));
        }
        @Override public Graph<T> assertAndApply(Object update, Metadata metadata) {
            return context.view(delegate.assertAndApply(update, metadata));
        }
        @Override public Graph<T> previous() {
            Graph<T> previous = delegate.previous();
            return previous == null ? null : context.view(previous);
        }
        @Override public Graph<T> atStateIndex(long stateIndex) {
            return context.view(delegate.atStateIndex(stateIndex));
        }
        @Override public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
            return delegate.playBackToEvent(eventIndex, eventId).map(context::view);
        }
        @Override public Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition) {
            Objects.requireNonNull(condition, "condition");
            Graph<T> result = this;
            while (result != null && !condition.test(result)) {
                result = result.previous();
            }
            return Optional.ofNullable(result);
        }
    }

    private static final class ChangeContext {
        private final Graph<?> currentRoot;
        private final Graph<?> previousRoot;
        private final Map<Graph<?>, ChangeGraph<?>> views = new IdentityHashMap<>();
        private ChangeGraph<?> root;

        private ChangeContext(Graph<?> currentRoot, Graph<?> previousRoot) {
            this.currentRoot = Objects.requireNonNull(currentRoot, "current graph");
            this.previousRoot = previousRoot;
        }

        @SuppressWarnings("unchecked")
        private synchronized <T> Graph<T> root() {
            if (root == null) {
                root = new ChangeGraph<>(this, (Graph<Object>) currentRoot, null);
                views.put(currentRoot, root);
            }
            return (Graph<T>) root;
        }

        @SuppressWarnings("unchecked")
        private synchronized <T> Graph<T> view(Graph<T> graph) {
            return (Graph<T>) views.computeIfAbsent(
                    graph, ignored -> new ChangeGraph<>(this, graph, null));
        }

        @SuppressWarnings("unchecked")
        private <T> Graph<T> previous(Graph<T> graph) {
            if (previousRoot == null) {
                return null;
            }
            if (graph == currentRoot) {
                return (Graph<T>) previousRoot;
            }
            return previousRoot.find(graph.id(), graph.type())
                    .map(candidate -> (Graph<T>) candidate)
                    .orElse(null);
        }
    }

    private static final class ChangeGraph<T> implements Graph<T> {
        private final ChangeContext context;
        private final Graph<T> delegate;
        private final Graph<?> parent;

        private ChangeGraph(
                ChangeContext context,
                Graph<T> delegate,
                Graph<?> parent) {
            this.context = context;
            this.delegate = delegate;
            this.parent = parent;
        }

        private <C> Graph<C> child(Graph<C> child) {
            synchronized (context) {
                @SuppressWarnings("unchecked") ChangeGraph<C> result =
                        (ChangeGraph<C>) context.views.get(child);
                if (result == null) {
                    result = new ChangeGraph<>(context, child, this);
                    context.views.put(child, result);
                }
                return result;
            }
        }

        @Override public T get() { return delegate.get(); }
        @Override public Object id() { return delegate.id(); }
        @Override public Class<T> type() { return delegate.type(); }
        @Override public Collection<?> aliases() { return delegate.aliases(); }
        @Override public <C> Optional<C> context(Class<C> contextType) {
            return delegate.context(contextType);
        }
        @Override public String relationshipPath() { return delegate.relationshipPath(); }
        @Override public long stateIndex() { return delegate.stateIndex(); }
        @Override public long revisionStateIndex() { return delegate.revisionStateIndex(); }
        @Override public String lastEventId() { return delegate.lastEventId(); }
        @Override public Long lastEventIndex() { return delegate.lastEventIndex(); }
        @Override public long sequenceNumber() { return delegate.sequenceNumber(); }
        @Override public Instant timestamp() { return delegate.timestamp(); }
        @Override public Graph<?> root() { return context.root(); }
        @Override public Optional<Graph<?>> parent() {
            if (parent != null) {
                return Optional.of(parent);
            }
            return delegate.parent().map(this::child).map(graph -> (Graph<?>) graph);
        }
        @Override public List<Graph<?>> parents() {
            if (parent != null) {
                return List.of(parent);
            }
            return delegate.parents().stream().<Graph<?>>map(this::child).toList();
        }
        @Override public <P> Optional<Graph<P>> parent(Class<P> parentType) {
            List<Graph<P>> matches = parents().stream()
                    .filter(candidate -> parentType.isAssignableFrom(candidate.type()))
                    .map(Graphs::<P>cast).toList();
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Model %s has multiple parents assignable to %s"
                                .formatted(id(), parentType.getName()));
            }
            return matches.stream().findFirst();
        }
        @Override public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
            Graph<?> candidate = this;
            while (candidate != null) {
                if (ancestorType.isAssignableFrom(candidate.type())) {
                    return Optional.of(Graphs.cast(candidate));
                }
                candidate = candidate.parent().orElse(null);
            }
            return Optional.empty();
        }
        @Override public List<Graph<?>> children() {
            return delegate.children().stream().<Graph<?>>map(this::child).toList();
        }
        @Override public List<String> childPaths() { return delegate.childPaths(); }
        @Override public <C> List<Graph<C>> children(Class<C> childType) {
            return delegate.children(childType).stream().map(this::child).toList();
        }
        @Override public <C> List<Graph<C>> children(String path, Class<C> childType) {
            return delegate.children(path, childType).stream().map(this::child).toList();
        }
        @Override public <D> List<Graph<D>> descendants(Class<D> descendantType) {
            return stream().skip(1)
                    .filter(candidate -> descendantType.isAssignableFrom(candidate.type()))
                    .map(Graphs::<D>cast).toList();
        }
        @Override public <D> List<Graph<D>> descendants(String path, Class<D> descendantType) {
            String selectedPath = normalizePath(path);
            List<Graph<D>> result = new ArrayList<>();
            Deque<PathGraph> remaining = new ArrayDeque<>();
            children().forEach(child -> remaining.addLast(
                    new PathGraph(child, child.relationshipPath())));
            while (!remaining.isEmpty()) {
                PathGraph candidate = remaining.removeFirst();
                if ((selectedPath == null
                     || Objects.equals(selectedPath, candidate.path()))
                    && descendantType.isAssignableFrom(
                        candidate.graph().type())) {
                    result.add(cast(candidate.graph()));
                }
                if (selectedPath == null
                    || candidate.path() != null
                    && selectedPath.startsWith(
                        candidate.path() + '/')) {
                    candidate.graph().children().forEach(child -> {
                        String descendantPath = candidate.path() == null
                                                || child.relationshipPath() == null
                                ? null
                                : candidate.path() + '/'
                                  + child.relationshipPath();
                        remaining.addLast(new PathGraph(
                                child, descendantPath));
                    });
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
                throw new IllegalArgumentException(
                        "Graph descendant path must contain non-empty segments");
            }
            return result;
        }

        private record PathGraph(Graph<?> graph, String path) {
        }
        @Override public Graph<T> apply(Object update) { return delegate.apply(update); }
        @Override public Graph<T> apply(Object update, Metadata metadata) {
            return delegate.apply(update, metadata);
        }
        @Override public Graph<T> apply(DeserializingMessage update) { return delegate.apply(update); }
        @Override public Graph<T> apply(Message update) { return delegate.apply(update); }
        @Override public Graph<T> apply(Object... updates) { return delegate.apply(updates); }
        @Override public Graph<T> apply(Collection<?> updates) { return delegate.apply(updates); }
        @Override public Graph<T> update(UnaryOperator<T> update) { return delegate.update(update); }
        @Override public Graph<T> commit() { return delegate.commit(); }
        @Override public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
            delegate.assertLegal(update);
            return this;
        }
        @Override public Graph<T> assertAndApply(Object update) { return delegate.assertAndApply(update); }
        @Override public Graph<T> assertAndApply(Object update, Metadata metadata) {
            return delegate.assertAndApply(update, metadata);
        }
        @Override public Graph<T> previous() { return context.previous(delegate); }
        @Override public Graph<T> atStateIndex(long stateIndex) { return delegate.atStateIndex(stateIndex); }
        @Override public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
            return delegate.playBackToEvent(eventIndex, eventId);
        }
        @Override public Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition) {
            Objects.requireNonNull(condition, "condition");
            Graph<T> result = this;
            while (result != null && !condition.test(result)) {
                result = result.previous();
            }
            return Optional.ofNullable(result);
        }
    }

    private static final class SelectedContext {
        private final Set<String> rootSelection;
        private final Map<SelectedKey, SelectedGraph<?>> views = new LinkedHashMap<>();
        private SelectedGraph<?> root;

        private SelectedContext(Set<String> rootSelection) {
            this.rootSelection = rootSelection;
        }

        @SuppressWarnings("unchecked")
        private synchronized <T> Graph<T> root(Graph<T> graph) {
            if (root == null) {
                root = new SelectedGraph<>(this, graph, rootSelection, null);
                views.put(new SelectedKey(graph, rootSelection), root);
            }
            return cast(root);
        }

        private synchronized <T> Graph<T> child(
                Graph<T> graph,
                Collection<String> selection,
                SelectedGraph<?> parent) {
            Set<String> immutable = Set.copyOf(selection);
            return (Graph<T>) views.computeIfAbsent(
                    new SelectedKey(graph, immutable),
                    ignored -> new SelectedGraph<>(this, graph, immutable, parent));
        }
    }

    private record SelectedKey(Graph<?> graph, Set<String> selection) {
        @Override public boolean equals(Object other) {
            return other instanceof SelectedKey key
                   && graph == key.graph
                   && selection.equals(key.selection);
        }

        @Override public int hashCode() {
            return 31 * System.identityHashCode(graph) + selection.hashCode();
        }
    }

    private static final class SelectedGraph<T> implements Graph<T> {
        private final SelectedContext context;
        private final Graph<T> delegate;
        private final Set<String> selection;
        private final SelectedGraph<?> parent;

        private SelectedGraph(
                SelectedContext context,
                Graph<T> delegate,
                Set<String> selection,
                SelectedGraph<?> parent) {
            this.context = context;
            this.delegate = delegate;
            this.selection = selection;
            this.parent = parent;
        }

        private Set<String> below(String relationshipPath) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String prefix = relationshipPath + "/";
            for (String path : selection) {
                if (path.startsWith(prefix)) {
                    result.add(path.substring(prefix.length()));
                }
            }
            return Set.copyOf(result);
        }

        private boolean includes(String relationshipPath) {
            String prefix = relationshipPath + "/";
            return selection.stream().anyMatch(
                    path -> path.equals(relationshipPath)
                            || path.startsWith(prefix));
        }

        private Graph<?> selected(Graph<?> graph) {
            return context.child(graph, below(graph.relationshipPath()), this);
        }

        @Override public T get() { return delegate.get(); }
        @Override public Object id() { return delegate.id(); }
        @Override public Class<T> type() { return delegate.type(); }
        @Override public Collection<?> aliases() { return delegate.aliases(); }
        @Override public <C> Optional<C> context(Class<C> contextType) {
            return delegate.context(contextType);
        }
        @Override public String relationshipPath() { return delegate.relationshipPath(); }
        @Override public long stateIndex() { return delegate.stateIndex(); }
        @Override public long revisionStateIndex() { return delegate.revisionStateIndex(); }
        @Override public String lastEventId() { return delegate.lastEventId(); }
        @Override public Long lastEventIndex() { return delegate.lastEventIndex(); }
        @Override public long sequenceNumber() { return delegate.sequenceNumber(); }
        @Override public Instant timestamp() { return delegate.timestamp(); }
        @Override public Graph<?> root() { return context.root; }
        @Override public Optional<Graph<?>> parent() {
            return Optional.ofNullable(parent);
        }
        @Override public List<Graph<?>> parents() {
            return parent().stream().toList();
        }
        @Override public <P> Optional<Graph<P>> parent(Class<P> parentType) {
            return parent().filter(candidate -> parentType.isAssignableFrom(candidate.type()))
                    .map(Graphs::<P>cast);
        }
        @Override public <A> Optional<Graph<A>> ancestor(Class<A> ancestorType) {
            Graph<?> candidate = this;
            while (candidate != null) {
                if (ancestorType.isAssignableFrom(candidate.type())) {
                    return Optional.of(cast(candidate));
                }
                candidate = candidate.parent().orElse(null);
            }
            return Optional.empty();
        }
        @Override public List<Graph<?>> children() {
            return delegate.children().stream()
                    .filter(child -> child.relationshipPath() != null
                                     && includes(child.relationshipPath()))
                    .map(this::selected).toList();
        }
        @Override public List<String> childPaths() {
            return delegate.childPaths().stream()
                    .filter(this::includes).toList();
        }
        @Override public <C> List<Graph<C>> children(Class<C> childType) {
            return children().stream()
                    .filter(child -> childType.isAssignableFrom(child.type()))
                    .map(Graphs::<C>cast).toList();
        }
        @Override public <C> List<Graph<C>> children(String path, Class<C> childType) {
            return children().stream()
                    .filter(child -> Objects.equals(path, child.relationshipPath()))
                    .filter(child -> childType.isAssignableFrom(child.type()))
                    .map(Graphs::<C>cast).toList();
        }
        @Override public <D> List<Graph<D>> descendants(Class<D> descendantType) {
            return stream().skip(1)
                    .filter(graph -> descendantType.isAssignableFrom(graph.type()))
                    .map(Graphs::<D>cast).toList();
        }
        @Override public <D> List<Graph<D>> descendants(String path, Class<D> descendantType) {
            return stream().skip(1)
                    .filter(graph -> Objects.equals(path, graph.relationshipPath()))
                    .filter(graph -> descendantType.isAssignableFrom(graph.type()))
                    .map(Graphs::<D>cast).toList();
        }
        @Override public Graph<T> apply(Object update) {
            return Graphs.selectPaths(delegate.apply(update), selection);
        }
        @Override public Graph<T> apply(Object update, Metadata metadata) {
            return Graphs.selectPaths(delegate.apply(update, metadata), selection);
        }
        @Override public Graph<T> apply(DeserializingMessage update) {
            return Graphs.selectPaths(delegate.apply(update), selection);
        }
        @Override public Graph<T> apply(Message update) {
            return Graphs.selectPaths(delegate.apply(update), selection);
        }
        @Override public Graph<T> apply(Object... updates) {
            return Graphs.selectPaths(delegate.apply(updates), selection);
        }
        @Override public Graph<T> apply(Collection<?> updates) {
            return Graphs.selectPaths(delegate.apply(updates), selection);
        }
        @Override public Graph<T> update(UnaryOperator<T> update) {
            return Graphs.selectPaths(delegate.update(update), selection);
        }
        @Override public Graph<T> commit() {
            return Graphs.selectPaths(delegate.commit(), selection);
        }
        @Override public <E extends Exception> Graph<T> assertLegal(Object update) throws E {
            delegate.assertLegal(update);
            return this;
        }
        @Override public Graph<T> assertAndApply(Object update) {
            return Graphs.selectPaths(delegate.assertAndApply(update), selection);
        }
        @Override public Graph<T> assertAndApply(Object update, Metadata metadata) {
            return Graphs.selectPaths(delegate.assertAndApply(update, metadata), selection);
        }
        @Override public Graph<T> previous() {
            Graph<T> previous = delegate.previous();
            return previous == null ? null : Graphs.selectPaths(previous, selection);
        }
        @Override public Graph<T> atStateIndex(long stateIndex) {
            return Graphs.selectPaths(delegate.atStateIndex(stateIndex), selection);
        }
        @Override public Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId) {
            return delegate.playBackToEvent(eventIndex, eventId)
                    .map(graph -> Graphs.selectPaths(graph, selection));
        }
        @Override public Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition) {
            Objects.requireNonNull(condition, "condition");
            Graph<T> result = this;
            while (result != null && !condition.test(result)) {
                result = result.previous();
            }
            return Optional.ofNullable(result);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Graph<T> cast(Graph<?> graph) {
        return (Graph<T>) graph;
    }
}
