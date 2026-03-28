package io.fluxzero.testserver.websocket;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import io.fluxzero.common.api.RequestBatch;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
