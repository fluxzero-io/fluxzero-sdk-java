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
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleError;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.HandleResult;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleWebResponse;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEntityParameterResolverTest {

    @Test
    void injectsValueAtExactHistoricalModelCommitBoundary() throws Exception {
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
                        Function<Object, Object>
                                valueResolver =
                                new ModelEntityParameterResolver()
                                        .resolveIfPossible(
                                                valueHandler
                                                        .getParameters()[1],
                                                valueHandler
                                                        .getAnnotation(
                                                                HandleEvent.class),
                                                message);
                        IllegalStateException failure =
                                assertThrows(
                                        IllegalStateException.class,
                                        () -> valueResolver
                                                .apply(message));
                        return new DeletedResolution(
                                entity, failure);
                    });
                })
                .expectResult(
                        (Predicate<DeletedResolution>) result -> {
                    assertFalse(result.entity().isPresent());
                    assertTrue(result.failure()
                                       .getMessage()
                                       .contains(
                                               "missing or deleted"));
                    return true;
                });
    }

    @Test
    void injectsCurrentModelIntoCommandHandler()
            throws Exception {
        AccountId accountId =
                new AccountId("command");
        Method handler = Handler.class.getDeclaredMethod(
                "onCommand", InspectAccount.class,
                Account.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10),
                        new ChangeAccount(accountId, 20))
                .whenApplying(fluxzero ->
                                      new DeserializingMessage(
                                              new Message(
                                                      new InspectAccount(
                                                              accountId)),
                                              MessageType.COMMAND,
                                              fluxzero.serializer())
                                              .apply(message ->
                                                             resolve(
                                                                     message,
                                                                     handler,
                                                                     handler.getParameters()[1])))
                .expectResult(
                        new Account(accountId, 20));
    }

    @Test
    void injectsNullForPresentNullableAssociationWithoutTreatingItAsAnAncestor()
            throws Exception {
        Method handler = Handler.class.getDeclaredMethod(
                "onOptional", InspectOptional.class, Account.class);

        TestFixture.create()
                .whenApplying(fluxzero -> {
                    DeserializingMessage message = new DeserializingMessage(
                            new Message(new InspectOptional(null)), MessageType.COMMAND,
                            fluxzero.serializer());
                    return resolve(message, handler, handler.getParameters()[1]);
                })
                .expectResult((Object) null);
    }

    @Test
    void injectsLazyGraphWithCurrentValueAndHistoryIntoCommandHandler()
            throws Exception {
        AccountId accountId = new AccountId("command-graph");
        Method handler = Handler.class.getDeclaredMethod(
                "onGraph", InspectAccount.class, Graph.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10),
                        new ChangeAccount(accountId, 20))
                .whenApplying(fluxzero ->
                        new DeserializingMessage(
                                new Message(new InspectAccount(accountId)),
                                MessageType.COMMAND,
                                fluxzero.serializer())
                                .apply(message -> {
                                    @SuppressWarnings("unchecked")
                                    Graph<Account> graph = (Graph<Account>) resolve(
                                            message, handler, handler.getParameters()[1]);
                                    return new GraphResolution(
                                            graph.get(), graph.previous().get(), graph.stateIndex());
                                }))
                .expectResult((Predicate<GraphResolution>) result -> {
                    assertEquals(new Account(accountId, 20), result.current());
                    assertEquals(new Account(accountId, 10), result.previous());
                    assertTrue(result.stateIndex() >= 1L);
                    return true;
                });
    }

    @Test
    void injectsCurrentModelsIntoEveryNonEventHandlerKind()
            throws Exception {
        AccountId accountId =
                new AccountId("all-message-types");
        List<HandlerInvocation> handlers =
                List.of(
                        new HandlerInvocation(
                                "onQuery",
                                MessageType.QUERY),
                        new HandlerInvocation(
                                "onSchedule",
                                MessageType.SCHEDULE),
                        new HandlerInvocation(
                                "onResult",
                                MessageType.RESULT),
                        new HandlerInvocation(
                                "onError",
                                MessageType.ERROR),
                        new HandlerInvocation(
                                "onMetrics",
                                MessageType.METRICS),
                        new HandlerInvocation(
                                "onDocument",
                                MessageType.DOCUMENT),
                        new HandlerInvocation(
                                "onCustom",
                                MessageType.CUSTOM),
                        new HandlerInvocation(
                                "onWebRequest",
                                MessageType.WEBREQUEST),
                        new HandlerInvocation(
                                "onWebResponse",
                                MessageType.WEBRESPONSE));

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(
                                accountId, 10),
                        new ChangeAccount(
                                accountId, 20))
                .whenApplying(fluxzero ->
                                      handlers.stream()
                                              .map(invocation -> {
                                                  try {
                                                      Method method =
                                                              Handler.class
                                                                      .getDeclaredMethod(
                                                                              invocation
                                                                                      .method(),
                                                                              InspectAccount.class,
                                                                              Account.class);
                                                      DeserializingMessage message =
                                                              new DeserializingMessage(
                                                                      new Message(
                                                                              new InspectAccount(
                                                                                      accountId)),
                                                                      invocation
                                                                              .type(),
                                                                      fluxzero
                                                                              .serializer());
                                                      return message.apply(
                                                              ignored ->
                                                                      resolve(
                                                                              message,
                                                                              method,
                                                                              method.getParameters()[1]));
                                                  } catch (ReflectiveOperationException e) {
                                                      throw new IllegalStateException(
                                                              e);
                                                  }
                                              })
                                              .toList())
                .expectResult(
                        handlers.stream()
                                .map(ignored ->
                                             new Account(
                                                     accountId,
                                                     20))
                                .toList());
    }

    @Test
    void selectedAsyncHandlerReceivesTheModelThroughThePublicPipeline() {
        AccountId accountId =
                new AccountId("async-query");

        TestFixture.create()
                .registerHandlers(
                        new AsyncQueryHandler())
                .givenCommands(
                        new CreateAccount(
                                accountId, 42))
                .whenQuery(
                        new InspectAccount(accountId))
                .expectResult(
                        new Account(accountId, 42));
    }

    @Test
    void injectsTwoUnrelatedDirectModelsInOneHandler()
            throws Exception {
        AccountId accountId =
                new AccountId("unrelated");
        InventoryId inventoryId =
                new InventoryId("unrelated");
        Method handler =
                Handler.class.getDeclaredMethod(
                        "onUnrelated",
                        InspectUnrelated.class,
                        Account.class,
                        Inventory.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 7),
                        new CreateInventory(
                                inventoryId, 11))
                .whenApplying(fluxzero -> {
                    DeserializingMessage message =
                            new DeserializingMessage(
                                    new Message(
                                            new InspectUnrelated(
                                                    accountId,
                                                    inventoryId)),
                                    MessageType.QUERY,
                                    fluxzero.serializer());
                    return message.apply(
                            ignored ->
                                    List.of(
                                            resolve(
                                                    message,
                                                    handler,
                                                    handler.getParameters()[1]),
                                            resolve(
                                                    message,
                                                    handler,
                                                    handler.getParameters()[2])));
                })
                .expectResult(
                        List.of(
                                new Account(accountId, 7),
                                new Inventory(
                                        inventoryId, 11)));
    }

    @Test
    void associationCanResolveModelIdFromMetadata()
            throws Exception {
        AccountId accountId =
                new AccountId("metadata");
        Method handler = Handler.class.getDeclaredMethod(
                "onSelected", InspectSelected.class,
                Account.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(accountId, 10))
                .whenApplying(fluxzero ->
                                      new DeserializingMessage(
                                              new Message(
                                                      new InspectSelected(),
                                                      Metadata.of(
                                                              "selectedId",
                                                              accountId
                                                                      .toString())),
                                              MessageType.COMMAND,
                                              fluxzero.serializer())
                                              .apply(message ->
                                                             resolve(
                                                                     message,
                                                                     handler,
                                                                     handler.getParameters()[1])))
                .expectResult(
                        new Account(accountId, 10));
    }

    @Test
    void associationCanExplicitlyIgnoreAConflictingMetadataId()
            throws Exception {
        AccountId payloadId =
                new AccountId("payload");
        AccountId metadataId =
                new AccountId("metadata-conflict");
        Method handler =
                Handler.class.getDeclaredMethod(
                        "onPayloadSelected",
                        InspectPayloadSelected.class,
                        Account.class);

        TestFixture.create()
                .givenCommands(
                        new CreateAccount(
                                payloadId, 10),
                        new CreateAccount(
                                metadataId, 20))
                .whenApplying(fluxzero ->
                                      new DeserializingMessage(
                                              new Message(
                                                      new InspectPayloadSelected(
                                                              payloadId),
                                                      Metadata.of(
                                                              "selectedId",
                                                              metadataId.toString())),
                                              MessageType.COMMAND,
                                              fluxzero.serializer())
                                              .apply(message ->
                                                             resolve(
                                                                     message,
                                                                     handler,
                                                                     handler.getParameters()[1])))
                .expectResult(
                        new Account(payloadId, 10));
    }

    @Test
    void injectsParentAndGrandparentFromAddressedChild()
            throws Exception {
        CompanyId companyId =
                new CompanyId("company");
        DepartmentId departmentId =
                new DepartmentId("department");
        WorkerId workerId =
                new WorkerId("worker");
        Method handler = Handler.class.getDeclaredMethod(
                "onWorker", InspectWorker.class,
                Worker.class, Department.class,
                Company.class);

        TestFixture.create()
                .givenCommands(
                        new CreateCompany(companyId),
                        new CreateDepartment(
                                departmentId, companyId),
                        new CreateWorker(
                                workerId, departmentId))
                .whenApplying(fluxzero -> {
                    DeserializingMessage message =
                            new DeserializingMessage(
                                    new Message(
                                            new InspectWorker(
                                                    workerId)),
                                    MessageType.COMMAND,
                                    fluxzero.serializer());
                    return message.apply(ignored ->
                                                 List.of(
                                                         resolve(
                                                                 message,
                                                                 handler,
                                                                 handler.getParameters()[1]),
                                                         resolve(
                                                                 message,
                                                                 handler,
                                                                 handler.getParameters()[2]),
                                                         resolve(
                                                                 message,
                                                                 handler,
                                                                 handler.getParameters()[3])));
                })
                .expectResult(
                        List.of(
                                new Worker(
                                        workerId,
                                        departmentId),
                                new Department(
                                        departmentId,
                                        companyId),
                                new Company(companyId)));
    }

    @Test
    void historicalEventInjectsTheAncestorsBeforeALaterMove()
            throws Exception {
        CompanyId oldCompanyId =
                new CompanyId("historical-old");
        CompanyId newCompanyId =
                new CompanyId("historical-new");
        DepartmentId oldDepartmentId =
                new DepartmentId("historical-old");
        DepartmentId newDepartmentId =
                new DepartmentId("historical-new");
        WorkerId workerId =
                new WorkerId("historical-move");
        Method handler =
                Handler.class.getDeclaredMethod(
                        "onWorkerCreated",
                        CreateWorker.class,
                        Worker.class,
                        Department.class,
                        Company.class);

        TestFixture.create()
                .givenCommands(
                        new CreateCompany(oldCompanyId),
                        new CreateCompany(newCompanyId),
                        new CreateDepartment(
                                oldDepartmentId,
                                oldCompanyId),
                        new CreateDepartment(
                                newDepartmentId,
                                newCompanyId),
                        new CreateWorker(
                                workerId,
                                oldDepartmentId),
                        new MoveWorker(
                                workerId,
                                newDepartmentId))
                .whenApplying(fluxzero -> {
                    DeserializingMessage created =
                            fluxzero.eventStore()
                                    .getEvents(workerId)
                                    .findFirst()
                                    .orElseThrow();
                    return created.apply(
                            message ->
                                    List.of(
                                            resolve(
                                                    message,
                                                    handler,
                                                    handler.getParameters()[1]),
                                            resolve(
                                                    message,
                                                    handler,
                                                    handler.getParameters()[2]),
                                            resolve(
                                                    message,
                                                    handler,
                                                    handler.getParameters()[3])));
                })
                .expectResult(
                        List.of(
                                new Worker(
                                        workerId,
                                        oldDepartmentId),
                                new Department(
                                        oldDepartmentId,
                                        oldCompanyId),
                                new Company(
                                        oldCompanyId)));
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
                    DeserializingMessage event = fluxzero.eventStore()
                            .getEvents(accountId)
                            .findFirst().orElseThrow();
                    DeserializingMessage notification =
                            new DeserializingMessage(
                                    event.getSerializedObject(),
                                    ignored -> event.getPayload(),
                                    MessageType.NOTIFICATION, null,
                                    fluxzero.serializer());
                    return notification.apply(message -> resolve(
                            message, handler, parameter));
                })
                .expectResult(new Account(accountId, 10));
    }

    @Test
    void injectsAncestorWhenOnlyTheDescendantIdAndAncestorParameterAreDeclared()
            throws Exception {
        CompanyId companyId =
                new CompanyId("ancestor-only");
        DepartmentId departmentId =
                new DepartmentId("ancestor-only");
        WorkerId workerId =
                new WorkerId("ancestor-only");
        Method handler = Handler.class.getDeclaredMethod(
                "onWorkerAncestor",
                InspectWorker.class,
                Company.class);

        TestFixture.create()
                .givenCommands(
                        new CreateCompany(companyId),
                        new CreateDepartment(
                                departmentId, companyId),
                        new CreateWorker(
                                workerId, departmentId))
                .whenApplying(fluxzero -> {
                    DeserializingMessage query =
                            new DeserializingMessage(
                                    new Message(
                                            new InspectWorker(
                                                    workerId)),
                                    MessageType.QUERY,
                                    fluxzero.serializer());
                    return query.apply(message ->
                                               resolve(
                                                       message,
                                                       handler,
                                                       handler.getParameters()[1]));
                })
                .expectResult(new Company(companyId));
    }

    @Test
    void ancestorInjectionUsesARelationshipMovedEarlierInTheMessageBatch()
            throws Exception {
        CompanyId firstCompanyId =
                new CompanyId("batch-move-first");
        CompanyId secondCompanyId =
                new CompanyId("batch-move-second");
        DepartmentId departmentId =
                new DepartmentId("batch-move");
        WorkerId workerId =
                new WorkerId("batch-move");
        Department before =
                new Department(departmentId, firstCompanyId);
        Department after =
                new Department(departmentId, secondCompanyId);
        Method handler = Handler.class.getDeclaredMethod(
                "onWorkerAncestor",
                InspectWorker.class,
                Company.class);

        TestFixture.create()
                .givenCommands(
                        new CreateCompany(firstCompanyId),
                        new CreateCompany(secondCompanyId),
                        new CreateDepartment(
                                departmentId, firstCompanyId),
                        new CreateWorker(
                                workerId, departmentId))
                .whenApplying(fluxzero -> {
                    AtomicReference<Object> result =
                            new AtomicReference<>();
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    new DeserializingMessage(
                                            new Message("move"),
                                            MessageType.COMMAND,
                                            fluxzero.serializer()),
                                    new DeserializingMessage(
                                            new Message(
                                                    new InspectWorker(
                                                            workerId)),
                                            MessageType.QUERY,
                                            fluxzero.serializer())),
                            current -> {
                                if (DeserializingMessage
                                        .getMessageBatchIndex() == 0) {
                                    MessageBatchModelView.stage(
                                            null,
                                            new ModelCommitEngine.CommitEvaluation(
                                                    -1L,
                                                    List.of(
                                                            departmentId
                                                                    .toString()),
                                                    java.util.Map.of(
                                                            departmentId
                                                                    .toString(),
                                                            Department.class),
                                                    List.of(new ModelCommitEngine.AppliedSubstep(
                                                            current,
                                                            List.of(new ModelCommitEngine.Transition(
                                                                    departmentId.toString(),
                                                                    Department.class,
                                                                    0L, before,
                                                                    after, null)))),
                                                    java.util.Map.of(
                                                            departmentId
                                                                    .toString(),
                                                            after)),
                                            null);
                                    return;
                                }
                                result.set(resolve(
                                        current, handler,
                                        handler.getParameters()[1]));
                            });
                    return result.get();
                })
                .expectResult(new Company(secondCompanyId));
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
                            .commitModels(
                                    new CommitModels(
                                            "customer-create",
                                            -1L, List.of(accountId.toString()),
                                            List.of(ModelCommitStep.builder()
                                                    .event(new Message(
                                                            new CreateAccount(
                                                                    accountId, 20))
                                                            .serialize(
                                                                    fluxzero.serializer()))
                                                    .publishEvent(true)
                                                    .targets(List.of(
                                                            ModelCommitTarget.builder()
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

    @Test
    void staticModelLoadUsesTheCurrentConsumerNamespace() {
        AccountId accountId = new AccountId("static-namespaced");

        TestFixture.create()
                .given(fluxzero -> {
                    Fluxzero.assertAndApply(
                            new CreateAccount(accountId, 10));
                    fluxzero.client()
                            .forNamespace("customer")
                            .getEventStoreClient()
                            .commitModels(
                                    new CommitModels(
                                            "customer-static-create",
                                            -1L, List.of(accountId.toString()),
                                            List.of(ModelCommitStep.builder()
                                                    .event(new Message(
                                                            new CreateAccount(
                                                                    accountId, 20))
                                                            .serialize(
                                                                    fluxzero.serializer()))
                                                    .publishEvent(true)
                                                    .targets(List.of(
                                                            ModelCommitTarget.builder()
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
                                    .name("customer-static-events")
                                    .namespace("customer")
                                    .build());
                    return customerEvent.apply(message ->
                            Fluxzero.loadModel(accountId).get());
                })
                .expectResult(new Account(accountId, 20));
    }

    private static Object resolve(
            DeserializingMessage message,
            Method handler,
            Parameter parameter) {
        Function<Object, Object> resolver =
                new ModelEntityParameterResolver()
                        .resolveIfPossible(
                                parameter,
                                handler.getDeclaredAnnotations()[0],
                                message);
        return resolver == null ? null : resolver.apply(message);
    }

    private record DeletedResolution(
            Entity<?> entity,
            IllegalStateException failure) {
    }

    private record HandlerInvocation(
            String method, MessageType type) {
    }

    private record GraphResolution(
            Account current, Account previous, long stateIndex) {
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

    private record InspectAccount(AccountId accountId) {
    }

    private record InspectUnrelated(
            AccountId accountId,
            InventoryId inventoryId) {
    }

    private record InspectSelected() {
    }

    private record InspectPayloadSelected(
            AccountId selectedId) {
    }

    private record InspectOptional(AccountId selectedId) {
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

    @Model
    private record Company(
            @EntityId CompanyId companyId) {
    }

    private static final class CompanyId
            extends Id<Company> {
        private CompanyId(String id) {
            super(id, "parameter-company-");
        }
    }

    @Model
    private record Department(
            @EntityId DepartmentId departmentId,
            @ParentId(path = "departments")
            CompanyId companyId) {
    }

    private static final class DepartmentId
            extends Id<Department> {
        private DepartmentId(String id) {
            super(id, "parameter-department-");
        }
    }

    @Model
    private record Worker(
            @EntityId WorkerId workerId,
            @ParentId(path = "workers")
            DepartmentId departmentId) {
    }

    private static final class WorkerId
            extends Id<Worker> {
        private WorkerId(String id) {
            super(id, "parameter-worker-");
        }
    }

    private record CreateCompany(
            CompanyId companyId) {
        @Apply
        Company apply() {
            return new Company(companyId);
        }
    }

    private record CreateDepartment(
            DepartmentId departmentId,
            CompanyId companyId) {
        @Apply
        Department apply() {
            return new Department(
                    departmentId, companyId);
        }
    }

    private record CreateWorker(
            WorkerId workerId,
            DepartmentId departmentId) {
        @Apply
        Worker apply() {
            return new Worker(
                    workerId, departmentId);
        }
    }

    private record MoveWorker(
            WorkerId workerId,
            DepartmentId departmentId) {
        @Apply
        Worker apply(Worker worker) {
            return new Worker(
                    worker.workerId(),
                    departmentId);
        }
    }

    private record InspectWorker(WorkerId workerId) {
    }

    private static class AsyncQueryHandler {
        @HandleQuery
        CompletableFuture<Account> on(
                InspectAccount query,
                Account account) {
            return CompletableFuture.completedFuture(
                    account);
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

        @HandleCommand
        void onCommand(
                InspectAccount command,
                Account account) {
        }

        @HandleCommand
        void onGraph(
                InspectAccount command,
                Graph<Account> account) {
        }

        @HandleQuery
        void onQuery(
                InspectAccount query,
                Account account) {
        }

        @HandleSchedule
        void onSchedule(
                InspectAccount schedule,
                Account account) {
        }

        @HandleResult
        void onResult(
                InspectAccount result,
                Account account) {
        }

        @HandleError
        void onError(
                InspectAccount error,
                Account account) {
        }

        @HandleMetrics
        void onMetrics(
                InspectAccount metric,
                Account account) {
        }

        @HandleDocument
        void onDocument(
                InspectAccount document,
                Account account) {
        }

        @HandleCustom("models")
        void onCustom(
                InspectAccount custom,
                Account account) {
        }

        @HandleGet
        void onWebRequest(
                InspectAccount request,
                Account account) {
        }

        @HandleWebResponse
        void onWebResponse(
                InspectAccount response,
                Account account) {
        }

        @HandleQuery
        void onUnrelated(
                InspectUnrelated query,
                Account account,
                Inventory inventory) {
        }

        @HandleCommand
        void onSelected(
                InspectSelected command,
                @Association("selectedId")
                Account account) {
        }

        @HandleCommand
        void onPayloadSelected(
                InspectPayloadSelected command,
                @Association(
                        value = "selectedId",
                        excludeMetadata = true)
                Account account) {
        }

        @HandleCommand
        void onOptional(
                InspectOptional command,
                @Association("selectedId") @Nullable Account account) {
        }

        @HandleCommand
        void onWorker(
                InspectWorker command,
                Worker worker,
                Department department,
                Company company) {
        }

        @HandleQuery
        void onWorkerAncestor(
                InspectWorker query,
                Company company) {
        }

        @HandleEvent
        void onWorkerCreated(
                CreateWorker event,
                Worker worker,
                Department department,
                Company company) {
        }
    }
}
