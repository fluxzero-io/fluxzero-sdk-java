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

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.publishing.Append;
import io.fluxzero.common.api.publishing.SetRetentionTime;
import io.fluxzero.common.api.publishing.Truncate;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.MessageLogMaintenance;
import io.fluxzero.common.tracking.MessageStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.MessageType.METRICS;

@Slf4j
public class ProducerEndpoint extends WebsocketEndpoint {

    private final MessageStore store;
    private final MessageLogMaintenance maintenance;
    private final MessageType messageType;
    private final String topic;

    public ProducerEndpoint(MessageStore store) {
        this(newMaintenance(store), null, null);
    }

    public ProducerEndpoint(MessageStore store, MessageType messageType, String topic) {
        this(newMaintenance(store), messageType, topic);
    }

    /**
     * Creates a producer endpoint backed by the shared maintenance components for one message log.
     *
     * @param maintenance the shared message log maintenance components
     * @param messageType the message type exposed by this endpoint
     * @param topic       the topic exposed by this endpoint, or {@code null} for non-topic message types
     */
    public ProducerEndpoint(MessageLogMaintenance maintenance, MessageType messageType, String topic) {
        this.store = maintenance.getMessageStore();
        this.maintenance = maintenance;
        this.messageType = messageType;
        this.topic = topic;
    }

    public ProducerEndpoint(MessageStore store, MessageType messageType, String topic,
                            CommandIdempotencyStore commandIdempotencyStore) {
        this(newMaintenance(store), messageType, topic, commandIdempotencyStore);
    }

    /**
     * Creates a producer endpoint backed by the shared maintenance components for one message log.
     *
     * @param maintenance             the shared message log maintenance components
     * @param messageType             the message type exposed by this endpoint
     * @param topic                   the topic exposed by this endpoint, or {@code null} for non-topic message types
     * @param commandIdempotencyStore the idempotency store used for command handling
     */
    public ProducerEndpoint(MessageLogMaintenance maintenance, MessageType messageType, String topic,
                            CommandIdempotencyStore commandIdempotencyStore) {
        super(commandIdempotencyStore);
        this.store = maintenance.getMessageStore();
        this.maintenance = maintenance;
        this.messageType = messageType;
        this.topic = topic;
    }

    @Handle
    CompletableFuture<Void> handle(Append request) {
        return store.append(request.getMessages().toArray(SerializedMessage[]::new));
    }

    @Handle
    void handle(SetRetentionTime request) {
        store.setRetentionTime(Optional.ofNullable(
                request.getRetentionTimeInSeconds()).map(Duration::ofSeconds).orElse(null));
    }

    @Handle
    CompletableFuture<Void> handle(Truncate request) {
        return maintenance.truncate();
    }

    private static MessageLogMaintenance newMaintenance(MessageStore store) {
        return new MessageLogMaintenance(store, new InMemoryPositionStore(), new DefaultTrackingStrategy(store));
    }

    @Override
    protected boolean shouldHandleIdempotently(Command command) {
        return messageType != METRICS && super.shouldHandleIdempotently(command);
    }

    @Override
    public String toString() {
        return topic == null
                ? "ProducerEndpoint{logType='" + messageType + "'}"
                : "ProducerEndpoint{messageType=" + messageType + ", topic='" + topic + "'}";
    }
}
