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

import java.time.Instant;

/**
 * Represents the root of a legacy aggregate in a domain model.
 * <p>
 * This compatibility specialization retains aggregate vocabulary while the shared persisted-root contract lives in
 * {@link ModelRoot}. Existing aggregate implementations and callers remain valid.
 *
 * @param <T> the type of the underlying domain object
 *
 * @see Entity
 * @see Aggregate
 * @see ModelRoot
 */
public interface AggregateRoot<T> extends ModelRoot<T> {

    @Override
    default Entity<?> parent() {
        return null;
    }

    @Override
    String lastEventId();

    @Override
    Long lastEventIndex();

    @Override
    Entity<T> withEventIndex(Long index, String messageId);

    @Override
    long sequenceNumber();

    @Override
    Entity<T> withSequenceNumber(long sequenceNumber);

    @Override
    Instant timestamp();

    @Override
    Entity<T> previous();
}
