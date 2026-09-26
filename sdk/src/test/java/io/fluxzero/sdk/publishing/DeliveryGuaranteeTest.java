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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULT_DELIVERY_GUARANTEE_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULTS_VERSION_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getDefaultDeliveryGuarantee;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeliveryGuaranteeTest {
    @ParameterizedTest
    @CsvSource({",,STORED", "2026.09.24,,STORED", "2026.09.25,,STORED", "2026.09.26,,STORED",
                ",STORED,STORED", "2026.09.24,STORED,STORED", "2026.09.26,NONE,NONE",
                "2026.09.26,SENT,SENT"})
    void resolvesVersionAndOverrides(String version, String override, Guarantee expected) {
        var properties = new java.util.HashMap<String, String>();
        if (version != null) properties.put(DEFAULTS_VERSION_PROPERTY, version);
        if (override != null) properties.put(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, override);
        assertEquals(expected, getDefaultDeliveryGuarantee(new SimplePropertySource(properties)));
    }

    @Test
    void rejectsInvalidConfiguration() {
        for (String value : List.of("DEFAULT", "", "invalid")) {
            assertThrows(IllegalArgumentException.class, () -> getDefaultDeliveryGuarantee(
                    new SimplePropertySource(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, value))));
        }
        assertEquals(Guarantee.STORED, getDefaultDeliveryGuarantee(
                new SimplePropertySource(Map.of(DEFAULTS_VERSION_PROPERTY, "invalid"))));
    }

    @Test
    void applicationsAndNamespacesKeepOwningDefaults() {
        try (var first = application(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED"));
             var second = application(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "NONE"))) {
            List<Guarantee> firstWrites = capture(first);
            List<Guarantee> secondWrites = capture(second);
            second.apply(f -> { first.eventGateway().publish("first"); return null; });
            first.apply(f -> { second.eventGateway().publish("second"); return null; });
            assertEquals(List.of(Guarantee.STORED), firstWrites);
            assertEquals(List.of(Guarantee.NONE), secondWrites);
            GatewayClient other = first.client().forNamespace("other").getGatewayClient(MessageType.EVENT);
            // Namespace gateways are created after the second application and must still inherit the first's policy.
            first.eventGateway().forNamespace("other").publish("namespaced");
            verify(other).append(eq(Guarantee.STORED), any(SerializedMessage[].class));
        }
    }

    @Test
    void lazyCustomGatewayRetainsDefaultAfterBuilderReuse() {
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(
                Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED")));
        try (var first = application(builder)) {
            builder.replacePropertySource(ignored -> new SimplePropertySource(
                    Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "NONE")));
            try (var second = application(builder)) {
                List<Guarantee> writes = new ArrayList<>();
                GatewayClient transport = first.client().getGatewayClient(MessageType.CUSTOM, "topic");
                doAnswer(invocation -> {
                    writes.add(invocation.getArgument(0));
                    return CompletableFuture.completedFuture(null);
                }).when(transport).append(any(), any(SerializedMessage[].class));
                second.apply(f -> {
                    first.customGateway("topic").sendAndForget("event");
                    return null;
                });
                assertEquals(List.of(Guarantee.STORED), writes);
            }
        }
    }

    @Test
    void implicitPublicationsDoNotWaitForAcknowledgementAndExplicitFutureDoes() {
        try (var app = application(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED"))) {
            var acknowledgement = new CompletableFuture<Void>();
            for (MessageType type : List.of(MessageType.EVENT, MessageType.COMMAND, MessageType.ERROR)) {
                GatewayClient gateway = app.client().getGatewayClient(type, null);
                doReturn(acknowledgement).when(gateway)
                        .append(any(), any(SerializedMessage[].class));
            }
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> app.apply(f -> {
                Fluxzero.publishEvent("event");
                Fluxzero.publishEvent("event", Metadata.empty());
                Fluxzero.publishEvents("one", "two");
                f.commandGateway().sendAndForget("command");
                f.commandGateway().sendAndForget("command", Metadata.empty());
                f.errorGateway().report("error");
                return null;
            }));
            CompletableFuture<Void> completion = app.eventGateway().publish(new Message("explicit"), Guarantee.DEFAULT);
            assertFalse(completion.isDone());
            acknowledgement.complete(null);
            completion.join();
        }
    }

    @Test
    void concreteGuaranteesAndParallelSerializationRemainUnchanged() {
        try (var app = application(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED"))) {
            List<Guarantee> writes = capture(app);
            for (Guarantee guarantee : List.of(Guarantee.NONE, Guarantee.SENT, Guarantee.STORED)) {
                app.eventGateway().publish(new Message("explicit"), guarantee).join();
            }
            app.eventGateway().publish(Guarantee.DEFAULT, IntStream.range(0, 8193).boxed().toArray()).join();
            assertEquals(List.of(Guarantee.NONE, Guarantee.SENT, Guarantee.STORED), writes.subList(0, 3));
            assertTrue(writes.size() > 3);
            assertTrue(writes.subList(3, writes.size()).stream().allMatch(g -> g == Guarantee.STORED));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicFixturePathsKeepPublishedEffects(boolean async) {
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(
                Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED")));
        var fixture = async ? TestFixture.createAsync(builder, new Publisher())
                            : TestFixture.create(builder, new Publisher());
        fixture.whenCommand(new PublishEvent()).expectEvents("published");
    }

    record PublishEvent() {}

    static class Publisher {
        @HandleCommand
        void handle(PublishEvent ignored) {
            Fluxzero.publishEvent("published");
        }
    }

    private static List<Guarantee> capture(Fluxzero app) {
        List<Guarantee> writes = new ArrayList<>();
        GatewayClient gateway = app.client().getGatewayClient(MessageType.EVENT);
        doAnswer(invocation -> {
                    writes.add(invocation.getArgument(0));
                    return CompletableFuture.completedFuture(null);
                }).when(gateway)
                .append(any(), any(SerializedMessage[].class));
        return writes;
    }

    private static Fluxzero application(Map<String, String> properties) {
        return application(DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(properties)));
    }

    private static Fluxzero application(FluxzeroBuilder builder) {
        LocalClient client = spy(LocalClient.newInstance());
        Map<String, GatewayClient> gateways = new java.util.HashMap<>();
        doAnswer(invocation -> gateways.computeIfAbsent(String.valueOf((Object) invocation.getArgument(0))
                                                        + invocation.getArgument(1), ignored -> {
            GatewayClient gateway;
            try {
                gateway = spy((GatewayClient) invocation.callRealMethod());
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            doReturn(CompletableFuture.completedFuture(null)).when(gateway)
                    .append(any(), any(SerializedMessage[].class));
            return gateway;
        })).when(client).getGatewayClient(any(), any());
        // Avoid replacing the tracking clients: only publication uses the controllable transport.
        LocalClient namespaced = spy(LocalClient.newInstance());
        GatewayClient namespacedGateway = spy(namespaced.getGatewayClient(MessageType.EVENT));
        doReturn(CompletableFuture.completedFuture(null)).when(namespacedGateway)
                .append(any(), any(SerializedMessage[].class));
        doReturn(namespacedGateway).when(namespaced).getGatewayClient(any(), any());
        doReturn(namespaced).when(client).forNamespace("other");
        return builder.disableShutdownHook().build(client);
    }
}
