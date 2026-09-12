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

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelParentWriteTest {
    private final ReservationId reservationId = new ReservationId("reservation-1");
    private final TicketId ticketId = new TicketId("ticket-1");
    private TestFixture fixture;

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.getFluxzero().close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void injectsAndUpdatesParentWhileDeletingChild(boolean async) {
        fixture = async ? TestFixture.createAsync() : TestFixture.create();
        var command = new ExpireReservation(reservationId);
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(command)
                .expectSuccessfulResult()
                .expectEvents(command)
                .expectThat(fc -> assertExpired(fc, command))
                .expectNoErrors();
    }

    @Test
    void readOnlyParentInjectionDoesNotWriteParent() {
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(new RemoveWithParentRead(reservationId))
                .expectSuccessfulResult()
                .expectEvents(new RemoveWithParentRead(reservationId))
                .expectThat(fc -> {
                    assertNull(Fluxzero.loadModel(reservationId).get());
                    assertTrue(Fluxzero.loadModel(ticketId).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(ticketId.toString()).count());
                }).expectNoErrors();
    }

    @Test
    void directParentIdKeepsExistingWriteSelection() {
        fixture = TestFixture.create();
        var command = new ExpireWithDirectParent(reservationId, ticketId);
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(command)
                .expectSuccessfulResult()
                .expectEvents(command)
                .expectThat(fc -> assertExpired(fc, command))
                .expectNoErrors();
    }

    @Test
    void replayRetainsStringIdSelectionRootWithoutExecutingTheOtherApply() {
        fixture = TestFixture.create();
        var command = new ExpireString(reservationId.toString());
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(command).expectSuccessfulResult().expectEvents(command)
                .expectThat(fc -> assertExpired(fc, command)).expectNoErrors();
    }

    @Test
    void deletingChildBeforeParentApplyKeepsItsPinnedParent() {
        fixture = TestFixture.create();
        var command = new ExpireChildFirst(reservationId);
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(command).expectSuccessfulResult().expectEvents(command)
                .expectThat(fc -> assertExpired(fc, command)).expectNoErrors();
    }

    @Test
    void graphParameterCanSupplyTheParentWriteTarget() {
        fixture = TestFixture.create();
        var command = new ExpireWithGraph(reservationId);
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(command).expectSuccessfulResult().expectEvents(command)
                .expectThat(fc -> assertExpired(fc, command)).expectNoErrors();
    }

    @Test
    void onlyTheParentNeedsAnApplyWhenChildIdSuppliesTheSelectionRoot() {
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(new ReleaseShared(reservationId, reservationId))
                .expectSuccessfulResult().expectEvents(new ReleaseShared(reservationId, reservationId))
                .expectThat(fc -> {
                    assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
                    assertEquals(new Reservation(reservationId, ticketId), Fluxzero.loadModel(reservationId).get());
                    assertEquals(1, fc.eventStore().getEvents(reservationId.toString()).count());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of(ticketId.toString()));
                    assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
                }).expectNoErrors();
    }

    @Test
    void differentChildrenMayResolveOneSharedParent() {
        var second = new ReservationId("reservation-2");
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId), new MoveReservation(second, ticketId))
                .whenCommand(new ReleaseShared(reservationId, second))
                .expectSuccessfulResult().expectEvents(new ReleaseShared(reservationId, second))
                .expectThat(fc -> assertFalse(Fluxzero.loadModel(ticketId).get().reserved())).expectNoErrors();
    }

    @Test
    void ambiguousParentsAreRejectedWithoutWrites() {
        var second = new ReservationId("reservation-2");
        var other = new TicketId("other-ticket");
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId), new CreateReservation(second, other))
                .whenCommand(new ReleaseShared(reservationId, second))
                .expectExceptionalResult(IllegalStateException.class).expectNoEvents()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadModel(ticketId).get().reserved());
                    assertTrue(Fluxzero.loadModel(other).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(ticketId.toString()).count());
                    assertEquals(1, fc.eventStore().getEvents(other.toString()).count());
                });
    }

    @Test
    void missingRequiredParentDoesNotDeleteChild() {
        fixture = TestFixture.create();
        fixture.givenCommands(new MoveReservation(reservationId, null))
                .whenCommand(new ExpireReservation(reservationId))
                .expectExceptionalResult(IllegalStateException.class).expectNoEvents()
                .expectThat(fc -> assertEquals(new Reservation(reservationId, null),
                                              Fluxzero.loadModel(reservationId).get()));
    }

    @Test
    void failureAfterChildApplyRollsBackTheCompleteAction() {
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(new FailAfterChild(reservationId))
                .expectExceptionalResult(IllegalStateException.class).expectNoEvents()
                .expectThat(fc -> {
                    assertEquals(new Reservation(reservationId, ticketId), Fluxzero.loadModel(reservationId).get());
                    assertTrue(Fluxzero.loadModel(ticketId).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(reservationId.toString()).count());
                });
    }

    @Test
    void returningAnUnrelatedReadOnlyModelCannotOverwriteIt() {
        var other = new TicketId("other-ticket");
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId), new SetTicket(other, true))
                .whenCommand(new WrongTicket(reservationId, other.toString()))
                .expectExceptionalResult(IllegalStateException.class).expectNoEvents()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadModel(ticketId).get().reserved());
                    assertTrue(Fluxzero.loadModel(other).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(other.toString()).count());
                });
    }

    @Test
    void qualifiedParentSelectionAndReplayKeepTheOtherParentReadOnly() {
        var other = new TicketId("other-ticket");
        fixture = TestFixture.create();
        fixture.givenCommands(new SetTicket(ticketId, true), new SetTicket(other, true),
                              new CreateDual("dual", ticketId, other))
                .whenCommand(new ReleasePrimary("dual"))
                .expectSuccessfulResult().expectEvents(new ReleasePrimary("dual"))
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(
                            List.of(ticketId.toString(), other.toString()));
                    assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
                    assertTrue(Fluxzero.loadModel(other).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(other.toString()).count());
                }).expectNoErrors();
    }

    @Test
    void planningKeepsAncestorWritesSeparateFromDirectRoutingAndReplayExecution() {
        var plan = MutationPlan.compile(ExpireReservation.class,
                                       EntityMetadata.of(ExpireReservation.class).handlerMethods());
        var resolution = plan.resolve(new ExpireReservation(reservationId));
        assertEquals(1, resolution.models().size());
        assertEquals(MutationPlan.Access.READ_WRITE, resolution.ancestorDependencies().getFirst().access());
        assertNull(plan.routingTarget(new Message(new ExpireReservation(reservationId))));

        var replay = new MutationPlan.Compiler(List.of(new PayloadParameterResolver()))
                .compileReplay(ExpireString.class, Ticket.class);
        var replayResolution = replay.targets().resolve(new ExpireString(reservationId.toString()));
        assertEquals(List.of(reservationId.toString()),
                     replayResolution.models().stream().map(MutationPlan.ResolvedModel::modelId).toList());
        assertEquals(MutationPlan.Access.READ_ONLY, replayResolution.models().getFirst().access());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collectionAndDynamicAppliesKeepTheirExistingReadOnlyAncestorSelection(boolean collection) {
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(collection ? new CreateAnotherTicket(reservationId, ticketId)
                                        : new DynamicNoOp(reservationId, ticketId))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadModel(ticketId).get().reserved());
                    assertEquals(new Reservation(reservationId, ticketId), Fluxzero.loadModel(reservationId).get());
                    assertEquals(1, fc.eventStore().getEvents(ticketId.toString()).count());
                    if (collection) {
                        assertFalse(Fluxzero.loadModel(new TicketId("new-ticket")).get().reserved());
                    }
                }).expectNoErrors();
    }

    @Test
    void aSelectionRootGetterIsNotReadAgainForTheAncestorWrite() {
        var command = new CountingExpiry();
        var plan = MutationPlan.compile(CountingExpiry.class, EntityMetadata.of(CountingExpiry.class).handlerMethods());
        plan.resolve(command);
        assertEquals(1, command.reads);
    }

    @Test
    void aDirectWriteIdKeepsPrecedenceOverAQualifiedSameTypeAncestorRead() {
        fixture = TestFixture.create();
        var root = new NestedTicketId("nested-root");
        var child = new NestedTicketId("nested-child");
        fixture.givenCommands(new SeedNestedTicket(root, null, true), new SeedNestedTicket(child, root, false))
                .whenCommand(new CopyParentState(child))
                .expectSuccessfulResult().expectEvents(new CopyParentState(child))
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of(child.toString()));
                    assertTrue(Fluxzero.loadModel(child).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(root.toString()).count());
                }).expectNoErrors();
    }

    @Test
    void anExplicitGraphTargetDoesNotRequireTheChildSelectionRoot() {
        fixture = TestFixture.create();
        fixture.givenCommands(new SetTicket(ticketId, true))
                .whenExecuting(fc -> Fluxzero.loadGraph(ticketId).assertAndApply(new ExpireReservation(null)))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of(ticketId.toString()));
                    assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
                }).expectNoErrors();
    }

    @Test
    void updatesAGrandparentAndReplaysThroughTheIntermediateRelation() {
        fixture = TestFixture.create();
        var linkId = new LinkId("link");
        var leafId = new LeafId("leaf");
        fixture.givenCommands(new SetTicket(ticketId, true), new CreateLink(linkId, ticketId),
                              new CreateLeaf(leafId, linkId))
                .whenCommand(new ReleaseThroughLeaf(leafId))
                .expectSuccessfulResult().expectEvents(new ReleaseThroughLeaf(leafId))
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of(ticketId.toString()));
                    assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(linkId.toString()).count());
                    assertEquals(1, fc.eventStore().getEvents(leafId.toString()).count());
                }).expectNoErrors();
    }

    @Test
    void returningNullMayDeleteTheSelectedParentAndCascadeNormally() {
        fixture = TestFixture.create();
        fixture.givenCommands(new CreateReservation(reservationId, ticketId))
                .whenCommand(new DeleteParent(reservationId))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(
                            List.of(ticketId.toString(), reservationId.toString()));
                    assertNull(Fluxzero.loadModel(ticketId).get());
                    assertNull(Fluxzero.loadModel(reservationId).get());
                }).expectNoErrors();
    }

    @Test
    void replayOfAnAncestorWriteRetainsADifferentlyTypedSiblingRead() {
        fixture = TestFixture.create();
        var link = new LinkId("sibling");
        fixture.givenCommands(new SetTicket(ticketId, true), new CreateLink(link, null),
                              new CreateMixedParents("mixed", ticketId, link))
                .whenCommand(new ReleaseWithSibling("mixed"))
                .expectSuccessfulResult().expectEvents(new ReleaseWithSibling("mixed"))
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of(ticketId.toString()));
                    assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
                    assertEquals(1, fc.eventStore().getEvents(link.toString()).count());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @EnumSource(value = ModelConflictPolicy.class, names = {"ACCEPT", "RETRY", "FAIL"})
    @Timeout(15)
    void reparentingBetweenEvaluationAndCommitUsesTheFreshParentOrFailsAtomically(ModelConflictPolicy policy) {
        GateClient client = new GateClient();
        var other = new TicketId("other-ticket");
        try (Fluxzero fc = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(policy, ModelConflictResolver.retryIfAllowed(), 3).build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fc, new CreateReservation(reservationId, ticketId));
            commit(fc, new SetTicket(other, true));
            ConflictExpiry.assertions.set(0);
            ConflictExpiry.applies.set(0);
            List<CommitModels> requests = new ArrayList<>();
            client.beforeCommit = request -> {
                if (request.getSubsteps().getFirst().getTargets().size() == 2) {
                    requests.add(request);
                    if (requests.size() == 1) {
                        commit(writer, new MoveReservation(reservationId, other));
                    }
                }
            };
            if (policy == ModelConflictPolicy.FAIL) {
                var failure = assertThrows(CompletionException.class,
                                           () -> commit(fc, new ConflictExpiry(reservationId)));
                assertInstanceOf(ModelCommitConflictException.class, failure.getCause());
                assertEquals(1, requests.size());
            } else {
                commit(fc, new ConflictExpiry(reservationId));
                assertEquals(2, requests.size());
                assertTrue(requests.get(1).getReadStateIndex() > requests.getFirst().getReadStateIndex());
                assertTrue(requests.get(1).getSubsteps().getFirst().getTargets().stream()
                                   .anyMatch(t -> t.getModelId().equals(other.toString())));
                assertFalse(requests.get(1).getSubsteps().getFirst().getTargets().stream()
                                    .anyMatch(t -> t.getModelId().equals(ticketId.toString())));
            }
            assertEquals(policy == ModelConflictPolicy.RETRY ? 2 : 1, ConflictExpiry.assertions.get());
            assertEquals(policy == ModelConflictPolicy.FAIL ? 1 : 2, ConflictExpiry.applies.get());
            assertTrue(requests.getFirst().getReadModelIds().contains(reservationId.toString()));
            assertTrue(requests.getFirst().getReadModelIds().contains(ticketId.toString()));
            client.beforeCommit = ignored -> {};
            // A new repository has no cached values or replay checkpoints from either writer.
            try (Fluxzero reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
                reader.apply(ignored -> {
                    assertTrue(Fluxzero.loadModel(ticketId).get().reserved(), "The former parent must not be updated");
                    assertEquals(policy == ModelConflictPolicy.FAIL, Fluxzero.loadModel(other).get().reserved());
                    assertEquals(policy == ModelConflictPolicy.FAIL ? new Reservation(reservationId, other) : null,
                                 Fluxzero.loadModel(reservationId).get());
                    return null;
                });
            }
        }
    }

    private static void commit(Fluxzero fc, Object command) {
        fc.apply(ignored -> fc.executeModelCommit(new Message(command)).join());
    }

    /** Changes transport timing only; conflict checking and state storage remain the real LocalClient implementation. */
    private static class GateClient extends LocalClient {
        Consumer<CommitModels> beforeCommit = ignored -> {};

        GateClient() { super(null); }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commitModels")) {
                            beforeCommit.accept((CommitModels) args[0]);
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private void assertExpired(Fluxzero fc, Object command) {
        assertNull(Fluxzero.loadModel(reservationId).get());
        assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
        for (var id : List.of(reservationId.toString(), ticketId.toString())) {
            assertEquals(List.of(new CreateReservation(reservationId, ticketId), command),
                         fc.eventStore().getEvents(id).map(event -> event.getPayload()).toList());
        }
        // Both streams must reconstruct independently after their cached values are gone.
        ((DefaultModelRepository) fc.modelRepository()).invalidateModels(
                List.of(reservationId.toString(), ticketId.toString()));
        assertFalse(Fluxzero.loadModel(ticketId).get().reserved());
        assertNull(Fluxzero.loadModel(reservationId).get());
    }

    @Model
    record Ticket(@EntityId TicketId ticketId, boolean reserved) {}

    @Model
    record Reservation(@EntityId ReservationId reservationId,
                       @Parent(pathInParent = "reservations") TicketId ticketId) {}

    record CreateReservation(ReservationId reservationId, TicketId ticketId) {
        @Apply
        Ticket createTicket() { return new Ticket(ticketId, true); }
        @Apply
        Reservation createReservation() { return new Reservation(reservationId, ticketId); }
    }

    record ExpireReservation(ReservationId reservationId) {
        @Apply
        Reservation remove(Reservation reservation) { return null; }
        @Apply
        Ticket release(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    record ExpireString(String reservationId) {
        @Apply
        Reservation remove(Reservation reservation) { return null; }
        @Apply
        Ticket release(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    record ConflictExpiry(ReservationId reservationId) {
        static final AtomicInteger assertions = new AtomicInteger();
        static final AtomicInteger applies = new AtomicInteger();
        @AssertLegal
        void check(Reservation reservation) { assertions.incrementAndGet(); }
        @Apply
        Reservation remove(Reservation reservation) { return null; }
        @Apply
        Ticket release(Ticket ticket) {
            applies.incrementAndGet();
            return new Ticket(ticket.ticketId(), false);
        }
    }

    static class CountingExpiry {
        int reads;
        public ReservationId getReservationId() {
            reads++;
            return new ReservationId("counting");
        }
        @Apply
        Reservation remove(Reservation reservation) { return null; }
        @Apply
        Ticket release(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    @Model
    record Link(@EntityId LinkId linkId, @Parent TicketId ticketId) {}

    static class LinkId extends Id<Link> {
        LinkId(String id) { super(id); }
    }

    @Model
    record Leaf(@EntityId LeafId leafId, @Parent LinkId linkId) {}

    static class LeafId extends Id<Leaf> {
        LeafId(String id) { super(id); }
    }

    record CreateLink(LinkId linkId, TicketId ticketId) {
        @Apply
        Link apply() { return new Link(linkId, ticketId); }
    }

    record CreateLeaf(LeafId leafId, LinkId linkId) {
        @Apply
        Leaf apply() { return new Leaf(leafId, linkId); }
    }

    record ReleaseThroughLeaf(LeafId leafId) {
        @Apply
        Ticket release(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    record DeleteParent(ReservationId reservationId) {
        @Apply
        Ticket delete(Ticket ticket) { return null; }
    }

    @Model
    record MixedParents(@EntityId String mixedId, @Parent TicketId ticketId, @Parent LinkId linkId) {}

    record CreateMixedParents(String mixedId, TicketId ticketId, LinkId linkId) {
        @Apply
        MixedParents apply() { return new MixedParents(mixedId, ticketId, linkId); }
    }

    record ReleaseWithSibling(String mixedId) {
        @AssertLegal
        void root(MixedParents root) { assertEquals(mixedId, root.mixedId()); }
        @Apply
        Ticket release(Ticket ticket, Link sibling) {
            assertEquals("sibling", sibling.linkId().toString());
            return new Ticket(ticket.ticketId(), false);
        }
    }

    record DynamicNoOp(ReservationId reservationId, TicketId auditTicketId) {
        @Apply
        Object apply(Reservation reservation, @Association("reservations") Ticket parent) {
            assertEquals(auditTicketId, parent.ticketId());
            return reservation;
        }
    }

    record CreateAnotherTicket(ReservationId reservationId, TicketId auditTicketId) {
        @Apply
        List<Ticket> apply(Reservation reservation, @Association("reservations") Ticket parent) {
            assertEquals(auditTicketId, parent.ticketId());
            return List.of(new Ticket(new TicketId("new-ticket"), false));
        }
    }

    record ExpireChildFirst(ReservationId reservationId) {
        @Apply
        Reservation aRemove(Reservation reservation) { return null; }
        @Apply
        Ticket zRelease(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    record ExpireWithGraph(ReservationId reservationId) {
        @Apply
        Reservation remove(Reservation reservation) { return null; }
        @Apply
        Ticket release(Graph<Ticket> ticket) { return new Ticket(ticket.get().ticketId(), false); }
    }

    record ReleaseShared(ReservationId first, ReservationId second) {
        @Apply
        Ticket release(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    record FailAfterChild(ReservationId reservationId) {
        @Apply
        Reservation aRemove(Reservation reservation) { return null; }
        @Apply
        Ticket zRelease(Ticket ticket) { throw new IllegalStateException("Rejected"); }
    }

    record WrongTicket(ReservationId reservationId, String otherId) {
        @AssertLegal
        void readOther(@Association("otherId") Ticket ticket) { assertTrue(ticket.reserved()); }
        @Apply
        Ticket release(@Association("reservations") Ticket ticket) { return new Ticket(new TicketId(otherId), false); }
    }

    @Model
    record NestedTicket(@EntityId NestedTicketId ticketId,
                        @Parent(pathInParent = "tickets") NestedTicketId parentId, boolean reserved) {}

    static class NestedTicketId extends Id<NestedTicket> {
        NestedTicketId(String id) { super(id); }
    }

    record SeedNestedTicket(NestedTicketId ticketId, NestedTicketId parentId, boolean reserved) {
        @Apply
        NestedTicket apply() { return new NestedTicket(ticketId, parentId, reserved); }
    }

    record CopyParentState(NestedTicketId ticketId) {
        @Apply
        NestedTicket apply(@Association("tickets") NestedTicket parent) {
            return new NestedTicket(ticketId, parent.ticketId(), parent.reserved());
        }
    }

    record MoveReservation(ReservationId reservationId, TicketId ticketId) {
        @Apply
        Reservation apply() { return new Reservation(reservationId, ticketId); }
    }

    record SetTicket(TicketId ticketId, boolean reserved) {
        @Apply
        Ticket apply() { return new Ticket(ticketId, reserved); }
    }

    @Model
    record Dual(@EntityId String dualId, @Parent(pathInParent = "primary") TicketId primary,
                @Parent(pathInParent = "secondary") TicketId secondary) {}

    record CreateDual(String dualId, TicketId primary, TicketId secondary) {
        @Apply
        Dual apply() { return new Dual(dualId, primary, secondary); }
    }

    record ReleasePrimary(String dualId) {
        @AssertLegal
        void readRoot(Dual dual) { assertEquals(dualId, dual.dualId()); }
        @Apply
        Ticket release(@Association("primary") Ticket primary, @Association("secondary") Ticket secondary) {
            assertTrue(secondary.reserved());
            return new Ticket(primary.ticketId(), false);
        }
    }

    record RemoveWithParentRead(ReservationId reservationId) {
        @Apply
        Reservation remove(Reservation reservation, Ticket ticket) {
            assertEquals(reservation.ticketId(), ticket.ticketId());
            assertTrue(ticket.reserved());
            return null;
        }
    }

    record ExpireWithDirectParent(ReservationId reservationId, TicketId ticketId) {
        @Apply
        Reservation remove(Reservation reservation) { return null; }
        @Apply
        Ticket release(Ticket ticket) { return new Ticket(ticket.ticketId(), false); }
    }

    static class TicketId extends Id<Ticket> {
        TicketId(String id) { super(id); }
    }

    static class ReservationId extends Id<Reservation> {
        ReservationId(String id) { super(id); }
    }
}
