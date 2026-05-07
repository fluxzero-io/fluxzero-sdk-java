package io.fluxzero.testserver.websocket;

import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.websocket.JdkWebsocketConnector;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.common.websocket.WebsocketConnectionOptions;
import io.fluxzero.sdk.common.websocket.WebsocketEndpoint;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.testserver.TestServerVersion;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.undertow.Handlers.path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WebsocketDeploymentUtilsTest {

    private static final int port = 9126;
    private static Undertow server;

    @BeforeAll
    static void beforeAll() {
        PathHandler pathHandler = WebsocketDeploymentUtils.deploy(ignored -> new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
            }
        }, "/test/", path());
        server = Undertow.builder().addHttpListener(port, "0.0.0.0").setHandler(pathHandler).build();
        server.start();
    }

    @AfterAll
    static void afterAll() {
        server.stop();
    }

    @Test
    void handshakePublishesRuntimeVersionHeader() throws Exception {
        WebsocketSession session = new JdkWebsocketConnector().connect(new WebsocketEndpoint() {
            @Override
            public void onOpen(WebsocketSession session) {
            }

            @Override
            public void onMessage(byte[] bytes, WebsocketSession session) {
            }

            @Override
            public void onPong(ByteBuffer data, WebsocketSession session) {
            }

            @Override
            public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
            }

            @Override
            public void onError(WebsocketSession session, Throwable error) {
            }
        }, new WebsocketConnectionOptions(Map.of(), Map.of(), Duration.ofSeconds(5), List.of()),
           URI.create("ws://localhost:" + port + "/test"));

        try {
            assertEquals(TestServerVersion.version().orElseThrow(),
                         WebSocketCapabilities.getRuntimeVersion(
                                 session.getHandshakeResponseHeaders()).orElseThrow());
        } finally {
            session.close();
        }
    }
}
