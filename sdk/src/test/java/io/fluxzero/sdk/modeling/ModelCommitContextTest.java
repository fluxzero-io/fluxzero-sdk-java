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
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommitContextTest {

    private final DefaultEntityHelper entityHelper =
            new DefaultEntityHelper(List.of(new ModelEntityParameterResolver()), true);

    @Test
    void injectsCommitModelsIntoAssertionsInterceptorsAndApplies() {
        List<String> invocations = new ArrayList<>();
        ReserveInventory command = new ReserveInventory(
                new OrderId("1"), new InventoryId("2"), invocations);
        Entity<Order> order = entity(command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));
        DeserializingMessage message = commitMessage(command, 91, order, inventory);

        message.apply(ignored -> {
            entityHelper.assertLegal(message, order);
            return null;
        });
        List<?> intercepted = message.apply(ignored -> entityHelper.intercept(message, order).toList());
        Order result = message.apply(ignored -> {
            HandlerInvoker apply = entityHelper.applyInvoker(message, order).orElseThrow();
            return (Order) apply.invoke();
        });

        assertEquals(List.of("assert:5", "intercept:pending", "apply:5"), invocations);
        assertEquals(1, intercepted.size());
        assertEquals(new Order(command.orderId(), "reserved"), result);
        assertEquals(91, message.getContext(ModelCommitContext.class).orElseThrow().readStateIndex());
    }

    @Test
    void injectsQualifiedSameTypeModelsAndEntityWrappers() {
        Transfer command = new Transfer(new AccountId("source"), new AccountId("destination"));
        Entity<Account> source = entity(command.sourceId(), new Account(command.sourceId(), 10));
        Entity<Account> destination = entity(
                command.destinationId(), new Account(command.destinationId(), 20));
        DeserializingMessage message = commitMessage(command, 92, source, destination);

        Account result = message.apply(ignored ->
                (Account) entityHelper.applyInvoker(message, source).orElseThrow().invoke());

        assertEquals(new Account(command.destinationId(), 30), result);
        assertSame(source, command.sourceEntity);
        assertSame(destination, command.destinationEntity);
    }

    @Test
    void injectsEmptyEntityWrapperButNotAbsentNonNullableValue() {
        CreateDependent command = new CreateDependent(
                new OrderId("new"), new InventoryId("missing"));
        Entity<Order> order = entity(command.orderId(), null);
        Entity<Inventory> inventory = entity(command.inventoryId(), null);
        DeserializingMessage message = commitMessage(command, 93, order, inventory);

        Order result = message.apply(ignored ->
                (Order) entityHelper.applyInvoker(message, order).orElseThrow().invoke());

        assertEquals(new Order(command.orderId(), "created"), result);
        assertSame(inventory, command.injected);

        RequiresInventoryValue invalid = new RequiresInventoryValue(
                command.orderId(), command.inventoryId());
        DeserializingMessage invalidMessage = commitMessage(invalid, 94, order, inventory);
        boolean invokerMissing = invalidMessage.apply(
                ignored -> entityHelper.applyInvoker(invalidMessage, order).isEmpty());
        assertTrue(invokerMissing);
    }

    @Test
    void rejectsIncompleteUnrelatedAndIncompatibleLoads() {
        ReserveInventory command = new ReserveInventory(
                new OrderId("1"), new InventoryId("2"), new ArrayList<>());
        ModelTargetResolver.Resolution resolution = resolution(command);
        Entity<Order> order = entity(command.orderId(), new Order(command.orderId(), "pending"));
        Entity<Inventory> inventory = entity(
                command.inventoryId(), new Inventory(command.inventoryId(), 5));

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitContext.create(1, resolution, Map.of(order.id().toString(), order)))
                           .getMessage().contains("Missing loaded model"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitContext.create(
                        1, resolution, Map.of(
                                order.id().toString(), order,
                                inventory.id().toString(), inventory,
                                "unrelated", entity(new OrderId("unrelated"),
                                                    new Order(new OrderId("unrelated"), "x")))))
                           .getMessage().contains("unrelated"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> ModelCommitContext.create(
                        1, resolution, Map.of(
                                order.id().toString(), entity(command.orderId(), "wrong"),
                                inventory.id().toString(), inventory)))
                           .getMessage().contains("incompatible type"));
    }

    private DeserializingMessage commitMessage(
            Object command, long stateIndex, Entity<?>... loadedModels) {
        ModelTargetResolver.Resolution resolution = resolution(command);
        Map<String, Entity<?>> models = java.util.Arrays.stream(loadedModels)
                .collect(java.util.stream.Collectors.toMap(entity -> entity.id().toString(), entity -> entity));
        ModelCommitContext context = ModelCommitContext.create(stateIndex, resolution, models);
        return context.attachTo(new DeserializingMessage(
                new Message(command), MessageType.EVENT, null));
    }

    private static ModelTargetResolver.Resolution resolution(Object command) {
        return ModelTargetResolver.compile(
                        command.getClass(),
                        ModelMetadata.of(command.getClass()).handlerMethods())
                .resolve(command);
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
    private record Inventory(@EntityId InventoryId inventoryId, int available) {
    }

    private static class InventoryId extends Id<Inventory> {
        private InventoryId(String id) {
            super(id, "inventory-");
        }
    }

    private record ReserveInventory(
            OrderId orderId, InventoryId inventoryId, List<String> invocations) {

        @AssertLegal
        void assertStock(Order order, Entity<Inventory> inventory) {
            invocations.add("assert:" + inventory.get().available());
        }

        @InterceptApply
        Object intercept(Order order, Inventory inventory) {
            invocations.add("intercept:" + order.status());
            return this;
        }

        @Apply
        Order apply(Order order, Inventory inventory) {
            invocations.add("apply:" + inventory.available());
            return new Order(order.orderId(), "reserved");
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

    private static class Transfer {
        private final AccountId sourceId;
        private final AccountId destinationId;
        private Entity<Account> sourceEntity;
        private Entity<Account> destinationEntity;

        private Transfer(AccountId sourceId, AccountId destinationId) {
            this.sourceId = sourceId;
            this.destinationId = destinationId;
        }

        AccountId sourceId() {
            return sourceId;
        }

        AccountId destinationId() {
            return destinationId;
        }

        @Apply
        Account apply(
                @io.fluxzero.sdk.tracking.handling.Association("sourceId") Entity<Account> source,
                @io.fluxzero.sdk.tracking.handling.Association("destinationId") Entity<Account> destination) {
            sourceEntity = source;
            destinationEntity = destination;
            return new Account(destination.get().accountId(),
                               source.get().balance() + destination.get().balance());
        }
    }

    private static class CreateDependent {
        private final OrderId orderId;
        private final InventoryId inventoryId;
        private Entity<Inventory> injected;

        private CreateDependent(OrderId orderId, InventoryId inventoryId) {
            this.orderId = orderId;
            this.inventoryId = inventoryId;
        }

        OrderId orderId() {
            return orderId;
        }

        InventoryId inventoryId() {
            return inventoryId;
        }

        @Apply
        Order apply(Entity<Inventory> inventory) {
            injected = inventory;
            return new Order(orderId, "created");
        }
    }

    private record RequiresInventoryValue(OrderId orderId, InventoryId inventoryId) {
        @Apply
        Order apply(Inventory inventory) {
            return new Order(orderId, inventory.toString());
        }
    }
}
