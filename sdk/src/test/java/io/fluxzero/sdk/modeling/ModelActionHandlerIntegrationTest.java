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

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.root.RootConsumerModelCommand;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ModelActionHandlerIntegrationTest {

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
    void existingCommandHandlerWinsOverModelActionFallback() {
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
                                 event.getMetadata().get("model-action"));
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
                TestFixture.create(new AsyncDelegatingHandler());
        AccountId accountId = new AccountId("direct-async");

        fixture.whenCommand(new AsyncDelegatedCreate(accountId))
                .expectResult("async")
                .expectTrue(fluxzero -> new Account(accountId, 71)
                        .equals(fluxzero.modelRepository()
                                        .load(accountId).get()));
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
    void receiverApplyUsesConsumerConfiguredForCommandRootPackage()
            throws Throwable {
        LocalClient client = LocalClient.newInstance(null);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            String modelId = "root-consumer-model";
            fluxzero.commandGateway().send(
                    new CreateRootConsumerModel(modelId)).join();
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
                    List<com.fasterxml.jackson.databind.node.ObjectNode>
                            graphs =
                            Fluxzero.searchGraph(
                                            FamilyRoot.class)
                            .constraint(match(
                                    "composed", true,
                                    "children/primaryGrandchildren/familyGrandchildId"))
                            .includeOnly("children")
                            .fetch(1);
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
                                        List<com.fasterxml.jackson.databind.node.ObjectNode>
                                                stored =
                                                Fluxzero.searchGraph(
                                                                ProjectionRoot.class)
                                                        .fetch(1);
                                        List<com.fasterxml.jackson.databind.node.ObjectNode>
                                                live =
                                                Fluxzero.searchGraph(
                                                                ProjectionRoot.class,
                                                                true)
                                                        .fetch(1);
                                        return "payload"
                                                       .equals(
                                                               stored.getFirst()
                                                                       .at("/projectedChildren/0/projectionChildId")
                                                                       .asText())
                                               && stored.equals(
                                                       live);
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
    void laterInterceptorSubstepSeesAParentMovedEarlierInTheSameAction() {
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
                            "same-action:second/root");
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
    void trackedPayloadApplyUsesTheSameModelActionPath() throws Throwable {
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
            fluxzero.commandGateway().send(
                    new CreateReceiverAccount(accountId, "before")).join();
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
    void trackedCrossModelReceiverActionExecutesOnlyOnce()
            throws Throwable {
        LocalClient client = LocalClient.newInstance(null);
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            FirstCounterId firstId = new FirstCounterId("tracked");
            SecondCounterId secondId = new SecondCounterId("tracked");
            fluxzero.commandGateway().send(
                    new CreateFirstCounter(firstId)).join();
            fluxzero.commandGateway().send(
                    new CreateSecondCounter(secondId)).join();
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
                    command, Metadata.of("model-action", "direct"));
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
        Account apply() {
            return new Account(accountId, 71);
        }
    }

    private static final class AsyncDelegatingHandler {
        @HandleCommand
        CompletableFuture<String> handle(AsyncDelegatedCreate command) {
            Fluxzero.assertAndApply(command);
            return CompletableFuture.completedFuture("async");
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

    @Model(searchable = true)
    private record FamilyChild(
            @EntityId FamilyChildId familyChildId,
            @ParentId(path = "children")
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

    @Model(cached = false, searchable = true)
    private record FamilyGrandchild(
            @EntityId FamilyGrandchildId familyGrandchildId,
            @ParentId(path = "primaryGrandchildren")
            FamilyChildId primaryId,
            @ParentId(path = "secondaryGrandchildren")
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
                            ? "same-action:" + parent.name()
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

    @Model
    private record ProjectionChild(
            @EntityId ProjectionChildId projectionChildId,
            @ParentId(path = "children")
            ProjectionRootId projectionRootId) {
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
