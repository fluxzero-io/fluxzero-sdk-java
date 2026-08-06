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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.fluxzero.common.api.search.ModelGraphComposition.UNBOUNDED;

/**
 * A typed view of one independently stored model and its lazily available relationship context.
 * <p>
 * Merely resolving a graph loads no more state than resolving the corresponding model value. Parent, child and
 * descendant state is loaded only when it is requested, at the same pinned model-state boundary. Every returned child
 * is itself a graph view, so root, parent, history and update operations remain available without exposing the
 * persistence-only {@link Entity} wrapper.
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

    /** Returns the functional identifier object carried by the model wrapper. */
    Object id();

    /** Returns the concrete model type. */
    Class<T> type();

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

    /** Returns the parent of this concrete graph placement, if one exists. */
    Optional<Graph<?>> parent();

    /** Returns the closest parent assignable to the requested type. */
    <P> Optional<Graph<P>> parent(Class<P> parentType);

    /** Returns the closest ancestor, including the current graph, assignable to the requested type. */
    <A> Optional<Graph<A>> ancestor(Class<A> ancestorType);

    /** Returns all direct children in deterministic relationship-path order. */
    List<Graph<?>> children();

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

    /** Explicitly commits staged changes. Normal handler processing commits automatically. */
    Graph<T> commit();

    /** Verifies that the supplied update is legal and returns this graph. */
    <E extends Exception> Graph<T> assertLegal(Object update) throws E;

    /** Verifies and applies the supplied update. */
    Graph<T> assertAndApply(Object update);

    /** Verifies and applies the supplied update with explicit metadata. */
    Graph<T> assertAndApply(Object update, Metadata metadata);

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
