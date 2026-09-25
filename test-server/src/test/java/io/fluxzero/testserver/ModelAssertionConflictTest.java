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
package io.fluxzero.testserver;

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.modeling.ModelCommitConflictException;
import io.fluxzero.sdk.modeling.ModelConflictResolver;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import jakarta.annotation.Nullable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ModelAssertionConflictTest {
    private static Server server;
    private static int port;
    private static final AtomicInteger checks = new AtomicInteger();

    @BeforeAll
    static void start() {
        server = TestServer.startServer(new InetSocketAddress("127.0.0.1", 0));
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception { server.stop(); }

    @Test
    void modelServerCannotBeShadowedOnItsClientAddress() throws Exception {
        try (ServerSocket other = new ServerSocket()) {
            other.setReuseAddress(true);
            assertThrows(BindException.class, () -> other.bind(new InetSocketAddress("127.0.0.1", port)));
        }
    }

    @ParameterizedTest
    @CsvSource({"RETRY", "ACCEPT"})
    void openMemberReadsAreProtectedAndReplayedOverWebSocket(String policy) {
        String namespace = "open-members-" + UUID.randomUUID();
        GateClient client = new GateClient(config(namespace, "reader"));
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(ModelConflictPolicy.valueOf(policy),
                        ModelConflictResolver.retryIfAllowed(), 3).build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(WebSocketClient.newInstance(config(namespace, "writer")))) {
            execute(writer, new SetProduct("product", true), false);
            execute(writer, new CreateOwner("owner"), false);
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("owner") && once.compareAndSet(false, true)) {
                    assertTrue(request.getReadModelIds().contains("product"));
                    execute(writer, new SetProduct("product", false), false);
                }
            };
            app.apply(fc -> {
                Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new ObserveProduct("item", "product"));
                return null;
            });
            assertTrue(once.get());
            // A separate client reconstructs the root and its late dependency from stored history.
            Owner result = writer.apply(fc -> Fluxzero.loadCurrentGraph("owner", Owner.class).get());
            assertEquals(2, ((ConcreteItem) result.items().getFirst()).observation());
        }
    }

    @Model(name = "ModelAssertionConflictTest.Owner") record Owner(@EntityId String id, @Member List<Item> items) {}
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS)
    interface Item { @EntityId String itemId(); }
    record ConcreteItem(String itemId, int observation) implements Item {
        @Apply ConcreteItem apply(ObserveProduct update, Graph<Product> product) {
            return new ConcreteItem(itemId, product.get().active() ? 1 : 2);
        }
    }
    record ObserveProduct(String itemId, String productId) {}
    record CreateOwner(String id) {
        @Apply Owner create() { return new Owner(id, List.of(new ConcreteItem("item", 0))); }
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT,false,false", "DEFAULT,true,false", "RETRY,false,false", "RETRY,true,false",
            "FAIL,false,false", "FAIL,true,false", "DEFAULT,false,true", "DEFAULT,true,true",
            "RETRY,false,true", "RETRY,true,true", "FAIL,false,true", "FAIL,true,true"})
    void creationValidatesCrossModelAssertionsOverWebSocket(String policy, boolean targeted, boolean membership) {
        String namespace = "model-assertions-" + UUID.randomUUID();
        GateClient client = new GateClient(config(namespace, "reader"));
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
        if (!policy.equals("DEFAULT")) {
            builder.configureModelConflictHandling(ModelConflictPolicy.valueOf(policy),
                                                   ModelConflictResolver.retryIfAllowed(), 3);
        }
        try (Fluxzero app = builder.build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(WebSocketClient.newInstance(config(namespace, "writer")))) {
            execute(writer, new SetProduct("product", true), false);
            app.apply(fc -> Fluxzero.loadModel("product", Product.class).get());
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    assertEquals(policy.equals("FAIL") ? ModelConflictPolicy.FAIL : ModelConflictPolicy.RETRY,
                                 request.getConflictPolicy());
                    if (membership) {
                        assertFalse(request.getReadRelationships().isEmpty());
                        execute(writer, new CreateReservation("other", "product"), false);
                    } else {
                        assertTrue(request.getReadModelIds().contains("product"));
                        execute(writer, new SetProduct("product", false), false);
                    }
                }
            };
            checks.set(0);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(app, new Reserve("reservation", "product", membership), targeted));
            assertTrue(once.get());
            Throwable cause = failure;
            while (cause.getCause() != null) { cause = cause.getCause(); }
            Class<? extends RuntimeException> expected = policy.equals("FAIL")
                    ? ModelCommitConflictException.class : IllegalCommandException.class;
            assertInstanceOf(expected, cause);
            assertEquals(policy.equals("FAIL") ? 1 : 2, checks.get());
            assertNull(writer.apply(fc -> Fluxzero.loadModel("reservation", Reservation.class).get()));
        }
    }

    private static WebSocketClient.ClientConfig config(String namespace, String name) {
        return WebSocketClient.ClientConfig.builder().name(name).namespace(namespace)
                .runtimeBaseUrl("ws://127.0.0.1:" + port).build();
    }

    private static void execute(Fluxzero app, Object command, boolean targeted) {
        app.apply(fc -> {
            if (targeted) { Fluxzero.loadGraph("reservation", Reservation.class).assertAndApply(command); }
            else { Fluxzero.assertAndApply(command); }
            return null;
        });
    }

    @Model(name = "ModelAssertionConflictTest.Product") record Product(@EntityId String productId, boolean active) {}
    @Model(name = "ModelAssertionConflictTest.Reservation") record Reservation(@EntityId String reservationId,
                              @Parent(value = Product.class, pathInParent = "reservations") String productId) {}
    record SetProduct(String productId, boolean active) {
        @Apply Product apply(@Nullable Product previous) { return new Product(productId, active); }
    }
    record CreateReservation(String reservationId, String productId) {
        @Apply Reservation apply() { return new Reservation(reservationId, productId); }
    }
    record Reserve(String reservationId, String productId, boolean membership) {
        @AssertLegal void check(Graph<Product> product) {
            checks.incrementAndGet();
            if (product.isEmpty() || !product.get().active()
                || membership && !product.children("reservations", Reservation.class).isEmpty()) {
                throw new IllegalCommandException("Reservation denied");
            }
        }
        @Apply Reservation apply() { return new Reservation(reservationId, productId); }
    }

    private static class GateClient extends WebSocketClient {
        volatile Consumer<CommitModels> beforeCommit = ignored -> {};
        GateClient(ClientConfig config) { super(config, null); }
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
