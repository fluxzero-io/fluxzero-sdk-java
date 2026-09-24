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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.HasMessageStore;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.MessageLogMaintenance;
import io.fluxzero.common.tracking.MessageStore;
import io.fluxzero.common.tracking.PositionStore;
import io.fluxzero.common.tracking.TrackingStrategy;
import io.fluxzero.common.tracking.WebSocketTracker;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import lombok.Getter;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static io.fluxzero.common.tracking.DefaultTrackingStrategy.DEFAULT_INITIAL_POSITION_LAG;

/**
 * In-memory implementation of the {@link TrackingClient} and {@link GatewayClient} interfaces, designed for
 * local-only or test-time usage.
 * <p>
 * This client simulates message tracking behavior without requiring a live Fluxzero backend. It uses local
 * data structures to emulate:
 * <ul>
 *   <li>A {@link MessageStore} to persist serialized messages</li>
 *   <li>A {@link PositionStore} to track consumer offsets</li>
 *   <li>A {@link TrackingStrategy} to emulate segment claims and batch fetch behavior</li>
 * </ul>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Unit tests or integration tests involving command/event/query handling</li>
 *   <li>Local development without using Fluxzero Runtime as a backend</li>
 *   <li>Custom tooling that simulates tracking or playback behavior</li>
 * </ul>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>Messages are stored in memory and may be optionally expired using {@code messageExpiration} if configured</li>
 *   <li>Tracks per-consumer positions independently via an in-memory position store</li>
 *   <li>Implements segment claiming and disconnection logic to simulate parallel consumer behavior</li>
 *   <li>Supports custom topics for {@link io.fluxzero.common.MessageType#CUSTOM} or {@link io.fluxzero.common.MessageType#DOCUMENT}</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * TrackingClient testClient = new LocalTrackingClient(MessageType.EVENT, "test-topic", Duration.ofMinutes(10));
 * }</pre>
 *
 * @see TrackingClient
 * @see GatewayClient
 * @see HasMessageStore
 * @see InMemoryMessageStore
 * @see InMemoryPositionStore
 */
public class LocalTrackingClient implements TrackingClient, GatewayClient, HasMessageStore {
    private static final String LOCAL_CLIENT_ID = ManagementFactory.getRuntimeMXBean().getName();

    @Getter
    private final MessageStore messageStore;
    private final PositionStore positionStore;

    @Getter
    private final MessageType messageType;
    @Getter
    private final String topic;
    private final Duration initialPositionLag;

    @Getter(lazy = true)
    private final TrackingStrategy trackingStrategy =
            new DefaultTrackingStrategy(messageStore, positionStore, initialPositionLag);
    @Getter(lazy = true)
    private final MessageLogMaintenance messageLogMaintenance =
            new MessageLogMaintenance(messageStore, positionStore, getTrackingStrategy());

    /**
     * Creates a local tracking client from explicit storage components using the default initial look-back.
     */
    public LocalTrackingClient(MessageStore messageStore, PositionStore positionStore, MessageType messageType,
                               String topic) {
        this(messageStore, positionStore, messageType, topic, DEFAULT_INITIAL_POSITION_LAG);
    }

    /**
     * Creates a local tracking client from explicit storage components.
     *
     * @param messageStore       local message store
     * @param positionStore      local consumer position store
     * @param messageType        message log type
     * @param topic              optional message topic
     * @param initialPositionLag duration subtracted from the current time for a new consumer
     */
    public LocalTrackingClient(MessageStore messageStore, PositionStore positionStore, MessageType messageType,
                               String topic, Duration initialPositionLag) {
        this.messageStore = messageStore;
        this.positionStore = positionStore;
        this.messageType = messageType;
        this.topic = topic;
        this.initialPositionLag = validateInitialPositionLag(initialPositionLag);
    }

    public LocalTrackingClient(MessageType messageType, String topic, Duration messageExpiration) {
        this(messageType, topic, messageExpiration, DEFAULT_INITIAL_POSITION_LAG);
    }

    /**
     * Creates a local tracking client with a configurable look-back for consumers without a stored position.
     *
     * @param messageType         message log type
     * @param topic               optional message topic
     * @param messageExpiration   retention duration for local messages
     * @param initialPositionLag  duration subtracted from the current time for a new consumer
     */
    public LocalTrackingClient(MessageType messageType, String topic, Duration messageExpiration,
                               Duration initialPositionLag) {
        this.messageStore = new InMemoryMessageStore(messageType, messageExpiration);
        this.positionStore = new InMemoryPositionStore();
        this.messageType = messageType;
        this.topic = topic;
        this.initialPositionLag = validateInitialPositionLag(initialPositionLag);
    }

    public LocalTrackingClient(MessageStore messageStore, MessageType messageType) {
        this(messageStore, messageType, null);
    }

