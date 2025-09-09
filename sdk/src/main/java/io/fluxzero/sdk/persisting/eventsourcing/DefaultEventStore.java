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

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.modeling.EventPublicationStrategy;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import lombok.AllArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.MessageType.EVENT;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

/**
 * Default implementation of the {@link EventStore} interface, providing mechanisms to store and retrieve events
 * associated with aggregate instances.
 * <p>
 * This implementation includes support for intercepting and modifying event messages during dispatch. The
 * {@link HandlerRegistry} is used to locally invoke event handlers after events are published.
 */
@AllArgsConstructor
@Slf4j
public class DefaultEventStore implements EventStore {
    private final EventStoreClient client;
    private final GatewayClient eventGateway;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    @Delegate
    private final HandlerRegistry localHandlerRegistry;

    @Override
    public CompletableFuture<Void> storeEvents(Object aggregateId, List<?> events, EventPublicationStrategy strategy) {
        CompletableFuture<Void> result;
        List<DeserializingMessage> messages = new ArrayList<>(events.size());
        try {
            int segment = ConsistentHashing.computeSegment(aggregateId.toString());
            events.forEach(e -> {
                DeserializingMessage deserializingMessage;
                if (e instanceof DeserializingMessage) {
                    deserializingMessage = (DeserializingMessage) e;
                } else {
                    Message m = dispatchInterceptor.interceptDispatch(Message.asMessage(e), EVENT, null);
                    SerializedMessage serializedMessage
                            = m == null ? null :
                            dispatchInterceptor.modifySerializedMessage(m.serialize(serializer), m, EVENT,
                                                                        null);
                    if (serializedMessage == null) {
                        return;
                    }
                    deserializingMessage = new DeserializingMessage(serializedMessage, type -> m.getPayload(), EVENT,
                                                                    null, serializer);
                }
                messages.add(deserializingMessage);
            });
            List<SerializedMessage> serializedEvents
                    = messages.stream().map(m -> m.getSerializedObject().getSegment() == null ?
                    m.getSerializedObject().withSegment(segment) : m.getSerializedObject()).toList();
            switch (strategy) {
                case DEFAULT, STORE_AND_PUBLISH, PUBLISH_ONLY -> {
                    for (DeserializingMessage message : messages) {
                        dispatchInterceptor.monitorDispatch(message.toMessage(), EVENT, null);
                    }
                }
            }
            result = switch (strategy) {
                case DEFAULT, STORE_AND_PUBLISH -> client.storeEvents(aggregateId.toString(), serializedEvents, false);
                case PUBLISH_ONLY ->
                        eventGateway.append(Guarantee.STORED, serializedEvents.toArray(SerializedMessage[]::new));
                case STORE_ONLY -> client.storeEvents(aggregateId.toString(), serializedEvents, true);
            };
        } catch (Exception e) {
            throw new EventSourcingException(format("Failed to store events %s for aggregate %s", events.stream().map(
                    DefaultEventStore::payloadName).collect(toList()), aggregateId), e);
        }
        switch (strategy) {
            case DEFAULT, STORE_AND_PUBLISH, PUBLISH_ONLY -> {
                for (DeserializingMessage message : messages) {
                    try {
                        localHandlerRegistry.handle(message).ifPresent(f -> f.getNow(null));
                    } catch (Throwable e) {
                        log.error("Failed to locally handle event {}. Continuing..",
                                  message.getPayloadClass().getSimpleName(), ObjectUtils.unwrapException(e));
                    }
                }
            }
        }
        return result;
    }

    private static String payloadName(Object event) {
        return event instanceof Message ? ((Message) event).getPayloadClass().getSimpleName()
                : event == null ? "null" : event.getClass().getSimpleName();
    }

    @Override
    public AggregateEventStream<DeserializingMessage> getEvents(Object aggregateId, long lastSequenceNumber,
                                                                int maxSize, boolean ignoreUnknownType) {
        try {
            AggregateEventStream<SerializedMessage> serializedEvents =
                    client.getEvents(aggregateId.toString(), lastSequenceNumber, maxSize);
            return serializedEvents.convert(stream -> serializer.deserializeMessages(stream, EVENT, ignoreUnknownType
                    ? UnknownTypeStrategy.IGNORE : UnknownTypeStrategy.FAIL));
        } catch (Exception e) {
            throw new EventSourcingException(format("Failed to obtain events for aggregate %s", aggregateId), e);
        }
    }
}
