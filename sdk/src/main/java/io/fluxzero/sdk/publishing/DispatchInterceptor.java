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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Message;

import java.util.List;

/**
 * Mechanism that enables modification, monitoring, or conditional suppression of messages before they are dispatched to
 * local handlers or published to the Fluxzero Runtime.
 * <p>
 * A {@code DispatchInterceptor} allows observing and transforming messages during the dispatch process. It is typically
 * used to inject metadata, rewrite payloads, log outgoing messages, or prevent dispatching certain messages based on
 * custom rules.
 * <p>
 * Implementations can also be registered via Java's {@link ServiceLoader}. Service-loaded interceptors are picked up
 * automatically by Fluxzero, including when using the {@code TestFixture}, and are ordered using {@link
 * io.fluxzero.sdk.common.Order @Order}.
 *
 * <p><strong>Key behaviors:</strong>
 * <ul>
 *   <li>{@link #interceptDispatch} is used for altering or blocking a message before it's handled or published.</li>
 *   <li>{@link #modifySerializedMessage} can change the final serialized message before it's stored or sent to the Fluxzero Runtime.</li>
 *   <li>{@link #monitorDispatch} is a post-processing hook for monitoring without side effects or blocking.</li>
 * </ul>
 *
 * @see io.fluxzero.sdk.configuration.FluxzeroBuilder#addDispatchInterceptor
 */
@FunctionalInterface
public interface DispatchInterceptor {

    /**
     * Default dispatch interceptors discovered via Java's service loader, sorted by {@link
     * io.fluxzero.sdk.common.Order}. These interceptors are applied automatically by Fluxzero.
     */
    List<DispatchInterceptor> defaultInterceptors = ClientUtils.loadServices(DispatchInterceptor.class);

    /**
     * No-op implementation of the {@code DispatchInterceptor} that returns the original message unchanged.
     */
    DispatchInterceptor noOp = new DispatchInterceptor() {
        @Override
        public Message interceptDispatch(Message message, MessageType messageType, String topic) {
            return message;
        }

        @Override
        public PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
            return PreparedLocalDispatch.noOp;
        }
    };

    /**
     * Intercepts the dispatch of a message before it is serialized and published or locally handled.
     * <p>
     * You may modify the message or return {@code null} to block dispatching. Throwing an exception also prevents
     * dispatching.
     *
     * @param message     the message to be dispatched
     * @param messageType the type of the message (e.g., COMMAND, EVENT, etc.)
     * @param topic       the target topic or null if not applicable
     * @return the modified message, the same message, or {@code null} to prevent dispatch
     */
    Message interceptDispatch(Message message, MessageType messageType, String topic);

    /**
     * Prepares this interceptor for repeated local dispatches with the same static message characteristics.
     *
     * <p>This optional method lets local handling apply an interceptor without creating a complete {@link Message}
     * first. Implementations must preserve the observable behavior of {@link #interceptDispatch(Message, MessageType,
     * String)}. Return {@code null} when that is not possible; Fluxzero will then use the regular dispatch path for
     * this payload type.</p>
     *
     * <p>The returned policy may be cached and used concurrently. State belonging to a single dispatch must be stored
     * in the supplied {@link io.fluxzero.sdk.tracking.handling.LocalExecution}, not in the policy itself.</p>
     *
     * @param descriptor payload class, message type, and topic shared by the local dispatches
     * @return a thread-safe prepared policy, or {@code null} to use regular message-based dispatch
     */
    default PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
        return null;
    }

    /**
     * Allows modifications to the serialized representation of the message before it is actually published.
     * <p>
     * This is called after {@link #interceptDispatch} and should not be used to block dispatching — use
     * {@link #interceptDispatch} for that purpose instead.
     *
     * @param serializedMessage the serialized form of the message
     * @param message           the deserialized message object
     * @param messageType       the message type
     * @param topic             the target topic
     * @return the modified or original {@link SerializedMessage}
     */
    default SerializedMessage modifySerializedMessage(SerializedMessage serializedMessage,
                                                      Message message, MessageType messageType, String topic) {
        return serializedMessage;
    }

    /**
     * Hook to observe the dispatch of a message. This method is called after all interceptors have had a chance to
     * block or modify the message.
     * <p>
     * Use this for logging or metrics, but <em>not</em> to alter or block the message.
     *
     * @param message     the final message about to be handled or published
     * @param messageType the type of the message
     * @param topic       the topic to which the message is dispatched (can be null)
     * @param namespace   the namespace to which the message is dispatched (can be null)
     * @param request     whether the sender expects a response to this dispatch
     */
    default void monitorDispatch(Message message, MessageType messageType, String topic, String namespace,
                                 boolean request) {
        // No-op by default
    }

    /**
     * Chains this interceptor with another. The resulting interceptor applies this one first, then the next one.
     *
     * @param nextInterceptor the interceptor to run after this one
     * @return a new {@code DispatchInterceptor} representing the combined logic
     */
    default DispatchInterceptor andThen(DispatchInterceptor nextInterceptor) {
        return CompositeDispatchInterceptor.combine(this, nextInterceptor);
    }
}
