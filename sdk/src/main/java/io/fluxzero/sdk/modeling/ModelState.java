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

import io.fluxzero.common.api.modeling.ModelHeadState;

import java.util.Objects;
import java.util.Optional;

/**
 * A document-backed current Model value verified against a durable head at {@link #stateIndex()}.
 * This is an explicit read-only state contract, not a replayed Entity or a transactional Graph. It neither loads
 * history nor registers commit read dependencies. Use injected Models/Graphs for transactional invariants.
 * Missing Models have a null head; deleted Models have a deleted head. Both have a null value.
 * Repository reads require a matching Runtime and proof captured with trusted Model document materialization;
 * older unproven documents and ordinary search overwrites fail verification rather than falling back to replay.
 *
 * @param id exact persisted Model identity
 * @param type requested Java state contract
 * @param value stored current value, or null for a missing/deleted Model
 * @param head durable version observed with the document, or null for a missing Model
 * @param stateIndex namespace-wide boundary at which the version was verified
 * @param <T> state value type
 */
public record ModelState<T>(String id, Class<T> type, T value, ModelHeadState head, long stateIndex) {
    public ModelState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (head != null && (!id.equals(head.getModelId()) || head.getStateIndex() > stateIndex)
            || (head == null || head.isDeleted()) != (value == null)
            || value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("Model state does not match its observed head");
        }
    }

    /** Returns the current value, or null for a missing/deleted Model. */
    public T get() { return value; }

    /** Returns whether a live value was observed. */
    public boolean isPresent() { return value != null; }

    /** Returns the current value as an optional. */
    public Optional<T> optional() { return Optional.ofNullable(value); }
}
