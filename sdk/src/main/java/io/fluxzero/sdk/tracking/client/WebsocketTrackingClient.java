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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ClaimSegment;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.DisconnectTracker;
import io.fluxzero.common.api.tracking.GetPosition;
import io.fluxzero.common.api.tracking.GetPositionResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadFromIndex;
import io.fluxzero.common.api.tracking.ReadFromIndexResult;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.api.tracking.ResetPosition;
import io.fluxzero.common.api.tracking.StorePosition;
import io.fluxzero.common.api.tracking.TrackingWebSocketCodec;
import io.fluxzero.common.websocket.WebSocketPayloadCodec;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import lombok.Getter;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.sdk.common.ClientUtils.waitForResults;

/**
 * WebSocket-based implementation of the {@link TrackingClient} that connects to the Fluxzero Runtime.
 * <p>
 * All tracking-related operations—including reading messages, claiming segments, storing/resetting positions, and
 * disconnecting trackers—are performed via a standardized WebSocket protocol with the Fluxzero Runtime.
 * <p>
 * This is the default production implementation used in deployed applications to track message streams such as
 * commands, queries, events, and other custom message types.
 * <p>
 * Requires an active WebSocket connection to the Fluxzero Runtime’s tracking service.
 *
 * @see WebSocketClient
 */
@Getter
public class WebsocketTrackingClient extends AbstractWebsocketClient implements TrackingClient {

    private static final String FILTERING_SUPPORTED = WebsocketTrackingClient.class.getName() + ".filteringSupported";

    private final MessageType messageType;
    private final String topic;
    private final ConsumerFilterUpdates consumerFilters = new ConsumerFilterUpdates(true, this::refreshPendingReadFilters);
    private final Metadata metricsMetadata;
    @Getter(lombok.AccessLevel.NONE)
    private final Set<CompletableFuture<Void>> pendingDisconnects = ConcurrentHashMap.newKeySet();
    @Getter(lombok.AccessLevel.NONE)
    private final Set<PendingRead> pendingReads = ConcurrentHashMap.newKeySet();

    @Override
    protected List<? extends WebSocketPayloadCodec> payloadCodecs() {
        return List.of(TrackingWebSocketCodec.INSTANCE);
    }

    public WebsocketTrackingClient(String endPointUrl, WebSocketClient client, MessageType type, String topic) {
        this(URI.create(endPointUrl), client, type, topic, type != METRICS);
    }

    public WebsocketTrackingClient(URI endPointUri, WebSocketClient client, MessageType type, String topic, boolean sendMetrics) {
        super(endPointUri, client, sendMetrics, client.getClientConfig().getTrackingConfigs().get(type).getSessions());
        this.messageType = type;
        this.topic = topic;
        this.metricsMetadata = Metadata.of("messageType", type).with("topic", topic);
    }

    @Override
    public boolean supportsConsumerFiltering() {
        return sessionsForCapabilities().stream().anyMatch(WebsocketTrackingClient::supportsFiltering);
    }

    @Override
    protected Request adaptRequestForSession(Request request, WebsocketSession session) {
        if (request instanceof Read read) {
            // Selection can wait for connection establishment while registration advances the subscription.
            ConsumerFilter current = consumerFilters.current(read);
            if (current != null && read.getConsumerFilter().revision() < current.revision()) {
                read.setConsumerFilter(current);
            }
            return supportsFiltering(session) ? read : read.forLegacyPeer();
        }
        return request;
    }

    @Override
    protected RequestResult localResponseForSession(Request request, WebsocketSession session) {
        // Older servers interpret maxSize=0 as a normal read, so a metadata refresh must stay local.
        return request instanceof Read read && read.getMaxSize() == 0 && read.getConsumerFilter() != null
               && !supportsFiltering(session)
                ? new ReadResult(read.getRequestId(), new MessageBatch(new int[]{0, 0}, List.of(), null,
                                                                      Position.newPosition(), false)) : null;
    }

    private static boolean supportsFiltering(WebsocketSession session) {
        return (boolean) session.getUserProperties().computeIfAbsent(FILTERING_SUPPORTED,
                ignored -> WebSocketCapabilities.supportsConsumerFiltering(session.getHandshakeResponseHeaders()));
    }

