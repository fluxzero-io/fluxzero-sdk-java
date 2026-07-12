/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerMethodPlan;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.publishing.PreparedLocalDispatch;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Holds the state of one payload-first local handler invocation.
 *
 * <p>This is a Fluxzero infrastructure type. It is public so dispatch interceptors, handler registries, and parameter
 * resolvers in different SDK packages can cooperate on the same lazy invocation. Applications should not construct,
 * retain, or reuse an instance. An execution is confined to the invoking thread and its contents are valid only while
 * that invocation is active.</p>
 */
public final class LocalExecution implements LocalHandlerInput {
    private static final ThreadLocal<LocalExecution> current = new ThreadLocal<>();

    private boolean active;
    private Object payload;
    private MessageType messageType;
    private String topic;
    private Serializer serializer;
    private Metadata metadata;
    private PreparedLocalDispatch dispatch;
    private User user;
    private Message message;
    private DeserializingMessage deserializingMessage;
    private HandlerDescriptor handler;
    private Invocation invocation;
    private boolean batchContextUsed;
    private Object result;
    private CompletableFuture<?> resultFuture;
    private Message resultMessage;

    private LocalExecution() {
    }

    /**
     * Attempts to handle a payload through the prepared local path.
     *
     * <p>This infrastructure method returns {@code null} when prepared handling is unsafe in the current context or
     * when the registry requests the regular message-based path. A non-null execution contains the completed local
     * result and must be followed by {@link #releaseResult()} after the result has been consumed.</p>
     *
     * @param payload the dispatched payload
     * @param messageType the type of message being dispatched
     * @param topic the dispatch topic, or {@code null}
     * @param serializer the serializer used if a complete message becomes necessary
     * @param dispatch the prepared dispatch-interceptor policy
     * @param registry the local handler registry
     * @return the completed execution, or {@code null} to use regular message-based dispatch
     */
    public static LocalExecution handle(Object payload, MessageType messageType, String topic,
                                        Serializer serializer, PreparedLocalDispatch dispatch,
                                        HandlerRegistry registry) {
        LocalExecution execution = current.get();
        if (execution == null) {
            execution = new LocalExecution();
            current.set(execution);
        }
        if (execution.active || DeserializingMessage.hasThreadLocalContext() || Invocation.hasThreadLocalContext()) {
            return null;
        }
        return execution.handleInFrame(payload, messageType, topic, serializer, dispatch, registry);
    }

    private LocalExecution handleInFrame(Object nextPayload, MessageType nextMessageType, String nextTopic,
                                         Serializer nextSerializer, PreparedLocalDispatch nextDispatch,
                                         HandlerRegistry registry) {
        active = true;
        payload = nextPayload;
        messageType = nextMessageType;
        topic = nextTopic;
        serializer = nextSerializer;
        metadata = null;
        dispatch = nextDispatch;
        user = null;
        message = null;
        deserializingMessage = null;
        handler = null;
        invocation = null;
        batchContextUsed = false;
        result = null;
        resultFuture = null;
        resultMessage = null;
        try {
            return dispatch.prepare(this) && registry.handleLocal(this) ? this : null;
        } finally {
            active = false;
            payload = null;
            messageType = null;
            topic = null;
            serializer = null;
            metadata = null;
            dispatch = null;
            user = null;
            message = null;
            deserializingMessage = null;
            handler = null;
            invocation = null;
        }
    }

    /**
     * Stores a synchronously completed handler value in this execution.
     *
     * @param value the value returned by the handler
     */
    public void complete(Object value) {
        result = value;
    }

    /**
     * Stores an asynchronous handler result in this execution.
     *
     * @param future the handler result future
     */
    public void completeAsync(CompletableFuture<?> future) {
        resultFuture = future;
        resultMessage = getMessage().toMessage();
    }

