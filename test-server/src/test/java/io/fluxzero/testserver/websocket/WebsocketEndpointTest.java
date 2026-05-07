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
 *
 */

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.api.ConnectEvent;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsocketEndpointTest {

    @Test
    void capabilityHeaderTakesPrecedenceOverLegacyCompressionParameter() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)))));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.GZIP, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void selectedCompressionAlgorithmTakesPrecedenceOverSupportedList() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)),
                WebsocketDeploymentUtils.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                CompressionAlgorithm.LZ4)));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("GZIP"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void legacyCompressionParameterRemainsFallbackWhenNoCapabilitiesAreSent() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void clientSdkVersionIsAddedToSessionMetadataWhenAdvertisedInHandshake() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of("cli1234567890"),
                       WebSocketCapabilities.CLIENT_SDK_VERSION_HEADER, List.of("1.2.3")),
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY,
                "srv123456789")));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of("client"), "clientName", List.of("test-client")));

        TestEndpoint endpoint = new TestEndpoint();

        assertEquals("1.2.3", endpoint.getClientSdkVersionForTest(session));
        assertEquals("1.2.3", endpoint.sessionMetadataForTest(session).getEntries().get("$clientSdkVersion"));
    }

    @Test
    void connectEventContainsSdkAndRuntimeVersion() {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of("cli1234567890"),
                       WebSocketCapabilities.CLIENT_SDK_VERSION_HEADER, List.of("1.2.3")),
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY,
                "srv123456789")));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of("client"), "clientName", List.of("test-client")));

        TestEndpoint endpoint = new TestEndpoint();

        ConnectEvent event = endpoint.createConnectEventForTest(session);

        assertEquals("1.2.3", event.getSdkVersion());
        assertEquals("9.8.7", event.getRuntimeVersion());
    }

    private static class TestEndpoint extends WebsocketEndpoint {
        @Override
        protected String getRuntimeVersion() {
            return "9.8.7";
        }

        CompressionAlgorithm getCompressionAlgorithmForTest(ServerWebsocketSession session) {
            return getCompressionAlgorithm(session);
        }

        String getClientSdkVersionForTest(ServerWebsocketSession session) {
            return getClientSdkVersion(session);
        }

        io.fluxzero.common.api.Metadata sessionMetadataForTest(ServerWebsocketSession session) {
            return sessionMetadata(session);
        }

        ConnectEvent createConnectEventForTest(ServerWebsocketSession session) {
            return new ConnectEvent(getClientName(session), getClientId(session), getNegotiatedSessionId(session),
                                    toString(), getClientSdkVersion(session), getRuntimeVersion());
        }
    }
}
