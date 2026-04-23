package io.fluxzero.testserver.websocket;

import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.testserver.TestServerVersion;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.undertow.Handlers.path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        CountDownLatch opened = new CountDownLatch(1);
        Map<String, List<String>> responseHeaders = new ConcurrentHashMap<>();
        ClientEndpointConfig endpointConfig = ClientEndpointConfig.Builder.create().configurator(
                new ClientEndpointConfig.Configurator() {
                    @Override
                    public void afterResponse(HandshakeResponse hr) {
                        responseHeaders.putAll(hr.getHeaders());
                    }
                }).build();

        Session session = container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                opened.countDown();
            }
        }, endpointConfig, URI.create("ws://localhost:" + port + "/test"));
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            assertEquals(TestServerVersion.version().orElseThrow(),
                         WebSocketCapabilities.getRuntimeVersion(responseHeaders).orElseThrow());
        } finally {
            session.close();
        }
    }
}
