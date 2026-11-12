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
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.common.api.search.GetDocumentResult;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.modeling.ModelCommitConflictException;
import io.fluxzero.sdk.modeling.ModelConflictResolver;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    @Model(searchable = false, name = "ModelAssertionConflictTest.Owner") record Owner(@EntityId String id, @Member List<Item> items) {}
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

    @Test
    void propertyQualifiedAliasTargetsRoundTripOverWebSocket() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new io.fluxzero.common.application.SimplePropertySource(
                        java.util.Map.of("fluxzero.model.aliasTargets", "true")))
                .build(WebSocketClient.newInstance(config("alias-" + UUID.randomUUID(), "aliases")))) {
            execute(app, new PutAliasAccount("shared", "primary-email", 1), false);
            execute(app, new PutAliasAccount("owner", "shared", 2), false);
            execute(app, new UpdateAliasAccount("shared", 3), false);
            AliasAccount owner = app.apply(fc -> Fluxzero.loadModel("owner", AliasAccount.class).get());
            AliasAccount primary = app.apply(fc -> Fluxzero.loadModel("shared", AliasAccount.class).get());
            assertEquals(3, owner.number());
            assertEquals(1, primary.number());
        }
    }

    @Model(searchable = false, cached = false)
    record AliasAccount(@EntityId String accountId, @io.fluxzero.sdk.modeling.Alias String email, int number) {}
    record PutAliasAccount(String accountId, String email, int number) {
        @Apply AliasAccount apply() { return new AliasAccount(accountId, email, number); }
    }
    record UpdateAliasAccount(String email, int number) {
        @Apply AliasAccount apply(AliasAccount before) { return new AliasAccount(before.accountId(), email, number); }
    }

    @Test
    void currentGraphReusesEventlessDocumentBodiesAndSeesIndependentParentUpdates() {
        String namespace = "document-cache-" + UUID.randomUUID();
        var client = new DocumentReaderClient(config(namespace, "reader"));
        try (var reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             var writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(WebSocketClient.newInstance(config(namespace, "writer")))) {
            execute(writer, new SetGroup("group", true), false);
            execute(writer, new SetEntry("entry", "group"), false);
            reader.registerHandlers(Group.class, Entry.class);
            assertTrue(canReadEntry(reader));
            assertEquals(2, client.bodies.get());
            client.documentReads.set(0);
            for (int i = 0; i < 100; i++) { assertTrue(canReadEntry(reader)); }
            assertEquals(0, client.documentReads.get(), "Storage head and relationship reads attest the cached bodies");

            var eventRead = new CompletableFuture<Boolean>();
            reader.registerHandlers(new Object() {
                @HandleEvent
                void read(CheckEntry event) {
                    var graph = Fluxzero.loadGraph("entry", Entry.class);
                    eventRead.complete(graph.get() != null && graph.parentModel(Group.class).orElseThrow().enabled());
                }
            });
            execute(writer, new CheckEntry("check"), false);
            assertTrue(eventRead.orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(0, client.documentReads.get(), "An event-bound Graph uses the confirmed materialized revisions");
            execute(writer, new SetGroup("group", false), false);
            assertFalse(canReadEntry(reader));
            assertEquals(1, client.documentReads.get(), "Only the changed parent needs its new body");
            assertFalse(canReadEntry(reader));
            assertEquals(1, client.documentReads.get(), "The new parent revision is reusable");
            execute(writer, new DeleteEntry("entry"), false);
            assertFalse(canReadEntry(reader));
        }
    }

    @Test
    void laggingEventHandlerReadsCurrentDocumentValuesThroughHistoricalRelationships() throws Exception {
        String namespace = "lagging-document-reader-" + UUID.randomUUID();
        var client = new DocumentReaderClient(config(namespace, "reader"));
        try (var reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             var writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(WebSocketClient.newInstance(config(namespace, "writer")))) {
            execute(writer, new SetGroup("original", true), false);
            execute(writer, new SetGroup("replacement", true), false);
            execute(writer, new SetEntry("entry", "original"), false);
            reader.registerHandlers(Group.class, Entry.class);
            assertTrue(canReadEntry(reader));

            record Observation(Entry entry, Group parent) {}
            var handlerStarted = new CompletableFuture<Void>();
            var writesCommitted = new CompletableFuture<Void>();
            var observed = new CompletableFuture<Observation>();
            reader.registerHandlers(new Object() {
                @HandleEvent
                void read(CheckEntry event) {
                    handlerStarted.complete(null);
                    try {
                        writesCommitted.join();
                        var graph = Fluxzero.loadGraph("entry", Entry.class);
                        observed.complete(new Observation(graph.get(), graph.parentModel(Group.class).orElseThrow()));
                    } catch (RuntimeException e) {
                        observed.completeExceptionally(e);
                    }
                }
            });
            try {
                execute(writer, new CheckEntry("check"), false);
                handlerStarted.get(5, TimeUnit.SECONDS);
                execute(writer, new SetGroup("original", false), false);
                execute(writer, new SetEntry("entry", "replacement"), false);
            } finally {
                writesCommitted.complete(null);
            }
            assertEquals(new Observation(new Entry("entry", "replacement"), new Group("original", false)),
                         observed.get(5, TimeUnit.SECONDS));
        }
    }

    private static boolean canReadEntry(Fluxzero app) {
        return app.apply(fc -> {
            var graph = Fluxzero.loadCurrentGraph("entry", Entry.class);
            return graph.get() != null && graph.parentModel(Group.class).map(Group::enabled).orElse(false);
        });
    }

    @Model(persistence = ModelPersistence.DOCUMENT, eventPublication = EventPublication.NEVER, cachingDepth = 0)
    record Group(@EntityId String id, boolean enabled) {}

    @Model(persistence = ModelPersistence.DOCUMENT, eventPublication = EventPublication.NEVER, cachingDepth = 0)
    record Entry(@EntityId String id, @Parent(Group.class) String groupId) {}

    @Model record Check(@EntityId String id) {}
    record CheckEntry(String id) {
        @Apply Check apply() { return new Check(id); }
    }

    record SetGroup(String id, boolean enabled) {
        @Apply Group apply(@Nullable Group previous) { return new Group(id, enabled); }
    }

    record SetEntry(String id, String groupId) {
        @Apply Entry apply(@Nullable Entry previous) { return new Entry(id, groupId); }
    }

    record DeleteEntry(String id) {
        @Apply Entry apply(Entry previous) { return null; }
    }

    private static class DocumentReaderClient extends WebSocketClient {
        final AtomicInteger documentReads = new AtomicInteger(), bodies = new AtomicInteger();

        DocumentReaderClient(ClientConfig config) { super(config, null); }

        @Override protected SearchClient createSearchClient() {
            var delegate = super.createSearchClient();
            return (SearchClient) Proxy.newProxyInstance(SearchClient.class.getClassLoader(), new Class<?>[]{SearchClient.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (result instanceof GetDocumentResult document) {
                                documentReads.incrementAndGet();
                                if (document.getDocument() != null) { bodies.incrementAndGet(); }
                            }
                            return result;
                        } catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        }

        @Override protected EventStoreClient createEventStoreClient() {
            var delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("trackModelUpdates")) {
                            // This client receives no update notifications, including after a successful remote write.
                            return new CompletableFuture<>();
                        }
                        try { return method.invoke(delegate, arguments); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
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

    @Model(searchable = false, name = "ModelAssertionConflictTest.Product") record Product(@EntityId String productId, boolean active) {}
    @Model(searchable = false, name = "ModelAssertionConflictTest.Reservation") record Reservation(@EntityId String reservationId,
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

    @Test
    void parentCardinalityShorthandRetriesThroughOrdinaryRelationshipConflicts() {
        String namespace = "parent-cardinality-" + UUID.randomUUID();
        GateClient client = new GateClient(config(namespace, "reader"));
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(ModelConflictPolicy.RETRY,
                                                ModelConflictResolver.retryIfAllowed(), 3)
                .build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(WebSocketClient.newInstance(config(namespace, "writer")))) {
            execute(writer, new CreateCapacityRoot("root"), false);
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadRelationships().stream().anyMatch(read ->
                        read.modelId().equals("root")
                        && read.direction()
                        == io.fluxzero.common.api.modeling.ModelRelationshipRead.Direction.CHILDREN)
                    && once.compareAndSet(false, true)) {
                    execute(writer, new CreateCapacityChild("winner", "root"), false);
                }
            };

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(app, new CreateCapacityChild("contender", "root"), false));
            assertTrue(once.get());
            Throwable cause = failure;
            while (cause.getCause() != null) { cause = cause.getCause(); }
            assertInstanceOf(IllegalCommandException.class, cause);
            assertNull(writer.apply(fc -> Fluxzero.loadModel("contender", CapacityChild.class).get()));
            assertNotNull(writer.apply(fc -> Fluxzero.loadModel("winner", CapacityChild.class).get()));
        }
    }

    @Model(searchable = false, name = "ModelAssertionConflictTest.CapacityRoot")
    record CapacityRoot(@EntityId String id) {}
    @Model(searchable = false, name = "ModelAssertionConflictTest.CapacityChild")
    record CapacityChild(
            @EntityId String id,
            @Parent(value = CapacityRoot.class, maxChildren = 1) String rootId) {}
    record CreateCapacityRoot(String id) {
        @Apply CapacityRoot apply() { return new CapacityRoot(id); }
    }
    record CreateCapacityChild(String id, String rootId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        CapacityChild apply() { return new CapacityChild(id, rootId); }
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
