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
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static io.fluxzero.sdk.configuration.ApplicationProperties.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestGuaranteeTest {
    @ParameterizedTest
    @CsvSource({",,STORED", "2026.09.26,,STORED", "2026.09.27,,STORED", "2099.01.01,,STORED",
                "invalid,,STORED",
                ",NONE,NONE", ",SENT,SENT", ",STORED,STORED", "2026.09.26,STORED,STORED", "2026.09.27,SENT,SENT",
                "2026.09.28,SENT,SENT", ",sent,SENT", ",stored,STORED"})
    void resolvesOnlyExplicitOverridesRegardlessOfDefaultsVersion(String version, String override, Guarantee expected) {
        Map<String, String> properties = new HashMap<>();
        if (version != null) properties.put(DEFAULTS_VERSION_PROPERTY, version);
        if (override != null) properties.put(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, override);
        assertEquals(expected, getRequestDeliveryGuarantee(new SimplePropertySource(properties)));
    }

    @Test
    void rejectsUnresolvedOrInvalidConfiguration() {
        for (String value : List.of("DEFAULT", "", "invalid")) {
            assertThrows(IllegalArgumentException.class, () -> getRequestDeliveryGuarantee(
                    new SimplePropertySource(Map.of(DEFAULT_DELIVERY_GUARANTEE_PROPERTY, value))));
        }
    }

    @Test
    void directGatewaysValidateOverrides() {
        var gateway = new DefaultGenericGateway(mock(io.fluxzero.sdk.configuration.client.Client.class),
                mock(GatewayClient.class), mock(RequestHandler.class), new JacksonSerializer(),
                DispatchInterceptor.noOp, MessageType.COMMAND, null, HandlerRegistry.noOp(), mock(ResponseMapper.class));
        for (Guarantee guarantee : List.of(Guarantee.DEFAULT)) {
            assertThrows(IllegalArgumentException.class, () -> gateway.withRequestGuarantee(guarantee));
        }
        assertThrows(NullPointerException.class, () -> gateway.withRequestGuarantee(null));
        assertSame(gateway, gateway.withRequestGuarantee(Guarantee.NONE));
        assertSame(gateway, gateway.withRequestGuarantee(Guarantee.SENT));
        assertSame(gateway, gateway.withRequestGuarantee(Guarantee.STORED));
    }

    @Test
    void applicationsNamespacesAndLazyGatewaysKeepTheirOwningSource() {
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                DEFAULTS_VERSION_PROPERTY, "2026.09.27", DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "STORED")));
        try (var first = application(builder)) {
            builder.replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                    DEFAULT_DELIVERY_GUARANTEE_PROPERTY, "SENT")));
            try (var second = application(builder)) {
                // Another active application and a reused builder must not change the first application's policy.
                second.apply(f -> { failRequest(first); return null; });
                first.apply(f -> { failRequest(second); return null; });
                verify(first.client().getGatewayClient(MessageType.COMMAND))
                        .append(eq(Guarantee.STORED), any(SerializedMessage[].class));
                verify(second.client().getGatewayClient(MessageType.COMMAND))
                        .append(eq(Guarantee.SENT), any(SerializedMessage[].class));
                assertThrows(CompletionException.class,
                        () -> first.commandGateway().forNamespace("other").send("request").join());
                verify(first.client().forNamespace("other").getGatewayClient(MessageType.COMMAND))
                        .append(eq(Guarantee.STORED), any(SerializedMessage[].class));
                assertThrows(CompletionException.class,
                        () -> first.customGateway("lazy").sendForMessage(new Message("request")).join());
                verify(first.client().getGatewayClient(MessageType.CUSTOM, "lazy"))
                        .append(eq(Guarantee.STORED), any(SerializedMessage[].class));
                var firstTransport = first.client().getGatewayClient(MessageType.COMMAND);
                doReturn(CompletableFuture.completedFuture(null))
                        .when(firstTransport)
                        .append(eq(Guarantee.STORED), any(SerializedMessage[].class));
                clearInvocations(firstTransport);
                first.commandGateway().sendAndForget("event");
                verify(first.client().getGatewayClient(MessageType.COMMAND))
                        .append(eq(Guarantee.STORED), any(SerializedMessage[].class));
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2026.09.26", "2026.09.27", "2099.01.01"})
    void builderPreservesMajorRequestDefaultRegardlessOfDefaultsVersion(String version) {
        Map<String, String> properties = new HashMap<>();
        if (version != null) properties.put(DEFAULTS_VERSION_PROPERTY, version);
        try (var app = application(DefaultFluxzero.builder().replacePropertySource(
                ignored -> new SimplePropertySource(properties)))) {
            failRequest(app);
            assertThrows(CompletionException.class, () -> app.queryGateway().send("query").join());
            for (var type : List.of(MessageType.COMMAND, MessageType.QUERY)) {
                verify(app.client().getGatewayClient(type))
                        .append(eq(Guarantee.STORED), any(SerializedMessage[].class));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"2026.09.26,STORED,STORED", "2026.09.27,SENT,SENT", "2099.01.01,NONE,NONE"})
    void builderAppliesExplicitRequestOverrides(String version, String override, Guarantee expected) {
        try (var app = application(DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(
                Map.of(DEFAULTS_VERSION_PROPERTY, version, DEFAULT_DELIVERY_GUARANTEE_PROPERTY, override))))) {
            failRequest(app);
            verify(app.client().getGatewayClient(MessageType.COMMAND))
                    .append(eq(expected), any(SerializedMessage[].class));
        }
    }

    private static void failRequest(Fluxzero app) {
        assertThrows(CompletionException.class, () -> app.commandGateway().send("request").join());
    }

    private static Fluxzero application(FluxzeroBuilder builder) {
        LocalClient client = publicationClient();
        LocalClient namespaced = publicationClient();
        doReturn(namespaced).when(client).forNamespace("other");
        return builder.disableShutdownHook().build(client);
    }

    private static LocalClient publicationClient() {
        LocalClient client = spy(LocalClient.newInstance());
        Map<String, GatewayClient> gateways = new HashMap<>();
        doAnswer(invocation -> gateways.computeIfAbsent(String.valueOf((Object) invocation.getArgument(0))
                                                        + invocation.getArgument(1), ignored -> {
            GatewayClient gateway = mock(GatewayClient.class);
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("publication rejected")))
                    .when(gateway).append(any(), any(SerializedMessage[].class));
            return gateway;
        })).when(client).getGatewayClient(any(), any());
        return client;
    }
}
