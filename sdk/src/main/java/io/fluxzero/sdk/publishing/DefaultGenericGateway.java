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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import io.fluxzero.sdk.web.WebResponse;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.fluxzero.common.Guarantee.SENT;
import static io.fluxzero.sdk.common.ClientUtils.waitForResults;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Stream.ofNullable;

@AllArgsConstructor
@Slf4j
public class DefaultGenericGateway extends AbstractNamespaced<GenericGateway> implements GenericGateway {
    private static final String REQUEST_TIMEOUT_METADATA_KEY = "$fluxzero.requestTimeoutMillis";

    @Getter(AccessLevel.PRIVATE)
    private final Client client;
    private final GatewayClient gatewayClient;
    private final RequestHandler requestHandler;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final MessageType messageType;
    private final String topic;
    @Delegate
    private final HandlerRegistry localHandlerRegistry;
    private final ResponseMapper responseMapper;

    private final Map<String, CompletableFuture<?>> callbacks = new ConcurrentHashMap<>();

    @Override
    protected GenericGateway createForNamespace(String namespace) {
        Client clientForNamespace = client.forNamespace(namespace);
        RequestHandler requestHandlerForNamespace = requestHandler.forNamespace(namespace);
        return client == clientForNamespace ? this
                : new DefaultGenericGateway(clientForNamespace, clientForNamespace.getGatewayClient(messageType, topic),
                                            requestHandlerForNamespace, serializer, dispatchInterceptor,
                                            messageType, topic, localHandlerRegistry, responseMapper);
    }

    @Override
    @SneakyThrows
    public CompletableFuture<Void> sendAndForget(Guarantee guarantee, Message... messages) {
        return sendAndForget(guarantee, UnaryOperator.identity(), messages);
    }

    @Override
    public CompletableFuture<Void> sendAndForget(Guarantee guarantee, UnaryOperator<SerializedMessage> interceptor,
                                                 Message... messages) {
        List<SerializedMessage> serializedMessages = new ArrayList<>();
        for (Message message : messages) {
            message = dispatchInterceptor.interceptDispatch(message, messageType, topic);
            if (message == null) {
                continue;
            }
            dispatchInterceptor.monitorDispatch(message, messageType, topic, client.namespace());
            Optional<CompletableFuture<Object>> localResult
                    = localHandlerRegistry.handle(new DeserializingMessage(message, messageType, topic, serializer));
            if (localResult.isEmpty()) {
                SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                        message.serialize(serializer), message, messageType, topic);
                if (serializedMessage == null) {
                    continue;
                }
                serializedMessages.add(serializedMessage);
            } else {
                if (localResult.get().isCompletedExceptionally()) {
                    try {
                        localResult.get().getNow(null);
                    } catch (CompletionException e) {
                        log.error("Handler failed to handle a {}",
                                  message.getPayloadClass().getSimpleName(), e.getCause());
                    }
                }
            }
        }
        if (!serializedMessages.isEmpty()) {
            try {
                SerializedMessage[] finalMessages = serializedMessages.stream().flatMap(
                        m -> ofNullable(interceptor.apply(m))).toArray(SerializedMessage[]::new);
                if (finalMessages.length > 0) {
                    return gatewayClient.append(guarantee, finalMessages);
                }
            } catch (Exception e) {
                throw new GatewayException(FluxzeroErrors.messageDispatchFailed(
                        messageType, topic, messages.length, e), e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<CompletableFuture<Message>> sendForMessages(Message... messages) {
        return sendForMessages(null, messages);
    }

    @Override
    @SneakyThrows
    public <R> R sendAndWait(Message message) {
        Duration timeout = sendAndWaitTimeout(message);
        CompletableFuture<R> future = sendForMessage(message, timeout).thenApply(Message::getPayload);
        try {
            return future.get();
        } catch (InterruptedException e) {
            currentThread().interrupt();
            throw new GatewayException(FluxzeroErrors.threadInterrupted(
                    "the response", message.getMessageId(), message.getPayloadClass().getName()), e);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e.getCause());
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new TimeoutException(FluxzeroErrors.requestTimedOut(
                        "request", message.getPayloadClass().getName(), message.getMessageId(), null,
                        MessageType.RESULT.name(), timeout));
            }
            throw cause;
        }
    }

    private CompletableFuture<Message> sendForMessage(Message message, Duration timeout) {
        return sendForMessages(timeout, message).get(0);
    }

    @SuppressWarnings("unchecked")
    private List<CompletableFuture<Message>> sendForMessages(Duration defaultTimeout, Message... messages) {
        List<Object> results = new ArrayList<>(messages.length);
        Map<SerializedMessage, Duration> requestTimeouts = new IdentityHashMap<>();
        for (Message message : messages) {
            Duration timeout = requestTimeout(message, defaultTimeout);
            message = message.withMetadata(message.getMetadata().without(REQUEST_TIMEOUT_METADATA_KEY));
            message = dispatchInterceptor.interceptDispatch(message, messageType, topic);
            if (message == null) {
                results.add(emptyReturnMessage());
                continue;
            }
            dispatchInterceptor.monitorDispatch(message, messageType, topic, client.namespace());
            Optional<CompletableFuture<Object>> localResult
                    = localHandlerRegistry.handle(new DeserializingMessage(message, messageType, topic, serializer));
            if (localResult.isPresent()) {
                CompletableFuture<Message> c = localResult.get().thenApply(responseMapper::map);
                if (timeout != null && !timeout.isNegative()) {
                    c.orTimeout(timeout.toMillis(), MILLISECONDS);
                }
                String messageId = message.getMessageId();
                callbacks.put(messageId, c);
                results.add(c.whenComplete((m, e) -> callbacks.remove(messageId)));
            } else {
                SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                        message.serialize(serializer), message, messageType, topic);
                if (serializedMessage == null) {
                    results.add(emptyReturnMessage());
                    continue;
                }
                results.add(serializedMessage);
                requestTimeouts.put(serializedMessage, timeout);
            }
        }
        List<SerializedMessage> serializedMessages = results.stream().filter(r -> r instanceof SerializedMessage)
                .map(m -> (SerializedMessage) m).collect(Collectors.toList());
        List<CompletableFuture<Message>> externalResults = serializedMessages.isEmpty()
                ? Collections.emptyList() : sendRequests(serializedMessages, requestTimeouts).stream()
                .map(r -> r.thenCompose(m -> {
                    Object result;
                    try {
                        result = serializer.deserialize(m);
                    } catch (Exception e) {
                        log.error("Failed to deserialize result with id {}", m.getMessageId(), e);
                        return CompletableFuture.failedFuture(e);
                    }
                    if (result instanceof Throwable) {
                        return CompletableFuture.failedFuture((Throwable) result);
                    } else {
                        Message message = new Message(result, m.getMetadata());
                        if (messageType == MessageType.WEBREQUEST) {
                            message = new WebResponse(message);
                        }
                        return CompletableFuture.completedFuture(message);
                    }
                })).toList();

        return results.stream().map(r -> {
            if (r instanceof CompletableFuture<?>) {
                return (CompletableFuture<Message>) r;
            } else {
                SerializedMessage m = (SerializedMessage) r;
                CompletableFuture<Message> future = externalResults.get(serializedMessages.indexOf(m));
                callbacks.put(m.getMessageId(), future);
                return future.whenComplete((v, e) -> callbacks.remove(m.getMessageId()));
            }
        }).collect(Collectors.toList());
    }

