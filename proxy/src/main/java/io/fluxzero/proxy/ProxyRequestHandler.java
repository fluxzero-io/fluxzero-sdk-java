/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.proxy;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.DefaultRequestHandler;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.sdk.web.WebUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getLongProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.mapProperty;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

@Slf4j
public class ProxyRequestHandler extends AbstractNamespaced<ProxyRequestHandler> {

    public static final String CORS_DOMAINS_PROPERTY = "FLUXZERO_CORS_DOMAINS";
    public static final String REQUEST_TIMEOUT_SECONDS_PROPERTY = "FLUXZERO_PROXY_REQUEST_TIMEOUT_SECONDS";
    static final Duration SERVER_SHUTDOWN_CLOSE_TIMEOUT = Duration.ofSeconds(1);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(200);

    @Getter
    private final Client client;

    private final ProxySerializer serializer = new ProxySerializer();
    private final GatewayClient requestGateway;
    private final RequestHandler requestHandler;
    private final ProxyWebsocketEndpoint proxyWebsocketEndpoint;
    private final NamespaceSelector namespaceSelector;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long maxRequestBodySize = ProxyServer.DEFAULT_MAX_REQUEST_BODY_SIZE;
    private volatile long maxMultipartRequestBodySize = ProxyServer.DEFAULT_MAX_MULTIPART_REQUEST_BODY_SIZE;
    private volatile int maxPendingWebsocketSends = ProxyServer.DEFAULT_MAX_PENDING_WEBSOCKET_SENDS;
    private volatile ServerWebSocketContainer websocketContainer;
    @Getter(lazy = true)
    private final Set<String> allowedCorsDomains = mapProperty(CORS_DOMAINS_PROPERTY, p -> Arrays.stream(p.split(","))
            .map(String::trim).filter(d -> !d.isEmpty()).collect(Collectors.toSet()), Set::of);

    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS = Set.of(
            "Connection",
            "Keep-Alive",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "Proxy-Connection",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade");

    public ProxyRequestHandler(Client client) {
        this(client, new NamespaceSelector());
    }

    private ProxyRequestHandler(Client client, NamespaceSelector namespaceSelector) {
        this.client = client;
        requestGateway = client.getGatewayClient(MessageType.WEBREQUEST);
        requestHandler = new DefaultRequestHandler(
                client, MessageType.WEBRESPONSE,
                Duration.ofSeconds(getLongProperty(REQUEST_TIMEOUT_SECONDS_PROPERTY, REQUEST_TIMEOUT.toSeconds())),
                format("%s_%s", client.name(), "$proxy-request-handler"));
        proxyWebsocketEndpoint = new ProxyWebsocketEndpoint(client, requestHandler);
        this.namespaceSelector = namespaceSelector;
    }

    @Override
    protected ProxyRequestHandler createForNamespace(String namespace) {
        if (Objects.equals(client.namespace(), namespace)) {
            return this;
        }
        ProxyRequestHandler namespacedHandler = new ProxyRequestHandler(client.forNamespace(namespace), namespaceSelector);
        namespacedHandler.setMaxRequestBodySize(maxRequestBodySize);
        namespacedHandler.setMaxMultipartRequestBodySize(maxMultipartRequestBodySize);
        namespacedHandler.setMaxPendingWebsocketSends(maxPendingWebsocketSends);
        namespacedHandler.setWebsocketContainer(websocketContainer);
        return namespacedHandler;
    }

    void setMaxRequestBodySize(long maxRequestBodySize) {
        this.maxRequestBodySize = maxRequestBodySize;
    }

    void setMaxMultipartRequestBodySize(long maxMultipartRequestBodySize) {
        this.maxMultipartRequestBodySize = maxMultipartRequestBodySize;
    }

    void setMaxPendingWebsocketSends(int maxPendingWebsocketSends) {
        this.maxPendingWebsocketSends = maxPendingWebsocketSends;
    }

    void setWebsocketContainer(ServerWebSocketContainer websocketContainer) {
        this.websocketContainer = websocketContainer;
    }

    public boolean handle(Request request, Response response, Callback callback) {
        JettyExchange exchange = new JettyExchange(request, response, callback,
                                                  maxRequestBodySize, maxMultipartRequestBodySize,
                                                  maxPendingWebsocketSends, client.namespace());
        if (closed.get()) {
            sendServiceUnavailable(exchange);
            return true;
        }
        try {
            if (handleCorsPreflight(exchange)) {
                return true;
            }
            Content.Source.asByteArrayAsync(request, exchange.maxRequestBodySizeAsInt())
                    .whenComplete((payload, error) -> {
                        try {
                            if (error != null) {
                                Throwable readFailure = unwrapException(error);
                                if (isRequestBodyTooLarge(readFailure)) {
                                    log.debug("Rejected request body larger than {} bytes",
                                              exchange.maxRequestBodySize());
                                    sendPayloadTooLarge(exchange);
                                } else {
                                    log.error("Failed to read incoming message", readFailure);
                                    sendServerError(exchange);
                                }
                                return;
                            }
                            sendWebRequest(exchange, createWebRequest(exchange, payload));
                        } catch (Throwable e) {
                            log.error("Failed to create request", e);
                            sendServerError(exchange);
                        }
                    });
        } catch (Throwable e) {
            log.error("Failed to handle incoming request", e);
            sendServerError(exchange);
        }
        return true;
    }

    protected WebRequest createWebRequest(JettyExchange exchange, byte[] payload) {
        var builder = WebRequest.builder()
                .url(exchange.getPathQuery())
                .method(exchange.getMethod()).payload(payload)
                .acceptGzipEncoding(false);
        exchange.getRequestHeaders().forEach(
                header -> builder.header(header.getName(), header.getValue()));
        return tryUpgrade(builder.build());
    }

    protected WebRequest tryUpgrade(WebRequest webRequest) {
        if (HttpRequestMethod.GET.equals(webRequest.getMethod())
            && headerContainsToken(webRequest.getHeader("Connection"), "Upgrade")
            && "websocket".equalsIgnoreCase(webRequest.getHeader("Upgrade"))) {
            var requestBuilder = webRequest.toBuilder();
            var protocols = getWebsocketProtocols(webRequest.getHeaders("Sec-WebSocket-Protocol"));
            if (!protocols.isEmpty() && protocols.size() % 2 == 0) {
                for (int i = 0; i < protocols.size(); i += 2) {
                    try {
                        var name = URLDecoder.decode(protocols.get(i), StandardCharsets.UTF_8);
                        var value = URLDecoder.decode(protocols.get(i + 1), StandardCharsets.UTF_8);
                        requestBuilder.header(name, value);
                    } catch (Throwable e) {
                        log.warn("Failed to convert websocket subprotocol pair to request headers", e);
                    }
                }
            }
            return requestBuilder.method(HttpRequestMethod.WS_HANDSHAKE).build();
        }
        return webRequest;
    }

    private static boolean headerContainsToken(String headerValue, String token) {
        if (headerValue == null) {
            return false;
        }
        return Arrays.stream(headerValue.split(",")).map(String::trim).anyMatch(token::equalsIgnoreCase);
    }

    static List<String> getWebsocketProtocols(List<String> headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return Collections.emptyList();
        }
        return headerValue.stream().flatMap(
                protocolHeader -> Arrays.stream(protocolHeader.split(",")).map(String::trim)).toList();
    }

