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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.root.RootConsumerModelCommand;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.NonFinal;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ModelCommitHandlerIntegrationTest {

    @Test
    void explicitGraphUpdateCreatesAndReplaysAnIndependentModel() {
        AccountId accountId = new AccountId("direct-graph-create");

        TestFixture.create(Account.class)
                .whenExecuting(ignored -> Fluxzero.loadGraph(accountId)
                        .update(current -> new Account(accountId, 41))
                        .commit())
                .expectNoEvents()
                .expectThat(fluxzero -> {
                    assertEquals(1L, fluxzero.eventStore()
                            .getEvents(accountId.toString()).count());
                    fluxzero.cache().clear();
                    assertEquals(new Account(accountId, 41),
                                 fluxzero.modelRepository().load(accountId).get());
                });
    }

    @Test
    void explicitGraphUpdateCommitsAnExistingIndependentModel() {
        AccountId accountId = new AccountId("direct-graph-update");

        TestFixture.create(Account.class)
                .givenCommands(new CreateAccount(accountId, 41))
                .whenExecuting(ignored -> Fluxzero.loadGraph(accountId)
                        .update(current -> new Account(accountId, current.balance() + 1))
                        .commit())
                .expectNoEvents()
                .expectThat(fluxzero -> {
                    assertEquals(2L, fluxzero.eventStore()
                            .getEvents(accountId.toString()).count());
                    fluxzero.cache().clear();
                    assertEquals(new Account(accountId, 42),
                                 fluxzero.modelRepository().load(accountId).get());
                });
    }

    @Test
    void graphAssertAndApplyRetainsTheSelectedIdentityAcrossInterception() {
        AccountId payloadId = new AccountId("targeted-payload");
        AccountId selectedId = new AccountId("targeted-selected");
        ApplyTargetedCredit event = new ApplyTargetedCredit(payloadId, 1);

        TestFixture.create(Account.class)
                .givenCommands(
                        new CreateAccount(payloadId, 10),
                        new CreateAccount(selectedId, 20))
                .whenExecuting(ignored -> Fluxzero.loadGraph(selectedId)
                        .assertAndApply(new TargetedCredit(payloadId, 1)))
                .expectEvents(event)
                .expectThat(fluxzero -> {
                    assertEquals(new Account(payloadId, 10),
                                 fluxzero.modelRepository().load(payloadId).get());
                    assertEquals(new Account(selectedId, 21),
                                 fluxzero.modelRepository().load(selectedId).get());
                });
    }

    @Test
    void graphAssertAndApplyPrefetchRetainsTheSelectedIdentityForAnIdlessInterceptorOutput() {
        AccountId selectedId = new AccountId("targeted-idless-selected");
        ApplyTargetedCreditWithoutId event = new ApplyTargetedCreditWithoutId(2);

        TestFixture.create(Account.class)
                .givenCommands(new CreateAccount(selectedId, 20))
                .whenExecuting(ignored -> Fluxzero.loadGraph(selectedId)
                        .assertAndApply(new TargetedCreditWithoutId(2)))
                .expectEvents(event)
                .expectThat(fluxzero -> assertEquals(
                        new Account(selectedId, 22),
                        fluxzero.modelRepository().load(selectedId).get()));
    }

    @Test
    void explicitInterceptorMessageStartsANewRoutingBoundary() {
        AccountId routedId = new AccountId("targeted-rerouted-payload");
        AccountId selectedId = new AccountId("targeted-rerouted-selected");
        ApplyTargetedCredit event = new ApplyTargetedCredit(routedId, 1);

        TestFixture.create(Account.class)
                .givenCommands(
                        new CreateAccount(routedId, 10),
                        new CreateAccount(selectedId, 20))
                .whenExecuting(ignored -> Fluxzero.loadGraph(selectedId)
                        .assertAndApply(new ReroutedTargetedCredit(routedId, 1)))
                .expectEvents(event)
                .expectThat(fluxzero -> {
                    assertEquals(new Account(routedId, 11),
                                 fluxzero.modelRepository().load(routedId).get());
                    assertEquals(new Account(selectedId, 20),
                                 fluxzero.modelRepository().load(selectedId).get());
                });
    }

    @Test
    void graphAssertAndApplyRetainsTheSelectedSubtypeForBaseModelApply() {
        IncrementBaseCounter event = new IncrementBaseCounter("payload", 2);

        TestFixture.create(SpecialCounter.class)
                .givenCommands(new CreateSpecialCounter("selected", 3))
                .whenExecuting(ignored -> Fluxzero.loadGraph("selected", SpecialCounter.class)
                        .assertAndApply(event))
                .expectEvents(event)
                .expectThat(fluxzero -> assertEquals(
                        SpecialCounter.builder().counterId("selected").value(5).marker("special").build(),
                        fluxzero.modelRepository().load("selected", SpecialCounter.class).get()));
    }

    @Test
    void baseModelApplyPersistsTheConcreteTypeOfANewModel() {
        TestFixture.create(BaseCounter.class)
                .whenCommand(new CreateSpecialThroughBase("new-special", 4))
                .expectThat(fluxzero -> assertEquals(
                        SpecialCounter.builder()
                                .counterId("new-special").value(4).marker("special").build(),
                        fluxzero.modelRepository()
                                .load("new-special", SpecialCounter.class).get()));
    }

    @Test
    void typedSubtypeIdNarrowsABaseModelApplyTarget() {
        SpecialCounterId counterId = new SpecialCounterId("typed-special");

        TestFixture.create(BaseCounter.class)
                .whenCommand(new CreateTypedSpecial(counterId, 6))
                .expectThat(fluxzero -> assertEquals(
                        SpecialCounter.builder()
                                .counterId(counterId.getId()).value(6).marker("special").build(),
                        fluxzero.modelRepository().load(counterId).get()));
    }

    @Test
    void explicitEmptySubtypeGraphRunsBaseModelInterceptorsAndApply() {
        SpecialCounterId counterId = new SpecialCounterId("explicit-new-special");
        CreateTypedSpecial event = new CreateTypedSpecial(counterId, 7);

        TestFixture.create(BaseCounter.class)
                .whenExecuting(ignored -> Fluxzero.loadGraph(counterId)
                        .assertAndApply(event))
                .expectEvents(event)
                .expectThat(fluxzero -> assertEquals(
                        SpecialCounter.builder()
                                .counterId(counterId.getId()).value(7).marker("special").build(),
                        fluxzero.modelRepository().load(counterId).get()));
    }

    @Test
    void graphAssertAndApplyReloadsAnAffixedRepositoryIdentityExactlyOnce() {
        AffixedRootId rootId = new AffixedRootId("targeted-affixed");

        TestFixture.create(AffixedRoot.class)
                .givenCommands(new CreateAffixedRoot(rootId))
                .whenExecuting(ignored -> Fluxzero.loadGraph(rootId)
                        .assertAndApply(new TouchAffixedRoot()))
                .expectThat(ignored -> assertEquals(
                        new AffixedRoot(rootId),
                        Fluxzero.loadGraph(rootId).get()));
    }

    @Test
    void graphAssertAndApplyReloadsAParentScopedRepositoryIdentityWithoutParentContext() {
        FamilyRootId rootId = new FamilyRootId("targeted-scoped");

        TestFixture.create(ScopedNote.class)
                .givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateScopedNote("note", rootId, 1))
                .whenExecuting(ignored -> Fluxzero.loadGraph(
                                rootId, FamilyRoot.class,
                                "note", ScopedNote.class)
                        .assertAndApply(new IncrementScopedNote(2)))
                .expectThat(ignored -> assertEquals(
                        new ScopedNote("note", rootId, 3),
                        Fluxzero.loadGraph(
                                rootId, FamilyRoot.class,
                                "note", ScopedNote.class).get()));
    }

    @Test
    void explicitGraphUpdateCommitsAnExistingDocumentModel() {
        InventoryId inventoryId = new InventoryId("direct-document-update");

        TestFixture.create(Inventory.class)
                .givenCommands(new CreateInventory(inventoryId, 41))
                .whenExecuting(ignored -> Fluxzero.loadGraph(inventoryId)
                        .update(current -> new Inventory(
                                inventoryId, current.available() + 1))
                        .commit())
                .expectNoEvents()
                .expectThat(fluxzero -> {
                    fluxzero.cache().clear();
                    assertEquals(new Inventory(inventoryId, 42),
                                 fluxzero.modelRepository().load(inventoryId).get());
                });
    }

    @Test
    void explicitGraphCommitReloadsFixedIdsByRepositoryIdentity() {
        TestFixture.create(FixedDocument.class)
                .whenExecuting(ignored -> Fluxzero.loadGraph(new FixedDocumentId())
                        .update(current -> new FixedDocument(42))
                        .commit())
                .expectNoEvents()
                .expectThat(fluxzero -> assertEquals(
                        new FixedDocument(42),
                        fluxzero.modelRepository().load(new FixedDocumentId()).get()));
    }

    @Test
    void graphOnlyHandlersObserveEveryAffectedRootWithCompletePreviousGraph() {
        FamilyRootId firstRootId = new FamilyRootId("change-first");
        FamilyRootId secondRootId = new FamilyRootId("change-second");
        FamilyChildId childId = new FamilyChildId("change-child");
        List<Graph<FamilyRoot>> events = new CopyOnWriteArrayList<>();
        List<Graph<FamilyRoot>> notifications = new CopyOnWriteArrayList<>();

        TestFixture.create()
                .registerHandlers(
                        new Object() {
                            @HandleEvent
                            void handle(Graph<FamilyRoot> graph) {
                                events.add(graph);
                            }
                        },
                        new Object() {
                            @HandleNotification
                            void handle(Graph<FamilyRoot> graph) {
                                notifications.add(graph);
                            }
                        })
                .givenCommands(
                        new CreateFamilyRoot(firstRootId, "first"),
                        new CreateFamilyRoot(secondRootId, "second"),
                        new CreateFamilyChild(childId, firstRootId, "child"))
                .whenApplying(fluxzero -> {
                    events.clear();
                    notifications.clear();
                    return fluxzero.commandGateway().sendAndWait(
                            new MoveFamilyChild(childId, secondRootId));
                })
                .expectThat(ignored -> {
                    assertMovedChildGraphs(
                            events, firstRootId, secondRootId, childId);
                    assertMovedChildGraphs(
                            notifications, firstRootId, secondRootId, childId);
                });
    }

    @Test
    void graphOnlyHandlerDeduplicatesCascadeTargetsAndRetainsDeletedGraphHistory() {
        FamilyRootId rootId = new FamilyRootId("change-delete");
        FamilyChildId firstChild = new FamilyChildId("change-delete-first");
        FamilyChildId secondChild = new FamilyChildId("change-delete-second");
        List<Graph<FamilyRoot>> events = new CopyOnWriteArrayList<>();

        TestFixture.create()
                .registerHandlers(new Object() {
                    @HandleEvent
                    void handle(Graph<FamilyRoot> graph) {
                        events.add(graph);
                    }
                })
                .givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateFamilyChild(firstChild, rootId, "first"),
                        new CreateFamilyChild(secondChild, rootId, "second"),
                        new CreateFamilyGrandchild(
                                new FamilyGrandchildId("change-delete"),
                                firstChild, secondChild))
                .whenApplying(fluxzero -> {
                    events.clear();
                    return fluxzero.commandGateway().sendAndWait(
                            new DeleteFamilyRoot(rootId));
                })
                .expectThat(ignored -> {
                    assertEquals(1, events.size());
                    Graph<FamilyRoot> deleted = events.getFirst();
                    assertTrue(deleted.isEmpty());
                    assertEquals(
                            Set.of(firstChild, secondChild),
                            Set.copyOf(deleted.previous()
                                               .childModels(
                                                       "children",
                                                       FamilyChild.class)
                                               .stream()
                                               .map(FamilyChild::familyChildId)
                                               .toList()));
                });
    }

    @Test
    void interceptorStagesOrdinaryGraphUpdateInTheSameCommit() {
        FamilyRootId rootId = new FamilyRootId("staged-update");
        FamilyChildId childId = new FamilyChildId("staged-update");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(rootId, "before-root"),
                        new CreateFamilyChild(childId, rootId, "before-child"))
                .whenCommand(new RenameFamily(rootId, childId))
                .expectThat(fluxzero -> {
                    ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                            fluxzero.modelRepository()).invalidateModels(
                            List.of(rootId.toString(), childId.toString()));
                    Graph<FamilyRoot> graph = fluxzero.modelRepository()
                            .loadGraph(rootId);
                    assertEquals("after-root", graph.get().name());
                    assertEquals(
                            "after-child",
                            graph.childModels("children", FamilyChild.class)
                                    .getFirst().name());
                    assertTrue(fluxzero.eventStore()
                                       .getEvents(childId.toString())
                                       .anyMatch(event -> event.getPayload()
                                               instanceof DirectModelUpdate));
                });
    }

    private static void assertMovedChildGraphs(
            List<Graph<FamilyRoot>> graphs,
            FamilyRootId firstRootId,
            FamilyRootId secondRootId,
            FamilyChildId childId) {
        assertEquals(2, graphs.size());
        Graph<FamilyRoot> first = graphs.stream()
                .filter(graph -> firstRootId.equals(
                        graph.get().familyRootId()))
                .findFirst().orElseThrow();
        Graph<FamilyRoot> second = graphs.stream()
                .filter(graph -> secondRootId.equals(
                        graph.get().familyRootId()))
                .findFirst().orElseThrow();

        assertTrue(first.childModels("children", FamilyChild.class).isEmpty());
        assertEquals(
                List.of(childId),
                first.previous().childModels("children", FamilyChild.class)
                        .stream().map(FamilyChild::familyChildId).toList());
        assertEquals(
                List.of(childId),
                second.childModels("children", FamilyChild.class)
                        .stream().map(FamilyChild::familyChildId).toList());
        assertTrue(second.previous()
                           .childModels("children", FamilyChild.class)
                           .isEmpty());
    }

    @Test
    void payloadApplyCommitsModelAndDirectSearchBeforeCommandCompletion() {
        TestFixture fixture = TestFixture.create();
        AccountId accountId = new AccountId("1");

        fixture.whenCommand(new CreateAccount(accountId, 42))
                .expectNoResult()
                .expectThat(fluxzero -> assertEquals(
                        new CreateAccount(accountId, 42),
                        fluxzero.eventStore().getEvents(accountId.toString())
                                .findFirst().orElseThrow().getPayload()))
                .expectTrue(fluxzero -> fluxzero.modelRepository()
                        .load(accountId).get() != null)
                .expectTrue(fluxzero -> fluxzero.documentStore()
                        .search(Account.class).fetchAll(Account.class)
                                .equals(java.util.List.of(new Account(accountId, 42))));
    }

    @Test
    void entityIdAffixesApplyToModelLoadsAndParentRelationships() {
        AffixedRootId rootId = new AffixedRootId("one");
        AffixedChildId childId = new AffixedChildId("one");

        TestFixture.create()
                .givenCommands(
                        new CreateAffixedRoot(rootId),
                        new CreateAffixedChild(childId, rootId))
                .whenCommand(new CreateAffixedCompanion(rootId, "online"))
                .expectThat(fluxzero -> {
                    Entity<AffixedRoot> root = fluxzero.modelRepository().load(rootId);
                    assertEquals(
                            new AffixedRoot(rootId),
                            root.get());
                    assertEquals(
                            new AffixedRoot(rootId),
                            fluxzero.modelRepository().load((Object) "one", AffixedRoot.class).get());
                    assertEquals(
                            new AffixedChild(childId, rootId),
                            fluxzero.modelRepository().load(childId).get());
                    assertEquals(
                            List.of(new AffixedChild(childId, rootId)),
                            fluxzero.modelRepository().loadGraph(rootId)
                                    .childModels("children", AffixedChild.class));
                    assertEquals(
                            new AffixedCompanion(rootId, "online"),
                            fluxzero.modelRepository().load(rootId, AffixedCompanion.class).get());
                    assertEquals(
                            List.of(new AffixedCompanion(rootId, "online")),
                            fluxzero.modelRepository().loadGraph(rootId)
                                    .childModels("companion", AffixedCompanion.class));
                    assertEquals("one", rootId.getFunctionalId());
                    assertEquals("root-one", rootId.toString());
                    assertEquals(
                            Set.of("move-root-one-state"),
                            root.relationships().stream()
                                    .map(Relationship::getEntityId)
                                    .collect(java.util.stream.Collectors.toSet()));
                    assertEquals(root, root.getEntity(rootId, AffixedRoot.class).orElseThrow());
                });
    }

    @Test
    void modelDefaultSkipsAnEventWhenApplyDoesNotModifyState() {
        AccountId accountId = new AccountId("default-if-modified");

        TestFixture.create()
                .givenCommands(new CreateAccount(accountId, 42))
                .whenCommand(new TouchAccount(accountId))
                .expectNoResult()
                .expectNoEvents();
    }

    @Test
    void applyMayAlwaysPublishAnUnchangedModelEvent() {
        AccountId accountId = new AccountId("explicit-always");
        AlwaysTouchAccount command = new AlwaysTouchAccount(accountId);

        TestFixture.create()
                .givenCommands(new CreateAccount(accountId, 42))
                .whenCommand(command)
                .expectNoResult()
                .expectEvents(command);
    }

    @Test
    void dedicatedModelCacheCanBeConfiguredOrDisabled() {
        InspectableCache configured =
                new InspectableCache();
        AccountId cached =
                new AccountId(
                        "dedicated-cache");
        TestFixture.create(
                        DefaultFluxzero.builder()
                                .withModelCache(
                                        configured))
                .whenCommand(
                        new CreateAccount(
                                cached, 42))
                .expectTrue(fluxzero ->
                                    configured.size()
                                    > 0);

        InspectableCache disabled =
                new InspectableCache();
        AccountId uncached =
                new AccountId(
                        "disabled-cache");
        TestFixture.create(
                        DefaultFluxzero.builder()
                                .withModelCache(
                                        disabled)
                                .disableAutomaticModelCaching())
                .whenCommand(
                        new CreateAccount(
                                uncached, 42))
                .expectTrue(fluxzero ->
                                    disabled.size()
                                    == 0);
    }

    @Test
    void trackedEventHandlerInjectsAffectedModelValueAndEntity() {
        AtomicReference<List<Object>> handled =
                new AtomicReference<>();
        AccountId accountId =
                new AccountId("event-parameter");

        TestFixture.createAsync(
                        new AffectedModelEventHandler(handled))
                .whenCommand(
                        new CreateAccount(accountId, 42))
                .expectTrue(fluxzero -> {
                    List<Object> expected = List.of(
                            new Account(accountId, 42),
                            new Account(accountId, 42));
                    long deadline = System.nanoTime()
                                    + Duration.ofSeconds(5)
                                            .toNanos();
                    while (!expected.equals(handled.get())
                           && System.nanoTime() < deadline) {
                        Thread.sleep(10L);
                    }
                    return expected.equals(handled.get());
                });
    }

    @Test
    void assertLegalInjectsUnrelatedModelFromPayloadProperty() {
        TestFixture fixture = TestFixture.create();
        InventoryId inventoryId =
                new InventoryId("assert-dependency");
        OrderId orderId =
                new OrderId("assert-dependency");

        fixture.givenCommands(
                        new CreateInventory(inventoryId, 5))
                .whenCommand(
                        new CreateCheckedOrder(
                                orderId, inventoryId, 5))
                .expectTrue(fluxzero ->
                                    new Order(orderId, 5)
                                            .equals(
                                                    fluxzero.modelRepository()
                                                            .load(orderId)
                                                            .get()))
                .andThen()
                .whenCommand(
                        new CreateCheckedOrder(
                                new OrderId("rejected"),
                                inventoryId, 99))
                .expectExceptionalResult(
                        IllegalStateException.class)
                .expectNoEvents();
    }

    @Test
    void existingCommandHandlerWinsOverModelCommitFallback() {
        TestFixture fixture = TestFixture.create(new ExplicitHandler());
        AccountId accountId = new AccountId("2");

        fixture.whenCommand(new ExplicitlyHandledCreate(accountId))
                .expectResult("explicit")
                .expectNoEvents()
                .expectTrue(fluxzero -> fluxzero.modelRepository()
                        .load(accountId).get() == null);
    }

    @Test
    void explicitCommandHandlerMayAssertAndApplyTheSamePayloadDirectly() {
        ExplicitDelegatingHandler.invocations.set(0);
        TestFixture fixture =
                TestFixture.create(new ExplicitDelegatingHandler());
        AccountId accountId = new AccountId("direct");
        ExplicitlyDelegatedCreate command =
                new ExplicitlyDelegatedCreate(accountId, 63);

        fixture.whenCommand(command)
                .expectResult("delegated")
                .expectThat(fluxzero -> {
                    assertEquals(1,
                                 ExplicitDelegatingHandler.invocations.get());
                    var event = fluxzero.eventStore()
                            .getEvents(accountId.toString())
                            .findFirst().orElseThrow();
                    assertEquals(command, event.getPayload());
                    assertEquals("direct",
                                 event.getMetadata().get("model-commit"));
                    assertEquals(new Account(accountId, 63),
                                 fluxzero.modelRepository()
                                         .load(accountId).get());
                    assertEquals(List.of(new Account(accountId, 63)),
                                 fluxzero.documentStore()
                                         .search(Account.class)
                                         .fetchAll(Account.class));
                });
    }

    @Test
    void directAssertAndApplyOutsideHandlerReturnsAfterCommit() {
        AccountId accountId = new AccountId("outside-handler");
        TestFixture.create()
                .whenApplying(fluxzero -> {
                    Fluxzero.assertAndApply(
                            new CreateAccount(accountId, 29));
                    return fluxzero.modelRepository()
                            .load(accountId).get();
                })
                .expectResult(new Account(accountId, 29));
    }

    @Test
    void directAssertAndApplyAsyncCompletesWithDurableModelCommit() {
        AccountId accountId = new AccountId("async-outside-handler");
        ASYNC_COMMIT_METADATA.set(null);
        TestFixture.createAsync()
                .whenApplying(fluxzero -> Fluxzero.assertAndApplyAsync(
                                new CreateAsyncAccount(accountId, 31),
                                Metadata.of("async-context", "captured"))
                        .thenApply(ignored -> fluxzero.modelRepository()
                                .load(accountId).get()))
                .expectResult(new Account(accountId, 31))
                .expectThat(ignored -> assertEquals(
                        "captured", ASYNC_COMMIT_METADATA.get()));
    }

    @Test
    void directBulkAssertAndApplyAsyncCommitsIndependentUpdates() {
        AccountId first = new AccountId("bulk-direct-first");
        AccountId second = new AccountId("bulk-direct-second");
        TestFixture.createAsync()
                .whenApplying(fluxzero -> Fluxzero.assertAndApplyAllAsync(List.of(
                                new CreateAccount(first, 41),
                                new CreateAccount(second, 43)))
                        .thenApply(ignored -> List.of(
                                fluxzero.modelRepository().load(first).get(),
                                fluxzero.modelRepository().load(second).get())))
                .expectResult(List.of(
                        new Account(first, 41),
                        new Account(second, 43)));
    }

    @Test
    void oneCollectionApplyUpdatesAllInjectedGraphsAtomicallyAndInOrder() {
        AccountId first = new AccountId("collection-first");
        AccountId second = new AccountId("collection-second");

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(first, 10),
                        new CreateAccount(second, 20))
                .whenApplying(fluxzero -> {
                    Fluxzero.assertAndApply(new UpdateAccounts(
                            List.of(second, first), 3));
                    fluxzero.cache().clear();
                    return List.of(
                            fluxzero.modelRepository().load(second).get(),
                            fluxzero.modelRepository().load(first).get());
                })
                .expectResult(List.of(
                        new Account(second, 23),
                        new Account(first, 13)))
                .expectThat(fluxzero -> {
                    assertEquals(2L, fluxzero.eventStore()
                            .getEvents(first.toString()).count());
                    assertEquals(2L, fluxzero.eventStore()
                            .getEvents(second.toString()).count());
                });
    }

    @Test
    void collectionApplyCreatesModelsAndRejectsAnExistingIdentity() {
        AccountId first = new AccountId("created-first");
        AccountId second = new AccountId("created-second");
        /*
         * Conflict resolution deliberately completes asynchronously. Use the production-like fixture so this test
         * waits for that completion instead of relying on a common-pool task winning a zero-timeout race.
         */
        TestFixture fixture = TestFixture.createAsync();

        fixture.whenApplying(ignored ->
                        Fluxzero.assertAndApplyAsync(new CreateAccounts(
                                List.of(first, second), 5)))
                .expectTrue(fluxzero ->
                                    new Account(first, 5).equals(
                                            fluxzero.modelRepository()
                                                    .load(first).get()))
                .expectTrue(fluxzero ->
                                    new Account(second, 5).equals(
                                            fluxzero.modelRepository()
                                                    .load(second).get()))
                .andThen()
                .whenApplying(ignored ->
                        Fluxzero.assertAndApplyAsync(new CreateAccounts(
                                List.of(new AccountId("third"), second), 9)))
                .expectExceptionalResult(
                        ModelCommitConflictException.class)
                .expectTrue(fluxzero ->
                                    fluxzero.modelRepository()
                                            .load(new AccountId("third"))
                                            .isEmpty())
                .expectTrue(fluxzero ->
                                    new Account(second, 5).equals(
                                            fluxzero.modelRepository()
                                                    .load(second).get()));
    }

    @Test
    void objectCollectionApplyReplaysEveryRuntimeValidatedModelType() {
        AccountId accountId = new AccountId("mixed-account");
        CollectionPeerId peerId =
                new CollectionPeerId("mixed-peer");

        TestFixture.create()
                .whenCommand(new CreateMixedCollection(
                        accountId, peerId))
                .expectThat(fluxzero -> {
                    fluxzero.cache().clear();
                    assertEquals(
                            new Account(accountId, 7),
                            fluxzero.modelRepository()
                                    .load(accountId).get());
                    assertEquals(
                            new CollectionPeer(peerId, "created"),
                            fluxzero.modelRepository()
                                    .load(peerId).get());
                });
    }

    @Test
    void directAssertLegalInterceptsAndValidatesWithoutApplying() {
        AccountId accountId = new AccountId("validate-only");
        TestFixture.create()
                .givenCommands(new CreateAccount(accountId, 29))
                .whenApplying(fluxzero -> {
                    List<String> observations =
                            new java.util.ArrayList<>();
                    Fluxzero.assertLegal(
                            new ValidateOnlyAccount(
                                    accountId, 29,
                                    observations),
                            Metadata.of(
                                    "validation", "direct"));
                    return new ValidationResult(
                            observations,
                            fluxzero.modelRepository()
                                    .load(accountId).get(),
                            fluxzero.eventStore()
                                    .getEvents(accountId)
                                    .count());
                })
                .expectResult(new ValidationResult(
                        List.of(
                                "intercept-29",
                                "assert-29-direct"),
                        new Account(accountId, 29),
                        1L));
    }

    @Test
    void directAssertAndApplyWithoutApplyValidatesAndReturns() {
        AccountId accountId = new AccountId("assert-and-apply-validation-only");
        TestFixture.create()
                .givenCommands(new CreateAccount(accountId, 29))
                .whenApplying(fluxzero -> {
                    List<String> observations =
                            new java.util.ArrayList<>();
                    Fluxzero.assertAndApply(
                            new ValidateOnlyAccount(
                                    accountId, 29,
                                    observations),
                            Metadata.of(
                                    "validation", "direct"));
                    return new ValidationResult(
                            observations,
                            fluxzero.modelRepository()
                                    .load(accountId).get(),
                            fluxzero.eventStore()
                                    .getEvents(accountId)
                                    .count());
                })
                .expectResult(new ValidationResult(
                        List.of(
                                "intercept-29",
                                "assert-29-direct"),
                        new Account(accountId, 29),
                        1L));
    }

    @Test
    void senderOnlyApplicationCanDispatchCommandWithoutLocalApply() {
        RemoteOnlyCommand command =
                new RemoteOnlyCommand("remote");

        TestFixture.create()
                .whenApplying(fluxzero -> {
                    Fluxzero.sendAndForgetCommand(command);
                    return null;
                })
                .expectCommands(command)
                .expectNoEvents();
    }

    @Test
    void unregisteredModelCommandIsNotHandledLocally() {
        AccountId accountId =
                new AccountId("unregistered");
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null))) {
            fluxzero.commandGateway()
                    .sendAndForget(
                            STORED,
                            new Message(new CreateAccount(
                                    accountId, 42)))
                    .join();

            assertFalse(fluxzero.modelRepository()
                                .load(accountId)
                                .isPresent());
        }
    }

    @Test
    void directAssertAndApplyPropagatesApplyFailureWithoutCommit() {
        TestFixture fixture =
                TestFixture.create(new ExplicitFailingDelegatingHandler());
        AccountId accountId = new AccountId("direct-failure");

        fixture.whenCommand(new ExplicitlyDelegatedFailure(accountId))
                .expectExceptionalResult(IllegalStateException.class)
                .expectNoEvents()
                .expectTrue(fluxzero -> fluxzero.modelRepository()
                        .load(accountId).isEmpty());
    }

    @Test
    void asyncHandlerResultRemainsUnchangedAfterDirectAssertAndApply() {
        TestFixture fixture =
                TestFixture.createAsync(new AsyncDelegatingHandler());
        AccountId accountId = new AccountId("direct-async");
        ASYNC_COMMIT_METADATA.set(null);

        fixture.whenCommand(new Message(
                        new AsyncDelegatedRequest(accountId),
                        Metadata.of("async-context", "inherited")))
                .expectResult("async")
                .expectTrue(fluxzero -> new Account(accountId, 71)
                        .equals(fluxzero.modelRepository()
                                        .load(accountId).get()))
                .expectThat(ignored -> assertEquals(
                        "inherited", ASYNC_COMMIT_METADATA.get()));
    }

    @Test
    void nestedCommandMayUseDirectAssertAndApply() {
        TestFixture fixture = TestFixture.create(
                new NestedDelegatingHandler(),
                new ExplicitDelegatingHandler());
        AccountId accountId = new AccountId("direct-nested");

        fixture.whenCommand(new NestedDelegatedCreate(accountId))
                .expectResult("delegated")
                .expectTrue(fluxzero -> new Account(accountId, 83)
                        .equals(fluxzero.modelRepository()
                                        .load(accountId).get()));
    }

    @Test
    void registeredModelMayHandleCommandAsReceiver() {
        TestFixture fixture = TestFixture.create(ReceiverAccount.class);
        ReceiverAccountId accountId = new ReceiverAccountId("3");

        fixture.givenCommands(new CreateReceiverAccount(accountId, "before"))
                .whenCommand(new RenameReceiverAccount(accountId, "after"))
                .expectThat(fluxzero -> assertEquals(
                        2L, fluxzero.eventStore()
                                .getEvents(accountId.toString()).count()))
                .expectTrue(fluxzero -> {
                    ReceiverAccount value =
                            fluxzero.modelRepository().load(accountId).get();
                    return value != null && value.name().equals("after");
                });
    }

    @Test
    void registeredStaticModelApplyCanBeReplayed() {
        TestFixture fixture =
                TestFixture.create(
                        StaticCreatedModel.class);

        fixture.whenCommand(
                        new CreateStaticModel(
                                "static-created"))
                .expectTrue(fluxzero ->
                                    new StaticCreatedModel(
                                            "static-created",
                                            "created")
                                            .equals(
                                                    fluxzero.modelRepository()
                                                            .load(
                                                                    "static-created",
                                                                    StaticCreatedModel.class)
                                                            .get()));
    }

    @Test
    void payloadCreationThenModelInstanceApplyCommitsOneReplayableTransition() {
        IntegratedPhasedModelId id =
                new IntegratedPhasedModelId("payload-first");
        CreateIntegratedPhasedModel command =
                new CreateIntegratedPhasedModel(id);

        TestFixture.create(IntegratedPhasedModel.class)
                .whenCommand(command)
                .expectThat(fluxzero -> {
                    assertEquals(1L, fluxzero.eventStore()
                            .getEvents(id.toString()).count());
                    assertEquals(command, fluxzero.eventStore()
                            .getEvents(id.toString()).findFirst()
                            .orElseThrow().getPayload());
                    fluxzero.cache().clear();
                    assertEquals(
                            new IntegratedPhasedModel(
                                    id, "payload-model"),
                            fluxzero.modelRepository().load(id).get());
                });
    }

    @Test
    void receiverApplyUsesConsumerConfiguredForCommandRootPackage()
            throws Throwable {
        LocalClient client = LocalClient.newInstance(null);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            String modelId = "root-consumer-model";
            fluxzero.executeModelCommit(new Message(
                    new CreateRootConsumerModel(modelId))).join();
            Registration registration =
                    fluxzero.registerHandlers(
                            RootConsumerModel.class);
            try {
                client.getGatewayClient(COMMAND).append(
                        STORED,
                        new Message(
                                new RootConsumerModelCommand(
                                        modelId))
                                .serialize(
                                        fluxzero.serializer())).join();

                assertEventually(() -> assertEquals(
                        new RootConsumerModel(
                                modelId, "root"),
                        fluxzero.modelRepository()
                                .load(modelId,
                                      RootConsumerModel.class)
                                .get()));
            } finally {
                registration.cancel();
            }
        }
    }

    @Test
    void interceptorMayEmitCommandHandledOnlyByModelReceiver() {
        TestFixture fixture = TestFixture.create(ReceiverAccount.class);
        ReceiverAccountId accountId = new ReceiverAccountId("intercepted");

        fixture.givenCommands(new CreateReceiverAccount(accountId, "before"))
                .whenCommand(new RenameReceiverAccounts(List.of(
                        new RenameReceiverAccount(accountId, "after"))))
                .expectThat(fluxzero -> assertEquals(
                        2L, fluxzero.eventStore()
                                .getEvents(accountId.toString()).count()))
                .expectTrue(fluxzero -> new ReceiverAccount(
                        accountId, "after").equals(
                        fluxzero.modelRepository().load(accountId).get()));
    }

    @Test
    void explicitAssertAndApplyDynamicallyFollowsUntypedInterceptorOutput() {
        TestFixture fixture = TestFixture.create(
                ReceiverAccount.class,
                new UntypedRenameReceiverAccountsHandler());
        ReceiverAccountId accountId = new ReceiverAccountId("untyped-intercepted");

        fixture.givenCommands(new CreateReceiverAccount(accountId, "before"))
                .whenCommand(new UntypedRenameReceiverAccounts(List.of(
                        new RenameReceiverAccount(accountId, "after"))))
                .expectTrue(fluxzero -> new ReceiverAccount(
                        accountId, "after").equals(
                        fluxzero.modelRepository().load(accountId).get()));
    }

    @Test
    void testFixtureAutomaticallyHandlesDynamicallyTypedInterceptorOutput() {
        ReceiverAccountId accountId = new ReceiverAccountId("auto-untyped-intercepted");

        TestFixture.create(ReceiverAccount.class)
                .givenCommands(new CreateReceiverAccount(accountId, "before"))
                .whenCommand(new UntypedRenameReceiverAccounts(List.of(
                        new RenameReceiverAccount(accountId, "after"))))
                .expectNoErrors()
                .expectTrue(fluxzero -> new ReceiverAccount(accountId, "after").equals(
                        fluxzero.modelRepository().load(accountId).get()));
    }

    @Test
    void testFixtureCompletesDynamicallyTypedEmptyInterceptorOutput() {
        TestFixture.create(ReceiverAccount.class)
                .whenCommand(new UntypedRenameReceiverAccounts(List.of()))
                .expectNoErrors();
    }

    @Test
    void failedApplyDoesNotCommitAnyTarget() {
        TestFixture fixture = TestFixture.create();
        AccountId accountId = new AccountId("4");

        fixture.whenCommand(new FailingCreate(accountId))
                .expectExceptionalResult(IllegalStateException.class)
                .expectNoEvents()
                .expectTrue(fluxzero -> fluxzero.modelRepository()
                        .load(accountId).get() == null);
    }

    @Test
    void logicalDeleteAndRecreateRemainOneIndependentStream() {
        TestFixture fixture = TestFixture.create();
        AccountId accountId = new AccountId("5");

        fixture.givenCommands(new CreateAccount(accountId, 1))
                .givenCommands(new DeleteAccount(accountId))
                .whenCommand(new CreateAccount(accountId, 2))
                .expectThat(fluxzero -> assertEquals(
                        3L, fluxzero.eventStore()
                                .getEvents(accountId.toString()).count()))
                .expectTrue(fluxzero -> new Account(accountId, 2).equals(
                        fluxzero.modelRepository().load(accountId).get()));
    }

    @Test
    void documentLoadedDependencyStillReconstructsHistoricallyFromEvents() {
        TestFixture fixture = TestFixture.create();
        InventoryId inventoryId = new InventoryId("1");
        OrderId orderId = new OrderId("1");

        fixture.givenCommands(new CreateInventory(inventoryId, 5))
                .givenCommands(new CreateOrder(orderId, inventoryId))
                .whenCommand(new ChangeInventory(inventoryId, 95))
                .expectTrue(fluxzero -> new Order(orderId, 5).equals(
                        fluxzero.modelRepository().load(orderId).get()));
    }

    @Test
    void injectsQualifiedParentsAndGrandparentsAcrossTheCompleteApplyLifecycle() {
        TestFixture fixture = TestFixture.create();
        FamilyRootId rootId = new FamilyRootId("one");
        FamilyChildId primaryId = new FamilyChildId("primary");
        FamilyChildId secondaryId =
                new FamilyChildId("secondary");
        FamilyGrandchildId grandchildId =
                new FamilyGrandchildId("grandchild");

        fixture.givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateFamilyChild(
                                primaryId, rootId, "primary"),
                        new CreateFamilyChild(
                                secondaryId, rootId, "secondary"),
                        new CreateFamilyGrandchild(
                                grandchildId, primaryId,
                                secondaryId))
                .whenCommand(new ObserveFamily(grandchildId))
                .expectTrue(fluxzero -> new FamilyGrandchild(
                        grandchildId, primaryId, secondaryId,
                        "assert:primary/root|intercept:primary/root|apply:primary/root")
                        .equals(fluxzero.modelRepository()
                                        .load(grandchildId).get()));
    }

    @Test
    void parentDeletionCascadesAcrossPathsAndRetainsOptedOutChildren() {
        FamilyRootId rootId = new FamilyRootId("cascade");
        FamilyChildId childId = new FamilyChildId("cascade");
        FamilyChildId secondChildId = new FamilyChildId("cascade-second");
        FamilyGrandchildId grandchildId = new FamilyGrandchildId("cascade");
        String pathlessId = "pathless-cascade";
        String retainedId = "retained-cascade";
        AtomicReference<List<FamilyChild>> beforeDeletion =
                new AtomicReference<>();

        TestFixture.create()
                .registerHandlers(new Object() {
                    @HandleEvent
                    void observe(
                            DeleteFamilyRoot event,
                            Graph<FamilyRoot> graph) {
                        Graph<FamilyRoot> previous = graph.previous();
                        beforeDeletion.set(previous == null
                                ? List.of()
                                : previous.childModels(
                                        "children",
                                        FamilyChild.class));
                    }
                })
                .givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateFamilyChild(childId, rootId, "child"),
                        new CreateFamilyChild(secondChildId, rootId, "second-child"),
                        new CreateFamilyGrandchild(grandchildId, childId, secondChildId),
                        new CreatePathlessFamilyChild(pathlessId, rootId),
                        new CreateRetainedFamilyChild(retainedId, rootId))
                .whenCommand(new DeleteFamilyRoot(rootId))
                .expectThat(fluxzero -> {
                    ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                            fluxzero.modelRepository()).invalidateModels(List.of(
                            rootId.toString(), childId.toString(), secondChildId.toString(),
                            grandchildId.toString(),
                            pathlessId, retainedId));
                    assertTrue(fluxzero.modelRepository().load(rootId).isEmpty(), "root should be deleted");
                    assertTrue(fluxzero.modelRepository().load(childId).isEmpty(), "first child should be deleted");
                    assertTrue(fluxzero.modelRepository().load(secondChildId).isEmpty(), "second child should be deleted");
                    assertTrue(fluxzero.modelRepository().load(grandchildId).isEmpty(), "grandchild should be deleted");
                    assertTrue(fluxzero.modelRepository()
                                       .load(pathlessId, PathlessFamilyChild.class).isEmpty(),
                               "pathless child should be deleted");
                    assertEquals(
                            new RetainedFamilyChild(retainedId, rootId),
                            fluxzero.modelRepository()
                                    .load(retainedId, RetainedFamilyChild.class).get());
                    assertTrue(fluxzero.eventStore().getEvents(childId.toString())
                                       .anyMatch(event -> event.getPayload()
                                               instanceof CascadedModelDeletion),
                               "first child should record the cascade event");
                    assertTrue(fluxzero.eventStore().getEvents(grandchildId.toString())
                                       .anyMatch(event -> event.getPayload()
                                               instanceof CascadedModelDeletion),
                               "grandchild should record the cascade event");
                    assertEquals(
                            Set.of(
                                    new FamilyChild(
                                            childId, rootId,
                                            "child"),
                                    new FamilyChild(
                                            secondChildId, rootId,
                                            "second-child")),
                            Set.copyOf(beforeDeletion.get()));
                });
    }

    @Test
    void parentDeletionCascadesToDocumentModels() {
        FamilyRootId rootId = new FamilyRootId("document-cascade");
        DocumentFamilyChildId childId =
                new DocumentFamilyChildId("document-cascade");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateDocumentFamilyChild(childId, rootId, "child"))
                .whenCommand(new DeleteFamilyRoot(rootId))
                .expectThat(fluxzero -> {
                    ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                            fluxzero.modelRepository()).invalidateModels(List.of(
                            rootId.toString(), childId.toString()));
                    assertTrue(fluxzero.modelRepository().load(rootId).isEmpty());
                    assertTrue(fluxzero.modelRepository().load(childId).isEmpty());
                });
    }

    @Test
    void childMovedWhileDeletingItsParentSurvivesUnderTheNewParent() {
        FamilyRootId deletedRootId = new FamilyRootId("move-delete-old");
        FamilyRootId retainedRootId = new FamilyRootId("move-delete-new");
        FamilyChildId childId = new FamilyChildId("move-delete");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(deletedRootId, "old"),
                        new CreateFamilyRoot(retainedRootId, "new"),
                        new CreateFamilyChild(childId, deletedRootId, "child"))
                .whenCommand(new DeleteRootAndMoveChild(
                        deletedRootId, childId, retainedRootId))
                .expectThat(fluxzero -> {
                    var repository = (io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                            fluxzero.modelRepository();
                    repository.invalidateModels(List.of(
                            deletedRootId.toString(), retainedRootId.toString(), childId.toString()));
                    assertTrue(repository.load(deletedRootId).isEmpty());
                    assertEquals(
                            new FamilyChild(childId, retainedRootId, "child"),
                            repository.load(childId).get());
                    assertFalse(fluxzero.eventStore().getEvents(childId.toString())
                                        .anyMatch(event -> event.getPayload()
                                                instanceof CascadedModelDeletion));
                });
    }

    @Test
    void subtreeAttachedToADeletedParentInTheSameCommitIsCascadedCompletely() {
        FamilyRootId deletedRootId = new FamilyRootId("attach-delete");
        FamilyRootId oldRootId = new FamilyRootId("attach-delete-old");
        FamilyChildId childId = new FamilyChildId("attach-delete");
        FamilyChildId retainedChildId = new FamilyChildId("attach-delete-retained");
        FamilyGrandchildId grandchildId = new FamilyGrandchildId("attach-delete");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(deletedRootId, "deleted"),
                        new CreateFamilyRoot(oldRootId, "old"),
                        new CreateFamilyChild(childId, oldRootId, "moved"),
                        new CreateFamilyChild(retainedChildId, oldRootId, "retained"),
                        new CreateFamilyGrandchild(
                                grandchildId, childId, retainedChildId))
                .whenCommand(new DeleteRootAndMoveChild(
                        deletedRootId, childId, deletedRootId))
                .expectThat(fluxzero -> {
                    var repository = (io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                            fluxzero.modelRepository();
                    repository.invalidateModels(List.of(
                            deletedRootId.toString(), oldRootId.toString(),
                            childId.toString(), retainedChildId.toString(),
                            grandchildId.toString()));
                    assertTrue(repository.load(deletedRootId).isEmpty());
                    assertTrue(repository.load(childId).isEmpty());
                    assertTrue(repository.load(grandchildId).isEmpty());
                    assertEquals(
                            new FamilyChild(retainedChildId, oldRootId, "retained"),
                            repository.load(retainedChildId).get());
                });
    }

    @Test
    void deletingAndRecreatingAParentInOneCommitDoesNotCascade() {
        FamilyRootId rootId = new FamilyRootId("replace");
        FamilyChildId childId = new FamilyChildId("replace");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(rootId, "before"),
                        new CreateFamilyChild(childId, rootId, "child"))
                .whenCommand(new ReplaceFamilyRoot(rootId, "after"))
                .expectThat(fluxzero -> {
                    var repository = (io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                            fluxzero.modelRepository();
                    repository.invalidateModels(List.of(rootId.toString(), childId.toString()));
                    assertEquals(new FamilyRoot(rootId, "after"), repository.load(rootId).get());
                    assertEquals(
                            new FamilyChild(childId, rootId, "child"),
                            repository.load(childId).get());
                    assertFalse(fluxzero.eventStore().getEvents(childId.toString())
                                        .anyMatch(event -> event.getPayload()
                                                instanceof CascadedModelDeletion));
                });
    }

    @Test
    void searchesModelsByCurrentGrandparentDocument() {
        FamilyRootId wantedRoot =
                new FamilyRootId("search-wanted");
        FamilyRootId otherRoot =
                new FamilyRootId("search-other");
        FamilyChildId wantedChild =
                new FamilyChildId("search-wanted");
        FamilyChildId otherChild =
                new FamilyChildId("search-other");
        FamilyGrandchildId wantedGrandchild =
                new FamilyGrandchildId("search-wanted");
        FamilyGrandchildId otherGrandchild =
                new FamilyGrandchildId("search-other");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(
                                wantedRoot, "wanted"),
                        new CreateFamilyRoot(
                                otherRoot, "other"),
                        new CreateFamilyChild(
                                wantedChild, wantedRoot,
                                "wanted-child"),
                        new CreateFamilyChild(
                                otherChild, otherRoot,
                                "other-child"),
                        new CreateFamilyGrandchild(
                                wantedGrandchild,
                                wantedChild, wantedChild),
                        new CreateFamilyGrandchild(
                                otherGrandchild,
                                otherChild, otherChild))
                .whenApplying(fluxzero ->
                                      Fluxzero.search(
                                                      FamilyGrandchild.class)
                                              .whereAncestor(
                                                      FamilyRoot.class,
                                                      2, 2,
                                                      match(
                                                              "wanted",
                                                              true,
                                                              "name"))
                                              .fetchAll(
                                                      FamilyGrandchild.class))
                .expectResult(List.of(
                        new FamilyGrandchild(
                                wantedGrandchild,
                                wantedChild, wantedChild,
                                null)))
                .andThen()
                .whenApplying(fluxzero ->
                                      Fluxzero.search(
                                                      FamilyRoot.class)
                                              .whereDescendant(
                                                      FamilyGrandchild.class,
                                                      2, 2,
                                                      match(
                                                              "search-wanted",
                                                              true,
                                                              "familyGrandchildId"))
                                              .fetchAll(
                                                      FamilyRoot.class))
                .expectResult(List.of(
                        new FamilyRoot(
                                wantedRoot, "wanted")));
    }

    @Test
    void composesCurrentModelTreeFromExplicitParentPaths() {
        FamilyRootId rootId =
                new FamilyRootId("composed");
        FamilyChildId childId =
                new FamilyChildId("composed");
        FamilyGrandchildId grandchildId =
                new FamilyGrandchildId("composed");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(
                                rootId, "root"),
                        new CreateFamilyChild(
                                childId, rootId,
                                "child"),
                        new CreateFamilyGrandchild(
                                grandchildId, childId,
                                childId))
                .whenApplying(fluxzero -> {
                    assertThrows(
                            IllegalStateException.class,
                            () -> Fluxzero.searchGraph(FamilyRoot.class)
                                    .includeOnly("children")
                                    .fetch(1));
                    List<com.fasterxml.jackson.databind.node.ObjectNode>
                            graphs =
                            Fluxzero.searchGraph(
                                            FamilyRoot.class)
                            .constraint(match(
                                    "composed", true,
                                    "children/primaryGrandchildren/familyGrandchildId"))
                            .includeOnly("children")
                            .fetch(1, com.fasterxml.jackson.databind.node.ObjectNode.class);
                    var document =
                            graphs.getFirst();
                    return List.of(
                            document.at(
                                            "/children/0/name")
                                    .asText(),
                            document.at(
                                            "/children/0/primaryGrandchildren/0/familyGrandchildId")
                                    .asText(),
                            document.at(
                                            "/children/0/secondaryGrandchildren/0/familyGrandchildId")
                                    .asText(),
                            document.get("name")
                                    == null);
                })
                .expectResult(List.of(
                        "child",
                        "composed",
                        "composed",
                        true));
    }

    @Test
    void localClientAwaitsAndMaterializesGraphProjectionWithNonSearchableChild() {
        ProjectionRootId rootId =
                new ProjectionRootId("ancestor");
        ProjectionChildId childId =
                new ProjectionChildId("payload");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateProjectionRoot(
                                rootId))
                .whenCommand(
                        new CreateProjectionChild(
                                childId, rootId))
                .expectTrue(fluxzero -> {
                    var status =
                            fluxzero.modelRepository()
                                    .graphProjectionStatus(
                                            ProjectionRoot.class);
                    return status.getCollection()
                                   .equals(
                                           "projectionRoots")
                           && status
                                      .getSourceStateIndex()
                              > 0L
                           && !status.isRebuilding()
                           && status
                                      .getProcessedStateIndex()
                              >= status
                                      .getSourceStateIndex();
                })
                .expectTrue(fluxzero -> {
                    var projected =
                            fluxzero.documentStore()
                                    .search(
                                            "projectionRoots")
                                    .fetchAll(
                                            io.fluxzero.common.api.search
                                                    .SerializedDocument.class);
                    assertEquals(
                            1, projected.size());
                    assertEquals(
                            "payload",
                            projected.getFirst()
                                    .deserializeDocument()
                                    .getEntryAtPath(
                                            "projectedChildren/0/projectionChildId")
                                    .orElseThrow()
                                    .getValue());
                    return true;
                })
                .expectTrue(fluxzero ->
                                    fluxzero.documentStore()
                                            .search(
                                                    ProjectionChild.class)
                                            .fetchAll()
                                            .isEmpty())
                .expectTrue(fluxzero ->
                                    {
                                        List<Graph<ProjectionRoot>> stored =
                                                Fluxzero.searchGraph(
                                                                ProjectionRoot.class)
                                                        .fetch(1);
                                        List<Graph<ProjectionRoot>> live =
                                                Fluxzero.searchGraph(
                                                                ProjectionRoot.class,
                                                                true)
                                                        .fetch(1);
                                        return "payload"
                                                       .equals(
                                                               stored.getFirst()
                                                                       .childModels(
                                                                               "projectedChildren",
                                                                               ProjectionChild.class)
                                                                       .getFirst()
                                                                       .projectionChildId()
                                                                       .getId())
                                               && "payload".equals(
                                                       live.getFirst()
                                                               .childModels(
                                                                       "projectedChildren",
                                                                       ProjectionChild.class)
                                                               .getFirst()
                                                               .projectionChildId()
                                                               .getId());
                                    });
    }

    @Test
    void handleDocumentCanTrackMaterializedModelGraph() {
        ProjectionRootId rootId = new ProjectionRootId("handled");
        ProjectionChildId childId = new ProjectionChildId("handled-child");
        AtomicReference<Graph<ProjectionRoot>> handled =
                new AtomicReference<>();

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .registerHandlers(new Object() {
                    @HandleDocument(modelGraph = ProjectionRoot.class)
                    void handle(Graph<ProjectionRoot> graph) {
                        handled.set(graph);
                    }
                })
                .givenCommands(new CreateProjectionRoot(rootId))
                .whenCommand(new CreateProjectionChild(childId, rootId))
                .expectThat(ignored -> assertEquals(
                        "handled-child",
                        handled.get()
                                .childModels(
                                        "projectedChildren",
                                        ProjectionChild.class)
                                .getFirst()
                                .projectionChildId().getId()));
    }

    @Test
    void handleDocumentReceivesTypedGraphTombstoneWithCompletePreviousGraph() {
        ProjectionRootId rootId = new ProjectionRootId("deleted-root");
        ProjectionChildId childId = new ProjectionChildId("deleted-child");
        AtomicReference<Graph<ProjectionRoot>> deletion = new AtomicReference<>();

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .registerHandlers(new Object() {
                    @HandleDocument(modelGraph = ProjectionRoot.class)
                    void handle(Graph<ProjectionRoot> graph) {
                        if (graph.isEmpty()) {
                            deletion.set(graph);
                        }
                    }

                    @HandleDocument("projectionRoots")
                    void handleOrdinaryDocument(DeserializingMessage message) {
                        assertFalse(message.getMetadata().containsKey(
                                io.fluxzero.common.search.ModelGraphDocumentManifest
                                        .TOMBSTONE_METADATA_KEY));
                    }
                })
                .givenCommands(
                        new CreateProjectionRoot(rootId),
                        new CreateProjectionChild(childId, rootId))
                .whenCommand(new DeleteProjectionRoot(rootId))
                .expectNoErrors()
                .expectThat(ignored -> {
                    Graph<ProjectionRoot> tombstone = deletion.get();
                    assertTrue(tombstone.isEmpty());
                    assertEquals(rootId.toString(), tombstone.id());
                    assertEquals(ProjectionRoot.class, tombstone.type());
                    Graph<ProjectionRoot> previous = tombstone.previous();
                    assertTrue(tombstone.stateIndex() > previous.stateIndex());
                    assertEquals(rootId, previous.get().projectionRootId());
                    assertEquals(childId, previous.childModels(
                            "projectedChildren", ProjectionChild.class)
                            .getFirst().projectionChildId());
                });
    }

    @Test
    void typedMaterializedGraphSerializesDeclaredEmptyChildPaths() {
        ProjectionRootId populatedRoot =
                new ProjectionRootId("populated");
        ProjectionRootId emptyRoot =
                new ProjectionRootId("empty");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateProjectionRoot(populatedRoot),
                        new CreateProjectionChild(
                                new ProjectionChildId("known"),
                                populatedRoot))
                .whenCommand(new CreateProjectionRoot(emptyRoot))
                .expectThat(fluxzero -> {
                    Graph<ProjectionRoot> graph =
                            Fluxzero.searchGraph(ProjectionRoot.class)
                                    .stream()
                                    .filter(candidate -> emptyRoot.equals(
                                            candidate.get().projectionRootId()))
                                    .findFirst().orElseThrow();
                    Graph<ProjectionRoot> filtered =
                            fluxzero.serializer().filterContent(graph, null);
                    ObjectNode document = fluxzero.serializer()
                            .convert(filtered, ObjectNode.class);
                    assertEquals(emptyRoot, graph.get().projectionRootId());
                    assertTrue(graph.childModels(
                            "projectedChildren",
                            ProjectionChild.class).isEmpty());
                    assertEquals(List.of("projectedChildren"),
                                 graph.childPaths());
                    assertTrue(document.path("projectedChildren").isArray(),
                               document::toPrettyString);
                    assertTrue(document.path("projectedChildren").isEmpty(),
                               document::toPrettyString);
                });
    }

    @Test
    void filtersMaterializedGraphWithNodeAndRootContext() {
        ProjectionRootId rootId =
                new ProjectionRootId("filtered");
        ProjectionChildId childId =
                new ProjectionChildId("filtered");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .givenCommands(new CreateProjectionRoot(rootId))
                .whenCommand(new CreateProjectionChild(childId, rootId))
                .expectTrue(fluxzero -> {
                    Graph<ProjectionRoot> graph = Fluxzero.searchGraph(
                                    ProjectionRoot.class)
                            .fetchAll().getFirst();
                    Graph<ProjectionRoot> filtered =
                            fluxzero.serializer().filterContent(graph, null);
                    return filtered.childModels(
                                    "projectedChildren",
                                    ProjectionChild.class)
                            .stream().map(ProjectionChild::projectionChildId)
                            .toList().equals(List.of(childId));
                });
    }

    @Test
    void asyncFixtureDoesNotAwaitGraphProjectionByDefault() {
        ProjectionRootId rootId =
                new ProjectionRootId(
                        "async-default");
        ProjectionChildId childId =
                new ProjectionChildId(
                        "async-default");

        TestFixture.createAsync()
                .spy()
                .givenCommands(
                        new CreateProjectionRoot(
                                rootId))
                .whenCommand(
                        new CreateProjectionChild(
                                childId, rootId))
                .expectThat(fluxzero ->
                                    verify(
                                            fluxzero.client()
                                                    .getEventStoreClient(),
                                            never())
                                            .awaitModelGraphProjection(
                                                    any()));
    }

    @Test
    void asyncFixtureCanExplicitlyAwaitGraphProjectionCompletion() {
        ProjectionRootId rootId =
                new ProjectionRootId(
                        "async-await");
        ProjectionChildId childId =
                new ProjectionChildId(
                        "async-await");

        TestFixture.createAsync(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .spy()
                .givenCommands(
                        new CreateProjectionRoot(
                                rootId))
                .whenCommand(
                        new CreateProjectionChild(
                                childId, rootId))
                .expectThat(fluxzero ->
                                    verify(
                                            fluxzero.client()
                                                    .getEventStoreClient(),
                                            times(1))
                                            .awaitModelGraphProjection(
                                                    any()))
                .expectTrue(fluxzero ->
                                    projectionContainsChild(
                                            fluxzero,
                                            childId));
    }

    private static boolean projectionContainsChild(
            Fluxzero fluxzero,
            ProjectionChildId childId) {
        return childId.getId()
                .equals(
                        fluxzero.documentStore()
                                .search(
                                        "projectionRoots")
                                .fetch(
                                        1,
                                        io.fluxzero.common.api.search
                                                .SerializedDocument.class)
                                .getFirst()
                                .deserializeDocument()
                                .getEntryAtPath(
                                        "projectedChildren/0/projectionChildId")
                                .orElseThrow()
                                .getValue());
    }

    @Test
    void localGraphProjectionUpdatesBothSidesOfAChildMove() {
        ProjectionRootId firstRoot =
                new ProjectionRootId("first");
        ProjectionRootId secondRoot =
                new ProjectionRootId("second");
        ProjectionChildId childId =
                new ProjectionChildId("moving");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateProjectionRoot(
                                firstRoot),
                        new CreateProjectionRoot(
                                secondRoot),
                        new CreateProjectionChild(
                                childId,
                                firstRoot))
                .whenCommand(
                        new MoveProjectionChild(
                                childId,
                                secondRoot))
                .expectTrue(fluxzero -> {
                    var projections =
                            fluxzero.documentStore()
                                    .search(
                                            "projectionRoots")
                                    .fetchAll(
                                            io.fluxzero.common.api.search
                                                    .SerializedDocument.class)
                                    .stream()
                                    .collect(
                                            java.util.stream.Collectors
                                                    .toMap(
                                                            io.fluxzero.common.api.search.SerializedDocument
                                                                    ::getId,
                                                            document ->
                                                                    document.deserializeDocument()));
                    return projections
                                   .get(
                                           firstRoot.toString())
                                   .getEntryAtPath(
                                           "projectedChildren/0/projectionChildId")
                                   .isEmpty()
                           && "moving".equals(
                                   projections
                                           .get(
                                                   secondRoot.toString())
                                           .getEntryAtPath(
                                                   "projectedChildren/0/projectionChildId")
                                           .orElseThrow()
                                           .getValue());
                });
    }

    @Test
    void localGraphProjectionRemovesLogicallyDeletedGraphChild() {
        ProjectionRootId rootId =
                new ProjectionRootId("delete");
        ProjectionChildId childId =
                new ProjectionChildId("deleted");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateProjectionRoot(
                                rootId),
                        new CreateProjectionChild(
                                childId, rootId))
                .whenCommand(
                        new DeleteProjectionChild(
                                childId))
                .expectTrue(fluxzero -> {
                    var projection =
                            fluxzero.documentStore()
                                    .search(
                                            "projectionRoots")
                                    .fetchAll(
                                            io.fluxzero.common.api.search
                                                    .SerializedDocument.class)
                                    .getFirst()
                                    .deserializeDocument();
                    return projection.getEntryAtPath(
                                    "projectedChildren/0/projectionChildId")
                                   .isEmpty()
                           && fluxzero.documentStore()
                                   .search(
                                           io.fluxzero.common.api.modeling.ModelDocumentMutation
                                                   .GRAPH_COMPONENT_COLLECTION)
                                   .fetchAll()
                                   .isEmpty();
                });
    }

    @Test
    void movingAChildChangesItsNextInjectedParentWithoutLoadingEitherParent() {
        TestFixture fixture = TestFixture.create();
        FamilyRootId rootId = new FamilyRootId("move");
        FamilyChildId firstId = new FamilyChildId("first");
        FamilyChildId secondId = new FamilyChildId("second");
        FamilyGrandchildId grandchildId =
                new FamilyGrandchildId("move");

        fixture.givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateFamilyChild(
                                firstId, rootId, "first"),
                        new CreateFamilyChild(
                                secondId, rootId, "second"),
                        new CreateFamilyGrandchild(
                                grandchildId, firstId, secondId),
                        new MoveFamilyGrandchild(
                                grandchildId, secondId))
                .whenCommand(new ObserveFamily(grandchildId))
                .expectTrue(fluxzero -> new FamilyGrandchild(
                        grandchildId, secondId, secondId,
                        "assert:second/root|intercept:second/root|apply:second/root")
                        .equals(fluxzero.modelRepository()
                                        .load(grandchildId).get()));
    }

    @Test
    void currentGraphReflectsPendingChildMoveAndKeepsHistoricalGraphStable() {
        FamilyRootId firstRootId = new FamilyRootId("batch-graph-first");
        FamilyRootId secondRootId = new FamilyRootId("batch-graph-second");
        FamilyChildId firstChildId = new FamilyChildId("batch-graph-first");
        FamilyChildId secondChildId = new FamilyChildId("batch-graph-second");
        FamilyGrandchildId grandchildId = new FamilyGrandchildId("batch-graph");
        FamilyChild before = new FamilyChild(
                firstChildId, firstRootId, "moving");
        FamilyChild after = new FamilyChild(
                firstChildId, secondRootId, "moving");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(firstRootId, "first"),
                        new CreateFamilyRoot(secondRootId, "second"),
                        new CreateFamilyChild(firstChildId, firstRootId, "moving"),
                        new CreateFamilyChild(secondChildId, secondRootId, "existing"),
                        new CreateFamilyGrandchild(
                                grandchildId, firstChildId, firstChildId))
                .whenApplying(fluxzero -> {
                    Graph<FamilyRoot> durable =
                            fluxzero.modelRepository().loadGraph(firstRootId);
                    DeserializingMessage moveMessage =
                            new DeserializingMessage(
                                    new Message("move"), COMMAND,
                                    fluxzero.serializer());
                    DeserializingMessage readMessage =
                            new DeserializingMessage(
                                    new Message("read"), COMMAND,
                                    fluxzero.serializer());
                    DeserializingMessage.forEachInBatch(
                            List.of(moveMessage, readMessage), current -> {
                                if (DeserializingMessage.getMessageBatchIndex() == 0) {
                                    ModelBatchScope.stage(
                                            null,
                                            CommitAttempt.fromChanges(
                                                    durable.stateIndex(),
                                                    List.of(firstChildId.toString()),
                                                    java.util.Map.of(
                                                            firstChildId.toString(),
                                                            FamilyChild.class),
                                                    current,
                                                    List.of(Change.applied(
                                                                    firstChildId.toString(),
                                                                    FamilyChild.class,
                                                                    0L, null, before, after, null,
                                                                    null, false))));
                                    return;
                                }

                                assertEquals(
                                        after,
                                        fluxzero.modelRepository()
                                                .load(firstChildId).get());
                                Graph<FamilyRoot> oldGraph =
                                        fluxzero.modelRepository()
                                                .loadGraph(firstRootId);
                                Graph<FamilyRoot> newGraph =
                                        fluxzero.modelRepository()
                                                .loadGraph(secondRootId);
                                Graph<FamilyRoot> historical =
                                        fluxzero.modelRepository().loadGraphAt(
                                                firstRootId,
                                                durable.stateIndex());
                                Graph<FamilyRoot> pinnedWithBatch =
                                        ((io.fluxzero.sdk.persisting.repository.DefaultModelRepository)
                                                fluxzero.modelRepository())
                                                .loadGraphAtIncludingMessageBatch(
                                                        secondRootId.toString(),
                                                        FamilyRoot.class,
                                                        durable.stateIndex(),
                                                        Graph.Options.DEFAULT);

                                assertEquals(
                                        List.of(),
                                        oldGraph.children("children", FamilyChild.class));
                                assertEquals(
                                        List.of("existing", "moving"),
                                        newGraph.children("children", FamilyChild.class)
                                                .stream()
                                                .map(Graph::get)
                                                .map(FamilyChild::name)
                                                .sorted()
                                                .toList());
                                Graph<FamilyChild> moved =
                                        newGraph.children("children", FamilyChild.class)
                                                .stream()
                                                .filter(node -> node.id()
                                                        .equals(firstChildId.toString()))
                                                .findFirst().orElseThrow();
                                assertEquals(
                                        grandchildId.toString(),
                                        moved.children("primaryGrandchildren", FamilyGrandchild.class)
                                                .getFirst().id());
                                assertEquals(
                                        firstChildId.toString(),
                                        historical.children("children", FamilyChild.class)
                                                .getFirst().id());
                                assertEquals(
                                        List.of("existing", "moving"),
                                        pinnedWithBatch.children("children", FamilyChild.class)
                                                .stream()
                                                .map(Graph::get)
                                                .map(FamilyChild::name)
                                                .sorted()
                                                .toList());
                            });
                    return null;
                })
                .expectNoErrors();
    }

    @Test
    void currentGraphCanBeComposedEntirelyFromNewPendingModels() {
        FamilyRootId rootId = new FamilyRootId("batch-new-root");
        FamilyChildId childId = new FamilyChildId("batch-new-child");
        FamilyRoot root = new FamilyRoot(rootId, "new-root");
        FamilyChild child = new FamilyChild(childId, rootId, "new-child");

        TestFixture.create()
                .whenApplying(fluxzero -> {
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    new DeserializingMessage(
                                            new Message("create"), COMMAND,
                                            fluxzero.serializer()),
                                    new DeserializingMessage(
                                            new Message("read"), COMMAND,
                                            fluxzero.serializer())),
                            current -> {
                                if (DeserializingMessage.getMessageBatchIndex() == 0) {
                                    ModelBatchScope.stage(
                                            null,
                                            CommitAttempt.fromChanges(
                                                    -1L,
                                                    List.of(
                                                            rootId.toString(),
                                                            childId.toString()),
                                                    java.util.Map.of(
                                                            rootId.toString(), FamilyRoot.class,
                                                            childId.toString(), FamilyChild.class),
                                                    current,
                                                    List.of(
                                                                    Change.applied(
                                                                            rootId.toString(),
                                                                            FamilyRoot.class,
                                                                            -1L, null, null, root, null,
                                                                            null, false),
                                                                    Change.applied(
                                                                            childId.toString(),
                                                                            FamilyChild.class,
                                                                            -1L, null, null, child, null,
                                                                            null, false))));
                                    return;
                                }
                                Graph<FamilyRoot> graph =
                                        fluxzero.modelRepository().loadGraph(rootId);
                                assertEquals(root, graph.get());
                                assertEquals(
                                        child,
                                        graph.children("children", FamilyChild.class)
                                                .getFirst().get());
                            });
                    return null;
                })
                .expectNoErrors();
    }

    @Test
    void pendingRootDeletionHidesItsCurrentGraphButNotItsHistory() {
        FamilyRootId rootId =
                new FamilyRootId("batch-deleted-root");
        FamilyChildId childId =
                new FamilyChildId("batch-deleted-root");
        FamilyRoot root =
                new FamilyRoot(rootId, "deleted-root");

        TestFixture.create()
                .givenCommands(
                        new CreateFamilyRoot(
                                rootId, root.name()),
                        new CreateFamilyChild(
                                childId, rootId, "child"))
                .whenApplying(fluxzero -> {
                    Graph<FamilyRoot> durable =
                            fluxzero.modelRepository()
                                    .loadGraph(rootId);
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    new DeserializingMessage(
                                            new Message("delete"), COMMAND,
                                            fluxzero.serializer()),
                                    new DeserializingMessage(
                                            new Message("read"), COMMAND,
                                            fluxzero.serializer())),
                            current -> {
                                if (DeserializingMessage
                                            .getMessageBatchIndex()
                                    == 0) {
                                    ModelBatchScope.stage(
                                            null,
                                            CommitAttempt.fromChanges(
                                                    durable.stateIndex(),
                                                    List.of(
                                                            rootId.toString()),
                                                    java.util.Map.of(
                                                            rootId.toString(),
                                                            FamilyRoot.class),
                                                    current,
                                                    List.of(Change.applied(
                                                                    rootId.toString(),
                                                                    FamilyRoot.class,
                                                                    0L, null, root,
                                                                    null, null, null, false))));
                                    return;
                                }
                                Graph<FamilyRoot> currentGraph =
                                        fluxzero.modelRepository()
                                                .loadGraph(rootId);
                                Graph<FamilyRoot> historical =
                                        fluxzero.modelRepository()
                                                .loadGraphAt(
                                                        rootId,
                                                        durable.stateIndex());

                                assertTrue(
                                        currentGraph.isEmpty());
                                assertTrue(
                                        currentGraph.children().isEmpty());
                                assertEquals(
                                        root,
                                        historical.get());
                                assertEquals(
                                        childId.toString(),
                                        historical.children("children", FamilyChild.class)
                                                .getFirst().id());
                            });
                    return null;
                })
                .expectNoErrors();
    }

    @Test
    void laterInterceptorSubstepSeesAParentMovedEarlierInTheSameCommit() {
        TestFixture fixture = TestFixture.create();
        FamilyRootId rootId = new FamilyRootId("staged");
        FamilyChildId firstId = new FamilyChildId("staged-first");
        FamilyChildId secondId =
                new FamilyChildId("staged-second");
        FamilyGrandchildId grandchildId =
                new FamilyGrandchildId("staged");

        fixture.givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateFamilyChild(
                                firstId, rootId, "first"),
                        new CreateFamilyChild(
                                secondId, rootId, "second"),
                        new CreateFamilyGrandchild(
                                grandchildId, firstId, secondId))
                .whenCommand(new MoveAndObserveFamily(List.of(
                        new FamilyStep(
                                grandchildId, secondId, false),
                        new FamilyStep(
                                grandchildId, secondId, true))))
                .expectTrue(fluxzero -> {
                    var actual = fluxzero.modelRepository()
                            .load(grandchildId).get();
                    var expected = new FamilyGrandchild(
                            grandchildId, secondId, secondId,
                            "same-commit:second/root");
                    if (!expected.equals(actual)) {
                        throw new AssertionError(
                                "Expected " + expected
                                + " but got " + actual);
                    }
                    return true;
                });
    }

    @Test
    void rejectsAnUnqualifiedAmbiguousAncestor() {
        TestFixture fixture = TestFixture.create();
        FamilyRootId rootId = new FamilyRootId("ambiguous");
        FamilyChildId firstId = new FamilyChildId("first-a");
        FamilyChildId secondId = new FamilyChildId("second-a");
        FamilyGrandchildId grandchildId =
                new FamilyGrandchildId("ambiguous");

        fixture.givenCommands(
                        new CreateFamilyRoot(rootId, "root"),
                        new CreateFamilyChild(
                                firstId, rootId, "first"),
                        new CreateFamilyChild(
                                secondId, rootId, "second"),
                        new CreateFamilyGrandchild(
                                grandchildId, firstId, secondId))
                .whenCommand(
                        new ObserveAmbiguousFamily(grandchildId))
                .expectExceptionalResult(
                        IllegalStateException.class)
                .expectNoEvents();
    }

    @Test
    void historicalDependencyLoadRejectsExplicitNonStoredGap() {
        TestFixture fixture = TestFixture.create();
        PrivateInventoryId inventoryId = new PrivateInventoryId("1");
        PrivateOrderId orderId = new PrivateOrderId("1");

        fixture.givenCommands(new CreatePrivateInventory(inventoryId, 5))
                .whenCommand(new CreatePrivateOrder(orderId, inventoryId))
                .expectExceptionalResult(
                        EventSourcingException.class)
                .expectTrue(fluxzero ->
                                    fluxzero.modelRepository()
                                            .load(orderId)
                                            .isEmpty());
    }

    @Test
    void trackedPayloadApplyUsesTheSameModelCommitPath() throws Throwable {
        LocalClient client = LocalClient.newInstance(null);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            Registration registration =
                    fluxzero.registerHandlers(CreateAccount.class);
            try {
                AccountId accountId = new AccountId("tracked");
                client.getGatewayClient(COMMAND).append(
                        STORED,
                        new Message(new CreateAccount(accountId, 7))
                                .serialize(fluxzero.serializer())).join();

                assertEventually(() -> assertEquals(
                        new Account(accountId, 7),
                        fluxzero.modelRepository().load(accountId).get()));
            } finally {
                registration.cancel();
            }
        }
    }

    @Test
    void trackedReceiverApplyIsOwnedByOneRegisteredModelHandler()
            throws Throwable {
        LocalClient client = LocalClient.newInstance(null);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            ReceiverAccountId accountId =
                    new ReceiverAccountId("tracked");
            fluxzero.executeModelCommit(new Message(
                    new CreateReceiverAccount(accountId, "before"))).join();
            Registration registration =
                    fluxzero.registerHandlers(ReceiverAccount.class);
            try {
                client.getGatewayClient(COMMAND).append(
                        STORED,
                        new Message(new RenameReceiverAccount(
                                accountId, "after"))
                                .serialize(fluxzero.serializer())).join();

                assertEventually(() -> assertEquals(
                        new ReceiverAccount(accountId, "after"),
                        fluxzero.modelRepository().load(accountId).get()));
            } finally {
                registration.cancel();
            }
        }
    }

    @Test
    void trackedCrossModelReceiverCommitExecutesOnlyOnce()
            throws Throwable {
        LocalClient client = LocalClient.newInstance(null);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            FirstCounterId firstId = new FirstCounterId("tracked");
            SecondCounterId secondId = new SecondCounterId("tracked");
            fluxzero.executeModelCommit(new Message(
                    new CreateFirstCounter(firstId))).join();
            fluxzero.executeModelCommit(new Message(
                    new CreateSecondCounter(secondId))).join();
            receiverInvocations.set(0);
            Registration registration = fluxzero.registerHandlers(
                    FirstCounter.class, SecondCounter.class);
            try {
                client.getGatewayClient(COMMAND).append(
                        STORED,
                        new Message(new IncrementBoth(firstId, secondId))
                                .serialize(fluxzero.serializer())).join();

                assertEventually(() -> {
                    assertEquals(
                            new FirstCounter(firstId, 1),
                            fluxzero.modelRepository().load(firstId).get());
                    assertEquals(
                            new SecondCounter(secondId, 1),
                            fluxzero.modelRepository().load(secondId).get());
                    assertEquals(2, receiverInvocations.get());
                });
            } finally {
                registration.cancel();
            }
        }
    }

    private static void assertEventually(Executable assertion)
            throws Throwable {
        AssertionError lastError = null;
        long deadline = System.nanoTime()
                        + Duration.ofSeconds(5).toNanos();
        do {
            try {
                assertion.execute();
                return;
            } catch (AssertionError error) {
                lastError = error;
                Thread.sleep(10L);
            }
        } while (System.nanoTime() < deadline);
        throw lastError;
    }

    @Model(searchable = true)
    private record Account(@EntityId AccountId accountId, int balance) {
    }

    private static final class AccountId extends Id<Account> {
        private AccountId(String id) {
            super(id, "account-");
        }
    }

    private record CreateAccount(AccountId accountId, int balance) {
        @Apply
        Account apply() {
            return new Account(accountId, balance);
        }
    }

    private record UpdateAccounts(
            List<AccountId> accountIds,
            int amount) {
        @Apply
        List<Account> apply(
                @Association("accountIds")
                List<Graph<Account>> accounts) {
            return accounts.stream()
                    .map(Graph::get)
                    .map(account -> new Account(
                            account.accountId(),
                            account.balance() + amount))
                    .toList();
        }
    }

    private record CreateAccounts(
            List<AccountId> accountIds,
            int balance) {
        @Apply
        List<Account> apply() {
            return accountIds.stream()
                    .map(accountId -> new Account(
                            accountId, balance))
                    .toList();
        }
    }

    @Model
    private record CollectionPeer(
            @EntityId CollectionPeerId id,
            String value) {
    }

    private static final class CollectionPeerId
            extends Id<CollectionPeer> {
        private CollectionPeerId(String id) {
            super(id, "collection-peer-");
        }
    }

    private record CreateMixedCollection(
            AccountId accountId,
            CollectionPeerId peerId) {
        @Apply
        List<Object> apply() {
            return List.of(
                    new Account(accountId, 7),
                    new CollectionPeer(peerId, "created"));
        }
    }

    private record TouchAccount(AccountId accountId) {
        @Apply
        Account apply(Account account) {
            return account;
        }
    }

    private record AlwaysTouchAccount(AccountId accountId) {
        @Apply(eventPublication = EventPublication.ALWAYS)
        Account apply(Account account) {
            return account;
        }
    }

    private record TargetedCredit(AccountId accountId, int amount) {
        @InterceptApply
        ApplyTargetedCredit intercept(Account account) {
            return new ApplyTargetedCredit(accountId, amount);
        }
    }

    private record ApplyTargetedCredit(AccountId accountId, int amount) {
        @Apply
        Account apply(Account account) {
            return new Account(account.accountId(), account.balance() + amount);
        }
    }

    private record TargetedCreditWithoutId(int amount) {
        @InterceptApply
        ApplyTargetedCreditWithoutId intercept(Account account) {
            return new ApplyTargetedCreditWithoutId(amount);
        }
    }

    private record ApplyTargetedCreditWithoutId(int amount) {
        @Apply
        Account apply(Account account) {
            return new Account(account.accountId(), account.balance() + amount);
        }
    }

    private record ReroutedTargetedCredit(AccountId accountId, int amount) {
        @InterceptApply
        Message intercept(Account account) {
            return new Message(new ApplyTargetedCredit(accountId, amount));
        }
    }

    @Value
    @NonFinal
    @SuperBuilder(toBuilder = true)
    @Jacksonized
    @Model
    private static class BaseCounter {
        @EntityId String counterId;
        int value;
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Jacksonized
    @Model
    private static class SpecialCounter extends BaseCounter {
        String marker;
    }

    private static final class SpecialCounterId extends Id<SpecialCounter> {
        private SpecialCounterId(String id) {
            super(id);
        }
    }

    private record CreateSpecialCounter(String counterId, int value) {
        @Apply
        SpecialCounter apply() {
            return SpecialCounter.builder()
                    .counterId(counterId).value(value).marker("special").build();
        }
    }

    private record CreateSpecialThroughBase(String counterId, int value) {
        @Apply
        BaseCounter apply() {
            return SpecialCounter.builder()
                    .counterId(counterId).value(value).marker("special").build();
        }
    }

    private record CreateTypedSpecial(SpecialCounterId counterId, int value) {
        @InterceptApply
        CreateTypedSpecial intercept(Graph<BaseCounter> graph) {
            return graph.isEmpty() ? this : null;
        }

        @Apply
        BaseCounter apply(@io.fluxzero.sdk.tracking.handling.Association("counterId")
                          Graph<BaseCounter> graph) {
            if (!graph.type().equals(SpecialCounter.class)) {
                throw new IllegalStateException(
                        "Typed subtype ID did not retain its model type");
            }
            return SpecialCounter.builder()
                    .counterId(counterId.getId()).value(value).marker("special").build();
        }
    }

    private record IncrementBaseCounter(String counterId, int amount) {
        @Apply
        BaseCounter apply(Graph<BaseCounter> graph) {
            BaseCounter current = graph.get();
            if (current instanceof SpecialCounter special) {
                return special.toBuilder().value(special.getValue() + amount).build();
            }
            return current.toBuilder().value(current.getValue() + amount).build();
        }
    }

    private static final AtomicReference<String> ASYNC_COMMIT_METADATA =
            new AtomicReference<>();

    private record CreateAsyncAccount(AccountId accountId, int balance) {
        @Apply
        Account apply(Metadata metadata) {
            ASYNC_COMMIT_METADATA.set(metadata.get("async-context"));
            return new Account(accountId, balance);
        }
    }

    private record ValidateOnlyAccount(
            AccountId accountId,
            int expectedBalance,
            List<String> observations) {
        @InterceptApply
        ValidateOnlyAccount intercept(Account account) {
            observations.add(
                    "intercept-" + account.balance());
            return this;
        }

        @AssertLegal
        void assertBalance(
                Account account,
                Metadata metadata) {
            observations.add(
                    "assert-" + account.balance()
                    + "-" + metadata.get(
                            "validation"));
            if (account.balance() != expectedBalance) {
                throw new IllegalStateException(
                        "Unexpected balance");
            }
        }

        @AssertLegal(afterHandler = true)
        void afterHandlerMustNotRun() {
            observations.add("after");
        }
    }

    private record ValidationResult(
            List<String> observations,
            Account account,
            long eventCount) {
        private ValidationResult {
            observations = List.copyOf(
                    observations);
        }
    }

    private record RemoteOnlyCommand(String id) {
    }

    private record DeleteAccount(AccountId accountId) {
        @Apply
        Account apply(Account account) {
            return null;
        }
    }

    private record ExplicitlyHandledCreate(AccountId accountId) {
        @Apply
        Account apply() {
            return new Account(accountId, 1);
        }
    }

    private static final class ExplicitHandler {
        @HandleCommand
        String handle(ExplicitlyHandledCreate command) {
            return "explicit";
        }
    }

    private record AffectedModelEventHandler(
            AtomicReference<List<Object>> handled) {
        @HandleEvent
        void on(
                CreateAccount event,
                Account account,
                Entity<Account> entity) {
            handled.set(List.of(account, entity.get()));
        }
    }

    private record ExplicitlyDelegatedCreate(
            AccountId accountId, int balance) {
        @Apply
        Account apply() {
            return new Account(accountId, balance);
        }
    }

    private static final class ExplicitDelegatingHandler {
        private static final AtomicInteger invocations =
                new AtomicInteger();

        @HandleCommand
        String handle(ExplicitlyDelegatedCreate command) {
            invocations.incrementAndGet();
            Fluxzero.assertAndApply(
                    command, Metadata.of("model-commit", "direct"));
            return "delegated";
        }
    }

    private record ExplicitlyDelegatedFailure(AccountId accountId) {
        @Apply
        Account apply() {
            throw new IllegalStateException("direct failure");
        }
    }

    private static final class ExplicitFailingDelegatingHandler {
        @HandleCommand
        void handle(ExplicitlyDelegatedFailure command) {
            Fluxzero.assertAndApply(command);
        }
    }

    private record AsyncDelegatedCreate(AccountId accountId) {
        @Apply
        Account apply(Metadata metadata) {
            ASYNC_COMMIT_METADATA.set(metadata.get("async-context"));
            return new Account(accountId, 71);
        }
    }

    private record AsyncDelegatedRequest(AccountId accountId) {
    }

    private static final class AsyncDelegatingHandler {
        @HandleCommand
        CompletableFuture<String> handle(AsyncDelegatedRequest command) {
            return Fluxzero.assertAndApplyAsync(
                            new AsyncDelegatedCreate(command.accountId()))
                    .thenApply(ignored -> "async");
        }
    }

    private record NestedDelegatedCreate(AccountId accountId) {
    }

    private static final class NestedDelegatingHandler {
        @HandleCommand
        String handle(NestedDelegatedCreate command) {
            return Fluxzero.sendCommandAndWait(
                    new ExplicitlyDelegatedCreate(
                            command.accountId(), 83));
        }
    }

    @Model
    private record ReceiverAccount(
            @EntityId ReceiverAccountId receiverAccountId, String name) {
        @Apply
        ReceiverAccount rename(RenameReceiverAccount command) {
            return new ReceiverAccount(receiverAccountId, command.name());
        }
    }

    private static final class ReceiverAccountId extends Id<ReceiverAccount> {
        private ReceiverAccountId(String id) {
            super(id, "receiver-account-");
        }
    }

    private record CreateReceiverAccount(
            ReceiverAccountId receiverAccountId, String name) {
        @Apply
        ReceiverAccount apply() {
            return new ReceiverAccount(receiverAccountId, name);
        }
    }

    private record RenameReceiverAccount(
            ReceiverAccountId receiverAccountId, String name) {
    }

    private record RenameReceiverAccounts(
            List<RenameReceiverAccount> commands) {
        @InterceptApply
        List<RenameReceiverAccount> intercept() {
            return commands;
        }
    }

    private record UntypedRenameReceiverAccounts(List<?> commands) {
        @InterceptApply
        List<?> intercept() {
            return commands;
        }
    }

    private static final class UntypedRenameReceiverAccountsHandler {
        @HandleCommand
        void handle(UntypedRenameReceiverAccounts command) {
            Fluxzero.assertAndApply(command);
        }
    }

    @Model(cached = false)
    private record StaticCreatedModel(
            @EntityId String staticCreatedModelId,
            String value) {

        @Apply
        static StaticCreatedModel create(
                CreateStaticModel command) {
            return new StaticCreatedModel(
                    command.staticCreatedModelId(),
                    "created");
        }
    }

    private record CreateStaticModel(
            String staticCreatedModelId) {
    }

    @Model(cached = false)
    private record IntegratedPhasedModel(
            @EntityId IntegratedPhasedModelId integratedPhasedModelId,
            String value) {
        @Apply
        IntegratedPhasedModel finish(
                CreateIntegratedPhasedModel command) {
            return new IntegratedPhasedModel(
                    integratedPhasedModelId, value + "-model");
        }
    }

    private static class IntegratedPhasedModelId
            extends Id<IntegratedPhasedModel> {
        private IntegratedPhasedModelId(String id) {
            super(id, "integrated-phased-");
        }
    }

    private record CreateIntegratedPhasedModel(
            IntegratedPhasedModelId integratedPhasedModelId) {
        @Apply
        IntegratedPhasedModel create() {
            return new IntegratedPhasedModel(
                    integratedPhasedModelId, "payload");
        }
    }

    private record FailingCreate(AccountId accountId) {
        @Apply
        Account apply() {
            throw new IllegalStateException("failed");
        }
    }

    @Model(eventSourced = false, searchable = true)
    private record Inventory(
            @EntityId InventoryId inventoryId, int available) {
    }

    private static final class InventoryId extends Id<Inventory> {
        private InventoryId(String id) {
            super(id, "inventory-");
        }
    }

    private record CreateInventory(
            InventoryId inventoryId, int available) {
        @Apply
        Inventory apply() {
            return new Inventory(inventoryId, available);
        }
    }

    private record ChangeInventory(
            InventoryId inventoryId, int delta) {
        @Apply
        Inventory apply(Inventory inventory) {
            return new Inventory(
                    inventoryId, inventory.available() + delta);
        }
    }

    @Model(eventSourced = false)
    private record FixedDocument(
            @EntityId FixedDocumentId id, int value) {
        private FixedDocument(int value) {
            this(new FixedDocumentId(), value);
        }
    }

    private static final class FixedDocumentId extends Id<FixedDocument> {
        private FixedDocumentId() {
            super("fixed-document");
        }
    }

    @Model(cached = false)
    private record Order(
            @EntityId OrderId orderId, int observedInventory) {
    }

    private static final class OrderId extends Id<Order> {
        private OrderId(String id) {
            super(id, "order-");
        }
    }

    private record CreateOrder(
            OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order apply(Inventory inventory) {
            return new Order(orderId, inventory.available());
        }
    }

    private record CreateCheckedOrder(
            OrderId orderId,
            InventoryId inventoryId,
            int expectedInventory) {
        @AssertLegal
        void assertInventory(Inventory inventory) {
            if (inventory.available()
                != expectedInventory) {
                throw new IllegalStateException(
                        "Unexpected inventory");
            }
        }

        @Apply
        Order apply(Inventory inventory) {
            return new Order(
                    orderId, inventory.available());
        }
    }

    @Model(
            eventSourced = false,
            searchable = true,
            eventPublication = EventPublication.NEVER)
    private record PrivateInventory(
            @EntityId PrivateInventoryId inventoryId, int available) {
    }

    private static final class PrivateInventoryId
            extends Id<PrivateInventory> {
        private PrivateInventoryId(String id) {
            super(id, "private-inventory-");
        }
    }

    private record CreatePrivateInventory(
            PrivateInventoryId inventoryId, int available) {
        @Apply
        PrivateInventory apply() {
            return new PrivateInventory(inventoryId, available);
        }
    }

    @Model(cached = false)
    private record PrivateOrder(
            @EntityId PrivateOrderId orderId, int observedInventory) {
    }

    private static final class PrivateOrderId extends Id<PrivateOrder> {
        private PrivateOrderId(String id) {
            super(id, "private-order-");
        }
    }

    private record CreatePrivateOrder(
            PrivateOrderId orderId,
            PrivateInventoryId inventoryId) {
        @Apply
        PrivateOrder apply(PrivateInventory inventory) {
            return new PrivateOrder(orderId, inventory.available());
        }
    }

    private static final AtomicInteger receiverInvocations =
            new AtomicInteger();

    @Model
    private record FirstCounter(
            @EntityId FirstCounterId firstCounterId, int value) {
        @Apply
        FirstCounter increment(IncrementBoth command) {
            if (!Entity.isLoading()) {
                receiverInvocations.incrementAndGet();
            }
            return new FirstCounter(firstCounterId, value + 1);
        }
    }

    private static final class FirstCounterId extends Id<FirstCounter> {
        private FirstCounterId(String id) {
            super(id, "first-counter-");
        }
    }

    private record CreateFirstCounter(FirstCounterId firstCounterId) {
        @Apply
        FirstCounter apply() {
            return new FirstCounter(firstCounterId, 0);
        }
    }

    @Model
    private record SecondCounter(
            @EntityId SecondCounterId secondCounterId, int value) {
        @Apply
        SecondCounter increment(IncrementBoth command) {
            if (!Entity.isLoading()) {
                receiverInvocations.incrementAndGet();
            }
            return new SecondCounter(secondCounterId, value + 1);
        }
    }

    private static final class SecondCounterId extends Id<SecondCounter> {
        private SecondCounterId(String id) {
            super(id, "second-counter-");
        }
    }

    private record CreateSecondCounter(
            SecondCounterId secondCounterId) {
        @Apply
        SecondCounter apply() {
            return new SecondCounter(secondCounterId, 0);
        }
    }

    private record IncrementBoth(
            FirstCounterId firstCounterId,
            SecondCounterId secondCounterId) {
    }

    @Model
    private record RootConsumerModel(
            @EntityId String rootConsumerModelId,
            String consumerName) {
        @Apply
        RootConsumerModel apply(
                RootConsumerModelCommand command) {
            return new RootConsumerModel(
                    rootConsumerModelId,
                    Tracker.current().orElseThrow()
                            .getName());
        }
    }

    private record CreateRootConsumerModel(
            String rootConsumerModelId) {
        @Apply
        RootConsumerModel apply() {
            return new RootConsumerModel(
                    rootConsumerModelId, null);
        }
    }

    @Model(searchable = true)
    private record FamilyRoot(
            @EntityId FamilyRootId familyRootId,
            String name) {
    }

    private static final class FamilyRootId
            extends Id<FamilyRoot> {
        private FamilyRootId(String id) {
            super(id, "family-root-");
        }
    }

    private record CreateFamilyRoot(
            FamilyRootId familyRootId, String name) {
        @Apply
        FamilyRoot apply() {
            return new FamilyRoot(familyRootId, name);
        }
    }

    private record DeleteFamilyRoot(FamilyRootId familyRootId) {
        @Apply
        FamilyRoot apply(FamilyRoot current) {
            return null;
        }
    }

    private record MoveFamilyChild(
            FamilyChildId familyChildId,
            FamilyRootId familyRootId) {
        @Apply
        FamilyChild apply(FamilyChild current) {
            return new FamilyChild(
                    current.familyChildId(), familyRootId,
                    current.name());
        }
    }

    private record RenameFamily(
            FamilyRootId familyRootId,
            FamilyChildId familyChildId) {
        @InterceptApply
        List<?> intercept(Graph<FamilyChild> child) {
            return List.of(
                    this,
                    child.update(current -> new FamilyChild(
                            current.familyChildId(),
                            current.familyRootId(),
                            "after-child")));
        }

        @Apply
        FamilyRoot apply(FamilyRoot current) {
            return new FamilyRoot(current.familyRootId(), "after-root");
        }
    }

    private record DeleteRootAndMoveChild(
            FamilyRootId familyRootId,
            FamilyChildId familyChildId,
            FamilyRootId newFamilyRootId) {
        @InterceptApply
        List<Object> intercept() {
            return List.of(
                    new DeleteFamilyRoot(familyRootId),
                    new MoveFamilyChild(familyChildId, newFamilyRootId));
        }
    }

    private record ReplaceFamilyRoot(
            FamilyRootId familyRootId,
            String name) {
        @InterceptApply
        List<Object> intercept() {
            return List.of(
                    new DeleteFamilyRoot(familyRootId),
                    new CreateFamilyRoot(familyRootId, name));
        }
    }

    @Model
    private record PathlessFamilyChild(
            @EntityId String id,
            @Parent FamilyRootId familyRootId) {
    }

    private record CreatePathlessFamilyChild(
            String id,
            FamilyRootId familyRootId) {
        @Apply
        PathlessFamilyChild apply() {
            return new PathlessFamilyChild(id, familyRootId);
        }
    }

    @Model
    private record RetainedFamilyChild(
            @EntityId String id,
            @Parent(deleteOnParentDeletion = false)
            FamilyRootId familyRootId) {
    }

    private record CreateRetainedFamilyChild(
            String id,
            FamilyRootId familyRootId) {
        @Apply
        RetainedFamilyChild apply() {
            return new RetainedFamilyChild(id, familyRootId);
        }
    }

    @Model(searchable = true)
    private record AffixedRoot(
            @EntityId(prefix = "move-", postfix = "-state") AffixedRootId affixedRootId) {
    }

    private static final class AffixedRootId extends Id<AffixedRoot> {
        private AffixedRootId(String id) {
            super(id, "root-");
        }
    }

    private record CreateAffixedRoot(AffixedRootId affixedRootId) {
        @Apply
        AffixedRoot apply() {
            return new AffixedRoot(affixedRootId);
        }
    }

    private record TouchAffixedRoot() {
        @Apply
        AffixedRoot apply(Graph<AffixedRoot> graph) {
            return graph.get();
        }
    }

    @Model
    private record ScopedNote(
            @EntityId(parentScoped = true) String noteId,
            @Parent(path = "notes") FamilyRootId familyRootId,
            int value) {
    }

    private record CreateScopedNote(
            String noteId,
            FamilyRootId familyRootId,
            int value) {
        @Apply
        ScopedNote apply() {
            return new ScopedNote(noteId, familyRootId, value);
        }
    }

    private record IncrementScopedNote(int amount) {
        @Apply
        ScopedNote apply(Graph<ScopedNote> graph) {
            ScopedNote note = graph.get();
            return new ScopedNote(
                    note.noteId(), note.familyRootId(),
                    note.value() + amount);
        }
    }

    @Model
    private record AffixedChild(
            @EntityId(prefix = "nested-") AffixedChildId affixedChildId,
            @Parent(path = "children") AffixedRootId affixedRootId) {
    }

    private static final class AffixedChildId extends Id<AffixedChild> {
        private AffixedChildId(String id) {
            super(id, "child-");
        }
    }

    private record CreateAffixedChild(
            AffixedChildId affixedChildId,
            AffixedRootId affixedRootId) {
        @Apply
        AffixedChild apply() {
            return new AffixedChild(affixedChildId, affixedRootId);
        }
    }

    @Model
    private record AffixedCompanion(
            @EntityId(prefix = "companion-")
            @Parent(path = "companion")
            AffixedRootId affixedRootId,
            String status) {
    }

    private record CreateAffixedCompanion(
            AffixedRootId affixedRootId,
            String status) {
        @Apply
        AffixedCompanion apply() {
            return new AffixedCompanion(affixedRootId, status);
        }
    }

    @Model(searchable = true)
    private record FamilyChild(
            @EntityId FamilyChildId familyChildId,
            @Parent(path = "children")
            FamilyRootId familyRootId,
            String name) {
    }

    private static final class FamilyChildId
            extends Id<FamilyChild> {
        private FamilyChildId(String id) {
            super(id, "family-child-");
        }
    }

    private record CreateFamilyChild(
            FamilyChildId familyChildId,
            FamilyRootId familyRootId,
            String name) {
        @Apply
        FamilyChild apply() {
            return new FamilyChild(
                    familyChildId, familyRootId, name);
        }
    }

    @Model(eventSourced = false, searchable = true)
    private record DocumentFamilyChild(
            @EntityId DocumentFamilyChildId documentFamilyChildId,
            @Parent(path = "documentChildren")
            FamilyRootId familyRootId,
            String name) {
    }

    private static final class DocumentFamilyChildId
            extends Id<DocumentFamilyChild> {
        private DocumentFamilyChildId(String id) {
            super(id, "document-family-child-");
        }
    }

    private record CreateDocumentFamilyChild(
            DocumentFamilyChildId documentFamilyChildId,
            FamilyRootId familyRootId,
            String name) {
        @Apply
        DocumentFamilyChild apply() {
            return new DocumentFamilyChild(
                    documentFamilyChildId, familyRootId, name);
        }
    }

    @Model(cached = false, searchable = true)
    private record FamilyGrandchild(
            @EntityId FamilyGrandchildId familyGrandchildId,
            @Parent(path = "primaryGrandchildren")
            FamilyChildId primaryId,
            @Parent(path = "secondaryGrandchildren")
            FamilyChildId secondaryId,
            String observations) {
    }

    private static final class FamilyGrandchildId
            extends Id<FamilyGrandchild> {
        private FamilyGrandchildId(String id) {
            super(id, "family-grandchild-");
        }
    }

    private record CreateFamilyGrandchild(
            FamilyGrandchildId familyGrandchildId,
            FamilyChildId primaryId,
            FamilyChildId secondaryId) {
        @Apply
        FamilyGrandchild apply() {
            return new FamilyGrandchild(
                    familyGrandchildId, primaryId,
                    secondaryId, "");
        }
    }

    private record MoveFamilyGrandchild(
            FamilyGrandchildId familyGrandchildId,
            FamilyChildId newPrimaryId) {
        @Apply
        FamilyGrandchild apply(
                FamilyGrandchild grandchild) {
            return new FamilyGrandchild(
                    grandchild.familyGrandchildId(),
                    newPrimaryId,
                    grandchild.secondaryId(),
                    grandchild.observations());
        }
    }

    private record ObserveAmbiguousFamily(
            FamilyGrandchildId familyGrandchildId) {
        @Apply
        FamilyGrandchild apply(
                FamilyGrandchild grandchild,
                FamilyChild ambiguousParent) {
            return grandchild;
        }
    }

    private record MoveAndObserveFamily(
            List<FamilyStep> steps) {
        @InterceptApply
        List<FamilyStep> intercept() {
            return steps;
        }
    }

    private record FamilyStep(
            FamilyGrandchildId familyGrandchildId,
            FamilyChildId newPrimaryId,
            boolean observe) {
        @Apply
        FamilyGrandchild apply(
                FamilyGrandchild grandchild,
                @io.fluxzero.sdk.tracking.handling.Association(
                        "primaryGrandchildren")
                FamilyChild parent,
                FamilyRoot grandparent) {
            return new FamilyGrandchild(
                    grandchild.familyGrandchildId(),
                    observe
                            ? grandchild.primaryId()
                            : newPrimaryId,
                    grandchild.secondaryId(),
                    observe
                            ? "same-commit:" + parent.name()
                              + "/" + grandparent.name()
                            : grandchild.observations());
        }
    }

    private record ObserveFamily(
            FamilyGrandchildId familyGrandchildId) {
        @AssertLegal
        void assertFamily(
                FamilyGrandchild grandchild,
                @io.fluxzero.sdk.tracking.handling.Association(
                        "primaryGrandchildren")
                FamilyChild parent,
                FamilyRoot grandparent) {
            if (!"primary".equals(parent.name())
                || !"root".equals(grandparent.name())) {
                throw new IllegalStateException(
                        "Unexpected family ancestor");
            }
        }

        @InterceptApply
        ObservedFamily intercept(
                FamilyGrandchild grandchild,
                @io.fluxzero.sdk.tracking.handling.Association(
                        "primaryGrandchildren")
                FamilyChild parent,
                Entity<FamilyRoot> grandparent) {
            return new ObservedFamily(
                    familyGrandchildId,
                    "assert:" + parent.name()
                    + "/" + grandparent.get().name()
                    + "|intercept:" + parent.name()
                    + "/" + grandparent.get().name());
        }
    }

    private record ObservedFamily(
            FamilyGrandchildId familyGrandchildId,
            String observations) {
        @Apply
        FamilyGrandchild apply(
                FamilyGrandchild grandchild,
                @io.fluxzero.sdk.tracking.handling.Association(
                        "primaryGrandchildren")
                FamilyChild parent,
                FamilyRoot grandparent) {
            return new FamilyGrandchild(
                    grandchild.familyGrandchildId(),
                    grandchild.primaryId(),
                    grandchild.secondaryId(),
                    observations + "|apply:" + parent.name()
                    + "/" + grandparent.name());
        }
    }

    @Model(
            searchable = true,
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "projectionRoots",
                    pathOverrides = @GraphPathOverride(
                            path = "children",
                            projectionPath = "projectedChildren")))
    private record ProjectionRoot(
            @EntityId ProjectionRootId projectionRootId) {
    }

    private static final class ProjectionRootId
            extends Id<ProjectionRoot> {
        private ProjectionRootId(String id) {
            super(id, "projection-root-");
        }
    }

    private record CreateProjectionRoot(
            ProjectionRootId projectionRootId) {
        @Apply
        ProjectionRoot apply() {
            return new ProjectionRoot(
                    projectionRootId);
        }
    }

    private record DeleteProjectionRoot(
            ProjectionRootId projectionRootId) {
        @Apply
        ProjectionRoot apply(ProjectionRoot current) {
            return null;
        }
    }

    @Model
    private record ProjectionChild(
            @EntityId ProjectionChildId projectionChildId,
            @Parent(path = "children")
            ProjectionRootId projectionRootId) {

        @FilterContent
        ProjectionChild filter(
                Graph<ProjectionChild> child,
                Graph<ProjectionRoot> root) {
            return child.get() == this
                   && projectionRootId.equals(
                           root.get().projectionRootId())
                    ? this : null;
        }
    }

    private static final class ProjectionChildId
            extends Id<ProjectionChild> {
        private ProjectionChildId(String id) {
            super(id, "projection-child-");
        }
    }

    private record CreateProjectionChild(
            ProjectionChildId projectionChildId,
            ProjectionRootId projectionRootId) {
        @Apply
        ProjectionChild apply() {
            return new ProjectionChild(
                    projectionChildId,
                    projectionRootId);
        }
    }

    private record MoveProjectionChild(
            ProjectionChildId projectionChildId,
            ProjectionRootId projectionRootId) {
        @Apply
        ProjectionChild apply(
                ProjectionChild current) {
            return new ProjectionChild(
                    current.projectionChildId(),
                    projectionRootId);
        }
    }

    private record DeleteProjectionChild(
            ProjectionChildId projectionChildId) {
        @Apply
        ProjectionChild apply(
                ProjectionChild current) {
            return null;
        }
    }

    private static final class InspectableCache
            extends AdaptiveObjectCache {
        private InspectableCache() {
            super(100);
        }

        @Override
        public Cache rebuild() {
            return this;
        }
    }
}
