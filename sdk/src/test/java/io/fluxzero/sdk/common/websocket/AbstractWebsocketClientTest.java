package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.SdkVersion;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.publishing.Append;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.GZIP;
import static io.fluxzero.common.serialization.compression.CompressionAlgorithm.LZ4;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractWebsocketClientTest {

    @Test
    void supportedCompressionAlgorithmsDefaultToConfiguredCompressionFirst() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();

        assertEquals(GZIP, clientConfig.getSupportedCompressionAlgorithms().getFirst());
        assertEquals(Set.of(CompressionAlgorithm.values()),
                     Set.copyOf(clientConfig.getSupportedCompressionAlgorithms()));
    }

    @Test
    void endpointConfigPublishesSupportedCompressionAlgorithmsHeader() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();

        var headers = new HashMap<String, List<String>>();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        connectionSetup.endpointConfig().getConfigurator().beforeRequest(headers);

        assertEquals(clientConfig.getSupportedCompressionAlgorithms(),
                     WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
        assertEquals(connectionSetup.configurator().getClientSessionId(),
                     WebSocketCapabilities.getClientSessionId(headers).orElseThrow());
        assertEquals(SdkVersion.version().orElseThrow(),
                     WebSocketCapabilities.getClientSdkVersion(headers).orElseThrow());
        assertEquals(12, connectionSetup.configurator().getClientSessionId().length());
    }

    @Test
    void endpointConfigCanSuppressCapabilityHeaders() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(List.of())
                .build();

        var headers = new HashMap<String, List<String>>();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        connectionSetup.endpointConfig().getConfigurator().beforeRequest(headers);

        assertEquals(List.of(), WebSocketCapabilities.getSupportedCompressionAlgorithms(headers));
        assertEquals(connectionSetup.configurator().getClientSessionId(),
                     WebSocketCapabilities.getClientSessionId(headers).orElseThrow());
    }

    @Test
    void endpointConfigCapturesNegotiatedHandshakeResponse() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        HandshakeResponse response = mock(HandshakeResponse.class);
        when(response.getHeaders()).thenReturn(Map.of(
                WebSocketCapabilities.RUNTIME_SESSION_ID_HEADER, List.of("srv123456789"),
                WebSocketCapabilities.RUNTIME_VERSION_HEADER, List.of("1.2.3"),
                WebSocketCapabilities.SELECTED_COMPRESSION_ALGORITHM_HEADER, List.of("LZ4")));

        connectionSetup.endpointConfig().getConfigurator().afterResponse(response);

        assertEquals("srv123456789", connectionSetup.configurator().getRuntimeSessionId());
        assertEquals("1.2.3", connectionSetup.configurator().getRuntimeVersion());
        assertEquals(CompressionAlgorithm.LZ4, connectionSetup.configurator().getSelectedCompressionAlgorithm());
    }

    @Test
    void endpointConfigLeavesNegotiatedValuesEmptyForLegacyRuntime() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .supportedCompressionAlgorithms(Stream.concat(
                        Stream.of(GZIP), EnumSet.complementOf(EnumSet.of(GZIP)).stream()).toList())
                .build();
        AbstractWebsocketClient.ConnectionSetup connectionSetup =
                AbstractWebsocketClient.createConnectionSetup(clientConfig);
        HandshakeResponse response = mock(HandshakeResponse.class);
        when(response.getHeaders()).thenReturn(Map.of());

        connectionSetup.endpointConfig().getConfigurator().afterResponse(response);

        assertNull(connectionSetup.configurator().getRuntimeSessionId());
        assertNull(connectionSetup.configurator().getRuntimeVersion());
        assertNull(connectionSetup.configurator().getSelectedCompressionAlgorithm());
    }

    @Test
    void constructorRejectsSubclassesAnnotatedWithClientEndpoint() {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                                                   () -> new InvalidAnnotatedClient(mock(WebSocketContainer.class),
                                                                                    clientConfig));

        assertTrue(error.getMessage().contains("@ClientEndpoint"));
        assertTrue(error.getMessage().contains(InvalidAnnotatedClient.class.getName()));
    }

    @Test
    void sendBatchIgnoresClosedChannelDuringShutdown() throws Exception {
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost")
                .name("test-client")
                .build();
        TestClient client = new TestClient(mock(WebSocketContainer.class), clientConfig);
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        when(remote.getSendStream()).thenThrow(new ClosedChannelException());
        when(session.getUserProperties()).thenReturn(new HashMap<>(Map.of(
                AbstractWebsocketClient.CLIENT_SESSION_ID_USER_PROPERTY, "client123",
                AbstractWebsocketClient.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime456")));

        client.close();

        Method sendBatch = AbstractWebsocketClient.class.getDeclaredMethod("sendBatch", List.class, Session.class);
        sendBatch.setAccessible(true);

        List<Request> requests = List.of(new Append(MessageType.EVENT, List.<SerializedMessage>of(), Guarantee.NONE));
        assertDoesNotThrow(() -> sendBatch.invoke(client, requests, session));
    }

    @ClientEndpoint
    private static class InvalidAnnotatedClient extends AbstractWebsocketClient {
        InvalidAnnotatedClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
            super(container, URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1);
        }
    }

    private static class TestClient extends AbstractWebsocketClient {
        TestClient(WebSocketContainer container, WebSocketClient.ClientConfig clientConfig) {
            super(container, URI.create("ws://localhost"), WebSocketClient.newInstance(clientConfig),
                  true, Duration.ofSeconds(1), defaultObjectMapper, 1);
        }
    }
}
