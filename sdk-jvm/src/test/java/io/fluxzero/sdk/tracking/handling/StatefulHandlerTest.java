/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Entry;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EntityParameterResolver;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.ImmutableEntity;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.registry.JvmCompatibilityMetadataMode;
import io.fluxzero.sdk.test.TestFixture;
import lombok.Builder;
import lombok.With;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatefulHandlerTest {

    @Nested
    class StaticTests {
        private final TestFixture testFixture = TestFixture.createJvmCompatibility(StaticHandler.class);

        @Test
        void handlerIsCreated() {
            testFixture.whenEvent(new SomeEvent("foo")).expectCommands(1);
        }

        @Test
        void eventIsExcluded() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new ExcludedEvent("foo"))
                    .expectNoCommands()
                    .expectNoErrors();
        }

        @Test
        void handlerIsUpdated() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new SomeEvent("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsDeleted() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new DeleteHandler("foo"))
                    .expectOnlyCommands(2)
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).fetchAll())
                    .expectResult(List::isEmpty);
        }

        @Test
        void handlerIsDeletedByList() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new DeleteHandlerByList("foo"))
                    .expectOnlyCommands(2)
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).fetchAll())
                    .expectResult(List::isEmpty);
        }

        @Test
        void handlerIsDeleted_secondDelete() {
            testFixture.givenEvents(new SomeEvent("foo"), new DeleteHandler("foo"))
                    .whenEvent(new DeleteHandler("foo"))
                    .expectNoCommands()
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).fetchAll())
                    .expectResult(List::isEmpty);
        }

        @Test
        void handlerIsDeleted_createdAgain() {
            testFixture.givenEvents(new SomeEvent("foo"), new DeleteHandler("foo"))
                    .whenEvent(new SomeEvent("foo"))
                    .expectOnlyCommands(1)
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).stream().findFirst().orElse(null))
                    .expectResult(Objects::nonNull);
        }

        @Test
        void handlerIsReplaced() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new ReplaceHandler("foo", "bar"))
                    .expectOnlyCommands(2, 1)
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).fetchAll(StaticHandler.class))
                    .expectResult(r -> r.size() == 1 && r.getFirst().someId().equals("bar"));
        }

        @Test
        void handlerIsReplacedByList() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new ReplaceHandlerByList("foo", "bar"))
                    .expectOnlyCommands(2, 1)
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).fetchAll(StaticHandler.class))
                    .expectResult(r -> r.size() == 1 && r.getFirst().someId().equals("bar"));
        }

        @Test
        void handlerIsCopied() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new DuplicationEvent("foo", "fooCopy"))
                    .expectOnlyCommands(2, 1)
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StaticHandler.class).fetchAll().size())
                    .expectResult(2);
        }

        @Test
        void handlerAssociationViaMetadata() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new Message("whatever", Metadata.of("someId", "foo")))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerAssociationViaIgnoredMetadata() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new Message(123, Metadata.of("otherId", "foo")))
                    .expectNoCommands().expectNoErrors();
        }

        @Test
        void handlerIsNotUpdatedIfNoMatch() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new SomeEvent("other"))
                    .expectOnlyCommands(1);
        }

        @Test
        void handlerIsUpdated_alias() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new AliasEvent(new AliasId("foo")))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsNotUpdated_wrongAlias() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new AliasEvent(new AliasId("other")))
                    .expectNoCommands();
        }

        @Test
        void handlerIsUpdated_associationOnMethod() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new CustomEvent("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnParameter() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new ParameterAssociationEvent("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsNotUpdated_associationOnParameter_wrongValue() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new ParameterAssociationEvent("bar"))
                    .expectNoCommands()
                    .expectNoErrors();
        }

        @Test
        void handlerIsUpdated_associationOnMethod_usesRoutingKeyIfValueMissing() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new CustomEvent("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnMethod_usesMessageRoutingKeyIfMethodRoutingKeyMissing() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new RoutingKeyEvent("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnMethod_rightPath() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new CustomRightPathEvent("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnMethod_wrongPath() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new CustomWrongPathEvent("foo"))
                    .expectNoCommands()
                    .expectNoErrors();
        }

        @Test
        void handlerIsUpdated_associationOnField_rightPath() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new EventWithRightPath("foo"))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnField_wrongPath() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new EventWithWrongPath("foo"))
                    .expectNoCommands()
                    .expectNoErrors();
        }

        @Test
        void handlerIsUpdated_associationOnCollectionField() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new EventWithPropertyList(List.of("bar", "foo")))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnNestedCollectionField() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new EventWithNestedPropertyList(new NestedPropertyList(List.of("bar", "foo"))))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_associationOnMapCollectionField() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new EventWithMapPropertyList(Map.of("nested", List.of("bar", "foo"))))
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsUpdated_alwaysAssociate() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new AlwaysAssociate())
                    .expectOnlyCommands(2);
        }

        @Test
        void handlerIsInvoked_alwaysAssociateStatic() {
            testFixture.givenEvents(new SomeEvent("foo"), new SomeEvent("bar"))
                    .whenEvent(new AlwaysAssociateStatic())
                    .expectOnlyCommands("once");
        }

        @Stateful
        @Builder(toBuilder = true)
        record StaticHandler(@EntityId @Association(value = {"someId", "aliasId"}, excludedClasses = ExcludedEvent.class) String someId,
                             @Association(excludeMetadata = true) String otherId,
                             int eventCount) {

            @HandleEvent
            static StaticHandler create(SomeEvent event) {
                Fluxzero.sendAndForgetCommand(1);
                return StaticHandler.builder().someId(event.someId).otherId(event.someId).eventCount(1).build();
            }

            @HandleEvent
            StaticHandler handle(SomeEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            StaticHandler handle(DeleteHandler event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return null;
            }

            @HandleEvent
            List<StaticHandler> handle(DeleteHandlerByList event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return List.of();
            }

            @HandleEvent
            StaticHandler handle(ReplaceHandler event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return create(new SomeEvent(event.newId()));
            }

            @HandleEvent
            List<StaticHandler> handle(ReplaceHandlerByList event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return List.of(create(new SomeEvent(event.newId())));
            }

            @HandleEvent
            List<StaticHandler> copy(DuplicationEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                Fluxzero.sendAndForgetCommand(1);
                return List.of(toBuilder().eventCount(eventCount + 1).build(),
                               StaticHandler.builder().someId(event.copyId).otherId(event.copyId).eventCount(1)
                                       .build());
            }

            @HandleEvent
            StaticHandler handle(String ignored) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            StaticHandler handle(Integer ignored) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            StaticHandler exclude(ExcludedEvent event) {
                throw new UnsupportedOperationException();
            }

            @HandleEvent
            StaticHandler handle(AliasEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association(always = true)
            StaticHandler handle(AlwaysAssociate event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association(always = true)
            static void handle(AlwaysAssociateStatic event) {
                Fluxzero.sendAndForgetCommand("once");
            }

            @HandleEvent
            @Association("customId")
            StaticHandler handle(CustomEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            StaticHandler handle(@Association("customId") ParameterAssociationEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association
            @RoutingKey("customId")
            StaticHandler routeViaRoutingKey(CustomEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association
            StaticHandler routeViaMessageRoutingKey(RoutingKeyEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association(value = "customId", path = "someId")
            StaticHandler handle(CustomRightPathEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association(value = "customId", path = "unknown")
            StaticHandler handle(CustomWrongPathEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            StaticHandler handle(EventWithRightPath event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            StaticHandler handle(EventWithWrongPath event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association("someIds")
            StaticHandler handle(EventWithPropertyList event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association("someIds/nested")
            StaticHandler handle(EventWithNestedPropertyList event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            @Association("someIds/nested")
            StaticHandler handle(EventWithMapPropertyList event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }
    }

    @Nested
    class ConstructorTests {
        private final TestFixture testFixture = TestFixture.createJvmCompatibility(ConstructorHandler.class);

        @Test
        void handlerIsCreated() {
            testFixture.whenEvent(new SomeEvent("foo"))
                    .expectCommands(1)
                    .expectTrue(
                            fc -> fc.documentStore().fetchDocument("foo", ConstructorHandler.class).isPresent());
        }

        @Test
        void handlerIsUpdated() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new SomeEvent("foo"))
                    .expectCommands(2);
        }

        @Test
        void handlerIsUpdated_async() {
            TestFixture.createAsyncJvmCompatibility(ConstructorHandler.class).givenEvents(new SomeEvent("foo"))
                    .whenEvent(new SomeEvent("foo"))
                    .expectCommands(2);
        }

        @Stateful
        @Builder(toBuilder = true)
        record ConstructorHandler(@EntityId @Association String someId,
                                  int eventCount) {

            @HandleEvent
            ConstructorHandler(SomeEvent event) {
                this(event.someId(), 1);
                Fluxzero.sendAndForgetCommand(eventCount);
            }

            @HandleEvent
            ConstructorHandler handle(SomeEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }
    }

    @Nested
    class EntityIdAssociationTests {
        private final TestFixture testFixture = TestFixture.createJvmCompatibility(EntityIdAssociationHandler.class);

        @Test
        void handlerIsUpdatedViaEntityIdAssociation() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new SomeEvent("foo"))
                    .expectCommands(2);
        }

        @Test
        void handlerAssociationViaEntityIdMetadata() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new Message("whatever", Metadata.of("someId", "foo")))
                    .expectCommands(2);
        }

        @Stateful
        @Builder(toBuilder = true)
        record EntityIdAssociationHandler(@EntityId String someId,
                                          int eventCount) {

            @HandleEvent
            EntityIdAssociationHandler(SomeEvent event) {
                this(event.someId(), 1);
                Fluxzero.sendAndForgetCommand(eventCount);
            }

            @HandleEvent
            EntityIdAssociationHandler handle(SomeEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            EntityIdAssociationHandler handle(String ignored) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }
    }

    @Nested
    class CustomAssociationProperty {
        private final TestFixture testFixture = TestFixture.createJvmCompatibility(SomeHandler.class);

        @Test
        void handlerIsCreated() {
            testFixture.whenEvent(new SomeEvent("foo")).expectCommands(1);
        }

        @Test
        void handlerIsUpdated() {
            testFixture.givenEvents(new SomeEvent("foo"))
                    .whenEvent(new SomeEvent("foo"))
                    .expectCommands(2);
        }

        @Stateful
        @Builder(toBuilder = true)
        record SomeHandler(@EntityId @Association("someId") String id,
                           int eventCount) {

            @HandleEvent
            SomeHandler(SomeEvent event) {
                this(event.someId(), 1);
                Fluxzero.sendAndForgetCommand(eventCount);
            }

            @HandleEvent
            SomeHandler handle(SomeEvent event) {
                Fluxzero.sendAndForgetCommand(eventCount + 1);
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }
    }

    @Nested
    class MappingTests {
        @Stateful
        record MappingHandler(String someId) {
            @HandleEvent
            static MappingHandler handle(String event) {
                return new MappingHandler(event);
            }
        }

        @Test
        void mappingTest() {
            TestFixture.createJvmCompatibility(MappingHandler.class).whenEvent("foo")
                    .expectTrue(fc -> Fluxzero.search(MappingHandler.class).fetchAll().size() == 1);
        }
    }

    @Nested
    class ValidityPeriodTests {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(10);

        @Stateful(timestampPath = "start", endPath = "end")
        record LimitedValidity(@EntityId String id, Instant start, Instant end) {
            @HandleEvent
            static LimitedValidity handle(LimitedValidity event) {
                return event;
            }
        }

        TestFixture fixture = TestFixture.createJvmCompatibility(LimitedValidity.class).givenEvents(
                        new LimitedValidity("no-end", start, null),
                        new LimitedValidity("no-start", null, end));


        record Query(Instant start, Instant end) implements Request<List<LimitedValidity>> {
            @HandleQuery
            List<LimitedValidity> handle() {
                return Fluxzero.search(LimitedValidity.class).inPeriod(start, end).fetchAll();
            }
        }

        @Test
        void willFindDocumentWithoutStartDateWhenSearchingBefore() {
            fixture.whenQuery(new Query(start.minusSeconds(10), start.minusSeconds(5)))
                    .mapResult(r -> r.getFirst().id())
                    .expectResult("no-start");
        }

        @Test
        void willFindDocumentWithoutEndDateWhenSearchingAfter() {
            fixture.whenQuery(new Query(end.plusSeconds(5), end.plusSeconds(10)))
                    .mapResult(r -> r.getFirst().id())
                    .expectResult("no-end");
        }
    }

    @Nested
    class TriggerAssociationTests {
        private final TestFixture testFixture = TestFixture.createAsyncJvmCompatibility(
                TriggerAssociationHandler.class, new TriggerAssociationCommandHandler());

        @Test
        void resultHandlerIsMatchedViaTriggerAssociation() {
            testFixture.givenStateful(new TriggerAssociationHandler("foo", 0))
                    .whenCommand(new SendOrder("foo"))
                    .expectOnlyCommands("result:1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(TriggerAssociationHandler.class)
                            .<TriggerAssociationHandler>fetchFirst())
                    .expectResult(r -> r.map(TriggerAssociationHandler::retryCount).orElse(0) == 1);
        }

        @Test
        void resultHandlerIsNotMatchedViaTriggerAssociation_wrongValue() {
            testFixture.givenStateful(new TriggerAssociationHandler("foo", 0))
                    .whenCommand(new SendOrder("bar"))
                    .expectResult("bar")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(TriggerAssociationHandler.class)
                            .<TriggerAssociationHandler>fetchFirst())
                    .expectResult(r -> r.map(TriggerAssociationHandler::retryCount).orElse(0) == 0);
        }

        @Stateful
        @Builder(toBuilder = true)
        record TriggerAssociationHandler(@EntityId @Association String orderId,
                                         int retryCount) {

            @HandleResult
            TriggerAssociationHandler retry(String result,
                                            @Trigger @Association("orderId") SendOrder command) {
                Fluxzero.sendAndForgetCommand("result:" + (retryCount + 1));
                return toBuilder().retryCount(retryCount + 1).build();
            }
        }

        static class TriggerAssociationCommandHandler {
            @HandleCommand
            String handle(SendOrder command) {
                return command.orderId();
            }
        }
    }

    @Nested
    class MemberTests {
        private final TestFixture testFixture = TestFixture.createJvmCompatibility(Customer.class);

        @Test
        void memberIsCreatedInsideParent() {
            testFixture.givenStateful(new Customer("customer-1", List.of()))
                    .whenEvent(new PaymentStarted("customer-1", "payment-1"))
                    .expectOnlyCommands("created:customer-1:payment-1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(Customer.class).<Customer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.payments().size() == 1)
                            .filter(c -> c.payments().getFirst().paymentId().equals("payment-1"))
                            .filter(c -> c.payments().getFirst().eventCount() == 1)
                            .isPresent());
        }

        @Test
        void memberIsUpdatedViaChildAssociationOnly() {
            testFixture.givenStateful(new Customer("customer-1", List.of(new Payment("payment-1", 1))))
                    .whenEvent(new PaymentCaptured("payment-1"))
                    .expectOnlyCommands("updated:customer-1:payment-1:2")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(Customer.class).<Customer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.payments().size() == 1)
                            .filter(c -> c.payments().getFirst().eventCount() == 2)
                            .isPresent());
        }

        @Test
        void memberIsDeletedViaChildAssociationOnly() {
            testFixture.givenStateful(new Customer("customer-1", List.of(new Payment("payment-1", 1))))
                    .whenEvent(new PaymentCancelled("payment-1"))
                    .expectNoCommands()
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(Customer.class).<Customer>fetchFirst())
                    .expectResult(customer -> customer.filter(c -> c.payments().isEmpty()).isPresent());
        }

        @Test
        void memberIsNotUpdatedForWrongChildAssociation() {
            testFixture.givenStateful(new Customer("customer-1", List.of(new Payment("payment-1", 1))))
                    .whenEvent(new PaymentCaptured("payment-2"))
                    .expectNoCommands()
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(Customer.class).<Customer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.payments().size() == 1)
                            .filter(c -> c.payments().getFirst().eventCount() == 1)
                            .isPresent());
        }

        @Stateful
        record Customer(@EntityId @Association String customerId,
                        @Member @With List<Payment> payments) {
        }

        @Builder(toBuilder = true)
        record Payment(@Association String paymentId,
                       int eventCount) {
            @HandleEvent
            static Payment create(PaymentStarted event, Customer customer) {
                Fluxzero.sendAndForgetCommand("created:%s:%s".formatted(customer.customerId(), event.paymentId()));
                return new Payment(event.paymentId(), 1);
            }

            @HandleEvent
            Payment handle(PaymentCaptured event, Customer customer) {
                Fluxzero.sendAndForgetCommand(
                        "updated:%s:%s:%s".formatted(customer.customerId(), paymentId(), eventCount + 1));
                return toBuilder().eventCount(eventCount + 1).build();
            }

            @HandleEvent
            Payment handle(PaymentCancelled event) {
                return null;
            }
        }
    }

    @Nested
    class AdvancedMemberTests {
        @Test
        void nestedMemberIsUpdatedViaGrandchildAssociationOnly() {
            TestFixture.createJvmCompatibility(NestedCustomer.class)
                    .givenStateful(new NestedCustomer("customer-1", List.of(
                            new NestedPayment("payment-1", List.of(new PaymentAttempt("attempt-1", "psp-1", 0))))))
                    .whenEvent(new PaymentAttemptSettled("psp-1"))
                    .expectOnlyCommands("attempt:customer-1:payment-1:attempt-1:1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(NestedCustomer.class).<NestedCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .map(c -> c.payments().getFirst().attempts().getFirst().eventCount())
                            .filter(count -> count == 1)
                            .isPresent());
        }

        @Test
        void mapAndSingleMembersCanHandleMessages() {
            TestFixture.createJvmCompatibility(StructuredCustomer.class)
                    .givenStateful(new StructuredCustomer(
                            "customer-1", new StructuredPayment("primary", 0),
                            Map.of("secondary", new StructuredPayment("secondary", 10))))
                    .whenEvent(new StructuredPaymentCaptured("primary"))
                    .expectOnlyCommands("structured:customer-1:primary:1")
                    .expectNoErrors()
                    .andThen()
                    .whenEvent(new StructuredPaymentCaptured("secondary"))
                    .expectOnlyCommands("structured:customer-1:secondary:11")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(StructuredCustomer.class).<StructuredCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.primaryPayment().eventCount() == 1)
                            .filter(c -> c.paymentsById().get("secondary").eventCount() == 11)
                            .isPresent());
        }

        @Test
        void memberUpdatesWorkWithCommitInBatch() {
            TestFixture.createAsyncJvmCompatibility(BatchedCustomer.class)
                    .givenStateful(new BatchedCustomer(
                            "customer-1", List.of(new BatchedPayment("payment-1", 0))))
                    .whenApplying(fc -> {
                        fc.eventGateway().publish(
                                Guarantee.STORED,
                                new BatchedPaymentCaptured("payment-1"),
                                new BatchedPaymentCaptured("payment-1")).get();
                        return null;
                    })
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(BatchedCustomer.class).<BatchedCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .map(c -> c.payments().getFirst().eventCount())
                            .filter(count -> count == 2)
                            .isPresent());
        }

        @Test
        void memberIsMatchedViaParameterAssociation() {
            TestFixture.createAsyncJvmCompatibility(ParameterAssociationCustomer.class, new ParameterAssociationCommandHandler())
                    .givenStateful(new ParameterAssociationCustomer(
                            "customer-1", List.of(new ParameterAssociationPayment("line-1", "payment-1", 0))))
                    .whenCommand(new CapturePaymentCommand("payment-1"))
                    .expectResult("payment-1")
                    .expectOnlyCommands("parameter:customer-1:payment-1:1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(ParameterAssociationCustomer.class)
                            .<ParameterAssociationCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .map(c -> c.payments().getFirst().eventCount())
                            .filter(count -> count == 1)
                            .isPresent());
        }

        @Test
        void parentAndMemberMutationsAreComposedFromUpdatedRoot() {
            TestFixture.createJvmCompatibility(CombinedCustomer.class)
                    .givenStateful(new CombinedCustomer(
                            "customer-1", 0, List.of(new CombinedPayment("line-1", "payment-1", 0, 0))))
                    .whenEvent(new CombinedCustomerAndPaymentChanged("customer-1", "payment-1"))
                    .expectOnlyCommands("parent:1", "child:1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(CombinedCustomer.class).<CombinedCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.parentEventCount() == 1)
                            .map(c -> c.payments().getFirst())
                            .filter(p -> p.eventCount() == 1)
                            .filter(p -> p.observedParentEventCount() == 1)
                            .isPresent());
        }

        @Test
        void matchingAssociationCanUpdateMultipleChildrenAcrossParents() {
            TestFixture.createJvmCompatibility(SharedPaymentCustomer.class)
                    .givenStateful(new SharedPaymentCustomer("customer-1", List.of(
                            new SharedPayment("line-1", "shared-payment", 0),
                            new SharedPayment("line-2", "shared-payment", 10),
                            new SharedPayment("line-ignored", "other-payment", 100))))
                    .givenStateful(new SharedPaymentCustomer("customer-2", List.of(
                            new SharedPayment("line-3", "shared-payment", 20))))
                    .whenEvent(new SharedPaymentCaptured("shared-payment"))
                    .expectOnlyCommands(
                            "shared:customer-1:line-1:1",
                            "shared:customer-1:line-2:11",
                            "shared:customer-2:line-3:21")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(SharedPaymentCustomer.class)
                            .fetchAll(SharedPaymentCustomer.class))
                    .expectResult(customers -> customers.stream()
                            .flatMap(c -> c.payments().stream())
                            .filter(p -> p.paymentId().equals("shared-payment"))
                            .map(SharedPayment::eventCount)
                            .sorted()
                            .toList()
                            .equals(List.of(1, 11, 21)));
        }

        @Test
        void staticMemberCreateWithoutParentAssociationDoesNotCreateMember() {
            TestFixture.createJvmCompatibility(UnroutableCreateCustomer.class)
                    .givenStateful(new UnroutableCreateCustomer("customer-1", List.of()))
                    .whenEvent(new UnroutablePaymentStarted("payment-1"))
                    .expectNoCommands()
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(UnroutableCreateCustomer.class)
                            .<UnroutableCreateCustomer>fetchFirst())
                    .expectResult(customer -> customer.filter(c -> c.payments().isEmpty()).isPresent());
        }

        @Test
        void staticMemberCreateWithAlwaysAssociationFansOutToAllParents() {
            TestFixture.createJvmCompatibility(FanoutCreateCustomer.class)
                    .givenStateful(new FanoutCreateCustomer("customer-1", List.of()))
                    .givenStateful(new FanoutCreateCustomer("customer-2", List.of()))
                    .whenEvent(new FanoutPaymentStarted("payment-1"))
                    .expectOnlyCommands("fanout:customer-1:payment-1", "fanout:customer-2:payment-1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(FanoutCreateCustomer.class)
                            .fetchAll(FanoutCreateCustomer.class))
                    .expectResult(customers -> customers.size() == 2
                                               && customers.stream().allMatch(c -> c.payments().size() == 1)
                                               && customers.stream().allMatch(c -> c.payments().getFirst()
                                                       .paymentId().equals("payment-1")));
        }

        @Test
        void memberCollectionReturnCanAddMultipleMembersToListAndMap() {
            TestFixture.createJvmCompatibility(BulkCreateCustomer.class)
                    .givenStateful(new BulkCreateCustomer("customer-1", List.of(), Map.of()))
                    .whenEvent(new BulkListPaymentsStarted("customer-1", List.of("list-1", "list-2")))
                    .expectNoErrors()
                    .andThen()
                    .whenEvent(new BulkMapPaymentsStarted("customer-1", List.of("map-1", "map-2")))
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(BulkCreateCustomer.class)
                            .<BulkCreateCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.listPayments().stream().map(BulkListPayment::paymentId)
                                    .sorted().toList().equals(List.of("list-1", "list-2")))
                            .filter(c -> c.mapPayments().keySet().stream().sorted()
                                    .toList().equals(List.of("map-1", "map-2")))
                            .filter(c -> c.mapPayments().values().stream().map(BulkMapPayment::paymentId)
                                    .sorted().toList().equals(List.of("map-1", "map-2")))
                            .isPresent());
        }

        @Test
        void memberCanReturnDifferentEntityIdForListAndMap() {
            TestFixture.createJvmCompatibility(RenamedPaymentCustomer.class)
                    .givenStateful(new RenamedPaymentCustomer(
                            "customer-1",
                            List.of(new RenamedListPayment("old-payment", 0)),
                            Map.of("old-payment", new RenamedMapPayment("old-payment", 0))))
                    .whenEvent(new PaymentRenamed("old-payment", "new-payment"))
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(RenamedPaymentCustomer.class)
                            .<RenamedPaymentCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.listPayments().size() == 1)
                            .filter(c -> c.listPayments().getFirst().paymentId().equals("new-payment"))
                            .filter(c -> c.mapPayments().size() == 1)
                            .filter(c -> !c.mapPayments().containsKey("old-payment"))
                            .filter(c -> c.mapPayments().containsKey("new-payment"))
                            .filter(c -> c.mapPayments().get("new-payment").paymentId().equals("new-payment"))
                            .isPresent());
        }

        @Test
        void memberCollectionReturnCanReplaceExistingMemberWithMultipleMembers() {
            TestFixture.createJvmCompatibility(SplitPaymentCustomer.class)
                    .givenStateful(new SplitPaymentCustomer(
                            "customer-1", List.of(new SplitPayment("payment-1", 0))))
                    .whenEvent(new PaymentSplit("payment-1", "payment-1-a", "payment-1-b"))
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(SplitPaymentCustomer.class)
                            .<SplitPaymentCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.payments().stream().map(SplitPayment::paymentId)
                                    .sorted().toList().equals(List.of("payment-1-a", "payment-1-b")))
                            .isPresent());
        }

        @Test
        void memberAddedByParentCanHandleSameMessageFromUpdatedRoot() {
            TestFixture.createJvmCompatibility(ParentCreatesChildCustomer.class)
                    .givenStateful(new ParentCreatesChildCustomer("customer-1", List.of()))
                    .whenEvent(new ParentCreatesMatchingPayment("customer-1", "payment-1"))
                    .expectOnlyCommands("parent-created-child:customer-1:payment-1:1")
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(ParentCreatesChildCustomer.class)
                            .<ParentCreatesChildCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.payments().size() == 1)
                            .filter(c -> c.payments().getFirst().handledCount() == 1)
                            .isPresent());
        }

        @Test
        void recordMembersCanBeUpdatedWithoutExplicitWither() {
            TestFixture.createJvmCompatibility(ConstructorBackedCustomer.class)
                    .givenStateful(new ConstructorBackedCustomer(
                            "customer-1", List.of(new ConstructorBackedPayment("payment-1", 0))))
                    .whenEvent(new ConstructorBackedPaymentCaptured("payment-1"))
                    .expectNoErrors()
                    .andThen()
                    .whenApplying(fc -> Fluxzero.search(ConstructorBackedCustomer.class)
                            .<ConstructorBackedCustomer>fetchFirst())
                    .expectResult(customer -> customer
                            .filter(c -> c.payments().getFirst().captureCount() == 1)
                            .isPresent());
        }

        @Test
        void duplicateNonNullEntityIdsInStatefulMemberListDoNotBreakLoading() {
            TestFixture.createJvmCompatibility(DuplicateStatefulMemberCustomer.class)
                    .givenStateful(new DuplicateStatefulMemberCustomer(
                            "customer-1",
                            List.of(new DuplicateStatefulMemberPayment("payment-1"),
                                    new DuplicateStatefulMemberPayment("payment-1"))))
                    .whenEvent(new DuplicateStatefulMemberCaptured("payment-1"))
                    .expectNoErrors();
        }

        @Stateful
        record NestedCustomer(@EntityId @Association String customerId,
                              @Member @With List<NestedPayment> payments) {
        }

        record NestedPayment(@EntityId String paymentId,
                             @Member @With List<PaymentAttempt> attempts) {
        }

        @Builder(toBuilder = true)
        record PaymentAttempt(@EntityId String attemptId,
                              @Association String pspReference,
                              int eventCount) {
            @HandleEvent
            PaymentAttempt handle(PaymentAttemptSettled event, NestedCustomer customer, NestedPayment payment) {
                Fluxzero.sendAndForgetCommand(
                        "attempt:%s:%s:%s:%s".formatted(
                                customer.customerId(), payment.paymentId(), attemptId(), eventCount + 1));
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }

        @Stateful
        record StructuredCustomer(@EntityId @Association String customerId,
                                  @Member @With StructuredPayment primaryPayment,
                                  @Member @With Map<String, StructuredPayment> paymentsById) {
        }

        @Builder(toBuilder = true)
        record StructuredPayment(@EntityId @Association String paymentId,
                                 int eventCount) {
            @HandleEvent
            StructuredPayment handle(StructuredPaymentCaptured event, StructuredCustomer customer) {
                Fluxzero.sendAndForgetCommand(
                        "structured:%s:%s:%s".formatted(customer.customerId(), paymentId(), eventCount + 1));
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }

        @Stateful(commitInBatch = true)
        record BatchedCustomer(@EntityId @Association String customerId,
                               @Member @With List<BatchedPayment> payments) {
        }

        @Builder(toBuilder = true)
        record BatchedPayment(@EntityId @Association String paymentId,
                              int eventCount) {
            @HandleEvent
            BatchedPayment handle(BatchedPaymentCaptured event) {
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }

        @Stateful
        record ParameterAssociationCustomer(@EntityId String customerId,
                                            @Member @With List<ParameterAssociationPayment> payments) {
        }

        @Builder(toBuilder = true)
        record ParameterAssociationPayment(@EntityId String lineId,
                                           String paymentId,
                                           int eventCount) {
            @HandleResult
            ParameterAssociationPayment retry(String result,
                                              @Trigger @Association(value = "paymentId", path = "paymentId")
                                              CapturePaymentCommand command,
                                              ParameterAssociationCustomer customer) {
                Fluxzero.sendAndForgetCommand(
                        "parameter:%s:%s:%s".formatted(customer.customerId(), paymentId(), eventCount + 1));
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }

        static class ParameterAssociationCommandHandler {
            @HandleCommand
            String handle(CapturePaymentCommand command) {
                return command.paymentId();
            }
        }

        @Stateful
        @Builder(toBuilder = true)
        record CombinedCustomer(@EntityId @Association String customerId,
                                int parentEventCount,
                                @Member @With List<CombinedPayment> payments) {
            @HandleEvent
            CombinedCustomer handle(CombinedCustomerAndPaymentChanged event) {
                Fluxzero.sendAndForgetCommand("parent:%s".formatted(parentEventCount + 1));
                return toBuilder().parentEventCount(parentEventCount + 1).build();
            }
        }

        @Builder(toBuilder = true)
        record CombinedPayment(@EntityId String lineId,
                               @Association String paymentId,
                               int eventCount,
                               int observedParentEventCount) {
            @HandleEvent
            CombinedPayment handle(CombinedCustomerAndPaymentChanged event, CombinedCustomer customer) {
                Fluxzero.sendAndForgetCommand("child:%s".formatted(customer.parentEventCount()));
                return toBuilder()
                        .eventCount(eventCount + 1)
                        .observedParentEventCount(customer.parentEventCount())
                        .build();
            }
        }

        @Stateful
        record SharedPaymentCustomer(@EntityId String customerId,
                                     @Member @With List<SharedPayment> payments) {
        }

        @Builder(toBuilder = true)
        record SharedPayment(@EntityId String lineId,
                             @Association String paymentId,
                             int eventCount) {
            @HandleEvent
            SharedPayment handle(SharedPaymentCaptured event, SharedPaymentCustomer customer) {
                Fluxzero.sendAndForgetCommand(
                        "shared:%s:%s:%s".formatted(customer.customerId(), lineId(), eventCount + 1));
                return toBuilder().eventCount(eventCount + 1).build();
            }
        }

        @Stateful
        record UnroutableCreateCustomer(@EntityId String customerId,
                                        @Member @With List<UnroutablePayment> payments) {
        }

        record UnroutablePayment(@EntityId @Association String paymentId) {
            @HandleEvent
            static UnroutablePayment create(UnroutablePaymentStarted event, UnroutableCreateCustomer customer) {
                Fluxzero.sendAndForgetCommand(
                        "unroutable:%s:%s".formatted(customer.customerId(), event.paymentId()));
                return new UnroutablePayment(event.paymentId());
            }
        }

        @Stateful
        record FanoutCreateCustomer(@EntityId String customerId,
                                    @Member @With List<FanoutPayment> payments) {
        }

        record FanoutPayment(@EntityId String paymentId) {
            @HandleEvent
            @Association(always = true)
            static FanoutPayment create(FanoutPaymentStarted event, FanoutCreateCustomer customer) {
                Fluxzero.sendAndForgetCommand("fanout:%s:%s".formatted(customer.customerId(), event.paymentId()));
                return new FanoutPayment(event.paymentId());
            }
        }

        @Stateful
        record BulkCreateCustomer(@EntityId @Association String customerId,
                                  @Member @With List<BulkListPayment> listPayments,
                                  @Member @With Map<String, BulkMapPayment> mapPayments) {
        }

        record BulkListPayment(@EntityId String paymentId) {
            @HandleEvent
            static List<BulkListPayment> create(BulkListPaymentsStarted event) {
                return event.paymentIds().stream().map(BulkListPayment::new).toList();
            }
        }

        record BulkMapPayment(@EntityId String paymentId) {
            @HandleEvent
            static List<BulkMapPayment> create(BulkMapPaymentsStarted event) {
                return event.paymentIds().stream().map(BulkMapPayment::new).toList();
            }
        }

        @Stateful
        record RenamedPaymentCustomer(@EntityId String customerId,
                                      @Member @With List<RenamedListPayment> listPayments,
                                      @Member @With Map<String, RenamedMapPayment> mapPayments) {
        }

        record RenamedListPayment(@EntityId @Association String paymentId,
                                  int version) {
            @HandleEvent
            RenamedListPayment rename(PaymentRenamed event) {
                return new RenamedListPayment(event.newPaymentId(), version + 1);
            }
        }

        record RenamedMapPayment(@EntityId @Association String paymentId,
                                 int version) {
            @HandleEvent
            RenamedMapPayment rename(PaymentRenamed event) {
                return new RenamedMapPayment(event.newPaymentId(), version + 1);
            }
        }

        @Stateful
        record SplitPaymentCustomer(@EntityId String customerId,
                                    @Member @With List<SplitPayment> payments) {
        }

        record SplitPayment(@EntityId @Association String paymentId,
                            int version) {
            @HandleEvent
            List<SplitPayment> split(PaymentSplit event) {
                return List.of(
                        new SplitPayment(event.firstPaymentId(), version + 1),
                        new SplitPayment(event.secondPaymentId(), version + 1));
            }
        }

        @Stateful
        @Builder(toBuilder = true)
        record ParentCreatesChildCustomer(@EntityId @Association String customerId,
                                          @Member @With List<ParentCreatedChildPayment> payments) {
            @HandleEvent
            ParentCreatesChildCustomer handle(ParentCreatesMatchingPayment event) {
                return toBuilder()
                        .payments(List.of(new ParentCreatedChildPayment(event.paymentId(), 0)))
                        .build();
            }
        }

        @Builder(toBuilder = true)
        record ParentCreatedChildPayment(@EntityId @Association String paymentId,
                                         int handledCount) {
            @HandleEvent
            ParentCreatedChildPayment handle(ParentCreatesMatchingPayment event,
                                             ParentCreatesChildCustomer customer) {
                Fluxzero.sendAndForgetCommand(
                        "parent-created-child:%s:%s:%s".formatted(
                                customer.customerId(), paymentId(), handledCount + 1));
                return toBuilder().handledCount(handledCount + 1).build();
            }
        }

        @Stateful
        record ConstructorBackedCustomer(@EntityId String customerId,
                                         @Member List<ConstructorBackedPayment> payments) {
        }

        record ConstructorBackedPayment(@EntityId @Association String paymentId,
                                        int captureCount) {
            @HandleEvent
            ConstructorBackedPayment capture(ConstructorBackedPaymentCaptured event) {
                return new ConstructorBackedPayment(paymentId, captureCount + 1);
            }
        }

        @Stateful
        record DuplicateStatefulMemberCustomer(@EntityId String customerId,
                                               @Member List<DuplicateStatefulMemberPayment> payments) {
        }

        record DuplicateStatefulMemberPayment(@EntityId @Association String paymentId) {
            @HandleEvent
            DuplicateStatefulMemberPayment capture(DuplicateStatefulMemberCaptured event) {
                return this;
            }
        }

        record PaymentAttemptSettled(String pspReference) {
        }

        record StructuredPaymentCaptured(String paymentId) {
        }

        record BatchedPaymentCaptured(String paymentId) {
        }

        record CapturePaymentCommand(String paymentId) {
        }

        record CombinedCustomerAndPaymentChanged(String customerId, String paymentId) {
        }

        record SharedPaymentCaptured(String paymentId) {
        }

        record UnroutablePaymentStarted(String paymentId) {
        }

        record FanoutPaymentStarted(String paymentId) {
        }

        record BulkListPaymentsStarted(String customerId, List<String> paymentIds) {
        }

        record BulkMapPaymentsStarted(String customerId, List<String> paymentIds) {
        }

        record PaymentRenamed(String paymentId, String newPaymentId) {
        }

        record PaymentSplit(String paymentId, String firstPaymentId, String secondPaymentId) {
        }

        record ParentCreatesMatchingPayment(String customerId, String paymentId) {
        }

        record ConstructorBackedPaymentCaptured(String paymentId) {
        }

        record DuplicateStatefulMemberCaptured(String paymentId) {
        }
    }

    @Nested
    class StorageActionTests {
        @Test
        void existingStatefulWithoutMembersStillStoresOnce() {
            CountingRepository repository =
                    new CountingRepository().add("customer-1", new PlainCountingCustomer("customer-1", 0));

            statefulHandler(PlainCountingCustomer.class, repository)
                    .getInvoker(message(new PlainCountingCustomerChanged("customer-1")))
                    .orElseThrow()
                    .invoke();

            assertEquals(1, repository.putCount);
            assertEquals(0, repository.deleteCount);
            assertEquals(new PlainCountingCustomer("customer-1", 1), repository.get("customer-1"));
        }

        @Test
        void multipleListMemberMutationsInOneRootAreStoredOnce() {
            CountingRepository repository = new CountingRepository()
                    .add("customer-1", new ListCountingCustomer("customer-1", List.of(
                            new ListCountingPayment("line-1", "shared-payment", 0),
                            new ListCountingPayment("line-2", "shared-payment", 10))));

            statefulHandler(ListCountingCustomer.class, repository)
                    .getInvoker(message(new CountingPaymentCaptured("shared-payment")))
                    .orElseThrow()
                    .invoke();

            ListCountingCustomer customer = repository.get("customer-1", ListCountingCustomer.class);
            assertEquals(1, repository.putCount);
            assertEquals(0, repository.deleteCount);
            assertEquals(List.of(1, 11), customer.payments().stream().map(ListCountingPayment::captureCount).toList());
        }

        @Test
        void multipleMapMemberMutationsInOneRootAreStoredOnce() {
            CountingRepository repository = new CountingRepository()
                    .add("customer-1", new MapCountingCustomer("customer-1", new LinkedHashMap<>(Map.of(
                            "line-1", new MapCountingPayment("line-1", "shared-payment", 0),
                            "line-2", new MapCountingPayment("line-2", "shared-payment", 10)))));

            statefulHandler(MapCountingCustomer.class, repository)
                    .getInvoker(message(new CountingPaymentCaptured("shared-payment")))
                    .orElseThrow()
                    .invoke();

            MapCountingCustomer customer = repository.get("customer-1", MapCountingCustomer.class);
            assertEquals(1, repository.putCount);
            assertEquals(0, repository.deleteCount);
            assertEquals(List.of(1, 11), customer.payments().values().stream()
                    .map(MapCountingPayment::captureCount).sorted().toList());
        }

        @Test
        void matchingMembersAcrossParentsStoreOncePerRoot() {
            CountingRepository repository = new CountingRepository()
                    .add("customer-1", new ListCountingCustomer("customer-1", List.of(
                            new ListCountingPayment("line-1", "shared-payment", 0),
                            new ListCountingPayment("line-2", "shared-payment", 10))))
                    .add("customer-2", new ListCountingCustomer("customer-2", List.of(
                            new ListCountingPayment("line-3", "shared-payment", 20))));

            statefulHandler(ListCountingCustomer.class, repository)
                    .getInvoker(message(new CountingPaymentCaptured("shared-payment")))
                    .orElseThrow()
                    .invoke();

            assertEquals(2, repository.putCount);
            assertEquals(0, repository.deleteCount);
            assertEquals(List.of(1, 11), repository.get("customer-1", ListCountingCustomer.class)
                    .payments().stream().map(ListCountingPayment::captureCount).sorted().toList());
            assertEquals(List.of(21), repository.get("customer-2", ListCountingCustomer.class)
                    .payments().stream().map(ListCountingPayment::captureCount).toList());
        }

        @Test
        void entityParameterIsNotResolvedBeforeStatefulRepositoryMatch() {
            CountingEntityParameterResolver resolver = new CountingEntityParameterResolver();

            statefulHandler(EntityInjectedCustomer.class, new CountingRepository(),
                            new PayloadParameterResolver(), resolver)
                    .getInvoker(message(
                            new EntityInjectedCustomerChanged("missing-customer"),
                            Metadata.of(Entity.AGGREGATE_ID_METADATA_KEY, "aggregate-1",
                                        Entity.AGGREGATE_TYPE_METADATA_KEY,
                                        EntityInjectedAggregate.class.getName())));

            assertEquals(0, resolver.resolutionCount);
        }

        @Test
        void entityAssociationParameterIsResolvedBeforeStatefulRepositoryMatch() {
            JvmCompatibilityMetadataMode.run(() -> {
                FixedEntityParameterResolver resolver =
                        new FixedEntityParameterResolver(new Foo("customer-1"));
                AssociationLookupRepository repository =
                        new AssociationLookupRepository(() -> resolver.resolutionCount);
                repository.add("customer-1", new EntityAssociationCustomer("customer-1"));

                statefulHandler(EntityAssociationCustomer.class, repository,
                                new PayloadParameterResolver(), resolver)
                        .getInvoker(message(
                                new EntityAssociationChanged(),
                                Metadata.of(Entity.AGGREGATE_ID_METADATA_KEY, "foo-1",
                                            Entity.AGGREGATE_TYPE_METADATA_KEY, Foo.class.getName())))
                        .orElseThrow();

                assertTrue(repository.resolvedBeforeLookup);
                assertEquals(Map.of("customer-1", "customerId"), repository.lastAssociations);
            });
        }

        @Test
        void entityValueAssociationParameterIsResolvedBeforeStatefulRepositoryMatch() {
            FixedEntityParameterResolver resolver =
                    new FixedEntityParameterResolver(new Foo("customer-1"));
            AssociationLookupRepository repository =
                    new AssociationLookupRepository(() -> resolver.resolutionCount);
            repository.add("customer-1", new EntityValueAssociationCustomer("customer-1"));

            statefulHandler(EntityValueAssociationCustomer.class, repository,
                            new PayloadParameterResolver(), resolver)
                    .getInvoker(message(
                            new EntityAssociationChanged(),
                            Metadata.of(Entity.AGGREGATE_ID_METADATA_KEY, "foo-1",
                                        Entity.AGGREGATE_TYPE_METADATA_KEY, Foo.class.getName())))
                    .orElseThrow();

            assertTrue(repository.resolvedBeforeLookup);
            assertEquals(Map.of("customer-1", "customerId"), repository.lastAssociations);
        }

        private StatefulHandler statefulHandler(Class<?> targetClass, HandlerRepository repository) {
            return statefulHandler(targetClass, repository, new PayloadParameterResolver());
        }

        @SafeVarargs
        private StatefulHandler statefulHandler(Class<?> targetClass, HandlerRepository repository,
                                                ParameterResolver<? super DeserializingMessage>... resolvers) {
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers = List.of(resolvers);
            Function<Executable, ? extends java.lang.annotation.Annotation> annotationProvider =
                    e -> ReflectionUtils.getMethodAnnotation(e, HandleEvent.class).orElse(null);
            BiFunction<Class<?>, List<ParameterResolver<? super DeserializingMessage>>,
                    HandlerMatcher<Object, DeserializingMessage>> matcherFactory =
                    (type, methodParameterResolvers) -> HandlerInspector.inspect(
                            type,
                            methodParameterResolvers,
                            HandlerConfiguration.<DeserializingMessage>builder()
                                    .methodAnnotation(HandleEvent.class)
                                    .messageFilter(new PayloadFilter())
                                    .build());
            return new StatefulHandler(
                    targetClass,
                    matcherFactory.apply(targetClass, parameterResolvers),
                    repository,
                    parameterResolvers,
                    annotationProvider,
                    matcherFactory,
                    new JacksonSerializer());
        }

        private DeserializingMessage message(Object payload) {
            return message(payload, Metadata.empty());
        }

        private DeserializingMessage message(Object payload, Metadata metadata) {
            return new DeserializingMessage(new Message(payload, metadata), MessageType.EVENT, new JacksonSerializer());
        }

        @Stateful
        record PlainCountingCustomer(@EntityId @Association String customerId,
                                     int handledCount) {
            @HandleEvent
            PlainCountingCustomer handle(PlainCountingCustomerChanged event) {
                return new PlainCountingCustomer(customerId, handledCount + 1);
            }
        }

        @Stateful
        record ListCountingCustomer(@EntityId String customerId,
                                    @Member List<ListCountingPayment> payments) {
        }

        record ListCountingPayment(@EntityId String lineId,
                                   @Association String paymentId,
                                   int captureCount) {
            @HandleEvent
            ListCountingPayment capture(CountingPaymentCaptured event) {
                return new ListCountingPayment(lineId, paymentId, captureCount + 1);
            }
        }

        @Stateful
        record MapCountingCustomer(@EntityId String customerId,
                                   @Member Map<String, MapCountingPayment> payments) {
        }

        record MapCountingPayment(@EntityId String lineId,
                                  @Association String paymentId,
                                  int captureCount) {
            @HandleEvent
            MapCountingPayment capture(CountingPaymentCaptured event) {
                return new MapCountingPayment(lineId, paymentId, captureCount + 1);
            }
        }

        record PlainCountingCustomerChanged(String customerId) {
        }

        record CountingPaymentCaptured(String paymentId) {
        }

        @Stateful
        record EntityInjectedCustomer(@EntityId @Association String customerId) {
            @HandleEvent
            EntityInjectedCustomer handle(EntityInjectedCustomerChanged event, Entity<EntityInjectedAggregate> entity) {
                return this;
            }
        }

        record EntityInjectedCustomerChanged(String customerId) {
        }

        record EntityInjectedAggregate(String value) {
        }

        @Stateful
        record EntityAssociationCustomer(@EntityId @Association String customerId) {
            @HandleEvent
            EntityAssociationCustomer handle(
                    EntityAssociationChanged event,
                    @Association(value = "get/customerId", path = "customerId") Entity<Foo> foo) {
                return this;
            }
        }

        @Stateful
        record EntityValueAssociationCustomer(@EntityId @Association String customerId) {
            @HandleEvent
            EntityValueAssociationCustomer handle(
                    EntityAssociationChanged event,
                    @Association(value = "customerId", path = "customerId") Foo foo) {
                return this;
            }
        }

        record EntityAssociationChanged() {
        }

        record Foo(String customerId) {
        }

        class CountingEntityParameterResolver extends EntityParameterResolver {
            private int resolutionCount;

            @Override
            protected Entity<?> getMatchingEntity(Object input, Parameter parameter) {
                resolutionCount++;
                return super.getMatchingEntity(input, parameter);
            }
        }

        class FixedEntityParameterResolver extends EntityParameterResolver {
            private final Entity<?> entity;
            private int resolutionCount;

            FixedEntityParameterResolver(Foo value) {
                entity = ImmutableEntity.<Foo>builder()
                        .id("foo-1")
                        .type(Foo.class)
                        .value(value)
                        .build();
            }

            @Override
            protected Entity<?> getMatchingEntity(Object input, Parameter parameter) {
                resolutionCount++;
                return entity;
            }
        }

        class AssociationLookupRepository extends CountingRepository {
            private final IntSupplier resolutionCount;
            private boolean resolvedBeforeLookup;

            AssociationLookupRepository(IntSupplier resolutionCount) {
                this.resolutionCount = resolutionCount;
            }

            @Override
            public Collection<? extends Entry<?>> findByAssociation(Map<Object, String> associations) {
                resolvedBeforeLookup = resolutionCount.getAsInt() > 0;
                return super.findByAssociation(associations);
            }
        }

        class CountingRepository implements HandlerRepository {
            private final Map<String, Object> values = new LinkedHashMap<>();
            private int putCount;
            private int deleteCount;
            Map<Object, String> lastAssociations;

            CountingRepository add(String id, Object value) {
                values.put(id, value);
                return this;
            }

            @SuppressWarnings("unchecked")
            <T> T get(String id, Class<T> type) {
                return (T) values.get(id);
            }

            Object get(String id) {
                return values.get(id);
            }

            @Override
            public Collection<? extends Entry<?>> findByAssociation(Map<Object, String> associations) {
                lastAssociations = Map.copyOf(associations);
                return associations.isEmpty() ? List.of() : entries();
            }

            @Override
            public Collection<? extends Entry<?>> getAll() {
                return entries();
            }

            @Override
            public CompletableFuture<?> put(Object id, Object value) {
                putCount++;
                values.put(id.toString(), value);
                return CompletableFuture.completedFuture(value);
            }

            @Override
            public CompletableFuture<?> delete(Object id) {
                deleteCount++;
                values.remove(id.toString());
                return CompletableFuture.completedFuture(id);
            }

            private Collection<? extends Entry<?>> entries() {
                return values.entrySet().stream()
                        .map(entry -> new CountingEntry(entry.getKey(), entry.getValue()))
                        .toList();
            }
        }

        record CountingEntry(String id, Object value) implements Entry<Object> {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Object getValue() {
                return value;
            }
        }
    }

    record SomeEvent(String someId) {
    }

    record DeleteHandler(String someId) {
    }

    record DeleteHandlerByList(String someId) {
    }

    record ReplaceHandler(String someId, String newId) {
    }

    record ReplaceHandlerByList(String someId, String newId) {
    }

    record DuplicationEvent(String someId, String copyId) {
    }

    record AliasEvent(AliasId aliasId) {
    }

    static class AliasId extends Id<Object> {
        protected AliasId(String id) {
            super(id, Object.class, "alias-");
        }
    }

    record CustomEvent(String customId) {
    }

    record ParameterAssociationEvent(String customId) {
    }

    record RoutingKeyEvent(@RoutingKey String someId) {
    }

    record SendOrder(String orderId) {
    }

    record CustomRightPathEvent(String customId) {
    }

    record CustomWrongPathEvent(String customId) {
    }

    record AlwaysAssociate(String randomId) {
        AlwaysAssociate() {
            this(UUID.randomUUID().toString());
        }
    }

    record AlwaysAssociateStatic() {
    }

    record ExcludedEvent(String someId) {
    }

    record EventWithRightPath(String someId) {
    }

    record EventWithWrongPath(String customId) {
    }

    record EventWithPropertyList(List<String> someIds) {
    }

    record EventWithNestedPropertyList(NestedPropertyList someIds) {
    }

    record NestedPropertyList(List<String> nested) {
    }

    record EventWithMapPropertyList(Map<String, List<String>> someIds) {
    }

    record PaymentStarted(String customerId, String paymentId) {
    }

    record PaymentCaptured(String paymentId) {
    }

    record PaymentCancelled(String paymentId) {
    }
}
