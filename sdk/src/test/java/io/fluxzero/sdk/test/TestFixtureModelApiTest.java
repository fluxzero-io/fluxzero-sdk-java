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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.common.api.Metadata;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFixtureModelApiTest {

    @Test
    void synchronousFixtureDistinguishesModelEventFromSourceCommandWithSameMessageId() {
        AtomicInteger handled = new AtomicInteger();
        CreateModel command = new CreateModel("model-1");

        TestFixture.create(new ModelEventHandler(handled))
                .whenCommand(command)
                .expectOnlyEvents(command)
                .expectTrue(ignored -> handled.get() == 1);
    }

    @Test
    @Timeout(2)
    void synchronousStoredEventHandlersObserveTheCommittedModel() {
        AtomicReference<FixtureModel> observed = new AtomicReference<>();

        TestFixture.create(new ModelReadingEventHandler(observed))
                .whenCommand(new CreateModel("model-1"))
                .expectOnlyEvents(new CreateModel("model-1"))
                .expectTrue(ignored -> new FixtureModel("model-1").equals(observed.get()));
    }

    @Test
    @Timeout(2)
    void synchronousStoredDeleteEventHandlersReceiveTheDeletedModelHistory() {
        AtomicReference<FixtureModel> previous = new AtomicReference<>();

        TestFixture.create(new ModelDeletionEventHandler(previous))
                .givenCommands(new CreateModel("model-1"))
                .whenCommand(new DeleteFixtureModel("model-1"))
                .expectOnlyEvents(new DeleteFixtureModel("model-1"))
                .expectTrue(ignored -> new FixtureModel("model-1").equals(previous.get()));
    }

    @Test
    @Timeout(2)
    void asynchronousStoredDeleteEventHandlersPreferTheExactModelCommitBoundary() {
        AtomicReference<FixtureModel> previous = new AtomicReference<>();

        TestFixture.createAsync(new ModelDeletionEventHandler(previous))
                .givenCommands(new CreateModel("model-1"))
                .whenCommand(new DeleteFixtureModel("model-1"))
                .expectOnlyEvents(new DeleteFixtureModel("model-1"))
                .expectTrue(ignored -> new FixtureModel("model-1").equals(previous.get()));
    }

    @Test
    @Timeout(2)
    void ordinaryEventsResolveIndependentModelsFromOneCurrentContext() {
        AtomicReference<FixtureModel> observed = new AtomicReference<>();

        TestFixture.createAsync(new OrdinaryEventModelHandler(observed))
                .givenCommands(new CreateModel("model-1"))
                .whenEvent(new OrdinaryEvent("model-1"))
                .expectNoErrors()
                .expectTrue(ignored -> new FixtureModel("model-1").equals(observed.get()));
    }

    @Test
    @Timeout(2)
    void publishedLegacyEventsBuildModelsWithoutRepublishingAndExposeTheirExactState() {
        List<Integer> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        LegacyIncrement first = new LegacyIncrement("legacy-model", 1);
        LegacyIncrement second = new LegacyIncrement("legacy-model", 2);

        TestFixture.createAsync(new LegacyModelMigrationHandler(observed))
                .whenEvent(first)
                .expectNoEvents()
                .expectNoErrors()
                .andThen()
                .whenEvent(second)
                .expectNoEvents()
                .expectNoErrors()
                .andThen()
                .whenApplying(ignored -> Fluxzero.<LegacyModel>loadModel("legacy-model").get())
                .expectResult(new LegacyModel("legacy-model", 3))
                .expectTrue(ignored -> observed.equals(List.of(1, 3)));
    }

    @Test
    @Timeout(2)
    void documentBackedMigrationContinuesFromStagingAndAdoptsWithoutRewritingLegacyState() {
        String modelId = "legacy-document";
        LegacyDocumentModel legacy = new LegacyDocumentModel(modelId, 3);

        TestFixture.createAsync(new LegacyDocumentMigrationHandler())
                .givenDocument(
                        legacy, modelId,
                        LegacyDocumentModel.class)
                .whenEvent(new LegacyDocumentIncrement(modelId, 1))
                .expectNoEvents()
                .expectNoErrors()
                .andThen()
                .whenApplying(ignored -> Fluxzero.getDocument(
                        modelId, LegacyDocumentModel.class).orElseThrow())
                .expectResult(legacy)
                .andThen()
                .whenEvent(new LegacyDocumentIncrement(modelId, 2))
                .expectNoEvents()
                .expectNoErrors()
                .andThen()
                .whenApplying(ignored -> {
                    Fluxzero.adoptModelMigrations().join();
                    return Fluxzero.<LegacyDocumentModel>loadModel(
                            modelId).get();
                })
                .expectResult(legacy);
    }

    @Test
    @Timeout(2)
    void documentBackedMigrationRejectsAValueThatDiffersFromLegacyState() {
        String modelId = "different-legacy-document";
        LegacyDocumentModel legacy = new LegacyDocumentModel(modelId, 3);

        TestFixture.createAsync(new LegacyDocumentMigrationHandler())
                .givenDocument(
                        legacy, modelId,
                        LegacyDocumentModel.class)
                .whenEvent(new LegacyDocumentIncrement(modelId, 4))
                .expectNoEvents()
                .expectNoErrors()
                .andThen()
                .whenApplying(ignored -> Fluxzero.adoptModelMigrations().join())
                .expectExceptionalResult(
                        java.util.concurrent.CompletionException.class)
                .andThen()
                .whenApplying(ignored -> Fluxzero.getDocument(
                        modelId, LegacyDocumentModel.class).orElseThrow())
                .expectResult(legacy);
    }

    @Test
    @Timeout(2)
    void adoptsEveryStagedDocumentIncludingModelsWithoutLegacyState() {
        String existingId = "existing-legacy-document";
        String newId = "new-migrated-document";

        TestFixture.createAsync(new LegacyDocumentMigrationHandler())
                .givenDocument(
                        new LegacyDocumentModel(existingId, 1), existingId,
                        LegacyDocumentModel.class)
                .whenEvent(new LegacyDocumentIncrement(existingId, 1))
                .expectNoErrors()
                .andThen()
                .whenEvent(new LegacyDocumentIncrement(newId, 2))
                .expectNoErrors()
                .andThen()
                .whenApplying(ignored -> Fluxzero.adoptModelMigrations().join())
                .expectResult(2)
                .andThen()
                .whenApplying(ignored -> List.of(
                        Fluxzero.<LegacyDocumentModel>loadModel(existingId).get(),
                        Fluxzero.<LegacyDocumentModel>loadModel(newId).get()))
                .expectResult(List.of(
                        new LegacyDocumentModel(existingId, 1),
                        new LegacyDocumentModel(newId, 2)))
                .andThen()
                .whenApplying(ignored -> Fluxzero.adoptModelMigrations().join())
                .expectResult(0);
    }

    @Test
    void givenEventsApplyIndependentModelsAndDispatchTheStoredEventOnce() {
        AtomicInteger handled = new AtomicInteger();
        StoredModelEvent event = new StoredModelEvent("model-1");

        TestFixture.create(new ModelEventHandler(handled))
                .givenEvents(event)
                .whenApplying(ignored -> Fluxzero.<FixtureModel>loadModel("model-1").get())
                .expectResult(new FixtureModel("model-1"))
                .andThen()
                .whenApplying(ignored -> handled.get())
                .expectResult(1);
    }

    @Test
    void givenEventsUpdateTheSameUncachedDocumentModelSynchronously() {
        TestFixture.create()
                .givenEvents(new UpdateUncachedDocument("document-1", 1),
                             new UpdateUncachedDocument("document-1", 2))
                .whenApplying(ignored -> Fluxzero.loadModel(
                        "document-1", UncachedDocument.class).get())
                .expectResult(new UncachedDocument("document-1", 3));
    }

    @Test
    void givenAppliedEventsAutomaticallyUseIndependentModelApplies() {
        StoredModelEvent event = new StoredModelEvent("model-1");

        TestFixture.create()
                .givenAppliedEvents("model-1", FixtureModel.class, event)
                .whenApplying(ignored -> Fluxzero.<FixtureModel>loadModel("model-1").get())
                .expectResult(new FixtureModel("model-1"));
    }

    @Test
    void legacyEntityLoadFallsBackToAnIndependentModel() {
        TestFixture.create()
                .givenCommands(new CreateModel("model-1"))
                .whenApplying(ignored -> Fluxzero.<FixtureModel>loadEntity("model-1").get())
                .expectResult(new FixtureModel("model-1"));
    }

    @Test
    void legacyTypedAggregateLoadAppliesToAnIndependentModel() {
        FixtureModelId id = new FixtureModelId("model-1");

        TestFixture.create()
                .given(ignored -> Fluxzero.loadAggregate(id).apply(new StoredModelEvent("model-1")))
                .whenApplying(ignored -> Fluxzero.<FixtureModel>loadModel(id).get())
                .expectResult(new FixtureModel("model-1"));
    }

    @Test
    void manualAssertAndApplyInheritsTheHandledMessageContext() {
        ContextualCreate command = new ContextualCreate("model-1");

        TestFixture.create(new ContextualHandler())
                .whenCommand(new Message(command, Metadata.of("tenant", "alpha")))
                .expectNoErrors()
                .andThen()
                .whenApplying(ignored -> Fluxzero.<ContextualModel>loadModel("model-1").get())
                .expectResult(new ContextualModel("model-1", "alpha"))
                .andThen()
                .whenApplying(ignored -> Fluxzero.<ContextualModel>loadModel("model-2").get())
                .expectResult(new ContextualModel("model-2", "alpha"));
    }

    @Test
    @Timeout(5)
    void consecutiveFixturesCompleteHandlersAwaitingDependentModelCommands() {
        TestFixture.create()
                .givenCommands(new CreateFixtureRoot("root"),
                               new CreateFixtureLocation("root", "location"),
                               new ImportFixtureModels("root", "location", "parent", "child"));

        TestFixture.create()
                .givenCommands(new CreateFixtureRoot("root"),
                               new CreateFixtureLocation("root", "location"))
                .whenCommand(new ImportFixtureModels("root", "location", "parent", "child"))
                .expectNoErrors();
    }

    @Test
    void parentScopedModelsKeepFunctionalIdsAndRemainUniqueBelowTheirParents() {
        TestFixture.create()
                .givenCommands(new CreateFixtureRoot("root-a"),
                               new CreateFixtureRoot("root-b"),
                               new PutScopedFixtureChild("root-a", "shared"),
                               new PutScopedFixtureChild("root-b", "shared"))
                .whenApplying(ignored -> Fluxzero.loadGraph("root-a", FixtureRoot.class)
                        .find("shared", ScopedFixtureChild.class)
                        .map(graph -> graph.get().rootId())
                        .orElse(null))
                .expectResult("root-a")
                .andThen()
                .whenApplying(ignored -> Fluxzero.loadModel(
                        "root-b", FixtureRoot.class,
                        "shared", ScopedFixtureChild.class).get())
                .expectResult(new ScopedFixtureChild("shared", "root-b"));
    }

    @Test
    void modelEventsUseTheSameCompiledStoredEventPipeline() {
        TestFixture fixture = TestFixture.create();
        Fluxzero fluxzero = mock(Fluxzero.class);
        List<Message> events = List.of(new Message("created"));
        when(fluxzero.executeStoredModelEvent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        fixture.applyEvents("model-1", TestModel.class, fluxzero, events);

        verify(fluxzero).executeStoredModelEvent(argThat(event ->
                "model-1".equals(event.getMetadata().get(Entity.AGGREGATE_ID_METADATA_KEY))
                && TestModel.class.getName().equals(
                        event.getMetadata().get(Entity.AGGREGATE_TYPE_METADATA_KEY))));
    }

    @Model
    private record TestModel(@EntityId String id) {
    }

    @Model
    private record FixtureModel(@EntityId String id) {
    }

    private static class FixtureModelId extends Id<FixtureModel> {
        private FixtureModelId(String id) {
            super(id, FixtureModel.class);
        }
    }

    private record CreateModel(String id) {
        @Apply
        FixtureModel apply() {
            return new FixtureModel(id);
        }
    }

    private record StoredModelEvent(String id) {
        @AssertLegal
        void mustNotRunForStoredEvents() {
            throw new AssertionError("Stored events must not run command assertions");
        }

        @Apply
        FixtureModel apply() {
            return new FixtureModel(id);
        }
    }

    @Model(eventSourced = false, cached = false, searchable = true)
    private record UncachedDocument(@EntityId String id, int value) {
    }

    private record UpdateUncachedDocument(String id, int delta) {
        @Apply
        UncachedDocument apply(@Nullable UncachedDocument current) {
            return new UncachedDocument(id, (current == null ? 0 : current.value()) + delta);
        }
    }

    private record DeleteFixtureModel(String id) {
        @Apply
        FixtureModel apply(FixtureModel model) {
            return null;
        }
    }

    @Model
    private record FixtureRoot(@EntityId String rootId) {
    }

    @Model
    private record FixtureLocation(
            @EntityId String locationId,
            @Parent(value = FixtureRoot.class, pathInParent = "locations") String rootId) {
    }

    @Model
    private record FixtureParent(
            @EntityId String parentId,
            @Parent(value = FixtureLocation.class, pathInParent = "parents") String locationId) {
    }

    @Model
    private record FixtureChild(
            @EntityId String childId,
            @Parent(value = FixtureParent.class, pathInParent = "children") String parentId) {
    }

    @Model
    private record ScopedFixtureChild(
            @EntityId(parentScoped = true) String childId,
            @Parent(value = FixtureRoot.class, pathInParent = "scopedChildren") String rootId) {
    }

    private record CreateFixtureRoot(String rootId) {
        @Apply
        FixtureRoot apply() {
            return new FixtureRoot(rootId);
        }
    }

    private record CreateFixtureLocation(String rootId, String locationId) {
        @Apply
        FixtureLocation apply() {
            return new FixtureLocation(locationId, rootId);
        }
    }

    private record PutScopedFixtureChild(String rootId, String childId) {
        @Apply
        ScopedFixtureChild apply() {
            return new ScopedFixtureChild(childId, rootId);
        }
    }

    private record CreateFixtureParent(String rootId, String locationId, String parentId) {
        @AssertLegal
        void assertRoot(FixtureRoot root) {
        }

        @AssertLegal
        void assertLocation(FixtureLocation location) {
        }

        @Apply
        FixtureParent apply() {
            return new FixtureParent(parentId, locationId);
        }
    }

    private record CreateFixtureChildren(String rootId, String locationId, String parentId, String childId) {
        @AssertLegal
        void assertRoot(FixtureRoot root) {
        }

        @AssertLegal
        void assertLocation(FixtureLocation location) {
        }

        @InterceptApply
        CreateFixtureChild intercept(FixtureParent parent) {
            return new CreateFixtureChild(parent.parentId(), childId);
        }
    }

    private record CreateFixtureChild(String parentId, String childId) {
        @Apply
        FixtureChild apply() {
            return new FixtureChild(childId, parentId);
        }
    }

    private record ImportFixtureModels(String rootId, String locationId, String parentId, String childId) {
        @HandleCommand
        CompletableFuture<?> handle() {
            return CompletableFuture.allOf(
                    Fluxzero.sendCommand(new CreateFixtureParent(rootId, locationId, parentId)),
                    Fluxzero.sendCommand(new CreateFixtureChildren(rootId, locationId, parentId, childId)));
        }
    }

    private record ModelEventHandler(AtomicInteger handled) {
        @HandleEvent
        void handle(CreateModel ignored) {
            handled.incrementAndGet();
        }

        @HandleEvent
        void handle(StoredModelEvent ignored) {
            handled.incrementAndGet();
        }
    }

    private record ModelReadingEventHandler(AtomicReference<FixtureModel> observed) {
        @HandleEvent
        void handle(CreateModel event) {
            observed.set(Fluxzero.<FixtureModel>loadModel(event.id()).get());
        }
    }

    private record ModelDeletionEventHandler(AtomicReference<FixtureModel> previous) {
        @HandleEvent
        void handle(DeleteFixtureModel event, Entity<FixtureModel> model) {
            previous.set(model.previous().get());
        }
    }

    private record OrdinaryEvent(String id) {
    }

    private record OrdinaryEventModelHandler(AtomicReference<FixtureModel> observed) {
        @HandleEvent
        void handle(OrdinaryEvent event, Entity<FixtureModel> model) {
            observed.set(model.get());
        }
    }

    @Model
    private record LegacyModel(@EntityId String id, int value) {
    }

    private record LegacyIncrement(String id, int delta) {
        @Apply
        LegacyModel apply(@Nullable LegacyModel model) {
            return new LegacyModel(id, (model == null ? 0 : model.value()) + delta);
        }
    }

    private record LegacyModelMigrationHandler(List<Integer> observed) {
        @HandleEvent
        void handle(LegacyIncrement event) {
            Fluxzero.migratePublishedEvent();
            observed.add(Fluxzero.<LegacyModel>loadModel(event.id()).get().value());
        }
    }

    @Model(eventSourced = false, cached = false, searchable = true)
    private record LegacyDocumentModel(
            @EntityId String id,
            int value) {
    }

    private record LegacyDocumentIncrement(
            String id,
            int delta) {
        @Apply
        LegacyDocumentModel apply(
                @Nullable LegacyDocumentModel model) {
            return new LegacyDocumentModel(
                    id,
                    (model == null ? 0 : model.value())
                    + delta);
        }
    }

    private static class LegacyDocumentMigrationHandler {
        @HandleEvent
        void handle(LegacyDocumentIncrement ignored) {
            Fluxzero.migratePublishedEvent();
        }
    }

    @Model
    private record ContextualModel(@EntityId String id, String tenant) {
    }

    private record ContextualCreate(String id) {
        @Apply(automaticHandling = io.fluxzero.sdk.modeling.AutomaticModelHandling.DISABLED)
        ContextualModel apply(Metadata metadata) {
            return new ContextualModel(id, metadata.get("tenant"));
        }
    }

    private static class ContextualHandler {
        @HandleCommand
        @LocalHandler
        void handle(ContextualCreate command) {
            Fluxzero.assertAndApply(command);
            Fluxzero.assertAndApply(new ContextualCreate("model-2"));
        }
    }
}
