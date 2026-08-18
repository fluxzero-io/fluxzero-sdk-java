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

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.NonFinal;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * Immutable entity wrapper for one independently stored model revision.
 * <p>
 * Model sequence numbers are local to the model stream. {@link #stateIndex()} is the namespace-wide state boundary at
 * which this revision became current; it is deliberately distinct from the global event-log index exposed through
 * {@link #lastEventIndex()}.
 *
 * @param <T> model value type
 */
@Value
@NonFinal
@SuperBuilder(toBuilder = true)
@Accessors(fluent = true)
@Jacksonized
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ImmutableModelRoot<T> extends ImmutableRoot<T>
        implements ModelRoot<T> {
    @JsonProperty
    @Builder.Default
    @EqualsAndHashCode.Include
    long stateIndex = -1L;

    /** Keeps the original builder descriptors while delegating their state to {@link ImmutableRoot}. */
    public abstract static class ImmutableModelRootBuilder<
            T, C extends ImmutableModelRoot<T>,
            B extends ImmutableModelRootBuilder<T, C, B>>
            extends ImmutableRoot.ImmutableRootBuilder<T, C, B> {
        @Override
        public B lastEventId(String value) {
            return super.lastEventId(value);
        }

        @Override
        public B lastEventIndex(Long value) {
            return super.lastEventIndex(value);
        }

        @Override
        public B timestamp(Instant value) {
            return super.timestamp(value);
        }

        @Override
        public B sequenceNumber(long value) {
            return super.sequenceNumber(value);
        }

        @Override
        public B previous(Entity<T> value) {
            return super.previous(value);
        }
    }

    private ImmutableModelRoot(
            Object id,
            Class<T> type,
            String idProperty,
            T value,
            EntityHelper entityHelper,
            io.fluxzero.sdk.common.serialization.Serializer serializer,
            String lastEventId,
            Long lastEventIndex,
            Instant timestamp,
            long sequenceNumber,
            long stateIndex,
            Entity<T> previous) {
        super(id, type, idProperty, value, entityHelper, serializer,
              lastEventId, lastEventIndex, timestamp, sequenceNumber, previous);
        this.stateIndex = stateIndex;
    }

    private ImmutableModelRoot(
            ImmutableModelRoot<T> source,
            String lastEventId,
            Long lastEventIndex,
            long sequenceNumber,
            Entity<T> previous) {
        super(source, lastEventId, lastEventIndex, sequenceNumber, previous);
        this.stateIndex = source.stateIndex;
    }

    /**
     * Creates a model root from an authoritative committed revision without consulting the ambient clock.
     */
    public static <T> ImmutableModelRoot<T> committed(
            Object id,
            Class<T> type,
            String idProperty,
            T value,
            EntityHelper entityHelper,
            io.fluxzero.sdk.common.serialization.Serializer serializer,
            String lastEventId,
            Long lastEventIndex,
            Instant timestamp,
            long sequenceNumber,
            long stateIndex,
            Entity<T> previous) {
        return new ImmutableModelRoot<>(
                id, type, idProperty, value,
                entityHelper, serializer,
                lastEventId, lastEventIndex,
                timestamp, sequenceNumber, stateIndex, previous);
    }

    @Override
    public Entity<T> withEventIndex(Long index, String messageId) {
        return new ImmutableModelRoot<>(
                this, messageId, index,
                sequenceNumber(), previous());
    }

    @Override
    public Entity<T> withSequenceNumber(long sequenceNumber) {
        return new ImmutableModelRoot<>(
                this, lastEventId(), lastEventIndex(),
                sequenceNumber, previous());
    }

    /**
     * Returns this revision with the supplied in-memory predecessor.
     */
    @Override
    public ImmutableModelRoot<T> withPrevious(Entity<T> previous) {
        return new ImmutableModelRoot<>(
                this, lastEventId(), lastEventIndex(),
                sequenceNumber(), previous);
    }
}
