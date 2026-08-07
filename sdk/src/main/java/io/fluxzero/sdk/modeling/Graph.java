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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.GraphJsonSerializer;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.fluxzero.common.api.search.ModelGraphComposition.UNBOUNDED;

/**
 * A typed view of one independently stored model and its lazily available relationship context.
 * <p>
 * Merely resolving a graph loads no more state than resolving the corresponding model value. Parent, child and
 * descendant state is loaded only when it is requested, at the same pinned model-state boundary. Every returned child
 * is itself a graph view, so root, parent, history and update operations remain available without exposing the
 * persistence-only {@link Entity} wrapper.
 * <p>
 * As the sole parameter of an event or notification handler, a graph subscribes to durable changes of that root and
 * any descendant. The handler runs once per affected root. {@link #previous()} then returns the complete graph directly
 * before the change; a child move therefore invokes the handler once for the old root and once for the new root.
 *
 * @param <T> model value type at the current graph placement
 */
@JsonSerialize(using = GraphJsonSerializer.class)
public interface Graph<T> {

    /** Returns the current model value, or {@code null} for a missing or deleted model. */
    @Nullable
    T get();

    /** Returns whether this graph currently contains a model value. */
    default boolean isPresent() {
        return get() != null;
    }

    /** Returns whether this graph currently represents a missing or deleted model. */
    default boolean isEmpty() {
        return get() == null;
    }

    /** Returns the current model value as an optional without loading relationship context. */
    default Optional<T> optional() {
        return Optional.ofNullable(get());
    }

    /** Maps this graph itself without loading relationship context. */
    default <R> Optional<R> mapGraph(Function<? super Graph<T>, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return Optional.ofNullable(mapper.apply(this));
    }

    /** Returns this graph when it matches the supplied condition. */
    default Optional<Graph<T>> filterGraph(Predicate<? super Graph<T>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return Optional.of(this).filter(predicate);
    }

    /** Returns this graph only when its current model value is present. */
    default Optional<Graph<T>> filterPresent() {
        return isPresent() ? Optional.of(this) : Optional.empty();
    }

    /** Maps this graph only when its current model value is present. */
    default <R> Optional<R> mapIfPresent(Function<? super Graph<T>, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return isPresent() ? Optional.ofNullable(mapper.apply(this)) : Optional.empty();
    }

    /** Maps the current model value when present without loading relationship context. */
    default <R> Optional<R> map(Function<? super T, ? extends R> mapper) {
        return optional().map(mapper);
    }

    /** Returns the current model value or the supplied fallback. */
    default T orElse(T fallback) {
        return optional().orElse(fallback);
    }

    /** Returns the current model value or obtains a fallback lazily. */
    default T orElseGet(Supplier<? extends T> fallback) {
        return optional().orElseGet(fallback);
    }

    /** Returns the current model value or throws when this graph is empty. */
    default T orElseThrow() {
        return optional().orElseThrow();
    }

