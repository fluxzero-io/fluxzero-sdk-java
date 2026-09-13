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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ModelLifecycleContractTest {
    private final RootId rootId = new RootId("lifecycle-root");
    private final ChildId childId = new ChildId("lifecycle-child");
    private final DocumentId documentId = new DocumentId("lifecycle-document");

    private TestFixture fixture(boolean async, Object... handlers) {
        return async ? TestFixture.createAsync(handlers) : TestFixture.create(handlers);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateCreationIsRejected(boolean async) {
        fixture(async).givenCommands(new CreateRoot(rootId))
                .whenCommand(new CreateRoot(rootId))
                .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION)
                .expectNoEvents();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingUpdateIsRejected(boolean async) {
        fixture(async).whenCommand(new UpdateChild(childId, 2))
                .expectExceptionalResult(Entity.NOT_FOUND_EXCEPTION)
                .expectNoEvents();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optionalParentIdentityCanBeNull(boolean async) {
        fixture(async).whenCommand(new CreateOptionalChild(childId, null))
                .expectSuccessfulResult().expectEvents(new CreateOptionalChild(childId, null))
                .expectThat(fc -> assertEquals(new Child(childId, null, 1), Fluxzero.loadModel(childId).get()))
                .expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dynamicModelResultsRetainReadRevisions(boolean async) {
        ChildId other = new ChildId("other-child");
        fixture(async).givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId),
                                     new CreateChild(other, rootId), new UpdateChild(other, 5))
                .whenCommand(new IncrementChildren(rootId))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertEquals(2, Fluxzero.loadModel(childId).get().value());
                    fc.cache().clear();
                    assertEquals(2, Fluxzero.loadModel(childId).get().value());
                    assertEquals(6, Fluxzero.loadModel(other).get().value());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullableCurrentStateMakesUpsertExplicit(boolean async) {
        fixture(async).givenCommands(new UpsertChild(childId, rootId, 1))
                .whenCommand(new UpsertChild(childId, rootId, 2))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(2, Fluxzero.loadModel(childId).get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitCompatibilityOptOutIsAnIdempotentNoOp(boolean async) {
        fixture(async).givenCommands(new CreateChild(childId, rootId))
                .whenCommand(new CreateChildIfAbsent(childId, rootId))
                .expectSuccessfulResult().expectNoEvents().expectNoErrors()
                .expectThat(fc -> assertEquals(1, Fluxzero.loadModel(childId).get().value()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompatibleCreationRejectsTheWholeCommit(boolean async) {
        fixture(async).givenCommands(new CreateRoot(rootId))
                .whenCommand(new CreateFamily(rootId, childId))
                .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION).expectNoEvents()
                .expectThat(fc -> assertNull(Fluxzero.loadModel(childId).get()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directGraphDeletionNotifiesCascadedChildren(boolean async) {
        List<Graph<Child>> changes = new CopyOnWriteArrayList<>();
        fixture(async, childObserver(changes))
                .givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new DeleteRootGraph(rootId))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(1, changes.size());
                    fc.cache().clear();
                    assertTrue(changes.getFirst().isEmpty());
                    assertEquals(1, changes.getFirst().previous().get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commandMetadataCannotInjectCascadeReferences(boolean async) {
        List<Graph<Root>> changes = new CopyOnWriteArrayList<>();
        fixture(async, new Object() {
            @HandleEvent void changed(Graph<Root> root) { changes.add(root); }
        }).whenCommand(new Message(new CreateRoot(rootId), Metadata.of(ModelEventMetadata.CASCADE_SUBSTEPS, "999")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(1, changes.size()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directGraphDeletionWithOrdinaryApplyRetainsItsCascadeAnchor(boolean async) {
        List<Graph<Child>> changes = new CopyOnWriteArrayList<>();
        fixture(async, childObserver(changes))
                .givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new DeleteRootGraphAndCreate(rootId, new RootId("other-root")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(1, changes.size());
                    assertTrue(changes.getFirst().isEmpty());
                    assertEquals(1, changes.getFirst().previous().get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatedThenCascadedChildHasSeparateEventBoundaries(boolean async) {
        List<Graph<Child>> changes = new CopyOnWriteArrayList<>();
        fixture(async, childObserver(changes))
                .givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new UpdateThenDeleteRoot(rootId, childId))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(2, changes.size());
                    fc.cache().clear();
                    assertEquals(2, changes.getFirst().get().value());
                    assertEquals(1, changes.getFirst().previous().get().value());
                    assertTrue(changes.getLast().isEmpty());
                    assertEquals(2, changes.getLast().previous().get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cascadeAnchorFollowsAnOrdinaryEnvelopeWithTheSameMessageId(boolean async) {
        List<Graph<Child>> changes = new CopyOnWriteArrayList<>();
        fixture(async, childObserver(changes))
                .givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new DeleteRootGraphAndReplacePayload(rootId, new RootId("other-root")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(1, changes.size());
                    assertTrue(changes.getFirst().isEmpty());
                    assertEquals(1, changes.getFirst().previous().get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cascadeAlsoNotifiesSurvivingNonOwningParent(boolean async) {
        RootId otherRoot = new RootId("non-owning-root");
        List<Graph<Root>> changes = new CopyOnWriteArrayList<>();
        fixture(async, new Object() {
            @HandleEvent void changed(Graph<Root> graph) { changes.add(graph); }
        }).givenCommands(new CreateRoot(rootId), new CreateRoot(otherRoot),
                         new CreateShared(new SharedId("shared"), rootId, otherRoot))
                .whenApplying(fc -> {
                    changes.clear();
                    return Fluxzero.sendCommandAndWait(new DeleteRoot(rootId));
                })
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(2, changes.size());
                    Graph<Root> surviving = changes.stream().filter(g -> g.id().toString().equals(otherRoot.toString()))
                            .findFirst().orElseThrow();
                    fc.cache().clear();
                    assertTrue(surviving.isPresent());
                    assertEquals(1, surviving.previous().children(Shared.class).size());
                    assertTrue(surviving.children(Shared.class).isEmpty());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonOwningDeletionDoesNotStealTheCascadeCause(boolean async) {
        RootId other = new RootId("non-owning-root");
        List<Object> causes = new CopyOnWriteArrayList<>();
        fixture(async, new Object() {
            @HandleEvent void changed(Graph<Shared> graph) {
                if (graph.isEmpty()) { causes.add(DeserializingMessage.getCurrent().getPayload()); }
            }
        }).givenCommands(new CreateRoot(rootId), new CreateRoot(other),
                         new CreateShared(new SharedId("shared"), rootId, other))
                .whenCommand(new DeleteBothRoots(rootId, other))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(List.of(new DeleteRoot(rootId)), causes));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentOnlyDoesNotPromiseHistoricalModelState(boolean async) {
        fixture(async).givenCommands(new PutDocumentOnly("document-only", 1))
                .whenCommand(new PutDocumentOnly("document-only", 2))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    Graph<DocumentOnly> graph = Fluxzero.loadGraph("document-only", DocumentOnly.class);
                    assertEquals(2, graph.get().value());
                    assertNull(graph.previous());
                });
    }

    @Test
    void oldCascadeDeliveryFindsKnownAncestorsWithoutResolvingUnknownChildren() {
        var client = LocalClient.newInstance(null);
        RootId other = new RootId("retained-root");
        try (Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            writer.apply(fc -> {
                for (Object command : List.of(new CreateRoot(rootId), new CreateRoot(other),
                        new CreateShared(new SharedId("foreign-shared"), rootId, other), new DeleteRoot(rootId))) {
                    fc.executeModelCommit(new Message(command)).join();
                }
                return null;
            });
            var stored = client.getEventStoreClient().getModelEvents(new GetModelEvents(
                    List.of(new ModelEventStreamRequest(rootId.toString(), -1, 100)), ModelReadBoundary.current(), 0))
                    .getPayloads().getLast().getEvent();
            // Advance both roots after the event. Redelivery must retain the original deletion and relation boundary.
            writer.apply(fc -> {
                fc.executeModelCommit(new Message(new CreateRoot(rootId))).join();
                fc.executeModelCommit(new Message(new CreateShared(new SharedId("later-child"), rootId, other))).join();
                return null;
            });
            List<Graph<Root>> changes = new CopyOnWriteArrayList<>();
            var reader = new TestFixture(DefaultFluxzero.builder(), fc -> List.of(new Object() {
                @HandleEvent void changed(Graph<Root> graph) {
                    if (java.util.Objects.equals(stored.getMetadata().get("$modelCommitId"),
                            DeserializingMessage.getCurrent().getMetadata().get("$modelCommitId"))) {
                        changes.add(graph);
                    }
                }
            }), client, true) {};
            ((DefaultModelRepository) reader.getFluxzero().modelRepository()).configureModelTypes(() -> List.of(Root.class));
            reader.whenEvent(new Message(new DeleteRoot(rootId), stored.getMetadata()))
                    .expectNoErrors().expectThat(fc -> {
                        assertEquals(2, changes.size());
                        fc.cache().clear();
                        Graph<Root> deleted = changes.stream().filter(g -> g.id().toString().equals(rootId.toString()))
                                .findFirst().orElseThrow();
                        assertTrue(deleted.isEmpty());
                        assertEquals(1, deleted.previous().children("owned", false).size());
                        Graph<Root> surviving = changes.stream().filter(g -> g.id().toString().equals(other.toString()))
                                .findFirst().orElseThrow();
                        assertTrue(surviving.isPresent());
                        var before = surviving.previous().children("shared", false);
                        assertEquals(1, before.size());
                        assertTrue(before.getFirst().knownType().isEmpty());
                        assertTrue(surviving.children("shared", false).isEmpty());
                    });
        }
    }

    private Object childObserver(List<Graph<Child>> changes) {
        return new Object() {
            @HandleEvent void changed(Graph<Child> graph) {
                if (graph.previous() != null) { changes.add(graph); }
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dynamicGraphResultsRetainReadRevisions(boolean async) {
        fixture(async).givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new IncrementChildGraphs(rootId))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(2, Fluxzero.loadModel(childId).get().value());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consecutiveDynamicSubstepsSeeEarlierChildWrites(boolean async) {
        fixture(async).givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new IncrementChildrenTwice(rootId))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(3, Fluxzero.loadModel(childId).get().value());
                    fc.cache().clear();
                    assertEquals(3, Fluxzero.loadModel(childId).get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryUpdateThenDynamicWriteKeepsOriginalRevision(boolean async) {
        fixture(async).givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new UpdateThenIncrementChildren(rootId, childId))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(3, Fluxzero.loadModel(childId).get().value());
                    fc.cache().clear();
                    assertEquals(3, Fluxzero.loadModel(childId).get().value());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryDeleteThenDynamicScanKeepsTheTombstone(boolean async) {
        fixture(async).givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId))
                .whenCommand(new DeleteThenIncrementChildren(rootId, childId))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertNull(Fluxzero.loadModel(childId).get());
                    assertTrue(Fluxzero.loadGraph(rootId).childModels(Child.class).isEmpty());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cascadeNotifiesChildHandlersWithPreviousState(boolean async) {
        List<Graph<Child>> children = new CopyOnWriteArrayList<>();
        List<Graph<Document>> documents = new CopyOnWriteArrayList<>();
        fixture(async, new Object() {
            @HandleEvent void childChanged(Graph<Child> graph) {
                if (graph.isEmpty()) { children.add(graph); }
            }
            @HandleEvent void documentChanged(Graph<Document> graph) {
                if (graph.isEmpty()) { documents.add(graph); }
            }
        }).givenCommands(new CreateRoot(rootId), new CreateChild(childId, rootId),
                         new CreateDocument(documentId, rootId))
                .whenCommand(new DeleteRoot(rootId))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertEquals(1, children.size(), "event-sourced child deletion");
                    assertEquals(1, documents.size(), "document child deletion");
                    fc.cache().clear();
                    assertEquals(new Child(childId, rootId, 1), children.getFirst().previous().get());
                    assertEquals(new Document(documentId, rootId, 1), documents.getFirst().previous().get());
                    assertNull(Fluxzero.loadModel(childId).get());
                    assertNull(Fluxzero.loadModel(documentId).get());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentHandlersHavePreviousStateAfterCacheClear(boolean async) {
        List<List<Integer>> complete = new CopyOnWriteArrayList<>();
        List<List<Integer>> ordinary = new CopyOnWriteArrayList<>();
        fixture(async, new Object() {
            @HandleEvent void changed(Graph<Document> graph) {
                if (graph.previous() != null) {
                    Fluxzero.get().cache().clear();
                    complete.add(List.of(graph.previous().get().value(), graph.get().value()));
                }
            }
        }, new Object() {
            @HandleEvent void updated(UpdateDocument event, Graph<Document> graph) {
                Fluxzero.get().cache().clear();
                ordinary.add(List.of(graph.previous().get().value(), graph.get().value()));
            }
        }).givenCommands(new CreateRoot(rootId), new CreateDocument(documentId, rootId))
                .whenCommand(new UpdateDocument(documentId, 2))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(List.of(List.of(1, 2)), complete);
                    assertEquals(List.of(List.of(1, 2)), ordinary);
                });
    }

    @Model record Root(@EntityId RootId rootId) {}
    @Model record Child(@EntityId ChildId childId, @Parent(pathInParent = "children") RootId rootId, int value) {}
    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT})
    record Document(@EntityId DocumentId documentId, @Parent(pathInParent = "documents") RootId rootId, int value) {}

    record CreateRoot(RootId rootId) {
        @Apply Root apply() { return new Root(rootId); }
    }
    record DeleteRoot(RootId rootId) {
        @Apply Root apply(Root root) { return null; }
    }
    record CreateChild(ChildId childId, RootId rootId) {
        @Apply Child apply() { return new Child(childId, rootId, 1); }
    }
    record UpsertChild(ChildId childId, RootId rootId, int value) {
        @Apply Child apply(@Nullable Child current) { return new Child(childId, rootId, value); }
    }
    record CreateChildIfAbsent(ChildId childId, RootId rootId) {
        @Apply(disableCompatibilityCheck = true) Child apply() { return new Child(childId, rootId, 999); }
    }
    record CreateFamily(RootId rootId, ChildId childId) {
        @Apply Root root() { return new Root(rootId); }
        @Apply Child child() { return new Child(childId, rootId, 1); }
    }
    record DeleteRootGraph(RootId rootId) {
        @InterceptApply Graph<Root> apply(Graph<Root> root) { return root.delete(); }
    }
    record DeleteRootGraphAndCreate(RootId rootId, RootId otherRootId) {
        @InterceptApply List<?> apply(Graph<Root> root) {
            return List.of(root.delete(), new CreateRoot(otherRootId));
        }
    }
    record UpdateThenDeleteRoot(RootId rootId, ChildId childId) {
        @InterceptApply List<?> apply() { return List.of(new UpdateChild(childId, 2), new DeleteRoot(rootId)); }
    }
    record DeleteRootGraphAndReplacePayload(RootId rootId, RootId otherRootId) {
        @InterceptApply List<?> apply(Graph<Root> root) {
            return List.of(root.delete(), DeserializingMessage.getCurrent().toMessage()
                    .withPayload(new CreateRoot(otherRootId)));
        }
    }
    record DeleteBothRoots(RootId rootId, RootId otherRootId) {
        @InterceptApply List<DeleteRoot> apply() { return List.of(new DeleteRoot(rootId), new DeleteRoot(otherRootId)); }
    }
    @Model(persistence = ModelPersistence.DOCUMENT)
    record DocumentOnly(@EntityId String documentOnlyId, int value) {}
    record PutDocumentOnly(String documentOnlyId, int value) {
        @Apply DocumentOnly apply(@Nullable DocumentOnly current) { return new DocumentOnly(documentOnlyId, value); }
    }
    @Model record Shared(@EntityId SharedId sharedId, @Parent(pathInParent = "owned") RootId rootId,
                         @Parent(pathInParent = "shared", deleteOnParentDeletion = false) RootId otherRootId) {}
    record CreateShared(SharedId sharedId, RootId rootId, RootId otherRootId) {
        @Apply Shared apply() { return new Shared(sharedId, rootId, otherRootId); }
    }
    record CreateOptionalChild(ChildId childId, RootId rootId) {
        @AssertLegal void check(@Nullable Root root) { assertNull(root); }
        @Apply Child apply() { return new Child(childId, rootId, 1); }
    }
    record UpdateChild(ChildId childId, int value) {
        @Apply Child apply(Child child) { return new Child(childId, child.rootId(), value); }
    }
    record IncrementChildren(RootId rootId) {
        @Apply List<Child> apply(Graph<Root> root) {
            return root.childModels(Child.class).stream()
                    .map(child -> new Child(child.childId(), child.rootId(), child.value() + 1)).toList();
        }
    }
    record UpdateThenIncrementChildren(RootId rootId, ChildId childId) {
        @InterceptApply List<?> apply() {
            return List.of(new UpdateChild(childId, 2), new IncrementChildren(rootId));
        }
    }
    record DeleteChild(ChildId childId) {
        @Apply Child apply(Child child) { return null; }
    }
    record DeleteThenIncrementChildren(RootId rootId, ChildId childId) {
        @InterceptApply List<?> apply() {
            return List.of(new DeleteChild(childId), new IncrementChildren(rootId));
        }
    }
    record IncrementChildGraphs(RootId rootId) {
        @InterceptApply List<Graph<Child>> apply(Graph<Root> root) {
            return root.children(Child.class).stream()
                    .map(graph -> graph.update(child -> new Child(child.childId(), child.rootId(), child.value() + 1)))
                    .toList();
        }
    }
    record IncrementChildrenTwice(RootId rootId) {
        @InterceptApply List<IncrementChildren> apply() {
            return List.of(new IncrementChildren(rootId), new IncrementChildren(rootId));
        }
    }
    record CreateDocument(DocumentId documentId, RootId rootId) {
        @Apply Document apply() { return new Document(documentId, rootId, 1); }
    }
    record UpdateDocument(DocumentId documentId, int value) {
        @Apply Document apply(Document document) { return new Document(documentId, document.rootId(), value); }
    }
    static class RootId extends Id<Root> { RootId(String id) { super(id); } }
    static class ChildId extends Id<Child> { ChildId(String id) { super(id); } }
    static class DocumentId extends Id<Document> { DocumentId(String id) { super(id); } }
    static class SharedId extends Id<Shared> { SharedId(String id) { super(id); } }
}
