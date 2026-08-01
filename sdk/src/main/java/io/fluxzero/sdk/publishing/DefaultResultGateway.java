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

import io.fluxzero.common.Backlog;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.publishing.client.WebsocketGatewayClient;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.With;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.fluxzero.common.MessageType.RESULT;
import static io.fluxzero.common.reflection.ReflectionUtils.ifClass;

/**
 * Default implementation of the {@link ResultGateway} interface for sending response messages.
 * <p>
 * This class is responsible for handling responses to commands, queries, dispatching the result message to the
 * specified target using a {@link GatewayClient}.
 * <p>
 * The dispatch process utilizes the {@link DispatchInterceptor} and {@link ResponseMapper} to modify or monitor
 * messages before they are sent.
 *
 * @see ResultGateway
 */
@AllArgsConstructor
public class DefaultResultGateway extends AbstractNamespaced<ResultGateway> implements ResultGateway, AutoCloseable {

    private static final int PARALLEL_SERIALIZATION_THRESHOLD = Math.max(
            2, Integer.getInteger("fluxzero.parallelResultSerializationThreshold", 256));
    private static final int RESULT_BATCH_SIZE = Math.max(
            1, Integer.getInteger("fluxzero.resultBatchSize", 16_384));
    private static final long RESULT_BATCH_COLLECTION_NANOS = Math.max(
            0L, Long.getLong("fluxzero.resultBatchCollectionNanos", 1_000_000L));

    @With
    private final Client client;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final ResponseMapper responseMapper;

    @Getter(lazy = true)
    private final GatewayClient gatewayClient = client.getGatewayClient(RESULT);
    @Getter(lazy = true)
    private final Backlog<PreparedResponse> responseBacklog = Backlog.forOrderedAsyncConsumer(
            this::publishBatch,
            RESULT_BATCH_SIZE,
            response -> 1L,
            RESULT_BATCH_SIZE,
            Duration.ofNanos(RESULT_BATCH_COLLECTION_NANOS));

    @Override
    protected ResultGateway createForNamespace(String namespace) {
        return withClient(client.forNamespace(namespace));
    }

    @Override
    public CompletableFuture<Void> respond(Object payload, Metadata metadata, String target, Integer requestId,
                                           Guarantee guarantee) {
        try {
            SerializedMessage serializedMessage = interceptDispatch(payload, metadata);
            if (serializedMessage == null) {
                return CompletableFuture.completedFuture(null);
            }
            serializedMessage.setTarget(target);
            serializedMessage.setRequestId(requestId);
            return getGatewayClient().append(guarantee, serializedMessage);
        } catch (Exception e) {
            String responseDescription = Objects.toString(payload != null && ifClass(payload) == null
                    ? payload.getClass() : payload);
            throw new GatewayException(FluxzeroErrors.responseDispatchFailed(
                    responseDescription, target, requestId, e), e);
        }
    }

    /**
     * Enqueues an automatically published handler response for ordered, context-preserving batch serialization.
     *
     * <p>The originating handler context is captured before the response is enqueued. Mapping, interception and
     * serialization then run with that context active on the result workers, allowing the complete response pipeline
     * to overlap with subsequent tracking work. Monitoring and appending remain ordered. The returned future completes
     * when the resulting transport append completes.</p>
     */
    public CompletableFuture<Void> respondBatched(Object response, String target, Integer requestId) {
        return respondBatched(response, target, requestId, null);
    }

    /**
     * Enqueues an automatically published handler response and delegates asynchronous preparation failures to the
     * originating consumer's error handler.
     */
    public CompletableFuture<Void> respondBatched(Object response, String target, Integer requestId,
                                                   ResultPreparationErrorHandler errorHandler) {
        ThreadLocalContext.Snapshot context = ThreadLocalContext.capture();
        BatchResponse batchResponse = BatchResponse.of(response, target, requestId);
        if (!(getGatewayClient() instanceof WebsocketGatewayClient)) {
            return respond(batchResponse.payload(), batchResponse.metadata(), target, requestId, Guarantee.NONE);
        }
        PreparedResponse prepared = new PreparedResponse(
                batchResponse.payload(), batchResponse.metadata(), target, requestId, context,
                errorHandler, new CompletableFuture<>());
        getResponseBacklog().addUntracked(prepared);
        return prepared.dispatched();
    }

