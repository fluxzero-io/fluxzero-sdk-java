/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
 */

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MissingMemberLookupTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentMemberLookupPreservesIdentityWithoutCreatingARoot(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new CheckAbsent()).expectSuccessfulResult().expectNoEvents().expectNoErrors();
            assertEquals(0, app.eventStore().getEvents("missing").count());
            assertTrue(app.aggregateRepository().getAggregatesFor("missing").isEmpty());
            assertTrue(app.snapshotStore().getSnapshot("missing").isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberCanBeCreatedUpdatedAndRemovedThroughItsOwnerAfterAnEmptyLookup(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new CheckLifecycle()).expectSuccessfulResult().expectNoErrors();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleOwnerMappingDoesNotTurnAnAbsentMemberIntoARoot(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
        try (var app = fixture.getFluxzero()) {
            fixture.given(fc -> {
                        Fluxzero.loadAggregate("owner", Root.class)
                                .update(v -> new Root("owner", List.of(), List.of())).commit();
                        fc.client().getEventStoreClient().updateRelationships(new UpdateRelationships(
                                Set.of(Relationship.builder().entityId("missing").aggregateId("owner")
                                        .aggregateType(Root.class.getName()).build()),
                                Set.of(), Guarantee.STORED)).join();
                    })
                    .whenCommand(new CheckAbsent()).expectSuccessfulResult().expectNoEvents().expectNoErrors();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storedUnmarkedRootsStillFailEntityLookup(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        try (var app = fixture.getFluxzero()) {
            fixture.given(fc -> fc.eventStore().storeEvents("missing", new Message(new CreateChild())
                            .addMetadata(Entity.AGGREGATE_TYPE_METADATA_KEY, Child.class.getName())).join())
                    .whenApplying(fc -> Fluxzero.loadEntity("missing", Child.class))
                    .expectExceptionalResult(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingMemberLookupsRemainInertDuringReplay(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        try (var app = fixture.getFluxzero()) {
            fixture.given(fc -> fc.eventStore().storeEvents("replayed", new Message(new ReplayLookup())).join())
                    .whenApplying(fc -> Fluxzero.loadAggregate("replayed", Root.class).get())
                    .expectResult(new Root("replayed", List.of(), List.of())).expectNoEvents().expectNoErrors();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableHistoricalRootTypesKeepTheirEventSourcingFailure(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        try (var app = fixture.getFluxzero()) {
            fixture.given(fc -> {
                        var event = new SerializedMessage(new Data<>(new byte[]{'{', '}'},
                                "missing.LegacyEvent", 0, Data.JSON_FORMAT),
                                Metadata.of(Entity.AGGREGATE_TYPE_METADATA_KEY, "missing.LegacyRoot"),
                                "historical", Fluxzero.currentTime().toEpochMilli());
                        fc.client().getEventStoreClient().storeEvents("unknown", List.of(event), false).join();
                        fc.client().getEventStoreClient().updateRelationships(new UpdateRelationships(Set.of(
                                Relationship.builder().entityId("unknown").aggregateId("unknown")
                                        .aggregateType("missing.LegacyRoot").build()), Set.of(), Guarantee.STORED)).join();
                    })
                    .whenApplying(fc -> Fluxzero.loadAggregateFor("unknown", Root.class))
                    .expectExceptionalResult(EventSourcingException.class);
        }
    }

    record ReplayLookup() {
        @Apply Root apply() {
            assertTrue(Entity.isLoading());
            var member = Fluxzero.loadEntity("missing", Child.class);
            assertTrue(member.isEmpty());
            assertEquals(Child.class, member.type());
            member.update(v -> { throw new AssertionError("Replay must not mutate other entities"); });
            member.apply(new CreateChild());
            member.assertLegal(new RewriteValidation());
            return new Root("replayed", List.of(), List.of());
        }
    }

    record CheckAbsent() {}
    record CheckLifecycle() {}
    record Child(@EntityId String id, String value) {}
    record AffixedChild(@EntityId(prefix = "child-") String id) {}
    @Aggregate
    interface RootType {}
    record Root(@EntityId String id, @Member List<Child> children, @Member List<AffixedChild> affixed)
            implements RootType {}
    @Aggregate
    static class AnnotatedContainer {
        record NestedMember(@EntityId String id) {}
    }
    record RewriteValidation() {
        @InterceptApply Object intercept() { return new ValidateAbsence(); }
    }
    record ValidateAbsence() {
        @AssertLegal void check(Entity<Child> child) {
            throw new IllegalStateException("validated " + child.id());
        }
    }
    record CreateChild() {
        @Apply Child apply() { return new Child("missing", "illegal root"); }
    }

    static class Handler {
        @HandleCommand
        void handle(CheckAbsent command) {
            var id = new Id<Child>("missing", Child.class) {};
            for (Entity<Child> entity : List.of(Fluxzero.loadEntity(id), Fluxzero.<Child>loadEntity((Object) id),
                                               Fluxzero.loadEntity("missing", Child.class))) {
                assertEquals("missing", entity.id().toString());
                assertEquals(Child.class, entity.type());
                assertEquals("id", entity.idProperty());
                assertTrue(entity.isEmpty());
                assertFalse(entity.isPresent());
                assertNull(entity.get());
                assertTrue(entity.entities().isEmpty());
                assertTrue(entity.relationships().isEmpty());
                assertThrows(IllegalArgumentException.class, () -> entity.update(v -> new Child("missing", "bad")));
                assertThrows(IllegalArgumentException.class, () -> entity.apply(new CreateChild()));
                assertThrows(IllegalArgumentException.class,
                             () -> entity.withType(Child.class).update(v -> new Child("missing", "bad")));
                assertThrows(IllegalArgumentException.class,
                             () -> entity.withSequenceNumber(1).apply(new CreateChild()));
                assertEquals("validated missing", assertThrows(IllegalStateException.class,
                             () -> entity.assertLegal(new RewriteValidation())).getMessage());
                assertTrue(entity.commit().isEmpty());
            }
            assertNull(Fluxzero.loadEntityValue(id));
            assertNull(Fluxzero.loadEntityValue((Object) id));
            assertNull(Fluxzero.loadEntityValue("missing", Child.class));
            assertTrue(Fluxzero.loadEntity("missing", AffixedChild.class).isEmpty());
            var nested = Fluxzero.loadEntity("nested", AnnotatedContainer.NestedMember.class);
            assertTrue(nested.isEmpty());
            assertEquals(AnnotatedContainer.NestedMember.class, nested.type());
            assertThrows(IllegalArgumentException.class,
                         () -> nested.update(v -> new AnnotatedContainer.NestedMember("nested")));
            assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadAggregate(id));
            assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadAggregateFor("unowned", Child.class));
        }

        @HandleCommand
        void handle(CheckLifecycle command) {
            assertTrue(Fluxzero.loadEntity("child", Child.class).isEmpty());
            var root = Fluxzero.loadEntity("root", Root.class);
            root.update(v -> new Root("root", List.of(new Child("child", "initial")),
                                      List.of(new AffixedChild("affixed"))));
            Entity<Child> child = Fluxzero.loadEntity("child", Child.class);
            assertEquals("root", child.root().id());
            assertEquals(new Child("child", "initial"), child.get());
            child.update(v -> new Child(v.id(), "updated"));
            assertEquals("updated", root.get().children().getFirst().value());
            root.commit();
            assertEquals(new AffixedChild("affixed"), Fluxzero.loadEntity("affixed", AffixedChild.class).get());
            root.update(v -> new Root(v.id(), List.of(), v.affixed()));
            assertTrue(Fluxzero.loadEntity("child", Child.class).isEmpty());
        }
    }
}
