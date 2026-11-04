/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import jakarta.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitTrackedThrough;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.evictModelAndAwait;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Contracts a cache optimization must retain when aliases and independently cached owners change. */
class ModelAliasReadContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentWriterCanMoveRemoveAndShadowAWarmAlias(boolean synchronous) {
        var client = LocalClient.newInstance();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            put(writer, "first", "lookup", 1);
            read(fixture, "first", new Item("first", "lookup", 1));
            read(fixture, "lookup", new Item("first", "lookup", 1));
            Graph<Item> pinned = fixture.getFluxzero().apply(fc -> {
                Graph<Item> graph = Fluxzero.loadGraph("lookup", Item.class, false);
                graph.id();
                return graph.atStateIndex(graph.stateIndex());
            });

            put(writer, "first", "released", 2);
            put(writer, "second", "lookup", 9);
            read(fixture, "lookup", new Item("second", "lookup", 9));
            assertEquals(new Item("first", "lookup", 1), pinned.get());
            assertEquals(new Item("first", "released", 2), pinned.current().get());

            put(writer, "second", null, 10);
            read(fixture, "lookup", null);
            put(writer, "first", "lookup", 3);
            read(fixture, "lookup", new Item("first", "lookup", 3));
            Throwable rejected = assertThrows(Exception.class, () -> put(writer, "lookup", "primary-alias", 99));
            assertInstanceOf(EventSourcingException.class, io.fluxzero.common.ObjectUtils.unwrapException(rejected));
            try (Fluxzero primaryWriter = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                    .disableAutomaticModelCaching().replacePropertySource(ignored -> new SimplePropertySource(
                            Map.of("fluxzero.model.aliasTargets", "true"))).build(client)) {
                put(primaryWriter, "lookup", "primary-alias", 99);
                read(fixture, "lookup", new Item("lookup", "primary-alias", 99));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void erasureCannotResurrectAnOwnerButItsAliasCanBeReused(boolean synchronous) {
        var client = LocalClient.newInstance();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            put(writer, "owner", "lookup", 1);
            read(fixture, "owner", new Item("owner", "lookup", 1));
            read(fixture, "lookup", new Item("owner", "lookup", 1));
            commit(writer, new DeleteItem("owner"));
            read(fixture, "lookup", null);
            put(writer, "owner", "lookup", 2);
            read(fixture, "lookup", new Item("owner", "lookup", 2));
            writer.modelRepository().deleteModel("owner", ModelDeletionCascade.NONE).join();
            read(fixture, "lookup", null);
            Throwable rejected = assertThrows(Exception.class, () -> put(writer, "owner", "lookup", 3));
            assertInstanceOf(IllegalStateException.class, io.fluxzero.common.ObjectUtils.unwrapException(rejected));
            put(writer, "new-owner", "lookup", 7);
            read(fixture, "lookup", new Item("new-owner", "lookup", 7));
            fixture.given(fc -> clearCacheAndAwait(fc));
            read(fixture, "lookup", new Item("new-owner", "lookup", 7));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitWrongTypeDoesNotFallBackToAnotherCachedAlias(boolean synchronous) {
        var client = LocalClient.newInstance();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            commit(writer, new PutOther("lookup"));
            put(writer, "owner", "lookup", 1);
            read(fixture, "owner", new Item("owner", "lookup", 1));
            fixture.whenQuery(new ReadItem("lookup")).expectExceptionalResult(EventSourcingException.class);
            read(fixture, "owner", new Item("owner", "lookup", 1));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMutationAfterTheReadResponseKeepsTheVerifiedBoundary(boolean synchronous) {
        var client = new AfterReadClient();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            prime(fixture, client, writer);
            client.reads.clear();
            client.afterRead.set(() -> {
                put(writer, "first", "released", 2);
                put(writer, "second", "lookup", 9);
            });
            fixture.whenQuery(new ReadItem("lookup")).expectResult(new Item("first", "lookup", 1))
                    .expectEvents(new PutItem("first", "released", 2), new PutItem("second", "lookup", 9))
                    .expectNoCommands();
            assertNull(client.afterRead.get(), "The controlled mutation must actually run");
            GetModelEvents aliasRead = client.reads.stream()
                    .filter(request -> request.getRequests().stream().anyMatch(r -> r.getModelId().equals("lookup")))
                    .findFirst().orElseThrow();
            assertTrue(aliasRead.getRequests().stream().allMatch(r -> r.getMaxSize() == 0),
                       "The mutation must follow the optimized head response");
            read(fixture, "lookup", new Item("second", "lookup", 9));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void cacheLossAfterHeadKeepsCanonicalIdentityAndTheFirstBoundary(boolean synchronous, boolean clear) {
        var client = new AfterReadClient();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            prime(fixture, client, writer);
            client.afterRead.set(() -> {
                if (clear) { clearCacheAndAwait(fixture.getFluxzero()); }
                else { evictModelAndAwait(fixture.getFluxzero(), "first"); }
                put(writer, "first", "released", 2);
                put(writer, "second", "lookup", 9);
            });
            client.reads.clear();
            read(fixture, "lookup", new Item("first", "lookup", 1));
            assertTrue(client.reads.getFirst().getRequests().stream().allMatch(r -> r.getMaxSize() == 0));
            // Independent writer commits can also read. Only the fallback is pinned to the first response boundary.
            assertEquals(List.of("first"), client.reads.stream().filter(r -> r.getBoundary().historical())
                    .flatMap(r -> r.getRequests().stream()).map(r -> r.getModelId()).toList());
            read(fixture, "lookup", new Item("second", "lookup", 9));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void erasedCanonicalSpellingCannotRedirectPinnedFallbackToAnotherOwner(boolean synchronous) {
        var client = new AfterReadClient();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            prime(fixture, client, writer);
            client.afterRead.set(() -> {
                clearCacheAndAwait(fixture.getFluxzero());
                writer.modelRepository().deleteModel("first", ModelDeletionCascade.NONE).join();
                put(writer, "second", "first", 9);
            });
            fixture.whenQuery(new ReadItem("lookup")).expectExceptionalResult(EventSourcingException.class);
            read(fixture, "second", new Item("second", "first", 9));
            read(fixture, "lookup", null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownColdAliasCannotPublishAcrossAClearDuringItsPayloadRead(boolean synchronous) {
        var client = new AfterReadClient();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            prime(fixture, client, writer);
            clearCacheAndAwait(fixture.getFluxzero());
            client.afterRead.set(() -> clearCacheAndAwait(fixture.getFluxzero()));
            read(fixture, "lookup", new Item("first", "lookup", 1));
            client.reads.clear();
            read(fixture, "first", new Item("first", "lookup", 1));
            assertEquals(1, client.reads.size(), "A clear after read-token admission must fence canonical publication");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canonicalFallbackRetainsItsHeadAcrossMultiplePayloadPages(boolean synchronous) {
        var client = new AfterReadClient();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            awaitCacheTracking(fixture.getFluxzero());
            // The default stream page holds 128 memberships; the final value must come from a later page.
            for (int version = 0; version < 130; version++) { put(writer, "first", "lookup", version); }
            long boundary = writer.apply(fc -> Fluxzero.loadCurrentGraph("first", Item.class).stateIndex());
            awaitTrackedThrough(client.polls, boundary);
            clearCacheAndAwait(fixture.getFluxzero());
            read(fixture, "first", new Item("first", "lookup", 129));
            read(fixture, "lookup", new Item("first", "lookup", 129));
            client.afterRead.set(() -> {
                clearCacheAndAwait(fixture.getFluxzero());
                put(writer, "first", "released", 130);
                put(writer, "second", "lookup", 9);
                // The writer also replayed several pages. Count only reader requests after this response hook.
                client.reads.clear();
            });
            client.reads.clear();
            read(fixture, "lookup", new Item("first", "lookup", 129));
            List<GetModelEvents> pages = client.reads.stream().filter(r -> r.getBoundary().historical()).toList();
            assertTrue(pages.size() >= 2, "Fallback must actually cross a payload page boundary");
            assertTrue(pages.stream().allMatch(r -> Long.valueOf(boundary).equals(r.getBoundary().stateIndex())
                    && r.getRequests().stream().allMatch(s -> s.getModelId().equals("first"))));
            read(fixture, "lookup", new Item("second", "lookup", 9));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignTypeRebindingDoesNotEvictTheOtherOwnersCanonicalCache(boolean synchronous) {
        var client = new AfterReadClient();
        var fixture = new Fixture(client, synchronous);
        try (Fluxzero writer = writer(client)) {
            prime(fixture, client, writer);
            put(writer, "first", "released", 2);
            commit(writer, new PutForeign("second", "lookup"));
            long boundary = writer.apply(fc -> Fluxzero.loadCurrentGraph("second", Foreign.class).stateIndex());
            awaitTrackedThrough(client.polls, boundary);
            fixture.whenQuery(new ReadForeign("second")).expectResult(new Foreign("second", "lookup"));
            read(fixture, "first", new Item("first", "released", 2));
            client.reads.clear();
            fixture.whenQuery(new ReadItem("lookup")).expectExceptionalResult(EventSourcingException.class);
            assertTrue(client.reads.getFirst().getRequests().stream().allMatch(r -> r.getMaxSize() == 0),
                       "The foreign-type test must exercise the live-candidate head path");
            client.reads.clear();
            fixture.whenQuery(new ReadForeign("second")).expectResult(new Foreign("second", "lookup"));
            assertTrue(client.reads.isEmpty(), "A wrong-type alias must not evict the valid foreign canonical owner");
        }
    }

    private static void prime(Fixture fixture, AfterReadClient client, Fluxzero writer) {
        awaitCacheTracking(fixture.getFluxzero());
        put(writer, "first", "lookup", 1);
        long boundary = writer.apply(fc -> Fluxzero.loadCurrentGraph("first", Item.class).stateIndex());
        awaitTrackedThrough(client.polls, boundary);
        clearCacheAndAwait(fixture.getFluxzero());
        read(fixture, "first", new Item("first", "lookup", 1));
        read(fixture, "lookup", new Item("first", "lookup", 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalAliasesRemainIsolatedAcrossNamespaces(boolean synchronous) {
        var client = LocalClient.newInstance();
        var blue = new Fixture(client.forNamespace("blue"), synchronous);
        var red = new Fixture(client.forNamespace("red"), synchronous);
        try (Fluxzero blueWriter = writer(client.forNamespace("blue"));
             Fluxzero redWriter = writer(client.forNamespace("red"))) {
            put(blueWriter, "blue-owner", "lookup", 1);
            put(redWriter, "red-owner", "lookup", 9);
            read(blue, "blue-owner", new Item("blue-owner", "lookup", 1));
            read(red, "red-owner", new Item("red-owner", "lookup", 9));
            read(blue, "lookup", new Item("blue-owner", "lookup", 1));
            read(red, "lookup", new Item("red-owner", "lookup", 9));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelAndApplicationCacheOptOutsKeepLiteralAliasReadsUncached(boolean applicationOptOut) {
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
        if (applicationOptOut) { builder.disableAutomaticModelCaching(); }
        try (Fluxzero app = builder.build(LocalClient.newInstance())) {
            commit(app, applicationOptOut ? new PutItem("owner", "lookup", 1) : new PutUncached("owner", "lookup"));
            Object first = app.apply(fc -> applicationOptOut
                    ? Fluxzero.loadModel("lookup", Item.class, false).get()
                    : Fluxzero.loadModel("lookup", Uncached.class, false).get());
            Object second = app.apply(fc -> applicationOptOut
                    ? Fluxzero.loadModel("lookup", Item.class, false).get()
                    : Fluxzero.loadModel("lookup", Uncached.class, false).get());
            assertEquals(first, second);
            assertNotSame(first, second);
        }
    }

    @Model(searchable = false, cached = false)
    record Uncached(@EntityId String id, @Alias String alias) {}
    record PutUncached(String id, String alias) {
        @Apply Uncached apply() { return new Uncached(id, alias); }
    }

    private static Fluxzero writer(Client client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .disableAutomaticModelCaching().build(client);
    }

    private static void put(Fluxzero app, String id, String alias, int version) {
        commit(app, new PutItem(id, alias, version));
    }

    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    private static void read(TestFixture fixture, String id, Item expected) {
        // This chain deliberately publishes events from an independent writer between queries.
        fixture.whenQuery(new ReadItem(id)).expectResult(expected).expectNoCommands();
    }

    @Model(searchable = false)
    record Item(@EntityId String id, @Alias String alias, int version) {}
    record PutItem(String id, String alias, int version) {
        @Apply Item apply(@Nullable Item before) { return new Item(id, alias, version); }
    }
    record DeleteItem(String id) {
        @Apply Item apply(Item before) { return null; }
    }
    record ReadItem(String id) {
        @HandleQuery Item handle() { return Fluxzero.loadModel(id, Item.class, false).get(); }
    }
    @Model(searchable = false)
    record Other(@EntityId String id) {}
    record PutOther(String id) {
        @Apply Other apply() { return new Other(id); }
    }

    @Model(searchable = false)
    record Foreign(@EntityId String id, @Alias String alias) {}
    record PutForeign(String id, String alias) {
        @Apply Foreign apply() { return new Foreign(id, alias); }
    }
    record ReadForeign(String id) {
        @HandleQuery Foreign handle() { return Fluxzero.loadModel(id, Foreign.class, false).get(); }
    }

    private static final class Fixture extends TestFixture {
        Fixture(Client client, boolean synchronous) {
            super(DefaultFluxzero.builder(), ignored -> List.of(), client, synchronous);
        }
    }

    private static final class AfterReadClient extends LocalClient {
        final AtomicReference<Runnable> afterRead = new AtomicReference<>();
        final List<GetModelEvents> reads = new CopyOnWriteArrayList<>();
        final BlockingQueue<TrackModelUpdates> polls = new LinkedBlockingQueue<>();
        AfterReadClient() { super(null); }
        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient storage = super.createEventStoreClient();
            EventStoreClient observed = mock(EventStoreClient.class, delegatesTo(storage));
            doAnswer(invocation -> {
                GetModelEvents request = invocation.getArgument(0);
                GetModelEventsResult response = storage.getModelEvents(request);
                reads.add(request);
                Runnable action = request.getRequests().stream().anyMatch(stream -> stream.getModelId().equals("lookup"))
                        ? afterRead.getAndSet(null) : null;
                if (action != null) { action.run(); }
                return response;
            }).when(observed).getModelEvents(any());
            doAnswer(invocation -> {
                TrackModelUpdates request = invocation.getArgument(0);
                polls.add(request);
                return storage.trackModelUpdates(request);
            }).when(observed).trackModelUpdates(any());
            return observed;
        }
    }
}