    protected boolean handleCorsPreflight(JettyExchange exchange) {
        if ("options".equalsIgnoreCase(exchange.getMethod())
            && exchange.hasRequestHeader("Access-Control-Request-Method")
            && applyCorsHeaders(exchange)) {
            ofNullable(exchange.getRequestHeader("Access-Control-Request-Headers"))
                    .ifPresent(h -> exchange.putResponseHeader("Access-Control-Allow-Headers", h));
            ofNullable(exchange.getRequestHeader("Access-Control-Request-Method"))
                    .ifPresent(h -> exchange.putResponseHeader("Access-Control-Allow-Methods", h));
            exchange.putResponseHeader("Access-Control-Max-Age", String.valueOf(Duration.ofDays(1).toSeconds()));
            exchange.putResponseHeader("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            exchange.setStatus(204);
            exchange.writeAndComplete(new byte[0]);
            return true;
        }
        return false;
    }

    protected boolean applyCorsHeaders(JettyExchange exchange) {
        String origin = exchange.getRequestHeader("Origin");
        if (corsOrigin(origin)) {
            exchange.putResponseHeader("Access-Control-Allow-Origin", origin);
            if (!exchange.hasResponseHeader("Access-Control-Allow-Credentials")) {
                exchange.putResponseHeader("Access-Control-Allow-Credentials", "true");
            }
            return true;
        }
        return false;
    }

    protected boolean corsOrigin(String origin) {
        if (getAllowedCorsDomains().isEmpty() || origin == null || origin.isBlank()) {
            return false;
        }
        origin = origin.trim();
        if (getAllowedCorsDomains().contains(origin)) {
            return true;
        }
        // Also allow matching by host suffix, e.g. allow "example.com" to match "https://app.example.com"
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            for (String domain : getAllowedCorsDomains()) {
                if (domain.startsWith("http://") || domain.startsWith("https://")) {
                    try {
                        domain = java.net.URI.create(domain).getHost();
                    } catch (Throwable ignored) {
                    }
                }
                if (domain != null && !domain.isBlank()
                    && (host.equalsIgnoreCase(domain) || host.toLowerCase().endsWith("." + domain.toLowerCase()))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    protected void sendWebRequest(JettyExchange exchange, WebRequest webRequest) {
        String namespace;
        try {
            namespace = namespaceSelector.select(webRequest);
        } catch (SecurityException e) {
            exchange.setStatus(401);
            exchange.writeAndComplete(e.getMessage().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (namespace != null) {
            forNamespace(namespace).doSendWebRequest(exchange, webRequest);
            return;
        }
        doSendWebRequest(exchange, webRequest);
    }

    protected void doSendWebRequest(JettyExchange exchange, WebRequest webRequest) {
        SerializedMessage requestMessage = webRequest.serialize(serializer);
        requestHandler.sendRequest(
                        requestMessage, m -> requestGateway.append(Guarantee.SENT, m),
                        intermediateResponse -> handleResponse(intermediateResponse, webRequest, exchange))
                .whenComplete((r, e) -> {
                    try {
                        e = unwrapException(e);
                        if (e == null) {
                            handleResponse(r, webRequest, exchange);
                        } else if (e instanceof TimeoutException) {
                            log.warn("Request {} timed out (messageId: {}). This is possibly due to a missing handler.",
                                     webRequest, webRequest.getMessageId(), e);
                            sendGatewayTimeout(exchange);
                        } else {
                            log.error("Failed to complete {} (messageId: {})",
                                      webRequest, webRequest.getMessageId(), e);
                            sendServerError(exchange);
                        }
                    } catch (Throwable t) {
                        log.error("Failed to process response {} to request {}", e == null ? r : e, webRequest, t);
                        sendServerError(exchange);
                    }
                });
    }

    @SuppressWarnings("resource")
    @SneakyThrows
    protected void handleResponse(SerializedMessage responseMessage, WebRequest webRequest, JettyExchange exchange) {
        int statusCode = WebResponse.getStatusCode(responseMessage.getMetadata());
        if (statusCode < 300 && HttpRequestMethod.WS_HANDSHAKE.equals(webRequest.getMethod())) {
            exchange.upgrade(proxyWebsocketEndpoint, createWebsocketRequestParameters(responseMessage, webRequest),
                             websocketContainer);
            return;
        }
        prepareForSending(responseMessage, exchange, statusCode);
        if (responseMessage.chunked()) {
            exchange.write(responseMessage.getData().getValue(), responseMessage.lastChunk());
        } else {
            sendResponse(responseMessage, exchange);
        }
    }

    private static Map<String, List<String>> createWebsocketRequestParameters(SerializedMessage responseMessage,
                                                                              WebRequest webRequest) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        putParameter(parameters, ProxyWebsocketEndpoint.clientIdKey, responseMessage.getMetadata().get("clientId"));
        putParameter(parameters, ProxyWebsocketEndpoint.trackerIdKey, responseMessage.getMetadata().get("trackerId"));
        webRequest.getMetadata().getEntries().forEach(
                (key, value) -> putParameter(parameters, ProxyWebsocketEndpoint.metadataPrefix + key, value));
        return Collections.unmodifiableMap(parameters);
    }

    private static void putParameter(Map<String, List<String>> parameters, String name, Object value) {
        if (value != null) {
            parameters.put(name, List.of(String.valueOf(value)));
        }
    }

    protected void prepareForSending(SerializedMessage responseMessage, JettyExchange exchange, int statusCode) {
        exchange.prepare(statusCode);
        applyCorsHeaders(exchange);
        Map<String, List<String>> headers = WebUtils.getHeaders(responseMessage.getMetadata());
        if (responseMessage.chunked() || statusMustNotHaveResponseBody(statusCode)) {
            headers.remove("Content-Length");
            headers.remove("Accept-Ranges");
        }
        headers.forEach((key, value) -> {
            if (isForwardableResponseHeader(key)) {
                exchange.addResponseHeader(key, value);
            }
        });
        if (!exchange.hasResponseHeader("Content-Type")) {
            ofNullable(responseMessage.getData().getFormat()).ifPresent(
                    format -> exchange.addResponseHeader("Content-Type", format));
        }
        if (!responseMessage.chunked()
            && !statusMustNotHaveResponseBody(statusCode)
            && !exchange.hasResponseHeader("Content-Length")) {
            exchange.putResponseHeader("Content-Length",
                                       String.valueOf(responseMessage.getData().getValue().length));
        }
    }

    protected void sendResponse(SerializedMessage responseMessage, JettyExchange exchange) {
        exchange.writeAndComplete(responseMessage.getData().getValue());
    }

    private static boolean statusMustNotHaveResponseBody(int statusCode) {
        return statusCode >= 100 && statusCode < 200
               || statusCode == HttpStatus.NO_CONTENT_204
               || statusCode == HttpStatus.NOT_MODIFIED_304;
    }

    private static boolean isRequestBodyTooLarge(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return error instanceof IllegalStateException
               && message != null
               && message.contains("Max size")
               && message.contains("exceeded");
    }

    private static boolean isForwardableResponseHeader(String name) {
        return name != null && !name.isBlank()
               && !name.startsWith(":")
               && HOP_BY_HOP_RESPONSE_HEADERS.stream().noneMatch(name::equalsIgnoreCase);
    }

    protected void sendServerError(JettyExchange exchange) {
        try {
            if (!exchange.isCommitted()) {
                exchange.setStatus(500);
                exchange.writeAndComplete("Request could not be handled due to a server side error"
                                                  .getBytes(StandardCharsets.UTF_8));
            } else {
                exchange.fail(new IllegalStateException("Request could not be handled due to a server side error"));
            }
        } catch (Throwable t) {
            log.error("Failed to send server error response", t);
            exchange.fail(t);
        }
    }

    protected void sendGatewayTimeout(JettyExchange exchange) {
        exchange.setStatus(504);
        exchange.writeAndComplete("Did not receive a response in time".getBytes(StandardCharsets.UTF_8));
    }

    private void sendPayloadTooLarge(JettyExchange exchange) {
        exchange.setStatus(HttpStatus.PAYLOAD_TOO_LARGE_413);
        exchange.writeAndComplete("Request body is too large".getBytes(StandardCharsets.UTF_8));
    }

    private void sendServiceUnavailable(JettyExchange exchange) {
        exchange.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
        exchange.writeAndComplete("Request handler has been shut down and is not accepting new requests"
                                          .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        close(true);
    }

    void close(boolean gracefulWebsocketShutdown) {
        if (closed.compareAndSet(false, true)) {
            proxyWebsocketEndpoint.shutDown(gracefulWebsocketShutdown
                                                    ? ProxyWebsocketEndpoint.CLOSE_NOTIFICATION_TIMEOUT
                                                    : SERVER_SHUTDOWN_CLOSE_TIMEOUT,
                                            gracefulWebsocketShutdown,
                                            gracefulWebsocketShutdown);
            requestHandler.close();
            requestGateway.close();
            super.close();
        }
    }

    protected static class JettyExchange {
        private final Request request;
        private final Response response;
        private final Callback callback;
        private final long maxRequestBodySize;
        private final long maxMultipartRequestBodySize;
        private final int maxPendingWebsocketSends;
        private final String namespace;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final Object writeLock = new Object();
        private CompletableFuture<Void> writeChain = CompletableFuture.completedFuture(null);
        private boolean prepared;
        private boolean noResponseBody;

        JettyExchange(Request request, Response response, Callback callback,
                      long maxRequestBodySize, long maxMultipartRequestBodySize, int maxPendingWebsocketSends,
                      String namespace) {
            this.request = request;
            this.response = response;
            this.callback = callback;
            this.maxRequestBodySize = maxRequestBodySize;
            this.maxMultipartRequestBodySize = maxMultipartRequestBodySize;
            this.maxPendingWebsocketSends = maxPendingWebsocketSends;
            this.namespace = namespace;
        }

        String getMethod() {
            return request.getMethod();
        }

        String getPathQuery() {
            String pathQuery = request.getHttpURI().getPathQuery();
            return pathQuery == null || pathQuery.isBlank() ? "/" : pathQuery;
        }

        org.eclipse.jetty.http.HttpFields getRequestHeaders() {
            return request.getHeaders();
        }

        String getRequestHeader(String name) {
            return request.getHeaders().get(name);
        }

        boolean hasRequestHeader(String name) {
            return request.getHeaders().contains(name);
        }

        boolean hasResponseHeader(String name) {
            return response.getHeaders().contains(name);
        }

        void addResponseHeader(String name, List<String> values) {
            values.forEach(value -> response.getHeaders().add(name, value));
        }

        void addResponseHeader(String name, String value) {
            response.getHeaders().add(name, value);
        }

        void putResponseHeader(String name, String value) {
            response.getHeaders().put(name, value);
        }

        void setStatus(int statusCode) {
            response.setStatus(statusCode);
            if (statusMustNotHaveResponseBody(statusCode)) {
                noResponseBody = true;
            }
        }

        void prepare(int statusCode) {
            if (!prepared) {
                prepared = true;
                response.setStatus(statusCode);
                if (statusMustNotHaveResponseBody(statusCode)) {
                    noResponseBody = true;
                }
            }
        }

        boolean isCommitted() {
            return response.isCommitted();
        }

        void writeAndComplete(byte[] payload) {
            write(payload, true);
        }

        void write(byte[] payload, boolean last) {
            CompletableFuture<Void> write;
            synchronized (writeLock) {
                write = writeChain.thenCompose(ignored -> writeNow(payload, last));
                writeChain = write;
            }
            write.whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(error);
                } else if (last) {
                    complete();
                }
            });
        }

        void upgrade(ProxyWebsocketEndpoint endpoint, Map<String, List<String>> requestParameters,
                     ServerWebSocketContainer container) {
            if (container == null) {
                fail(new IllegalStateException("Websocket container is not available"));
                return;
            }
            boolean upgraded = container.upgrade((request, response, callback) -> {
                var protocols = request.getSubProtocols();
                if (!protocols.isEmpty()) {
                    response.setAcceptedSubProtocol(protocols.getFirst());
                }
                return new JettyProxyWebsocketAdapter(endpoint, requestParameters, maxPendingWebsocketSends,
                                                      namespace);
            }, request, response, callback);
            if (!upgraded) {
                setStatus(HttpStatus.BAD_REQUEST_400);
                writeAndComplete("Could not upgrade request to websocket".getBytes(StandardCharsets.UTF_8));
            }
        }

        int maxRequestBodySizeAsInt() {
            long maxSize = maxRequestBodySize();
            if (maxSize > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) maxSize;
        }

        long maxRequestBodySize() {
            String contentType = getRequestHeader("Content-Type");
            if (contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, "multipart/".length())) {
                return maxMultipartRequestBodySize;
            }
            return maxRequestBodySize;
        }

        private CompletableFuture<Void> writeNow(byte[] payload, boolean last) {
            try {
                CompletableFuture<Void> write = new CompletableFuture<>();
                response.write(last, noResponseBody ? null : ByteBuffer.wrap(payload), Callback.from(write));
                return write;
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                callback.succeeded();
            }
        }

        private void fail(Throwable error) {
            if (completed.compareAndSet(false, true)) {
                callback.failed(error);
            }
        }
    }
}
