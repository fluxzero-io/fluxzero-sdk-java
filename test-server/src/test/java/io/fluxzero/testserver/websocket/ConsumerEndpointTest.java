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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ClaimSegment;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.tracking.ClaimResult;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.Tracker;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsumerEndpointTest {
    private final InMemoryMessageStore messages = new InMemoryMessageStore(COMMAND, null);
    private final InMemoryPositionStore positions = new InMemoryPositionStore();
    private final PausingStrategy strategy = new PausingStrategy();
    private final ConsumerEndpoint endpoint = new ConsumerEndpoint(strategy, messages, positions, COMMAND);
    private final ServerWebsocketSession departed = session("departed", "client-1");
    private final ServerWebsocketSession replacement = session("replacement", "client-2");

    @AfterEach
    void close() {
        strategy.resume.countDown();
        endpoint.onClose(departed, closeReason());
        endpoint.onClose(replacement, closeReason());
        endpoint.shutDown();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void registrationRacingCloseReleasesEverySegment(boolean claim, boolean failAfterRegistration) throws Exception {
        endpoint.onOpen(departed);
        endpoint.onOpen(replacement);
        positions.storePosition("consumer", new int[]{0, MAX_SEGMENT}, 10L).join();
        var retained = List.of(message(11, 0), message(12, MAX_SEGMENT - 1));
        messages.append(retained).join();
        strategy.pause = true;
        strategy.failAfterRegistration = failAfterRegistration;

        try (var worker = Executors.newSingleThreadExecutor()) {
            var registration = worker.submit(() -> register(claim, departed, "departed-tracker"));
            try {
                assertTrue(strategy.entered.await(5, TimeUnit.SECONDS));
                // The read passed endpoint admission, but has not registered any ownership yet.
                endpoint.onClose(departed, closeReason());
            } finally {
                strategy.resume.countDown();
            }
            if (failAfterRegistration) {
                var failure = assertThrows(ExecutionException.class, () -> registration.get(5, TimeUnit.SECONDS));
                assertEquals("registration failed", failure.getCause().getMessage());
            } else {
                registration.get(5, TimeUnit.SECONDS);
            }
        }

        var batch = endpoint.handle(read("replacement-tracker"), replacement).get(5, TimeUnit.SECONDS)
                .getMessageBatch();
        assertArrayEquals(new int[]{0, MAX_SEGMENT}, batch.getSegment());
        assertEquals(retained, batch.getMessages());
        assertEquals(10L, positions.position("consumer").getIndex(0).orElseThrow());
        assertEquals(10L, positions.position("consumer").getIndex(MAX_SEGMENT - 1).orElseThrow());
        assertEquals(retained, messages.getBatch(10L, 10));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedSessionCannotRegisterAgain(boolean claim) {
        endpoint.onOpen(departed);
        endpoint.onClose(departed, closeReason());

        assertTrue(register(claim, departed, "departed-tracker").isCompletedExceptionally());
        assertTrue(strategy.disconnectTrackers(t -> true, false).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closePreservesOtherSessionsOfSameClient(boolean claim) throws Exception {
        ServerWebsocketSession sibling = session("sibling", "client-1");
        endpoint.onOpen(departed);
        endpoint.onOpen(sibling);
        try {
            register(claim, departed, "departed-tracker");
            register(claim, sibling, "sibling-tracker");
            endpoint.onClose(departed, closeReason());
            endpoint.onClose(departed, closeReason());

            var batch = endpoint.handle(read("sibling-tracker"), sibling).get(5, TimeUnit.SECONDS)
                    .getMessageBatch();
            assertArrayEquals(new int[]{0, MAX_SEGMENT}, batch.getSegment());
            var remaining = strategy.disconnectTrackers(t -> true, false);
            assertEquals(1, remaining.size());
            assertEquals("sibling-tracker", remaining.iterator().next().getTrackerId());
        } finally {
            endpoint.onClose(sibling, closeReason());
        }
    }

    @Test
    void closeCancelsRegisteredLongPollWithoutLosingLaterMessages() throws Exception {
        endpoint.onOpen(departed);
        endpoint.onOpen(replacement);
        positions.storePosition("consumer", new int[]{0, MAX_SEGMENT}, 10L).join();
        var waiting = endpoint.handle(new Read(COMMAND, "consumer", "departed-tracker", 10, 30_000, null,
                                               false, false, false, false, 10L, null), departed);
        assertFalse(waiting.isDone());
        endpoint.onClose(departed, closeReason());
        assertTrue(waiting.isCompletedExceptionally());

        var retained = List.of(message(11, 0), message(12, MAX_SEGMENT - 1));
        messages.append(retained).join();
        var recovered = endpoint.handle(read("replacement-tracker"), replacement).get(5, TimeUnit.SECONDS)
                .getMessageBatch();
        assertArrayEquals(new int[]{0, MAX_SEGMENT}, recovered.getSegment());
        assertEquals(retained, recovered.getMessages());
    }

    private CompletableFuture<?> register(boolean claim, ServerWebsocketSession session, String trackerId) {
        return claim ? endpoint.handle(new ClaimSegment(read(trackerId)), session)
                : endpoint.handle(read(trackerId), session);
    }

    private static Read read(String trackerId) {
        return new Read(COMMAND, "consumer", trackerId, 10, 0, null,
                        false, false, false, false, 10L, null);
    }

    private static SerializedMessage message(long index, int segment) {
        var result = new SerializedMessage(new Data<>(new byte[]{1}, "example", 0), Metadata.empty(),
                                           "message-" + index, System.currentTimeMillis());
        result.setIndex(index);
        result.setSegment(segment);
        return result;
    }

    private static ServerWebsocketSession session(String sessionId, String clientId) {
        ServerWebsocketSession session = mock(ServerWebsocketSession.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                Map.of(WebSocketCapabilities.CLIENT_SESSION_ID_HEADER, List.of(sessionId)),
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime-" + sessionId)));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of(clientId), "clientName", List.of("test-client")));
        return session;
    }

    private static WebsocketCloseReason closeReason() {
        return new WebsocketCloseReason(WebsocketCloseReason.NO_STATUS_CODE, "transport lost");
    }

    private class PausingStrategy extends DefaultTrackingStrategy {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        volatile boolean pause;
        volatile boolean failAfterRegistration;

        PausingStrategy() {
            super(messages, positions);
        }

        @Override
        public CompletableFuture<MessageBatch> getBatch(Tracker tracker) {
            beforeRegistration();
            var result = super.getBatch(tracker);
            afterRegistration();
            return result;
        }

        @Override
        public CompletableFuture<ClaimResult> claimSegment(Tracker tracker) {
            beforeRegistration();
            var result = super.claimSegment(tracker);
            afterRegistration();
            return result;
        }

        private void afterRegistration() {
            if (failAfterRegistration) {
                failAfterRegistration = false;
                throw new IllegalStateException("registration failed");
            }
        }

        private void beforeRegistration() {
            if (pause) {
                pause = false;
                entered.countDown();
                try {
                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
