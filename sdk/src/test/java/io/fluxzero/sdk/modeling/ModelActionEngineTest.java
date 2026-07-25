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
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelActionEngineTest {

    private final ModelActionEngine engine =
            new ModelActionEngine(List.of(new PayloadParameterResolver()));

    @Test
    void evaluatesAllWritesAgainstSameBeginStateThenPublishesResultingState() {
        UpdateOrderAndInventory command = new UpdateOrderAndInventory(
                new OrderId("1"), new InventoryId("1"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(UpdateOrderAndInventory.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        ModelActionContext begin = context(command, handlers, order, inventory);

        ModelActionEngine.Evaluation result = engine.evaluate(message(command), begin, handlers);

        assertEquals(2, result.transitions().size());
        assertEquals(new Order(command.orderId(), "saw-stock-5"),
                     result.resultingState().resolve(Order.class, null).get());
        assertEquals(new Inventory(command.inventoryId(), 4, "saw-order-pending"),
                     result.resultingState().resolve(Inventory.class, null).get());
        assertSame(begin, result.beginState());
        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
        assertEquals(new Inventory(command.inventoryId(), 5, null),
                     begin.resolve(Inventory.class, null).get());
    }

    @Test
    void stagesOnlyReturnedModelsAndLeavesReadDependenciesUntouched() {
        ReserveOrder command = new ReserveOrder(new OrderId("1"), new InventoryId("1"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(ReserveOrder.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        ModelActionContext begin = context(command, handlers, order, inventory);

        ModelActionEngine.Evaluation result = engine.evaluate(message(command), begin, handlers);

        assertEquals(List.of("order-1"),
                     result.transitions().stream()
                             .map(ModelActionEngine.Transition::modelId).toList());
        assertSame(inventory, result.resultingState().resolve(Inventory.class, null));
    }

    @Test
    void nullReturnStagesLogicalDeleteAndRetainsBeginState() {
        DeleteOrder command = new DeleteOrder(new OrderId("1"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(DeleteOrder.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        ModelActionContext begin = context(command, handlers, order);

        ModelActionEngine.Evaluation result = engine.evaluate(message(command), begin, handlers);

        assertEquals(1, result.transitions().size());
        assertNull(result.transitions().getFirst().after());
        assertNull(result.resultingState().resolve(Order.class, null).get());
        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
    }

    @Test
    void createsWriteOnlyTargetFromReturnedModel() {
        CreateOrder command = new CreateOrder(new OrderId("new"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(CreateOrder.class).handlerMethods();
        Entity<Order> empty = entity(command.orderId(), null);
        ModelActionContext begin = context(command, handlers, empty);

        ModelActionEngine.Evaluation result = engine.evaluate(message(command), begin, handlers);

        assertEquals(new Order(command.orderId(), "created"),
                     result.resultingState().resolve(Order.class, null).get());
        assertNull(result.transitions().getFirst().before());
    }

    @Test
    void skipsInapplicableInterceptorWithMissingModelAndStillCreatesTarget() {
        ConditionalCreateOrder command = new ConditionalCreateOrder(new OrderId("new"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(ConditionalCreateOrder.class).handlerMethods();
        Entity<Order> empty = entity(command.orderId(), null);
        ModelActionContext begin = context(command, handlers, empty);

        ModelActionEngine.Evaluation result = engine.evaluate(message(command), begin, handlers);

        assertEquals(new Order(command.orderId(), "created"),
                     result.resultingState().resolve(Order.class, null).get());
    }

    @Test
    void rejectsDuplicateWritesWithoutMutatingBeginState() {
        DuplicateOrderWrite command = new DuplicateOrderWrite(new OrderId("1"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(DuplicateOrderWrite.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        ModelActionContext begin = context(command, handlers, order);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> engine.evaluate(message(command), begin, handlers));

        assertTrue(exception.getMessage().contains("written by both"));
        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
    }

    @Test
    void failureRollsBackAllStagedWritesInMemory() {
        FailingMultiWrite command = new FailingMultiWrite(
                new OrderId("1"), new InventoryId("1"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(FailingMultiWrite.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        ModelActionContext begin = context(command, handlers, order, inventory);

        assertThrows(
                MockFailure.class,
                () -> engine.evaluate(message(command), begin, handlers));

        assertEquals(new Order(command.orderId(), "pending"), begin.resolve(Order.class, null).get());
        assertEquals(new Inventory(command.inventoryId(), 5, null),
                     begin.resolve(Inventory.class, null).get());
    }

    @Test
    void nonNullDeferredSameTypeReturnSelectsOneCandidateButNullIsAmbiguous() {
        Transfer transfer = new Transfer(
                new AccountId("source"), new AccountId("destination"), false);
        List<ModelMetadata.HandlerMethod> handlers = ModelMetadata.of(Transfer.class).handlerMethods();
        Entity<Account> source = entity(
                transfer.sourceId(), new Account(transfer.sourceId(), 10));
        Entity<Account> destination = entity(
                transfer.destinationId(), new Account(transfer.destinationId(), 20));
        ModelActionContext begin = context(transfer, handlers, source, destination);

        ModelActionEngine.Evaluation result = engine.evaluate(message(transfer), begin, handlers);

        assertEquals(List.of("account-destination"),
                     result.transitions().stream()
                             .map(ModelActionEngine.Transition::modelId).toList());
        assertEquals(30, ((Account) result.transitions().getFirst().after()).balance());

        Transfer delete = new Transfer(
                transfer.sourceId(), transfer.destinationId(), true);
        ModelActionContext deleteBegin = context(delete, handlers, source, destination);
        assertTrue(assertThrows(
                IllegalStateException.class,
                () -> engine.evaluate(message(delete), deleteBegin, handlers))
                           .getMessage().contains("delete target is ambiguous"));
    }

    @Test
    void rejectsReturnedIdentityOutsideResolvedTargets() {
        ReturnOtherOrder command = new ReturnOtherOrder(new OrderId("1"));
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(ReturnOtherOrder.class).handlerMethods();
        Entity<Order> order = entity(
                command.orderId(), new Order(command.orderId(), "pending"));
        ModelActionContext begin = context(command, handlers, order);

        assertTrue(assertThrows(
                IllegalStateException.class,
                () -> engine.evaluate(message(command), begin, handlers))
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

        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(command),
                (substep, requestedStateIndex) -> {
                    requestedStateIndices.add(requestedStateIndex);
                    return resolveSubstep(substep, 77, stored);
                });

        assertEquals(List.of(-1L, 77L, 77L), requestedStateIndices);
        assertEquals(77, result.readStateIndex());
        assertEquals(
                List.of(firstId, secondId),
                result.substeps().stream()
                        .map(ModelActionEngine.AppliedSubstep::message)
                        .map(DeserializingMessage::getPayload)
                        .map(AdjustInventory.class::cast)
                        .map(AdjustInventory::inventoryId).toList());
        assertEquals(4, ((Inventory) result.finalValues().get(firstId.toString())).available());
        assertEquals(8, ((Inventory) result.finalValues().get(secondId.toString())).available());
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

        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(command),
                (substep, requestedStateIndex) -> resolveSubstep(substep, 79, stored));

        assertEquals(1, result.substeps().size());
        assertSame(command, result.substeps().getFirst().message().getPayload());
        assertEquals(
                List.of(inventoryId.toString(), orderId.toString()),
                result.substeps().getFirst().transitions().stream()
                        .map(ModelActionEngine.Transition::modelId)
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

        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(command),
                (substep, requestedStateIndex) -> resolveSubstep(substep, 88, stored));

        assertEquals(2, result.substeps().size());
        assertEquals(5, ((Inventory) result.transitions().get(0).before()).available());
        assertEquals(6, ((Inventory) result.transitions().get(1).before()).available());
        assertEquals(8, ((Inventory) result.finalValues().get(id.toString())).available());
    }

    @Test
    void failureInALaterSubstepRollsBackTheCompleteActionEvaluation() {
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
                () -> engine.evaluate(
                        message,
                        (substep, requestedStateIndex) ->
                                resolveSubstep(substep, 89, stored)));

        assertEquals(new Inventory(id, 5), storedInventory.get());
        assertTrue(message.getContext(ModelActionContext.class)
                           .orElseThrow().entries().isEmpty());
    }

    @Test
    void sameTypeInterceptorReplacementIsResolvedAgainWithoutRecursiveLoop() {
        InventoryId id = new InventoryId("one");
        NormalizeInventory command = new NormalizeInventory(id, -2, false);
        Map<String, Entity<?>> stored =
                Map.of(id.toString(), entity(id, new Inventory(id, 5)));
        int[] resolutions = {0};

        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(command),
                (substep, requestedStateIndex) -> {
                    resolutions[0]++;
                    return resolveSubstep(substep, 91, stored);
                });

        assertEquals(2, resolutions[0]);
        assertEquals(1, result.substeps().size());
        assertTrue(((NormalizeInventory) result.substeps().getFirst()
                .message().getPayload()).normalized());
        assertEquals(3, ((Inventory) result.finalValues().get(id.toString())).available());
    }

    @Test
    void interceptorMaySuppressAnActionWithoutCreatingASubstep() {
        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(new SuppressInventoryUpdate()),
                (substep, requestedStateIndex) -> resolveSubstep(substep, 12, Map.of()));

        assertEquals(12, result.readStateIndex());
        assertTrue(result.substeps().isEmpty());
        assertTrue(result.finalValues().isEmpty());
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
                () -> engine.evaluate(
                        message(command),
                        (substep, requestedStateIndex) -> resolveSubstep(
                                substep, requestedStateIndex < 0 ? 70 : 71, stored)));

        assertTrue(exception.getMessage().contains(
                "loaded at state index 71 while action is pinned at 70"));
    }

    @Test
    void actionResultRetainsNullDeleteTransitionAndOriginalEvent() {
        OrderId id = new OrderId("delete");
        DeleteOrder command = new DeleteOrder(id);
        Map<String, Entity<?>> stored =
                Map.of(id.toString(), entity(id, new Order(id, "pending")));

        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(command),
                (substep, requestedStateIndex) -> resolveSubstep(substep, -1, stored));

        assertEquals(-1, result.readStateIndex());
        assertSame(command, result.substeps().getFirst().message().getPayload());
        assertEquals(1, result.transitions().size());
        assertTrue(result.finalValues().containsKey(id.toString()));
        assertNull(result.finalValues().get(id.toString()));
    }

    @Test
    void invokesAssertionsInterceptorsAndAppliesOnAModelReceiver() {
        ReceiverOrderId id = new ReceiverOrderId("one");
        List<String> observations = new ArrayList<>();
        RenameReceiverOrder command =
                new RenameReceiverOrder(id, "renamed", observations);
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(ReceiverOrder.class).handlerMethods();
        ModelActionContext begin = context(
                command, handlers, entity(id, new ReceiverOrder(id, "initial")));

        ModelActionEngine.ActionEvaluation result = engine.evaluate(
                message(command),
                (substep, requestedStateIndex) ->
                        new ModelActionEngine.ResolvedSubstep(begin, handlers));

        assertEquals(
                List.of("intercept-initial", "assert-initial", "apply-initial"),
                observations);
        assertEquals(
                new ReceiverOrder(id, "renamed"),
                result.finalValues().get(id.toString()));
    }

    @Test
    void runsBeforeAssertionsByPriorityAndAfterAssertionsAgainstResultingState() {
        OrderId id = new OrderId("assert");
        List<String> observations = new ArrayList<>();
        AssertedOrderUpdate command = new AssertedOrderUpdate(id, observations);
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(AssertedOrderUpdate.class).handlerMethods();
        ModelActionContext begin = context(
                command, handlers, entity(id, new Order(id, "pending")));

        ModelActionEngine.Evaluation result =
                engine.evaluate(message(command), begin, handlers);

        assertEquals(
                List.of("before-high-pending", "before-low-pending", "after-updated"),
                observations);
        assertEquals(new Order(id, "updated"),
                     result.resultingState().resolve(Order.class, null).get());
    }

    @Test
    void failingAfterAssertionExposesNoPartiallyUpdatedContext() {
        OrderId id = new OrderId("reject");
        RejectAfterOrderUpdate command = new RejectAfterOrderUpdate(id);
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(RejectAfterOrderUpdate.class).handlerMethods();
        ModelActionContext begin = context(
                command, handlers, entity(id, new Order(id, "pending")));
        DeserializingMessage message = message(command);

        assertThrows(
                MockFailure.class,
                () -> engine.evaluate(message, begin, handlers));

        assertEquals(new Order(id, "pending"), begin.resolve(Order.class, null).get());
        assertSame(begin, message.getContext(ModelActionContext.class).orElseThrow());
    }

    private static ModelActionEngine.ResolvedSubstep resolveSubstep(
            DeserializingMessage message,
            long stateIndex,
            Map<String, Entity<?>> stored) {
        Object payload = message.getPayload();
        List<ModelMetadata.HandlerMethod> handlers =
                ModelMetadata.of(payload.getClass()).handlerMethods();
        ModelTargetResolver.Resolution resolution =
                ModelTargetResolver.resolve(payload, handlers);
        Map<String, Entity<?>> loaded = new LinkedHashMap<>();
        for (ModelTargetResolver.ResolvedModel target : resolution.models()) {
            Entity<?> entity = stored.get(target.modelId());
            if (entity == null) {
                throw new IllegalArgumentException("Missing fixture model " + target.modelId());
            }
            loaded.put(target.modelId(), entity);
        }
        return new ModelActionEngine.ResolvedSubstep(
                ModelActionContext.create(stateIndex, resolution, loaded), handlers);
    }

    private static ModelActionContext context(
            Object command,
            Collection<ModelMetadata.HandlerMethod> handlers,
            Entity<?>... entities) {
        ModelTargetResolver.Resolution resolution =
                ModelTargetResolver.resolve(command, handlers);
        Map<String, Entity<?>> loaded = new LinkedHashMap<>();
        for (Entity<?> entity : entities) {
            loaded.put(entity.id().toString(), entity);
        }
        return ModelActionContext.create(101, resolution, loaded);
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

    private record CreateOrder(OrderId orderId) {
        @Apply
        Order create() {
            return new Order(orderId, "created");
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

    private static class MockFailure extends RuntimeException {
    }
}