    public LocalTrackingClient(MessageStore messageStore, MessageType messageType, String topic) {
        this(messageStore, messageType, topic, DEFAULT_INITIAL_POSITION_LAG);
    }

    /**
     * Creates a local tracking client over an existing message store with a configurable initial look-back.
     *
     * @param messageStore        local message store
     * @param messageType         message log type
     * @param topic               optional message topic
     * @param initialPositionLag  duration subtracted from the current time for a new consumer
     */
    public LocalTrackingClient(MessageStore messageStore, MessageType messageType, String topic,
                               Duration initialPositionLag) {
        this.messageStore = messageStore;
        this.messageType = messageType;
        this.topic = topic;
        this.positionStore = new InMemoryPositionStore();
        this.initialPositionLag = validateInitialPositionLag(initialPositionLag);
    }

    private static Duration validateInitialPositionLag(Duration initialPositionLag) {
        if (initialPositionLag == null || initialPositionLag.isNegative()) {
            throw new IllegalArgumentException("initialPositionLag must be non-negative");
        }
        return initialPositionLag;
    }

    @Override
    public Registration registerMonitor(Consumer<List<SerializedMessage>> monitor) {
        return messageStore.registerMonitor(monitor);
    }

    @Override
    public CompletableFuture<Void> append(Guarantee guarantee, SerializedMessage... messages) {
        return messageStore.append(messages);
    }

    @Override
    public CompletableFuture<Void> setRetentionTime(Duration duration, Guarantee guarantee) {
        messageStore.setRetentionTime(duration);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<MessageBatch> read(String trackerId, Long lastIndex,
                                                ConsumerConfiguration config) {
        return getTrackingStrategy().getBatch(
                new WebSocketTracker(readRequest(trackerId, lastIndex, config), messageType, LOCAL_CLIENT_ID, null));
    }

    @Override
    public List<SerializedMessage> readFromIndex(long minIndex, int maxSize) {
        return readFromIndex(minIndex, maxSize, 0L);
    }

    @Override
    public List<SerializedMessage> readFromIndex(long minIndex, int maxSize, long maxBytes) {
        return messageStore.getBatch(minIndex, maxSize, true, maxBytes);
    }

    @Override
    public List<SerializedMessage> readRange(long minIndexInclusive, long maxIndexExclusive, int maxSize) {
        return readRange(minIndexInclusive, maxIndexExclusive, maxSize, 0L);
    }

    @Override
    public List<SerializedMessage> readRange(long minIndexInclusive, long maxIndexExclusive, int maxSize,
                                             long maxBytes) {
        return messageStore.scanBatch(
                minIndexInclusive, maxSize, true, maxBytes,
                message -> message.getIndex() != null && message.getIndex() < maxIndexExclusive).messages();
    }

    @Override
    public CompletableFuture<ClaimSegmentResult> claimSegment(String trackerId, Long lastIndex,
                                                              ConsumerConfiguration config) {
        Read read = readRequest(trackerId, lastIndex, config);
        return getTrackingStrategy().claimSegment(
                        new WebSocketTracker(read, messageType, LOCAL_CLIENT_ID, null))
                .thenApply(claim -> new ClaimSegmentResult(read.getRequestId(), claim.getPosition(),
                                                           claim.getSegment()));
    }

    private Read readRequest(String trackerId, Long lastIndex, ConsumerConfiguration config) {
        return new Read(messageType, config.getName(), trackerId, config.getMaxFetchSize(),
                        config.effectiveMaxFetchBytes(), config.getMaxWaitDuration().toMillis(),
                        config.getTypeFilter(),
                        config.filterMessageTarget(), config.ignoreSegment(),
                        config.singleTracker(), config.clientControlledIndex(),
                        config.isIncludeDocumentTombstones(),
                        lastIndex == null ? -1L : lastIndex,
                        Optional.ofNullable(config.getPurgeDelay()).map(Duration::toMillis).orElse(null));
    }

    @Override
    public CompletableFuture<Void> storePosition(String consumer, int[] segment, long lastIndex, Guarantee guarantee) {
        return positionStore.storePosition(consumer, segment, lastIndex);
    }

    @Override
    public CompletableFuture<Void> resetPosition(String consumer, long lastIndex, Guarantee guarantee) {
        return positionStore.resetPosition(consumer, lastIndex);
    }

    @Override
    public Position getPosition(String consumer) {
        return positionStore.position(consumer);
    }

    @Override
    public CompletableFuture<Void> disconnectTracker(String consumer, String trackerId, boolean sendFinalEmptyBatch,
                                                     Guarantee guarantee) {
        getTrackingStrategy().disconnectTrackers(t -> t.getTrackerId().equalsIgnoreCase(trackerId), sendFinalEmptyBatch);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> truncate(Guarantee guarantee) {
        return getMessageLogMaintenance().truncate();
    }

    @Override
    public void close() {
        messageStore.close();
        getTrackingStrategy().close();
    }
}
