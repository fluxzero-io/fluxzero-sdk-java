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

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ModelCommitEngineTest {

    private final MutationPlan.Compiler compiler =
            new MutationPlan.Compiler(List.of(new PayloadParameterResolver()));

    @Test
    void evaluatesAllWritesAgainstSameBeginStateThenPublishesResultingState() {
        UpdateOrderAndInventory command = new UpdateOrderAndInventory(
                new OrderId("1"), new InventoryId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(UpdateOrderAndInventory.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        CommitAttempt begin = context(command, handlers, order, inventory);

        List<Change> result = evaluate(message(command), begin, handlers);

        assertEquals(2, result.size());
        assertEquals(new Order(command.orderId(), "saw-stock-5"),
                     resultingState(begin, result).resolve(Order.class, null).get());
        assertEquals(new Inventory(command.inventoryId(), 4, "saw-order-pending"),
                     resultingState(begin, result).resolve(Inventory.class, null).get());
        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
        assertEquals(new Inventory(command.inventoryId(), 5, null),
                     begin.resolve(Inventory.class, null).get());
    }

    @Test
    void stagesOnlyReturnedModelsAndLeavesReadDependenciesUntouched() {
        ReserveOrder command = new ReserveOrder(new OrderId("1"), new InventoryId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(ReserveOrder.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        CommitAttempt begin = context(command, handlers, order, inventory);

        List<Change> result = evaluate(message(command), begin, handlers);

        assertEquals(List.of("order-1"),
                     result.stream()
                             .map(Change::modelId).toList());
        assertSame(inventory, resultingState(begin, result).resolve(Inventory.class, null));
    }

    @Test
    void nullReturnStagesLogicalDeleteAndRetainsBeginState() {
        DeleteOrder command = new DeleteOrder(new OrderId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(DeleteOrder.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        CommitAttempt begin = context(command, handlers, order);

        List<Change> result = evaluate(message(command), begin, handlers);

        assertEquals(1, result.size());
        assertNull(result.getFirst().after());
        assertNull(resultingState(begin, result).resolve(Order.class, null).get());
        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
    }

    @Test
    void explicitNoArgumentDeleteStagesLogicalDelete() {
        DeleteOrderWithoutState command = new DeleteOrderWithoutState(new OrderId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(DeleteOrderWithoutState.class).handlerMethods();
        Entity<Order> order = entity(command.orderId(), new Order(command.orderId(), "pending"));
        CommitAttempt begin = context(command, handlers, order);

        List<Change> result = evaluate(message(command), begin, handlers);

        assertEquals(1, result.size());
        assertNull(result.getFirst().after());
        assertNull(resultingState(begin, result).resolve(Order.class, null).get());
    }

    @Test
    void createsWriteOnlyTargetFromReturnedModel() {
        CreateOrder command = new CreateOrder(new OrderId("new"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(CreateOrder.class).handlerMethods();
        Entity<Order> empty = entity(command.orderId(), null);
        CommitAttempt begin = context(command, handlers, empty);

        List<Change> result = evaluate(message(command), begin, handlers);

        assertEquals(new Order(command.orderId(), "created"),
                     resultingState(begin, result).resolve(Order.class, null).get());
        assertNull(result.getFirst().before());
    }

    @Test
    void createsAnOrderedCollectionOfModelsInOneSubstep() {
        CreateOrders command = new CreateOrders(List.of(
                new OrderId("first"), new OrderId("second")));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(CreateOrders.class).handlerMethods();
        CommitAttempt begin = context(command, handlers);

        List<Change> result =
                evaluate(message(command), begin, handlers);

        assertEquals(
                List.of("order-first", "order-second"),
                result.stream()
                        .map(Change::modelId)
                        .toList());
        assertTrue(result.stream()
                           .allMatch(transition -> transition.before() == null
                                                   && transition.beforeSequenceNumber() == -1L));
    }

    @Test
    void createsHeterogeneousRuntimeValidatedModels() {
        CreateMixedModels command = new CreateMixedModels(
                new OrderId("one"), new InventoryId("one"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(CreateMixedModels.class).handlerMethods();

        List<Change> result = evaluate(
                message(command), context(command, handlers), handlers);

        assertEquals(
                List.of("order-one", "inventory-one"),
                result.stream()
                        .map(Change::modelId)
                        .toList());
        assertEquals(
                List.of(Order.class, Inventory.class),
                result.stream()
                        .map(Change::modelType)
                        .toList());
    }

    @Test
    void createsOneRuntimeValidatedObjectResult() {
        CreateDynamicModel command =
                new CreateDynamicModel(new OrderId("dynamic"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(CreateDynamicModel.class)
                        .handlerMethods();

        List<Change> result = evaluate(
                message(command), context(command, handlers), handlers);

        assertEquals(1, result.size());
        assertEquals("order-dynamic",
                     result.getFirst().modelId());
        assertEquals(Order.class,
                     result.getFirst().modelType());
    }

    @Test
    void rejectsDuplicateNullAndNonModelCollectionResults() {
        assertTrue(collectionFailure(
                new InvalidModelCollection(InvalidCollectionResult.DUPLICATE))
                           .getMessage().contains("written by both"));
        assertTrue(collectionFailure(
                new InvalidModelCollection(InvalidCollectionResult.NULL))
                           .getMessage().contains("null model at collection index 1"));
        assertTrue(collectionFailure(
                new InvalidModelCollection(InvalidCollectionResult.NON_MODEL))
                           .getMessage().contains("not annotated with @Model"));
    }

    private IllegalStateException collectionFailure(
            InvalidModelCollection command) {
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(InvalidModelCollection.class)
                        .handlerMethods();
        return assertThrows(
                IllegalStateException.class,
                () -> evaluate(
                        message(command), context(command, handlers), handlers));
    }

    @Test
    void skipsInapplicableInterceptorWithMissingModelAndStillCreatesTarget() {
        ConditionalCreateOrder command = new ConditionalCreateOrder(new OrderId("new"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(ConditionalCreateOrder.class).handlerMethods();
        Entity<Order> empty = entity(command.orderId(), null);
        CommitAttempt begin = context(command, handlers, empty);

        List<Change> result = evaluate(message(command), begin, handlers);

        assertEquals(new Order(command.orderId(), "created"),
                     resultingState(begin, result).resolve(Order.class, null).get());
    }

    @Test
    void rejectsDuplicateWritesWithoutMutatingBeginState() {
        DuplicateOrderWrite command = new DuplicateOrderWrite(new OrderId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(DuplicateOrderWrite.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        CommitAttempt begin = context(command, handlers, order);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> evaluate(message(command), begin, handlers));

        assertTrue(exception.getMessage().contains("written by both"));
        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
    }

    @Test
    void failureRollsBackAllStagedWritesInMemory() {
        FailingMultiWrite command = new FailingMultiWrite(
                new OrderId("1"), new InventoryId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(FailingMultiWrite.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        CommitAttempt begin = context(command, handlers, order, inventory);

        assertThrows(
                MockFailure.class,
                () -> evaluate(message(command), begin, handlers));

        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
        assertEquals(new Inventory(command.inventoryId(), 5, null),
                     begin.resolve(Inventory.class, null).get());
    }

    @Test
    void nonNullDeferredSameTypeReturnSelectsOneCandidateButNullIsAmbiguous() {
        Transfer transfer = new Transfer(
                new AccountId("source"), new AccountId("destination"), false);
        List<EntityMetadata.HandlerMethod> handlers = EntityMetadata.of(Transfer.class).handlerMethods();
        Entity<Account> source = entity(
                transfer.sourceId(), new Account(transfer.sourceId(), 10));
        Entity<Account> destination = entity(
                transfer.destinationId(), new Account(transfer.destinationId(), 20));
        CommitAttempt begin = context(transfer, handlers, source, destination);

        List<Change> result = evaluate(message(transfer), begin, handlers);

        assertEquals(List.of("account-destination"),
                     result.stream()
                             .map(Change::modelId).toList());
        assertEquals(30, ((Account) result.getFirst().after()).balance());

        Transfer delete = new Transfer(
                transfer.sourceId(), transfer.destinationId(), true);
        CommitAttempt deleteBegin = context(delete, handlers, source, destination);
        assertTrue(assertThrows(
                IllegalStateException.class,
                () -> evaluate(message(delete), deleteBegin, handlers))
                           .getMessage().contains("delete target is ambiguous"));
    }

    @Test
    void rejectsReturnedIdentityOutsideResolvedTargets() {
        ReturnOtherOrder command = new ReturnOtherOrder(new OrderId("1"));
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(ReturnOtherOrder.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        CommitAttempt begin = context(command, handlers, order);

        assertTrue(assertThrows(
                IllegalStateException.class,
                () -> evaluate(message(command), begin, handlers))
                           .getMessage().contains("not a resolved write target"));
    }

    @Test
    void executesInterceptorExpansionInOrderAtOnePinnedStateBoundary() {
        InventoryId firstId = new InventoryId("first");
        InventoryId secondId = new InventoryId("second");
        BulkInventoryUpdate command = new BulkInventoryUpdate(List.of(
                new AdjustInventory(firstId, -1),
                new AdjustInventory(secondId, -2)));
        Map<String, Entity<?>> stored = Map.of(
                firstId.toString(), entity(firstId, new Inventory(firstId, 5)),
                secondId.toString(), entity(secondId, new Inventory(secondId, 10)));
        List<Long> requestedStateIndices = new ArrayList<>();
        DeserializingMessage source = message(command);

        CommitAttempt result = evaluate(
                source,
                (substep, requestedStateIndex, stagedValues) -> {
                    requestedStateIndices.add(requestedStateIndex);
                    return resolveSubstep(substep, 77, stored);
                });

        assertEquals(java.util.Arrays.asList(null, 77L, 77L), requestedStateIndices);
        assertEquals(77, result.readStateIndex());
        assertEquals(
                List.of(firstId.toString(), secondId.toString()),
                result.readModelIds());
        assertEquals(
                List.of(firstId, secondId),
                result.steps().stream().map(CommitAttempt.Step::message)
                        .map(DeserializingMessage::getPayload)
                        .map(AdjustInventory.class::cast)
                        .map(AdjustInventory::inventoryId).toList());
        assertEquals(4, ((Inventory) finalValues(result).get(firstId.toString())).available());
        assertEquals(8, ((Inventory) finalValues(result).get(secondId.toString())).available());
        assertNotEquals(result.steps().getFirst().message().getMessageId(),
                        result.steps().getLast().message().getMessageId());
        assertNotEquals(source.getMessageId(), result.steps().getFirst().message().getMessageId());
        assertEquals(source.getTimestamp(), result.steps().getFirst().message().getTimestamp());
    }

    @Test
    void representsOneOriginalEventOnceWithAllOfItsTargetTransitions() {
        OrderId orderId = new OrderId("one");
        InventoryId inventoryId = new InventoryId("one");
        UpdateOrderAndInventory command =
                new UpdateOrderAndInventory(orderId, inventoryId);
        Map<String, Entity<?>> stored = Map.of(
                orderId.toString(), entity(orderId, new Order(orderId, "pending")),
                inventoryId.toString(), entity(
                        inventoryId, new Inventory(inventoryId, 5)));

        CommitAttempt result = evaluate(
                message(command),
                (substep, requestedStateIndex, stagedValues) ->
                        resolveSubstep(substep, 79, stored));

        assertEquals(1, result.steps().size());
        assertSame(command, result.steps().getFirst().message().getPayload());
        assertEquals(
                List.of(inventoryId.toString(), orderId.toString()),
                result.steps().getFirst().changes().stream()
                        .map(Change::modelId)
                        .sorted().toList());
    }

    @Test
    void laterInterceptorSubstepObservesEarlierResultForTheSameModel() {
        InventoryId id = new InventoryId("one");
        BulkInventoryUpdate command = new BulkInventoryUpdate(List.of(
                new AdjustInventory(id, 1),
                new AdjustInventory(id, 2)));
        Map<String, Entity<?>> stored =
                Map.of(id.toString(), entity(id, new Inventory(id, 5)));

        CommitAttempt result = evaluate(
                message(command),
                (substep, requestedStateIndex, stagedValues) ->
                        resolveSubstep(substep, 88, stored));

        assertEquals(2, result.steps().size());
        assertEquals(5, ((Inventory) result.transitions().get(0).before()).available());
        assertEquals(6, ((Inventory) result.transitions().get(1).before()).available());
        assertEquals(8, ((Inventory) finalValues(result).get(id.toString())).available());
    }

    @Test
    void failureInALaterSubstepRollsBackTheCompleteCommitEvaluation() {
        InventoryId id = new InventoryId("one");
        BulkMixedUpdate command = new BulkMixedUpdate(List.of(
                new AdjustInventory(id, 1),
                new FailingInventoryUpdate(id)));
        Entity<Inventory> storedInventory =
                entity(id, new Inventory(id, 5));
        Map<String, Entity<?>> stored = Map.of(id.toString(), storedInventory);
        DeserializingMessage message = message(command);

        assertThrows(
                MockFailure.class,
                () -> evaluate(
                        message,
                        (substep, requestedStateIndex, stagedValues) ->
                                resolveSubstep(substep, 89, stored)));

        assertEquals(new Inventory(id, 5), storedInventory.get());
        assertTrue(message.getContext(CommitAttempt.class)
                           .orElseThrow().modelIds().isEmpty());
    }

    @Test
    void sameTypeInterceptorReplacementIsResolvedAgainWithoutRecursiveLoop() {
        InventoryId id = new InventoryId("one");
        NormalizeInventory command = new NormalizeInventory(id, -2, false);
        Map<String, Entity<?>> stored =
                Map.of(id.toString(), entity(id, new Inventory(id, 5)));
        int[] resolutions = {0};

        DeserializingMessage source = message(command);
        CommitAttempt result = evaluate(
                source,
                (substep, requestedStateIndex, stagedValues) -> {
                    resolutions[0]++;
                    return resolveSubstep(substep, 91, stored);
                });

        assertEquals(2, resolutions[0]);
        assertEquals(1, result.steps().size());
        assertEquals(source.getMessageId(), result.steps().getFirst().message().getMessageId());
        assertTrue(((NormalizeInventory) result.steps().getFirst().message()
                .getPayload()).normalized());
        assertEquals(3, ((Inventory) finalValues(result).get(id.toString())).available());
    }

    @Test
    void interceptorMaySuppressACommitWithoutCreatingASubstep() {
        CommitAttempt result = evaluate(
                message(new SuppressInventoryUpdate()),
                (substep, requestedStateIndex, stagedValues) ->
                        resolveSubstep(substep, 12, Map.of()));

        assertEquals(12, result.readStateIndex());
        assertTrue(result.steps().isEmpty());
        assertTrue(finalValues(result).isEmpty());
    }

    @Test
    void interceptorMayStageGraphDeletionInTheSameCommitAndRebaseIt() {
        OrderId orderId = new OrderId("one");
        InventoryId inventoryId = new InventoryId("one");
        Entity<Order> order = entity(
                orderId, new Order(orderId, "pending"));
        Entity<Inventory> inventory = entity(
                inventoryId, new Inventory(inventoryId, 5));
        Graph<Inventory> inventoryGraph = Graphs.lazy(
                inventory, 77L, mock(ModelRepository.class));
        RemoveInventory command = new RemoveInventory(
                orderId, inventoryGraph);
        Map<String, Entity<?>> stored = Map.of(
                orderId.toString(), order,
                inventoryId.toString(), inventory);
        ModelReducer.SubstepResolver resolver =
                new ModelReducer.SubstepResolver() {
                    @Override
                    public ModelReducer.ResolvedSubstep resolve(
                            DeserializingMessage substep,
                            Long requestedStateIndex,
                            Map<String, Object> stagedValues) {
                        return resolveSubstep(
                                substep,
                                requestedStateIndex == null
                                        ? 77L : requestedStateIndex,
                                stored);
                    }

                    @Override
                    public ModelReducer.ResolvedSubstep resolveGraph(
                            String modelId,
                            Class<?> modelType,
                            Long requestedStateIndex,
                            Map<String, Object> stagedValues) {
                        MutationPlan.Resolution resolution =
                                new MutationPlan.Resolution(
                                        List.of(new MutationPlan.ResolvedModel(
                                                modelId, modelType,
                                                MutationPlan.Access.READ_WRITE,
                                                List.of())),
                                        List.of());
                        return new ModelReducer.ResolvedSubstep(
                                CommitAttempt.create(
                                        requestedStateIndex == null
                                                ? 77L : requestedStateIndex,
                                        resolution,
                                        Map.of(modelId, stored.get(modelId))),
                                ModelReducer.EMPTY);
                    }
                };

        CommitAttempt result = evaluate(
                message(command), resolver);

        assertEquals(
                List.of(orderId.toString(), inventoryId.toString()),
                result.readModelIds());
        assertEquals(2, result.steps().size());
        assertEquals(1, result.steps().getFirst().changes().size());
        assertEquals(1, result.steps().getLast().changes().size());
        assertSame(command, result.steps().getFirst().message().getPayload());
        assertNull(finalValues(result).get(inventoryId.toString()));

        DeserializingMessage deletionEvent =
                result.steps().getFirst().message();
        CommitAttempt rebased = reapply(
                List.of(
                        result.steps().getFirst().message(),
                        ModelReducer.graphChangeReplay(
                                deletionEvent,
                                inventoryId.toString(), Inventory.class,
                                result.steps().getLast().changes().stream()
                                        .filter(transition -> transition.modelId()
                                                .equals(inventoryId.toString()))
                                        .findFirst().orElseThrow().replay())),
                resolver);

        assertEquals(2, rebased.steps().size());
        assertEquals(1, rebased.steps().getFirst().changes().size());
        assertEquals(1, rebased.steps().getLast().changes().size());
        assertNull(finalValues(rebased).get(inventoryId.toString()));
    }

    @Test
    void interceptorMayStageGraphUpdateAndReplayItAgainstAConflictBoundary() {
        OrderId orderId = new OrderId("graph-update");
        InventoryId inventoryId = new InventoryId("graph-update");
        Entity<Order> order = entity(orderId, new Order(orderId, "pending"));
        Entity<Inventory> inventory = entity(
                inventoryId, new Inventory(inventoryId, 5));
        Graph<Inventory> inventoryGraph = Graphs.lazy(
                inventory, 77L, mock(ModelRepository.class));
        AdjustInventoryGraph command = new AdjustInventoryGraph(
                orderId, inventoryGraph, 2);
        CommitAttempt result = evaluate(
                message(command), graphResolver(
                        77L, Map.of(orderId.toString(), order,
                                    inventoryId.toString(), inventory)));

        assertEquals(7, ((Inventory) finalValues(result)
                .get(inventoryId.toString())).available());
        Change graphTransition = result.steps().getLast().changes().stream()
                .filter(transition -> transition.modelId()
                        .equals(inventoryId.toString()))
                .findFirst().orElseThrow();
        assertNotNull(graphTransition.replay());

        Entity<Inventory> concurrent = entity(
                inventoryId, new Inventory(inventoryId, 9));
        DeserializingMessage event = result.steps().getFirst().message();
        CommitAttempt rebased = reapply(
                List.of(event, ModelReducer.graphChangeReplay(
                        event, inventoryId.toString(), Inventory.class,
                        graphTransition.replay())),
                graphResolver(
                        88L, Map.of(orderId.toString(), order,
                                    inventoryId.toString(), concurrent)));

        assertEquals(11, ((Inventory) finalValues(rebased)
                .get(inventoryId.toString())).available());
        assertEquals(2, rebased.steps().size());
    }

    @Test
    void combinesRepeatedGraphUpdatesToOneModelInTheSameCommitSubstep() {
        OrderId orderId = new OrderId("repeated-graph-update");
        InventoryId inventoryId = new InventoryId("repeated-graph-update");
        Entity<Order> order = entity(orderId, new Order(orderId, "pending"));
        Entity<Inventory> inventory = entity(
                inventoryId, new Inventory(inventoryId, 5));
        Graph<Inventory> first = Graphs.lazy(
                inventory, 77L, mock(ModelRepository.class));
        Graph<Inventory> second = Graphs.lazy(
                inventory, 77L, mock(ModelRepository.class));
        AdjustInventoryGraphs command = new AdjustInventoryGraphs(
                orderId, first, second);
        CommitAttempt result = evaluate(
                message(command), graphResolver(
                        77L, Map.of(orderId.toString(), order,
                                    inventoryId.toString(), inventory)));

        assertEquals(8, ((Inventory) finalValues(result)
                .get(inventoryId.toString())).available());
        assertEquals(2, result.steps().size());
        assertEquals(1, result.steps().getLast().changes().size());
        Change combined =
                result.steps().getLast().changes().getFirst();
        assertEquals(new Inventory(inventoryId, 5), combined.before());
        assertEquals(new Inventory(inventoryId, 8), combined.after());

        Entity<Inventory> concurrent = entity(
                inventoryId, new Inventory(inventoryId, 9));
        DeserializingMessage event = result.steps().getFirst().message();
        CommitAttempt rebased = reapply(
                List.of(event, ModelReducer.graphChangeReplay(
                        event, inventoryId.toString(), Inventory.class,
                        combined.replay())),
                graphResolver(
                        88L, Map.of(orderId.toString(), order,
                                    inventoryId.toString(), concurrent)));

        assertEquals(12, ((Inventory) finalValues(rebased)
                .get(inventoryId.toString())).available());
        assertEquals(1, rebased.steps().getLast().changes().size());
    }

    @Test
    void rejectsSubstepLoadedAtAnotherStateBoundary() {
        InventoryId id = new InventoryId("one");
        BulkInventoryUpdate command =
                new BulkInventoryUpdate(List.of(new AdjustInventory(id, 1)));
        Map<String, Entity<?>> stored =
                Map.of(id.toString(), entity(id, new Inventory(id, 5)));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> evaluate(
                        message(command),
                        (substep, requestedStateIndex, stagedValues) -> resolveSubstep(
                                substep, requestedStateIndex == null ? 70 : 71, stored)));

        assertTrue(exception.getMessage().contains(
                "loaded at state index 71 while commit is pinned at 70"));
    }

    @Test
    void commitResultRetainsNullDeleteTransitionAndOriginalEvent() {
        OrderId id = new OrderId("delete");
        DeleteOrder command = new DeleteOrder(id);
        Map<String, Entity<?>> stored =
                Map.of(id.toString(), entity(id, new Order(id, "pending")));

        CommitAttempt result = evaluate(
                message(command),
                (substep, requestedStateIndex, stagedValues) ->
                        resolveSubstep(substep, -1, stored));

        assertEquals(-1, result.readStateIndex());
        assertSame(command, result.steps().getFirst().message().getPayload());
        assertEquals(1, result.transitions().size());
        assertTrue(finalValues(result).containsKey(id.toString()));
        assertNull(finalValues(result).get(id.toString()));
    }

    @Test
    void invokesAssertionsInterceptorsAndAppliesOnAModelReceiver() {
        ReceiverOrderId id = new ReceiverOrderId("one");
        List<String> observations = new ArrayList<>();
        RenameReceiverOrder command =
                new RenameReceiverOrder(id, "renamed", observations);
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(ReceiverOrder.class).handlerMethods();
        CommitAttempt begin = context(
                command, handlers, entity(id, new ReceiverOrder(id, "initial")));

        CommitAttempt result = evaluate(
                message(command),
                (substep, requestedStateIndex, stagedValues) ->
                        new ModelReducer.ResolvedSubstep(
                                begin, new ModelReducer(
                                        compiler.compileHandlers(handlers), null)));

        assertEquals(
                List.of("intercept-initial", "assert-initial", "apply-initial"),
                observations);
        assertEquals(
                new ReceiverOrder(id, "renamed"),
                finalValues(result).get(id.toString()));
    }

    @Test
    void runsBeforeAssertionsByPriorityAndAfterAssertionsAgainstResultingState() {
        OrderId id = new OrderId("assert");
        List<String> observations = new ArrayList<>();
        AssertedOrderUpdate command = new AssertedOrderUpdate(id, observations);
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(AssertedOrderUpdate.class).handlerMethods();
        CommitAttempt begin = context(
                command, handlers, entity(id, new Order(id, "pending")));

        List<Change> result =
                evaluate(message(command), begin, handlers);

        assertEquals(
                List.of("before-high-pending", "before-low-pending", "after-updated"),
                observations);
        assertEquals(new Order(id, "updated"),
                     resultingState(begin, result).resolve(Order.class, null).get());
    }

    @Test
    void validationOnlyRunsImmediateAssertionsWithoutAppliesOrAfterAssertions() {
        OrderId id = new OrderId("validate-only");
        List<String> observations = new ArrayList<>();
        AssertedOrderUpdate command = new AssertedOrderUpdate(id, observations);
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(AssertedOrderUpdate.class).handlerMethods();
        Entity<Order> order = entity(id, new Order(id, "pending"));
        CommitAttempt begin = context(command, handlers, order);

        ModelReducer.assertLegal(
                new CommitAttempt(),
                message(command),
                (substep, requestedStateIndex, stagedValues) ->
                        new ModelReducer.ResolvedSubstep(
                                begin, new ModelReducer(
                                        compiler.compileHandlers(handlers), null)));

        assertEquals(
                List.of("before-high-pending", "before-low-pending"),
                observations);
        assertEquals(new Order(id, "pending"), order.get());
    }

    @Test
    void validationOnlyRunsApplyInterceptorBeforeImmediateAssertions() {
        ReceiverOrderId id = new ReceiverOrderId("validate-only");
        List<String> observations = new ArrayList<>();
        RenameReceiverOrder command =
                new RenameReceiverOrder(id, "ignored", observations);
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(ReceiverOrder.class).handlerMethods();
        Entity<ReceiverOrder> order = entity(
                id, new ReceiverOrder(id, "initial"));
        CommitAttempt begin = context(command, handlers, order);

        ModelReducer.assertLegal(
                new CommitAttempt(),
                message(command),
                (substep, requestedStateIndex, stagedValues) ->
                        new ModelReducer.ResolvedSubstep(
                                begin, new ModelReducer(
                                        compiler.compileHandlers(handlers), null)));

        assertEquals(
                List.of("intercept-initial", "assert-initial"),
                observations);
        assertEquals(new ReceiverOrder(id, "initial"), order.get());
    }

    @Test
    void failingAfterAssertionExposesNoPartiallyUpdatedContext() {
        OrderId id = new OrderId("reject");
        RejectAfterOrderUpdate command = new RejectAfterOrderUpdate(id);
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(RejectAfterOrderUpdate.class).handlerMethods();
        CommitAttempt begin = context(
                command, handlers, entity(id, new Order(id, "pending")));
        DeserializingMessage message = message(command);

        assertThrows(
                MockFailure.class,
                () -> evaluate(message, begin, handlers));

        assertEquals(new Order(id, "pending"), begin.resolve(Order.class, null).get());
        assertSame(begin, message.getContext(CommitAttempt.class).orElseThrow());
    }

    @Test
    void directlyInvokesSimpleSingleTargetModelReceiver() {
        FastOrderId id = new FastOrderId("one");
        RenameFastOrder command = new RenameFastOrder(id, "updated");
        EntityMetadata.HandlerMethod handler = EntityMetadata.of(FastOrder.class)
                .applyMethods().getFirst();
        CommitAttempt begin = context(
                command, List.of(handler),
                entity(id, new FastOrder(id, "initial")));
        MutationPlan.DirectSingleTargetApply direct =
                MutationPlan.directSingleTargetApply(
                        handler, RenameFastOrder.class);

        assertTrue(direct != null);
        List<Change> result =
                evaluate(
                        message(command), begin,
                        List.of(handler), direct);

        assertEquals(1, result.size());
        assertEquals(
                new FastOrder(id, "updated"),
                result.getFirst().after());
    }

    @Test
    void directInvocationRejectsModelInjectionParameters() {
        EntityMetadata.HandlerMethod handler = EntityMetadata.of(Transfer.class)
                .applyMethods().getFirst();

        assertNull(MutationPlan.directSingleTargetApply(
                handler, Transfer.class));
    }

    private List<Change> evaluate(
            DeserializingMessage message,
            CommitAttempt context,
            Collection<EntityMetadata.HandlerMethod> handlers) {
        return new ModelReducer(
                compiler.compileHandlers(handlers), null)
                .apply(message, context, true, true);
    }

    private List<Change> evaluate(
            DeserializingMessage message,
            CommitAttempt context,
            Collection<EntityMetadata.HandlerMethod> handlers,
            MutationPlan.DirectSingleTargetApply directApply) {
        return new ModelReducer(
                compiler.compileHandlers(handlers), directApply)
                .apply(message, context, true, true);
    }

    private CommitAttempt evaluate(
            DeserializingMessage message,
            ModelReducer.SubstepResolver resolver) {
        return ModelReducer.apply(new CommitAttempt(), List.of(message), resolver);
    }

    private CommitAttempt reapply(
            List<DeserializingMessage> messages,
            ModelReducer.SubstepResolver resolver) {
        return ModelReducer.reapply(new CommitAttempt(), messages, resolver);
    }

    private ModelReducer.ResolvedSubstep resolveSubstep(
            DeserializingMessage message,
            long stateIndex,
            Map<String, Entity<?>> stored) {
        Object payload = message.getPayload();
        List<EntityMetadata.HandlerMethod> handlers =
                EntityMetadata.of(payload.getClass()).handlerMethods();
        MutationPlan.Resolution resolution =
                MutationPlan.compile(payload.getClass(), handlers)
                        .resolve(payload);
        Map<String, Entity<?>> loaded = new LinkedHashMap<>();
        for (MutationPlan.ResolvedModel target : resolution.models()) {
            Entity<?> entity = stored.get(target.modelId());
            if (entity == null) {
                throw new IllegalArgumentException("Missing fixture model " + target.modelId());
            }
            loaded.put(target.modelId(), entity);
        }
        return new ModelReducer.ResolvedSubstep(
                CommitAttempt.create(stateIndex, resolution, loaded),
                new ModelReducer(
                        compiler.compileHandlers(handlers), null));
    }

    private ModelReducer.SubstepResolver graphResolver(
            long stateIndex,
            Map<String, Entity<?>> stored) {
        return new ModelReducer.SubstepResolver() {
            @Override
            public ModelReducer.ResolvedSubstep resolve(
                    DeserializingMessage substep,
                    Long requestedStateIndex,
                    Map<String, Object> stagedValues) {
                return resolveSubstep(
                        substep,
                        requestedStateIndex == null
                                ? stateIndex : requestedStateIndex,
                        stored);
            }

            @Override
            public ModelReducer.ResolvedSubstep resolveGraph(
                    String modelId,
                    Class<?> modelType,
                    Long requestedStateIndex,
                    Map<String, Object> stagedValues) {
                MutationPlan.Resolution resolution =
                        new MutationPlan.Resolution(
                                List.of(new MutationPlan.ResolvedModel(
                                        modelId, modelType,
                                        MutationPlan.Access.READ_WRITE,
                                        List.of())),
                                List.of());
                return new ModelReducer.ResolvedSubstep(
                        CommitAttempt.create(
                                requestedStateIndex == null
                                        ? stateIndex : requestedStateIndex,
                                resolution,
                                Map.of(modelId, stored.get(modelId))),
                        ModelReducer.EMPTY);
            }
        };
    }

    private static CommitAttempt context(
            Object command,
            Collection<EntityMetadata.HandlerMethod> handlers,
            Entity<?>... entities) {
        MutationPlan.Resolution resolution =
                MutationPlan.compile(command.getClass(), handlers)
                        .resolve(command);
        Map<String, Entity<?>> loaded = new LinkedHashMap<>();
        for (Entity<?> entity : entities) {
            loaded.put(entity.id().toString(), entity);
        }
        return CommitAttempt.create(101, resolution, loaded);
    }

    private static CommitAttempt resultingState(
            CommitAttempt begin,
            List<Change> transitions) {
        return begin.withValues(finalValues(transitions));
    }

    private static Map<String, Object> finalValues(CommitAttempt attempt) {
        return finalValues(attempt.transitions());
    }

    private static Map<String, Object> finalValues(List<Change> transitions) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        transitions.forEach(
                transition -> values.put(transition.modelId(), transition.after()));
        return values;
    }

    private static DeserializingMessage message(Object command) {
        return new DeserializingMessage(new Message(command), MessageType.EVENT, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entity<T> entity(Object id, T value) {
        Class<T> type = value == null
                ? (Class<T>) ((Id<?>) id).getType() : (Class<T>) value.getClass();
        return ImmutableEntity.<T>builder().id(id).type(type).value(value).build();
    }

    @Model
    private record Order(@EntityId OrderId orderId, String status) {
    }

    private static class OrderId extends Id<Order> {
        private OrderId(String id) {
            super(id, "order-");
        }
    }

    @Model
    private record Inventory(
            @EntityId InventoryId inventoryId, int available, String observation) {
        private Inventory(InventoryId inventoryId, int available) {
            this(inventoryId, available, null);
        }
    }

    private static class InventoryId extends Id<Inventory> {
        private InventoryId(String id) {
            super(id, "inventory-");
        }
    }

    private record UpdateOrderAndInventory(OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order updateOrder(Order order, Inventory inventory) {
            return new Order(order.orderId(), "saw-stock-" + inventory.available());
        }

        @Apply
        Inventory updateInventory(Order order, Inventory inventory) {
            return new Inventory(
                    inventory.inventoryId(), inventory.available() - 1,
                    "saw-order-" + order.status());
        }
    }

    private record ReserveOrder(OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order reserve(Order order, Inventory inventory) {
            return new Order(order.orderId(), "reserved-" + inventory.available());
        }
    }

    private record DeleteOrder(OrderId orderId) {
        @Apply
        Order delete(Order order) {
            return null;
        }
    }

    private record DeleteOrderWithoutState(OrderId orderId) {
        @Apply
        Order delete() {
            return null;
        }
    }

    private record CreateOrder(OrderId orderId) {
        @Apply
        Order create() {
            return new Order(orderId, "created");
        }
    }

    private record CreateOrders(List<OrderId> orderIds) {
        @Apply
        List<Order> create() {
            return orderIds.stream()
                    .map(id -> new Order(
                            id, "created-" + id.getFunctionalId()))
                    .toList();
        }
    }

    private record CreateMixedModels(
            OrderId orderId,
            InventoryId inventoryId) {
        @Apply
        List<Object> create() {
            return List.of(
                    new Order(orderId, "created"),
                    new Inventory(inventoryId, 1));
        }
    }

    private record CreateDynamicModel(
            OrderId orderId) {
        @Apply
        Object create() {
            return new Order(orderId, "created");
        }
    }

    private enum InvalidCollectionResult {
        DUPLICATE,
        NULL,
        NON_MODEL
    }

    private record InvalidModelCollection(
            InvalidCollectionResult result) {
        @Apply
        List<Object> create() {
            Order order = new Order(
                    new OrderId("duplicate"), "created");
            return switch (result) {
                case DUPLICATE -> List.of(order, order);
                case NULL -> java.util.Arrays.asList(order, null);
                case NON_MODEL -> List.of(order, "not-a-model");
            };
        }
    }

    private record ConditionalCreateOrder(OrderId orderId) {
        @InterceptApply
        ConditionalCreateOrder updateExisting(Order existing) {
            throw new AssertionError("Interceptor should be skipped for an empty target");
        }

        @Apply
        Order create() {
            return new Order(orderId, "created");
        }
    }

    private record DuplicateOrderWrite(OrderId orderId) {
        @Apply
        Order alpha(Order order) {
            return new Order(order.orderId(), "alpha");
        }

        @Apply
        Order bravo(Order order) {
            return new Order(order.orderId(), "bravo");
        }
    }

    private record FailingMultiWrite(OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order alpha(Order order) {
            return new Order(order.orderId(), "changed");
        }

        @Apply
        Inventory bravo(Inventory inventory) {
            throw new MockFailure();
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

    private record Transfer(AccountId sourceId, AccountId destinationId, boolean delete) {
        @Apply
        Account transfer(
                @io.fluxzero.sdk.tracking.handling.Association("sourceId") Account source,
                @io.fluxzero.sdk.tracking.handling.Association("destinationId") Account destination) {
            return delete ? null : new Account(
                    destination.accountId(), source.balance() + destination.balance());
        }
    }

    private record ReturnOtherOrder(OrderId orderId) {
        @Apply
        Order apply(Order order) {
            return new Order(new OrderId("other"), order.status());
        }
    }

    private record BulkInventoryUpdate(List<AdjustInventory> updates) {
        @InterceptApply
        List<AdjustInventory> expand() {
            return updates;
        }
    }

    private record BulkMixedUpdate(List<Object> updates) {
        @InterceptApply
        List<Object> expand() {
            return updates;
        }
    }

    private record AdjustInventory(InventoryId inventoryId, int delta) {
        @Apply
        Inventory adjust(Inventory inventory) {
            return new Inventory(
                    inventory.inventoryId(), inventory.available() + delta);
        }
    }

    private record FailingInventoryUpdate(InventoryId inventoryId) {
        @Apply
        Inventory fail(Inventory inventory) {
            throw new MockFailure();
        }
    }

    private record NormalizeInventory(
            InventoryId inventoryId, int delta, boolean normalized) {
        @InterceptApply
        NormalizeInventory normalize() {
            return normalized ? this : new NormalizeInventory(inventoryId, delta, true);
        }

        @Apply
        Inventory adjust(Inventory inventory) {
            return new Inventory(
                    inventory.inventoryId(), inventory.available() + delta);
        }
    }

    private record SuppressInventoryUpdate() {
        @InterceptApply
        Object suppress() {
            return null;
        }
    }

    private record RemoveInventory(
            OrderId orderId,
            Graph<Inventory> inventory) {
        @InterceptApply
        List<?> expand() {
            return List.of(this, inventory.delete());
        }

        @Apply
        Order retain(Order order) {
            return order;
        }
    }

    private record AdjustInventoryGraph(
            OrderId orderId,
            Graph<Inventory> inventory,
            int delta) {
        @InterceptApply
        List<?> expand() {
            return List.of(this, inventory.update(value -> new Inventory(
                    value.inventoryId(), value.available() + delta)));
        }

        @Apply
        Order retain(Order order) {
            return order;
        }
    }

    private record AdjustInventoryGraphs(
            OrderId orderId,
            Graph<Inventory> first,
            Graph<Inventory> second) {
        @InterceptApply
        List<?> expand() {
            return List.of(
                    this,
                    first.update(value -> new Inventory(
                            value.inventoryId(), value.available() + 1)),
                    second.update(value -> new Inventory(
                            value.inventoryId(), value.available() + 2)));
        }

        @Apply
        Order retain(Order order) {
            return order;
        }
    }

    private record AssertedOrderUpdate(OrderId orderId, List<String> observations) {
        @AssertLegal(priority = 100)
        void beforeHigh(Order order) {
            observations.add("before-high-" + order.status());
        }

        @AssertLegal(priority = -100)
        void beforeLow(Order order) {
            observations.add("before-low-" + order.status());
        }

        @Apply
        Order apply(Order order) {
            return new Order(order.orderId(), "updated");
        }

        @AssertLegal(afterHandler = true)
        void after(Order order) {
            observations.add("after-" + order.status());
        }
    }

    private record RejectAfterOrderUpdate(OrderId orderId) {
        @Apply
        Order apply(Order order) {
            return new Order(order.orderId(), "updated");
        }

        @AssertLegal(afterHandler = true)
        void after(Order order) {
            if ("updated".equals(order.status())) {
                throw new MockFailure();
            }
        }
    }

    @Model
    private record ReceiverOrder(
            @EntityId ReceiverOrderId receiverOrderId, String name) {
        @InterceptApply
        RenameReceiverOrder intercept(RenameReceiverOrder update) {
            update.observations().add("intercept-" + name);
            return update;
        }

        @AssertLegal
        void assertRename(RenameReceiverOrder update) {
            update.observations().add("assert-" + name);
        }

        @Apply
        ReceiverOrder rename(RenameReceiverOrder update) {
            update.observations().add("apply-" + name);
            return new ReceiverOrder(receiverOrderId, update.name());
        }
    }

    private static class ReceiverOrderId extends Id<ReceiverOrder> {
        private ReceiverOrderId(String id) {
            super(id, "receiver-order-");
        }
    }

    private record RenameReceiverOrder(
            ReceiverOrderId receiverOrderId, String name, List<String> observations) {
    }

    @Model
    private record FastOrder(
            @EntityId FastOrderId fastOrderId,
            String name) {
        @Apply
        FastOrder rename(RenameFastOrder command) {
            return new FastOrder(fastOrderId, command.name());
        }
    }

    private static class FastOrderId extends Id<FastOrder> {
        private FastOrderId(String id) {
            super(id, "fast-order-");
        }
    }

    private record RenameFastOrder(
            FastOrderId fastOrderId,
            String name) {
    }

    private static class MockFailure extends RuntimeException {
    }
}
