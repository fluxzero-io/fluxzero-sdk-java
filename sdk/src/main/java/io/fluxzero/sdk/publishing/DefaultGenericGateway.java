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
import io.fluxzero.common.jfr.FluxzeroJfr;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.LocalHandlerResult;
import io.fluxzero.sdk.tracking.handling.LocalExecution;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import io.fluxzero.sdk.web.WebResponse;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;

import static io.fluxzero.common.Guarantee.SENT;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotationAs;
import static io.fluxzero.common.reflection.ReflectionUtils.getPackageAnnotations;
import static io.fluxzero.common.reflection.ReflectionUtils.getTypeMetadata;
import static io.fluxzero.sdk.common.ClientUtils.isApplicationNamespace;
import static io.fluxzero.sdk.common.ClientUtils.setConsumerNamespace;
import static io.fluxzero.sdk.common.ClientUtils.waitForResults;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Stream.ofNullable;

@Slf4j
public class DefaultGenericGateway extends AbstractNamespaced<GenericGateway> implements GenericGateway {
    private static final int PARALLEL_SERIALIZATION_THRESHOLD = Math.max(
            2, Integer.getInteger("fluxzero.parallelSerializationThreshold", 256));
    private static final int SERIALIZATION_CHUNK_SIZE = Math.max(
            PARALLEL_SERIALIZATION_THRESHOLD,
            Integer.getInteger("fluxzero.serializationChunkSize", 8_192));
    @Getter(AccessLevel.PRIVATE)
    private final Client client;
    private final GatewayClient gatewayClient;
    private final RequestHandler requestHandler;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final MessageType messageType;
    private final String topic;
    private final String namespace;
    private final boolean applicationNamespace;
    @Delegate
    private final HandlerRegistry localHandlerRegistry;
    private final ResponseMapper responseMapper;
    private final ClassValue<PreparedDispatchEntry> preparedLocalDispatch = new ClassValue<>() {
        @Override
        protected PreparedDispatchEntry computeValue(Class<?> payloadClass) {
            return new PreparedDispatchEntry(payloadClass, dispatchInterceptor.prepareLocalDispatch(
                    new LocalDispatchDescriptor(payloadClass, messageType, topic)), isLocalOnly(payloadClass));
        }
    };
    private volatile PreparedDispatchEntry lastPreparedDispatch;

    private final Map<String, CompletableFuture<?>> callbacks = new ConcurrentHashMap<>();

