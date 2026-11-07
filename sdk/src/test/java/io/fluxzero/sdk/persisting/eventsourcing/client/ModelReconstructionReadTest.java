/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.api.modeling.*;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.sdk.test.contracts.ModelSnapshotContract.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ModelReconstructionReadTest {
    @Test void newerSnapshotDoesNotBlockOrAdvanceTheSelectedRead() { race("snapshot"); }
    @Test void reassignmentDoesNotRedirectTheSelectedCanonicalSuffix() { race("alias"); }
    @Test void erasureCannotLeaveASnapshotAsTheOnlyAuthority() { race("erase"); }

    private void race(String change) {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            write(app, new SetAliasedCounter("canonical", "friendly", 26));
            write(app, new SetAliasedCounter("canonical", "friendly", 27));
            var events = ((LocalEventStoreClient) client.getEventStoreClient()).getMessageStore();
            long selectedState = events.getModelEvents(new GetModelEvents(List.of(
                    new ModelEventStreamRequest("canonical", -1L, 0)), ModelReadBoundary.current(), 0L)).getStateIndex();
            var changed = new AtomicBoolean();
            var searches = new java.util.concurrent.atomic.AtomicInteger();
            events.setModelSnapshotReader(client.getSearchClient(), query -> {
                if (searches.incrementAndGet() == 1) {
                    try (var hits = client.getSearchClient().search(query, 1)) {
                        assertTrue(hits.findFirst().isEmpty(), "the alias spelling has no literal snapshot");
                        return null;
                    }
                }
                assertTrue(changed.compareAndSet(false, true), "canonical snapshot selection happens once");
                switch (change) {
                    case "snapshot" -> write(app, new SetAliasedHistory("canonical", "friendly", 25));
                    case "alias" -> {
                        write(app, new SetAliasedCounter("canonical", "released", 28));
                        write(app, new SetAliasedHistory("replacement", "friendly", 25));
                    }
                    case "erase" -> app.modelRepository().deleteModel("canonical", ModelDeletionCascade.NONE).join();
                }
                try (var hits = client.getSearchClient().search(query, 1)) {
                    return hits.findFirst().map(hit -> hit.getValue()).orElse(null);
                }
            });
            var request = new GetModelReconstruction("friendly", ModelReadBoundary.current(), 10, 0L, false);
            if (change.equals("erase")) {
                assertThrows(IllegalStateException.class, () -> events.getModelReconstruction(request));
            } else {
                var result = events.getModelReconstruction(request);
                assertEquals(selectedState, result.getEvents().getStateIndex());
                assertEquals("canonical", result.getEvents().getStreams().getFirst().getModelId());
                assertEquals(26L, result.getEvents().getStreams().getFirst().getHead().getSequenceNumber());
                assertEquals(24L, ModelReconstructionLoader.sequence(result.getSnapshot()));
                assertEquals(List.of(25L, 26L), result.getEvents().getStreams().getFirst().getMemberships().stream()
                        .map(ModelEventMembership::getSequenceNumber).toList());
            }
            assertTrue(changed.get());
        }
    }

    @Test
    void primarySnapshotNeedsOnlyOneAuthoritativeEventRead() {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            var eventReads = new java.util.concurrent.atomic.AtomicInteger();
            var snapshotReads = new java.util.concurrent.atomic.AtomicInteger();
            var result = ModelReconstructionLoader.load(new GetModelReconstruction(
                    "canonical", ModelReadBoundary.current(), 10, 0L, false), request -> {
                eventReads.incrementAndGet();
                return client.getEventStoreClient().getModelEvents(request);
            }, query -> {
                snapshotReads.incrementAndGet();
                try (var hits = client.getSearchClient().search(query, 1)) {
                    var snapshot = hits.findFirst().orElseThrow().getValue();
                    // The read may miss the new snapshot; subsequent events must still reach the selected head.
                    write(app, new SetAliasedHistory("canonical", "friendly", 25));
                    return snapshot;
                }
            });
            assertEquals(1, eventReads.get());
            assertEquals(1, snapshotReads.get());
            assertEquals(24L, ModelReconstructionLoader.sequence(result.getSnapshot()));
            assertEquals(49L, result.getEvents().getStreams().getFirst().getHead().getSequenceNumber());
            assertEquals(10, result.getEvents().getPayloads().size());
            assertEquals(25L, result.getEvents().getStreams().getFirst().getMemberships().getFirst().getSequenceNumber());
        }
    }

    @Test
    void erasedPrimaryCannotBeRestoredByAnAlreadyReadSnapshot() {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            var result = ModelReconstructionLoader.load(new GetModelReconstruction(
                    "canonical", ModelReadBoundary.current(), 10, 0L, false),
                    client.getEventStoreClient()::getModelEvents, query -> {
                try (var hits = client.getSearchClient().search(query, 1)) {
                    var snapshot = hits.findFirst().orElseThrow().getValue();
                    app.modelRepository().deleteModel("canonical", ModelDeletionCascade.NONE).join();
                    return snapshot;
                }
            });
            assertNull(result.getSnapshot());
            assertTrue(result.getEvents().getPayloads().isEmpty());
            assertTrue(result.getEvents().getStreams().getFirst().getHead() == null
                    || result.getEvents().getStreams().getFirst().getHead().isDeleted());
        }
    }

    @Test
    void missingLiteralSnapshotMayRemainMissingWhenOneIsPublishedBeforeTheHeadRead() {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedCounter("canonical", "friendly", 1));
            var searches = new java.util.concurrent.atomic.AtomicInteger();
            var result = ModelReconstructionLoader.load(new GetModelReconstruction(
                    "canonical", ModelReadBoundary.current(), 100, 0L, false),
                    client.getEventStoreClient()::getModelEvents, query -> {
                assertEquals(1, searches.incrementAndGet());
                try (var hits = client.getSearchClient().search(query, 1)) { assertTrue(hits.findFirst().isEmpty()); }
                write(app, new SetAliasedHistory("canonical", "friendly", 25));
                return null;
            });
            assertNull(result.getSnapshot());
            assertEquals(24L, result.getEvents().getStreams().getFirst().getHead().getSequenceNumber());
            assertEquals(25, result.getEvents().getPayloads().size());
        }
    }

    @Test
    void numericBoundaryExcludesANewerLiteralSnapshot() {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            long boundary = client.getEventStoreClient().getModelEvents(new GetModelEvents(List.of(
                    new ModelEventStreamRequest("canonical", -1L, 0)), ModelReadBoundary.current(), 0L)).getStateIndex();
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            var result = client.getEventStoreClient().getModelReconstruction(new GetModelReconstruction(
                    "canonical", ModelReadBoundary.state(boundary, false), 100, 0L, false));
            assertEquals(boundary, result.getEvents().getStateIndex());
            assertEquals(24L, ModelReconstructionLoader.sequence(result.getSnapshot()));
            assertEquals(24L, result.getEvents().getStreams().getFirst().getHead().getSequenceNumber());
            assertTrue(result.getEvents().getPayloads().isEmpty());
        }
    }

    @Test
    void snapshotDoesNotConsumeTheEventPageAndByteProgressBudget() {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            for (int i = 26; i <= 29; i++) { write(app, new SetAliasedCounter("canonical", "friendly", i)); }
            var store = client.getEventStoreClient();
            var first = store.getModelReconstruction(new GetModelReconstruction("friendly",
                    ModelReadBoundary.current(), 2, 1L, false));
            assertEquals(24L, ModelReconstructionLoader.sequence(first.getSnapshot()));
            assertEquals(1, first.getEvents().getPayloads().size(), "one oversized event must still make progress");
            var next = store.getModelEvents(new GetModelEventsByIdentity(List.of(new ModelEventStreamRequest(
                    "canonical", first.getEvents().getStreams().getFirst().getMemberships().getLast().getSequenceNumber(), 10)),
                    ModelReadBoundary.state(first.getEvents().getStateIndex(), false), 0L,
                    GetModelEventsByIdentity.Selection.PRIMARY));
            assertEquals(3, next.getPayloads().size());
            assertEquals(28L, next.getStreams().getFirst().getMemberships().getLast().getSequenceNumber());
        }
    }

    @Test
    void explicitPrimarySelectionDoesNotFollowAnAlias() {
        var client = LocalClient.newInstance(null);
        try (Fluxzero app = app(client)) {
            write(app, new SetAliasedHistory("canonical", "friendly", 25));
            var result = client.getEventStoreClient().getModelReconstruction(new GetModelReconstruction(
                    "friendly", ModelReadBoundary.current(), 10, 0L, true));
            assertNull(result.getSnapshot());
            assertNull(result.getEvents().getStreams().getFirst().getHead());
            assertTrue(result.getEvents().getPayloads().isEmpty());
        }
    }

    private static Fluxzero app(LocalClient client) {
        var result = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
        result.registerHandlers(AliasedCounter.class); return result;
    }
    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }
}
