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

/**
 * Controls how events published from an aggregate are assigned to a message segment.
 */
public enum AggregateEventRouting {

    /**
     * Use the enclosing aggregate's configured event routing. At the aggregate level, this behaves like
     * {@link #MESSAGE_ROUTING_KEY}.
     */
    DEFAULT,

    /**
     * Preserve the regular message routing behavior. If the event payload has a
     * {@link io.fluxzero.sdk.publishing.routing.RoutingKey @RoutingKey}, that value determines the segment.
     */
    MESSAGE_ROUTING_KEY,

    /**
     * Route aggregate events by the aggregate id, regardless of routing annotations on the event payload.
     */
    AGGREGATE_ID
}
