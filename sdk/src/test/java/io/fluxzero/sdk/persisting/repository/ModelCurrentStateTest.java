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
package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetDocumentResult;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.client.InMemorySearchStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelCurrentStateTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsSharedStateWithoutWriterLocalReplayContractOrCachePollution(boolean cached) {
        var client = LocalClient.newInstance();
        var rejectedEvents = new AtomicInteger();
        var readerSerializer = new JacksonSerializer() {
            @Override protected boolean isKnownType(String type) {
                if (type.equals(Create.class.getName())) {
                    rejectedEvents.incrementAndGet();
                    return false;
                }
                return super.isKnownType(type);
            }
        };
        try (Fluxzero writer = app(client, new JacksonSerializer());
             Fluxzero reader = cached
                     ? DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                            .withModelCache(new io.fluxzero.common.caching.AdaptiveObjectCache(100))
                            .replaceSerializer(readerSerializer).build(client)
                     : app(client, readerSerializer)) {
            commit(writer, new Create("account", "shared-value"));
            var state = reader.apply(fc -> Fluxzero.loadCurrentModelState("account", Account.class));
            assertEquals(new Account("account", "shared-value"), state.get());
            assertTrue(state.isPresent());
            assertEquals(0, state.head().getSequenceNumber());
            assertEquals(state.head().getStateIndex(), state.stateIndex());
            assertEquals(0, rejectedEvents.get(), "Current state must not inspect historical event contracts");
            assertThrows(EventSourcingException.class, () -> reader.apply(fc -> fc.modelRepository().load("account", Account.class)));
            assertTrue(rejectedEvents.get() > 0, "Ordinary loads must still replay instead of reusing state-read values");
        }
    }

    @Test
    void distinguishesMissingDeletedAndUnavailableDocuments() {
        var client = new ControlledDocuments();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            var missing = app.modelRepository().loadCurrentState("missing", Account.class);
            assertNull(missing.head());
            assertFalse(missing.isPresent());
            commit(app, new Create("account", "first"));
            var current = app.modelRepository().loadCurrentState("account", Account.class);
            assertTrue(current.isPresent());
            GetDocumentResult first = client.getSearchClient().fetchModelDocument(new GetDocument("account", "Account", true, true));
            commit(app, new Create("account", "second"));
            doReturn(first).when(client.getSearchClient()).fetchModelDocument(any());
            var stale = assertThrows(EventSourcingException.class,
                    () -> app.modelRepository().loadCurrentState("account", Account.class));
            assertTrue(stale.getMessage().contains("no document matching head"));
            doReturn(new GetDocumentResult(0, null)).when(client.getSearchClient()).fetchModelDocument(any());
            assertThrows(EventSourcingException.class,
                    () -> app.modelRepository().loadCurrentState("account", Account.class));
            doCallRealMethod().when(client.getSearchClient()).fetchModelDocument(any());
            commit(app, new Delete("account"));
            var deleted = app.modelRepository().loadCurrentState("account", Account.class);
            assertFalse(deleted.isPresent());
            assertTrue(deleted.head().isDeleted());
        }
    }

    @Test
    void rejectsReplayOnlyModelsAndUnsupportedCustomRepositories() {
        try (Fluxzero app = app(LocalClient.newInstance(), new JacksonSerializer())) {
            var failure = assertThrows(EventSourcingException.class,
                    () -> app.modelRepository().loadCurrentState("history", ReplayOnly.class));
            assertTrue(failure.getMessage().contains("requires a maintained Model document"));
        }
        ModelRepository custom = mock(ModelRepository.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> custom.loadCurrentState("account", Account.class));
        verify(custom, never()).load(anyString(), any());
    }

    @Test
    void repeatsHeadObservationWhenDocumentAdvancesBetweenReads() {
        var client = new ControlledDocuments();
        var reads = new AtomicInteger();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            commit(app, new Create("account", "first"));
            doAnswer(invocation -> {
                if (reads.getAndIncrement() == 0) {
                    commit(app, new Create("account", "second"));
                }
                return invocation.callRealMethod();
            }).when(client.getSearchClient()).fetchModelDocument(any());
            var current = app.modelRepository().loadCurrentState("account", Account.class);
            assertEquals("second", current.get().value());
            assertEquals(1, current.head().getSequenceNumber());
            assertEquals(2, reads.get());
        }
    }

    @Test
    void boundsVerificationWhenDocumentKeepsAdvancing() {
        var client = new ControlledDocuments();
        var reads = new AtomicInteger();
        try (Fluxzero app = app(client, new JacksonSerializer())) {
            commit(app, new Create("account", "first"));
            doAnswer(invocation -> {
                commit(app, new Create("account", "version-" + reads.incrementAndGet()));
                return invocation.callRealMethod();
            }).when(client.getSearchClient()).fetchModelDocument(any());
            var failure = assertThrows(EventSourcingException.class,
                    () -> app.modelRepository().loadCurrentState("account", Account.class));
            assertTrue(failure.getMessage().contains("kept moving during verification"));
            assertEquals(8, reads.get());
        }
    }

    @Test
    void ordinaryDocumentWritesCannotPretendToBeVerifiedModelState() {
        var client = LocalClient.newInstance();
        var serializer = new JacksonSerializer();
        try (Fluxzero app = app(client, serializer)) {
            commit(app, new Create("account", "original"));
            assertEquals("original", app.modelRepository().loadCurrentState("account", Account.class).get().value());
            var changed = serializer.toDocument(new Account("account", "outside-commit"), "account", "Account",
                                                null, null, io.fluxzero.common.api.Metadata.empty());
            client.getSearchClient().index(java.util.List.of(changed), io.fluxzero.common.Guarantee.STORED, false).join();
            var failure = assertThrows(EventSourcingException.class,
                    () -> app.modelRepository().loadCurrentState("account", Account.class));
            assertTrue(failure.getCause().getMessage().contains("no longer matches"));
            commit(app, new Create("account", "next-version"));
            assertEquals("next-version", app.modelRepository().loadCurrentState("account", Account.class).get().value());
            client.getSearchClient().delete("account", "Account", io.fluxzero.common.Guarantee.STORED).join();
            assertThrows(EventSourcingException.class,
                    () -> app.modelRepository().loadCurrentState("account", Account.class));
        }
    }

    @Test
    void verifiesOuterFieldsBeforeCallingCustomDocumentSerializers() {
        var client = LocalClient.newInstance();
        var decoded = new AtomicInteger();
        var serializer = new JacksonSerializer() {
            @Override public <T> T fromDocument(io.fluxzero.common.api.search.SerializedDocument document, Class<T> type) {
                if (type == Account.class) {
                    decoded.incrementAndGet();
                    return type.cast(new Account(document.getId(), String.valueOf(document.getTimestamp())));
                }
                return super.fromDocument(document, type);
            }
        };
        try (Fluxzero writer = app(client, new JacksonSerializer()); Fluxzero reader = app(client, serializer)) {
            commit(writer, new Create("account", "original"));
            var original = client.getSearchClient().fetchModelDocument(new GetDocument("account", "Account", true))
                    .getDocument();
            assertEquals(String.valueOf(original.getTimestamp()),
                         reader.modelRepository().loadCurrentState("account", Account.class).get().value());
            var changed = original.toBuilder().timestamp(original.getTimestamp() == null ? 1L
                                                                                         : original.getTimestamp() + 1).build();
            assertSame(original.getDocument(), changed.getDocument());
            client.getSearchClient().index(java.util.List.of(changed), io.fluxzero.common.Guarantee.STORED, false).join();
            assertThrows(EventSourcingException.class,
                         () -> reader.modelRepository().loadCurrentState("account", Account.class));
            assertEquals(1, decoded.get(), "Unverified metadata must not reach the custom serializer");
        }
    }

    private Fluxzero app(LocalClient client, JacksonSerializer serializer) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().disableAutomaticModelCaching()
                .replaceSerializer(serializer).build(client);
    }

    private void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    private static class ControlledDocuments extends LocalClient {
        ControlledDocuments() { super(null); }
        @Override protected InMemorySearchStore createSearchClient() { return spy(super.createSearchClient()); }
        @Override public InMemorySearchStore getSearchClient() { return (InMemorySearchStore) super.getSearchClient(); }
    }

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT})
    record Account(@EntityId String id, String value) {}

    @Model record ReplayOnly(@EntityId String id) {}

    record Create(String id, String value) {
        @Apply Account apply() { return new Account(id, value); }
    }

    record Delete(String id) {
        @Apply Account apply(Account account) { return null; }
    }
}
