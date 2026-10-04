/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.contracts.GraphReindexContract.PutLive;
import io.fluxzero.sdk.test.contracts.GraphReindexContract.ReindexLive;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.fluxzero.sdk.test.contracts.SearchableModelGraphContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphReindexTest {
    @Test
    void repeatedEventsSkipBeforeReplayAndWrite() {
        var client = new LocalClient(null) {
            @Override protected EventStoreClient createEventStoreClient() { return spy(super.createEventStoreClient()); }
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.executeModelCommit(new Message(new PutLive("node", 1))).join();
            var graph = app.apply(fc -> Fluxzero.loadGraph("node", ReindexLive.class));
            long cutoff = (System.currentTimeMillis() - 1) << 16;
            graph.reindex();
            clearInvocations(client.getEventStoreClient());
            Tracker previous = Tracker.current.get();
            Tracker.current.set(new Tracker("bounded", MessageType.EVENT, null, ConsumerConfiguration.builder()
                    .name("bounded").maxIndexExclusive(cutoff).build(), null));
            try {
                long started = System.nanoTime();
                for (int i = 0; i < 10_000; i++) { graph.reindex(); }
                System.out.println("Reindex skip: 10000 calls in " + (System.nanoTime() - started) / 1_000_000 + " ms");
                verify(client.getEventStoreClient(), never()).getModelEvents(any());
                verify(client.getEventStoreClient(), never()).reindexModel(any());
            } finally { Tracker.current.set(previous); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void awaitAlsoWaitsWhenTheSourceWriteIsSkipped(boolean skip) throws Exception {
        var client = new LocalClient(null) {
            @Override protected EventStoreClient createEventStoreClient() { return spy(super.createEventStoreClient()); }
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.executeModelCommit(new Message(new io.fluxzero.sdk.test.contracts.GraphReindexContract.PutAwait("node", 1))).join();
            var graph = app.apply(fc -> Fluxzero.loadGraph("node",
                    io.fluxzero.sdk.test.contracts.GraphReindexContract.ReindexAwait.class));
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CompletableFuture<Void>();
            doAnswer(invocation -> {
                var result = (java.util.concurrent.CompletableFuture<io.fluxzero.common.api.modeling.ModelGraphProjectionStatus>)
                        invocation.callRealMethod();
                entered.countDown();
                return result.thenCompose(status -> release.thenApply(ignored -> status));
            }).when(client.getEventStoreClient()).awaitModelGraphProjection(
                    isA(io.fluxzero.common.api.modeling.AwaitModelGraphReindex.class));
            clearInvocations(client.getEventStoreClient());
            var reindex = java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (skip) {
                    Tracker.current.set(new Tracker("bounded", MessageType.EVENT, null, ConsumerConfiguration.builder()
                            .name("bounded").maxIndexExclusive(1L).build(), null));
                }
                try { graph.reindex(); } finally { Tracker.current.remove(); }
            });
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(reindex.isDone());
                verify(client.getEventStoreClient(), times(skip ? 0 : 1)).reindexModel(any());
            } finally { release.complete(null); }
            reindex.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"AWAIT,DEFAULT,true", "AWAIT,ASYNC,false", "ASYNC,AWAIT,true", "ASYNC,DEFAULT,false"})
    void inheritsApplicationDefaultAndConsumerOverride(GraphProjectionCompletion application,
                                                       GraphProjectionCompletion consumer, boolean waits) {
        var client = new LocalClient(null) {
            @Override protected EventStoreClient createEventStoreClient() { return spy(super.createEventStoreClient()); }
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureGraphProjectionCompletion(application).build(client)) {
            app.executeModelCommit(new Message(new io.fluxzero.sdk.test.contracts.GraphReindexContract.PutAsync("node", 1))).join();
            var graph = app.apply(fc -> Fluxzero.loadGraph("node",
                    io.fluxzero.sdk.test.contracts.GraphReindexContract.ReindexAsync.class));
            clearInvocations(client.getEventStoreClient());
            Tracker previous = Tracker.current.get();
            Tracker.current.set(new Tracker("policy", MessageType.EVENT, null, ConsumerConfiguration.builder()
                    .name("policy").graphProjectionCompletion(consumer).build(), null));
            try { graph.reindex(); } finally { Tracker.current.set(previous); }
            verify(client.getEventStoreClient(), times(waits ? 1 : 0)).awaitModelGraphProjection(
                    isA(io.fluxzero.common.api.modeling.AwaitModelGraphReindex.class));
        }
    }

    @Test
    void childReindexWaitsForItsAwaitAncestor() {
        var client = new LocalClient(null) {
            @Override protected EventStoreClient createEventStoreClient() { return spy(super.createEventStoreClient()); }
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository) app.modelRepository())
                    .configureModelTypes(() -> java.util.List.of(PolyAwaitRoot.class, PolyChild.class));
            app.executeModelCommit(new Message(new SetPolyAwaitRoot("root", new PolyAwaitRoot("root")))).join();
            app.executeModelCommit(new Message(new SetPolyChild("child", new PolyChild("child", "root", "value")))).join();
            var graph = app.apply(fc -> Fluxzero.loadGraph("child", PolyChild.class));
            clearInvocations(client.getEventStoreClient());
            graph.reindex();
            verify(client.getEventStoreClient()).awaitModelGraphProjection(argThat(request ->
                    request instanceof io.fluxzero.common.api.modeling.AwaitModelGraphReindex
                            && request.getModelIds().equals(java.util.List.of("child"))));
            app.apply(fc -> {
                assertEquals(1, Fluxzero.searchGraph(PolyAwaitRoot.class).match("value", "children/value").fetchAll().size());
                return null;
            });
        }
    }

    @Test
    void localAwaitDoesNotWaitForAnUnrelatedFailingProjection() {
        var client = new LocalClient(null) { };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.executeModelCommit(new Message(new io.fluxzero.sdk.test.contracts.GraphReindexContract.PutAwait("node", 1))).join();
            String other = app.modelRepository().registerGraphProjection(
                    io.fluxzero.sdk.test.contracts.GraphReindexContract.ReindexAsync.class, false).join().getCollection();
            var events = ((io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient)
                    client.getEventStoreClient()).getMessageStore();
            var materializer = io.fluxzero.common.reflection.ReflectionUtils
                    .<io.fluxzero.sdk.persisting.eventsourcing.client.InMemoryEventStore.ModelGraphProjectionMaterializer>
                            getFieldValue("modelGraphProjectionMaterializer", events).orElseThrow();
            events.setModelGraphProjectionMaterializer(
                    new io.fluxzero.sdk.persisting.eventsourcing.client.InMemoryEventStore.ModelGraphProjectionMaterializer() {
                        @Override
                        public Runnable materialize(io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration config,
                                                    java.util.Set<String> roots, long boundary, boolean rebuild) {
                            if (config.getCollection().equals(other)) { throw new IllegalStateException("Other projection unavailable"); }
                            return materializer.materialize(config, roots, boundary, rebuild);
                        }
                        @Override
                        public Runnable materializeSchema(io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration config,
                                                          java.util.Set<String> roots, long boundary) {
                            if (config.getCollection().equals(other)) { throw new IllegalStateException("Other projection unavailable"); }
                            return materializer.materializeSchema(config, roots, boundary);
                        }
                    });
            app.apply(fc -> {
                Fluxzero.loadGraph("node", io.fluxzero.sdk.test.contracts.GraphReindexContract.ReindexAwait.class).reindex();
                assertEquals(1, Fluxzero.searchGraph(io.fluxzero.sdk.test.contracts.GraphReindexContract.ReindexAwait.class)
                        .match(1, "version").fetchAll().size());
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reindexesCommittedStateInsideAnUncommittedGraphUpdate(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.given(fc -> fc.executeModelCommit(new Message(new PutLive("node", 1))).join())
                .whenExecuting(fc -> {
                    var graph = Fluxzero.loadGraph("node", ReindexLive.class);
                    graph.updateAndGet(current -> {
                        var staged = current.update(value -> new ReindexLive(value.id(), 99));
                        staged.reindex();
                        var source = fc.client().getSearchClient().fetchModelDocument(new GetDocument("node",
                                EntityMetadata.of(ReindexLive.class).modelSourceDocumentCollection("").orElseThrow(), true, true));
                        assertEquals(1, fc.documentStore().getSerializer().fromDocument(source.getDocument(), ReindexLive.class).version());
                        return staged;
                    });
                    assertEquals(99, Fluxzero.loadGraph("node", ReindexLive.class).get().version());
                }).expectSuccessfulResult().expectNoErrors();
    }
}
