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
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.ClaimSegment;
import io.fluxzero.common.api.tracking.DisconnectTracker;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.fluxzero.common.MessageType.EVENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerFilterRecoveryTest {
    @Test
    void adaptsEveryAttemptToItsActualPeerWithoutChangingExplicitCursors() {
        var owner = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .name("capabilities").runtimeBaseUrl("ws://localhost:1").disableMetrics(true).build());
        try (var client = new WebsocketTrackingClient(URI.create("ws://localhost:1/tracking"), owner, EVENT, null, false)) {
            var legacy = session(Map.of());
            var supported = session(Map.of(WebSocketCapabilities.CONSUMER_FILTER_VERSIONS_HEADER, List.of("1")));
            var unknown = session(Map.of(WebSocketCapabilities.CONSUMER_FILTER_VERSIONS_HEADER, List.of("2")));
            for (Long cursor : java.util.Arrays.asList(null, -20L, 50L)) {
                Read request = new Read(EVENT, "consumer", "tracker", 10, 900_000L, "typeFilter",
                                        true, true, true, true, cursor, 123L);
                request.setConsumerFilter(ConsumerFilter.unrestricted().forRevision("subscription", 1, null, List.of()));
                request.setMaxScanLookBack(60_000L);
                for (var peer : List.of(supported, legacy, supported, unknown)) {
                    Read adapted = (Read) client.adaptRequestForSession(request, peer);
                    assertEquals(request.getRequestId(), adapted.getRequestId());
                    assertEquals(cursor, adapted.getLastIndex());
                    assertEquals(request.getPurgeTimeout(), adapted.getPurgeTimeout());
                    assertEquals(request.getTypeFilter(), adapted.getTypeFilter());
                    assertTrue(adapted.isIgnoreSegment() && adapted.isSingleTracker()
                               && adapted.isClientControlledIndex() && adapted.isFilterMessageTarget());
                    if (peer == supported) {
                        assertSame(request, adapted);
                        assertEquals(900_000, adapted.getMaxTimeout());
                    } else {
                        assertNull(adapted.getConsumerFilter());
                        assertNull(adapted.getMaxScanLookBack());
                        assertEquals(60_000, adapted.getMaxTimeout());
                    }
                }
            }
            Read refresh = new Read(EVENT, "consumer", "tracker", 0, 0L, null,
                                    false, false, false, true, -20L, null);
            refresh.setConsumerFilter(ConsumerFilter.unrestricted());
            assertNull(client.localResponseForSession(refresh, supported));
            assertInstanceOf(ReadResult.class, client.localResponseForSession(refresh, legacy));
            var claim = new ClaimSegment(EVENT, "consumer", "tracker", 900_000L, true, null, false, -20L, null);
            claim.setMaxScanLookBack(60_000L);
            var legacyClaim = assertInstanceOf(ClaimSegment.class, client.adaptRequestForSession(claim, legacy));
            assertEquals(claim.getRequestId(), legacyClaim.getRequestId());
            assertEquals(-20L, legacyClaim.getLastIndex());
            assertEquals(60_000L, legacyClaim.getMaxTimeout());
            assertSame(claim, client.adaptRequestForSession(claim, supported));
            Read explicit = read();
            assertSame(explicit, client.adaptRequestForSession(explicit, legacy));
            assertEquals(900_000, explicit.getMaxTimeout());
        } finally {
            owner.shutDown();
        }
    }

    private static WebsocketSession session(Map<String, List<String>> headers) {
        var session = mock(WebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>());
        when(session.getHandshakeResponseHeaders()).thenReturn(headers);
        return session;
    }

    @Test
    void cancellationDuringDelayedFullRetryReleasesLateOwnershipWithoutWaitingForLongRead() throws Exception {
        var owner = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .name("recovery").runtimeBaseUrl("ws://localhost:1").disableMetrics(true).build());
        try (var client = new DelayedClient(owner);
             var blueprint = new ConsumerBlueprint(new JacksonSerializer(), ConsumerFilter::unrestricted,
                                                    new AtomicLong()::incrementAndGet);
             var executor = Executors.newSingleThreadExecutor()) {
            var configuration = ConsumerConfiguration.builder().name("consumer")
                    .consumerBlueprint(blueprint).maxWaitDuration(Duration.ofMinutes(15)).build();
            client.read("tracker", 1L, configuration).join();
            var logicalRead = client.read("tracker", 2L, configuration);
            var missing = client.reads.getLast();
            assertTrue(missing.getConsumerFilter().referenceOnly());
            var response = executor.submit(() -> client.missing.complete(
                    new ReadResult(missing.getRequestId(), MessageBatch.requireConsumerFilter())));
            assertTrue(client.retryEntered.await(2, TimeUnit.SECONDS));
            var disconnected = client.disconnectTerminatedTracker("consumer", "tracker", Guarantee.STORED);
            assertFalse(disconnected.isDone());
            client.resumeRetry.countDown();
            response.get(2, TimeUnit.SECONDS);
            disconnected.get(2, TimeUnit.SECONDS);
            logicalRead.get(2, TimeUnit.SECONDS);
            assertTrue(client.remoteOwners.isEmpty());
            Read retry = client.reads.getLast();
            assertNotEquals(missing.getRequestId(), retry.getRequestId());
            assertEquals(2L, retry.getLastIndex());
            assertEquals(0L, retry.getMaxTimeout());
            assertFalse(retry.getConsumerFilter().referenceOnly());
        } finally {
            owner.shutDown();
        }
    }

    @Test
    void readObservingPublishedSnapshotCannotSkipItsRegistrationAcknowledgement() throws Exception {
        var helper = new ConsumerFilterUpdates(true, (consumer, filter) -> {});
        var published = new CountDownLatch(1);
        var notify = new CountDownLatch(1);
        var updateSent = new CountDownLatch(1);
        var serverAck = new CompletableFuture<MessageBatch>();
        try (var blueprint = new ConsumerBlueprint(new JacksonSerializer(), ConsumerFilter::unrestricted,
                                                    new AtomicLong()::incrementAndGet);
             var executor = Executors.newSingleThreadExecutor()) {
            blueprint.observe(filter -> {
                published.countDown();
                try {
                    if (!notify.await(2, TimeUnit.SECONDS)) throw new AssertionError("notification was not resumed");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            });
            java.util.function.Function<Read, CompletableFuture<MessageBatch>> send = read -> {
                if (read.getMaxSize() == 0) {
                    updateSent.countDown();
                    return serverAck;
                }
                return CompletableFuture.completedFuture(
                        new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true));
            };
            helper.read(read(), blueprint, send).join();
            var registration = executor.submit(blueprint::refresh);
            assertTrue(published.await(2, TimeUnit.SECONDS));
            helper.read(read(), blueprint, send).join();
            notify.countDown();
            assertTrue(updateSent.await(2, TimeUnit.SECONDS));
            assertFalse(registration.isDone());
            serverAck.complete(new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true));
            registration.get(2, TimeUnit.SECONDS);
        } finally {
            notify.countDown();
            helper.close();
        }
    }

    private static Read read() {
        return new Read(EVENT, "consumer", "tracker", 10, 900_000L, null,
                        false, false, false, false, 0L, null);
    }

    private static final class DelayedClient extends WebsocketTrackingClient {
        final List<Read> reads = new CopyOnWriteArrayList<>();
        final CompletableFuture<ReadResult> missing = new CompletableFuture<>();
        final CountDownLatch retryEntered = new CountDownLatch(1);
        final CountDownLatch resumeRetry = new CountDownLatch(1);
        final Set<String> remoteOwners = ConcurrentHashMap.newKeySet();

        DelayedClient(WebSocketClient client) {
            super(URI.create("ws://localhost:1/tracking"), client, EVENT, null, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <R extends RequestResult> CompletableFuture<R> send(Request request) {
            Read read = (Read) request;
            reads.add(read);
            if (read.getConsumerFilter().referenceOnly()) {
                return (CompletableFuture<R>) missing;
            }
            if (reads.size() > 1) {
                retryEntered.countDown();
                try {
                    if (!resumeRetry.await(2, TimeUnit.SECONDS)) throw new AssertionError("retry was not resumed");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            remoteOwners.add(read.getTrackerId());
            return CompletableFuture.completedFuture((R) new ReadResult(read.getRequestId(),
                    new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true)));
        }

        @Override
        protected CompletableFuture<Void> sendCommand(Command command) {
            remoteOwners.remove(((DisconnectTracker) command).getTrackerId());
            return CompletableFuture.completedFuture(null);
        }
    }
}
