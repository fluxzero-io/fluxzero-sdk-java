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

package io.fluxzero.sdk.publishing.routing;

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperty;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotation;

/**
 * A {@link DispatchInterceptor} that assigns a routing segment to messages prior to dispatch.
 *
 * <p>This interceptor computes a consistent hash-based segment index for each message using
 * {@link ConsistentHashing#computeSegment(String)} and injects it into the serialized message if no segment has already
 * been set.
 *
 * <p>Fluxzero Runtime uses the segment value for consistent message routing and load distribution.
 * It ensures that all messages with the same routing key are handled by the same segment, preserving message affinity
 * and ordering guarantees within that segment.
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>If {@code SerializedMessage#getSegment()} is {@code null}, the interceptor computes the segment
 *       from the message's routing key via {@link Message#computeRoutingKey()}.</li>
 *   <li>If a segment is already assigned, the interceptor leaves it unchanged.</li>
 *   <li>The message content itself is not modified; only the serialized representation is updated.</li>
 * </ul>
 *
 * <p>This interceptor is typically enabled by default for most gateway clients (e.g., command, event, query, etc.),
 * ensuring that routing behavior is applied uniformly before messages are published to Fluxzero Runtime.
 *
 * @see DispatchInterceptor
 * @see ConsistentHashing
 * @see SerializedMessage
 */
@Slf4j
public class MessageRoutingInterceptor implements DispatchInterceptor {
    private final Function<Message, String> modelRoutingTarget;

    /** Routes messages using only their explicitly defined routing key. */
    public MessageRoutingInterceptor() {
        this(null);
    }

    /** Adds a command-only Model-ID fallback when no explicit routing is declared. */
    public MessageRoutingInterceptor(Function<Message, String> modelRoutingTarget) {
        this.modelRoutingTarget = modelRoutingTarget;
    }

    /** An explicit routing declaration suppresses automatic Model routing even if its value is absent. */
    public static boolean hasExplicitRouting(Message message) {
        Class<?> payloadType = message.getPayloadClass();
        return getAnnotation(payloadType, RoutingKey.class).filter(a -> !a.value().isBlank()).isPresent()
               || getAnnotatedProperty(payloadType, RoutingKey.class).isPresent();
    }

    @Override
    public io.fluxzero.sdk.publishing.PreparedLocalDispatch prepareLocalDispatch(
            io.fluxzero.sdk.publishing.LocalDispatchDescriptor descriptor) {
        return io.fluxzero.sdk.publishing.PreparedLocalDispatch.noOp;
    }
    /**
     * Returns the unmodified {@link Message} as this interceptor only modifies the serialized form.
     */
    @Override
    public Message interceptDispatch(Message message, MessageType messageType, String topic) {
        return message;
    }

    /**
     * Computes and sets the routing segment on the serialized message if not already present.
     *
     * @param serializedMessage the message to be sent
     * @param m                 the original message object
     * @param messageType       the type of message (e.g., command, event, query)
     * @param topic             the topic to which the message will be published
     * @return the same {@code SerializedMessage} instance, possibly updated with a segment
     */
    @Override
    public SerializedMessage modifySerializedMessage(SerializedMessage serializedMessage, Message m,
                                                     MessageType messageType, String topic) {
        if (serializedMessage.getSegment() == null) {
            m.computeRoutingKey().map(ConsistentHashing::computeSegment).ifPresent(serializedMessage::setSegment);
            if (serializedMessage.getSegment() == null && modelRoutingTarget != null
                && messageType == MessageType.COMMAND && !hasExplicitRouting(m)) {
                String target = modelRoutingTarget.apply(m);
                if (target != null) {
                    serializedMessage.setSegment(ConsistentHashing.computeSegment(target));
                }
            }
        }
        return serializedMessage;
    }
}
