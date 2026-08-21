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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.DeleteModel;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelAncestors;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelDeletionPlan;
import io.fluxzero.common.api.modeling.ModelDeletionResult;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.caching.AdaptiveObjectCache;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.MemoryPressureController;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.CommitAttempt;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.EventPublicationStrategy;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProjection;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.ModelRoot;
import io.fluxzero.sdk.modeling.ModelCommitTestBuilder;
import io.fluxzero.sdk.modeling.ParentId;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.COMMAND;

class DefaultModelRepositoryTest {

    private final Client client = mock(Client.class);
    private final DocumentStore documentStore = mock(DocumentStore.class);
    private final DefaultModelRepository repository = new DefaultModelRepository(client, documentStore);

    @Test
    void delegatesDeletionPlanningUsingTheExactId() {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ModelDeletionPlan expected =
                new ModelDeletionPlan(
                        1L, "product-1",
                        ModelDeletionCascade.DESCENDANTS,
                        12, 5_000,
                        12L, "fingerprint",
                        3, 1, 5L, 2L,
                        List.of("product-1"));
        when(client.getEventStoreClient())
                .thenReturn(eventStore);
        when(eventStore.planModelDeletion(any()))
                .thenReturn(expected);

        ModelDeletionPlan result =
                repository.planDeletion(
                        new ProductId("1"),
                        ModelDeletionCascade.DESCENDANTS);

        assertEquals(expected, result);
        verify(eventStore)
                .planModelDeletion(
                        org.mockito.ArgumentMatchers.argThat(
                                request ->
                                        "product-1".equals(
                                                request.getModelId())
                                        && request.getCascade()
                                           == ModelDeletionCascade.DESCENDANTS));
    }

    @Test
    void ownsIdempotentGraphProjectionRegistrationAndRetriesFailures() {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ModelGraphProjectionStatus status =
                new ModelGraphProjectionStatus(
                        0L, "repository-graphs",
                        -1L, -1L, 0L, 0L, false);
        when(client.getEventStoreClient()).thenReturn(eventStore);
        when(eventStore.registerModelGraphProjection(any()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("temporarily unavailable")),
                        CompletableFuture.completedFuture(status),
                        CompletableFuture.completedFuture(status));

        assertThrows(
                CompletionException.class,
                () -> repository.registerGraphProjection(
                        ProjectedRoot.class, false).join());
        assertEquals(status, repository.registerGraphProjection(
                ProjectedRoot.class, false).join());
        assertEquals(status, repository.registerGraphProjection(
                ProjectedRoot.class, false).join());
        assertEquals(status, repository.registerGraphProjection(
                ProjectedRoot.class, true).join());

        var requests = org.mockito.ArgumentCaptor.forClass(
                io.fluxzero.common.api.modeling.RegisterModelGraphProjection.class);
        verify(eventStore, times(3))
                .registerModelGraphProjection(requests.capture());
        assertEquals(
                List.of(false, false, true),
                requests.getAllValues().stream()
                        .map(io.fluxzero.common.api.modeling.RegisterModelGraphProjection::isRebuild)
                        .toList());
    }

    @Test
    void delegatesGraphProjectionAwaitToItsDurableCapability() {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ModelGraphProjectionStatus status =
                new ModelGraphProjectionStatus(
                        0L, "repository-graphs",
                        3L, 5L, 0L, 0L, false);
        when(client.getEventStoreClient()).thenReturn(eventStore);
        when(eventStore.awaitModelGraphProjection(any()))
                .thenReturn(CompletableFuture.completedFuture(status));

        Map<Class<?>, Set<String>> projections = new LinkedHashMap<>();
        projections.put(ProjectedRoot.class, Set.of("root-1"));
        projections.put(AlternateProjectedRoot.class, Set.of("root-2"));

        repository.awaitGraphProjections(projections, 3L, 5L).join();
        verify(eventStore).awaitModelGraphProjection(
                org.mockito.ArgumentMatchers.argThat(request ->
                        "repository-graphs".equals(request.getCollection())
                        && request.getFirstStateIndex() == 3L
                        && request.getStateIndex() == 5L
                        && Set.copyOf(request.getModelIds())
                                .equals(Set.of("root-1", "root-2"))));
    }

    @Test
    void deletionPlanningComposesEntityIdAndTypedIdAffixes() {
        EventStoreClient eventStore = mock(EventStoreClient.class);
        ModelDeletionPlan expected = new ModelDeletionPlan(
                1L, "move-affixed-1-state", ModelDeletionCascade.NONE,
                0, 1, 1L, "fingerprint", 1, 0, 1L, 0L,
                List.of("move-affixed-1-state"));
        when(client.getEventStoreClient()).thenReturn(eventStore);
        when(eventStore.planModelDeletion(any())).thenReturn(expected);

        assertEquals(expected, repository.planDeletion(
                new AffixedDeletionId("1"), ModelDeletionCascade.NONE));
        verify(eventStore).planModelDeletion(
                org.mockito.ArgumentMatchers.argThat(request ->
                        "move-affixed-1-state".equals(request.getModelId())));
    }

    @Test
    void executesConfirmedDeletionPlanWithExplicitIdempotencyKey() {
        EventStoreClient eventStore =
                mock(EventStoreClient.class);
        ModelDeletionPlan plan =
                new ModelDeletionPlan(
                        1L, "product-1",
                        ModelDeletionCascade.DESCENDANTS,
                        12, 5_000,
                        12L, "fingerprint",
                        3, 1, 5L, 2L,
                        List.of("product-1"));
        ModelDeletionResult expected =
                new ModelDeletionResult(
                        2L, "erasure-1",
                        ModelDeletionCascade.DESCENDANTS,
                        13L, 3, 5L, 2L,
                        false);
        when(client.getEventStoreClient())
                .thenReturn(eventStore);
        when(eventStore.deleteModel(any()))
                .thenReturn(
                        java.util.concurrent.CompletableFuture
                                .completedFuture(expected));

        ModelDeletionResult result =
                repository.deleteModel(
                                "erasure-1", plan)
                        .join();

        assertEquals(expected, result);
        verify(eventStore).deleteModel(
                org.mockito.ArgumentMatchers
                        .argThat(request ->
                                         "erasure-1".equals(
                                                 request.getDeletionId())
                                         && "product-1".equals(
                                                 request.getModelId())
                                         && "fingerprint".equals(
                                                 request.getPlanFingerprint())
                                         && request.getMaxDepth()
                                            == 12
                                         && request.getMaxModels()
                                            == 5_000));
    }

    @Test
    void refusesUnplannedDescendantDeletion() {
        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () -> repository.deleteModel(
                                        new ProductId("1"),
                                        ModelDeletionCascade.DESCENDANTS)
                                .join());

