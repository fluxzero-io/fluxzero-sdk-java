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
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static java.net.http.HttpClient.Version.HTTP_1_1;

/**
 * Low-level Fluxzero websocket connector backed by the JDK {@link HttpClient} websocket implementation.
 *
 * <p>The JDK WebSocket API does not expose successful {@code 101 Switching Protocols} response headers directly.
 * This connector captures them through a shared {@link CookieHandler} wrapper while preserving any cookie handler
 * configured on the supplied {@link HttpClient}.</p>
 */
public class JdkWebsocketConnector implements WebsocketConnector {
    static final String DEFAULT_EXECUTOR_THREAD_PREFIX = "fluxzero-websocket-jdk-";

    private static final Executor DEFAULT_EXECUTOR = newWorkerPool(DEFAULT_EXECUTOR_THREAD_PREFIX, 8);
    private static final String SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";
    private static final String SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";
    private static final String WEBSOCKET_ACCEPT_SUFFIX = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final HttpClient httpClient;
    private final CapturingCookieHandler cookieHandler;
    private final Executor executor;
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
     * <p>A single internal client is derived from this client so the connector can install its handshake-header
     * capturing cookie handler without mutating the original client.</p>
     *
     * @param httpClient base client whose proxy, SSL, authenticator, executor, cookie, and timeout settings are reused
     */
    public JdkWebsocketConnector(HttpClient httpClient) {
        this(httpClient, resolveExecutor(httpClient));
    }

    /**
     * Creates a connector backed by the supplied HTTP client and executor.
     *
     * <p>The executor is used for the internal HTTP client derived from the supplied client and for dispatching native
     * JDK websocket listener callbacks.</p>
     *
     * @param httpClient base client whose proxy, SSL, authenticator, cookie, and timeout settings are reused
     * @param executor   executor for JDK websocket and listener callback work
     */
    public JdkWebsocketConnector(HttpClient httpClient, Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        this.cookieHandler = new CapturingCookieHandler(
                Objects.requireNonNull(httpClient).cookieHandler().orElse(null));
        this.httpClient = createHttpClient(httpClient, this.executor, cookieHandler);
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
        java.net.http.WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        if (connectionOptions.connectTimeout() != null) {
            builder.connectTimeout(connectionOptions.connectTimeout());
        } else {
            httpClient.connectTimeout().ifPresent(builder::connectTimeout);
        }
        applyHeaders(builder, connectionOptions.headers());
        applySubprotocols(builder, connectionOptions.subprotocols());

        JdkWebSocketSession session = new JdkWebSocketSession(this, endpoint, connectionOptions, uri,
                                                             handshakeResponse, executor);
        HandshakeCapture handshakeCapture = new HandshakeCapture(handshakeResponse);
        try {
            cookieHandler.buildAsync(handshakeCapture, () -> builder.buildAsync(uri, session.createListener())).get();
            session.awaitOpen();
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting websocket endpoint " + uri, e);
        } catch (ExecutionException | CancellationException e) {
            throw unwrapConnectionFailure(uri, e);
        } finally {
            cookieHandler.unregister(handshakeCapture);
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

    private static HttpClient createHttpClient(HttpClient httpClient, Executor executor,
                                               CapturingCookieHandler cookieHandler) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(httpClient.version())
                .followRedirects(httpClient.followRedirects())
                .sslContext(httpClient.sslContext())
                .sslParameters(httpClient.sslParameters())
                .executor(executor)
                // The JDK websocket API does not expose successful 101 responses directly; CookieHandler.put does.
                .cookieHandler(cookieHandler);
        httpClient.authenticator().ifPresent(builder::authenticator);
        httpClient.proxy().ifPresent(builder::proxy);
        httpClient.connectTimeout().ifPresent(builder::connectTimeout);
        return builder.build();
    }

    private static Executor resolveExecutor(HttpClient httpClient) {
        return Objects.requireNonNull(httpClient).executor().orElse(DEFAULT_EXECUTOR);
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

    private static String websocketAcceptKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((key + WEBSOCKET_ACCEPT_SUFFIX).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm is required for websocket handshakes", e);
        }
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
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

    private static class HandshakeCapture {
        private final CapturedHandshakeResponse handshakeResponse;
        private final CompletableFuture<Void> requestRegistered = new CompletableFuture<>();
        private volatile String expectedAccept;

        private HandshakeCapture(CapturedHandshakeResponse handshakeResponse) {
            this.handshakeResponse = handshakeResponse;
        }

        private void register(String expectedAccept) {
            this.expectedAccept = expectedAccept;
            requestRegistered.complete(null);
        }

        private void completeRegistration() {
            requestRegistered.complete(null);
        }

        private void awaitRegistration() throws InterruptedException, ExecutionException {
            requestRegistered.get();
        }
    }

    private static class CapturingCookieHandler extends CookieHandler {
        private final CookieHandler delegate;
        private final Semaphore registrationSemaphore = new Semaphore(1);
        private final AtomicReference<HandshakeCapture> registeringCapture = new AtomicReference<>();
        private final ConcurrentMap<String, HandshakeCapture> capturesByAccept = new ConcurrentHashMap<>();

        private CapturingCookieHandler(CookieHandler delegate) {
            this.delegate = delegate;
        }

        private CompletableFuture<WebSocket> buildAsync(HandshakeCapture capture,
                                                        Supplier<CompletableFuture<WebSocket>> builder)
                throws InterruptedException, ExecutionException {
            registrationSemaphore.acquire();
            try {
                registeringCapture.set(capture);
                CompletableFuture<WebSocket> webSocket = builder.get();
                webSocket.whenComplete((ignored, error) -> {
                    capture.completeRegistration();
                    if (error != null) {
                        unregister(capture);
                    }
                });
                capture.awaitRegistration();
                return webSocket;
            } finally {
                registeringCapture.compareAndSet(capture, null);
                registrationSemaphore.release();
            }
        }

        private void unregister(HandshakeCapture capture) {
            String expectedAccept = capture.expectedAccept;
            if (expectedAccept != null) {
                capturesByAccept.remove(expectedAccept, capture);
            }
        }

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
            HandshakeCapture capture = registeringCapture.get();
            if (capture != null) {
                String key = firstHeader(requestHeaders, SEC_WEBSOCKET_KEY);
                if (key != null) {
                    String expectedAccept = websocketAcceptKey(key);
                    capturesByAccept.put(expectedAccept, capture);
                    capture.register(expectedAccept);
                } else {
                    capture.completeRegistration();
                }
            }
            if (delegate != null) {
                Map<String, List<String>> headers = delegate.get(uri, requestHeaders);
                return headers == null ? Map.of() : headers;
            }
            return Map.of();
        }

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
            String accept = firstHeader(responseHeaders, SEC_WEBSOCKET_ACCEPT);
            if (accept != null) {
                HandshakeCapture capture = capturesByAccept.remove(accept);
                if (capture != null) {
                    capture.handshakeResponse.capture(responseHeaders);
                }
            }
            if (delegate != null) {
                delegate.put(uri, responseHeaders);
            }
        }
    }
}
