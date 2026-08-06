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
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
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
        return lazy(entity, stateIndex, repository, Map.of(entity.id().toString(), entity), false);
    }

    /** Creates a lazy graph that reuses every model already loaded for the same handler boundary. */
    static <T> Graph<T> lazy(
            Entity<T> entity,
            ModelCommitContext commitContext,
            ModelRepository repository) {
        LinkedHashMap<String, Entity<?>> models = new LinkedHashMap<>();
        commitContext.entries().forEach(entry -> models.put(entry.target().modelId(), entry.entity()));
        return lazy(entity, commitContext.readStateIndex(), repository, models, false);
    }

    private static <T> Graph<T> lazy(
            Entity<T> entity,
            long stateIndex,
            ModelRepository repository,
            Map<String, Entity<?>> models,
            boolean historical) {
        Objects.requireNonNull(entity, "entity");
        Context context = new Context(
                stateIndex,
                Collections.unmodifiableMap(new LinkedHashMap<>(models)),
                List.of(), repository, false, historical,
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
        return new MappedContext(Objects.requireNonNull(mapper, "mapper"))
                .view(Objects.requireNonNull(graph, "graph"));
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
                Boundary boundary) {
            this.stateIndex = stateIndex;
            this.models = models;
            this.edges = edges;
            this.repository = Objects.requireNonNull(repository, "repository");
            this.complete = complete;
            this.historical = historical;
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
            return Graphs.lazy(entity, replacementStateIndex, repository, updated, historical);
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
        public String relationshipPath() {
            return placement.path;
        }

        @Override
        public long stateIndex() {
            return context.stateIndex;
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
                if (context.historical) {
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
            return context.replace(next, stateIndex(next, context.stateIndex));
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
                            Map.of(previous.id().toString(), previous), true));
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
            Metadata messageMetadata) {

        private static Boundary state(long stateIndex) {
            return new Boundary(
                    stateIndex, null, null, null,
                    false, null);
        }

        private static Boundary current(long stateIndex) {
            DeserializingMessage message =
                    DeserializingMessage.getCurrent();
            return message == null
                    ? state(stateIndex)
                    : new Boundary(
                            stateIndex, null, null,
                            null, false,
                            message.getMetadata());
        }

        private Boundary asBefore() {
            Boundary resolved = resolve();
            return resolved.before ? resolved
                    : new Boundary(
                            resolved.stateIndex,
                            resolved.commitId,
                            resolved.substep,
                            resolved.eventIndex,
                            true, null);
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
                        before, null);
            }
            return new Boundary(
                    stateIndex, null, null, null,
                    before, null);
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
        private final Map<Graph<?>, MappedGraph<?>> views = new IdentityHashMap<>();

        private MappedContext(Function<? super Graph<?>, ?> mapper) {
            this.mapper = mapper;
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
        @Override public String relationshipPath() { return delegate.relationshipPath(); }
        @Override public long stateIndex() { return delegate.stateIndex(); }
        @Override public String lastEventId() { return delegate.lastEventId(); }
        @Override public Long lastEventIndex() { return delegate.lastEventIndex(); }
        @Override public long sequenceNumber() { return delegate.sequenceNumber(); }
        @Override public Instant timestamp() { return delegate.timestamp(); }
        @Override public Graph<?> root() { return context.view(delegate.root()); }
        @Override public Optional<Graph<?>> parent() {
            return delegate.parent().map(context::view).map(graph -> (Graph<?>) graph);
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

    @SuppressWarnings("unchecked")
    private static <T> Graph<T> cast(Graph<?> graph) {
        return (Graph<T>) graph;
    }
}
