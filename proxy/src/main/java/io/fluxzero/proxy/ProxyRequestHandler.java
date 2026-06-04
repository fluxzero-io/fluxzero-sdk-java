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

package io.fluxzero.proxy;

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.DefaultRequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.sdk.web.WebUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getLongProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.mapProperty;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

@Slf4j
public class ProxyRequestHandler extends AbstractNamespaced<ProxyRequestHandler> {

    public static final String CORS_DOMAINS_PROPERTY = "FLUXZERO_CORS_DOMAINS";
    public static final String REQUEST_TIMEOUT_SECONDS_PROPERTY = "FLUXZERO_PROXY_REQUEST_TIMEOUT_SECONDS";

    /**
     * Enables publishing large or unknown-length request bodies as chunked web request messages.
     * <p>
     * Defaults to {@code false} for backward compatibility with applications that still run older SDK clients.
     */
    public static final String REQUEST_CHUNKING_ENABLED_PROPERTY = "FLUXZERO_PROXY_REQUEST_CHUNKING_ENABLED";

    /**
     * Maximum payload bytes per web request chunk when {@link #REQUEST_CHUNKING_ENABLED_PROPERTY} is enabled.
     */
    public static final String REQUEST_CHUNK_SIZE_PROPERTY = "FLUXZERO_PROXY_REQUEST_CHUNK_SIZE";
    static final Duration SERVER_SHUTDOWN_CLOSE_TIMEOUT = Duration.ofSeconds(1);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(200);

    @Getter
    private final Client client;

