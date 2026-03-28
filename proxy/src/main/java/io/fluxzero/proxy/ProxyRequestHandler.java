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
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.sdk.web.WebUtils;
import io.undertow.Undertow;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.util.HttpString;
import io.undertow.util.Protocols;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import jakarta.servlet.DispatcherType;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.channels.Channels;
import org.xnio.channels.StreamSourceChannel;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getBooleanProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.mapProperty;
import static io.undertow.servlet.Servlets.deployment;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

@Slf4j
public class ProxyRequestHandler extends AbstractNamespaced<ProxyRequestHandler> implements HttpHandler {

    public static final String CORS_DOMAINS_PROPERTY = "FLUXZERO_CORS_DOMAINS";
    public static final String REQUEST_CHUNK_SIZE_PROPERTY = "FLUXZERO_PROXY_REQUEST_CHUNK_SIZE";
    public static final String LOG_CHUNK_DISPATCHES_PROPERTY = "FLUXZERO_PROXY_LOG_CHUNK_DISPATCHES";
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(200);

    @Getter
    private final Client client;

    private final int maxRequestSize;
    private final Duration requestTimeout;
    private final ProxySerializer serializer = new ProxySerializer();
    private final GatewayClient requestGateway;
    private final RequestHandler requestHandler;
    private final WebsocketEndpoint websocketEndpoint;
    private final HttpHandler websocketHandler;
    private final NamespaceSelector namespaceSelector;
    private final ExecutorService chunkedResponseExecutor;
    private final boolean logChunkDispatches;

    private final AtomicBoolean closed = new AtomicBoolean();
    @Getter(lazy = true)
    private final Set<String> allowedCorsDomains = mapProperty(CORS_DOMAINS_PROPERTY, p -> Arrays.stream(p.split(",")).map(String::trim)
            .filter(d -> !d.isEmpty()).collect(Collectors.toSet()), Set::of);

    public ProxyRequestHandler(Client client) {
        this(client, new NamespaceSelector(),
             validateRequestChunkSize(getIntegerProperty(REQUEST_CHUNK_SIZE_PROPERTY, WebUtils.DEFAULT_CHUNK_SIZE)),
             DEFAULT_REQUEST_TIMEOUT);
    }

    protected ProxyRequestHandler(Client client, int maxRequestSize, Duration requestTimeout) {
        this(client, new NamespaceSelector(), validateRequestChunkSize(maxRequestSize), requestTimeout);
    }

    private ProxyRequestHandler(Client client, NamespaceSelector namespaceSelector, int maxRequestSize,
                                Duration requestTimeout) {
        this.client = client;
        this.maxRequestSize = maxRequestSize;
        this.requestTimeout = requestTimeout;
        requestGateway = client.getGatewayClient(MessageType.WEBREQUEST);
        requestHandler = new DefaultRequestHandler(client, MessageType.WEBRESPONSE, requestTimeout,
                                                   format("%s_%s", client.name(), "$proxy-request-handler"));
        chunkedResponseExecutor = newWorkerPool(format("%s_%s", client.name(), "$proxy-chunked-response"), 8);
        websocketEndpoint = new WebsocketEndpoint(client);
        websocketHandler = createWebsocketHandler();
        this.namespaceSelector = namespaceSelector;
        logChunkDispatches = getBooleanProperty(LOG_CHUNK_DISPATCHES_PROPERTY);
    }

