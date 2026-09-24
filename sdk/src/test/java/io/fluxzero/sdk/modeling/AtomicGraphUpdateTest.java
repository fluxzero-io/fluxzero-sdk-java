/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AtomicGraphUpdateTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void checkedReplacementAndConsumeOnce(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new Create("one", 1)).whenExecuting(fc -> {
            Graph<Counter> initial = Fluxzero.loadCurrentGraph("one", Counter.class);
            assertTrue(initial.compareAndSet(new Counter("one", 2)));
            assertFalse(initial.compareAndSet(new Counter("one", 3)));
            Graph<Counter> after = initial.updateAndGet(g -> g.update(c -> new Counter(c.id(), c.value() + 1)));
            assertEquals(3, after.get().value());
            assertTrue(after.revisionStateIndex() > initial.revisionStateIndex());
            Graph<Counter> before = after.getAndUpdate(Graph::delete);
            assertEquals(3, before.get().value());
            assertNull(Fluxzero.loadCurrentGraph("one", Counter.class).get());
            assertThrows(NoSuchElementException.class, () -> after.getAndUpdate(Graph::delete));
        }).expectSuccessfulResult().expectNoErrors();
    }

    @Test void equalReplacementStillChecksStorage() {
        GateClient client = new GateClient();
        try (Fluxzero app = build(client)) {
            write(app, new Create("one", 1));
            app.apply(fc -> {
                var graph = Fluxzero.loadCurrentGraph("one", Counter.class);
                assertTrue(graph.compareAndSet(graph.get()));
                assertFalse(graph.compareAndSet(graph.get()));
                var current = Fluxzero.loadCurrentGraph("one", Counter.class);
                client.beforeCommit.set(() -> write(app, new Set("one", 9)));
                assertFalse(current.compareAndSet(current.get()));
                assertEquals(9, Fluxzero.loadCurrentGraph("one", Counter.class).get().value());
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void explicitRetryBudgetAndSuccessfulBeforeState(int retries) {
        GateClient client = new GateClient();
        try (Fluxzero app = build(client)) {
            write(app, new Create("one", 1));
            app.apply(fc -> {
                var graph = Fluxzero.loadCurrentGraph("one", Counter.class);
                AtomicInteger calls = new AtomicInteger();
                java.util.function.UnaryOperator<Graph<Counter>> update = g -> {
                    calls.incrementAndGet();
                    client.beforeCommit.set(() -> write(app, new Set("one", g.get().value() + 10)));
                    return g.update(c -> new Counter(c.id(), c.value() + 1));
                };
                assertThrows(ModelCommitConflictException.class, () -> graph.getAndUpdate(update, retries));
                assertEquals(1 + retries, calls.get());
                client.beforeCommit.set(() -> write(app, new Set("one", 100)));
                Graph<Counter> before = graph.getAndUpdate(g -> g.update(c -> new Counter(c.id(), c.value() + 1)), 1);
                assertEquals(100, before.get().value());
                assertEquals(101, Fluxzero.loadCurrentGraph("one", Counter.class).get().value());
                return null;
            });
        }
    }

    @Test void functionFailureDoesNotRetryOrCommit() {
        var fixture = TestFixture.create().givenCommands(new Create("one", 1));
        fixture.whenExecuting(fc -> {
            AtomicInteger calls = new AtomicInteger();
            var graph = Fluxzero.loadCurrentGraph("one", Counter.class);
            var failure = new IllegalArgumentException("stop");
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> graph.updateAndGet(g -> {
                calls.incrementAndGet(); throw failure;
            }, 3)));
            assertEquals(1, calls.get());
            assertEquals(1, graph.current().get().value());
        }).expectSuccessfulResult().expectNoErrors();
    }

    @Test void rejectsIdentityChangesForeignGraphsAndNestedCommits() {
        TestFixture.create().givenCommands(new Create("one", 1), new Create("two", 2)).whenExecuting(fc -> {
            var graph = Fluxzero.loadCurrentGraph("one", Counter.class);
            var other = Fluxzero.loadCurrentGraph("two", Counter.class);
            assertThrows(IllegalArgumentException.class, () -> graph.compareAndSet(new Counter("two", 9)));
            assertThrows(IllegalArgumentException.class, () -> graph.updateAndGet(g -> other.update(c -> c)));
            assertThrows(IllegalArgumentException.class, () -> graph.updateAndGet(g -> g.update(c -> new Counter("two", 3))));
            assertThrows(IllegalStateException.class, () -> graph.updateAndGet(g -> {
                g.updateAndGet(x -> x); return g;
            }));
            assertThrows(IllegalStateException.class, () -> graph.updateAndGet(g -> g.update(c -> c).commit()));
            assertThrows(IllegalStateException.class, () -> graph.updateAndGet(g -> g.apply(new Set("one", 3))));
            graph.updateAndGet(g -> {
                assertThrows(IllegalStateException.class, () ->
                        Fluxzero.assertAndApplyAllAsync(java.util.List.of(new Set("two", 9))).join());
                assertThrows(IllegalStateException.class, () -> Fluxzero.assertAndApplyAsync(new Set("two", 9)));
                return g;
            });
            assertEquals(2, other.current().get().value());
            assertThrows(IllegalArgumentException.class, () -> graph.updateAndGet(g -> g, -1));
            assertEquals(1, graph.current().get().value());
        }).expectSuccessfulResult().expectNoErrors();
    }

    @Test void externalEffectFailureRetainsClaimForRecovery() {
        TestFixture.create().givenCommands(new Create("one", 0)).whenExecuting(fc -> {
            var source = Fluxzero.loadCurrentGraph("one", Counter.class);
            java.util.function.UnaryOperator<Graph<Counter>> claim = g -> {
                if (g.get().value() != 0) { throw new IllegalCommandException("already claimed"); }
                return g.update(c -> new Counter(c.id(), 1));
            };
            var claimed = source.updateAndGet(claim);
            // A provider call starts only after this read confirms the claim is durable.
            assertEquals(1, source.current().get().value());
            assertThrows(IllegalCommandException.class, () -> source.updateAndGet(claim));
            // An uncertain external outcome leaves the durable claim untouched; recovery can complete it.
            assertEquals(1, source.current().get().value());
            assertTrue(claimed.compareAndSet(new Counter("one", 2)));
            assertFalse(claimed.compareAndSet(new Counter("one", 2)));
            fc.cache().clear();
            assertEquals(2, source.current().get().value());
        }).expectSuccessfulResult().expectNoErrors();
    }

    @Test void callbackOriginCommitConflictIsNotRetried() {
        TestFixture.create().givenCommands(new Create("one", 1)).whenExecuting(fc -> {
            var error = new ModelCommitConflictException(new io.fluxzero.common.api.modeling.CommitModelsResult(
                    1L, "foreign", java.util.List.of(), java.util.List.of(
                    new io.fluxzero.common.api.modeling.ModelCommitConflict("one", 9L, -1L)), true, false, null));
            AtomicInteger calls = new AtomicInteger();
            assertSame(error, assertThrows(ModelCommitConflictException.class, () ->
                    Fluxzero.loadCurrentGraph("one", Counter.class).updateAndGet(g -> {
                        calls.incrementAndGet(); throw error;
                    }, 3)));
            assertEquals(1, calls.get());
        }).expectSuccessfulResult().expectNoErrors();
    }

    @Test void namespaceOverrideCannotWriteTheApplicationNamespace() {
        var client = LocalClient.newInstance();
        try (Fluxzero app = build(client); Fluxzero customer = build(client.forNamespace("customer"))) {
            write(app, new Create("one", 1));
            write(customer, new Create("one", 10));
            app.apply(fc -> {
                var message = new DeserializingMessage(new Message("trigger"), MessageType.EVENT, fc.serializer())
                        .putContext(ConsumerConfiguration.class, ConsumerConfiguration.builder()
                                .name("customer-events").namespace("customer").build());
                message.run(ignored -> {
                    var graph = Fluxzero.loadCurrentGraph("one", Counter.class);
                    assertEquals(10, graph.get().value());
                    assertThrows(UnsupportedOperationException.class, () -> graph.updateAndGet(g -> g));
                });
                assertEquals(1, Fluxzero.loadCurrentGraph("one", Counter.class).get().value());
                return null;
            });
            customer.apply(fc -> {
                var graph = Fluxzero.loadCurrentGraph("one", Counter.class);
                assertEquals(10, graph.get().value());
                assertTrue(graph.compareAndSet(new Counter("one", 11)));
                fc.cache().clear();
                assertEquals(11, Fluxzero.loadCurrentGraph("one", Counter.class).get().value());
                return null;
            });
        }
    }

    static Fluxzero build(io.fluxzero.sdk.configuration.client.Client client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
    }
    static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }
    static class GateClient extends LocalClient {
        final AtomicReference<Runnable> beforeCommit = new AtomicReference<>();
        GateClient() { super(null); }
        @Override protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commitModels")) {
                            Runnable gate = beforeCommit.getAndSet(null);
                            if (gate != null) { gate.run(); }
                        }
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        }
    }
    @Model(conflictPolicy = ModelConflictPolicy.ACCEPT)
    record Counter(@EntityId String id, int value) {}
    record Create(String id, int value) { @Apply Counter apply() { return new Counter(id, value); } }
    record Set(String id, int value) {
        @Apply Counter apply(Counter before) { return new Counter(id, value); }
    }
}
