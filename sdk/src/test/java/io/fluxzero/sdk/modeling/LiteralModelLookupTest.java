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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiteralModelLookupTest {
    @ParameterizedTest
    @ValueSource(strings = {"model", "graph", "current", "batch"})
    void literalKeysAndAliasesPreserveDefaultNormalizationAndPrecedence(String route) {
        try (Fluxzero app = app()) {
            ItemId first = new ItemId("one"), second = new ItemId("two");
            Item one = new Item(first, "daily", 1), two = new Item(second, "one", 2);
            commit(app, new Put(one));
            commit(app, new Put(two));
            String key = "model-item-one-state";
            app.apply(fc -> {
                assertEquals(one, read(route, first, true));
                assertEquals(one, read(route, "one", true));
                assertEquals(one, read(route, key, false));
                assertEquals(one, read(route, "daily", false));
                assertEquals(two, read(route, "one", false));
                // Existing single-load alias fallback also accepts a compatible literal primary key.
                assertEquals(route.equals("batch") ? null : one, read(route, key, true));
                assertNull(read(route, "missing", false));
                assertNull(read(route, first, false)); // the outer model-/-state affixes are not added
                return null;
            });
            Item distinct = new Item(new ItemId(key), "third", 3);
            commit(app, new Put(distinct));
            app.apply(fc -> {
                assertEquals(distinct, read(route, key, true));
                assertEquals(one, read(route, key, false));
                assertEquals(one, Fluxzero.loadModel(first).get());
                assertEquals(one, Fluxzero.loadGraph(first).get());
                assertEquals(one, Fluxzero.loadCurrentGraph(first).get());
                assertEquals(one, Fluxzero.loadModel(first, true).get());
                assertEquals(one, Fluxzero.loadGraph(first, true).get());
                assertEquals(one, Fluxzero.loadCurrentGraph(first, true).get());
                assertNull(Fluxzero.loadModel(first, false).get());
                assertNull(Fluxzero.loadGraph(first, false).get());
                assertNull(Fluxzero.loadCurrentGraph(first, false).get());
                assertNull(Fluxzero.loadModel((Object) first, false).get());
                assertNull(Fluxzero.loadGraph((Object) first, false).get());
                assertEquals(one, Fluxzero.loadModel((Object) key, false).get());
                assertEquals(one, Fluxzero.loadGraph((Object) key, false).get());
                return null;
            });
        }
    }

    private static Item read(String route, Object id, boolean affixes) {
        return switch (route) {
            case "model" -> Fluxzero.loadModel(id, Item.class, affixes).get();
            case "graph" -> Fluxzero.loadGraph(id, Item.class, affixes).get();
            case "current" -> Fluxzero.loadCurrentGraph(id, Item.class, affixes).get();
            default -> Fluxzero.loadModels(List.of(id), Item.class, affixes).getFirst().get();
        };
    }

    @Test
    void untypedBooleanOverloadPreservesCallTimeBoundary() {
        try (Fluxzero app = app()) {
            ItemId id = new ItemId("one");
            commit(app, new Put(new Item(id, "daily", 1)));
            Graph<?> original = app.apply(fc -> Fluxzero.loadGraph((Object) id));
            Graph<?> explicit = app.apply(fc -> Fluxzero.loadGraph((Object) id, true));
            Graph<?> literal = app.apply(fc -> Fluxzero.loadGraph((Object) "model-item-one-state", false));
            commit(app, new Put(new Item(id, "daily", 2)));
            assertEquals(new Item(id, "daily", 1), original.get());
            assertEquals(original.get(), explicit.get());
            assertEquals(original.get(), literal.get());
        }
    }

    @Test
    void fullParentScopedKeyAndAliasResolveWithoutParentArguments() {
        try (Fluxzero app = app()) {
            ItemId parent = new ItemId("parent");
            commit(app, new Put(new Item(parent, "parent-alias", 1)));
            commit(app, new PutNote("child", parent, "note-alias"));
            app.apply(fc -> {
                Graph<Note> scoped = Fluxzero.loadGraph(parent, Item.class, "child", Note.class);
                String key = scoped.id().toString();
                for (String requested : List.of(key, "note-alias")) {
                    assertEquals(scoped.get(), Fluxzero.loadModel(requested, Note.class, false).get());
                    assertEquals(scoped.get(), Fluxzero.loadGraph(requested, Note.class, false).get());
                    assertEquals(scoped.get(), Fluxzero.loadCurrentGraph(requested, Note.class, false).get());
                    assertEquals(scoped.get(), Fluxzero.loadModels(List.of(requested), Note.class, false).getFirst().get());
                }
                assertEquals(scoped.get(), scoped.current().get());
                return null;
            });
        }
    }

    @Test
    void literalGraphPinsAliasAndCurrentViewRetainsCanonicalIdentity() {
        try (Fluxzero app = app()) {
            ItemId first = new ItemId("one"), second = new ItemId("two");
            commit(app, new Put(new Item(first, "daily", 1)));
            Graph<Item> before = app.apply(fc -> Fluxzero.loadCurrentGraph("daily", Item.class, false));
            commit(app, new Put(new Item(first, "old", 2)));
            commit(app, new Put(new Item(second, "daily", 3)));
            assertEquals(new Item(first, "daily", 1), before.get());
            assertEquals(new Item(first, "old", 2), before.current().get());
            assertEquals(new Item(second, "daily", 3),
                         app.apply(fc -> Fluxzero.loadCurrentGraph("daily", Item.class, false)).get());
        }
    }

    @Test
    void literalDocumentRootsCaptureCurrentValueAndKeepAliasResolution() {
        try (Fluxzero app = app()) {
            commit(app, new PutDocument("one", "daily", 1));
            Graph<Document> before = app.apply(fc -> Fluxzero.loadCurrentGraph("daily", Document.class, false));
            commit(app, new PutDocument("one", "daily", 2));
            assertEquals(1, before.get().version());
            assertEquals(2, app.apply(fc -> Fluxzero.loadCurrentGraph("doc-one", Document.class, false)).get().version());
            assertEquals(2, app.apply(fc -> Fluxzero.loadCurrentGraph("daily", Document.class, false)).get().version());
        }
    }

    @Test
    void literalPrimaryIdentityPrecedesAnAliasOfAnotherType() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new io.fluxzero.common.application.SimplePropertySource(
                        java.util.Map.of("fluxzero.model.aliasTargets", "true")))
                .build(LocalClient.newInstance())) {
            commit(app, new Put(new Item(new ItemId("one"), "doc-one", 1)));
            commit(app, new PutDocument("one", "document-alias", 2));
            app.apply(fc -> {
                for (String route : List.of("model", "graph", "current", "batch")) {
                    assertThrows(io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException.class,
                                 () -> read(route, "doc-one", false));
                }
                return null;
            });
        }
    }

    @Test
    void customRepositoriesReceiveLiteralStringWithoutChangingExistingOverrides() {
        ModelRepository repository = mock(ModelRepository.class, CALLS_REAL_METHODS);
        Entity<Item> value = mock(Entity.class);
        when(value.id()).thenReturn("stored");
        when(value.type()).thenReturn(Item.class);
        when(repository.load("daily", Item.class)).thenReturn(value);
        assertSame(value, repository.load((Object) "daily", Item.class, false));
        assertSame(value, repository.loadCurrent("daily", Item.class, false));
        Graphs.lazy("daily", Item.class, repository, false).get();
        Graphs.lazyCurrent("daily", Item.class, repository, false).get();
        verify(repository, never()).load("model-item-daily-state", Item.class);
    }

    @Test
    void explicitTruePreservesTypedAndObjectRepositoryOverrides() {
        ModelRepository repository = mock(ModelRepository.class, CALLS_REAL_METHODS);
        ItemId id = new ItemId("one");
        Entity<Item> typed = mock(Entity.class);
        Entity<Item> untyped = mock(Entity.class);
        doReturn(typed).when(repository).load(id);
        doReturn(untyped).when(repository).load((Object) id);
        assertSame(typed, repository.load(id, true));
        assertSame(untyped, repository.load((Object) id, true));
        Fluxzero facade = mock(Fluxzero.class);
        when(facade.modelRepository()).thenReturn(repository);
        Fluxzero previous = Fluxzero.instance.get();
        try {
            Fluxzero.instance.set(facade);
            assertSame(typed, Fluxzero.loadModel(id, true));
            assertSame(untyped, Fluxzero.loadModel((Object) id, true));
        } finally { Fluxzero.instance.set(previous); }
        verify(repository, never()).load(any(Object.class), eq(Item.class), eq(true));
    }

    private static Fluxzero app() {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance());
    }
    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }
    @Model(searchable = false)
    record Item(@EntityId(prefix = "model-", postfix = "-state") ItemId itemId, @Alias String alias, int version) {}
    static class ItemId extends Id<Item> { ItemId(String value) { super(value, "item-"); } }
    record Put(Item item) { public ItemId getItemId() { return item.itemId(); } @Apply Item apply(@Nullable Item before) { return item; } }
    @Model(searchable = false)
    record Note(@EntityId(parentScoped = true) String noteId, @Parent ItemId itemId, @Alias String alias) {}
    record PutNote(String noteId, ItemId itemId, String alias) { @Apply Note apply() { return new Note(noteId, itemId, alias); } }
    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT, eventPublication = EventPublication.NEVER)
    record Document(@EntityId(prefix = "doc-") String documentId, @Alias String alias, int version) {}
    record PutDocument(String documentId, String alias, int version) {
        @Apply Document apply(@Nullable Document current) { return new Document(documentId, alias, version); }
    }
}