    private List<CompletableFuture<SerializedMessage>> sendRequests(List<SerializedMessage> requests,
                                                                    Map<SerializedMessage, Duration> timeouts) {
        Duration firstTimeout = timeouts.get(requests.getFirst());
        boolean sameTimeout = requests.stream().allMatch(r -> Objects.equals(firstTimeout, timeouts.get(r)));
        if (sameTimeout) {
            return firstTimeout == null ? requestHandler.sendRequests(
                    requests, m -> gatewayClient.append(SENT, m.toArray(SerializedMessage[]::new)))
                    : requestHandler.sendRequests(
                            requests, m -> gatewayClient.append(SENT, m.toArray(SerializedMessage[]::new)),
                            firstTimeout);
        }
        return requests.stream().map(request -> {
            Duration timeout = timeouts.get(request);
            return timeout == null ? requestHandler.sendRequest(
                    request, m -> gatewayClient.append(SENT, m))
                    : requestHandler.sendRequest(request, m -> gatewayClient.append(SENT, m), timeout);
        }).toList();
    }

    private Duration requestTimeout(Message message, Duration defaultTimeout) {
        String timeoutMillis = message.getMetadata().get(REQUEST_TIMEOUT_METADATA_KEY);
        return timeoutMillis == null ? annotatedTimeout(message).orElse(defaultTimeout)
                : Duration.ofMillis(Long.parseLong(timeoutMillis));
    }

    private Duration sendAndWaitTimeout(Message message) {
        return annotatedTimeout(message).orElse(Duration.ofMinutes(1));
    }

    private Optional<Duration> annotatedTimeout(Message message) {
        Timeout timeout = message.getPayloadClass().getAnnotation(Timeout.class);
        return timeout == null ? Optional.empty()
                : Optional.of(Duration.ofNanos(timeout.timeUnit().toNanos(timeout.value())));
    }

    private Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    @Override
    public CompletableFuture<Void> setRetentionTime(Duration duration, Guarantee guarantee) {
        return gatewayClient.setRetentionTime(duration, guarantee);
    }

    @Override
    public CompletableFuture<Void> truncate(Guarantee guarantee) {
        return gatewayClient.truncate(guarantee);
    }

    protected CompletableFuture<Message> emptyReturnMessage() {
        CompletableFuture<Message> c = CompletableFuture.completedFuture(Message.asMessage(null));
        if (messageType == MessageType.WEBREQUEST) {
            c = c.thenApply(WebResponse::new);
        }
        return c;
    }

    @Override
    public void close() {
        waitForResults(Duration.ofSeconds(2), callbacks.values());
        super.close();
    }
}