    private final ProxySerializer serializer = new ProxySerializer();
    private final GatewayClient requestGateway;
    private final CancellableRequestHandler requestHandler;
    private final ProxyWebsocketEndpoint proxyWebsocketEndpoint;
    private final NamespaceSelector namespaceSelector;
    private final ExecutorService chunkedRequestExecutor;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long maxRequestBodySize = ProxyServer.DEFAULT_MAX_REQUEST_BODY_SIZE;
    private volatile long maxMultipartRequestBodySize = ProxyServer.DEFAULT_MAX_MULTIPART_REQUEST_BODY_SIZE;
    private volatile boolean requestChunkingEnabled = getBooleanProperty(REQUEST_CHUNKING_ENABLED_PROPERTY, true);
    private volatile int requestChunkSize = validateRequestChunkSize(
            getLongProperty(REQUEST_CHUNK_SIZE_PROPERTY,
                            Long.valueOf(io.fluxzero.sdk.web.WebResponseGateway.MAX_RESPONSE_SIZE)));
    private final InFlightWebRequestLimiter inFlightWebRequests =
            new InFlightWebRequestLimiter(ProxyServer.DEFAULT_MAX_IN_FLIGHT_WEB_REQUESTS);
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
        requestHandler = new CancellableRequestHandler(
                client, MessageType.WEBRESPONSE,
                Duration.ofSeconds(getLongProperty(REQUEST_TIMEOUT_SECONDS_PROPERTY, REQUEST_TIMEOUT.toSeconds())),
                format("%s_%s", client.name(), "$proxy-request-handler"));
        proxyWebsocketEndpoint = new ProxyWebsocketEndpoint(client, requestHandler);
        this.namespaceSelector = namespaceSelector;
        chunkedRequestExecutor = newWorkerPool(format("%s_%s", client.name(), "$proxy-chunked-request"), 8);
    }

    @Override
    protected ProxyRequestHandler createForNamespace(String namespace) {
        if (Objects.equals(client.namespace(), namespace)) {
            return this;
        }
        ProxyRequestHandler namespacedHandler = new ProxyRequestHandler(client.forNamespace(namespace), namespaceSelector);
        namespacedHandler.setMaxRequestBodySize(maxRequestBodySize);
        namespacedHandler.setMaxMultipartRequestBodySize(maxMultipartRequestBodySize);
        namespacedHandler.setRequestChunkingEnabled(requestChunkingEnabled);
        namespacedHandler.setRequestChunkSize(requestChunkSize);
        namespacedHandler.setMaxInFlightWebRequests(inFlightWebRequests.max());
        namespacedHandler.setMaxPendingWebsocketSends(maxPendingWebsocketSends);
        namespacedHandler.setWebsocketContainer(websocketContainer);
        return namespacedHandler;
    }

    static int validateRequestChunkSize(long requestChunkSize) {
        if (requestChunkSize <= 0 || requestChunkSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    REQUEST_CHUNK_SIZE_PROPERTY + " must be greater than 0 and at most " + Integer.MAX_VALUE);
        }
        return (int) requestChunkSize;
    }

    void setMaxRequestBodySize(long maxRequestBodySize) {
        this.maxRequestBodySize = maxRequestBodySize;
    }

    void setMaxMultipartRequestBodySize(long maxMultipartRequestBodySize) {
        this.maxMultipartRequestBodySize = maxMultipartRequestBodySize;
    }

    void setRequestChunkingEnabled(boolean requestChunkingEnabled) {
        this.requestChunkingEnabled = requestChunkingEnabled;
    }

    void setRequestChunkSize(int requestChunkSize) {
        this.requestChunkSize = validateRequestChunkSize(requestChunkSize);
    }

    void setMaxInFlightWebRequests(int maxInFlightWebRequests) {
        if (maxInFlightWebRequests < 0) {
            throw new IllegalArgumentException(ProxyServer.MAX_IN_FLIGHT_WEB_REQUESTS_PROPERTY + " must be >= 0");
        }
        inFlightWebRequests.setMax(maxInFlightWebRequests);
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
            if (knownRequestBodyTooLarge(exchange)) {
                log.debug("Rejected request body larger than {} bytes", exchange.maxRequestBodySize());
                sendPayloadTooLarge(exchange);
                return true;
            }
            InFlightWebRequestLimiter.Permit requestPermit = inFlightWebRequests.tryAcquire();
            if (requestPermit == null) {
                log.debug("Rejected request because {} proxy web requests are already in flight",
                          inFlightWebRequests.max());
                sendRequestBacklogUnavailable(exchange);
                return true;
            }
            JettyExchange acceptedExchange = requestPermit.tracked()
                    ? new JettyExchange(request, response, releasePermitOnCompletion(callback, requestPermit),
                                        maxRequestBodySize, maxMultipartRequestBodySize,
                                        maxPendingWebsocketSends, client.namespace())
                    : exchange;
            handleAcceptedRequest(request, acceptedExchange);
        } catch (Throwable e) {
            log.error("Failed to handle incoming request", e);
            sendServerError(exchange);
        }
        return true;
    }

    private void handleAcceptedRequest(Request request, JettyExchange exchange) {
        try {
            if (isWebsocketUpgrade(exchange)) {
                sendWebRequest(exchange, createWebRequest(exchange, new byte[0]), false);
                return;
            }
            if (shouldChunkRequest(exchange)) {
                WebRequest webRequest = createWebRequest(exchange, new byte[0]);
                sendWebRequest(exchange, webRequest, true);
                return;
            }
            readRequestBody(request, exchange.maxRequestBodySizeAsInt())
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
                            sendWebRequest(exchange, createWebRequest(exchange, payload), false);
                        } catch (Throwable e) {
                            log.error("Failed to create request", e);
                            sendServerError(exchange);
                        }
                    });
        } catch (Throwable e) {
            log.error("Failed to handle incoming request", e);
            sendServerError(exchange);
        }
    }

    private CompletableFuture<byte[]> readRequestBody(Request request, int maxSize) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Content.Source.asByteArrayAsync(request, maxSize, Promise.Invocable.toPromise(result));
        return result;
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

    protected boolean knownRequestBodyTooLarge(JettyExchange exchange) {
        long contentLength = exchange.getRequestBodyLength();
        return contentLength > exchange.maxRequestBodySize();
    }

    protected boolean shouldChunkRequest(JettyExchange exchange) {
        if (!requestChunkingEnabled) {
            return false;
        }
        long contentLength = exchange.getRequestBodyLength();
        return contentLength > requestChunkSize
               || contentLength < 0 && methodMayHaveRequestBody(exchange.getMethod());
    }

    protected boolean isWebsocketUpgrade(JettyExchange exchange) {
        return HttpRequestMethod.GET.equals(exchange.getMethod())
               && headerContainsToken(exchange.getRequestHeader("Connection"), "Upgrade")
               && "websocket".equalsIgnoreCase(exchange.getRequestHeader("Upgrade"));
    }

    private static boolean methodMayHaveRequestBody(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
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

    protected void sendWebRequest(JettyExchange exchange, WebRequest webRequest, boolean chunked) {
        String namespace;
        try {
            namespace = namespaceSelector.select(webRequest);
        } catch (SecurityException e) {
            exchange.setStatus(401);
            exchange.writeAndComplete(e.getMessage().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (namespace != null) {
            forNamespace(namespace).sendResolvedWebRequest(exchange, webRequest, chunked);
            return;
        }
        sendResolvedWebRequest(exchange, webRequest, chunked);
    }

    protected void sendResolvedWebRequest(JettyExchange exchange, WebRequest webRequest, boolean chunked) {
        if (chunked) {
            doSendChunkedWebRequest(exchange, webRequest);
        } else {
            doSendWebRequest(exchange, webRequest);
        }
    }

    protected void doSendWebRequest(JettyExchange exchange, WebRequest webRequest) {
        ProxyResponseContext responseContext = createResponseContext(webRequest, exchange);
        SerializedMessage requestMessage = webRequest.serialize(serializer);
        requestHandler.sendRequest(
                        requestMessage, m -> requestGateway.append(Guarantee.SENT, m),
                        intermediateResponse -> handleResponse(intermediateResponse, responseContext))
                .whenComplete((r, e) -> completeResponse(r, e, responseContext));
    }

    protected ProxyResponseContext createResponseContext(WebRequest webRequest, JettyExchange exchange) {
        return new ProxyResponseContext(webRequest, exchange);
    }

    protected void doSendChunkedWebRequest(JettyExchange exchange, WebRequest webRequest) {
        CompletableFuture.runAsync(() -> readChunkedWebRequest(exchange, webRequest), chunkedRequestExecutor);
    }

    protected void readChunkedWebRequest(JettyExchange exchange, WebRequest webRequest) {
        ChunkedProxyRequest chunkedRequest = null;
        ProxyResponseContext responseContext = createResponseContext(webRequest, exchange);
        ChunkAccumulator pending = new ChunkAccumulator(requestChunkSize);
        long contentLength = exchange.getRequestBodyLength();
        long totalRead = 0L;
        try {
            InputStream input = exchange.getRequestInputStream();
            int read;
            while ((read = pending.readFrom(input)) >= 0) {
                if (read == 0) {
                    continue;
                }
                totalRead += read;
                if (totalRead > exchange.maxRequestBodySize()) {
                    if (chunkedRequest != null) {
                        chunkedRequest.abort(new IOException("Request body is too large"));
                    }
                    sendPayloadTooLarge(exchange);
                    return;
                }
                boolean last = contentLength >= 0 && totalRead == contentLength;
                chunkedRequest = dispatchReadableChunks(webRequest, pending, last, chunkedRequest);
                if (last) {
                    break;
                }
            }
            if (contentLength >= 0 && totalRead < contentLength) {
                throw new IOException("Early EOF while reading request body. Expected " + contentLength
                                      + " bytes, received " + totalRead);
            }
            if (chunkedRequest == null) {
                doSendWebRequest(exchange, webRequest.withPayload(pending.takeBytes()));
                return;
            }
            if (!chunkedRequest.hasDispatchedFinalChunk()) {
                chunkedRequest = dispatchReadableChunks(webRequest, pending, true, chunkedRequest);
            }
            chunkedRequest.dispatchFuture().join();
            ChunkedProxyRequest finalChunkedRequest = chunkedRequest;
            finalChunkedRequest.responseFuture()
                    .whenComplete((response, error) -> completeChunkedResponse(finalChunkedRequest, response, error,
                                                                                responseContext));
        } catch (Throwable e) {
            Throwable failure = unwrapException(e);
            if (isClientDisconnect(failure)) {
                log.debug("Incoming request body ended before the final chunk", failure);
            } else {
                log.error("Failed to read incoming message", failure);
            }
            if (chunkedRequest != null) {
                chunkedRequest.abort(failure);
            }
            completeResponse(null, failure, responseContext);
        }
    }

    protected ChunkedProxyRequest dispatchReadableChunks(WebRequest webRequest, ChunkAccumulator pending, boolean last,
                                                         ChunkedProxyRequest chunkedRequest) {
        while (pending.isFull() || last && pending.hasBytes()) {
            if (chunkedRequest == null) {
                chunkedRequest = new ChunkedProxyRequest(webRequest);
            }
            boolean finalChunk = last;
            chunkedRequest.append(pending.takeChunk(), finalChunk);
        }
        if (last && !pending.hasBytes() && chunkedRequest != null && !chunkedRequest.hasDispatchedFinalChunk()) {
            chunkedRequest.append(new byte[0], true);
        }
        return chunkedRequest;
    }

    protected SerializedMessage createChunkedRequestMessage(WebRequest webRequest, byte[] chunk, long chunkIndex,
                                                            boolean firstChunk, boolean finalChunk) {
        SerializedMessage chunkMessage = new SerializedMessage(
                new Data<>(chunk, byte[].class.getName(), 0, "application/octet-stream"),
                webRequest.getMetadata().with(
                        HasMetadata.CHUNK_INDEX, chunkIndex,
                        HasMetadata.FIRST_CHUNK, Boolean.toString(firstChunk),
                        HasMetadata.FINAL_CHUNK, Boolean.toString(finalChunk)),
                webRequest.getMessageId(), webRequest.getTimestamp().toEpochMilli());
        return chunkMessage;
    }

    protected static class ChunkAccumulator {
        private final int chunkSize;
        private byte[] buffer;
        private int size;

        protected ChunkAccumulator(int chunkSize) {
            this.chunkSize = chunkSize;
            this.buffer = new byte[chunkSize];
        }

        protected int readFrom(InputStream input) throws IOException {
            if (isFull()) {
                return 0;
            }
            int read = input.read(buffer, size, chunkSize - size);
            if (read > 0) {
                size += read;
            }
            return read;
        }

        protected boolean isFull() {
            return size == chunkSize;
        }

        protected boolean hasBytes() {
            return size > 0;
        }

        protected byte[] takeChunk() {
            if (isFull()) {
                byte[] chunk = buffer;
                buffer = new byte[chunkSize];
                size = 0;
                return chunk;
            }
            return takeBytes();
        }

        protected byte[] takeBytes() {
            byte[] chunk = size == 0 ? new byte[0] : Arrays.copyOf(buffer, size);
            size = 0;
            return chunk;
        }
    }

    protected void completeChunkedResponse(ChunkedProxyRequest chunkedRequest, SerializedMessage response,
                                           Throwable error, ProxyResponseContext responseContext) {
        if (error == null) {
            chunkedRequest.intermediateResponses().forEach(r -> handleResponse(r, responseContext));
        }
        completeResponse(response, error, responseContext);
    }

    protected void completeResponse(SerializedMessage response, Throwable error, ProxyResponseContext responseContext) {
        try {
            error = unwrapException(error);
            if (error == null) {
                handleResponse(response, responseContext);
            } else if (error instanceof TimeoutException) {
                log.warn("Request {} timed out (messageId: {}). This is possibly due to a missing handler.",
                         responseContext.description(), responseContext.messageId(), error);
                sendGatewayTimeout(responseContext.exchange());
            } else if (isRequestBodyReadTimeout(error)) {
                log.debug("Request {} timed out while reading the request body (messageId: {})",
                          responseContext.description(), responseContext.messageId(), error);
                sendRequestTimeout(responseContext.exchange());
            } else if (isClientDisconnect(error)) {
                log.debug("Request {} disconnected before the request body was complete (messageId: {})",
                          responseContext.description(), responseContext.messageId(), error);
                responseContext.exchange().fail(error);
            } else {
                log.error("Failed to complete {} (messageId: {})",
                          responseContext.description(), responseContext.messageId(), error);
                sendServerError(responseContext.exchange());
            }
        } catch (Throwable t) {
            log.error("Failed to process response {} to request {}",
                      error == null ? response : error, responseContext.description(), t);
            sendServerError(responseContext.exchange());
        }
    }

    protected class ChunkedProxyRequest {
        private final WebRequest webRequest;
        private final List<SerializedMessage> intermediateResponses = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CompletableFuture<SerializedMessage> responseFuture = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<Void>> lastDispatch =
                new AtomicReference<>(CompletableFuture.completedFuture(null));
        private final AtomicBoolean finalChunkDispatched = new AtomicBoolean();
        private final List<SerializedMessage> pendingContinuations = new ArrayList<>();
        private long nextChunkIndex;
        private int pendingContinuationBytes;
        private SerializedMessage firstChunk;

        protected ChunkedProxyRequest(WebRequest webRequest) {
            this.webRequest = webRequest;
        }

        protected synchronized void append(byte[] chunk, boolean finalChunk) {
            if (finalChunkDispatched.get()) {
                throw new IllegalStateException("Cannot append chunks after the final chunk has been dispatched");
            }
            if (firstChunk == null) {
                sendFirstChunk(chunk, finalChunk);
                return;
            }
            SerializedMessage continuation = prepareContinuation(createChunkedRequestMessage(
                    webRequest, chunk, nextChunkIndex++, false, finalChunk));
            if (finalChunk) {
                finalChunkDispatched.set(true);
            }
            pendingContinuations.add(continuation);
            pendingContinuationBytes += chunk.length;
            if (finalChunk || pendingContinuationBytes >= continuationBatchByteLimit()) {
                flushContinuations();
            }
        }

        protected CompletableFuture<Void> dispatchFuture() {
            return lastDispatch.get();
        }

        protected boolean hasDispatchedFinalChunk() {
            return finalChunkDispatched.get();
        }

        protected CompletableFuture<SerializedMessage> responseFuture() {
            return responseFuture;
        }

        protected List<SerializedMessage> intermediateResponses() {
            return intermediateResponses;
        }

        protected synchronized void abort(Throwable error) {
            if (firstChunk != null && firstChunk.getRequestId() != null) {
                requestHandler.cancelRequest(firstChunk.getRequestId(), error);
            }
            responseFuture.completeExceptionally(error);
        }

        private void sendFirstChunk(byte[] chunk, boolean finalChunk) {
            firstChunk = createChunkedRequestMessage(webRequest, chunk, nextChunkIndex++, true, finalChunk);
            if (finalChunk) {
                finalChunkDispatched.set(true);
            }
            firstChunk.setSegment(ConsistentHashing.computeSegment(firstChunk.getMessageId()));
            AtomicReference<CompletableFuture<Void>> initialDispatch =
                    new AtomicReference<>(CompletableFuture.completedFuture(null));
            requestHandler.sendRequest(firstChunk, message -> {
                try {
                    initialDispatch.set(requestGateway.append(Guarantee.SENT, message));
                } catch (Throwable e) {
                    initialDispatch.set(CompletableFuture.failedFuture(e));
                    throw e;
                }
            }, null, intermediateResponses::add).whenComplete((response, error) -> {
                if (error == null) {
                    responseFuture.complete(response);
                } else {
                    responseFuture.completeExceptionally(unwrapException(error));
                }
            });
            dispatch(initialDispatch.get());
        }

        private SerializedMessage prepareContinuation(SerializedMessage chunk) {
            chunk.setMessageId(firstChunk.getMessageId());
            chunk.setRequestId(firstChunk.getRequestId());
            chunk.setSource(firstChunk.getSource());
            chunk.setSegment(firstChunk.getSegment());
            return chunk;
        }

        private void flushContinuations() {
            if (pendingContinuations.isEmpty()) {
                return;
            }
            SerializedMessage[] batch = pendingContinuations.toArray(SerializedMessage[]::new);
            pendingContinuations.clear();
            pendingContinuationBytes = 0;
            dispatch(lastDispatch.get().thenCompose(ignored -> requestGateway.append(Guarantee.SENT, batch)));
        }

        private int continuationBatchByteLimit() {
            return Math.max(requestChunkSize, io.fluxzero.sdk.web.WebResponseGateway.MAX_RESPONSE_SIZE);
        }

        private void dispatch(CompletableFuture<Void> future) {
            lastDispatch.set(future);
            future.whenComplete((ignored, error) -> {
                if (error != null) {
                    responseFuture.completeExceptionally(unwrapException(error));
                }
            });
        }
    }

    private static class CancellableRequestHandler extends DefaultRequestHandler {
        CancellableRequestHandler(Client client, MessageType resultType, Duration timeout,
                                  String responseConsumerName) {
            super(client, resultType, timeout, responseConsumerName);
        }

        void cancelRequest(int requestId, Throwable error) {
            completeRequestExceptionally(requestId, error);
        }
    }

    private static class InFlightWebRequestLimiter {
        private static final Permit UNTRACKED_PERMIT = new Permit(null);

        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile int max;

        InFlightWebRequestLimiter(int max) {
            setMax(max);
        }

        void setMax(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("max must be >= 0");
            }
            this.max = max;
        }

        int max() {
            return max;
        }

        Permit tryAcquire() {
            int limit = max;
            if (limit == 0) {
                return UNTRACKED_PERMIT;
            }
            while (true) {
                int current = inFlight.get();
                if (current >= limit) {
                    return null;
                }
                if (inFlight.compareAndSet(current, current + 1)) {
                    return new Permit(this);
                }
            }
        }

        private void release() {
            inFlight.decrementAndGet();
        }

        private record Permit(InFlightWebRequestLimiter limiter) {
            boolean tracked() {
                return limiter != null;
            }

            void release() {
                if (limiter != null) {
                    limiter.release();
                }
            }
        }
    }

    protected static class ProxyResponseContext {
        private final JettyExchange exchange;
        private final String method;
        private final String path;
        private final String messageId;
        private final Map<String, String> requestMetadata;

        ProxyResponseContext(WebRequest webRequest, JettyExchange exchange) {
            this.exchange = exchange;
            this.method = webRequest.getMethod();
            this.path = webRequest.getPath();
            this.messageId = webRequest.getMessageId();
            this.requestMetadata = Map.copyOf(webRequest.getMetadata().getEntries());
        }

        JettyExchange exchange() {
            return exchange;
        }

        String method() {
            return method;
        }

        String messageId() {
            return messageId;
        }

        Map<String, String> requestMetadata() {
            return requestMetadata;
        }

        String description() {
            return method + " " + path;
        }
    }

    @SuppressWarnings("resource")
    @SneakyThrows
    protected void handleResponse(SerializedMessage responseMessage, ProxyResponseContext responseContext) {
        int statusCode = WebResponse.getStatusCode(responseMessage.getMetadata());
        if (statusCode < 300 && HttpRequestMethod.WS_HANDSHAKE.equals(responseContext.method())) {
            responseContext.exchange().upgrade(
                    proxyWebsocketEndpoint, createWebsocketRequestParameters(responseMessage, responseContext),
                    websocketContainer);
            return;
        }
        prepareForSending(responseMessage, responseContext.exchange(), statusCode);
        if (responseMessage.chunked()) {
            responseContext.exchange().write(responseMessage.getData().getValue(), responseMessage.lastChunk());
        } else {
            sendResponse(responseMessage, responseContext.exchange());
        }
    }

    private static Map<String, List<String>> createWebsocketRequestParameters(SerializedMessage responseMessage,
                                                                              ProxyResponseContext responseContext) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        putParameter(parameters, ProxyWebsocketEndpoint.clientIdKey, responseMessage.getMetadata().get("clientId"));
        putParameter(parameters, ProxyWebsocketEndpoint.trackerIdKey, responseMessage.getMetadata().get("trackerId"));
        responseContext.requestMetadata().forEach(
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

    private static boolean isClientDisconnect(Throwable error) {
        String className = error == null ? null : error.getClass().getName();
        String message = error == null ? null : error.getMessage();
        return className != null && className.toLowerCase().contains("eof")
               || message != null && message.toLowerCase().contains("early eof")
               || isRequestBodyReadTimeout(error);
    }

    private static boolean isRequestBodyReadTimeout(Throwable error) {
        return error instanceof IOException && error.getCause() instanceof TimeoutException;
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

    protected void sendRequestTimeout(JettyExchange exchange) {
        exchange.setStatus(HttpStatus.REQUEST_TIMEOUT_408);
        exchange.writeAndComplete("Timed out while reading request body".getBytes(StandardCharsets.UTF_8));
    }

    private void sendPayloadTooLarge(JettyExchange exchange) {
        exchange.setStatus(HttpStatus.PAYLOAD_TOO_LARGE_413);
        exchange.writeAndComplete("Request body is too large".getBytes(StandardCharsets.UTF_8));
    }

    private void sendRequestBacklogUnavailable(JettyExchange exchange) {
        exchange.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
        exchange.writeAndComplete("Too many in-flight proxy requests".getBytes(StandardCharsets.UTF_8));
    }

    private void sendServiceUnavailable(JettyExchange exchange) {
        exchange.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
        exchange.writeAndComplete("Request handler has been shut down and is not accepting new requests"
                                          .getBytes(StandardCharsets.UTF_8));
    }

    private static Callback releasePermitOnCompletion(Callback callback, InFlightWebRequestLimiter.Permit permit) {
        return new Callback() {
            private final AtomicBoolean completed = new AtomicBoolean();

            @Override
            public void succeeded() {
                release();
                callback.succeeded();
            }

            @Override
            public void failed(Throwable x) {
                release();
                callback.failed(x);
            }

            private void release() {
                if (completed.compareAndSet(false, true)) {
                    permit.release();
                }
            }
        };
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
            chunkedRequestExecutor.shutdown();
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

        long getRequestBodyLength() {
            if (request.getHeaders().get(HttpHeader.CONTENT_LENGTH) == null) {
                return -1L;
            }
            try {
                return request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        InputStream getRequestInputStream() {
            return Request.asInputStream(request);
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