    protected static int validateRequestChunkSize(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException(REQUEST_CHUNK_SIZE_PROPERTY + " must be greater than 0");
        }
        return chunkSize;
    }

    @Override
    protected ProxyRequestHandler createForNamespace(String namespace) {
        return Objects.equals(client.namespace(), namespace)
                ? this : new ProxyRequestHandler(client.forNamespace(namespace), namespaceSelector, maxRequestSize,
                                                 requestTimeout);
    }

    @Override
    @SneakyThrows
    public void handleRequest(HttpServerExchange exchange) {
        if (closed.get()) {
            throw new IllegalStateException("Request handler has been shut down and is not accepting new requests");
        }
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (shouldChunkRequest(exchange)) {
            try {
                sendWebRequest(exchange, createWebRequest(exchange, new byte[0]), true);
            } catch (Throwable e) {
                log.error("Failed to stream incoming message", e);
                sendServerError(exchange);
            }
            return;
        }
        exchange.getRequestReceiver().receiveFullBytes(
                (se, payload) -> se.dispatch(() -> {
                    try {
                        sendWebRequest(se, createWebRequest(se, payload), false);
                    } catch (Throwable e) {
                        log.error("Failed to create request", e);
                        sendServerError(se);
                    }
                }),
                (se, error) -> se.dispatch(() -> {
                    log.error("Failed to read incoming message", error);
                    sendServerError(se);
                }));
    }

    protected WebRequest createWebRequest(HttpServerExchange se, byte[] payload) {
        var builder = WebRequest.builder()
                .url(se.getRelativePath() + (se.getQueryString().isBlank() ? "" : ("?" + se.getQueryString())))
                .method(se.getRequestMethod().toString()).payload(payload)
                .acceptGzipEncoding(false);
        se.getRequestHeaders().forEach(
                header -> header.forEach(value -> builder.header(header.getHeaderName().toString(), value)));
        return tryUpgrade(builder.build(), se);
    }

    protected boolean shouldChunkRequest(HttpServerExchange exchange) {
        long contentLength = exchange.getRequestContentLength();
        return contentLength < 0 || contentLength > maxRequestSize;
    }

    protected WebRequest tryUpgrade(WebRequest webRequest, HttpServerExchange se) {
        if (HttpRequestMethod.GET.equals(webRequest.getMethod())
            && "Upgrade".equalsIgnoreCase(webRequest.getHeader("Connection"))
            && "websocket".equalsIgnoreCase(webRequest.getHeader("Upgrade"))) {
            var requestBuilder = webRequest.toBuilder();
            var protocols = getWebsocketProtocols(webRequest.getHeaders("Sec-WebSocket-Protocol"));
            if (!protocols.isEmpty() && protocols.size() % 2 == 0) {
                for (int i = 0; i < protocols.size(); i += 2) {
                    try {
                        var name = URLDecoder.decode(protocols.get(i), StandardCharsets.UTF_8);
                        var value = URLDecoder.decode(protocols.get(i + 1), StandardCharsets.UTF_8);
                        requestBuilder.header(name, value);
                        se.getRequestHeaders().put(new HttpString(name), value);
                    } catch (Throwable e) {
                        log.warn("Failed to convert a protocol to a ");
                    }
                }
            }
            return requestBuilder.method(HttpRequestMethod.WS_HANDSHAKE).build();
        }
        return webRequest;
    }

    static List<String> getWebsocketProtocols(List<String> headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return Collections.emptyList();
        }
        return headerValue.stream().flatMap(
                protocolHeader -> Arrays.stream(protocolHeader.split(",")).map(String::trim)).toList();
    }

    protected boolean handleCorsPreflight(HttpServerExchange se) {
        if ("options".equalsIgnoreCase(se.getRequestMethod().toString())
            && se.getRequestHeaders().contains("Access-Control-Request-Method")
            && applyCorsHeaders(se)) {
            ofNullable(se.getRequestHeaders().getFirst("Access-Control-Request-Headers"))
                    .ifPresent(h -> se.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), h));
            ofNullable(se.getRequestHeaders().getFirst("Access-Control-Request-Method"))
                    .ifPresent(h -> se.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), h));
            se.getResponseHeaders().put(new HttpString("Access-Control-Max-Age"),
                                        String.valueOf(Duration.ofDays(1).toSeconds()));
            se.getResponseHeaders().put(new HttpString("Vary"),
                                        "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            se.setStatusCode(204);
            se.endExchange();
            return true;
        }
        return false;
    }

    protected boolean applyCorsHeaders(HttpServerExchange se) {
        String origin = se.getRequestHeaders().getFirst("Origin");
        if (corsOrigin(origin)) {
            se.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), origin);
            if (!se.getResponseHeaders().contains("Access-Control-Allow-Credentials")) {
                se.getResponseHeaders().put(new HttpString("Access-Control-Allow-Credentials"), "true");
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

    protected void sendWebRequest(HttpServerExchange se, WebRequest webRequest, boolean chunked) {
        String namespace;
        try {
            namespace = namespaceSelector.select(webRequest);
        } catch (SecurityException e) {
            se.setStatusCode(401);
            se.getResponseSender().send(e.getMessage());
            return;
        }
        if (namespace != null) {
            forNamespace(namespace).sendResolvedWebRequest(se, webRequest, chunked);
            return;
        }
        sendResolvedWebRequest(se, webRequest, chunked);
    }

    protected void sendResolvedWebRequest(HttpServerExchange se, WebRequest webRequest, boolean chunked) {
        if (chunked) {
            doSendChunkedWebRequest(se, webRequest);
        } else {
            doSendWebRequest(se, webRequest);
        }
    }

    protected void doSendWebRequest(HttpServerExchange se, WebRequest webRequest) {
        SerializedMessage requestMessage = webRequest.serialize(serializer);
        requestHandler.sendRequest(
                        requestMessage, m -> requestGateway.append(Guarantee.SENT, m),
                        intermediateResponse -> handleResponse(intermediateResponse, webRequest, se))
                .whenComplete((r, e) -> completeResponse(r, e, webRequest, se));
    }

    protected void doSendChunkedWebRequest(HttpServerExchange se, WebRequest webRequest) {
        se.dispatch(chunkedResponseExecutor, () -> readChunkedWebRequest(se, webRequest));
    }

    protected void readChunkedWebRequest(HttpServerExchange se, WebRequest webRequest) {
        AtomicReference<ChunkedProxyRequest> chunkedRequest = new AtomicReference<>();
        AtomicReference<ByteArrayOutputStream> pendingBuffer =
                new AtomicReference<>(new ByteArrayOutputStream(maxRequestSize));
        AtomicBoolean failed = new AtomicBoolean();
        long expectedContentLength = se.getRequestContentLength();
        long totalRead = 0;
        try {
            StreamSourceChannel requestChannel = se.getRequestChannel();
            ByteBuffer readBuffer = ByteBuffer.allocate(Math.min(maxRequestSize, 64 * 1024));
            long readTimeoutMillis = Math.max(1L, Math.min(requestTimeout.toMillis(), 100L));
            while (!failed.get()) {
                readBuffer.clear();
                int read = Channels.readBlocking(requestChannel, readBuffer, readTimeoutMillis,
                                                 java.util.concurrent.TimeUnit.MILLISECONDS);
                if (read < 0) {
                    if (expectedContentLength >= 0 && totalRead < expectedContentLength) {
                        throw new EOFException(format("Request ended early after %s of %s bytes",
                                                      totalRead, expectedContentLength));
                    }
                    break;
                }
                if (read == 0) {
                    continue;
                }
                totalRead += read;
                processChunkedRequestBytes(se, webRequest, readBuffer.array(), read, false, chunkedRequest,
                                           pendingBuffer, failed);
                awaitChunkDispatch(se, webRequest, chunkedRequest.get(), false, failed);
            }
            if (failed.get()) {
                return;
            }
            processChunkedRequestBytes(se, webRequest, new byte[0], 0, true, chunkedRequest, pendingBuffer, failed);
            awaitChunkDispatch(se, webRequest, chunkedRequest.get(), true, failed);
        } catch (Throwable error) {
            if (failed.compareAndSet(false, true)) {
                log.error("Failed to read incoming message", error);
                completeResponse(null, error, webRequest, se);
            }
        }
    }

    protected void processChunkedRequestBytes(HttpServerExchange se, WebRequest webRequest, byte[] bytes,
                                              int length, boolean last,
                                              AtomicReference<ChunkedProxyRequest> chunkedRequest,
                                              AtomicReference<ByteArrayOutputStream> pendingBuffer,
                                              AtomicBoolean failed) {
        if (failed.get()) {
            return;
        }
        try {
            ByteArrayOutputStream buffer = pendingBuffer.get();
            if (length > 0) {
                buffer.write(bytes, 0, length);
            }
            ChunkedProxyRequest currentRequest = chunkedRequest.get();
            boolean dispatchedFinalChunk = false;
            if (currentRequest == null && last && buffer.size() <= maxRequestSize) {
                doSendWebRequest(se, webRequest.withPayload(buffer.toByteArray()));
                buffer.reset();
                return;
            }
            while (buffer.size() >= maxRequestSize || (last && buffer.size() > 0)) {
                if (currentRequest == null) {
                    currentRequest = new ChunkedProxyRequest(webRequest, se);
                    currentRequest.watchForEarlyFailure(failed);
                }
                boolean finalChunk = last && buffer.size() <= maxRequestSize;
                currentRequest.append(takeChunk(buffer, Math.min(maxRequestSize, buffer.size())), finalChunk);
                dispatchedFinalChunk |= finalChunk;
            }
            if (last && buffer.size() == 0 && currentRequest != null
                && !dispatchedFinalChunk && !currentRequest.hasDispatchedFinalChunk()) {
                currentRequest.append(new byte[0], true);
            }
            if (currentRequest != null) {
                chunkedRequest.set(currentRequest);
            }
        } catch (Throwable e) {
            if (failed.compareAndSet(false, true)) {
                completeResponse(null, e, webRequest, se);
            }
        }
    }

    protected byte[] takeChunk(ByteArrayOutputStream buffer, int length) {
        byte[] allBytes = buffer.toByteArray();
        byte[] chunk = Arrays.copyOfRange(allBytes, 0, length);
        buffer.reset();
        if (allBytes.length > length) {
            buffer.write(allBytes, length, allBytes.length - length);
        }
        return chunk;
    }

    protected void awaitChunkDispatch(HttpServerExchange se, WebRequest webRequest, ChunkedProxyRequest currentRequest,
                                      boolean last, AtomicBoolean failed) {
        if (failed.get() || currentRequest == null) {
            return;
        }
        try {
            currentRequest.dispatchFuture().join();
        } catch (Throwable error) {
            if (failed.compareAndSet(false, true)) {
                completeResponse(null, error, webRequest, se);
            }
            return;
        }
        if (last) {
            currentRequest.responseFuture().whenComplete((response, responseError) -> se.dispatch(
                    chunkedResponseExecutor, () -> completeResponse(response, responseError, webRequest, se)));
        }
    }

    protected SerializedMessage createChunkedRequestMessage(WebRequest webRequest, byte[] chunk, long chunkIndex,
                                                            boolean firstChunk, boolean finalChunk) {
        var metadata = webRequest.getMetadata()
                .with(HasMetadata.CHUNK_INDEX, chunkIndex)
                .with(HasMetadata.FIRST_CHUNK, Boolean.toString(firstChunk))
                .with(HasMetadata.FINAL_CHUNK, Boolean.toString(finalChunk));
        SerializedMessage chunkMessage = new SerializedMessage(
                new Data<>(chunk, byte[].class.getName(), 0, "application/octet-stream"),
                metadata, webRequest.getMessageId(), webRequest.getTimestamp().toEpochMilli());
        return chunkMessage;
    }

    protected void logChunkDispatch(WebRequest webRequest, SerializedMessage chunkMessage) {
        if (!logChunkDispatches || !log.isInfoEnabled()) {
            return;
        }
        log.info(
                "Dispatching proxy chunk for request {} (messageId={}, chunkIndex={}, firstChunk={}, finalChunk={}, bytes={})",
                webRequest.getPath(), chunkMessage.getMessageId(), chunkMessage.getMetadata().get(HasMetadata.CHUNK_INDEX),
                chunkMessage.firstChunk(), chunkMessage.lastChunk(),
                chunkMessage.getData() == null || chunkMessage.getData().getValue() == null
                        ? 0 : chunkMessage.getData().getValue().length);
    }

    protected class ChunkedProxyRequest {
        private final WebRequest webRequest;
        private final HttpServerExchange exchange;
        private final CompletableFuture<SerializedMessage> responseFuture = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<Void>> lastDispatch =
                new AtomicReference<>(CompletableFuture.completedFuture(null));
        private final AtomicBoolean watchingFailure = new AtomicBoolean();
        private final AtomicBoolean finalChunkDispatched = new AtomicBoolean();
        private long nextChunkIndex;
        private SerializedMessage firstChunk;

        protected ChunkedProxyRequest(WebRequest webRequest, HttpServerExchange exchange) {
            this.webRequest = webRequest;
            this.exchange = exchange;
        }

        protected synchronized void append(byte[] chunk, boolean finalChunk) {
            if (responseFuture.isDone()) {
                throw new IllegalStateException("Cannot append chunks after the request has already completed");
            }
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
            dispatch(lastDispatch.get().thenCompose(ignored -> {
                logChunkDispatch(webRequest, continuation);
                return requestGateway.append(Guarantee.SENT, continuation);
            }));
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

        protected void watchForEarlyFailure(AtomicBoolean failed) {
            if (watchingFailure.compareAndSet(false, true)) {
                responseFuture.whenComplete((response, error) -> {
                    if (error != null && failed.compareAndSet(false, true)) {
                        exchange.dispatch(chunkedResponseExecutor,
                                          () -> completeResponse(null, error, webRequest, exchange));
                    }
                });
            }
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
                    logChunkDispatch(webRequest, message);
                    initialDispatch.set(requestGateway.append(Guarantee.SENT, message));
                } catch (Throwable e) {
                    initialDispatch.set(CompletableFuture.failedFuture(e));
                    throw e;
                }
            }, null, intermediateResponse -> handleResponse(intermediateResponse, webRequest, exchange))
                    .whenComplete((response, error) -> {
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

        private void dispatch(CompletableFuture<Void> future) {
            lastDispatch.set(future);
            future.whenComplete((r, e) -> {
                if (e != null) {
                    responseFuture.completeExceptionally(unwrapException(e));
                }
            });
        }
    }

    protected void completeResponse(SerializedMessage response, Throwable error, WebRequest webRequest,
                                    HttpServerExchange se) {
        try {
            error = unwrapException(error);
            if (error == null) {
                handleResponse(response, webRequest, se);
            } else if (error instanceof TimeoutException) {
                log.warn("Request {} timed out (messageId: {}). This is possibly due to a missing handler.",
                         webRequest, webRequest.getMessageId(), error);
                sendGatewayTimeout(se);
            } else {
                log.error("Failed to complete {} (messageId: {})", webRequest, webRequest.getMessageId(), error);
                sendServerError(se);
            }
        } catch (Throwable t) {
            log.error("Failed to process response {} to request {}", error == null ? response : error, webRequest, t);
            sendServerError(se);
        }
    }

    @SuppressWarnings("resource")
    @SneakyThrows
    protected void handleResponse(SerializedMessage responseMessage, WebRequest webRequest, HttpServerExchange se) {
        int statusCode = WebResponse.getStatusCode(responseMessage.getMetadata());
        if (statusCode < 300 && HttpRequestMethod.WS_HANDSHAKE.equals(webRequest.getMethod())) {
            se.addQueryParam("_clientId", responseMessage.getMetadata().get("clientId"));
            se.addQueryParam("_trackerId", responseMessage.getMetadata().get("trackerId"));
            websocketHandler.handleRequest(se);
            return;
        }
        if (responseMessage.chunked()) {
            if (!se.isBlocking()) {
                prepareForSending(responseMessage, se, statusCode).startBlocking();
            }
            var out = se.getOutputStream();
            out.write(responseMessage.getData().getValue());
            if (responseMessage.lastChunk()) {
                out.close();
            }
        } else {
            sendResponse(responseMessage, prepareForSending(responseMessage, se, statusCode));
        }
    }

    protected HttpServerExchange prepareForSending(SerializedMessage responseMessage, HttpServerExchange se,
                                                   int statusCode) {
        se.setStatusCode(statusCode);
        applyCorsHeaders(se);
        boolean http2 = se.getProtocol().compareTo(Protocols.HTTP_1_1) > 0;
        Map<String, List<String>> headers = WebUtils.getHeaders(responseMessage.getMetadata());
        if (responseMessage.chunked()) {
            headers.remove("Content-Length");
            headers.remove("Accept-Ranges");
        }
        headers.forEach(
                (key, value) -> {
                    if (http2 || !key.startsWith(":")) {
                        se.getResponseHeaders().addAll(new HttpString(key), value);
                    }
                });
        if (!se.getResponseHeaders().contains("Content-Type")) {
            ofNullable(responseMessage.getData().getFormat()).ifPresent(
                    format -> se.getResponseHeaders().add(new HttpString("Content-Type"), format));
        }
        return se;
    }

    protected void sendResponse(SerializedMessage responseMessage, HttpServerExchange se) {
        se.getResponseSender().send(ByteBuffer.wrap(responseMessage.getData().getValue()));
    }

    protected void sendServerError(HttpServerExchange se) {
        try {
            se.setStatusCode(500);
            se.getResponseSender().send("Request could not be handled due to a server side error");
        } catch (Throwable t) {
            log.error("Failed to send server error response", t);
        }
    }

    protected void sendGatewayTimeout(HttpServerExchange se) {
        se.setStatusCode(504);
        se.getResponseSender().send("Did not receive a response in time");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            websocketEndpoint.shutDown();
            requestHandler.close();
            requestGateway.close();
            chunkedResponseExecutor.shutdown();
            super.close();
        }
    }

    @SneakyThrows
    protected HttpHandler createWebsocketHandler() {
        DeploymentManager deploymentManager = Servlets.defaultContainer().addDeployment(
                deployment().setContextPath("/**").addServletContextAttribute(
                                WebSocketDeploymentInfo.ATTRIBUTE_NAME,
                                new WebSocketDeploymentInfo()
                                        .setBuffers(new DefaultByteBufferPool(false, 1024, 100, 12))
                                        .setWorker(Xnio.getInstance().createWorker(
                                                OptionMap.create(Options.THREAD_DAEMON, true)))
                                        .addEndpoint(ServerEndpointConfig.Builder
                                                             .create(WebsocketEndpoint.class, "/**")
                                                             .configurator(new ServerEndpointConfig.Configurator() {
                                                                 @Override
                                                                 public <T> T getEndpointInstance(Class<T> endpointClass) {
                                                                     return endpointClass.cast(websocketEndpoint);
                                                                 }

                                                                 @Override
                                                                 public void modifyHandshake(ServerEndpointConfig sec,
                                                                                             HandshakeRequest request,
                                                                                             HandshakeResponse response) {
                                                                     super.modifyHandshake(sec, request, response);
                                                                     var protocols = getWebsocketProtocols(request.getHeaders()
                                                                                                                   .get("Sec-WebSocket-Protocol"));
                                                                     if (!protocols.isEmpty()) {
                                                                         response.getHeaders().put("Sec-WebSocket-Protocol",
                                                                                                   List.of(protocols.getFirst()));
                                                                     }
                                                                 }
                                                             }).build()))
                        .setDeploymentName("websocket")
                        .addFilter(new FilterInfo("websocketFilter", WebsocketFilter.class))
                        .addFilterUrlMapping("websocketFilter", "*", DispatcherType.REQUEST)
                        .setClassLoader(Undertow.class.getClassLoader()));
        deploymentManager.deploy();
        return deploymentManager.start();
    }
}
