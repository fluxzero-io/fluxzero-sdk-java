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

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.tracking.ClaimSegment;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.DisconnectTracker;
import io.fluxzero.common.api.tracking.GetPosition;
import io.fluxzero.common.api.tracking.GetPositionResult;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadFromIndex;
import io.fluxzero.common.api.tracking.ReadFromIndexResult;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.api.tracking.ResetPosition;
import io.fluxzero.common.api.tracking.StorePosition;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.MessageLogMaintenance;
import io.fluxzero.common.tracking.MessageStore;
import io.fluxzero.common.tracking.PositionStore;
import io.fluxzero.common.tracking.TrackingStrategy;
import io.fluxzero.common.tracking.WebSocketTracker;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.MessageType.METRICS;

@Slf4j
public class ConsumerEndpoint extends WebsocketEndpoint {

    private final TrackingStrategy trackingStrategy;
    private final MessageStore messageStore;
    private final PositionStore positionStore;
    private final MessageType messageType;
    private final String topic;

    public ConsumerEndpoint(MessageStore messageStore, MessageType messageType) {
        this(new DefaultTrackingStrategy(messageStore), messageStore, new InMemoryPositionStore(), messageType, null);
    }

    public ConsumerEndpoint(MessageStore messageStore, MessageType messageType,
                            CommandIdempotencyStore commandIdempotencyStore) {
        this(new DefaultTrackingStrategy(messageStore), messageStore, new InMemoryPositionStore(), messageType, null,
             commandIdempotencyStore);
    }

    /**
     * Creates a consumer endpoint for a topic-specific message log.
     *
     * @param messageStore            the message store backing the endpoint
     * @param messageType             the message type exposed by this endpoint
     * @param topic                   the topic exposed by this endpoint
     * @param commandIdempotencyStore the idempotency store used for command handling
     */
    public ConsumerEndpoint(MessageStore messageStore, MessageType messageType, String topic,
                            CommandIdempotencyStore commandIdempotencyStore) {
        this(new DefaultTrackingStrategy(messageStore), messageStore, new InMemoryPositionStore(), messageType, topic,
             commandIdempotencyStore);
    }

    /**
     * Creates a consumer endpoint backed by shared maintenance components for one message log.
     *
     * @param maintenance             the shared message log maintenance components
     * @param messageType             the message type exposed by this endpoint
     * @param commandIdempotencyStore the idempotency store used for command handling
     */
    public ConsumerEndpoint(MessageLogMaintenance maintenance, MessageType messageType,
                            CommandIdempotencyStore commandIdempotencyStore) {
        this(maintenance, messageType, null, commandIdempotencyStore);
    }

    /**
     * Creates a consumer endpoint backed by shared maintenance components for one message log.
     *
     * @param maintenance             the shared message log maintenance components
     * @param messageType             the message type exposed by this endpoint
     * @param topic                   the topic exposed by this endpoint, or {@code null} for non-topic message types
     * @param commandIdempotencyStore the idempotency store used for command handling
     */
    public ConsumerEndpoint(MessageLogMaintenance maintenance, MessageType messageType, String topic,
                            CommandIdempotencyStore commandIdempotencyStore) {
        this(maintenance.getTrackingStrategy(), maintenance.getMessageStore(), maintenance.getPositionStore(),
             messageType, topic, commandIdempotencyStore);
    }

    public ConsumerEndpoint(TrackingStrategy trackingStrategy, MessageStore messageStore,
                            PositionStore positionStore, MessageType messageType) {
        this(trackingStrategy, messageStore, positionStore, messageType, null);
    }

    /**
     * Creates a consumer endpoint from explicit tracking components.
     *
     * @param trackingStrategy the tracking strategy backing reads and claims
     * @param messageStore     the message store backing direct reads
     * @param positionStore    the position store backing consumer positions
     * @param messageType      the message type exposed by this endpoint
     * @param topic            the topic exposed by this endpoint, or {@code null} for non-topic message types
     */
    public ConsumerEndpoint(TrackingStrategy trackingStrategy, MessageStore messageStore,
                            PositionStore positionStore, MessageType messageType, String topic) {
        this.trackingStrategy = trackingStrategy;
        this.messageStore = messageStore;
        this.positionStore = positionStore;
        this.messageType = messageType;
        this.topic = topic;
    }