    @Override
    public CompletableFuture<MessageBatch> read(String trackerId, Long lastIndex,
                                                ConsumerConfiguration configuration) {
        Read read = new Read(messageType,
                        configuration.getName(), trackerId, configuration.getMaxFetchSize(),
                        configuration.effectiveMaxFetchBytes(), configuration.getMaxWaitDuration().toMillis(),
                configuration.getTypeFilter(),
                configuration.filterMessageTarget(), configuration.ignoreSegment(),
                configuration.singleTracker(), configuration.clientControlledIndex(),
                configuration.isIncludeDocumentTombstones(), lastIndex,
                Optional.ofNullable(configuration.getPurgeDelay()).map(Duration::toMillis).orElse(null));
        read.setMaxScanLookBack(configuration.getMaxScanLookBack());
        return consumerFilters.read(read, configuration.getConsumerBlueprint(), this::sendRead);
    }

    private void refreshPendingReadFilters(String consumer, ConsumerFilter filter) {
        pendingResponses(request -> {
            if (request instanceof Read read && consumer.equals(read.getConsumer())
                && read.getConsumerFilter() != null
                && Objects.equals(filter.subscriptionId(), read.getConsumerFilter().subscriptionId())
                && filter.revision() > read.getConsumerFilter().revision()) {
                read.setConsumerFilter(filter);
            }
            return false;
        });
    }

