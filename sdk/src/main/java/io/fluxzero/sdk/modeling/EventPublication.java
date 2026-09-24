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
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;

/**
 * Controls whether an applied update should result in event publication.
 * <p>
 * This setting can be defined at the aggregate or independent-model level (via {@link Aggregate} or {@link Model})
 * and optionally overridden on specific {@link Apply @Apply} methods.
 * <p>
 * Event publication occurs when an update is applied to an entity. Depending on this setting and the configured
 * {@link EventPublicationStrategy}, the update may be published and/or stored.
 *
 * @see Aggregate
 * @see Model
 * @see Apply
 * @see InterceptApply
 * @see EventPublicationStrategy
 */
public enum EventPublication {

    /**
     * Inherit the publication behavior from the parent context.
     * <p>
     * This may be the enclosing aggregate, model, or application-level default. If not explicitly configured
     * elsewhere, aggregates fall back to {@link #ALWAYS}; independent models fall back to {@link #IF_MODIFIED}.
     */
    DEFAULT,

    /**
     * Always publish and/or store the applied update, even if it does not change the entity.
     * <p>
     * This is the default behavior for aggregates if no other setting is specified.
     */
    ALWAYS,

    /**
     * Never publish or store applied updates.
     * <p>
     * Useful for document-backed models or aggregates when the state change deliberately has no stored or published
     * event. Event-sourced state-changing updates reject this value because they could not be reconstructed later.
     */
    NEVER,

    /**
     * Only publish or store the update if the entity's state is actually modified by the application.
     * <p>
     * This avoids unnecessary event traffic and eliminates the need for {@link InterceptApply} methods that suppress
     * no-op updates.
     */
    IF_MODIFIED
}
