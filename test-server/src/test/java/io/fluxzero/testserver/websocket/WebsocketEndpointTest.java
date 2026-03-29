package io.fluxzero.testserver.websocket;

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
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

    private static class TestEndpoint extends WebsocketEndpoint {
        CompressionAlgorithm getCompressionAlgorithmForTest(Session session) {
            return getCompressionAlgorithm(session);
        }
    }
}
