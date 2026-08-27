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

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.modeling.AggregateEventRouting.AGGREGATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelEventRoutingTest {

    @Test
    void modelEventUsesPayloadRoutingKey() {
        String modelId = "model-id";
        String routingKey = "event-routing-key";

        TestFixture.create(RoutedModel.class)
                .whenCommand(new CreateRoutedModel(modelId, routingKey))
                .expectThat(fluxzero -> assertEquals(
                        ConsistentHashing.computeSegment(routingKey),
                        fluxzero.client().getEventStoreClient().getEvents(modelId, -1L)
                                .findFirst().orElseThrow().getSegment()));
    }

    @Model
    private record RoutedModel(@EntityId String id) {
    }

    private record CreateRoutedModel(String id, @RoutingKey String routingKey) {
        @Apply(eventRouting = AGGREGATE_ID)
        RoutedModel apply() {
            return new RoutedModel(id);
        }
    }
}
