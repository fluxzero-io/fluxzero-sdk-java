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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.CommitModelAction;
import io.fluxzero.common.api.modeling.ModelActionSubstep;
import io.fluxzero.common.api.modeling.ModelActionTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelEntityParameterResolverTest {

    @Test
    void injectsValueAtExactHistoricalModelActionBoundary() throws Exception {
        AccountId accountId = new AccountId("historical");
        Method handler = Handler.class.getDeclaredMethod(
                "onChanged", ChangeAccount.class, Account.class);
        Parameter parameter = handler.getParameters()[1];

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10),
                        new ChangeAccount(accountId, 20))
                .whenApplying(fluxzero -> {
                    DeserializingMessage firstEvent =
                            fluxzero.eventStore()
                                    .getEvents(accountId)
                                    .findFirst().orElseThrow();
                    return firstEvent.apply(message -> resolve(
                            message, handler, parameter));
                })
                .expectResult(new Account(accountId, 10));
    }

    @Test
    void storedOnlySuffixCannotLeakIntoPublishedEventBoundary()
            throws Exception {
        AccountId accountId =
                new AccountId("store-only-suffix");
        Method handler = Handler.class.getDeclaredMethod(
                "onCreated", CreateAccount.class, Account.class);
        Parameter parameter = handler.getParameters()[1];

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10),
                        new StoreOnlyChangeAccount(accountId, 99))
                .whenApplying(fluxzero -> {
                    DeserializingMessage createEvent =
                            fluxzero.eventStore()
                                    .getEvents(accountId)
                                    .findFirst().orElseThrow();
                    return createEvent.apply(message -> resolve(
                            message, handler, parameter));
                })
                .expectResult(new Account(accountId, 10));
    }

    @Test
    void injectsDocumentLoadedModelAtHistoricalBoundary() throws Exception {
        InventoryId inventoryId =
                new InventoryId("document");
        Method handler = Handler.class.getDeclaredMethod(
                "onInventory", ChangeInventory.class,
                Inventory.class);
        Parameter parameter = handler.getParameters()[1];

        TestFixture.create()
                .givenCommands(
                        new CreateInventory(inventoryId, 5),
                        new ChangeInventory(inventoryId, 95))
                .whenApplying(fluxzero -> {
                    DeserializingMessage firstEvent =
                            fluxzero.eventStore()
                                    .getEvents(inventoryId)
                                    .findFirst().orElseThrow();
                    return firstEvent.apply(message -> resolve(
                            message, handler, parameter));
                })
                .expectResult(new Inventory(inventoryId, 5));
    }

    @Test
    void injectsEmptyEntityForLogicalDeleteAndRejectsBareValue()
            throws Exception {
        AccountId accountId = new AccountId("deleted");
        Method entityHandler = Handler.class.getDeclaredMethod(
                "onDeleted", DeleteAccount.class, Entity.class);
        Method valueHandler = Handler.class.getDeclaredMethod(
                "onDeletedValue", DeleteAccount.class,
                Account.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10),
                        new DeleteAccount(accountId))
                .whenApplying(fluxzero -> {
                    DeserializingMessage deleteEvent =
                            fluxzero.eventStore()
                                    .getEvents(accountId)
                                    .reduce((first, second) -> second)
                                    .orElseThrow();
                    return deleteEvent.apply(message -> {
                        Entity<?> entity = (Entity<?>) resolve(
                                message, entityHandler,
                                entityHandler.getParameters()[1]);
                        Function<DeserializingMessage, Object>
                                valueResolver =
                                new ModelEntityParameterResolver()
                                        .resolveIfPossible(
                                                valueHandler
                                                        .getParameters()[1],
                                                valueHandler
                                                        .getAnnotation(
                                                                HandleEvent.class),
                                                message);
                        return new DeletedResolution(
                                entity, valueResolver);
                    });
                })
                .expectResult(
                        (Predicate<DeletedResolution>) result -> {
                    assertFalse(result.entity().isPresent());
                    assertNull(result.valueResolver());
                    return true;
                });
    }

    @Test
    void associationSelectsMultipleAffectedModelsOfSameType()
            throws Exception {
        AccountId sourceId = new AccountId("source");
        AccountId destinationId =
                new AccountId("destination");
        Method handler = Handler.class.getDeclaredMethod(
                "onTransferred", Transfer.class,
                Account.class, Entity.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(sourceId, 10),
                        new CreateAccount(destinationId, 20),
                        new Transfer(sourceId, destinationId))
                .whenApplying(fluxzero -> {
                    DeserializingMessage transferEvent =
                            fluxzero.eventStore()
                                    .getEvents(sourceId)
                                    .reduce((first, second) -> second)
                                    .orElseThrow();
                    return transferEvent.apply(message -> List.of(
                            resolve(
                                    message, handler,
                                    handler.getParameters()[1]),
                            resolve(
                                    message, handler,
                                    handler.getParameters()[2])));
                })
                .expectResult((Predicate<List<Object>>) values -> {
                    assertEquals(
                            new Account(sourceId, 9),
                            values.getFirst());
                    assertEquals(
                            new Account(destinationId, 21),
                            ((Entity<?>) values.getLast()).get());
                    return true;
                });
    }

    @Test
    void notificationUsesTheSameExactModelBoundary() throws Exception {
        AccountId accountId =
                new AccountId("notification");
        Method handler = Handler.class.getDeclaredMethod(
                "onNotification", CreateAccount.class,
                Account.class);
        Parameter parameter = handler.getParameters()[1];

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10),
                        new ChangeAccount(accountId, 20))
                .whenApplying(fluxzero -> {
                    Message event = fluxzero.eventStore()
                            .getEvents(accountId)
                            .findFirst().orElseThrow()
                            .toMessage();
                    DeserializingMessage notification =
                            new DeserializingMessage(
                                    event,
                                    MessageType.NOTIFICATION,
                                    fluxzero.serializer());
                    return notification.apply(message -> resolve(
                            message, handler, parameter));
                })
                .expectResult(new Account(accountId, 10));
    }

    @Test
    void ordinaryEventWithoutModelBoundaryDoesNotInjectCurrentModel()
            throws Exception {
        AccountId accountId = new AccountId("ordinary");
        Method handler = Handler.class.getDeclaredMethod(
                "onChanged", ChangeAccount.class, Account.class);
        Parameter parameter = handler.getParameters()[1];

        TestFixture.create()
                .givenCommands(new CreateAccount(accountId, 10))
                .whenApplying(fluxzero -> {
                    DeserializingMessage ordinaryEvent =
                            new DeserializingMessage(
                                    new Message(new ChangeAccount(
                                            accountId, 20)),
                                    MessageType.EVENT,
                                    fluxzero.serializer());
                    return ordinaryEvent.apply(message ->
                            new ModelEntityParameterResolver()
                                    .resolveIfPossible(
                                            parameter,
                                            handler.getAnnotation(
                                                    HandleEvent.class),
                                    message));
                })
                .expectResult(result -> result == null);
    }

    @Test
    void injectionUsesTheEventConsumerNamespace() throws Exception {
        AccountId accountId =
                new AccountId("namespaced");
        Method handler = Handler.class.getDeclaredMethod(
                "onCreated", CreateAccount.class,
                Account.class);
        Parameter parameter = handler.getParameters()[1];

        TestFixture.create()
                .given(fluxzero -> {
                    Fluxzero.assertAndApply(
                            new CreateAccount(accountId, 10));
                    fluxzero.client()
                            .forNamespace("customer")
                            .getEventStoreClient()
                            .commitModelAction(
                                    new CommitModelAction(
                                            "customer-create",
                                            -1L, List.of(accountId.toString()),
                                            List.of(ModelActionSubstep.builder()
                                                    .event(new Message(
                                                            new CreateAccount(
                                                                    accountId, 20),
                                                            Metadata.of(
                                                                    ModelEventMetadata.ACTION_ID,
                                                                    "customer-create",
                                                                    ModelEventMetadata.SUBSTEP,
                                                                    0))
                                                            .serialize(
                                                                    fluxzero.serializer()))
                                                    .publishEvent(false)
                                                    .targets(List.of(
                                                            ModelActionTarget.builder()
                                                                    .modelId(accountId.toString())
                                                                    .storeEvent(true)
                                                                    .updateState(true)
                                                                    .relationships(List.of())
                                                                    .build()))
                                                    .build()),
                                            ModelConflictPolicy.ACCEPT,
                                            Guarantee.STORED))
                            .join();
                })
                .whenApplying(fluxzero -> {
                    DeserializingMessage customerEvent =
                            fluxzero.eventStore()
                                    .forNamespace("customer")
                                    .getEvents(accountId)
                                    .findFirst().orElseThrow();
                    customerEvent.putContext(
                            ConsumerConfiguration.class,
                            ConsumerConfiguration.builder()
                                    .name("customer-events")
                                    .namespace("customer")
                                    .build());
                    return customerEvent.apply(message ->
                            resolve(
                                    message, handler,
                                    parameter));
                })
                .expectResult(new Account(accountId, 20));
    }

    private static Object resolve(
            DeserializingMessage message,
            Method handler,
            Parameter parameter) {
        AnnotationType annotationType =
                handler.getAnnotation(HandleEvent.class) != null
                        ? AnnotationType.EVENT
                        : AnnotationType.NOTIFICATION;
        Function<DeserializingMessage, Object> resolver =
                new ModelEntityParameterResolver()
                        .resolveIfPossible(
                                parameter,
                                annotationType == AnnotationType.EVENT
                                        ? handler.getAnnotation(
                                                HandleEvent.class)
                                        : handler.getAnnotation(
                                                HandleNotification.class),
                                message);
        return resolver == null ? null : resolver.apply(message);
    }

    private enum AnnotationType {
        EVENT,
        NOTIFICATION
    }

    private record DeletedResolution(
            Entity<?> entity, Object valueResolver) {
    }

    @Model
    private record Account(
            @EntityId AccountId accountId, int balance) {
    }

    private static final class AccountId extends Id<Account> {
        private AccountId(String id) {
            super(id, "parameter-account-");
        }
    }

    private record CreateAccount(
            AccountId accountId, int balance) {
        @Apply
        Account apply() {
            return new Account(accountId, balance);
        }
    }

    private record ChangeAccount(
            AccountId accountId, int balance) {
        @Apply
        Account apply(Account account) {
            return new Account(accountId, balance);
        }
    }

    private record StoreOnlyChangeAccount(
            AccountId accountId, int balance) {
        @Apply(
                publicationStrategy =
                        EventPublicationStrategy.STORE_ONLY)
        Account apply(Account account) {
            return new Account(accountId, balance);
        }
    }

    private record DeleteAccount(AccountId accountId) {
        @Apply
        Account apply(Account account) {
            return null;
        }
    }

    private record Transfer(
            AccountId sourceId,
            AccountId destinationId) {
        @Apply
        Account debit(
                @Association("sourceId") Account source) {
            return new Account(
                    source.accountId(),
                    source.balance() - 1);
        }

        @Apply
        Account credit(
                @Association("destinationId")
                Account destination) {
            return new Account(
                    destination.accountId(),
                    destination.balance() + 1);
        }
    }

    @Model(eventSourced = false, searchable = true)
    private record Inventory(
            @EntityId InventoryId inventoryId, int quantity) {
    }

    private static final class InventoryId
            extends Id<Inventory> {
        private InventoryId(String id) {
            super(id, "parameter-inventory-");
        }
    }

    private record CreateInventory(
            InventoryId inventoryId, int quantity) {
        @Apply
        Inventory apply() {
            return new Inventory(inventoryId, quantity);
        }
    }

    private record ChangeInventory(
            InventoryId inventoryId, int quantity) {
        @Apply
        Inventory apply(Inventory inventory) {
            return new Inventory(inventoryId, quantity);
        }
    }

    private static class Handler {
        @HandleEvent
        void onChanged(
                ChangeAccount event, Account account) {
        }

        @HandleEvent
        void onCreated(
                CreateAccount event, Account account) {
        }

        @HandleEvent
        void onDeleted(
                DeleteAccount event, Entity<Account> account) {
        }

        @HandleEvent
        void onDeletedValue(
                DeleteAccount event, Account account) {
        }

        @HandleEvent
        void onTransferred(
                Transfer event,
                @Association("sourceId") Account source,
                @Association("destinationId")
                Entity<Account> destination) {
        }

        @HandleEvent
        void onInventory(
                ChangeInventory event, Inventory inventory) {
        }

        @HandleNotification
        void onNotification(
                CreateAccount event, Account account) {
        }
    }
}
