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

package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;

import static io.fluxzero.sdk.Fluxzero.currentTime;

/**
 * Shared immutable revision state of persisted Aggregate and Model roots.
 * <p>
 * Storage-specific roots add only metadata that is genuinely absent from the other contract: Aggregate relationship
 * replay state or the Model namespace-wide state boundary. Jackson continues to expose these inherited properties as
 * the same flat snapshot fields.
 */
@Getter
@SuperBuilder(toBuilder = true)
@Accessors(fluent = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(callSuper = true)
public abstract class ImmutableRoot<T> extends ImmutableEntity<T> implements PersistedRoot<T> {
    /** Source-visible builder owner shared by Aggregate and Model roots. */
    public abstract static class ImmutableRootBuilder<
            T, C extends ImmutableRoot<T>,
            B extends ImmutableRootBuilder<T, C, B>>
            extends ImmutableEntity.ImmutableEntityBuilder<T, C, B> {
    }

    @JsonProperty
    @EqualsAndHashCode.Include
    private final String lastEventId;
    @JsonProperty
    @EqualsAndHashCode.Include
    private final Long lastEventIndex;
    @JsonProperty
    @Builder.Default
    @EqualsAndHashCode.Include
    private final Instant timestamp = currentTime();
    @JsonProperty
    @Builder.Default
    @EqualsAndHashCode.Include
    private final long sequenceNumber = -1L;

    @ToString.Exclude
    @JsonIgnore
    private final transient Entity<T> previous;

    /** Replays all events with one isolated loading context. */
    public static <S, E> S replay(
            S initial, Iterator<E> events,
            Transition<S, E> transition,
            ReplayFailure<S, E> failure) {
        return replayUntil(initial, events, (state, event) -> true, transition, failure);
    }

    /** Replays the ordered prefix accepted by {@code include} with one isolated loading context. */
    public static <S, E> S replayUntil(
            S initial, Iterator<E> events,
            ReplayContinuation<S, E> include,
            Transition<S, E> transition,
            ReplayFailure<S, E> failure) {
        boolean wasLoading = Entity.isLoading();
        var previousRoutes = new LinkedHashMap<>(ImmutableEntity.snapshotLoadingRouteCache());
        var previousEntities = new LinkedHashMap<>(AnnotatedEntityHolder.snapshotLoadingEntityCache());
        var previousRouteValues = new LinkedHashMap<>(AnnotatedEntityHolder.snapshotLoadingRouteValuesCache());
        try {
            Entity.loading.set(true);
            ImmutableEntity.clearLoadingRouteCache();
            AnnotatedEntityHolder.clearLoadingEntityCache();
            AnnotatedEntityHolder.clearLoadingRouteValuesCache();
            S state = initial;
            while (events.hasNext()) {
                E event = events.next();
                if (!include.test(state, event)) {
                    break;
                }
                try {
                    state = transition.apply(state, event);
                } catch (Throwable error) {
                    throw failure.map(state, event, error);
                }
            }
            return state;
        } finally {
            AnnotatedEntityHolder.restoreLoadingRouteValuesCache(previousRouteValues);
            AnnotatedEntityHolder.restoreLoadingEntityCache(previousEntities);
            ImmutableEntity.restoreLoadingRouteCache(previousRoutes);
            Entity.loading.set(wasLoading);
        }
    }

    /** Returns this root revision with a different in-memory predecessor. */
    public abstract ImmutableRoot<T> withPrevious(Entity<T> previous);

    /**
     * Applies the shared bounded revision-retention policy used by persisted roots.
     */
    public static Entity<?> retainPrevious(
            Entity<?> previous, EntityMetadata.RootConfiguration configuration) {
        if (previous == null || !configuration.cached()
            || !configuration.eventSourced() || configuration.cachingDepth() == 0) {
            return null;
        }
        return configuration.cachingDepth() < 0
                ? previous : truncatePrevious(previous, configuration.cachingDepth() - 1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Entity<?> truncatePrevious(Entity<?> revision, int remainingDepth) {
        if (!(revision instanceof ImmutableRoot root)) {
            return revision;
        }
        Entity<?> previous = remainingDepth <= 0
                ? null : truncatePrevious(root.previous(), remainingDepth - 1);
        return root.previous() == previous ? root : root.withPrevious((Entity) previous);
    }

    protected ImmutableRoot(
            Object id, Class<T> type, String idProperty, T value,
            EntityHelper entityHelper, io.fluxzero.sdk.common.serialization.Serializer serializer,
            String lastEventId, Long lastEventIndex, Instant timestamp,
            long sequenceNumber, Entity<T> previous) {
        super(id, type, value, idProperty, null, null, entityHelper, serializer);
        this.lastEventId = lastEventId;
        this.lastEventIndex = lastEventIndex;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.previous = previous;
    }

    protected ImmutableRoot(
            ImmutableRoot<T> source, String lastEventId, Long lastEventIndex,
            long sequenceNumber, Entity<T> previous) {
        super(source);
        this.lastEventId = lastEventId;
        this.lastEventIndex = lastEventIndex;
        this.timestamp = source.timestamp;
        this.sequenceNumber = sequenceNumber;
        this.previous = previous;
    }

    @FunctionalInterface
    public interface Transition<S, E> {
        S apply(S state, E event) throws Throwable;
    }

    @FunctionalInterface
    public interface ReplayContinuation<S, E> {
        boolean test(S state, E event);
    }

    @FunctionalInterface
    public interface ReplayFailure<S, E> {
        RuntimeException map(S state, E event, Throwable error);
    }
}
