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
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.DocumentProjection;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
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
    void givenStoreOnlyEventsApplyIndependentModelsAndDispatchTheOriginalEventOnce() {
        AtomicInteger handled = new AtomicInteger();
        StoreOnlyStoredModelEvent event = new StoreOnlyStoredModelEvent("model-1");

        TestFixture.create(new StoreOnlyModelEventHandler(handled))
                .givenEvents(event)
                .whenApplying(ignored -> Fluxzero.<FixtureModel>loadModel("model-1").get())
                .expectResult(new FixtureModel("model-1"))
                .andThen()
                .whenApplying(ignored -> handled.get())
                .expectResult(1);
    }

    @Test
    void givenEventsPublishPayloadsWithLegacyApplyInterceptors() {
        AtomicInteger handled = new AtomicInteger();

        TestFixture.create(new LegacyInterceptedEventHandler(handled))
                .givenEvents(new LegacyInterceptedEvent())
                .whenApplying(ignored -> handled.get())
                .expectResult(1);
    }

    @Test
    @Timeout(2)
    void asynchronousFixturesAutomaticallyRegisterInterceptOnlyModelCommands() {
        TestFixture.createAsync()
                .whenCommand(new InterceptedCreateModel("model-1"))
                .expectOnlyEvents(new CreateModel("model-1"));
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
    void cachelessEventlessDocumentModelSupportsItsCompleteLifecycle() {
        String id = "document-lifecycle";

        TestFixture.create(UncachedDocument.class)
                .whenExecuting(ignored -> Fluxzero.loadGraph(id, UncachedDocument.class)
                        .update(current -> new UncachedDocument(id, 1))
                        .commit())
                .expectSuccessfulResult()
                .expectNoEvents()
                .andThen()
                .whenExecuting(ignored -> Fluxzero.loadGraph(id, UncachedDocument.class)
                        .update(current -> new UncachedDocument(id, current.value() + 1))
                        .commit())
                .expectSuccessfulResult()
                .expectNoEvents()
                .andThen()
                .whenExecuting(ignored -> Fluxzero.loadGraph(id, UncachedDocument.class)
                        .update(current -> new UncachedDocument(id, current.value() + 1))
                        .commit())
                .expectSuccessfulResult()
                .expectNoEvents()
                .andThen()
                .whenExecuting(ignored -> Fluxzero.loadGraph(id, UncachedDocument.class)
                        .update(current -> null)
                        .commit())
                .expectSuccessfulResult()
                .expectNoEvents()
                .expectTrue(ignored -> Fluxzero.loadModel(id, UncachedDocument.class).isEmpty())
                .andThen()
                .whenExecuting(ignored -> Fluxzero.loadGraph(id, UncachedDocument.class)
                        .update(current -> new UncachedDocument(id, 4))
                        .commit())
                .expectSuccessfulResult()
                .expectNoEvents()
                .andThen()
                .whenApplying(ignored -> Fluxzero.loadModel(id, UncachedDocument.class).get())
                .expectResult(new UncachedDocument(id, 4))
                .andThen()
                .whenApplying(ignored -> Fluxzero.search(UncachedDocument.class).fetchAll())
                .expectResult(List.of());
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

    private record InterceptedCreateModel(String id) {
        @InterceptApply
        CreateModel intercept() {
            return new CreateModel(id);
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

    private record StoreOnlyStoredModelEvent(String id) {
        @Apply(publicationStrategy = io.fluxzero.sdk.modeling.EventPublicationStrategy.STORE_ONLY)
        FixtureModel apply() {
            return new FixtureModel(id);
        }
    }

    private record LegacyInterceptedEvent() {
        @InterceptApply
        Object intercept() {
            return this;
        }
    }

    @Model(
            persistence = ModelPersistence.DOCUMENT,
            document = @DocumentProjection(searchable = false),
            eventPublication = EventPublication.NEVER,
            cached = false)
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

    private record StoreOnlyModelEventHandler(AtomicInteger handled) {
        @HandleEvent
        void handle(StoreOnlyStoredModelEvent ignored) {
            handled.incrementAndGet();
        }
    }

    private record LegacyInterceptedEventHandler(AtomicInteger handled) {
        @HandleEvent
        void handle(LegacyInterceptedEvent ignored) {
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