    /**
     * Stores a handler failure in this execution.
     *
     * @param error the handling error
     */
    public void completeExceptionally(Throwable error) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        resultFuture = future;
        resultMessage = getMessage().toMessage();
    }

    /**
     * Returns whether handling completed synchronously without throwing.
     *
     * @return {@code true} when {@link #getResult()} contains the synchronous result
     */
    public boolean isCompletedSuccessfully() {
        return resultFuture == null;
    }

    /**
     * Returns the synchronously completed handler value.
     *
     * @return the handler value, which may be {@code null}
     */
    public Object getResult() {
        return result;
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> getResultFuture() {
        return (CompletableFuture<Object>) resultFuture;
    }

    public Message getResultMessage() {
        return resultMessage;
    }

    public void releaseResult() {
        result = null;
        resultFuture = null;
        resultMessage = null;
    }

    /**
     * Returns the message for the active payload-first invocation on this thread.
     *
     * <p>Calling this method materializes the message if necessary.</p>
     *
     * @return the active local message, or {@code null} outside a payload-first invocation
     */
    public static DeserializingMessage currentMessage() {
        LocalExecution execution = current.get();
        return execution == null || !execution.active ? null : execution.getMessage();
    }

    /**
     * Marks batch-scoped message state as used by the active payload-first handler.
     *
     * <p>This infrastructure hook ensures that batch callbacks and resources are completed after the invocation.</p>
     */
    public static void markBatchContextUsed() {
        LocalExecution execution = current.get();
        if (execution != null && execution.active) {
            execution.batchContextUsed = true;
        }
    }

    static Invocation currentInvocation() {
        LocalExecution execution = current.get();
        if (execution == null || !execution.active || execution.handler == null) {
            return null;
        }
        if (execution.invocation == null) {
            execution.invocation = Invocation.forHandler(execution.handler);
        }
        return execution.invocation;
    }

    /** {@inheritDoc} */
    @Override
    public Object getPayload() {
        return payload;
    }

    /** {@inheritDoc} */
    @Override
    public DeserializingMessage getMessage() {
        if (deserializingMessage == null) {
            if (message == null) {
                message = new Message(payload, getMetadata());
            }
            deserializingMessage = new DeserializingMessage(message, messageType, topic, serializer);
        }
        return deserializingMessage;
    }

    /** {@inheritDoc} */
    @Override
    public DeserializingMessage getMessageIfAvailable() {
        return deserializingMessage;
    }

    /** {@inheritDoc} */
    @Override
    public MessageType getMessageType() {
        return messageType;
    }

    /** {@inheritDoc} */
    @Override
    public User getUser(UserProvider provider) {
        return user;
    }

    /**
     * Stores the user selected during dispatch for later handler-parameter resolution.
     *
     * @param user the selected user, or {@code null}
     */
    public void setUser(User user) {
        this.user = user;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasResolvedUser() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public Metadata getMetadata() {
        if (metadata == null) {
            metadata = dispatch.interceptMetadata(Metadata.empty(), this);
        }
        return metadata;
    }

    /** {@inheritDoc} */
    @Override
    public boolean containsMetadata(String key) {
        return metadata != null && metadata.containsKey(key);
    }

    /** {@inheritDoc} */
    @Override
    public Long getIndex() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public Object invoke(HandlerMethodPlan<DeserializingMessage> plan) {
        handler = plan;
        Object result = null;
        Throwable error = null;
        try {
            result = plan.invoke(this);
            return result;
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            if (invocation != null) {
                invocation.completeLocal(result, error);
            }
            if (batchContextUsed) {
                DeserializingMessage.completeLocalBatch(error);
            }
            handler = null;
            invocation = null;
        }
    }
}
    /**
     * Returns the asynchronous handler result.
     *
     * @return the result future, or {@code null} after synchronous completion
     */
    /**
     * Returns the materialized message retained for asynchronous result processing.
     *
     * @return the message, or {@code null} after synchronous completion
     */
    /**
     * Clears the completed result so this thread-local frame does not retain application objects.
     */