    /**
     * Creates a consumer endpoint from explicit tracking components.
     *
     * @param trackingStrategy        the tracking strategy backing reads and claims
     * @param messageStore            the message store backing direct reads
     * @param positionStore           the position store backing consumer positions
     * @param messageType             the message type exposed by this endpoint
     * @param topic                   the topic exposed by this endpoint, or {@code null} for non-topic message types
     * @param commandIdempotencyStore the idempotency store used for command handling
     */
    public ConsumerEndpoint(TrackingStrategy trackingStrategy, MessageStore messageStore,
                            PositionStore positionStore, MessageType messageType, String topic,
                            CommandIdempotencyStore commandIdempotencyStore) {
        super(commandIdempotencyStore);
        this.trackingStrategy = trackingStrategy;
        this.messageStore = messageStore;
        this.positionStore = positionStore;
        this.messageType = messageType;
        this.topic = topic;
    }

    @Handle
    void handle(Read read, ServerWebsocketSession session) {
        trackingStrategy.getBatch(
                new WebSocketTracker(read, messageType, getClientId(session), getNegotiatedSessionId(session), batch
                        -> doSendResult(session, new ReadResult(read.getRequestId(), batch))), positionStore);
    }

    @Handle
    void handle(ClaimSegment read, ServerWebsocketSession session) {
        trackingStrategy.claimSegment(
                new WebSocketTracker(read, messageType, getClientId(session), getNegotiatedSessionId(session), batch ->
                        doSendResult(session, new ClaimSegmentResult(read.getRequestId(), batch.getPosition(),
                                                                     batch.getSegment()))), positionStore);
    }

    @Handle
    CompletableFuture<Void> handle(StorePosition storePosition) {
        return positionStore.storePosition(storePosition.getConsumer(), storePosition.getSegment(),
                                           storePosition.getLastIndex());
    }

    @Handle
    CompletableFuture<Void> handle(ResetPosition resetPosition) {
        return positionStore.resetPosition(resetPosition.getConsumer(), resetPosition.getLastIndex());
    }

    @Handle
    void handle(DisconnectTracker disconnectTracker) {
        trackingStrategy.disconnectTrackers(
                t -> Objects.equals(t.getConsumerName(), disconnectTracker.getConsumer())
                     && Objects.equals(t.getTrackerId(), disconnectTracker.getTrackerId()),
                disconnectTracker.isSendFinalEmptyBatch());
    }

    @Handle
    ReadFromIndexResult handle(ReadFromIndex read) {
        return new ReadFromIndexResult(read.getRequestId(),
                                       messageStore.getBatch(read.getMinIndex(), read.getMaxSize(), true));
    }

    @Handle
    GetPositionResult handle(GetPosition read) {
        return new GetPositionResult(read.getRequestId(), positionStore.position(read.getConsumer()));
    }

    @Override
    public void onClose(ServerWebsocketSession session, WebsocketCloseReason closeReason) {
        super.onClose(session, closeReason);
        var trackers = trackingStrategy.disconnectTrackers(t -> t instanceof WebSocketTracker
                                                                && ((WebSocketTracker) t).getSessionId()
                                                                        .equals(getNegotiatedSessionId(session)),
                                                           false);
        trackers.forEach(t -> metricsLog.registerMetrics(
                new DisconnectTracker(messageType, t.getConsumerName(), t.getTrackerId(),
                                      false, Guarantee.STORED)));
    }

    @Override
    protected void shutDown() {
        trackingStrategy.disconnectTrackers(t -> true, false);
        trackingStrategy.close();
        super.shutDown();
    }

    @Override
    protected boolean shouldHandleIdempotently(Command command) {
        return messageType != METRICS && super.shouldHandleIdempotently(command);
    }

    @Override
    public String toString() {
        return topic == null
                ? "ConsumerEndpoint{logType='" + messageType + "'}"
                : "ConsumerEndpoint{messageType=" + messageType + ", topic='" + topic + "'}";
    }
}
