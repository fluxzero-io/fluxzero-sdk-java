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
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.sdk.modeling.AggregateEventRouting.AGGREGATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelEventRoutingTest {

    @Test
    void singleModelCommandAndEventUseTheSameCanonicalTargetRoute() {
        AtomicReference<Integer> commandSegment = new AtomicReference<>();
        DefaultFluxzero.Builder builder = routingBuilder().addDispatchInterceptor(new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                return message;
            }

            @Override
            public SerializedMessage modifySerializedMessage(
                    SerializedMessage serialized, Message message, MessageType type, String topic) {
                commandSegment.set(serialized.getSegment());
                return serialized;
            }
        }, MessageType.COMMAND);
        TestFixture.createAsync(builder, PrefixedModel.class)
                .whenCommand(new CreatePrefixedModel(new PrefixedId("same")))
                .expectThat(fluxzero -> {
                    int expected = ConsistentHashing.computeSegment("model-prefix-typed-same");
                    assertEquals(expected, commandSegment.get());
                    assertEquals(expected, fluxzero.client().getEventStoreClient()
                            .getEvents("model-prefix-typed-same", -1L)
                            .findFirst().orElseThrow().getSegment());
                });
    }

    @Test
    void interceptedModelEventUsesItsActualTargetInsteadOfTheCommandRoute() {
        TestFixture.createAsync(routingBuilder(), PrefixedModel.class)
                .whenCommand(new RedirectCreate(new PrefixedId("original"), new PrefixedId("actual"), "tenant"))
                .expectThat(fluxzero -> assertEquals(
                        ConsistentHashing.computeSegment("model-prefix-typed-actual"),
                        fluxzero.client().getEventStoreClient().getEvents("model-prefix-typed-actual", -1L)
                                .findFirst().orElseThrow().getSegment()));
    }

    @Test
    void explicitMissingRoutingFieldSuppressesModelEventFallback() {
        TestFixture.create(routingBuilder(), RoutedModel.class)
                .whenCommand(new CreateRoutedModel("explicit-missing", null))
                .expectThat(fluxzero -> assertNull(fluxzero.client().getEventStoreClient()
                        .getEvents("explicit-missing", -1L).findFirst().orElseThrow().getSegment()));
    }

    @Test
    void explicitMetadataRoutingWinsOverModelEventFallback() {
        TestFixture.create(routingBuilder(), RoutedModel.class)
                .whenCommand(new Message(new CreateMetadataRoutedModel("metadata"), Metadata.of("route", "tenant")))
                .expectThat(fluxzero -> assertEquals(ConsistentHashing.computeSegment("tenant"),
                        fluxzero.client().getEventStoreClient().getEvents("metadata", -1L)
                                .findFirst().orElseThrow().getSegment()));
    }

    @Test
    void explicitMissingMetadataRoutingSuppressesModelEventFallback() {
        TestFixture.create(routingBuilder(), RoutedModel.class)
                .whenCommand(new CreateMetadataRoutedModel("missing-metadata"))
                .expectThat(fluxzero -> assertNull(fluxzero.client().getEventStoreClient()
                        .getEvents("missing-metadata", -1L).findFirst().orElseThrow().getSegment()));
    }

    @Test
    void multipleTargetsDoNotPickAnArbitraryEventRoute() {
        TestFixture.create(routingBuilder(), RoutedModel.class, OtherModel.class)
                .whenCommand(new CreateBoth("first", "second"))
                .expectThat(fluxzero -> assertNull(fluxzero.client().getEventStoreClient()
                        .getEvents("first", -1L).findFirst().orElseThrow().getSegment()));
    }

    @Test
    void eventFallbackLeavesExplicitInterceptorSegmentsUntouched() {
        DefaultFluxzero.Builder builder = routingBuilder().addDispatchInterceptor(new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                return message;
            }

            @Override
            public SerializedMessage modifySerializedMessage(
                    SerializedMessage serialized, Message message, MessageType type, String topic) {
                serialized.setSegment(42);
                return serialized;
            }
        }, MessageType.EVENT);
        TestFixture.create(builder, PrefixedModel.class)
                .whenCommand(new CreatePrefixedModel(new PrefixedId("explicit")))
                .expectThat(fluxzero -> assertEquals(42, fluxzero.client().getEventStoreClient()
                        .getEvents("model-prefix-typed-explicit", -1L).findFirst().orElseThrow().getSegment()));
    }

    @Test
    void compatibilityDefaultsDoNotAddModelEventRouting() {
        TestFixture.create(PrefixedModel.class)
                .whenCommand(new CreatePrefixedModel(new PrefixedId("old")))
                .expectThat(fluxzero -> assertNull(fluxzero.client().getEventStoreClient()
                        .getEvents("model-prefix-typed-old", -1L).findFirst().orElseThrow().getSegment()));
    }

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

    private static DefaultFluxzero.Builder routingBuilder() {
        DefaultFluxzero.Builder builder = DefaultFluxzero.builder();
        builder.replacePropertySource(existing -> new SimplePropertySource(
                java.util.Map.of("fluxzero.model.automaticRouting", "true")).andThen(existing));
        return builder;
    }

    private record CreateRoutedModel(String id, @RoutingKey String routingKey) {
        @Apply(eventRouting = AGGREGATE_ID)
        RoutedModel apply() {
            return new RoutedModel(id);
        }
    }

    @RoutingKey("route")
    private record CreateMetadataRoutedModel(String id) {
        @Apply
        RoutedModel apply() {
            return new RoutedModel(id);
        }
    }

    @Model
    private record PrefixedModel(@EntityId(prefix = "model-prefix-") PrefixedId id) {
    }

    private static class PrefixedId extends Id<PrefixedModel> {
        private PrefixedId(String id) {
            super(id, "typed-");
        }
    }

    private record CreatePrefixedModel(PrefixedId id) {
        @Apply
        PrefixedModel apply() {
            return new PrefixedModel(id);
        }
    }

    private record RedirectCreate(PrefixedId id, PrefixedId actual, @RoutingKey String route) {
        @InterceptApply
        CreatePrefixedModel intercept() {
            return new CreatePrefixedModel(actual);
        }
    }

    @Model
    private record OtherModel(@EntityId String otherId) {
    }

    private record CreateBoth(String id, String otherId) {
        @Apply
        RoutedModel first() {
            return new RoutedModel(id);
        }

        @Apply
        OtherModel second() {
            return new OtherModel(otherId);
        }
    }
}
