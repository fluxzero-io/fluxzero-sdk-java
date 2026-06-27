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

import io.fluxzero.sdk.persisting.eventsourcing.Apply;

/**
 * Strategy for controlling how applied updates (typically from {@link Apply @Apply} methods)
 * are handled in terms of event storage, event publication, and aggregate state updates.
 * <p>
 * This strategy determines whether an update is published to the global event log, stored in the aggregate event store,
 * and whether its apply result should advance the aggregate state in cache, snapshots, relationships, and search
 * indexing.
 * It can be configured at the aggregate level, or overridden per update.
 *
 * @see Apply
 * @see Aggregate
 * @see EventPublication
 */
public enum EventPublicationStrategy {

    /**
     * Inherit the strategy from the enclosing aggregate or global default.
     * <p>
     * If not configured anywhere, the fallback is {@link #STORE_AND_PUBLISH}.
     */
    DEFAULT,

    /**
     * Store the applied update in the aggregate event store, publish it to the global event log, and update aggregate
     * state.
     * <p>
     * This is the default behavior used for event-sourced aggregates.
     */
    STORE_AND_PUBLISH,

    /**
     * Store the applied update in the aggregate event store and update aggregate state, but do not publish it to the
     * global event log.
     * <p>
     * Useful when updates must be persisted but should not trigger side effects or listeners.
     */
    STORE_ONLY,

    /**
     * Publish the update to the global event log but do not store it in the aggregate event store.
     * <p>
     * This disables event sourcing for the update. On event-sourced aggregates, the apply result does not advance
     * aggregate state in cache, snapshots, or search indexing because replay would not be able to reconstruct it. On
     * non-event-sourced, document-backed aggregates, the apply result still updates aggregate/search state.
     */
    PUBLISH_ONLY
}
