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
package io.fluxzero.sdk.test.contracts;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.common.caching.CacheEviction;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.test.SpyingClient;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Literal alias correctness and storage costs shared by local, TestServer and production Runtime clients. */
@Timeout(30)
public abstract class ModelAliasCacheContract {
    private static final String OWNER = "custom-owner", ALIAS = "friendly-name";

    /** Returns two clients sharing isolated storage, for an observed reader and an independent writer. */
    protected abstract Client[] clients(String namespace);

    @Test
    void coldAliasesRetainOneRequestAndWarmAliasesReadOnlyTheFreshHead() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            for (int attempt = 0; attempt < 4; attempt++) {
                session.observed.reset();
                assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 1));
                if (attempt < 2) { assertReplay(session.observed, ALIAS); }
                else { assertHeadOnly(session.observed, ALIAS); }
            }
            session.observed.reset();
            assertItem(session.reader, OWNER, new Item(OWNER, ALIAS, 1));
            assertEquals(0, session.observed.reads.size(), "Canonical reads must remain cache hits");

            clearCacheAndAwait(session.reader);
            session.observed.reset();
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 1));
            assertReplay(session.observed, ALIAS);
        }
    }

    @Test
    void anAlreadyWarmOwnerIsReusedAfterTheAliasIdentityHasBeenObserved() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            assertItem(session.reader, OWNER, new Item(OWNER, ALIAS, 1));
            session.observed.reset();
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 1));
            assertReplay(session.observed, ALIAS);
            session.observed.reset();
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 1));
            assertHeadOnly(session.observed, ALIAS);
            session.observed.reset();
            assertItem(session.reader, OWNER, new Item(OWNER, ALIAS, 1));
            assertEquals(0, session.observed.reads.size());
        }
    }

    @Test
    void reassignmentRemovalAndUpdatesRetainCurrentBindingsAndPinnedHistory() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            warmAlias(session);
            Graph<Item> pinned = session.reader.apply(fc -> {
                Graph<Item> graph = Fluxzero.loadGraph(ALIAS, Item.class, false);
                graph.id();
                return graph.atStateIndex(graph.stateIndex());
            });
            put(session.writer, OWNER, "released", 2);
            put(session.writer, "second-owner", ALIAS, 9);
            assertItem(session.reader, ALIAS, new Item("second-owner", ALIAS, 9));
            assertEquals(new Item(OWNER, ALIAS, 1), pinned.get());
            assertEquals(new Item(OWNER, "released", 2), pinned.current().get());
            put(session.writer, "second-owner", ALIAS, 10);
            assertItem(session.reader, ALIAS, new Item("second-owner", ALIAS, 10));
            put(session.writer, "second-owner", null, 11);
            assertItem(session.reader, ALIAS, null);
            put(session.writer, OWNER, ALIAS, 3);
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 3));
        }
    }

    @Test
    void deletionAndErasureDoNotResurrectCachedOwnersAndAliasesRemainReusable() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            warmAlias(session);
            write(session.writer, new DeleteItem(OWNER));
            assertItem(session.reader, ALIAS, null);
            put(session.writer, OWNER, ALIAS, 2);
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 2));
            session.writer.modelRepository().deleteModel(OWNER, ModelDeletionCascade.NONE).join();
            assertItem(session.reader, ALIAS, null);
            assertThrows(RuntimeException.class, () -> put(session.writer, OWNER, ALIAS, 3));
            put(session.writer, "replacement", ALIAS, 7);
            assertItem(session.reader, ALIAS, new Item("replacement", ALIAS, 7));
            clearCacheAndAwait(session.reader);
            assertItem(session.reader, ALIAS, new Item("replacement", ALIAS, 7));
        }
    }

    @Test
    void newLiveAndDeletedPrimariesShadowAnAlreadyCachedAlias() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            warmAlias(session);
            createPrimary(session.writer, new PutItem(ALIAS, null, 99), "AliasCacheItem");
            assertItem(session.reader, ALIAS, new Item(ALIAS, null, 99));
            write(session.writer, new DeleteItem(ALIAS));
            long deletion = session.writer.apply(fc -> Fluxzero.loadCurrentGraph(ALIAS, Item.class, false).stateIndex());
            // The spelling is now a canonical cache key; observe its ordinary asynchronous invalidation first.
            awaitTrackedThrough(session.observed.polls, deletion);
            assertItem(session.reader, ALIAS, null);
            assertItem(session.reader, OWNER, new Item(OWNER, ALIAS, 1));
        }
    }

    @Test
    void aWrongTypePrimaryNeverFallsBackToTheCachedAliasOwner() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            warmAlias(session);
            createPrimary(session.writer, new PutOther(ALIAS), "AliasCacheOther");
            Throwable failure = assertThrows(RuntimeException.class,
                    () -> read(session.reader, ALIAS));
            assertInstanceOf(EventSourcingException.class, io.fluxzero.common.ObjectUtils.unwrapException(failure));
            assertItem(session.reader, OWNER, new Item(OWNER, ALIAS, 1));
        }
    }

    @Test
    void aReassignmentAfterTheHeadResponseRetainsThatReadBoundary() {
        try (Session session = session(true)) {
            prepareColdReader(session);
            warmAlias(session);
            session.observed.afterAliasResponse.set(() -> {
                put(session.writer, OWNER, "released", 2);
                put(session.writer, "second-owner", ALIAS, 9);
            });
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 1));
            assertNull(session.observed.afterAliasResponse.get(), "The controlled mutation must actually run");
            assertItem(session.reader, ALIAS, new Item("second-owner", ALIAS, 9));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicationAndModelCacheOptOutsContinueToReplay(boolean applicationOptOut) {
        try (Session session = session(!applicationOptOut)) {
            write(session.writer, applicationOptOut ? new PutItem(OWNER, ALIAS, 1)
                    : new PutUncached(OWNER, ALIAS));
            Object first = uncachedRead(session.reader, applicationOptOut);
            session.observed.reset();
            Object second = uncachedRead(session.reader, applicationOptOut);
            assertEquals(first, second);
            assertNotSame(first, second);
            assertReplay(session.observed, ALIAS);
        }
    }

    private static Object uncachedRead(Fluxzero reader, boolean applicationOptOut) {
        return reader.apply(fc -> applicationOptOut ? Fluxzero.loadModel(ALIAS, Item.class, false).get()
                : Fluxzero.loadModel(ALIAS, Uncached.class, false).get());
    }

    private Session session(boolean caching) {
        Client[] pair = clients("alias-cache-contract-" + UUID.randomUUID());
        ObservedClient observed = new ObservedClient(pair[0]);
        Fluxzero writer = app(pair[1], false);
        try {
            return new Session(app(observed, caching), writer, observed);
        } catch (RuntimeException | Error failure) {
            try { writer.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            try { pair[0].shutDown(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static Fluxzero app(Client client, boolean caching) {
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().disableTrackingMetrics();
        if (!caching) { builder.disableAutomaticModelCaching(); }
        return builder.build(client);
    }

    private static void prepareColdReader(Session session) {
        put(session.writer, OWNER, ALIAS, 1);
        assertItem(session.reader, OWNER, new Item(OWNER, ALIAS, 1));
        long boundary = session.writer.apply(fc -> Fluxzero.loadCurrentGraph(OWNER, Item.class, false).stateIndex());
        awaitTrackedThrough(session.observed.polls, boundary);
        clearCacheAndAwait(session.reader);
    }

    private static void warmAlias(Session session) {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertItem(session.reader, ALIAS, new Item(OWNER, ALIAS, 1));
        }
    }

    private static void put(Fluxzero app, String id, String alias, int value) {
        write(app, new PutItem(id, alias, value));
    }

    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    /** Creates an equal primary through the existing exact-ID protocol, without a newer identity-selection capability. */
    private static void createPrimary(Fluxzero app, Object event, String type) {
        long boundary = app.client().getEventStoreClient().getModelEvents(
                new GetModelEvents(List.of(), ModelReadBoundary.current(), 0L)).getStateIndex();
        var target = ModelCommitTarget.builder().modelId(ALIAS).modelType(type).expectedSequenceNumber(-1L)
                .storeEvent(true).updateState(true).relationships(List.of()).aliases(List.of()).build();
        var result = app.client().getEventStoreClient().commitModels(new CommitModels(UUID.randomUUID().toString(),
                boundary, List.of(ALIAS), List.of(new ModelCommitStep(new Message(event).serialize(app.serializer()),
                true, List.of(target))), ModelConflictPolicy.RETRY, Guarantee.STORED, false)).join();
        assertTrue(result.isAccepted());
    }

    private static Lookup read(Fluxzero reader, String key) {
        return reader.apply(fc -> {
            Entity<Item> item = Fluxzero.loadModel(key, Item.class, false);
            return new Lookup(item.id().toString(), item.get());
        });
    }

    private static void assertItem(Fluxzero reader, String key, @Nullable Item expected) {
        Lookup result = read(reader, key);
        assertEquals(expected, result.item());
        if (expected != null) { assertEquals(expected.id(), result.id()); }
    }

    private static void assertReplay(ObservedClient client, String key) {
        assertEquals(1, client.reads.size(), "A cold alias must retain one storage request");
        Read read = client.reads.getFirst();
        assertEquals(List.of(key), read.request().getRequests().stream().map(r -> r.getModelId()).toList());
        assertEquals(GetModelEvents.class, read.request().getClass(), "Ordinary reads require no new peer capability");
        assertEquals(1, read.metric().getPayloadCount());
        assertTrue(read.metric().getBytes() > 0);
        assertNoOtherReads(client);
    }

    private static void assertHeadOnly(ObservedClient client, String key) {
        assertEquals(1, client.reads.size());
        Read read = client.reads.getFirst();
        assertEquals(List.of(key), read.request().getRequests().stream().map(r -> r.getModelId()).toList());
        assertEquals(GetModelEvents.class, read.request().getClass(), "Ordinary reads require no new peer capability");
        assertTrue(read.request().getRequests().stream().allMatch(r -> r.getMaxSize() == 0));
        assertEquals(0, read.metric().getPayloadCount());
        assertEquals(0L, read.metric().getBytes());
        assertNoOtherReads(client);
    }

    private static void assertNoOtherReads(ObservedClient client) {
        assertEquals(0, client.graphReads.get(), "Literal Model reads must not load a graph");
        assertEquals(0, client.searchReads.get(), "Alias identity must come from the Model store");
    }

    /** A new long poll proves the preceding update page was processed, rather than merely received. */
    private static void awaitTrackedThrough(BlockingQueue<TrackModelUpdates> polls, long boundary) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                TrackModelUpdates request = polls.poll(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (request == null) { throw new AssertionError("Tracker did not process state " + boundary); }
                if (request.getMaxWaitMillis() > 0 && request.getLastStateIndex() >= boundary) { return; }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while observing tracker completion", failure);
        }
    }

    private static void clearCacheAndAwait(Fluxzero app) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Registration listener = app.cache().registerEvictionListener(event -> {
            if (event.getId() == null && event.getReason() == CacheEviction.Reason.manual) { completed.complete(null); }
        });
        try {
            app.cache().clear();
            completed.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while clearing the cache", failure);
        } catch (Exception failure) {
            throw new AssertionError("Cache eviction did not complete", failure);
        } finally { listener.cancel(); }
    }

    private record Session(Fluxzero reader, Fluxzero writer, ObservedClient observed) implements AutoCloseable {
        @Override public void close() {
            try (Fluxzero ignoredWriter = writer; Fluxzero ignoredReader = reader) { }
        }
    }
    private record Lookup(String id, Item item) {}
    private record Read(GetModelEvents request, GetModelEventsResult.Metric metric) {}

    private static final class ObservedClient extends SpyingClient {
        private final Client delegate;
        private final EventStoreClient events;
        private final SearchClient search;
        final List<Read> reads = new CopyOnWriteArrayList<>();
        final BlockingQueue<TrackModelUpdates> polls = new LinkedBlockingQueue<>();
        final AtomicInteger graphReads = new AtomicInteger(), searchReads = new AtomicInteger();
        final AtomicReference<Runnable> afterAliasResponse = new AtomicReference<>();

        ObservedClient(Client delegate) {
            super(delegate);
            this.delegate = delegate;
            EventStoreClient storage = delegate.getEventStoreClient();
            events = observe(EventStoreClient.class, storage, (method, arguments, result) -> {
                if (method.equals("getModelEvents")) {
                    GetModelEvents request = (GetModelEvents) arguments[0];
                    reads.add(new Read(request, ((GetModelEventsResult) result).toMetric()));
                    if (request.getRequests().stream().anyMatch(r -> ALIAS.equals(r.getModelId()))) {
                        Runnable action = afterAliasResponse.getAndSet(null);
                        if (action != null) { action.run(); }
                    }
                } else if (method.equals("getModelGraph")) { graphReads.incrementAndGet(); }
            });
            search = observe(SearchClient.class, delegate.getSearchClient(), (method, arguments, result) -> {
                if (!method.equals("close") && !method.equals("toString")) { searchReads.incrementAndGet(); }
            });
        }

        void reset() { reads.clear(); graphReads.set(0); searchReads.set(0); }
        @Override protected <T> T decorate(T component) { return component; }
        @Override public EventStoreClient getEventStoreClient() { return events; }
        @Override public SearchClient getSearchClient() { return search; }
        @Override public Client forNamespace(String namespace) {
            Client selected = delegate.forNamespace(namespace);
            return selected == delegate ? this : new ObservedClient(selected);
        }

        private <T> T observe(Class<T> type, T storage, Observation observation) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().equals("trackModelUpdates")) { polls.add((TrackModelUpdates) args[0]); }
                Object result;
                try { result = method.invoke(storage, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
                observation.accept(method.getName(), args, result);
                return result;
            }));
        }
    }

    @FunctionalInterface
    private interface Observation { void accept(String method, Object[] arguments, Object result); }

    @Model(name = "AliasCacheItem", searchable = false)
    public record Item(@EntityId String id, @Alias String alias, int value) {}
    public record PutItem(String id, String alias, int value) {
        @Apply Item apply(@Nullable Item previous) { return new Item(id, alias, value); }
    }
    public record DeleteItem(String id) { @Apply Item apply(Item previous) { return null; } }
    @Model(name = "AliasCacheOther", searchable = false)
    public record Other(@EntityId String id) {}
    public record PutOther(String id) { @Apply Other apply() { return new Other(id); } }
    @Model(name = "AliasCacheUncached", searchable = false, cached = false)
    public record Uncached(@EntityId String id, @Alias String alias) {}
    public record PutUncached(String id, String alias) { @Apply Uncached apply() { return new Uncached(id, alias); } }
}
