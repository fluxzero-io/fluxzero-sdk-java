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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.correlation.DefaultCorrelationDataProvider;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityParameterResolverNamespaceTest {

    @Test
    void explicitLoadsUseApplicationNamespaceWhileParameterInjectionUsesConsumerNamespace() throws Exception {
        var fluxzero = TestFixture.create().getFluxzero();
        fluxzero.apply(fc -> {
            Invocation.performInvocation(() -> {
                fc.aggregateRepository().load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "application"));
                fc.aggregateRepository().forNamespace("customer").load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "customer"));
                return null;
            });

            Method handlerMethod = Handler.class.getDeclaredMethod("handle", NamespacedAggregate.class);
            Parameter parameter = handlerMethod.getParameters()[0];
            DeserializingMessage message = new DeserializingMessage(
                    new Message(new Trigger(), Metadata.of(
                            Entity.AGGREGATE_ID_METADATA_KEY, "shared",
                            Entity.AGGREGATE_TYPE_METADATA_KEY, NamespacedAggregate.class.getName())),
                    MessageType.EVENT, fc.serializer());
            message.putContext(ConsumerConfiguration.class, ConsumerConfiguration.builder()
                    .name("customer-events").namespace("customer").build());

            message.run(current -> {
                assertEquals("application",
                             Fluxzero.loadAggregate("shared", NamespacedAggregate.class).get().value());
                NamespacedAggregate injected = (NamespacedAggregate) new EntityParameterResolver()
                        .resolve(parameter, handlerMethod.getAnnotation(HandleEvent.class)).apply(current);
                assertEquals("customer", injected.value());
            });
            return null;
        });
    }

    @Test
    void localEventEntityInjectionUsesApplicationNamespaceInsideCustomTracker() {
        AtomicReference<String> injectedValue = new AtomicReference<>();
        AtomicReference<String> triggerNamespace = new AtomicReference<>();
        var fluxzero = TestFixture.create(new LocalEntityHandler(injectedValue, triggerNamespace)).getFluxzero();
        fluxzero.apply(fc -> {
            Invocation.performInvocation(() -> {
                fc.aggregateRepository().load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "application"));
                fc.aggregateRepository().forNamespace("customer").load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "customer"));
                return null;
            });
            Tracker.current.set(new Tracker("customer-tracker", MessageType.EVENT, null,
                                            ConsumerConfiguration.builder().name("customer-events")
                                                    .namespace("customer").build(), null));
            try {
                DeserializingMessage customerSource = new DeserializingMessage(
                        new Message("source"), MessageType.EVENT, fc.serializer());
                customerSource.run(ignored -> fc.eventGateway().publish(new Message(new Trigger(), Metadata.of(
                        Entity.AGGREGATE_ID_METADATA_KEY, "shared",
                        Entity.AGGREGATE_TYPE_METADATA_KEY, NamespacedAggregate.class.getName()))));
            } finally {
                Tracker.current.remove();
            }
            return null;
        });

        assertEquals("application", injectedValue.get());
        assertEquals("customer", triggerNamespace.get());
    }

    @Test
    void localEventEntityInjectionUsesCustomGatewayNamespace() {
        AtomicReference<String> injectedValue = new AtomicReference<>();
        var fluxzero = TestFixture.create(new LocalEntityHandler(injectedValue, new AtomicReference<>())).getFluxzero();
        fluxzero.apply(fc -> {
            Invocation.performInvocation(() -> {
                fc.aggregateRepository().load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "application"));
                fc.aggregateRepository().forNamespace("customer").load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "customer"));
                return null;
            });
            fc.eventGateway().forNamespace("customer").publish(new Message(new Trigger(), Metadata.of(
                    Entity.AGGREGATE_ID_METADATA_KEY, "shared",
                    Entity.AGGREGATE_TYPE_METADATA_KEY, NamespacedAggregate.class.getName())));
            return null;
        });

        assertEquals("customer", injectedValue.get());
    }

    @Test
    void nonEventEntityInjectionUsesApplicationNamespaceInsideCustomConsumer() throws Exception {
        var fluxzero = TestFixture.create().getFluxzero();
        fluxzero.apply(fc -> {
            Invocation.performInvocation(() -> {
                fc.aggregateRepository().load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "application"));
                fc.aggregateRepository().forNamespace("customer").load("shared", NamespacedAggregate.class)
                        .assertAndApply(new SetValue("shared", "customer"));
                return null;
            });
            Method method = CommandEntityHandler.class.getDeclaredMethod("handle", NamespacedAggregate.class);
            DeserializingMessage message = new DeserializingMessage(new Message(new Trigger(), Metadata.of(
                    Entity.AGGREGATE_ID_METADATA_KEY, "shared",
                    Entity.AGGREGATE_TYPE_METADATA_KEY, NamespacedAggregate.class.getName())),
                                                                      MessageType.COMMAND, fc.serializer())
                    .putContext(ConsumerConfiguration.class, ConsumerConfiguration.builder()
                            .name("customer-commands").namespace("customer").build());

            message.run(current -> {
                NamespacedAggregate injected = (NamespacedAggregate) new EntityParameterResolver()
                        .resolve(method.getParameters()[0], method.getAnnotation(HandleCommand.class)).apply(current);
                assertEquals("application", injected.value());
            });
            return null;
        });
    }

    record Trigger() {
    }

    record SetValue(String aggregateId, String value) {
    }

    @Aggregate(snapshotPeriod = 1)
    record NamespacedAggregate(@EntityId String aggregateId, String value) {
        @Apply
        static NamespacedAggregate apply(SetValue event) {
            return new NamespacedAggregate(event.aggregateId(), event.value());
        }
    }

    static class Handler {
        @HandleEvent
        void handle(NamespacedAggregate aggregate) {
        }
    }

    static class CommandEntityHandler {
        @HandleCommand
        void handle(NamespacedAggregate aggregate) {
        }
    }

    @LocalHandler
    record LocalEntityHandler(AtomicReference<String> injectedValue, AtomicReference<String> triggerNamespace) {
        @HandleEvent
        void handle(Trigger event, NamespacedAggregate aggregate, DeserializingMessage message) {
            injectedValue.set(aggregate.value());
            triggerNamespace.set(message.getMetadata().get(
                    DefaultCorrelationDataProvider.INSTANCE.getTriggerNamespaceKey()));
        }
    }
}