    public DefaultGenericGateway(Client client, GatewayClient gatewayClient, RequestHandler requestHandler,
                                 Serializer serializer, DispatchInterceptor dispatchInterceptor,
                                 MessageType messageType, String topic, HandlerRegistry localHandlerRegistry,
                                 ResponseMapper responseMapper) {
        this.client = client;
        this.gatewayClient = gatewayClient;
        this.requestHandler = requestHandler;
        this.serializer = serializer;
        this.dispatchInterceptor = dispatchInterceptor;
        this.messageType = messageType;
        this.topic = topic;
        this.namespace = client.namespace();
        this.applicationNamespace = isApplicationNamespace(client);
        this.localHandlerRegistry = localHandlerRegistry;
        this.responseMapper = responseMapper;
    }

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
        if (messages.length >= PARALLEL_SERIALIZATION_THRESHOLD) {
            return sendAndForgetParallel(guarantee, interceptor, messages);
        }
        List<SerializedMessage> serializedMessages = new ArrayList<>();
        for (Message message : messages) {
            Message original = message;
            boolean originalLocalOnly = isLocalOnly(original.getPayloadClass());
            message = interceptDispatch(original);
            if (message == null) {
                continue;
            }
            try {
                boolean localOnly = originalLocalOnly || isLocalOnly(message.getPayloadClass());
                dispatchInterceptor.monitorDispatch(message, messageType, topic, namespace, false);
                Optional<CompletableFuture<Object>> localResult = localOnly
                        ? localHandlerRegistry.handle(localMessage(message), false)
                        : canSkipLocalHandling(message)
                                ? Optional.empty() : localHandlerRegistry.handle(localMessage(message));
                if (localResult.isEmpty()) {
                    if (localOnly) {
                        if (messageType.isRequest()) {
                            return CompletableFuture.failedFuture(
                                    new LocalOnlyDispatchException(message.getPayloadClass(), messageType));
                        }
                        continue;
                    }
                    SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                            message.serialize(serializer), message, messageType, topic);
                    if (serializedMessage == null) {
                        continue;
                    }
                    serializedMessages.add(serializedMessage);
                } else if (localResult.get().isCompletedExceptionally()) {
                    try {
                        localResult.get().getNow(null);
                    } catch (CompletionException e) {
                        log.error("Handler failed to handle a {}",
                                  message.getPayloadClass().getSimpleName(), e.getCause());
                    }
                }
            } finally {
                dispatchInterceptor.completeLocalDispatch(message);
            }
        }
        if (!serializedMessages.isEmpty()) {
            try {
                SerializedMessage[] finalMessages = serializedMessages.stream().flatMap(
                        m -> ofNullable(interceptor.apply(m))).toArray(SerializedMessage[]::new);
                if (finalMessages.length > 0) {
                    return AsyncCompletionScope.register(gatewayClient.append(guarantee, finalMessages));
                }
            } catch (Exception e) {
                throw new GatewayException(FluxzeroErrors.messageDispatchFailed(
                        messageType, topic, messages.length, e), e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> sendAndForgetParallel(
            Guarantee guarantee, UnaryOperator<SerializedMessage> interceptor, Message[] messages) {
        if (messages.length > SERIALIZATION_CHUNK_SIZE) {
            List<CompletableFuture<Void>> chunks = new ArrayList<>(
                    Math.ceilDiv(messages.length, SERIALIZATION_CHUNK_SIZE));
            for (int offset = 0; offset < messages.length; offset += SERIALIZATION_CHUNK_SIZE) {
                chunks.add(sendAndForgetParallel(
                        guarantee, interceptor,
                        messages, offset,
                        Math.min(messages.length, offset + SERIALIZATION_CHUNK_SIZE)));
            }
            return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
        }
        return sendAndForgetParallel(guarantee, interceptor, messages, 0, messages.length);
    }

    private CompletableFuture<Void> sendAndForgetParallel(
            Guarantee guarantee, UnaryOperator<SerializedMessage> interceptor,
            Message[] messages, int from, int until) {
        List<Message> externalMessages = new ArrayList<>(until - from);
        try {
            for (int index = from; index < until; index++) {
                Message candidate = messages[index];
                boolean originalLocalOnly = isLocalOnly(candidate.getPayloadClass());
                Message message = interceptDispatch(candidate);
                if (message == null) {
                    continue;
                }
                boolean external = false;
                try {
                    boolean localOnly = originalLocalOnly || isLocalOnly(message.getPayloadClass());
                    dispatchInterceptor.monitorDispatch(message, messageType, topic, namespace, false);
                    Optional<CompletableFuture<Object>> localResult = localOnly
                            ? localHandlerRegistry.handle(localMessage(message), false)
                            : canSkipLocalHandling(message)
                                    ? Optional.empty() : localHandlerRegistry.handle(localMessage(message));
                    if (localResult.isEmpty()) {
                        if (localOnly) {
                            if (messageType.isRequest()) {
                                return CompletableFuture.failedFuture(
                                        new LocalOnlyDispatchException(message.getPayloadClass(), messageType));
                            }
                        } else {
                            externalMessages.add(message);
                            external = true;
                        }
                    } else if (localResult.get().isCompletedExceptionally()) {
                        try {
                            localResult.get().getNow(null);
                        } catch (CompletionException e) {
                            log.error("Handler failed to handle a {}", message.getPayloadClass().getSimpleName(), e.getCause());
                        }
                    }
                } finally {
                    if (!external) {
                        dispatchInterceptor.completeLocalDispatch(message);
                    }
                }
            }
            FluxzeroJfr.Batch serializationEvent = FluxzeroJfr.startBatch(
                    "sdk.command-gateway", "serialize", messageType.name(),
                    externalMessages.size(), 0L, 0L, 0L);
            List<SerializedMessage> serializedMessages;
            try {
                serializedMessages = serializeMessages(externalMessages);
                FluxzeroJfr.finish(serializationEvent, null);
            } catch (RuntimeException | Error failure) {
                FluxzeroJfr.finish(serializationEvent, failure);
                throw failure;
            }
            SerializedMessage[] finalMessages = new SerializedMessage[serializedMessages.size()];
            int resultSize = 0;
            for (int i = 0; i < serializedMessages.size(); i++) {
                Message message = externalMessages.get(i);
                SerializedMessage serialized = dispatchInterceptor.modifySerializedMessage(
                        serializedMessages.get(i), message, messageType, topic);
                if (serialized != null) {
                    serialized = interceptor.apply(serialized);
                    if (serialized != null) {
                        finalMessages[resultSize++] = serialized;
                    }
                }
            }
            if (resultSize == 0) {
                return CompletableFuture.completedFuture(null);
            }
            return AsyncCompletionScope.register(gatewayClient.append(
                    guarantee, resultSize == finalMessages.length
                            ? finalMessages : java.util.Arrays.copyOf(finalMessages, resultSize)));
        } finally {
            // External candidates retain protected values until serialization and externalization have completed.
            externalMessages.forEach(dispatchInterceptor::completeLocalDispatch);
        }
    }

    @Override
    public List<CompletableFuture<Message>> sendForMessages(Message... messages) {
        if (messages.length >= PARALLEL_SERIALIZATION_THRESHOLD) {
            if (messages.length > SERIALIZATION_CHUNK_SIZE) {
                List<CompletableFuture<Message>> results = new ArrayList<>(messages.length);
                for (int offset = 0; offset < messages.length; offset += SERIALIZATION_CHUNK_SIZE) {
                    Message[] chunk = java.util.Arrays.copyOfRange(
                            messages, offset,
                            Math.min(messages.length, offset + SERIALIZATION_CHUNK_SIZE));
                    results.addAll(completeRequests(prepareRequests(chunk)));
                }
                return results;
            }
            return completeRequests(prepareRequests(messages));
        }
        List<PendingRequest> requests = new ArrayList<>(messages.length);
        for (Message message : messages) {
            requests.add(prepareRequest(message, requestTimeout(message).orElse(null)));
        }
        return completeRequests(requests);
    }

    private List<PendingRequest> prepareRequests(Message[] messages) {
        PendingRequest[] requests = new PendingRequest[messages.length];
        List<Message> externalMessages = new ArrayList<>(messages.length);
        int[] externalIndices = new int[messages.length];
        Duration[] externalTimeouts = new Duration[messages.length];
        int externalSize = 0;
        try {
            for (int i = 0; i < messages.length; i++) {
                Message original = messages[i];
                boolean originalLocalOnly = isLocalOnly(original.getPayloadClass());
                Duration timeout = requestTimeout(original).orElse(null);
                Message message = interceptDispatch(original);
                if (message == null) {
                    requests[i] = PendingRequest.completed(emptyReturnMessage());
                    continue;
                }
                boolean external = false;
                try {
                    boolean localOnly = originalLocalOnly || isLocalOnly(message.getPayloadClass());
                    dispatchInterceptor.monitorDispatch(message, messageType, topic, namespace, true);
                    LocalHandlerResult localResult = handleLocally(message, localOnly);
                    if (localResult.isHandled()) {
                        requests[i] = prepareLocalRequest(message, localResult.asFuture(), timeout);
                    } else {
                        if (localOnly) {
                            requests[i] = prepareMissingLocalHandler(message);
                        } else {
                            externalIndices[externalSize++] = i;
                            externalTimeouts[i] = timeout;
                            externalMessages.add(message);
                            external = true;
                        }
                    }
                } finally {
                    if (!external) {
                        dispatchInterceptor.completeLocalDispatch(message);
                    }
                }
            }
            FluxzeroJfr.Batch serializationEvent = FluxzeroJfr.startBatch(
                    "sdk.command-gateway", "serialize", messageType.name(),
                    externalMessages.size(), 0L, 0L, 0L);
            List<SerializedMessage> serializedMessages;
            try {
                serializedMessages = serializeMessages(externalMessages);
                FluxzeroJfr.finish(serializationEvent, null);
            } catch (RuntimeException | Error failure) {
                FluxzeroJfr.finish(serializationEvent, failure);
                throw failure;
            }
            for (int i = 0; i < externalSize; i++) {
                int requestIndex = externalIndices[i];
                Message message = externalMessages.get(i);
                SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                        serializedMessages.get(i), message, messageType, topic);
                requests[requestIndex] = serializedMessage == null
                        ? PendingRequest.completed(emptyReturnMessage())
                        : PendingRequest.external(serializedMessage, externalTimeouts[requestIndex]);
            }
            return java.util.Arrays.asList(requests);
        } finally {
            externalMessages.forEach(dispatchInterceptor::completeLocalDispatch);
        }
    }

    private List<SerializedMessage> serializeMessages(List<Message> messages) {
        if (messages.size() < PARALLEL_SERIALIZATION_THRESHOLD) {
            return messages.stream().map(message -> message.serialize(serializer)).toList();
        }
        SerializedMessage[] result = new SerializedMessage[messages.size()];
        int workers = Math.min(
                Runtime.getRuntime().availableProcessors(),
                Math.ceilDiv(messages.size(),
                             PARALLEL_SERIALIZATION_THRESHOLD));
        int chunkSize = Math.ceilDiv(messages.size(), workers);
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        ThreadLocalContext.Snapshot context = ThreadLocalContext.capture();
        for (int worker = 0; worker < workers; worker++) {
            int from = worker * chunkSize;
            int until = Math.min(
                    messages.size(), from + chunkSize);
            tasks[worker] = CompletableFuture.runAsync(() -> context.run(() -> {
                for (int index = from; index < until; index++) {
                    result[index] = messages.get(index)
                            .serialize(serializer);
                }
            }));
        }
        CompletableFuture.allOf(tasks).join();
        return java.util.Arrays.asList(result);
    }

    @Override
    @SneakyThrows
    @SuppressWarnings("unchecked")
    public <R> R sendAndWait(Object input) {
        if (input instanceof HasMessage hasMessage) {
            return sendAndWait(hasMessage.toMessage());
        }
        Class<?> payloadClass = input == null ? Void.class : input.getClass();
        PreparedDispatchEntry cachedDispatch = lastPreparedDispatch;
        boolean lastDispatch = cachedDispatch != null && cachedDispatch.payloadClass() == payloadClass;
        PreparedDispatchEntry preparedDispatch = lastDispatch
                ? cachedDispatch : preparedLocalDispatch.get(payloadClass);
        PreparedLocalDispatch dispatch = preparedDispatch.dispatch();
        if (!preparedDispatch.localOnly() && dispatch != null && applicationNamespace) {
            if (!lastDispatch) {
                lastPreparedDispatch = preparedDispatch;
            }
            LocalExecution result = LocalExecution.handle(
                    input, messageType, topic, serializer, dispatch, localHandlerRegistry);
            if (result != null) {
                try {
                    if (result.isCompletedSuccessfully()) {
                        return (R) responseMapper.mapPayload(result.getResult());
                    }
                    Message resultMessage = result.getResultMessage();
                    Duration timeout = sendAndWaitTimeout(resultMessage);
                    CompletableFuture<R> future = prepareLocalRequest(
                            resultMessage, result.getResultFuture(), timeout).result().thenApply(Message::getPayload);
                    return waitForResult(future, resultMessage, timeout);
                } finally {
                    result.releaseResult();
                }
            }
        }
        return sendAndWait(new Message(input));
    }

    private record PreparedDispatchEntry(Class<?> payloadClass, PreparedLocalDispatch dispatch, boolean localOnly) {
    }

    @Override
    @SneakyThrows
    public <R> R sendAndWait(Message message) {
        Duration timeout = sendAndWaitTimeout(message);
        Message original = message;
        boolean originalLocalOnly = isLocalOnly(original.getPayloadClass());
        message = interceptDispatch(original);
        if (message == null) {
            return null;
        }
        PendingRequest request;
        try {
            boolean localOnly = originalLocalOnly || isLocalOnly(message.getPayloadClass());
            dispatchInterceptor.monitorDispatch(message, messageType, topic, namespace, true);
            LocalHandlerResult localResult = handleLocally(message, localOnly);
            if (localResult.isCompletedSuccessfully()) {
                return (R) responseMapper.mapPayload(localResult.getValue());
            }
            request = localResult.isHandled()
                    ? prepareLocalRequest(message, localResult.asFuture(), timeout)
                    : localOnly ? prepareMissingLocalHandler(message) : prepareExternalRequest(message, timeout);
        } finally {
            dispatchInterceptor.completeLocalDispatch(message);
        }
        CompletableFuture<R> future = (request.isExternal() ? sendRequest(request) : request.result())
                .thenApply(Message::getPayload);
        return waitForResult(future, message, timeout);
    }

    private <R> R waitForResult(CompletableFuture<R> future, Message message, Duration timeout) throws Throwable {
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

    @Override
    public CompletableFuture<Message> sendForMessage(Message message, Duration timeout) {
        return sendSingle(message, timeout == null ? requestTimeout(message).orElse(null) : timeout);
    }

    private CompletableFuture<Message> sendSingle(Message message, Duration timeout) {
        PendingRequest request = prepareRequest(message, timeout);
        return request.isExternal() ? sendRequest(request) : request.result();
    }

    private PendingRequest prepareRequest(Message message, Duration timeout) {
        Message original = message;
        boolean originalLocalOnly = isLocalOnly(original.getPayloadClass());
        message = interceptDispatch(original);
        if (message == null) {
            return PendingRequest.completed(emptyReturnMessage());
        }
        try {
            boolean localOnly = originalLocalOnly || isLocalOnly(message.getPayloadClass());
            dispatchInterceptor.monitorDispatch(message, messageType, topic, namespace, true);
            LocalHandlerResult localResult = handleLocally(message, localOnly);
            if (localResult.isHandled()) {
                return prepareLocalRequest(message, localResult.asFuture(), timeout);
            }
            return localOnly ? prepareMissingLocalHandler(message) : prepareExternalRequest(message, timeout);
        } finally {
            dispatchInterceptor.completeLocalDispatch(message);
        }
    }

    private PendingRequest prepareLocalRequest(Message message, CompletableFuture<Object> localResult,
                                               Duration timeout) {
        CompletableFuture<Message> result = localResult.thenApply(responseMapper::map);
        if (timeout != null && !timeout.isNegative()) {
            result.orTimeout(timeout.toMillis(), MILLISECONDS);
        }
        return PendingRequest.completed(trackCallback(message.getMessageId(), result));
    }

    private boolean canSkipLocalHandling(Message message) {
        return localHandlerRegistry.canSkipLocalHandling(messageType, message.getPayloadClass());
    }

    private static boolean isLocalOnly(Class<?> payloadClass) {
        // Cache positive and negative scope decisions, never handler or interceptor state.
        return getTypeMetadata(payloadClass).specializedMetadata(LocalOnlyMetadata.class, LocalOnlyMetadata::resolve)
                .localOnly();
    }

    private record LocalOnlyMetadata(boolean localOnly) {
        private static LocalOnlyMetadata resolve(Class<?> payloadClass) {
            Optional<LocalOnly> typeAnnotation = getAnnotationAs(payloadClass, LocalOnly.class, LocalOnly.class);
            if (typeAnnotation.isPresent()) {
                return new LocalOnlyMetadata(typeAnnotation.get().value());
            }
            return new LocalOnlyMetadata(getPackageAnnotations(payloadClass.getPackage()).stream()
                    .filter(annotation -> annotation.annotationType() == LocalOnly.class
                                          || annotation.annotationType().isAnnotationPresent(LocalOnly.class))
                    .map(annotation -> getAnnotationAs(annotation, LocalOnly.class, LocalOnly.class))
                    .flatMap(Optional::stream)
                    .findFirst().map(LocalOnly::value).orElse(false));
        }
    }

    private LocalHandlerResult handleLocally(Message message, boolean localOnly) {
        if (!localOnly && canSkipLocalHandling(message)) {
            return LocalHandlerResult.notHandled();
        }
        DeserializingMessage localMessage = localMessage(message);
        return localOnly ? localHandlerRegistry.handleResult(localMessage, false)
                : localHandlerRegistry.handleResult(localMessage);
    }

    private PendingRequest prepareMissingLocalHandler(Message message) {
        return messageType.isRequest()
                ? PendingRequest.completed(CompletableFuture.failedFuture(
                        new LocalOnlyDispatchException(message.getPayloadClass(), messageType)))
                : PendingRequest.completed(emptyReturnMessage());
    }

    private DeserializingMessage localMessage(Message message) {
        return setConsumerNamespace(
                new DeserializingMessage(message, messageType, topic, serializer),
                applicationNamespace ? null : namespace);
    }

    private PendingRequest prepareExternalRequest(Message message, Duration timeout) {
        SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                message.serialize(serializer), message, messageType, topic);
        return serializedMessage == null ? PendingRequest.completed(emptyReturnMessage())
                : PendingRequest.external(serializedMessage, timeout);
    }

    private Message interceptDispatch(Message message) {
        return localHandlerRegistry.supportsDeferredExternalization()
                ? dispatchInterceptor.interceptLocalDispatch(message, messageType, topic, namespace)
                : dispatchInterceptor.interceptDispatch(message, messageType, topic, namespace);
    }

    private List<CompletableFuture<Message>> completeRequests(List<PendingRequest> requests) {
        List<PendingRequest> externalRequests = new ArrayList<>();
        boolean allExternal = true;
        for (PendingRequest request : requests) {
            if (request.isExternal()) {
                externalRequests.add(request);
            } else {
                allExternal = false;
            }
        }
        List<CompletableFuture<Message>> sentRequests = sendRequests(externalRequests);
        List<CompletableFuture<Message>> results;
        if (allExternal) {
            results = sentRequests;
        } else {
            results = new ArrayList<>(requests.size());
            int externalIndex = 0;
            for (PendingRequest request : requests) {
                results.add(request.isExternal() ? sentRequests.get(externalIndex++) : request.result());
            }
        }
        return results;
    }

    private CompletableFuture<Message> sendRequest(PendingRequest request) {
        SerializedMessage message = request.serializedMessage();
        CompletableFuture<SerializedMessage> result = request.timeout() == null
                ? requestHandler.sendRequest(message, m -> gatewayClient.append(SENT, m))
                : requestHandler.sendRequest(message, m -> gatewayClient.append(SENT, m), request.timeout());
        return trackCallback(message.getMessageId(), result.thenApply(this::deserializeResponse));
    }

    private List<CompletableFuture<Message>> sendRequests(List<PendingRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<SerializedMessage> serializedMessages = new ArrayList<>(requests.size());
        Duration firstTimeout = requests.getFirst().timeout();
        boolean sameTimeout = true;
        for (int i = 0; i < requests.size(); i++) {
            PendingRequest request = requests.get(i);
            serializedMessages.add(request.serializedMessage());
            if (i > 0 && !Objects.equals(firstTimeout, request.timeout())) {
                sameTimeout = false;
            }
        }
        List<CompletableFuture<SerializedMessage>> results;
        if (sameTimeout) {
            results = firstTimeout == null ? requestHandler.sendRequests(
                    serializedMessages, m -> gatewayClient.append(SENT, m.toArray(SerializedMessage[]::new)))
                    : requestHandler.sendRequests(
                            serializedMessages, m -> gatewayClient.append(SENT, m.toArray(SerializedMessage[]::new)),
                            firstTimeout);
        } else {
            results = new ArrayList<>(requests.size());
            for (PendingRequest request : requests) {
                Duration timeout = request.timeout();
                results.add(timeout == null ? requestHandler.sendRequest(
                        request.serializedMessage(), m -> gatewayClient.append(SENT, m))
                                    : requestHandler.sendRequest(
                                            request.serializedMessage(), m -> gatewayClient.append(SENT, m), timeout));
            }
        }
        List<CompletableFuture<Message>> mappedResults = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            SerializedMessage request = serializedMessages.get(i);
            mappedResults.add(trackCallback(
                    request.getMessageId(), results.get(i).thenApply(this::deserializeResponse)));
        }
        return mappedResults;
    }

    private Optional<Duration> requestTimeout(Message message) {
        String timeoutMillis = message.getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY);
        if (timeoutMillis != null) {
            return Optional.of(Duration.ofMillis(Long.parseLong(timeoutMillis)));
        }
        return annotatedTimeout(message);
    }

    private Duration sendAndWaitTimeout(Message message) {
        return requestTimeout(message).orElse(Duration.ofMinutes(1));
    }

    private Optional<Duration> annotatedTimeout(Message message) {
        Timeout timeout = message.getPayloadClass().getAnnotation(Timeout.class);
        return timeout == null ? Optional.empty()
                : Optional.of(Duration.ofNanos(timeout.timeUnit().toNanos(timeout.value())));
    }

    private Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private Message deserializeResponse(SerializedMessage m) {
        Object result;
        try {
            result = serializer.deserialize(m);
        } catch (Exception e) {
            log.error("Failed to deserialize result with id {}", m.getMessageId(), e);
            return failedResponse(e);
        }
        if (result instanceof Throwable failure) {
            return failedResponse(failure);
        }
        Message message = new Message(result, m.getMetadata());
        if (messageType == MessageType.WEBREQUEST) {
            message = new WebResponse(message);
        }
        return message;
    }

    private static Message failedResponse(Throwable failure) {
        // Preserve CompletableFuture's relay wrapping, including cancellation and JDK-specific
        // handling of a Throwable whose toString() fails. Successful responses need no extra future.
        return CompletableFuture.<Message>failedFuture(failure).copy().join();
    }

    private CompletableFuture<Message> trackCallback(String messageId, CompletableFuture<Message> future) {
        callbacks.put(messageId, future);
        return future.whenComplete((message, failure) -> callbacks.remove(messageId));
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

    private record PendingRequest(CompletableFuture<Message> result, SerializedMessage serializedMessage,
                                  Duration timeout) {
        static PendingRequest completed(CompletableFuture<Message> result) {
            return new PendingRequest(result, null, null);
        }

        static PendingRequest external(SerializedMessage serializedMessage, Duration timeout) {
            return new PendingRequest(null, serializedMessage, timeout);
        }

        boolean isExternal() {
            return serializedMessage != null;
        }
    }

    @Override
    public void close() {
        waitForResults(Duration.ofSeconds(2), callbacks.values());
        super.close();
    }
}
