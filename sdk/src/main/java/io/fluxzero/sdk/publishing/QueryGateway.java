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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Namespaced;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import io.fluxzero.sdk.tracking.handling.Request;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Gateway interface for dispatching queries and receiving responses in Fluxzero.
 * <p>
 * The {@code QueryGateway} provides a high-level API for submitting queries and retrieving
 * results, either asynchronously or synchronously. It supports rich metadata and integrates with
 * both local and remote query handlers.
 * <p>
 * Queries can be sent as raw payloads, {@link Message} objects, or {@link Request} wrappers for typed responses.
 * This interface also supports registration of local handlers via {@link #registerHandler(Object)}.
 * <p>
 * For message types that are queries, the {@link MessageType#QUERY} enum is typically used.
 *
 * @see HasLocalHandlers
 * @see io.fluxzero.sdk.tracking.handling.HandleQuery
 * @see LocalOnly
 */
public interface QueryGateway extends Namespaced<QueryGateway>, HasLocalHandlers {

    /**
     * Sends the given query asynchronously and returns a future representing the result.
     * <p>
     * If the query is a {@link Message}, it is dispatched as-is. Otherwise, it is wrapped in a new message.
     *
     * @param query the query object
     * @param <R>   the expected type of the result
     * @return a {@link CompletableFuture} with the result
     */
    <R> CompletableFuture<R> send(Object query);

    /**
     * Sends the given query along with metadata asynchronously and returns a future representing the result.
     *
     * @param payload  the query payload
     * @param metadata additional metadata to attach to the message
     * @param <R>      the expected result type
     * @return a {@link CompletableFuture} containing the query result
     */
    <R> CompletableFuture<R> send(Object payload, Metadata metadata);

    /**
     * Sends the given {@link Message} and returns a future representing the resulting message.
     * This method gives access to the full {@link Message} returned by the query handler.
     *
     * @param message the message representing the query
     * @return a {@link CompletableFuture} with the response message
     */
    CompletableFuture<Message> sendForMessage(Message message);

    /**
     * Sends multiple queries asynchronously and returns a list of futures, one for each result.
     *
     * @param messages one or more query objects or messages
     * @param <R>      the expected result type for each query
     * @return a list of {@link CompletableFuture}s for each query result
     */
    <R> List<CompletableFuture<R>> send(Object... messages);

    /**
     * Sends multiple query {@link Message}s and returns a list of futures for the raw responses.
     *
     * @param messages one or more messages representing queries
     * @return a list of {@link CompletableFuture}s containing the result messages
     */
    List<CompletableFuture<Message>> sendForMessages(Message... messages);

    /**
     * Sends the given query and waits for the result, blocking the current thread.
     *
     * @param query the query object
     * @param <R>   the expected result type
     * @return the result of the query
     */
    <R> R sendAndWait(Object query);

    /**
     * Sends the given query and metadata, then waits for the result.
     *
     * @param payload  the query payload
     * @param metadata additional metadata to attach to the query
     * @param <R>      the expected result type
     * @return the result of the query
     */
    <R> R sendAndWait(Object payload, Metadata metadata);

    /**
     * Sends a typed {@link Request} query and returns a future representing the result.
     *
     * @param query the {@link Request} query
     * @param <R>   the expected result type
     * @return a {@link CompletableFuture} containing the result
     */
    <R> CompletableFuture<R> send(Request<R> query);

    /**
     * Sends a typed {@link Request} query with additional metadata and returns a future with the result.
     *
     * @param payload  the {@link Request} payload
     * @param metadata metadata to attach to the request
     * @param <R>      the expected result type
     * @return a {@link CompletableFuture} with the result
     */
    <R> CompletableFuture<R> send(Request<R> payload, Metadata metadata);

    /**
     * Sends a typed {@link Request} query and waits for the result.
     *
     * @param query the {@link Request} query
     * @param <R>   the expected result type
     * @return the result of the query
     */
    <R> R sendAndWait(Request<R> query);

    /**
     * Sends a typed {@link Request} query with metadata and waits for the result.
     *
     * @param payload  the {@link Request} payload
     * @param metadata additional metadata to attach to the query
     * @param <R>      the expected result type
     * @return the result of the query
     */
    <R> R sendAndWait(Request<R> payload, Metadata metadata);

    /**
     * Gracefully shuts down this gateway and releases any held resources.
     */
    void close();

    /**
     * Sends a request with the given publication guarantee. The future represents its business response.
     * {@link Guarantee#DEFAULT} uses the configured request policy; {@link Guarantee#SENT} and
     * {@link Guarantee#STORED} override it for this call only. {@link Guarantee#NONE} is not supported.
     *
     * @param payload request payload
     * @param metadata request metadata; use {@link Metadata#empty()} when none is needed
     * @param guarantee request publication guarantee
     * @param <R> response payload type
     * @return the business response future
     */
    default <R> CompletableFuture<R> send(Object payload, Metadata metadata, Guarantee guarantee) {
        requireDefaultRequestGuarantee(guarantee);
        return send(payload, metadata);
    }

    /** Sends a typed request with a per-call publication guarantee and returns its business response future. */
    default <R> CompletableFuture<R> send(Request<R> payload, Metadata metadata, Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? send(payload, metadata) : send((Object) payload, metadata, guarantee);
    }

    /**
     * Sends a request with a per-call publication guarantee and waits for its business response.
     * Storage acknowledgment does not replace or delay an already available business response.
     */
    default <R> R sendAndWait(Object payload, Metadata metadata, Guarantee guarantee) {
        requireDefaultRequestGuarantee(guarantee);
        return sendAndWait(payload, metadata);
    }

    /** Sends a typed request with a per-call publication guarantee and waits for its business response. */
    default <R> R sendAndWait(Request<R> payload, Metadata metadata, Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? sendAndWait(payload, metadata)
                : sendAndWait((Object) payload, metadata, guarantee);
    }

    /**
     * Sends full request messages with one publication guarantee and returns business response futures in input order.
     * Custom gateways retain their existing batch behavior for {@code DEFAULT}; unsupported explicit guarantees fail.
     */
    default List<CompletableFuture<Message>> sendForMessages(Guarantee guarantee, Message... messages) {
        requireDefaultRequestGuarantee(guarantee);
        return sendForMessages(messages);
    }

    private static void requireDefaultRequestGuarantee(Guarantee guarantee) {
        switch (java.util.Objects.requireNonNull(guarantee, "guarantee")) {
            case DEFAULT -> { }
            case NONE -> throw new IllegalArgumentException("Request publication requires SENT or STORED");
            default -> throw new UnsupportedOperationException("This gateway does not support per-call request guarantees");
        }
    }
}