    private CompletableFuture<MessageBatch> sendRead(Read read) {
        if (read.getMaxSize() == 0 && read.getConsumerFilter() != null) {
            // Each Runtime node owns its own cache. Refresh every peer that may still have an open read.
            return this.<ReadResult>sendToAllSessions(() -> {
                Read update = new Read(read.getMessageType(), read.getConsumer(), read.getTrackerId(), 0,
                        0L, 0L, read.getTypeFilter(), read.isFilterMessageTarget(), read.isIgnoreSegment(),
                        read.isSingleTracker(), read.isClientControlledIndex(), read.isIncludeDocumentTombstones(),
                        read.getLastIndex(), read.getPurgeTimeout());
                update.setConsumerFilter(read.getConsumerFilter());
                return update;
            }).thenApply(ignored -> new MessageBatch(new int[]{0, 0}, List.of(), null, Position.newPosition(), false));
        }
        if (read.getConsumerFilter() == null) {
            return this.<ReadResult>send(read).thenApply(ReadResult::getMessageBatch);
        }
        CompletableFuture<MessageBatch> result = new CompletableFuture<>();
        PendingRead pending = new PendingRead(read.getConsumer(), read.getTrackerId(), result);
        pendingReads.add(pending);
        try {
            sendReadWithRecovery(read).whenComplete((batch, failure) -> {
                try {
                    if (failure == null) {
                        result.complete(batch);
                    } else {
                        result.completeExceptionally(failure);
                    }
                } finally {
                    pendingReads.remove(pending);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            pendingReads.remove(pending);
        }
        return result;
    }

    private CompletableFuture<MessageBatch> sendReadWithRecovery(Read read) {
        return this.<ReadResult>send(read).thenCompose(result -> {
            MessageBatch batch = result.getMessageBatch();
            if (!batch.isConsumerFilterRequired()) {
                return CompletableFuture.completedFuture(batch);
            }
            var filter = consumerFilters.current(read);
            if (filter == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Missing full consumer filter snapshot"));
            }
            // Recovery must acknowledge admission promptly, including when cancellation preceded this retry.
            Read retry = new Read(read.getMessageType(), read.getConsumer(), read.getTrackerId(), read.getMaxSize(),
                    read.getMaxBytes(), 0L, read.getTypeFilter(), read.isFilterMessageTarget(),
                    read.isIgnoreSegment(), read.isSingleTracker(), read.isClientControlledIndex(),
                    read.isIncludeDocumentTombstones(), read.getLastIndex(), read.getPurgeTimeout());
            retry.setConsumerFilter(filter);
            retry.setMaxScanLookBack(read.getMaxScanLookBack());
            return this.<ReadResult>send(retry).thenApply(response -> {
                if (response.getMessageBatch().isConsumerFilterRequired()) {
                    throw new IllegalStateException("Server did not accept a full consumer filter snapshot");
                }
                return response.getMessageBatch();
            });
        });
    }


    /**
     * Requests a tracker segment using this client's message type and the consumer's filtering, waiting, and
     * index-control settings; returns the asynchronous claim result.
     */
    public CompletableFuture<ClaimSegmentResult> claimSegment(String trackerId, Long lastIndex,
                                                              ConsumerConfiguration config) {
        ClaimSegment claim = new ClaimSegment(messageType,
                config.getName(), trackerId, config.getMaxWaitDuration().toMillis(), config.clientControlledIndex(),
                config.getTypeFilter(), config.filterMessageTarget(), lastIndex,
                Optional.ofNullable(config.getPurgeDelay()).map(Duration::toMillis).orElse(null));
        claim.setMaxScanLookBack(config.getMaxScanLookBack());
        return send(claim);
    }

    @Override
    public List<SerializedMessage> readFromIndex(long minIndex, int maxSize) {
        return readFromIndex(minIndex, maxSize, 0L);
    }

    @Override
    public List<SerializedMessage> readFromIndex(long minIndex, int maxSize, long maxBytes) {
        ReadFromIndexResult result = sendAndWait(new ReadFromIndex(minIndex, maxSize, maxBytes));
        return result.getMessages();
    }

    @Override
    public CompletableFuture<Void> storePosition(String consumer, int[] segment, long lastIndex, Guarantee guarantee) {
        return sendCommand(new StorePosition(messageType, consumer, segment, lastIndex, resolveGuarantee(guarantee), topic));
    }

    @Override
    public CompletableFuture<Void> resetPosition(String consumer, long lastIndex, Guarantee guarantee) {
        return sendCommand(new ResetPosition(messageType, consumer, lastIndex, resolveGuarantee(guarantee)));
    }

    @Override
    public Position getPosition(String consumer) {
        return this.<GetPositionResult>sendAndWait(new GetPosition(messageType, consumer)).getPosition();
    }

    @Override
    public CompletableFuture<Void> disconnectTracker(String consumer, String trackerId, boolean sendFinalEmptyBatch, Guarantee guarantee) {
        consumerFilters.disconnect(consumer, trackerId);
        return sendCommand(new DisconnectTracker(messageType, consumer, trackerId, sendFinalEmptyBatch, resolveGuarantee(guarantee)));
    }

    @Override
    public CompletableFuture<Void> disconnectTerminatedTracker(String consumer, String trackerId, Guarantee guarantee) {
        List<CompletableFuture<?>> pending = new ArrayList<>(pendingResponses(request -> request instanceof Read read
                && consumer.equals(read.getConsumer()) && trackerId.equals(read.getTrackerId())));
        pendingReads.stream().filter(read -> consumer.equals(read.consumer()) && trackerId.equals(read.tracker()))
                .map(PendingRead::result).forEach(pending::add);
        // An interrupted readAndWait still has a remote request. A delayed read can arrive after the first
        // disconnect; release once more after its response proves that it has finished registering.
        var first = disconnectTracker(consumer, trackerId, !pending.isEmpty(), guarantee);
        var result = pending.isEmpty() ? first : CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> disconnectTracker(consumer, trackerId, false, Guarantee.STORED));
        if (!result.isDone()) {
            pendingDisconnects.add(result);
            result.whenComplete((ignored, failure) -> pendingDisconnects.remove(result));
        }
        return result;
    }

    @Override
    protected Metadata metricsMetadata() {
        return metricsMetadata;
    }

    @Override
    public void close() {
        consumerFilters.close();
        try {
            if (canAwaitTrackerShutdown()) {
                waitForResults(Duration.ofSeconds(2), List.copyOf(pendingDisconnects));
            }
        } finally {
            try {
                close(true);
            } finally {
                pendingDisconnects.clear();
            }
        }
    }

    boolean canAwaitTrackerShutdown() {
        return canAwaitShutdownResults();
    }

    private record PendingRead(String consumer, String tracker, CompletableFuture<MessageBatch> result) {}

    @Override
    public String toString() {
        return "%s-%s%s".formatted(super.toString(), messageType, topic == null ? "" : "_" + topic);
    }
}
