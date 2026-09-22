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

package io.fluxzero.sdk.modeling;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerHandlingMode;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ProvisionalModelFailureTest {
    @AfterEach void cleanup() { TestFixture.shutDownActiveFixtures(); }

    @org.junit.jupiter.api.Test
    void provisionalRejectionKeepsItsOrderingPositionForAFollowingMutation() {
        var client = new PausingClient();
        var producerEvaluated = new CountDownLatch(1);
        var followerEvaluated = new CountDownLatch(1);
        var thirdEvaluated = new CountDownLatch(1);
        var producer = new Add("stock", 4);
        var follower = new Add("stock", 1);
        var third = new Remove("stock", 2);
        TestFixture.createAsync(DefaultFluxzero.builder()
                        .configureDefaultConsumer(COMMAND, c -> c.toBuilder().handlingMode(ConsumerHandlingMode.ASYNC).build())
                        .addDispatchInterceptor(new DispatchInterceptor() {
                            @Override public Message interceptDispatch(Message m, MessageType t, String topic) { return m; }
                            @Override public SerializedMessage modifySerializedMessage(SerializedMessage s, Message m,
                                                                                       MessageType t, String topic) {
                                s.setSegment(0);
                                return s;
                            }
                        }, COMMAND)
                        .addHandlerInterceptor((next, invoker) -> message -> {
                            if (message.getPayload().equals(follower)) await(producerEvaluated);
                            if (message.getPayload().equals(third)) await(followerEvaluated);
                            Object result = next.apply(message);
                            if (message.getPayload().equals(producer)) producerEvaluated.countDown();
                            if (message.getPayload().equals(follower)) followerEvaluated.countDown();
                            if (message.getPayload().equals(third)) thirdEvaluated.countDown();
                            return result;
                        }, COMMAND), client, SetStock.class, Add.class, Remove.class)
                .givenCommands(new SetStock("stock", 2))
                .whenExecuting(f -> {
                    var competitor = DefaultFluxzero.builder().disableAutomaticTracking().build(client);
                    try {
                        client.armed.set(true);
                        var results = Fluxzero.sendCommands(producer, follower, third);
                        try {
                            await(thirdEvaluated);
                            competitor.apply(fc -> {
                                Fluxzero.assertAndApply(new Add("stock", 3));
                                return null;
                            });
                            client.pauseFollower.set(true);
                            client.release.complete(null);
                            assertThrows(java.util.concurrent.ExecutionException.class,
                                    () -> results.getFirst().get(10, TimeUnit.SECONDS));
                            await(client.followerPrepared);
                            assertFalse(results.get(1).isDone(), "The recovered second command is paused before commit");
                            assertThrows(java.util.concurrent.TimeoutException.class,
                                    () -> results.get(2).get(2, TimeUnit.SECONDS),
                                    "The third command must await the recovered second command's durability");
                        } finally {
                            client.release.complete(null);
                            client.releaseFollower.complete(null);
                        }
                        results.get(1).get(10, TimeUnit.SECONDS);
                        results.get(2).get(10, TimeUnit.SECONDS);
                        assertEquals(4, Fluxzero.loadModel("stock", Stock.class).get().used());
                    } finally {
                        client.release.complete(null);
                        client.releaseFollower.complete(null);
                        competitor.close();
                    }
                }).expectSuccessfulResult();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedProducerDoesNotRejectAnIndependentMutation(boolean initiallyRejected) {
        var client = new PausingClient();
        var producerEvaluated = new CountDownLatch(1);
        var followerEvaluated = new CountDownLatch(1);
        var producer = new Add("stock", 4);
        Object follower = initiallyRejected ? new Add("stock", 1) : new Remove("stock", 2);
        TestFixture.createAsync(DefaultFluxzero.builder()
                        .configureDefaultConsumer(COMMAND, c -> c.toBuilder().handlingMode(ConsumerHandlingMode.ASYNC).build())
                        .addDispatchInterceptor(new DispatchInterceptor() {
                            @Override public Message interceptDispatch(Message m, MessageType t, String topic) { return m; }
                            @Override public SerializedMessage modifySerializedMessage(SerializedMessage s, Message m,
                                                                                       MessageType t, String topic) {
                                s.setSegment(0); // Deliberately exercise shared segment and batch visibility.
                                return s;
                            }
                        }, COMMAND)
                        .addHandlerInterceptor((next, invoker) -> message -> {
                            if (message.getPayload().equals(follower)) await(producerEvaluated);
                            Object result = next.apply(message);
                            if (message.getPayload().equals(producer)) producerEvaluated.countDown();
                            if (message.getPayload().equals(follower)) followerEvaluated.countDown();
                            return result;
                        }, COMMAND), client, SetStock.class, Add.class, Remove.class)
                .givenCommands(new SetStock("stock", 2))
                .whenExecuting(f -> {
                    var competitor = DefaultFluxzero.builder().disableAutomaticTracking().build(client);
                    try {
                        client.armed.set(true);
                        var results = Fluxzero.sendCommands(producer, follower);
                        try {
                            await(producerEvaluated);
                            await(followerEvaluated);
                            assertFalse(client.armed.get(), "The producer reached the paused commit");
                            assertFalse(results.getFirst().isDone(), "The producer is still provisional");
                            competitor.apply(fc -> {
                                Fluxzero.assertAndApply(new Add("stock", initiallyRejected ? 3 : 4));
                                assertEquals(initiallyRejected ? 5 : 6, Fluxzero.loadModel("stock", Stock.class).get().used());
                                return null;
                            });
                            client.pauseFollower.set(initiallyRejected);
                        } finally { client.release.complete(null); }
                        var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> results.getFirst().get(10, TimeUnit.SECONDS));
                        assertInstanceOf(IllegalCommandException.class, rejected.getCause());
                        assertEquals("Capacity exceeded", rejected.getCause().getMessage());
                        if (initiallyRejected) {
                            // The recovered command must retain its coordinator when storage also forces a retry.
                            await(client.followerPrepared);
                            competitor.apply(fc -> {
                                Fluxzero.assertAndApply(new Remove("stock", 1));
                                return null;
                            });
                            client.releaseFollower.complete(null);
                        }
                        results.getLast().get(10, TimeUnit.SECONDS);
                        assertEquals(initiallyRejected ? 5 : 4, Fluxzero.loadModel("stock", Stock.class).get().used());
                    } finally {
                        client.release.complete(null);
                        client.releaseFollower.complete(null);
                        competitor.close();
                    }
                }).expectSuccessfulResult();
    }

    @Model record Stock(@EntityId String stockId, int used) {}
    record SetStock(String stockId, int used) {
        @Apply Stock apply() { return new Stock(stockId, used); }
    }
    @io.fluxzero.sdk.tracking.Consumer(name = "stock", handlingMode = ConsumerHandlingMode.ASYNC)
    record Add(@RoutingKey String stockId, int quantity) {
        @AssertLegal void validate(Stock stock) {
            if (stock.used() + quantity > 6) throw new IllegalCommandException("Capacity exceeded");
        }
        @Apply Stock apply(Stock stock) { return new Stock(stockId, stock.used() + quantity); }
    }
    @io.fluxzero.sdk.tracking.Consumer(name = "stock", handlingMode = ConsumerHandlingMode.ASYNC)
    record Remove(@RoutingKey String stockId, int quantity) {
        @Apply Stock apply(Stock stock) { return new Stock(stockId, stock.used() - quantity); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Expected evaluation reached its checkpoint"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    /** Delay transport only; all assertions, state changes and conflicts use the actual SDK. */
    private static class PausingClient extends LocalClient {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicBoolean pauseFollower = new AtomicBoolean();
        final CountDownLatch followerPrepared = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final CompletableFuture<Void> releaseFollower = new CompletableFuture<>();
        PausingClient() { super(Duration.ofHours(1)); }
        @Override protected EventStoreClient createEventStoreClient() {
            var delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{EventStoreClient.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("commitModels") && args[0] instanceof CommitModels commit
                                && commit.getSubsteps().stream().anyMatch(s -> s.getEvent().getData().getType().endsWith("$Add"))) {
                            CompletableFuture<Void> gate = null;
                            if (armed.compareAndSet(true, false)) gate = release;
                            else if (pauseFollower.compareAndSet(true, false)) {
                                followerPrepared.countDown();
                                gate = releaseFollower;
                            }
                            if (gate != null) return gate.thenCompose(ignored -> {
                                    try { return (CompletableFuture<?>) method.invoke(delegate, args); }
                                    catch (ReflectiveOperationException e) { return CompletableFuture.failedFuture(e); }
                                });
                        }
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        }
    }
}
