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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractParentReplayTest {
    private static final IllegalCommandException CLOSED = new IllegalCommandException("Owner is closed");
    private static final AtomicInteger checks = new AtomicInteger();
    private final OwnerId ownerId = new OwnerId("owner");
    private TestFixture fixture;

    private TestFixture fixture(boolean async, boolean closed) {
        checks.set(0);
        fixture = async ? TestFixture.createAsync() : TestFixture.create();
        return fixture.givenCommands(new CreateOwner(ownerId, closed));
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.getFluxzero().close();
        }
    }

    @Test
    void graphReadThroughInterfaceReconstructsConcreteOwner() {
        fixture(false, false).whenExecuting(fc -> {
            var graph = Fluxzero.loadCurrentGraph(ownerId, OwnerContract.class);
            assertEquals(new Owner(ownerId, false), graph.get());
            assertEquals(Owner.class, graph.type());
        }).expectSuccessfulResult().expectNoErrors();
    }

    @Test
    void modelReadThroughInterfaceReconstructsConcreteOwner() {
        fixture(false, false).whenExecuting(fc -> {
            var model = Fluxzero.loadModel(ownerId, OwnerContract.class);
            assertEquals(new Owner(ownerId, false), model.get());
            assertEquals(Owner.class, model.type());
        }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concreteParentReconstructsAndAuthorizesChild(boolean async) {
        var childId = new ConcreteChildId("child");
        var command = new CreateConcreteChild(childId, ownerId);
        fixture(async, false).whenCommand(command)
                .expectSuccessfulResult().expectEvents(command).expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(1, checks.get());
                    assertEquals(new ConcreteChild(childId, ownerId), Fluxzero.loadCurrentGraph(childId).get());
                    assertEquals(1, fc.eventStore().getEvents(childId.toString()).count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abstractParentReconstructsAndAuthorizesChild(boolean async) {
        var childId = new AbstractChildId("child");
        var command = new CreateAbstractChild(childId, ownerId);
        fixture(async, false).whenCommand(command)
                .expectSuccessfulResult().expectEvents(command).expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(1, checks.get());
                    var child = Fluxzero.loadCurrentGraph(childId).get();
                    assertEquals(new AbstractChild(childId, ownerId), child);
                    assertInstanceOf(OwnerId.class, child.ownerId());
                    assertEquals(1, fc.eventStore().getEvents(childId.toString()).count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concreteParentRejectsForbiddenChildWithoutCommitting(boolean async) {
        var childId = new ConcreteChildId("child");
        fixture(async, true).whenCommand(new CreateConcreteChild(childId, ownerId))
                .expectNoEvents()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadCurrentGraph(childId).isEmpty());
                    assertEquals(0, fc.eventStore().getEvents(childId.toString()).count());
                    assertEquals(new Owner(ownerId, true), Fluxzero.loadCurrentGraph(ownerId).get());
                })
                .expectExceptionalResult(CLOSED)
                .expectThat(fc -> assertEquals(1, checks.get()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abstractParentRejectsForbiddenChildWithoutCommitting(boolean async) {
        var childId = new AbstractChildId("child");
        fixture(async, true).whenCommand(new CreateAbstractChild(childId, ownerId))
                .expectNoEvents()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadCurrentGraph(childId).isEmpty());
                    assertEquals(0, fc.eventStore().getEvents(childId.toString()).count());
                    assertEquals(new Owner(ownerId, true), Fluxzero.loadCurrentGraph(ownerId).get());
                })
                .expectExceptionalResult(CLOSED)
                .expectThat(fc -> assertEquals(1, checks.get()));
    }

    interface ChildMutation {}

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void interfaceOnlyAssertionRetainsItsDeclaredScope(boolean async, boolean closed) {
        checks.set(0);
        fixture = async ? TestFixture.createAsync() : TestFixture.create();
        var command = new CreateContractChild("child", "contract-owner");
        var result = fixture.givenCommands(new CreateContractOwner("contract-owner", closed)).whenCommand(command);
        if (closed) {
            result.expectExceptionalResult(CLOSED).expectNoEvents().expectThat(fc -> {
                assertTrue(Fluxzero.loadCurrentGraph("child", ContractChild.class).isEmpty());
                assertEquals(0, fc.eventStore().getEvents("child").count());
            });
        } else {
            result.expectSuccessfulResult().expectNoErrors().expectEvents(command).expectThat(fc -> {
                assertEquals(new ContractChild("child", "contract-owner"),
                        Fluxzero.loadModel("child", ContractChild.class).get());
                assertEquals(1, fc.eventStore().getEvents("child").count());
            });
        }
        assertEquals(1, checks.get());
    }

    @Model(cached = false)
    interface InterfaceOnlyOwner {
        @EntityId String id();

        @AssertLegal(cascade = true)
        void authorize(ChildMutation mutation);
    }

    @Model(cached = false)
    record ContractOwner(@EntityId String id, boolean closed) implements InterfaceOnlyOwner {
        @Override
        public void authorize(ChildMutation mutation) {
            checks.incrementAndGet();
            if (closed) { throw CLOSED; }
        }

        @AssertLegal(cascade = true)
        void concreteOnly(ChildMutation mutation) {
            throw new IllegalStateException("An interface parent must retain its declared assertion scope");
        }
    }

    @Model(cached = false)
    record ContractChild(@EntityId String id, @Parent(InterfaceOnlyOwner.class) String ownerId) {}

    record CreateContractOwner(String id, boolean closed) {
        @Apply ContractOwner apply() { return new ContractOwner(id, closed); }
    }

    record CreateContractChild(String id, String ownerId) implements ChildMutation {
        @Apply ContractChild apply() { return new ContractChild(id, ownerId); }
    }

    @Model(cached = false)
    interface OwnerContract {
        @EntityId Id<? extends OwnerContract> ownerId();

        @AssertLegal(cascade = true)
        void authorize(ChildMutation mutation);
    }

    // Disabling caches forces the parent check to reconstruct the previously committed owner.
    @Model(name = "abstract-parent-replay-owner", cached = false)
    record Owner(@EntityId OwnerId ownerId, boolean closed) implements OwnerContract {
        @Override
        @AssertLegal(cascade = true)
        public void authorize(ChildMutation mutation) {
            checks.incrementAndGet();
            if (closed) {
                throw CLOSED;
            }
        }
    }

    @Model(cached = false)
    record AbstractChild(@EntityId AbstractChildId childId,
                         @Parent(required = true, deleteOnParentDeletion = false, preventParentDeletion = true)
                         Id<? extends OwnerContract> ownerId) {}

    @Model(cached = false)
    record ConcreteChild(@EntityId ConcreteChildId childId,
                         @Parent(required = true, deleteOnParentDeletion = false, preventParentDeletion = true)
                         OwnerId ownerId) {}

    record CreateOwner(OwnerId ownerId, boolean closed) {
        @Apply Owner apply() { return new Owner(ownerId, closed); }
    }

    record CreateAbstractChild(AbstractChildId childId, Id<? extends OwnerContract> ownerId) implements ChildMutation {
        @Apply AbstractChild apply() { return new AbstractChild(childId, ownerId); }
    }

    record CreateConcreteChild(ConcreteChildId childId, OwnerId ownerId) implements ChildMutation {
        @Apply ConcreteChild apply() { return new ConcreteChild(childId, ownerId); }
    }

    static final class OwnerId extends Id<Owner> {
        OwnerId(String id) { super(id, "owner-"); }
    }

    static final class AbstractChildId extends Id<AbstractChild> {
        AbstractChildId(String id) { super(id, "abstract-child-"); }
    }

    static final class ConcreteChildId extends Id<ConcreteChild> {
        ConcreteChildId(String id) { super(id, "concrete-child-"); }
    }
}