    /** Returns the current model value or throws the supplied exception when this graph is empty. */
    default <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        return optional().orElseThrow(exceptionSupplier);
    }

    /** Applies a graph operation only when a current model value is present. */
    default Graph<T> ifPresent(UnaryOperator<Graph<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return isPresent() ? operation.apply(this) : this;
    }

    /** Returns the functional identifier object carried by the model wrapper. */
    Object id();

    /** Returns the concrete model type. */
    Class<T> type();

    /** Returns the model aliases without loading relationship context. */
    default Collection<?> aliases() {
        return List.of();
    }

    /** Returns the parent-relative relationship path, or {@code null} for a pathless, standalone, or root view. */
    @Nullable
    String relationshipPath();

    /** Returns the pinned namespace-wide model-state boundary. */
    long stateIndex();

    /** Returns the last globally published event identifier visible to this model revision. */
    @Nullable
    String lastEventId();

    /** Returns the last globally published event index visible to this model revision. */
    @Nullable
    Long lastEventIndex();

    /** Returns the model-local sequence number. */
    long sequenceNumber();

    /** Returns the timestamp of this model revision. */
    Instant timestamp();

    /** Returns the outer graph root. On a root graph this returns {@code this}. */
    Graph<?> root();

    /** Returns whether this graph is the outer root of its current graph view. */
    default boolean isRoot() {
        return root() == this;
    }

    /** Returns the parent of this concrete graph placement, if one exists. */
    Optional<Graph<?>> parent();

    /**
     * Returns all direct parents of this model. A concrete placement normally has one parent, while a directly loaded
     * model may expose multiple declared {@link ParentId} relationships.
     */
    default List<Graph<?>> parents() {
        return parent().stream().toList();
    }

    /** Returns the closest parent assignable to the requested type. */
    <P> Optional<Graph<P>> parent(Class<P> parentType);

    /** Returns the value of the closest parent assignable to the requested type. */
    default <P> Optional<P> parentModel(Class<P> parentType) {
        return parent(parentType).map(Graph::get);
    }

    /** Returns the closest ancestor, including the current graph, assignable to the requested type. */
    <A> Optional<Graph<A>> ancestor(Class<A> ancestorType);

    /** Returns the value of the closest ancestor, including the current model, assignable to the requested type. */
    default <A> Optional<A> ancestorModel(Class<A> ancestorType) {
        return ancestor(ancestorType).map(Graph::get);
    }

    /** Returns all direct children in deterministic relationship-path order. */
    List<Graph<?>> children();

    /**
     * Returns the declared serialized child paths in deterministic order, including paths that currently have no
     * children. Pathless relationships are deliberately absent because they are graph context rather than JSON
     * structure.
     */
    default List<String> childPaths() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        children().stream().map(Graph::relationshipPath)
                .filter(path -> path != null && !path.isBlank())
                .forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * Returns an immutable graph view containing only the selected serialized relationship paths and their ancestors.
     * Model values and graph nodes are shared with this graph; no models are copied or loaded merely by creating the
     * view. An empty selection returns this complete graph.
     */
    default Graph<T> selectPaths(String... paths) {
        return Graphs.selectPaths(this, List.of(paths));
    }

    /** See {@link #selectPaths(String...)}. */
    default Graph<T> selectPaths(Collection<String> paths) {
        return Graphs.selectPaths(this, paths);
    }

    /**
     * Returns an immutable, lazy view whose model values are retained only when the supplied predicate accepts their
     * graph placement. Rejected descendants remain structurally addressable as empty graphs but are omitted during
     * graph serialization; accepted values are shared and never copied. A caller that retains a deep descendant should
     * also retain its serialized ancestors.
     */
    default Graph<T> filterNodes(Predicate<? super Graph<?>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return Graphs.mapValues(this, graph -> predicate.test(graph) ? graph.get() : null);
    }

    /**
     * Returns an immutable response view containing every matching placement, its complete descendant branch and the
     * ancestors needed to preserve its serialized path. This is useful for selecting independently addressed branches:
     * matching a parent retains its whole subtree, while matching a leaf retains only that leaf and its ancestors.
     * The graph is traversed once; retained model values are shared and never copied.
     */
    default Graph<T> filterBranches(Predicate<? super Graph<?>> predicate) {
        return Graphs.filterBranches(this, predicate);
    }

    /** Returns direct children of the requested type. */
    <C> List<Graph<C>> children(Class<C> childType);

    /** Returns direct children placed at the requested explicit relationship path. */
    <C> List<Graph<C>> children(String path, Class<C> childType);

    /** Returns direct child values of the requested type. */
    default <C> List<C> childModels(Class<C> childType) {
        return children(childType).stream().map(Graph::get).filter(Objects::nonNull).toList();
    }

    /** Returns direct child values placed at the requested explicit relationship path. */
    default <C> List<C> childModels(String path, Class<C> childType) {
        return children(path, childType).stream().map(Graph::get).filter(Objects::nonNull).toList();
    }

    /** Returns all descendants assignable to the requested type in deterministic graph order. */
    <D> List<Graph<D>> descendants(Class<D> descendantType);

    /** Returns descendants reached through the requested relationship path. */
    <D> List<Graph<D>> descendants(String path, Class<D> descendantType);

    /** Returns all descendant values assignable to the requested type. */
    default <D> List<D> descendantModels(Class<D> descendantType) {
        return descendants(descendantType).stream().map(Graph::get).filter(Objects::nonNull).toList();
    }

    /** Returns descendant values reached through the requested relationship path. */
    default <D> List<D> descendantModels(String path, Class<D> descendantType) {
        return descendants(path, descendantType).stream().map(Graph::get).filter(Objects::nonNull).toList();
    }

    /**
     * Lazily traverses this graph in deterministic pre-order, including this graph itself. Relationship context is
     * loaded only when the returned stream is consumed.
     */
    default Stream<Graph<?>> stream() {
        Iterator<Graph<?>> iterator = new Iterator<>() {
            private final Deque<Iterator<Graph<?>>> remaining = new ArrayDeque<>();
            private Graph<?> next = Graph.this;
            private Graph<?> expandAfterReturn;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                if (expandAfterReturn != null) {
                    remaining.addLast(expandAfterReturn.children().iterator());
                    expandAfterReturn = null;
                }
                while (!remaining.isEmpty()) {
                    Iterator<Graph<?>> siblings = remaining.peekLast();
                    if (siblings.hasNext()) {
                        next = siblings.next();
                        return true;
                    }
                    remaining.removeLast();
                }
                return false;
            }

            @Override
            public Graph<?> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Graph<?> result = next;
                next = null;
                expandAfterReturn = result;
                return result;
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /** Finds the first graph in deterministic graph order matching the supplied condition. */
    default Optional<Graph<?>> find(Predicate<? super Graph<?>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return stream().filter(predicate).findFirst();
    }

    /**
     * Finds a graph by exact persisted identity or alias. Exact identities take precedence over aliases throughout
     * the complete graph, even when an earlier graph owns a colliding alias.
     */
    default Optional<Graph<?>> find(Object idOrAlias) {
        if (idOrAlias == null) {
            return Optional.empty();
        }
        String requested = idOrAlias.toString();
        Graph<?> aliasMatch = null;
        var iterator = stream().iterator();
        while (iterator.hasNext()) {
            Graph<?> candidate = iterator.next();
            if (candidate.id() != null && requested.equals(candidate.id().toString())) {
                return Optional.of(candidate);
            }
            if (aliasMatch == null && matchesAlias(candidate, requested)) {
                aliasMatch = candidate;
            }
        }
        return Optional.ofNullable(aliasMatch);
    }

    /**
     * Finds a graph by functional identity or alias and expected model type. The expected type applies the same
     * {@link EntityId} and nested {@link Id} affixes as a typed model load.
     */
    default <M> Optional<Graph<M>> find(Object idOrAlias, Class<M> modelType) {
        if (idOrAlias == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(modelType, "modelType");
        String requested = idOrAlias.toString();
        String repositoryId = ModelMetadata.of(modelType).repositoryId(idOrAlias);
        Graph<M> aliasMatch = null;
        var iterator = stream().iterator();
        while (iterator.hasNext()) {
            Graph<?> candidate = iterator.next();
            if (!modelType.isAssignableFrom(candidate.type())) {
                continue;
            }
            @SuppressWarnings("unchecked") Graph<M> typed = (Graph<M>) candidate;
            if (candidate.id() != null && repositoryId.equals(candidate.id().toString())) {
                return Optional.of(typed);
            }
            if (aliasMatch == null && matchesAlias(candidate, requested)) {
                aliasMatch = typed;
            }
        }
        return Optional.ofNullable(aliasMatch);
    }

    private static boolean matchesAlias(Graph<?> graph, String requested) {
        return graph.aliases().stream().filter(Objects::nonNull)
                .map(Object::toString).anyMatch(requested::equals);
    }

    /** Applies one update to this graph's current model and returns the staged resulting graph. */
    Graph<T> apply(Object update);

    /** Applies one update with explicit metadata. */
    Graph<T> apply(Object update, Metadata metadata);

    /** Applies a deserializing message. */
    Graph<T> apply(DeserializingMessage update);

    /** Applies a complete message. */
    Graph<T> apply(Message update);

    /** Applies the supplied updates in order. */
    Graph<T> apply(Object... updates);

    /** Applies the supplied updates in order. */
    Graph<T> apply(Collection<?> updates);

    /** Updates the current value directly. Prefer {@link #apply(Object)} for domain updates. */
    Graph<T> update(UnaryOperator<T> update);

    /**
     * Marks this model as deleted and returns the staged resulting graph. Return it from model handling so it joins the
     * surrounding commit, or call {@link #commit()} for an explicit graph operation.
     */
    default Graph<T> delete() {
        return update(ignored -> null);
    }

    /** Explicitly commits staged changes. Normal handler processing commits automatically. */
    Graph<T> commit();

    /** Verifies that the supplied update is legal and returns this graph. */
    <E extends Exception> Graph<T> assertLegal(Object update) throws E;

    /** Verifies and applies the supplied update. */
    Graph<T> assertAndApply(Object update);

    /** Verifies and applies the supplied update with explicit metadata. */
    Graph<T> assertAndApply(Object update, Metadata metadata);

    /** Verifies and applies the supplied updates in order. */
    default Graph<T> assertAndApply(Object... updates) {
        return assertAndApply(List.of(updates));
    }

    /** Verifies and applies the supplied updates in order. */
    default Graph<T> assertAndApply(Collection<?> updates) {
        Objects.requireNonNull(updates, "updates");
        Graph<T> result = this;
        for (Object update : updates) {
            result = result.assertAndApply(update);
        }
        return result;
    }

    /**
     * Returns the preceding model revision as a lazy graph, or {@code null} when none is retained.
     * Surrounding models and relationships are resolved immediately before the current revision became effective.
     * This keeps children added after the preceding root revision visible while excluding changes made by the update
     * whose before-state is being observed.
     */
    @Nullable
    Graph<T> previous();

    /** Returns current and retained preceding revisions, newest first. */
    default Stream<Graph<T>> revisions() {
        return Stream.iterate(this, Objects::nonNull, Graph::previous);
    }

    /** Returns the newest event index retained by this graph's revision history. */
    @Nullable
    default Long highestEventIndex() {
        Graph<T> revision = this;
        while (revision != null) {
            if (revision.lastEventIndex() != null) {
                return revision.lastEventIndex();
            }
            revision = revision.previous();
        }
        return null;
    }

    /** Returns the same model graph reconstructed at the requested durable state boundary. */
    Graph<T> atStateIndex(long stateIndex);

    /** Plays back to the first retained revision matching the supplied event boundary. */
    Optional<Graph<T>> playBackToEvent(Long eventIndex, String eventId);

    /** Plays back to the first retained revision matching the supplied condition. */
    Optional<Graph<T>> playBackToCondition(Predicate<Graph<T>> condition);

    /** Returns whether the selected value differs from the preceding revision. */
    default boolean hasChanged(Function<? super T, ?> selector) {
        Objects.requireNonNull(selector, "selector");
        Graph<T> previous = previous();
        return !Objects.equals(get() == null ? null : selector.apply(get()),
                               previous == null || previous.get() == null ? null : selector.apply(previous.get()));
    }

    /** Returns the selected value from the preceding revision, or {@code null} when unavailable. */
    @Nullable
    default <V> V previousValue(Function<? super T, V> selector) {
        Objects.requireNonNull(selector, "selector");
        Graph<T> previous = previous();
        return previous == null || previous.get() == null ? null : selector.apply(previous.get());
    }

    /** Optional caller-imposed graph reconstruction limits. */
    record Options(int maxDepth, int maxModels) {
        /** Uses no caller-imposed graph limits. */
        public static final Options DEFAULT = new Options(UNBOUNDED, UNBOUNDED);

        public Options {
            if (maxDepth != UNBOUNDED && maxDepth < 0) {
                throw new IllegalArgumentException(
                        "Graph maxDepth must be non-negative or UNBOUNDED (-1)");
            }
            if (maxModels != UNBOUNDED && maxModels < 1) {
                throw new IllegalArgumentException(
                        "Graph maxModels must be positive or UNBOUNDED (-1)");
            }
        }
    }
}