    private CompletableFuture<Void> publishBatch(List<PreparedResponse> responses) {
        int size = responses.size();
        Message[] messages = new Message[size];
        SerializedMessage[] serialized = new SerializedMessage[size];
        Throwable[] failures = new Throwable[size];
        if (size < PARALLEL_SERIALIZATION_THRESHOLD) {
            for (int index = 0; index < size; index++) {
                prepareResponse(responses.get(index), messages, serialized, failures, index);
            }
        } else {
            IntStream.range(0, size).parallel().forEach(
                    index -> prepareResponse(responses.get(index), messages, serialized, failures, index));
        }

        PreparedResponse[] published = new PreparedResponse[size];
        int publishedSize = 0;
        for (int index = 0; index < size; index++) {
            PreparedResponse response = responses.get(index);
            if (failures[index] != null) {
                handlePreparationFailure(response, failures[index]);
                continue;
            }
            if (serialized[index] == null) {
                response.dispatched().complete(null);
                continue;
            }
            try {
                Message message = messages[index];
                response.context().run(() -> dispatchInterceptor.monitorDispatch(
                        message, RESULT, null, client.namespace(), false));
                serialized[publishedSize] = serialized[index];
                published[publishedSize++] = response;
            } catch (Throwable e) {
                handlePreparationFailure(response, e);
            }
        }
        if (publishedSize == 0) {
            return CompletableFuture.completedFuture(null);
        }

        SerializedMessage[] appendBatch = publishedSize == serialized.length
                ? serialized : Arrays.copyOf(serialized, publishedSize);
        PreparedResponse[] appendedResponses = publishedSize == published.length
                ? published : Arrays.copyOf(published, publishedSize);
        CompletableFuture<Void> result;
        try {
            result = getGatewayClient().append(Guarantee.NONE, appendBatch);
        } catch (Throwable e) {
            result = CompletableFuture.failedFuture(e);
        }
        result.whenComplete((ignored, failure) -> {
            for (PreparedResponse response : appendedResponses) {
                if (failure == null) {
                    response.dispatched().complete(null);
                } else {
                    response.dispatched().completeExceptionally(failure);
                }
            }
        });
        return result;
    }

    private void prepareResponse(PreparedResponse response, Message[] messages, SerializedMessage[] serialized,
                                 Throwable[] failures, int index) {
        try {
            response.context().run(() -> {
                Message message = dispatchInterceptor.interceptDispatch(
                        responseMapper.map(response.payload(), response.metadata()),
                        RESULT, null, client.namespace());
                if (message == null) {
                    return;
                }
                SerializedMessage result = dispatchInterceptor.modifySerializedMessage(
                        message.serialize(serializer), message, RESULT, null);
                if (result == null) {
                    return;
                }
                result.setTarget(response.target());
                result.setRequestId(response.requestId());
                messages[index] = message;
                serialized[index] = SerializedMessage.encode(result);
            });
        } catch (Throwable e) {
            failures[index] = e;
        }
    }

    private void handlePreparationFailure(PreparedResponse response, Throwable failure) {
        if (response.errorHandler() == null) {
            response.dispatched().completeExceptionally(failure);
            return;
        }
        try {
            CompletionStage<Void> recovery = response.context().supply(() -> response.errorHandler().handle(
                    failure, () -> enqueueRetry(response)));
            if (recovery == null) {
                response.dispatched().complete(null);
            } else {
                recovery.whenComplete((ignored, recoveryFailure) -> {
                    if (recoveryFailure == null) {
                        response.dispatched().complete(null);
                    } else {
                        response.dispatched().completeExceptionally(recoveryFailure);
                    }
                });
            }
        } catch (Throwable e) {
            response.dispatched().completeExceptionally(e);
        }
    }

    private CompletableFuture<Void> enqueueRetry(PreparedResponse response) {
        PreparedResponse retry = new PreparedResponse(
                response.payload(), response.metadata(), response.target(), response.requestId(), response.context(),
                null, new CompletableFuture<>());
        getResponseBacklog().addUntracked(retry);
        return retry.dispatched();
    }

    protected SerializedMessage interceptDispatch(Object payload, Metadata metadata) {
        Message message = dispatchInterceptor.interceptDispatch(
                responseMapper.map(payload, metadata), RESULT, null, client.namespace());
        SerializedMessage serializedMessage = message == null ? null
                : dispatchInterceptor.modifySerializedMessage(message.serialize(serializer), message, RESULT, null);
        if (serializedMessage != null) {
            dispatchInterceptor.monitorDispatch(message, RESULT, null, client.namespace(), false);
        }
        return serializedMessage;
    }

    private record BatchResponse(Object payload, Metadata metadata, String target, Integer requestId) {

        private static BatchResponse of(Object response, String target, Integer requestId) {
            return response instanceof Message message
                    ? new BatchResponse(message.getPayload(), message.getMetadata(), target, requestId)
                    : new BatchResponse(response, Metadata.empty(), target, requestId);
        }
    }

    private record PreparedResponse(Object payload, Metadata metadata, String target, Integer requestId,
                                    ThreadLocalContext.Snapshot context, ResultPreparationErrorHandler errorHandler,
                                    CompletableFuture<Void> dispatched) {
    }

    /** Handles an asynchronous result-preparation failure and may retry the supplied publication. */
    @FunctionalInterface
    public interface ResultPreparationErrorHandler {
        CompletionStage<Void> handle(Throwable failure, Supplier<CompletableFuture<Void>> retry);
    }

    @Override
    public void close() {
        super.close();
        getResponseBacklog().shutDown();
    }
}
