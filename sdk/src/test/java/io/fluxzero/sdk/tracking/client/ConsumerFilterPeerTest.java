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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.ReadResult;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.websocket.WebsocketConnector;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.EVENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsumerFilterPeerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationRefreshesEveryOwningCacheIncludingMixedVersionPeers(boolean secondSupports) throws Exception {
        var owner = owner(2);
        var type = new AtomicReference<>("Before");
        try (var client = new PeerClient(owner, List.of(true, secondSupports));
             var blueprint = blueprint(type);
             var executor = Executors.newSingleThreadExecutor()) {
            var config = ConsumerConfiguration.builder().name("consumer").consumerBlueprint(blueprint).build();
            var first = client.read("first", -20L, config);
            assertNotNull(client.peers.getFirst().reads.poll(2, TimeUnit.SECONDS),
                          () -> first.isCompletedExceptionally() ? first.handle((v, e) -> e).join().toString() : "no read reached the peer");
            client.read("second", 50L, config);
            assertNotNull(client.peers.getLast().reads.poll(2, TimeUnit.SECONDS));
            var supporting = client.peers.getFirst();
            type.set("After");
            var registration = executor.submit(blueprint::refresh);
            Read update = supporting.updates.poll(2, TimeUnit.SECONDS);
            assertNotNull(update);
            if (secondSupports) {
                Read other = client.peers.getLast().updates.poll(2, TimeUnit.SECONDS);
                assertNotNull(other);
                assertNotEquals(update.getRequestId(), other.getRequestId());
                client.peers.getLast().acknowledge(other, empty());
            }
            assertFalse(registration.isDone());
            supporting.acknowledge(update, empty());
            registration.get(2, TimeUnit.SECONDS);
            var message = new SerializedMessage(new Data<>(new byte[0], "After", 0, Data.JSON_FORMAT),
                                                Metadata.empty(), "after", System.currentTimeMillis());
            assertTrue(supporting.filter.get().compile().test(message));
            supporting.acknowledge(supporting.pending.getFirst(),
                    new MessageBatch(new int[]{0, 128}, List.of(message), 1L, Position.newPosition(), false));
            assertEquals("After", first.get(2, TimeUnit.SECONDS).getMessages().getFirst().getType());
            assertEquals(900_000L, supporting.pending.getFirst().getMaxTimeout());
            assertEquals(60_000L, supporting.pending.getFirst().getMaxScanLookBack());
            if (!secondSupports) {
                assertTrue(client.peers.getLast().updates.isEmpty());
                Read legacy = client.peers.getLast().pending.getFirst();
                assertNull(legacy.getConsumerFilter());
                assertEquals(60_000L, legacy.getMaxTimeout());
                assertEquals(50L, legacy.getLastIndex());
            }
        } finally { owner.shutDown(); }
    }

    @Test
    void recoveryConnectingAfterRegistrationUsesTheLatestSnapshot() throws Exception {
        var owner = owner(1);
        var type = new AtomicReference<>("Before");
        try (var client = new PeerClient(owner, List.of(true, true)); var blueprint = blueprint(type)) {
            var config = ConsumerConfiguration.builder().name("consumer").consumerBlueprint(blueprint).build();
            var first = client.read("tracker", -20L, config);
            var original = client.peers.getFirst();
            assertNotNull(original.reads.poll(2, TimeUnit.SECONDS),
                          () -> first.isCompletedExceptionally() ? first.handle((v, e) -> e).join().toString() : "no read reached the peer");
            original.filter.set(null);
            original.closeOnMiss = true;
            client.delayConnection = true;
            var recovering = client.read("tracker", 50L, config);
            assertTrue(client.connecting.await(2, TimeUnit.SECONDS));
            type.set("After");
            blueprint.refresh(); // No active session yet; its later admission must use this snapshot.
            client.resume.countDown();
            recovering.get(2, TimeUnit.SECONDS);
            var replacement = client.peers.getLast();
            Read admitted = replacement.reads.poll(2, TimeUnit.SECONDS);
            assertNotNull(admitted);
            assertEquals(50L, admitted.getLastIndex());
            assertEquals(blueprint.getFilter().revision(), admitted.getConsumerFilter().revision());
            assertEquals("After", admitted.getConsumerFilter().payloadTypes().getFirst().name());
        } finally { owner.shutDown(); }
    }

    private static ConsumerBlueprint blueprint(AtomicReference<String> type) {
        return new ConsumerBlueprint(new JacksonSerializer(), () -> ConsumerFilter.select(null, 0, 1, Data.JSON_FORMAT,
                List.of(new ConsumerFilter.PayloadType(type.get(), null)), null, List.of(), false),
                new AtomicLong()::incrementAndGet);
    }

    private static WebSocketClient owner(int sessions) {
        return WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder().name("peers")
                .runtimeBaseUrl("ws://localhost:1").disableMetrics(true).build()
                .withTrackingConfig(EVENT, WebSocketClient.TrackingClientConfig.builder().sessions(sessions).build()));
    }

    private static MessageBatch empty() {
        return new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true);
    }

    private static final class PeerClient extends WebsocketTrackingClient {
        final List<Peer> peers;
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch connecting = new CountDownLatch(1), resume = new CountDownLatch(1);
        volatile boolean delayConnection;

        PeerClient(WebSocketClient owner, List<Boolean> support) {
            super(URI.create("ws://localhost:1/tracking"), owner, EVENT, null, false);
            peers = support.stream().map(supported -> new Peer(this, supported)).toList();
        }

        @Override
        protected WebsocketSession connectToServer(WebsocketConnector connector, URI uri, WebsocketSession previous)
                throws Exception {
            int next = connections.getAndIncrement();
            if (delayConnection) {
                connecting.countDown();
                if (!resume.await(2, TimeUnit.SECONDS)) throw new AssertionError("connection was not resumed");
            }
            Peer peer = peers.get(next);
            peer.session.getUserProperties().put(NEGOTIATED_SESSION_ID_USER_PROPERTY, "peer-" + next);
            peer.session.getUserProperties().put(SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY, CompressionAlgorithm.NONE);
            peer.session.getUserProperties().put(SELECTED_TRANSPORT_FORMAT_USER_PROPERTY, WebSocketTransportFormat.JSON);
            return peer.session;
        }

        @Override
        public void close() {
            resume.countDown();
            super.close();
        }
    }

    private static final class Peer {
        final WebsocketSession session = mock(WebsocketSession.class);
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicReference<ConsumerFilter> filter = new AtomicReference<>();
        final LinkedBlockingQueue<Read> reads = new LinkedBlockingQueue<>(), updates = new LinkedBlockingQueue<>();
        final List<Read> pending = new CopyOnWriteArrayList<>();
        final PeerClient client;
        volatile boolean closeOnMiss;

        Peer(PeerClient client, boolean supported) {
            this.client = client;
            when(session.isOpen()).thenAnswer(ignored -> open.get());
            when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
            when(session.getRequestURI()).thenReturn(URI.create("ws://localhost:1/tracking"));
            when(session.getHandshakeResponseHeaders()).thenReturn(supported
                    ? Map.of(WebSocketCapabilities.CONSUMER_FILTER_VERSIONS_HEADER, List.of("1")) : Map.of());
            try {
                doAnswer(call -> {
                    ByteBuffer buffer = call.getArgument(0);
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    var object = WebSocketTransportCodecs.json(defaultObjectMapper()).decode(bytes);
                    for (var request : object instanceof RequestBatch<?> batch ? batch.getRequests() : List.of(object)) {
                        accept((Read) request);
                    }
                    return null;
                }).when(session).sendBinary(any());
                when(session.sendBinaryAsync(any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call -> {
                    session.sendBinary(call.getArgument(0));
                    return CompletableFuture.completedFuture(null);
                });
                doAnswer(ignored -> { open.set(false); return null; }).when(session).close();
            } catch (Exception e) { throw new AssertionError(e); }
        }

        private static com.fasterxml.jackson.databind.ObjectMapper defaultObjectMapper() {
            return io.fluxzero.sdk.common.websocket.AbstractWebsocketClient.defaultObjectMapper;
        }

        void accept(Read read) throws Exception {
            var description = read.getConsumerFilter();
            if (description != null && description.referenceOnly() && filter.get() == null) {
                if (closeOnMiss) open.set(false);
                acknowledge(read, MessageBatch.requireConsumerFilter());
            } else {
                if (description != null && !description.referenceOnly()) filter.set(description);
                if (description != null && read.getMaxSize() == 0) {
                    updates.add(read); // Test controls the activation acknowledgement.
                } else {
                    if (read.getMaxTimeout() == 0) acknowledge(read, empty()); else pending.add(read);
                }
            }
            // Observing a read must also observe its cache/pending updates before the test clears that cache.
            if (read.getMaxSize() > 0) reads.add(read);
        }

        void acknowledge(Read read, MessageBatch batch) throws Exception {
            client.onMessage(WebSocketTransportCodecs.json(defaultObjectMapper()).encode(
                    new ReadResult(read.getRequestId(), batch)), session);
        }
    }
}