        assertInstanceOf(
                IllegalArgumentException.class,
                failure.getCause());
    }

    @Test
    void loadsDocumentBasedModelFromItsDirectSearchCollection() {
        ProductId id = new ProductId("1");
        Product product = new Product(id, "first");
        when(documentStore.fetchDocument(id.toString(), "products", Product.class))
                .thenReturn(Optional.of(product));

        var result = repository.load(id);

        assertEquals(id.toString(), result.id());
        assertEquals(Product.class, result.type());
        assertEquals(product, result.get());
        assertEquals("productId", result.idProperty());
        verify(documentStore).fetchDocument(id.toString(), "products", Product.class);
    }

    @Test
    void missingDirectDocumentReturnsTypedEmptyEntity() {
        ProductId id = new ProductId("missing");
        when(documentStore.fetchDocument(id.toString(), "products", Product.class))
                .thenReturn(Optional.empty());

        var result = repository.load(id);

        assertEquals(id.toString(), result.id());
        assertEquals(Product.class, result.type());
        assertFalse(result.isPresent());
    }

    @Test
    void rejectsDocumentWhoseEntityIdDoesNotMatchStorageKey() {
        when(documentStore.fetchDocument("product-1", "products", Product.class))
                .thenReturn(Optional.of(new Product(new ProductId("other"), "wrong")));

        EventSourcingException exception = assertThrows(
                EventSourcingException.class,
                () -> repository.load("product-1", Product.class));

        assertEquals(
                "Stored model document 'product-1' reports @EntityId 'product-other'",
                exception.getMessage());
    }

    @Test
    void documentOnlyRepositoryRequiresReconstructionComponentsForEventSourcedModel() {
        EventSourcingException exception = assertThrows(
                EventSourcingException.class,
                () -> repository.load("account-1", Account.class));

        assertEquals(
                "Event-sourced model reconstruction requires a configured serializer and model entity helper",
                exception.getMessage());
    }

    @Test
    void untypedIdWaitsForModelHeadLookupProtocol() {
        EventSourcingException exception = assertThrows(
                EventSourcingException.class,
                () -> repository.load("product-1", Object.class));

        assertEquals(
                "Loading an independent model by untyped ID requires model-head type metadata",
                exception.getMessage());
    }

    @Test
    void untypedLoadResolvesStoredModelTypeThroughSerializerAlias() {
        AliasedAccountId id =
                new AliasedAccountId("renamed-type");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.serializer().registerTypeCaster(
                    "legacy.example.AliasedAccount",
                    AliasedAccount.class.getName());
            ModelCommitStep substep =
                    ModelCommitStep.builder()
                            .event(new Message(
                                    new CreateAliasedAccount(
                                            id, 7))
                                           .serialize(
                                                   fluxzero.serializer()))
                            .targets(List.of(
                                    ModelCommitTarget.builder()
                                            .modelId(
                                                    id.toString())
                                            .modelType(
                                                    "legacy.example.AliasedAccount")
                                            .storeEvent(false)
                                            .updateState(true)
                                            .relationships(
                                                    List.of())
                                            .build()))
                            .build();
            CommitModelsResult result =
                    fluxzero.client()
                            .getEventStoreClient()
                            .commitModels(
                                    new CommitModels(
                                            "renamed-type",
                                            -1L,
                                            List.of(
                                                    id.toString()),
                                            List.of(substep),
                                            ModelConflictPolicy.ACCEPT,
                                            Guarantee.STORED, true))
                            .join();
            assertTrue(result.isAccepted());
            fluxzero.documentStore().index(
                    new AliasedAccount(id, 7),
                    id, "aliasedAccounts").join();

            Entity<Object> loaded =
                    fluxzero.modelRepository()
                            .load(id.toString(),
                                  Object.class);

            assertEquals(AliasedAccount.class,
                         loaded.type());
            assertEquals(new AliasedAccount(id, 7),
                         loaded.get());
        }
    }

    @Test
    void missingUntypedModelReturnsEmptyObjectEntity() {
        try (Fluxzero fluxzero = configuredFluxzero()) {
            Entity<Object> loaded =
                    fluxzero.modelRepository()
                            .load("missing-model");

            assertEquals("missing-model", loaded.id());
            assertEquals(Object.class, loaded.type());
            assertTrue(loaded.isEmpty());
            assertEquals(-1L, loaded.sequenceNumber());
            assertEquals(
                    -1L,
                    assertInstanceOf(
                            ModelRoot.class,
                            loaded).stateIndex());
        }
    }

    @Test
    void reconstructsCheckpointedStreamsInParallelWithinOneReplaySession() {
        List<CreateCheckpointAccount> creates = IntStream.range(0, 256)
                .mapToObj(index -> new CreateCheckpointAccount(
                        new CheckpointAccountId(Integer.toString(index)), index))
                .toList();
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway()
                    .send(new CreateCheckpointAccounts(creates))
                    .join();
            List<CheckpointAccountId> ids = creates.stream()
                    .map(CreateCheckpointAccount::accountId)
                    .toList();
            DefaultModelRepository repository =
                    (DefaultModelRepository) fluxzero.modelRepository();
            repository.invalidateModels(ids.stream().map(Object::toString).toList());

            List<Entity<CheckpointAccount>> loaded =
                    repository.loadAll(ids, CheckpointAccount.class);

            assertEquals(
                    creates.stream()
                            .map(create -> new CheckpointAccount(
                                    create.accountId(), create.balance()))
                            .toList(),
                    loaded.stream().map(Entity::get).toList());
        }
    }

    @Test
    void firstModelStateRequiresAStoredType() {
        try (Fluxzero fluxzero = configuredFluxzero()) {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> fluxzero.client()
                            .getEventStoreClient().commitModels(
                                    new CommitModels(
                                            "untyped-head",
                                            -1L,
                                            List.of("untyped-head"),
                                            List.of(
                                                    ModelCommitStep.builder()
                                                            .event(new Message("state")
                                                                           .serialize(
                                                                                   fluxzero.serializer()))
                                                            .targets(List.of(
                                                                    ModelCommitTarget.builder()
                                                                            .modelId("untyped-head")
                                                                            .storeEvent(false)
                                                                            .updateState(true)
                                                                            .relationships(List.of())
                                                                            .build()))
                                                    .build()),
                                            ModelConflictPolicy.ACCEPT,
                                            Guarantee.STORED, true)).join());

            assertEquals(
                    "Model untyped-head has no type",
                    failure.getCause().getMessage());
        }
    }

    @Test
    void untypedLoadDoesNotOverrideTheDurableModelTypeFromPayloadHandlers() {
        AccountId id =
                new AccountId("payload-type");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            ModelCommitStep substep =
                    ModelCommitStep.builder()
                            .event(new Message(
                                    new CreateAccount(id, 9))
                                           .serialize(
                                                   fluxzero.serializer()))
                            .targets(List.of(
                                    ModelCommitTarget.builder()
                                            .modelId(id.toString())
                                            .modelType(
                                                    "missing.example.Account")
                                            .storeEvent(true)
                                            .updateState(true)
                                            .relationships(List.of())
                                            .build()))
                            .build();
            CommitModelsResult result =
                    fluxzero.client()
                            .getEventStoreClient()
                            .commitModels(
                                    new CommitModels(
                                            "payload-type",
                                            -1L,
                                            List.of(id.toString()),
                                            List.of(substep),
                                            ModelConflictPolicy.ACCEPT,
                                            Guarantee.STORED, true))
                            .join();
            assertTrue(result.isAccepted());

            EventSourcingException failure = assertThrows(
                    EventSourcingException.class,
                    () -> fluxzero.modelRepository()
                            .load(id.toString(), Object.class));

            assertTrue(failure.getMessage().contains(
                    "Could not resolve stored model type 'missing.example.Account'"));
        }
    }

    @Test
    void reconstructsEventSourcedModelFromItsIndependentStream() {
        AccountId id = new AccountId("one");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            long created = commit(
                    fluxzero, "create", -1L,
                    new CreateAccount(id, 5), Account.class, id.toString());
            commit(fluxzero, "change", created,
                   new ChangeAccount(id, 2), Account.class, id.toString());

            var result = fluxzero.modelRepository().load(id);

            assertEquals(new Account(id, 7), result.get());
            assertEquals(1L, result.sequenceNumber());
        }
    }

    @Test
    void staleAcceptedEventReconstructsAgainstItsRebasedReadBoundary() {
        AccountId id = new AccountId("stale");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            long created = commit(
                    fluxzero, "create-stale", -1L,
                    new CreateAccount(id, 0), Account.class, id.toString());
            commit(fluxzero, "first-writer", created,
                   new ChangeAccount(id, 1), Account.class, id.toString());
            commit(fluxzero, "stale-writer", created,
                   new ChangeAccount(id, 10), Account.class, id.toString());

            var result = fluxzero.modelRepository().load(id);

            assertEquals(new Account(id, 11), result.get());
            assertEquals(2L, result.sequenceNumber());
        }
    }

    @Test
    void reconstructsHistoricalCrossModelDependencyAtStoredReadBoundary() {
        InventoryId inventoryId = new InventoryId("one");
        OrderId orderId = new OrderId("one");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            long stock = commit(
                    fluxzero, "stock-5", -1L,
                    new CreateInventory(inventoryId, 5),
                    Inventory.class, inventoryId.toString());
            long order = commit(
                    fluxzero, "create-order", stock,
                    new CreateOrder(orderId, inventoryId),
                    Order.class, orderId.toString());
            commit(fluxzero, "stock-100", order,
                   new ChangeInventory(inventoryId, 95), Inventory.class, inventoryId.toString());

            var result = fluxzero.modelRepository().load(orderId);

            assertEquals(new Order(orderId, 5), result.get());
        }
    }

    @Test
    void reconstructsNormallyDocumentLoadedDependencyFromStoredHistory() {
        DocumentInventoryId inventoryId =
                new DocumentInventoryId("history");
        DocumentOrderId orderId =
                new DocumentOrderId("history");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(
                    new CreateDocumentInventory(
                            inventoryId, 5)).join();
            fluxzero.commandGateway().send(
                    new CreateDocumentOrder(
                            orderId, inventoryId)).join();
            fluxzero.commandGateway().send(
                    new ChangeDocumentInventory(
                            inventoryId, 95)).join();

            assertEquals(
                    new DocumentInventory(
                            inventoryId, 100),
                    fluxzero.modelRepository()
                            .load(inventoryId).get());
            ((DefaultModelRepository)
                    fluxzero.modelRepository())
                    .invalidateModels(
                            List.of(orderId.toString()));

            assertEquals(
                    new DocumentOrder(orderId, 5),
                    fluxzero.modelRepository()
                            .load(orderId).get());
        }
    }

    @Test
    void rejectsEventSourcedDependencyOnIncompleteDocumentModelHistory() {
        DocumentInventoryId inventoryId =
                new DocumentInventoryId("gap");
        DocumentOrderId orderId =
                new DocumentOrderId("gap");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(
                    new CreateDocumentInventory(
                            inventoryId, 5)).join();
            fluxzero.commandGateway().send(
                    new ChangeDocumentInventoryWithoutHistory(
                            inventoryId, 95)).join();

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> fluxzero.executeModelCommit(
                            new Message(new CreateDocumentOrder(
                                    orderId,
                                    inventoryId))).join());

            assertInstanceOf(
                    EventSourcingException.class,
                    failure.getCause());
            assertTrue(
                    failure.getCause().getMessage()
                            .matches(
                                    "Model 'document-inventory-gap' cannot be reconstructed at state index \\d+ "
                                    + "because its stored history is incomplete"));
            assertFalse(
                    fluxzero.modelRepository()
                            .load(orderId).isPresent());
        }
    }

    @Test
    void logicalDeleteAndRecreateReplayAsDistinctStoredRevisions() {
        AccountId id =
                new AccountId("delete-recreate");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(
                    new CreateAccount(id, 1)).join();
            fluxzero.commandGateway().send(
                    new DeleteAccount(id)).join();
            fluxzero.commandGateway().send(
                    new CreateAccount(id, 7)).join();
            ((DefaultModelRepository)
                    fluxzero.modelRepository())
                    .invalidateModels(
                            List.of(id.toString()));

            var result =
                    fluxzero.modelRepository().load(id);

            assertEquals(
                    new Account(id, 7), result.get());
            assertEquals(2L, result.sequenceNumber());
        }
    }

    @Test
    void reconstructionUsesTheConfiguredEventUpcasters() {
        UpcastAccountId id =
                new UpcastAccountId("one");
        JacksonSerializer serializer =
                new JacksonSerializer();
        try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                .replaceSerializer(serializer)
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null)))) {
            fluxzero.commandGateway().send(
                    new CreateUpcastAccount(id, 1)).join();
            fluxzero.commandGateway().send(
                    new UpcastAccountChange(id, 2)).join();
            serializer.registerUpcasters(
                    new AccountChangeUpcaster());
            ((DefaultModelRepository)
                    fluxzero.modelRepository())
                    .invalidateModels(
                            List.of(id.toString()));

            assertEquals(
                    new UpcastAccount(id, 21),
                    fluxzero.modelRepository()
                            .load(id).get());
        }
    }

    @Test
    void reconstructionIncludesEarlierSubstepsFromTheSameCommit() {
        InventoryId inventoryId = new InventoryId("prefix");
        OrderId orderId = new OrderId("prefix");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            long stock = commit(
                    fluxzero, "prefix-stock", -1L,
                    new CreateInventory(inventoryId, 5),
                    Inventory.class, inventoryId.toString());
            commitModels(
                    fluxzero, "prefix-commit", stock,
                    List.of(inventoryId.toString(), orderId.toString()),
                    new CommitEvent(
                            new ChangeInventory(inventoryId, 1),
                            inventoryId.toString(), Inventory.class),
                    new CommitEvent(
                            new CreateOrder(orderId, inventoryId),
                            orderId.toString(), Order.class));

            var result = fluxzero.modelRepository().load(orderId);

            assertEquals(new Order(orderId, 6), result.get());
        }
    }

    @Test
    void standardFluxzeroConfigurationLoadsFromItsDirectDocumentStore() {
        ProductId id = new ProductId("configured");
        Product product = new Product(id, "configured");
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null))) {
            fluxzero.documentStore().index(product, id, "products").join();

            var result = fluxzero.modelRepository().load(id);

            assertEquals(product, result.get());
            assertEquals(id.toString(), result.id());
        }
    }

    @Nested
    class CacheFastPathTests {
        @Test
        void acceptedLocalCommitSeedsCacheWithoutPerLoadHeadChecks() {
            AccountId id = new AccountId("cached");
            LocalClient localClient = LocalClient.newInstance(null);
            EventStoreClient eventStoreClient = spy(localClient.getEventStoreClient());
            LocalClient client = spy(localClient);
            doReturn(eventStoreClient).when(client).getEventStoreClient();
            try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                    .disableKeepalive()
                    .disableShutdownHook()
                    .replaceCache(testCache())
                    .build(client))) {
                assertTrue(((DefaultModelRepository) fluxzero.modelRepository())
                                   .cacheTrackingReadiness().join());
                fluxzero.executeModelCommit(
                        new Message(new CreateAccount(id, 5))).join();
                clearInvocations(eventStoreClient);

                assertEquals(new Account(id, 5), fluxzero.modelRepository().load(id).get());
                assertEquals(new Account(id, 5), fluxzero.modelRepository().load(id).get());

                verify(eventStoreClient, times(0))
                        .getModelEvents(any());
            }
        }

        @Test
        void acceptedLocalCommitSeedsTheNextAutomaticApplyWithoutAStoreReload() {
            AccountId id = new AccountId("cached-apply");
            LocalClient localClient = LocalClient.newInstance(null);
            EventStoreClient eventStoreClient = spy(localClient.getEventStoreClient());
            LocalClient client = spy(localClient);
            doReturn(eventStoreClient).when(client).getEventStoreClient();
            try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                    .disableKeepalive()
                    .disableShutdownHook()
                    .replaceCache(testCache())
                    .build(client))) {
                assertTrue(((DefaultModelRepository) fluxzero.modelRepository())
                                   .cacheTrackingReadiness().join());
                fluxzero.executeModelCommit(
                        new Message(new CreateAccount(id, 5))).join();
                clearInvocations(eventStoreClient);

                fluxzero.executeModelCommit(
                        new Message(new ChangeAccount(id, 2))).join();

                assertEquals(
                        new Account(id, 7),
                        fluxzero.modelRepository().load(id).get());
                verify(eventStoreClient, times(0))
                        .getModelEvents(any());
                verify(eventStoreClient, times(1))
                        .commitModels(any());
            }
        }
    }

    @Test
    void loadsIndependentModelByCurrentAliasWithoutSearch() {
        AliasAccountId id = new AliasAccountId("1");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.executeModelCommit(
                    new Message(new CreateAliasAccount(
                            id, "first-code", 5))).join();

            Entity<AliasAccount> typed = fluxzero.apply(ignored ->
                    Fluxzero.loadModel(
                            "first-code", AliasAccount.class));
            Entity<Object> untyped = fluxzero.apply(ignored ->
                    Fluxzero.loadModel("first-code"));

            assertEquals(id.toString(), typed.id());
            assertEquals(new AliasAccount(
                    id, "first-code", 5), typed.get());
            assertEquals(AliasAccount.class, untyped.type());
            assertEquals(typed.get(), untyped.get());

            fluxzero.executeModelCommit(
                    new Message(new RenameAliasAccount(
                            id, "second-code"))).join();

            assertTrue(fluxzero.apply(ignored ->
                    Fluxzero.loadModel(
                            "first-code", AliasAccount.class))
                    .isEmpty());
            Entity<AliasAccount> renamed = fluxzero.apply(ignored ->
                    Fluxzero.loadModel(
                            "second-code", AliasAccount.class));
            assertEquals(id.toString(), renamed.id());
            assertEquals("second-code", renamed.get().code());
        }
    }

    @Test
    void explicitModelCommitsReadEarlierPendingChangesFromTheCurrentMessageBatch()
            throws Exception {
        AccountId id = new AccountId("explicit-batch");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient delegate = localClient.getEventStoreClient();
        EventStoreClient eventStoreClient = spy(delegate);
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        AtomicBoolean delayNextCommit = new AtomicBoolean();
        CompletableFuture<Void> releaseFirstCommit =
                new CompletableFuture<>();
        doAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            if (delayNextCommit.compareAndSet(true, false)) {
                return releaseFirstCommit.thenCompose(
                        ignored -> delegate.commitModels(commit));
            }
            return delegate.commitModels(commit);
        }).when(eventStoreClient).commitModels(any());

        try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client))) {
            fluxzero.executeModelCommit(
                    new Message(new CreateAccount(id, 5))).join();
            clearInvocations(eventStoreClient);
            delayNextCommit.set(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] updates =
                    new CompletableFuture[2];

            fluxzero.apply(ignored -> {
                DeserializingMessage first =
                        new DeserializingMessage(
                                new Message("first"), COMMAND,
                                fluxzero.serializer());
                DeserializingMessage second =
                        new DeserializingMessage(
                                new Message("second"), COMMAND,
                                fluxzero.serializer());
                first.getSerializedObject().setSegment(17);
                second.getSerializedObject().setSegment(17);
                DeserializingMessage.forEachInBatch(
                        List.of(first, second),
                        current -> {
                            int index =
                                    DeserializingMessage
                                            .getMessageBatchIndex();
                            updates[index] =
                                    fluxzero.executeModelCommit(
                                            new Message(
                                                    new ChangeAccount(
                                                            id,
                                                            index == 0
                                                                    ? 2 : 3)));
                        });
                return null;
            });

            verify(eventStoreClient, times(1))
                    .commitModels(any());
            assertFalse(updates[0].isDone());
            assertFalse(updates[1].isDone());

            releaseFirstCommit.complete(null);
            CompletableFuture.allOf(updates)
                    .get(5, TimeUnit.SECONDS);

            assertEquals(
                    new Account(id, 10),
                    fluxzero.modelRepository().load(id).get());
            verify(eventStoreClient, times(2))
                    .commitModels(any());
        }
    }

    @Test
    void explicitBatchDependentCommitFailsWhenItsPendingPredecessorFails()
            throws Exception {
        AccountId id = new AccountId("explicit-batch-failure");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient delegate = localClient.getEventStoreClient();
        EventStoreClient eventStoreClient = spy(delegate);
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        AtomicReference<CompletableFuture<CommitModelsResult>>
                pendingResponse = new AtomicReference<>();
        doAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            CompletableFuture<CommitModelsResult> pending =
                    pendingResponse.get();
            return pending == null
                    ? delegate.commitModels(commit)
                    : pending;
        }).when(eventStoreClient).commitModels(any());

        try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client))) {
            fluxzero.executeModelCommit(
                    new Message(new CreateAccount(id, 5))).join();
            clearInvocations(eventStoreClient);
            CompletableFuture<CommitModelsResult> rejected =
                    new CompletableFuture<>();
            pendingResponse.set(rejected);
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] updates =
                    new CompletableFuture[2];

            fluxzero.apply(ignored -> {
                DeserializingMessage first =
                        new DeserializingMessage(
                                new Message("first"), COMMAND,
                                fluxzero.serializer());
                DeserializingMessage second =
                        new DeserializingMessage(
                                new Message("second"), COMMAND,
                                fluxzero.serializer());
                first.getSerializedObject().setSegment(23);
                second.getSerializedObject().setSegment(23);
                DeserializingMessage.forEachInBatch(
                        List.of(first, second),
                        current -> {
                            int index = DeserializingMessage
                                    .getMessageBatchIndex();
                            if (index == 1) {
                                assertEquals(
                                        new Account(id, 6),
                                        fluxzero.modelRepository()
                                                .load(id).get());
                            }
                            updates[index] =
                                    fluxzero.executeModelCommit(
                                            new Message(
                                                    new ChangeAccount(
                                                            id, index + 1)));
                        });
                return null;
            });

            verify(eventStoreClient, times(1))
                    .commitModels(any());
            rejected.completeExceptionally(
                    new IllegalStateException("predecessor failed"));

            assertThrows(
                    CompletionException.class,
                    () -> updates[0].join());
            assertThrows(
                    CompletionException.class,
                    () -> updates[1].join());
            assertEquals(
                    new Account(id, 5),
                    fluxzero.modelRepository().load(id).get());
            verify(eventStoreClient, times(1))
                    .commitModels(any());
        }
    }

    @Test
    void periodicSnapshotBecomesTheLongStreamLoadCursor() {
        SnapshotAccountId id = new SnapshotAccountId("snapshotted");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient eventStoreClient = spy(localClient.getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client))) {
            fluxzero.commandGateway().send(
                    new CreateSnapshotAccount(id, 1)).join();
            fluxzero.commandGateway().send(
                    new ChangeSnapshotAccount(id, 1)).join();
            assertEquals(
                    1L, fluxzero.documentStore()
                            .search("$modelSnapshots").count());
            clearInvocations(eventStoreClient);

            assertEquals(
                    new SnapshotAccount(id, 2),
                    fluxzero.modelRepository().load(id).get());

            var captor = org.mockito.ArgumentCaptor.forClass(GetModelEvents.class);
            verify(eventStoreClient).getModelEvents(captor.capture());
            assertEquals(
                    1L, captor.getValue().getRequests().getFirst()
                            .getLastSequenceNumber());
        }
    }

    @Test
    void cachedModelCatchesUpWithAStoredExternalSuffix() {
        AccountId id = new AccountId("external");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(new CreateAccount(id, 5)).join();
            commit(fluxzero, "external-change",
                   currentStateIndex(fluxzero),
                   new ChangeAccount(id, 2), Account.class, id.toString());

            awaitModelValue(
                    fluxzero, id,
                    new Account(id, 7));
        }
    }

    @Test
    void cachedModelDisappearsAfterExternallyCompletedHardDelete() {
        AccountId id =
                new AccountId(
                        "externally-erased");
        LocalClient localClient =
                LocalClient.newInstance(null);
        try (Fluxzero fluxzero = withModelHandlers(
                     DefaultFluxzero.builder()
                             .disableKeepalive()
                             .disableShutdownHook()
                             .build(localClient))) {
            fluxzero.commandGateway().send(
                    new CreateAccount(id, 5))
                    .join();
            assertEquals(
                    new Account(id, 5),
                    fluxzero.modelRepository()
                            .load(id).get());

            localClient.getEventStoreClient()
                    .deleteModel(
                            DeleteModel.builder()
                                    .deletionId(
                                            "external-erasure")
                                    .modelId(
                                            id.toString())
                                    .cascade(
                                            ModelDeletionCascade
                                                    .NONE)
                                    .maxDepth(0)
                                    .maxModels(1)
                                    .build())
                    .join();

            long deadline =
                    System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS
                            .toNanos(5L);
            while (fluxzero.modelRepository()
                           .load(id)
                           .isPresent()
                   && System.nanoTime()
                      < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(
                    fluxzero.modelRepository()
                            .load(id)
                            .isPresent());
        }
    }

    @Test
    void modelCacheRetainsOnePreviousRevisionByDefault() {
        AccountId id = new AccountId("latest-only");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(
                    new CreateAccount(id, 1)).join();
            fluxzero.commandGateway().send(
                    new ChangeAccount(id, 1)).join();

            var result = fluxzero.modelRepository().load(id);

            assertEquals(new Account(id, 2), result.get());
            assertNotNull(result.previous());
            assertEquals(
                    new Account(id, 1),
                    result.previous().get());
            assertNull(result.previous().previous());
        }
    }

    @Test
    void olderCommitCompletionCannotOverwriteANewerCachedModel() {
        JacksonSerializer serializer =
                new JacksonSerializer();
        Cache cache = new DefaultCache();
        DefaultModelRepository repository =
                new DefaultModelRepository(
                        client, documentStore, serializer,
                        mock(EntityHelper.class), null,
                        cache, List.of());
        AccountId id = new AccountId("fenced");
        repository.updateAfterCommit(List.of(
                committed(repository, id, 20, 1L, 20L)));
        repository.updateAfterCommit(List.of(
                committed(repository, id, 10, 0L, 10L)));
        AtomicReference<Entity<?>> cached =
                new AtomicReference<>();
        cache.<Object>modifyEach((ignored, value) -> {
            if (value instanceof Entity<?> entity) {
                cached.set(entity);
            }
            return value;
        });

        assertNotNull(cached.get());
        assertEquals(
                new Account(id, 20),
                cached.get().get());
        assertEquals(20L,
                     ((ModelRoot<?>) cached.get())
                             .stateIndex());
        cache.close();
    }

    @Test
    void olderReconstructionCompletionCannotOverwriteANewerCachedModel()
            throws InterruptedException {
        AccountId id = new AccountId("reconstruction-fence");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient eventStoreClient =
                spy(localClient.getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient)
                .when(client).getEventStoreClient();
        try (Fluxzero fluxzero = withModelHandlers(
                     DefaultFluxzero.builder()
                             .disableKeepalive()
                             .disableShutdownHook()
                             .build(client))) {
            fluxzero.commandGateway().send(
                    new CreateAccount(id, 5)).join();
            long oldStateIndex =
                    currentStateIndex(fluxzero);
            DefaultModelRepository repository =
                    (DefaultModelRepository)
                            fluxzero.modelRepository();
            repository.invalidateModels(
                    List.of(id.toString()));

            CountDownLatch reconstructionLoaded =
                    new CountDownLatch(1);
            CountDownLatch allowReconstructionCompletion =
                    new CountDownLatch(1);
            AtomicBoolean intercept =
                    new AtomicBoolean(true);
            doAnswer(invocation -> {
                GetModelEventsResult result =
                        (GetModelEventsResult)
                                invocation.callRealMethod();
                if (intercept.compareAndSet(
                        true, false)) {
                    reconstructionLoaded
                            .countDown();
                    assertTrue(
                            allowReconstructionCompletion
                                    .await(
                                            5,
                                            TimeUnit.SECONDS));
                }
                return result;
            }).when(eventStoreClient)
                    .getModelEvents(any());

            CompletableFuture<Entity<Account>>
                    olderReconstruction =
                    CompletableFuture.supplyAsync(
                            () -> repository.load(id));
            assertTrue(
                    reconstructionLoaded.await(
                            5, TimeUnit.SECONDS));

            repository.updateAfterCommit(
                    List.of(committed(
                            repository, id, 20, 1L,
                            oldStateIndex + 1L)));
            allowReconstructionCompletion
                    .countDown();

            assertEquals(
                    new Account(id, 5),
                    olderReconstruction.join().get());
            assertEquals(
                    new Account(id, 20),
                    repository.load(id).get());
        }
    }

    private static DefaultModelRepository.Commit.Outcome
            committed(
                    DefaultModelRepository repository,
                    AccountId id,
                    int balance,
                    long sequenceNumber,
                    long stateIndex) {
        try {
            JacksonSerializer serializer = new JacksonSerializer();
            Account value = new Account(id, balance);
            DeserializingMessage message = new DeserializingMessage(
                    new Message(new ChangeAccount(id, 0)), EVENT, serializer);
            CommitAttempt attempt = ModelCommitTestBuilder.attempt(
                    Math.max(0L, stateIndex - 1L), id.toString(), Account.class,
                    sequenceNumber - 1L, null, value,
                    ChangeAccount.class.getDeclaredMethod("apply", Account.class), message);
            DefaultModelRepository.Commit committer = repository.new Commit(
                    mock(EventStoreClient.class), serializer, serializer,
                    DispatchInterceptor.noOp, "test");
            DefaultModelRepository.Commit.Outcome prepared =
                    committer.prepare("commit-" + stateIndex, attempt);
            return prepared.accepted(
                    CommitModelsResult.acceptedSingleTarget(
                            prepared.commit().getRequestId(),
                            prepared.commit().getCommitId(),
                            stateIndex, sequenceNumber, id.toString(),
                            stateIndex, true));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void boundedCacheRetainsEveryLocalSubstepUpToConfiguredDepth() {
        HistoryAccountId id =
                new HistoryAccountId("bounded");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(
                    new CreateHistoryAccount(id, 1)).join();
            fluxzero.commandGateway().send(
                    new ChangeHistoryAccountTwice(id)).join();

            var result = fluxzero.modelRepository().load(id);

            assertEquals(
                    new HistoryAccount(id, 3), result.get());
            assertNotNull(result.previous());
            assertEquals(
                    new HistoryAccount(id, 2),
                    result.previous().get());
            assertNotNull(result.previous().previous());
            assertEquals(
                    new HistoryAccount(id, 1),
                    result.previous().previous().get());
            assertNull(result.previous().previous().previous());
        }
    }

    @Test
    void unknownStoredMembershipFailsUnlessModelExplicitlyIgnoresIt() {
        AccountId accountId = new AccountId("unknown");
        LenientAccountId lenientId = new LenientAccountId("unknown");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            long accountCreated = commit(
                    fluxzero, "unknown-account-create", -1L,
                    new CreateAccount(accountId, 1),
                    Account.class, accountId.toString());
            long accountUnknown = commit(
                    fluxzero, "unknown-account-event",
                    accountCreated,
                    new UnknownAccountEvent(accountId),
                    Account.class, accountId.toString());
            long lenientCreated = commit(
                    fluxzero, "unknown-lenient-create",
                    accountUnknown,
                    new CreateLenientAccount(lenientId, 2),
                    LenientAccount.class, lenientId.toString());
            commit(fluxzero, "unknown-lenient-event",
                   lenientCreated,
                   new UnknownLenientEvent(lenientId), LenientAccount.class, lenientId.toString());

            assertThrows(
                    EventSourcingException.class,
                    () -> fluxzero.modelRepository().load(accountId));
            assertEquals(
                    new LenientAccount(lenientId, 2),
                    fluxzero.modelRepository().load(lenientId).get());
        }
    }

    @Test
    void historicalDependenciesForOneApplyAreBatchLoaded() {
        AccountId accountId = new AccountId("batched");
        InventoryId inventoryId = new InventoryId("batched");
        ShipmentId shipmentId = new ShipmentId("batched");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient eventStoreClient = spy(localClient.getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(client)) {
            long account = commit(
                    fluxzero, "batch-account", -1L,
                    new CreateAccount(accountId, 3),
                    Account.class, accountId.toString());
            long inventory = commit(
                    fluxzero, "batch-inventory", account,
                    new CreateInventory(inventoryId, 5),
                    Inventory.class, inventoryId.toString());
            commit(fluxzero, "batch-shipment", inventory,
                   new CreateShipment(shipmentId, inventoryId, accountId),
                   Shipment.class, shipmentId.toString());
            clearInvocations(eventStoreClient);

            assertEquals(
                    new Shipment(shipmentId, 8),
                    fluxzero.modelRepository().load(shipmentId).get());

            var captor = org.mockito.ArgumentCaptor.forClass(GetModelEvents.class);
            verify(eventStoreClient, times(2))
                    .getModelEvents(captor.capture());
            assertEquals(
                    2, captor.getAllValues().getLast()
                            .getRequests().size());
        }
    }

    @Test
    void interceptorOutputsPrefetchDistinctModelsInOnePinnedBatch() {
        AccountId first = new AccountId("bulk-1");
        AccountId second = new AccountId("bulk-2");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient eventStoreClient = spy(localClient.getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                .disableKeepalive()
                .disableAutomaticModelCaching()
                .disableShutdownHook()
                .build(client))) {
            fluxzero.commandGateway().send(new CreateAccounts(List.of(
                    new CreateAccount(first, 1),
                    new CreateAccount(second, 2)))).join();

            var captor = org.mockito.ArgumentCaptor.forClass(GetModelEvents.class);
            verify(eventStoreClient, atLeastOnce())
                    .getModelEvents(captor.capture());
            assertEquals(
                    List.of(2),
                    captor.getAllValues().stream()
                            .filter(request -> !request.getRequests().isEmpty())
                            .map(request -> request.getRequests().size())
                            .toList());
            clearInvocations(eventStoreClient);
            assertEquals(
                    new Account(first, 1),
                    fluxzero.modelRepository().load(first).get());
            assertEquals(
                    new Account(second, 2),
                    fluxzero.modelRepository().load(second).get());
        }
    }

    @Test
    void reconstructsAndMovesAnExplicitlyPlacedModelGraph() {
        GraphRootId firstRootId = new GraphRootId("first");
        GraphRootId secondRootId = new GraphRootId("second");
        GraphChildId childId = new GraphChildId("child");
        GraphGrandchildId grandchildId = new GraphGrandchildId("grandchild");
        UnplacedChildId unplacedId = new UnplacedChildId("unplaced");
        try (Fluxzero fluxzero = configuredFluxzero()) {
            fluxzero.commandGateway().send(
                    new CreateGraphRoot(firstRootId, "first")).join();
            fluxzero.commandGateway().send(
                    new CreateGraphRoot(secondRootId, "second")).join();
            fluxzero.commandGateway().send(
                    new CreateGraphChild(childId, firstRootId, "child")).join();
            fluxzero.commandGateway().send(
                    new CreateGraphGrandchild(
                            grandchildId, childId, "grandchild")).join();
            fluxzero.commandGateway().send(
                    new CreateUnplacedChild(unplacedId, firstRootId)).join();

            Graph<GraphRoot> first =
                    fluxzero.modelRepository().loadGraph(firstRootId);

            assertEquals(
                    new GraphRoot(firstRootId, "first"),
                    first.get());
            assertEquals(
                    List.of(new GraphChild(childId, firstRootId, "child")),
                    first.childModels("children", GraphChild.class));
            assertEquals(
                    List.of(new GraphGrandchild(
                            grandchildId, childId, "grandchild")),
                    first.children("children", GraphChild.class).getFirst()
                            .childModels("grandchildren", GraphGrandchild.class));
            assertEquals(3, first.descendants(Object.class).size());
            assertTrue(first.descendants(Object.class).stream()
                               .anyMatch(node -> node.id().equals(unplacedId.toString())));
            assertFalse(first.children("children", Object.class).stream()
                                .anyMatch(node -> node.id().equals(unplacedId.toString())));

            fluxzero.commandGateway().send(
                    new MoveGraphChild(childId, secondRootId)).join();

            Graph<GraphRoot> historical =
                    fluxzero.modelRepository().loadGraphAt(
                            firstRootId,
                            first.stateIndex());
            Graph<GraphRoot> oldRoot =
                    fluxzero.modelRepository().loadGraph(firstRootId);
            Graph<GraphRoot> newRoot =
                    fluxzero.modelRepository().loadGraph(secondRootId);
            assertEquals(
                    new GraphChild(
                            childId, firstRootId, "child"),
                    historical.children("children", GraphChild.class)
                            .getFirst().get());
            assertEquals(
                    new GraphGrandchild(
                            grandchildId, childId,
                            "grandchild"),
                    historical.children("children", GraphChild.class)
                            .getFirst()
                            .children("grandchildren", GraphGrandchild.class)
                            .getFirst().get());
            assertEquals(List.of(), oldRoot.children("children", GraphChild.class));
            assertEquals(
                    new GraphChild(childId, secondRootId, "child"),
                    newRoot.children("children", GraphChild.class).getFirst().get());
            assertEquals(
                    new GraphGrandchild(grandchildId, childId, "grandchild"),
                    newRoot.children("children", GraphChild.class).getFirst()
                            .children("grandchildren", GraphGrandchild.class).getFirst().get());
        }
    }

    @Test
    void lazyAncestorNavigationLoadsOnlyTheSelectedAncestorValue() {
        GraphRootId rootId = new GraphRootId("lazy-ancestor");
        GraphChildId childId = new GraphChildId("lazy-ancestor");
        GraphGrandchildId grandchildId =
                new GraphGrandchildId("lazy-ancestor");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient eventStoreClient =
                spy(localClient.getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        try (Fluxzero fluxzero = withModelHandlers(
                     DefaultFluxzero.builder()
                             .disableKeepalive()
                             .disableShutdownHook()
                             .disableAutomaticModelCaching()
                             .build(client))) {
            fluxzero.commandGateway().send(
                    new CreateGraphRoot(rootId, "root")).join();
            fluxzero.commandGateway().send(
                    new CreateGraphChild(childId, rootId, "child")).join();
            fluxzero.commandGateway().send(
                    new CreateGraphGrandchild(
                            grandchildId, childId,
                            "grandchild")).join();
            fluxzero.commandGateway().send(
                    new RenameGraphRoot(rootId, "updated root")).join();

            Graph<GraphGrandchild> graph = fluxzero.apply(
                    ignored -> Fluxzero.loadGraph(grandchildId));
            clearInvocations(eventStoreClient);

            Graph<GraphRoot> root =
                    graph.ancestor(GraphRoot.class).orElseThrow();

            assertEquals(
                    new GraphRoot(rootId, "updated root"), root.get());
            var ancestors = org.mockito.ArgumentCaptor.forClass(
                    GetModelAncestors.class);
            verify(eventStoreClient).getModelAncestors(
                    ancestors.capture());
            assertEquals(
                    List.of(grandchildId.toString()),
                    ancestors.getValue().getModelIds());
            assertEquals(0,
                         ancestors.getValue().getMaxEventsPerModel());
            assertEquals(0L,
                         ancestors.getValue().getMaxBytes());
            assertEquals(-1,
                         ancestors.getValue().getMaxDepth());
            assertEquals(-1,
                         ancestors.getValue().getMaxModels());

            var loaded = org.mockito.ArgumentCaptor.forClass(
                    GetModelGraph.class);
            verify(eventStoreClient).getModelGraph(
                    loaded.capture());
            assertEquals(rootId.toString(),
                         loaded.getValue().getRootId());
            assertEquals(0, loaded.getValue().getMaxDepth());
            assertEquals(1, loaded.getValue().getMaxModels());
        }
    }

    @Test
    void currentGraphReusesCachedModelsAtItsPinnedBoundary() {
        GraphRootId rootId =
                new GraphRootId("cached");
        GraphChildId childId =
                new GraphChildId("cached");
        LocalClient localClient =
                LocalClient.newInstance(null);
        EventStoreClient eventStoreClient =
                spy(localClient
                            .getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient)
                .when(client)
                .getEventStoreClient();
        try (Fluxzero fluxzero = withModelHandlers(
                     DefaultFluxzero.builder()
                             .disableKeepalive()
                             .disableShutdownHook()
                             .build(client))) {
            fluxzero.commandGateway().send(
                    new CreateGraphRoot(
                            rootId, "root"))
                    .join();
            fluxzero.commandGateway().send(
                    new CreateGraphChild(
                            childId, rootId,
                            "child"))
                    .join();
            ((DefaultModelRepository)
                    fluxzero.modelRepository())
                    .invalidateModels(
                            List.of(
                                    rootId.toString(),
                                    childId.toString()));

            fluxzero.modelRepository()
                    .loadGraph(rootId);
            clearInvocations(
                    eventStoreClient);
            fluxzero.modelRepository()
                    .loadGraph(rootId);

            var captor =
                    org.mockito.ArgumentCaptor
                            .forClass(
                                    GetModelEvents.class);
            verify(eventStoreClient,
                   atLeastOnce())
                    .getModelEvents(
                            captor.capture());
            List<ModelEventStreamRequest> requests =
                    captor.getAllValues()
                            .stream()
                            .flatMap(request ->
                                             request.getRequests()
                                                     .stream())
                            .toList();
            assertFalse(requests.isEmpty());
            assertTrue(
                    requests.stream()
                            .allMatch(request ->
                                              request.getLastSequenceNumber()
                                              >= 0L));
        }
    }

    @Test
    void modelEventHandlerLoadsEveryModelAtItsMappedStateBoundary() {
        AccountId accountId = new AccountId("event-boundary");
        InventoryId inventoryId =
                new InventoryId("event-boundary");
        LocalClient localClient = LocalClient.newInstance(null);
        EventStoreClient eventStoreClient =
                spy(localClient.getEventStoreClient());
        LocalClient client = spy(localClient);
        doReturn(eventStoreClient).when(client).getEventStoreClient();
        try (Fluxzero fluxzero = withModelHandlers(DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .disableAutomaticModelCaching()
                .build(client))) {
            fluxzero.commandGateway().send(
                    new CreateInventory(inventoryId, 5)).join();
            fluxzero.commandGateway().send(
                    new CreateAccount(accountId, 10)).join();
            var handledEvent = eventStoreClient
                    .getEvents(accountId.toString())
                    .findFirst().orElseThrow();
            assertNotNull(handledEvent.getIndex());
            String handledCommitId = (String) handledEvent.getMetadata().get(
                    io.fluxzero.common.api.modeling.ModelEventMetadata.COMMIT_ID);
            int handledSubstep = Integer.parseInt(handledEvent.getMetadata().get(
                    io.fluxzero.common.api.modeling.ModelEventMetadata.SUBSTEP));

            fluxzero.commandGateway().send(
                    new ChangeInventory(inventoryId, 95)).join();
            fluxzero.commandGateway().send(
                    new ChangeAccount(accountId, 90)).join();
            long handledStateIndex =
                    eventStoreClient.getModelEvents(
                                    new GetModelEvents(
                                            List.of(),
                                            ModelReadBoundary.event(
                                                    handledEvent.getIndex()),
                                            0L))
                            .getStateIndex();
            clearInvocations(eventStoreClient);

            List<Object> handledView = fluxzero.serializer()
                    .deserializeMessage(handledEvent, EVENT)
                    .apply(message -> List.of(
                            fluxzero.modelRepository()
                                    .load(accountId).get(),
                            fluxzero.modelRepository()
                                    .load(inventoryId).get()));

            assertEquals(
                    List.of(
                            new Account(accountId, 10),
                            new Inventory(inventoryId, 5)),
                    handledView);
            var requests = org.mockito.ArgumentCaptor.forClass(
                    GetModelEvents.class);
            verify(eventStoreClient, times(2))
                    .getModelEvents(
                            requests.capture());
            assertEquals(
                    handledCommitId,
                    requests.getAllValues().getFirst()
                            .getBoundary().commitId());
            assertEquals(
                    handledSubstep,
                    requests.getAllValues().getFirst()
                            .getBoundary().substep());
            assertEquals(
                    handledStateIndex,
                    requests.getAllValues().getLast()
                            .getBoundary().stateIndex());
        }
    }

    private static Fluxzero configuredFluxzero() {
        return withModelHandlers(DefaultFluxzero.builder()
                .disableKeepalive()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null)));
    }

    private static Cache testCache() {
        return new AdaptiveObjectCache(
                100, MemoryPressureController.none());
    }

    private static Fluxzero withModelHandlers(Fluxzero fluxzero) {
        Object[] handlerTypes = Arrays.stream(
                        DefaultModelRepositoryTest.class.getDeclaredClasses())
                .filter(type -> {
                    EntityMetadata metadata = EntityMetadata.of(type);
                    return metadata.isModel() || metadata.handlerMethods().stream()
                            .anyMatch(handler -> handler.kind() == EntityMetadata.HandlerKind.INTERCEPT_APPLY
                                                 || handler.kind() == EntityMetadata.HandlerKind.APPLY
                                                    && !handler.targetModelTypes().isEmpty());
                })
                .toArray();
        fluxzero.registerHandlers(handlerTypes);
        return fluxzero;
    }

    private static <T> void awaitModelValue(
            Fluxzero fluxzero,
            Id<T> id,
            T expected) {
        long deadline =
                System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(5L);
        T actual;
        do {
            actual =
                    fluxzero.modelRepository()
                            .load(id).get();
            if (Objects.equals(
                    expected, actual)) {
                return;
            }
            Thread.onSpinWait();
        } while (System.nanoTime()
                 < deadline);
        assertEquals(expected, actual);
    }

    private static long commit(
            Fluxzero fluxzero,
            String commitId,
            long readStateIndex,
            Object event,
            Class<?> modelType,
            String... targetIds) {
        ModelCommitStep substep = ModelCommitStep.builder()
                .event(new Message(event).serialize(fluxzero.serializer()))
                .publishEvent(false)
                .targets(java.util.Arrays.stream(targetIds)
                                 .map(modelId -> ModelCommitTarget.builder()
                                         .modelId(modelId)
                                         .modelType(modelType.getName())
                                         .storeEvent(true)
                                         .updateState(true)
                                         .relationships(List.of())
                                         .build())
                                 .toList())
                .build();
        CommitModels commit = new CommitModels(
                commitId, readStateIndex, List.of(targetIds),
                List.of(substep), ModelConflictPolicy.ACCEPT, Guarantee.STORED, true);
        CommitModelsResult result = fluxzero.client().getEventStoreClient()
                .commitModels(commit).join();
        if (result.isRebaseRequired()) {
            commit = new CommitModels(
                    commitId, result.getRebaseStateIndex(), List.of(targetIds),
                    List.of(substep), ModelConflictPolicy.ACCEPT, Guarantee.STORED, true);
            result = fluxzero.client().getEventStoreClient()
                    .commitModels(commit).join();
        }
        assertTrue(result.isAccepted());
        return result.getUpdates().getLast()
                .getStateIndex();
    }

    private static long commitModels(
            Fluxzero fluxzero,
            String commitId,
            long readStateIndex,
            List<String> readModelIds,
            CommitEvent... events) {
        List<ModelCommitStep> substeps = java.util.Arrays.stream(events)
                .map(event -> ModelCommitStep.builder()
                        .event(new Message(event.payload()).serialize(fluxzero.serializer()))
                        .publishEvent(false)
                        .targets(List.of(ModelCommitTarget.builder()
                                                 .modelId(event.targetId())
                                                 .modelType(event.modelType().getName())
                                                 .storeEvent(true)
                                                 .updateState(true)
                                                 .relationships(List.of())
                                                 .build()))
                        .build())
                .toList();
        CommitModels commit = new CommitModels(
                commitId, readStateIndex, readModelIds, substeps,
                ModelConflictPolicy.ACCEPT, Guarantee.STORED, true);
        CommitModelsResult result = fluxzero.client().getEventStoreClient()
                .commitModels(commit).join();
        if (result.isRebaseRequired()) {
            commit = new CommitModels(
                    commitId, result.getRebaseStateIndex(), readModelIds, substeps,
                    ModelConflictPolicy.ACCEPT, Guarantee.STORED, true);
            result = fluxzero.client().getEventStoreClient()
                    .commitModels(commit).join();
        }
        assertTrue(result.isAccepted());
        return result.getUpdates().getLast()
                .getStateIndex();
    }

    private static long currentStateIndex(
            Fluxzero fluxzero) {
        return fluxzero.client()
                .getEventStoreClient()
                .getModelEvents(
                        new GetModelEvents(
                                List.of(), ModelReadBoundary.current(), 0L))
                .getStateIndex();
    }

    private record CommitEvent(Object payload, String targetId, Class<?> modelType) {
    }

    @Model(eventSourced = false, searchable = true,
            searchProjection = @Searchable(collection = "products"))
    private record Product(@EntityId ProductId productId, String name) {
    }

    private static class ProductId extends Id<Product> {
        ProductId(String id) {
            super(id, "product-");
        }
    }

    @Model
    private record AffixedDeletion(
            @EntityId(prefix = "move-", postfix = "-state") AffixedDeletionId id) {
    }

    private static class AffixedDeletionId extends Id<AffixedDeletion> {
        private AffixedDeletionId(String id) {
            super(id, "affixed-");
        }
    }

    @Model
    private record Account(@EntityId AccountId accountId, int balance) {
    }

    private static class AccountId extends Id<Account> {
        private AccountId(String id) {
            super(id, "account-");
        }
    }

    private record ChangeAccount(AccountId accountId, int delta) {
        @Apply
        Account apply(Account account) {
            return new Account(accountId, (account == null ? 0 : account.balance()) + delta);
        }
    }

    private record CreateAccount(AccountId accountId, int balance) {
        @Apply
        Account apply() {
            return new Account(accountId, balance);
        }
    }

    @Model(checkpointPeriod = 1)
    private record CheckpointAccount(
            @EntityId CheckpointAccountId accountId,
            int balance) {
    }

    private static class CheckpointAccountId
            extends Id<CheckpointAccount> {
        private CheckpointAccountId(String id) {
            super(id, "checkpoint-account-");
        }
    }

    private record CreateCheckpointAccount(
            CheckpointAccountId accountId,
            int balance) {
        @Apply
        CheckpointAccount apply() {
            return new CheckpointAccount(accountId, balance);
        }
    }

    private record CreateCheckpointAccounts(
            List<CreateCheckpointAccount> accounts) {
        @InterceptApply
        List<CreateCheckpointAccount> expand() {
            return accounts;
        }
    }

    @Model(
            eventSourced = false, searchable = true,
            searchProjection = @Searchable(collection = "aliasedAccounts"))
    private record AliasedAccount(
            @EntityId AliasedAccountId accountId,
            int balance) {
    }

    private static class AliasedAccountId
            extends Id<AliasedAccount> {
        private AliasedAccountId(String id) {
            super(id, "aliased-account-");
        }
    }

    private record CreateAliasedAccount(
            AliasedAccountId accountId, int balance) {
    }

    @Model
    private record AliasAccount(
            @EntityId AliasAccountId accountId,
            @Alias String code,
            int balance) {
    }

    private static class AliasAccountId
            extends Id<AliasAccount> {
        private AliasAccountId(String id) {
            super(id, "alias-account-");
        }
    }

    private record CreateAliasAccount(
            AliasAccountId accountId,
            String code,
            int balance) {
        @Apply
        AliasAccount apply() {
            return new AliasAccount(
                    accountId, code, balance);
        }
    }

    private record RenameAliasAccount(
            AliasAccountId accountId,
            String code) {
        @Apply
        AliasAccount apply(AliasAccount account) {
            return new AliasAccount(
                    accountId, code, account.balance());
        }
    }

    private record DeleteAccount(AccountId accountId) {
        @Apply
        Account apply(Account account) {
            return null;
        }
    }

    private record CreateAccounts(List<CreateAccount> accounts) {
        @InterceptApply
        List<CreateAccount> expand() {
            return accounts;
        }
    }

    @Model(cachingDepth = 2)
    private record HistoryAccount(
            @EntityId HistoryAccountId accountId,
            int balance) {
    }

    private static class HistoryAccountId
            extends Id<HistoryAccount> {
        private HistoryAccountId(String id) {
            super(id, "history-account-");
        }
    }

    private record CreateHistoryAccount(
            HistoryAccountId accountId, int balance) {
        @Apply
        HistoryAccount apply() {
            return new HistoryAccount(accountId, balance);
        }
    }

    private record ChangeHistoryAccount(
            HistoryAccountId accountId) {
        @Apply
        HistoryAccount apply(HistoryAccount account) {
            return new HistoryAccount(
                    accountId, account.balance() + 1);
        }
    }

    private record ChangeHistoryAccountTwice(
            HistoryAccountId accountId) {
        @InterceptApply
        List<ChangeHistoryAccount> expand() {
            return List.of(
                    new ChangeHistoryAccount(accountId),
                    new ChangeHistoryAccount(accountId));
        }
    }

    @Model
    private record Inventory(@EntityId InventoryId inventoryId, int available) {
    }

    private static class InventoryId extends Id<Inventory> {
        private InventoryId(String id) {
            super(id, "inventory-");
        }
    }

    private record ChangeInventory(InventoryId inventoryId, int delta) {
        @Apply
        Inventory apply(Inventory inventory) {
            return new Inventory(
                    inventoryId, (inventory == null ? 0 : inventory.available()) + delta);
        }
    }

    private record CreateInventory(InventoryId inventoryId, int available) {
        @Apply
        Inventory apply() {
            return new Inventory(inventoryId, available);
        }
    }

    @Model(
            eventSourced = false,
            searchable = true,
            searchProjection = @Searchable(collection = "documentInventory"))
    private record DocumentInventory(
            @EntityId DocumentInventoryId inventoryId,
            int available) {
    }

    private static class DocumentInventoryId
            extends Id<DocumentInventory> {
        private DocumentInventoryId(String id) {
            super(id, "document-inventory-");
        }
    }

    private record CreateDocumentInventory(
            DocumentInventoryId inventoryId,
            int available) {
        @Apply
        DocumentInventory apply() {
            return new DocumentInventory(
                    inventoryId, available);
        }
    }

    private record ChangeDocumentInventory(
            DocumentInventoryId inventoryId,
            int delta) {
        @Apply
        DocumentInventory apply(
                DocumentInventory inventory) {
            return new DocumentInventory(
                    inventoryId,
                    inventory.available() + delta);
        }
    }

    private record ChangeDocumentInventoryWithoutHistory(
            DocumentInventoryId inventoryId,
            int delta) {
        @Apply(
                publicationStrategy =
                        EventPublicationStrategy.PUBLISH_ONLY)
        DocumentInventory apply(
                DocumentInventory inventory) {
            return new DocumentInventory(
                    inventoryId,
                    inventory.available() + delta);
        }
    }

    @Model
    private record Order(@EntityId OrderId orderId, int observedInventory) {
    }

    private static class OrderId extends Id<Order> {
        private OrderId(String id) {
            super(id, "order-");
        }
    }

    @Model
    private record DocumentOrder(
            @EntityId DocumentOrderId orderId,
            int observedInventory) {
    }

    private static class DocumentOrderId
            extends Id<DocumentOrder> {
        private DocumentOrderId(String id) {
            super(id, "document-order-");
        }
    }

    private record CreateDocumentOrder(
            DocumentOrderId orderId,
            DocumentInventoryId inventoryId) {
        @Apply
        DocumentOrder apply(
                DocumentInventory inventory) {
            return new DocumentOrder(
                    orderId,
                    inventory.available());
        }
    }

    @Model
    private record UpcastAccount(
            @EntityId UpcastAccountId accountId,
            int balance) {
    }

    private static class UpcastAccountId
            extends Id<UpcastAccount> {
        private UpcastAccountId(String id) {
            super(id, "upcast-account-");
        }
    }

    private record CreateUpcastAccount(
            UpcastAccountId accountId,
            int balance) {
        @Apply
        UpcastAccount apply() {
            return new UpcastAccount(
                    accountId, balance);
        }
    }

    private record UpcastAccountChange(
            UpcastAccountId accountId,
            int delta) {
        @Apply
        UpcastAccount apply(
                UpcastAccount account) {
            return new UpcastAccount(
                    accountId,
                    account.balance() + delta);
        }
    }

    private static class AccountChangeUpcaster {
        @Upcast(
                type = "io.fluxzero.sdk.persisting.repository."
                       + "DefaultModelRepositoryTest$UpcastAccountChange",
                revision = 0)
        ObjectNode upcast(ObjectNode input) {
            return input.deepCopy().put(
                    "delta",
                    input.get("delta").asInt() * 10);
        }
    }

    private record CreateOrder(OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order apply(Inventory inventory) {
            return new Order(orderId, inventory.available());
        }
    }

    @Model(snapshotPeriod = 2, cached = false)
    private record SnapshotAccount(
            @EntityId SnapshotAccountId accountId, int balance) {
    }

    private static class SnapshotAccountId extends Id<SnapshotAccount> {
        private SnapshotAccountId(String id) {
            super(id, "snapshot-account-");
        }
    }

    private record CreateSnapshotAccount(
            SnapshotAccountId accountId, int balance) {
        @Apply
        SnapshotAccount apply() {
            return new SnapshotAccount(accountId, balance);
        }
    }

    private record ChangeSnapshotAccount(
            SnapshotAccountId accountId, int delta) {
        @Apply
        SnapshotAccount apply(SnapshotAccount account) {
            return new SnapshotAccount(
                    accountId, account.balance() + delta);
        }
    }

    private record UnknownAccountEvent(AccountId accountId) {
    }

    @Model(ignoreUnknownEvents = true)
    private record LenientAccount(
            @EntityId LenientAccountId accountId, int balance) {
    }

    private static class LenientAccountId extends Id<LenientAccount> {
        private LenientAccountId(String id) {
            super(id, "lenient-account-");
        }
    }

    private record CreateLenientAccount(
            LenientAccountId accountId, int balance) {
        @Apply
        LenientAccount apply() {
            return new LenientAccount(accountId, balance);
        }
    }

    private record UnknownLenientEvent(LenientAccountId accountId) {
    }

    @Model
    private record Shipment(
            @EntityId ShipmentId shipmentId, int score) {
    }

    private static class ShipmentId extends Id<Shipment> {
        private ShipmentId(String id) {
            super(id, "shipment-");
        }
    }

    private record CreateShipment(
            ShipmentId shipmentId,
            InventoryId inventoryId,
            AccountId accountId) {
        @Apply
        Shipment apply(Inventory inventory, Account account) {
            return new Shipment(
                    shipmentId, inventory.available() + account.balance());
        }
    }

    @Model
    private record GraphRoot(
            @EntityId GraphRootId graphRootId, String name) {
    }

    @Model(
            searchable = true,
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "repository-graphs"))
    private record ProjectedRoot(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "repository-graphs"))
    private record AlternateProjectedRoot(
            @EntityId String id) {
    }

    private static class GraphRootId extends Id<GraphRoot> {
        private GraphRootId(String id) {
            super(id, "graph-root-");
        }
    }

    private record CreateGraphRoot(GraphRootId graphRootId, String name) {
        @Apply
        GraphRoot apply() {
            return new GraphRoot(graphRootId, name);
        }
    }

    private record RenameGraphRoot(GraphRootId graphRootId, String name) {
        @Apply
        GraphRoot apply(GraphRoot root) {
            return new GraphRoot(root.graphRootId(), name);
        }
    }

    @Model
    private record GraphChild(
            @EntityId GraphChildId graphChildId,
            @ParentId(path = "children") GraphRootId graphRootId,
            String name) {
    }

    private static class GraphChildId extends Id<GraphChild> {
        private GraphChildId(String id) {
            super(id, "graph-child-");
        }
    }

    private record CreateGraphChild(
            GraphChildId graphChildId, GraphRootId graphRootId, String name) {
        @Apply
        GraphChild apply() {
            return new GraphChild(graphChildId, graphRootId, name);
        }
    }

    private record MoveGraphChild(
            GraphChildId graphChildId, GraphRootId graphRootId) {
        @Apply
        GraphChild apply(GraphChild child) {
            return new GraphChild(graphChildId, graphRootId, child.name());
        }
    }

    @Model
    private record GraphGrandchild(
            @EntityId GraphGrandchildId graphGrandchildId,
            @ParentId(path = "grandchildren") GraphChildId graphChildId,
            String name) {
    }

    private static class GraphGrandchildId extends Id<GraphGrandchild> {
        private GraphGrandchildId(String id) {
            super(id, "graph-grandchild-");
        }
    }

    private record CreateGraphGrandchild(
            GraphGrandchildId graphGrandchildId,
            GraphChildId graphChildId,
            String name) {
        @Apply
        GraphGrandchild apply() {
            return new GraphGrandchild(
                    graphGrandchildId, graphChildId, name);
        }
    }

    @Model
    private record UnplacedChild(
            @EntityId UnplacedChildId unplacedChildId,
            @ParentId GraphRootId graphRootId) {
    }

    private static class UnplacedChildId extends Id<UnplacedChild> {
        private UnplacedChildId(String id) {
            super(id, "unplaced-child-");
        }
    }

    private record CreateUnplacedChild(
            UnplacedChildId unplacedChildId, GraphRootId graphRootId) {
        @Apply
        UnplacedChild apply() {
            return new UnplacedChild(unplacedChildId, graphRootId);
        }
    }
}
