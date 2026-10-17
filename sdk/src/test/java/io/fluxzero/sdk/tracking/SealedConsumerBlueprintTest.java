/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */
package io.fluxzero.sdk.tracking;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.sdk.tracking.BatchInterceptor.preservingConsumerFilter;
import static org.junit.jupiter.api.Assertions.*;

class SealedConsumerBlueprintTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedSealedInterfacesPreserveAllLeavesIncludingDiamondAndEnum(boolean allowedClasses) throws Exception {
        var handler = allowedClasses ? new AllowedHandler() : new ParameterHandler();
        var delivered = new CopyOnWriteArrayList<SerializedMessage>();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        try (Fluxzero app = application(delivered, configuration)) {
            app.registerHandlers(handler);
            assertFalse(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            app.eventGateway().publish(new Message(new Unrelated()), Guarantee.STORED).join();
            var events = List.of(new First("one"), new Second("two"), Choice.ONE);
            for (Object event : events) {
                app.eventGateway().publish(new Message(event), Guarantee.STORED).join();
                assertEquals(event, handler.received.poll(2, TimeUnit.SECONDS));
            }
            assertEquals(4, delivered.size());
        }
    }

    @Test
    void concreteSealedBaseIsNotLost() throws Exception {
        var handler = new ConcreteHandler();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        try (Fluxzero app = application(new CopyOnWriteArrayList<>(), configuration)) {
            app.registerHandlers(handler);
            assertFalse(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            for (ConcreteBase event : List.of(new ConcreteBase(), new ConcreteLeaf())) {
                app.eventGateway().publish(new Message(event), Guarantee.STORED).join();
                assertEquals(event.getClass(), handler.received.poll(2, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonSealedBranchesKeepUnlistedSubtypesEligible(boolean allowedClasses) throws Exception {
        var handler = allowedClasses ? new AllowedOpenHandler() : new OpenHandler();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        try (Fluxzero app = application(new CopyOnWriteArrayList<>(), configuration)) {
            app.registerHandlers(handler);
            assertFalse(configuration.get().getConsumerBlueprint().getFilter().payloadRestricted());
            app.eventGateway().publish(new Message(new UnlistedLeaf()), Guarantee.STORED).join();
            assertInstanceOf(UnlistedLeaf.class, handler.received.poll(2, TimeUnit.SECONDS));
        }
    }

    private static Fluxzero application(List<SerializedMessage> delivered,
                                       AtomicReference<ConsumerConfiguration> configuration) {
        return DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addBatchInterceptor(preservingConsumerFilter((consumer, tracker) -> {
                    configuration.set(tracker.getConfiguration());
                    return batch -> {
                        delivered.addAll(batch.getMessages());
                        consumer.accept(batch);
                    };
                }), EVENT).build(LocalClient.newInstance());
    }

    sealed interface Event permits Branch, OtherBranch, Choice {}
    sealed interface Branch extends Event permits First, Second {}
    sealed interface OtherBranch extends Event permits Second {}
    record First(String value) implements Branch {}
    record Second(String value) implements Branch, OtherBranch {}
    enum Choice implements Event { ONE, TWO { @Override public String toString() { return "two"; } } }
    record Unrelated() {}
    abstract static class CollectingHandler {
        final LinkedBlockingQueue<Object> received = new LinkedBlockingQueue<>();
    }
    static class ParameterHandler extends CollectingHandler {
        @HandleEvent void on(Event event) { received.add(event); }
    }
    static class AllowedHandler extends CollectingHandler {
        @HandleEvent(allowedClasses = Event.class) void on(Object event) { received.add(event); }
    }
    static sealed class ConcreteBase permits ConcreteLeaf {}
    static final class ConcreteLeaf extends ConcreteBase {}
    static class ConcreteHandler {
        final LinkedBlockingQueue<Class<?>> received = new LinkedBlockingQueue<>();
        @HandleEvent void on(ConcreteBase event) { received.add(event.getClass()); }
    }
    sealed interface OpenRoot permits OpenBranch {}
    non-sealed interface OpenBranch extends OpenRoot {}
    record UnlistedLeaf() implements OpenBranch {}
    static class OpenHandler extends CollectingHandler {
        @HandleEvent void on(OpenRoot event) { received.add(event); }
    }
    static class AllowedOpenHandler extends CollectingHandler {
        @HandleEvent(allowedClasses = OpenRoot.class) void on(Object event) { received.add(event); }
    }
}
