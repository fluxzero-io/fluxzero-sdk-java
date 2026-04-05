package io.fluxzero.testserver.websocket;

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.common.api.ConnectEvent;
import jakarta.websocket.Session;
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
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)))));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.GZIP, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void selectedCompressionAlgorithmTakesPrecedenceOverSupportedList() {
        Session session = mock(Session.class);
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
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithmForTest(session));
    }

    @Test
    void clientSdkVersionIsAddedToSessionMetadataWhenAdvertisedInHandshake() {
        Session session = mock(Session.class);
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
        Session session = mock(Session.class);
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

        CompressionAlgorithm getCompressionAlgorithmForTest(Session session) {
            return getCompressionAlgorithm(session);
        }

        String getClientSdkVersionForTest(Session session) {
            return getClientSdkVersion(session);
        }

        io.fluxzero.common.api.Metadata sessionMetadataForTest(Session session) {
            return sessionMetadata(session);
        }

        ConnectEvent createConnectEventForTest(Session session) {
            return new ConnectEvent(getClientName(session), getClientId(session), getNegotiatedSessionId(session),
                                    toString(), getClientSdkVersion(session), getRuntimeVersion());
        }
    }
}
