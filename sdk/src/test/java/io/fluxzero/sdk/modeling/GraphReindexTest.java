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
