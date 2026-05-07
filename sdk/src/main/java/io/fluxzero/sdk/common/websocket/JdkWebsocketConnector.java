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

package io.fluxzero.sdk.common.websocket;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static java.net.http.HttpClient.Version.HTTP_1_1;

/**
 * Low-level Fluxzero websocket connector backed by the JDK {@link HttpClient} websocket implementation.
 *
 * <p>The JDK WebSocket API does not expose successful {@code 101 Switching Protocols} response headers directly.
 * This connector captures them through a per-connection {@link CookieHandler} wrapper while preserving any cookie
 * handler configured on the supplied {@link HttpClient}.</p>
 */
public class JdkWebsocketConnector implements WebsocketConnector {
    private final HttpClient httpClient;
    private final Set<WebsocketSession> openSessions = ConcurrentHashMap.newKeySet();

    /**
     * Creates a connector backed by a default HTTP/1.1 {@link HttpClient}.
     */
    public JdkWebsocketConnector() {
        this(HttpClient.newBuilder().version(HTTP_1_1).build());
    }

    /**
     * Creates a connector backed by the supplied HTTP client.
     *
     * <p>Per-connection clients are derived from this client so the connector can install its handshake-header
     * capturing cookie handler without mutating the original client.</p>
     *
     * @param httpClient base client whose proxy, SSL, authenticator, executor, cookie, and timeout settings are reused
     */
    public JdkWebsocketConnector(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    /**
     * Opens a native JDK WebSocket and adapts it to Fluxzero's low-level {@link WebsocketSession} contract.
     */
    @Override
    public WebsocketSession connect(WebsocketEndpoint endpoint, WebsocketConnectionOptions options, URI uri)
            throws IOException {
        Objects.requireNonNull(endpoint, "endpoint may not be null");
        Objects.requireNonNull(uri, "uri may not be null");
        WebsocketConnectionOptions connectionOptions = options == null
                ? new WebsocketConnectionOptions(Map.of(), Map.of(), null, List.of()) : options;

        CapturedHandshakeResponse handshakeResponse = new CapturedHandshakeResponse();
        HttpClient client = createHttpClient(connectionOptions, handshakeResponse);
        java.net.http.WebSocket.Builder builder = client.newWebSocketBuilder();
        if (connectionOptions.connectTimeout() != null) {
            builder.connectTimeout(connectionOptions.connectTimeout());
        }
        applyHeaders(builder, connectionOptions.headers());
        applySubprotocols(builder, connectionOptions.subprotocols());

        JdkWebSocketSession session = new JdkWebSocketSession(this, endpoint, connectionOptions, uri,
                                                             handshakeResponse);
        try {
            builder.buildAsync(uri, session.createListener()).get();
            session.awaitOpen();
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting websocket endpoint " + uri, e);
        } catch (ExecutionException | CancellationException e) {
            throw unwrapConnectionFailure(uri, e);
        }
    }

    Set<WebsocketSession> getOpenSessions() {
        return Set.copyOf(openSessions);
    }

    void addOpenSession(WebsocketSession session) {
        openSessions.add(session);
    }

    void removeOpenSession(WebsocketSession session) {
        openSessions.remove(session);
    }

    private HttpClient createHttpClient(WebsocketConnectionOptions options,
                                        CapturedHandshakeResponse handshakeResponse) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(httpClient.version())
                .followRedirects(httpClient.followRedirects())
                .sslContext(httpClient.sslContext())
                .sslParameters(httpClient.sslParameters())
                // The JDK websocket API does not expose successful 101 responses directly; CookieHandler.put does.
                .cookieHandler(new CapturingCookieHandler(httpClient.cookieHandler().orElse(null),
                                                          handshakeResponse));
        httpClient.authenticator().ifPresent(builder::authenticator);
        httpClient.executor().ifPresent(builder::executor);
        httpClient.proxy().ifPresent(builder::proxy);
        if (options.connectTimeout() != null) {
            builder.connectTimeout(options.connectTimeout());
        } else {
            httpClient.connectTimeout().ifPresent(builder::connectTimeout);
        }
        return builder.build();
    }

    private static void applyHeaders(java.net.http.WebSocket.Builder builder, Map<String, List<String>> headers) {
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
    }

    private static void applySubprotocols(java.net.http.WebSocket.Builder builder, List<String> subprotocols) {
        List<String> protocols = new ArrayList<>(subprotocols);
        if (!protocols.isEmpty()) {
            builder.subprotocols(protocols.getFirst(), protocols.subList(1, protocols.size()).toArray(String[]::new));
        }
    }

    private static IOException unwrapConnectionFailure(URI uri, Exception e) {
        Throwable cause = e instanceof ExecutionException executionException
                ? executionException.getCause() : e.getCause();
        if (cause instanceof WebSocketHandshakeException handshakeException) {
            int statusCode = handshakeException.getResponse().statusCode();
            return new IOException(
                    "Failed to establish websocket connection to %s: HTTP %s".formatted(uri, statusCode),
                    handshakeException);
        }
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException("Failed to establish websocket connection to " + uri, cause);
    }

    static class CapturedHandshakeResponse {
        private volatile Map<String, List<String>> headers = Map.of();

        Map<String, List<String>> headers() {
            return headers;
        }

        private void capture(Map<String, List<String>> responseHeaders) {
            headers = copyHeaders(responseHeaders);
        }

        private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            source.forEach((name, values) -> {
                if (name != null) {
                    copy.put(name, values == null ? List.of() : List.copyOf(values));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
    }

    private static class CapturingCookieHandler extends CookieHandler {
        private final CookieHandler delegate;
        private final CapturedHandshakeResponse handshakeResponse;

        private CapturingCookieHandler(CookieHandler delegate, CapturedHandshakeResponse handshakeResponse) {
            this.delegate = delegate;
            this.handshakeResponse = handshakeResponse;
        }

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
            if (delegate == null) {
                return Map.of();
            }
            Map<String, List<String>> headers = delegate.get(uri, requestHeaders);
            return headers == null ? Map.of() : headers;
        }

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
            handshakeResponse.capture(responseHeaders);
            if (delegate != null) {
                delegate.put(uri, responseHeaders);
            }
        }
    }
}
