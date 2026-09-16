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

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.Association;
import jakarta.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ModelAssertionScopeTest {
    private static final AtomicInteger checks = new AtomicInteger();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingAssertionBindingFailsInsteadOfDisappearing(boolean targeted) {
        TestFixture.create().whenExecuting(fc -> execute(fc, new UnboundReserve("reservation"), targeted, "reservation"))
                .expectExceptionalResult(IllegalStateException.class).expectNoEvents()
                .expectThat(fc -> assertNull(Fluxzero.loadModel("reservation", Reservation.class).get()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void payloadChecksDoNotWidenTheExplicitWriteScope(boolean after) {
        Object command = new ReserveAndDisable("reservation", "product", after);
        checks.set(0);
        TestFixture.create().givenCommands(new SetProduct("product", true))
                .whenExecuting(fc -> execute(fc, command, true, "reservation"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(1, checks.get());
                    assertTrue(Fluxzero.loadModel("product", Product.class).get().active());
                    assertNotNull(Fluxzero.loadModel("reservation", Reservation.class).get());
                });
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT,absent,1", "DEFAULT,existing,1", "DEFAULT,deleted,1",
            "RETRY,absent,1", "RETRY,existing,1", "RETRY,deleted,1",
            "DEFAULT,absent,2", "DEFAULT,existing,2", "DEFAULT,deleted,2",
            "RETRY,absent,2", "RETRY,existing,2", "RETRY,deleted,2"})
    void stagedGraphRetriesPreserveOriginalPresence(String policy, String origin, int updates) {
        GateClient client = new GateClient();
        try (Fluxzero app = application(client, policy)) {
            execute(app, new SetProduct("product", true), false, null);
            if (!origin.equals("absent")) {
                execute(app, new SeedReservation("reservation", "product", 10), false, null);
                if (origin.equals("deleted")) {
                    app.apply(fc -> Fluxzero.loadGraph("reservation", Reservation.class).delete().commit());
                }
            }
            AtomicInteger transformations = new AtomicInteger();
            Graph<Reservation> staged = app.apply(fc -> {
                Graph<Reservation> graph = Fluxzero.loadGraph("reservation", Reservation.class);
                for (int i = 0; i < updates; i++) {
                    graph = graph.update(value -> {
                        transformations.incrementAndGet();
                        return new Reservation("reservation", "product", value == null ? 1 : value.value() + 1);
                    });
                }
                return graph;
            });
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    execute(app, new SetReservation("reservation", "product", 42), false, null);
                }
            };
            if (origin.equals("existing")) {
                app.apply(fc -> staged.commit());
                assertEquals(2 * updates, transformations.get());
            } else {
                RuntimeException failure = assertThrows(RuntimeException.class, () -> app.apply(fc -> staged.commit()));
                assertInstanceOf(ModelCommitConflictException.class, rootCause(failure));
                assertEquals(updates, transformations.get(), "An absent-target update must not become an upsert on retry");
            }
            assertEquals(origin.equals("existing") ? 42 + updates : 42,
                         app.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get().value()).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "RETRY"})
    void stagedGraphRetryReevaluatesAllTargetsAtOneBoundary(String policy) {
        GateClient client = new GateClient();
        try (Fluxzero app = application(client, policy)) {
            execute(app, new SetProduct("product", true), false, null);
            execute(app, new SeedReservation("reservation", "product", 10), false, null);
            Graph<Product> staged = app.apply(fc -> Fluxzero.loadGraph("reservation", Reservation.class)
                    .update(value -> new Reservation(value.reservationId(), value.productId(), value.value() + 1))
                    .parent(Product.class).orElseThrow()
                    .update(value -> new Product(value.productId(), value.active(), value.revision() + 1)));
            assertEquals(2, Graphs.stagedChanges(staged).size());
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    execute(app, new SetReservation("reservation", "product", 42), false, null);
                    execute(app, new SetProduct("product", false), false, null);
                }
            };
            app.apply(fc -> staged.commit());
            assertTrue(once.get());
            assertEquals(43, app.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get().value()).intValue());
            assertEquals(new Product("product", false, 2),
                         app.apply(fc -> Fluxzero.loadModel("product", Product.class).get()));
        }
    }

    @ParameterizedTest
    @CsvSource({"graph,false", "graph,true", "value,false", "value,true", "after,false", "after,true",
            "nested,false", "nested,true"})
    void commandAssertionsProtectBothPublicRoutes(String assertion, boolean targeted) {
        for (boolean async : List.of(false, true)) {
            for (String product : List.of("active", "inactive", "missing")) {
                TestFixture fixture = async ? TestFixture.createAsync() : TestFixture.create();
                fixture = fixture.givenCommands(new SetProduct("active", true), new SetProduct("inactive", false));
                try {
                    String id = product + "-reservation";
                    Object command = reservation(assertion, id, product);
                    checks.set(0);
                    var result = fixture.whenExecuting(fc -> execute(fc, command, targeted, id));
                    if (product.equals("active")) {
                        result.expectSuccessfulResult().expectEvents(command).expectNoErrors();
                    } else {
                        result.expectExceptionalResult(IllegalCommandException.class).expectNoEvents();
                    }
                    result.expectThat(fc -> {
                        assertEquals(1, checks.get());
                        assertEquals(product.equals("active"),
                                     Fluxzero.loadModel(id, Reservation.class).isPresent());
                    });
                } finally {
                    fixture.getFluxzero().close();
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT,false", "DEFAULT,true", "RETRY,false", "RETRY,true", "FAIL,false", "FAIL,true"})
    void independentProductChangesAreCheckedAtCommit(String policy, boolean targeted) {
        GateClient client = new GateClient();
        try (Fluxzero app = application(client, policy)) {
            execute(app, new SetProduct("product", true), false, null);
            app.apply(fc -> Fluxzero.loadModel("product", Product.class).get()); // Warm the cache.
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    assertEquals(ModelConflictPolicy.valueOf(policy.equals("DEFAULT") ? "RETRY" : policy),
                                 request.getConflictPolicy());
                    assertTrue(request.getReadModelIds().contains("product"));
                    execute(app, new SetProduct("product", false), false, null);
                }
            };
            checks.set(0);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(app, new Reserve("reservation", "product"), targeted, "reservation"));
            assertTrue(once.get());
            Class<? extends RuntimeException> expected = policy.equals("FAIL")
                    ? ModelCommitConflictException.class : IllegalCommandException.class;
            assertInstanceOf(expected, rootCause(failure));
            assertEquals(policy.equals("FAIL") ? 1 : 2, checks.get());
            assertNull(app.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get()));
        }
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT,false", "DEFAULT,true", "RETRY,false", "RETRY,true", "FAIL,false", "FAIL,true"})
    void newChildRetriesChangedMembershipInsteadOfForcingFail(String policy, boolean targeted) {
        GateClient client = new GateClient();
        try (Fluxzero app = application(client, policy)) {
            execute(app, new SetProduct("product", true), false, null);
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    assertFalse(request.getReadRelationships().isEmpty());
                    execute(app, new SeedReservation("other", "product", 2), false, null);
                }
            };
            checks.set(0);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(app, new ReserveSlot("reservation", "product"), targeted, "reservation"));
            assertTrue(once.get());
            Class<? extends RuntimeException> expected = policy.equals("FAIL")
                    ? ModelCommitConflictException.class : IllegalCommandException.class;
            assertInstanceOf(expected, rootCause(failure));
            assertEquals(policy.equals("FAIL") ? 1 : 2, checks.get());
            assertNull(app.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get()));
            assertNotNull(app.apply(fc -> Fluxzero.loadModel("other", Reservation.class).get()));
        }
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT,false", "DEFAULT,true", "RETRY,false", "RETRY,true"})
    void retryDoesNotTurnAFactoryIntoAnUpsert(String policy, boolean targeted) {
        GateClient client = new GateClient();
        try (Fluxzero app = application(client, policy)) {
            execute(app, new SetProduct("product", true), false, null);
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    execute(app, new SeedReservation("reservation", "product", 42), false, null);
                }
            };
            checks.set(0);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(app, new Reserve("reservation", "product"), targeted, "reservation"));
            assertInstanceOf(IllegalCommandException.class, rootCause(failure));
            assertEquals(2, checks.get(), "A retry must recheck the command and then reject the occupied factory target");
            assertEquals(42, app.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get().value()).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dependencyRetryCanStillCreateWhenTheInvariantRemainsValid(boolean targeted) {
        GateClient client = new GateClient();
        try (Fluxzero app = application(client, "DEFAULT")) {
            execute(app, new SetProduct("product", true), false, null);
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    execute(app, new SetProduct("product", true), false, null);
                }
            };
            checks.set(0);
            execute(app, new Reserve("reservation", "product"), targeted, "reservation");
            assertEquals(2, checks.get());
            assertNotNull(app.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get()));
        }
    }

    private static Fluxzero application(GateClient client, String policy) {
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
        if (!policy.equals("DEFAULT")) {
            builder.configureModelConflictHandling(ModelConflictPolicy.valueOf(policy),
                                                   ModelConflictResolver.retryIfAllowed(), 3);
        }
        return builder.build(client);
    }

    private static void execute(Fluxzero app, Object command, boolean targeted, String id) {
        app.apply(fc -> {
            if (targeted) { Fluxzero.loadGraph(id, Reservation.class).assertAndApply(command); }
            else { Fluxzero.assertAndApply(command); }
            return null;
        });
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) { failure = failure.getCause(); }
        return failure;
    }

    private static Object reservation(String assertion, String id, String product) {
        return switch (assertion) {
            case "value" -> new ReserveValue(id, product);
            case "after" -> new ReserveAfter(id, product);
            case "nested" -> new ReserveNested(id, product);
            default -> new Reserve(id, product);
        };
    }

    private static void requireActive(Product product) {
        checks.incrementAndGet();
        if (product == null || !product.active()) { throw new IllegalCommandException("Product is not active"); }
    }

    @Model record Product(@EntityId String productId, boolean active, int revision) {}
    @Model record Reservation(@EntityId String reservationId,
                              @Parent(value = Product.class, pathInParent = "reservations") String productId,
                              int value) {}

    record SetProduct(String productId, boolean active) {
        @Apply Product apply(@Nullable Product previous) {
            return new Product(productId, active, previous == null ? 0 : previous.revision() + 1);
        }
    }

    record SeedReservation(String reservationId, String productId, int value) {
        @Apply Reservation apply() { return new Reservation(reservationId, productId, value); }
    }

    record SetReservation(String reservationId, String productId, int value) {
        @Apply Reservation apply(@Nullable Reservation previous) { return new Reservation(reservationId, productId, value); }
    }

    record UnboundReserve(String reservationId) {
        @AssertLegal void check(@Association("requiredProductId") Graph<Product> product) { requireActive(product.get()); }
        @Apply Reservation apply() { return new Reservation(reservationId, null, 1); }
    }

    record ReserveAndDisable(String reservationId, String productId, boolean after) {
        @AssertLegal void before(Graph<Product> product) { if (!after) { requireActive(product.get()); } }
        @AssertLegal(afterHandler = true) void after(Graph<Product> product) { if (after) { requireActive(product.get()); } }
        @Apply Reservation reserve() { return new Reservation(reservationId, productId, 1); }
        @Apply Product disable(Product product) { return new Product(productId, false, product.revision() + 1); }
    }

    record Reserve(String reservationId, String productId) {
        @AssertLegal void check(Graph<Product> product) { requireActive(product.get()); }
        @Apply Reservation apply() { return new Reservation(reservationId, productId, 1); }
    }

    record ReserveValue(String reservationId, String productId) {
        @AssertLegal void check(@Nullable Product product) { requireActive(product); }
        @Apply Reservation apply() { return new Reservation(reservationId, productId, 1); }
    }

    record ReserveAfter(String reservationId, String productId) {
        @AssertLegal(afterHandler = true) void check(Graph<Product> product) { requireActive(product.get()); }
        @Apply Reservation apply() { return new Reservation(reservationId, productId, 1); }
    }

    record ReserveNested(String reservationId, String productId) {
        @AssertLegal ProductGuard check() { return new ProductGuard(productId); }
        @Apply Reservation apply() { return new Reservation(reservationId, productId, 1); }
    }

    record ProductGuard(String productId) {
        @AssertLegal void check(Graph<Product> product) { requireActive(product.get()); }
    }

    record ReserveSlot(String reservationId, String productId) {
        @AssertLegal void check(Graph<Product> product) {
            checks.incrementAndGet();
            if (!product.children("reservations", Reservation.class).isEmpty()) {
                throw new IllegalCommandException("Product capacity exceeded");
            }
        }
        @Apply Reservation apply() { return new Reservation(reservationId, productId, 1); }
    }

    /** Only the transport is gated; conflict checking uses the real LocalClient store. */
    private static class GateClient extends LocalClient {
        volatile Consumer<CommitModels> beforeCommit = ignored -> {};

        GateClient() { super(null); }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("commitModels")) { beforeCommit.accept((CommitModels) arguments[0]); }
                        try { return method.invoke(delegate, arguments); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        }
    }
}
