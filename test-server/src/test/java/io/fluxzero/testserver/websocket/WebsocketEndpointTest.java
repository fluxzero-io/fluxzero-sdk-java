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
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.common.websocket.WebSocketCapabilities;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsocketEndpointTest {

    @Test
    void capabilityHeaderTakesPrecedenceOverLegacyCompressionParameter() {
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)))));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.GZIP, new TestEndpoint().getCompressionAlgorithm(session));
    }

    @Test
    void selectedCompressionAlgorithmTakesPrecedenceOverSupportedList() {
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.HANDSHAKE_HEADERS_USER_PROPERTY,
                WebSocketCapabilities.asHeaders(List.of(CompressionAlgorithm.GZIP, CompressionAlgorithm.LZ4)),
                WebsocketDeploymentUtils.SELECTED_COMPRESSION_ALGORITHM_USER_PROPERTY,
                CompressionAlgorithm.LZ4)));
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("GZIP"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithm(session));
    }

    @Test
    void legacyCompressionParameterRemainsFallbackWhenNoCapabilitiesAreSent() {
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("compression", List.of("LZ4"), "clientId", List.of("client"), "clientName", List.of("test-client")));

        assertEquals(CompressionAlgorithm.LZ4, new TestEndpoint().getCompressionAlgorithm(session));
    }

    @Test
    void requestBatchWithSameRoutingKeyIsHandledInOrder() throws Exception {
        CountDownLatch secondHandled = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<String> handled = new CopyOnWriteArrayList<>();
            TestEndpoint endpoint = new TestEndpoint(executor, handled, secondHandled, allowFirstToFinish);
            Session session = mock(Session.class);
            prepareOpenSession(session);
            endpoint.openSessionForTest(session);

            executor.submit(() -> endpoint.dispatchRequest(session, new RequestBatch<>(List.of(new FirstCommand(),
                                                                                               new SecondCommand()))));

            assertFalseWithMessage(secondHandled.await(200, TimeUnit.MILLISECONDS),
                                   "Second request should not overtake the first within a batch");
            allowFirstToFinish.countDown();
            assertEventually(() -> assertEquals(List.of("first", "second"), handled));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requestsWithSameRoutingKeyAreHandledInOrder() throws Exception {
        CountDownLatch secondHandled = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<String> handled = new CopyOnWriteArrayList<>();
            TestEndpoint endpoint = new TestEndpoint(executor, handled, secondHandled, allowFirstToFinish);
            Session session = mock(Session.class);
            prepareOpenSession(session);
            endpoint.openSessionForTest(session);

            endpoint.submitRequestTask(session, new FirstCommand(), () -> {
                try {
                    assertTrue(allowFirstToFinish.await(1, TimeUnit.SECONDS),
                               "First task did not get released in time");
                    handled.add("first");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            endpoint.submitRequestTask(session, new SecondCommand(), () -> {
                handled.add("second");
                secondHandled.countDown();
            });

            assertFalseWithMessage(secondHandled.await(200, TimeUnit.MILLISECONDS),
                                   "Second request should not overtake the first within a routing key");
            allowFirstToFinish.countDown();
            assertEventually(() -> assertEquals(List.of("first", "second"), handled));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requestsWithDifferentRoutingKeysMayRunIndependently() throws Exception {
        CountDownLatch secondHandled = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<String> handled = new CopyOnWriteArrayList<>();
            TestEndpoint endpoint = new TestEndpoint(executor, handled, secondHandled, allowFirstToFinish);
            Session session = mock(Session.class);
            prepareOpenSession(session);
            endpoint.openSessionForTest(session);

            endpoint.submitRequestTask(session, new FirstCommand(), () -> {
                try {
                    assertTrue(allowFirstToFinish.await(1, TimeUnit.SECONDS),
                               "First task did not get released in time");
                    handled.add("first");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            endpoint.submitRequestTask(session, new OtherKeyCommand(), () -> {
                handled.add("second");
                secondHandled.countDown();
            });

            assertTrue(secondHandled.await(200, TimeUnit.MILLISECONDS),
                       "Request with different routing key should not be blocked by the first");
            allowFirstToFinish.countDown();
            assertEventually(() -> assertEquals(List.of("second", "first"), handled));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requestForClosedSessionIsIgnored() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            TestEndpoint endpoint = new TestEndpoint(executor, new CopyOnWriteArrayList<>(), new CountDownLatch(1),
                                                     new CountDownLatch(0));
            Session session = mock(Session.class);
            prepareOpenSession(session);
            AtomicBoolean invoked = new AtomicBoolean();

            endpoint.openSessionForTest(session);
            endpoint.onClose(session, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "test"));
            endpoint.submitRequestTask(session, null, () -> invoked.set(true));

            Thread.sleep(50);
            assertTrue(!invoked.get(), "Task for a closed session should be ignored");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertEventually(ThrowingRunnable assertion) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        AssertionError lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                lastError = e;
                Thread.sleep(10);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    private static void assertFalseWithMessage(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void prepareOpenSession(Session session) {
        when(session.getId()).thenReturn("session");
        when(session.getRequestParameterMap()).thenReturn(
                Map.of("clientId", List.of("client"), "clientName", List.of("test-client")));
        when(session.getUserProperties()).thenReturn(new ConcurrentHashMap<>(Map.of(
                WebsocketDeploymentUtils.RUNTIME_SESSION_ID_USER_PROPERTY, "runtime-session")));
        doNothing().when(session).addMessageHandler(any(Class.class), any(MessageHandler.Whole.class));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class TestEndpoint extends WebsocketEndpoint {
        private final List<String> handled;
        private final CountDownLatch secondHandled;
        private final CountDownLatch allowFirstToFinish;

        TestEndpoint() {
            this(Executors.newSingleThreadExecutor(), new CopyOnWriteArrayList<>(), new CountDownLatch(0),
                 new CountDownLatch(0));
        }

        private TestEndpoint(ExecutorService executor, List<String> handled, CountDownLatch secondHandled,
                             CountDownLatch allowFirstToFinish) {
            super(executor);
            this.handled = handled;
            this.secondHandled = secondHandled;
            this.allowFirstToFinish = allowFirstToFinish;
        }

        @Handle
        void handle(FirstCommand command) throws Exception {
            assertTrue(allowFirstToFinish.await(1, TimeUnit.SECONDS), "First command did not get released in time");
            handled.add("first");
        }

        @Handle
        void handle(SecondCommand command) {
            handled.add("second");
            secondHandled.countDown();
        }

        @Override
        protected CompressionAlgorithm getCompressionAlgorithm(Session session) {
            return super.getCompressionAlgorithm(session);
        }

        void openSessionForTest(Session session) {
            onOpen(session, mock(EndpointConfig.class));
        }
    }

    @Value
    private static class FirstCommand extends Command {
        @Override
        public Guarantee getGuarantee() {
            return Guarantee.NONE;
        }

        @Override
        public String routingKey() {
            return "key";
        }
    }

    @Value
    private static class SecondCommand extends Command {
        @Override
        public Guarantee getGuarantee() {
            return Guarantee.NONE;
        }

        @Override
        public String routingKey() {
            return "key";
        }
    }

    @Value
    private static class OtherKeyCommand extends Command {
        @Override
        public Guarantee getGuarantee() {
            return Guarantee.NONE;
        }

        @Override
        public String routingKey() {
            return "other";
        }
    }
}
