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

import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ParentConstraintPlanTest {
    private TestFixture fixture;

    private TestFixture fixture(boolean async) {
        return fixture = async ? TestFixture.createAsync() : TestFixture.create();
    }

    @AfterEach
    void close() {
        if (fixture != null) { fixture.getFluxzero().close(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsMissingAndAbsentRequiredParent(boolean async) {
        fixture(async).whenCommand(new CreateChild("missing", null))
                .expectExceptionalResult(ModelErrors.parentRequired(Root.class));
        fixture.whenCommand(new CreateChild("absent", "p"))
                .expectExceptionalResult(ModelErrors.parentNotFound(Root.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void capacityUsesFinalRelationshipState(boolean async) {
        fixture(async).givenCommands(new CreateRoot("p"), new CreateRoot("q"),
                                     new CreateChild("first", "p"), new CreateChild("second", "q"))
                .whenCommand(new MoveChild("second", "p"))
                .expectExceptionalResult(ModelErrors.maxChildrenExceeded(Root.class, Child.class))
                .expectThat(f -> assertEquals("q", f.modelRepository().load("second", Child.class).get().rootId()));
        fixture.whenCommand(new MoveChild("first", "q")).expectExceptionalResult(
                ModelErrors.maxChildrenExceeded(Root.class, Child.class));
        fixture.whenCommand(new DeleteChild("first")).expectSuccessfulResult();
        fixture.whenCommand(new MoveChild("second", "p")).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deletionProtectionIsAnSdkCheckAndOverridesCascade(boolean async) {
        fixture(async).givenCommands(new CreateRoot("p"), new CreateChild("c", "p"))
                .whenCommand(new DeleteRoot("p"))
                .expectExceptionalResult(ModelErrors.parentDeletionBlocked(Root.class, Child.class))
                .expectThat(f -> {
                    assertNotNull(f.modelRepository().load("p", Root.class).get());
                    assertNotNull(f.modelRepository().load("c", Child.class).get());
                });
        fixture.whenCommand(new DeleteChild("c")).expectSuccessfulResult();
        fixture.whenCommand(new DeleteRoot("p")).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void requiredChildStillUsesOrdinaryCascade(boolean async) {
        fixture(async).givenCommands(new CreateRoot("p"), new CreateRequiredChild("c", "p"))
                .whenCommand(new DeleteRoot("p"))
                .expectSuccessfulResult()
                .expectThat(f -> {
                    assertTrue(f.modelRepository().load("p", Root.class).isEmpty());
                    assertTrue(f.modelRepository().load("c", RequiredChild.class).isEmpty());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void survivingRequiredChildBlocksParentDeletion(boolean async) {
        fixture(async).givenCommands(new CreateRoot("p"), new CreateRetainedChild("c", "p"))
                .whenCommand(new DeleteRoot("p"))
                .expectExceptionalResult(ModelErrors.parentDeletionBlocked(Root.class, RetainedChild.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deletionChecksTheConstrainedReferenceWhenPathsOverlap(boolean async) {
        fixture(async).givenCommands(
                        new CreateRoot("required"), new CreateRoot("optional"),
                        new CreateRoleChild("child", "required", "optional"))
                .whenCommand(new DeleteRoot("optional"))
                .expectSuccessfulResult()
                .expectThat(f -> assertNotNull(
                        f.modelRepository().load("child", RoleChild.class).get()));
        fixture.whenCommand(new DeleteRoot("required"))
                .expectExceptionalResult(ModelErrors.parentDeletionBlocked(Root.class, RoleChild.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void collectionRequiredMeansNonEmptyAndAllParentsExist(boolean async) {
        fixture(async).givenCommands(new CreateRoot("p"))
                .whenCommand(new CreateGroup("g", java.util.List.of()))
                .expectExceptionalResult(ModelErrors.parentRequired(Root.class));
        fixture.whenCommand(new CreateGroup("g", java.util.List.of("p", "absent")))
                .expectExceptionalResult(ModelErrors.parentNotFound(Root.class));
        fixture.whenCommand(new CreateGroup("g", java.util.List.of("p"))).expectSuccessfulResult();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void collectionMayDetachDeletedParentWhenAnotherRequiredParentRemains(boolean async) {
        fixture(async).givenCommands(
                        new CreateRoot("p"), new CreateRoot("q"),
                        new CreateProtectedGroup("g", java.util.List.of("p", "q")))
                .whenCommand(new DetachAndDelete("g", "p", "q"))
                .expectSuccessfulResult()
                .expectThat(f -> {
                    assertTrue(f.modelRepository().load("p", Root.class).isEmpty());
                    assertEquals(java.util.List.of("q"),
                                 f.modelRepository().load("g", ProtectedGroup.class).get().rootIds());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void parentAndChildMayBeCreatedAndDeletedTogether(boolean async) {
        fixture(async).whenCommand(new CreatePair("p", "c")).expectSuccessfulResult()
                .expectThat(f -> {
                    assertNotNull(f.modelRepository().load("p", Root.class).get());
                    assertNotNull(f.modelRepository().load("c", Child.class).get());
                });
        fixture.whenCommand(new DeletePair("p", "c")).expectSuccessfulResult()
                .expectThat(f -> {
                    assertTrue(f.modelRepository().load("p", Root.class).isEmpty());
                    assertTrue(f.modelRepository().load("c", Child.class).isEmpty());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void finalStateIncludesEverySubstepInOneCommit(boolean async) {
        fixture(async).whenCommand(new CreateOverCapacityPair("p", "first", "second"))
                .expectExceptionalResult(ModelErrors.maxChildrenExceeded(Root.class, Child.class))
                .expectThat(f -> {
                    assertTrue(f.modelRepository().load("p", Root.class).isEmpty());
                    assertTrue(f.modelRepository().load("first", Child.class).isEmpty());
                    assertTrue(f.modelRepository().load("second", Child.class).isEmpty());
                });
    }

    @org.junit.jupiter.api.Test
    void rejectsInvalidOrUntypedConstraintDeclarations() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> EntityMetadata.of(InvalidLimit.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> EntityMetadata.of(UntypedRequired.class));
        var protectedRelationship = EntityMetadata.of(DuplicateProtectedChild.class)
                .parentRelationships("child", new DuplicateProtectedChild("child", "p", "p"));
        assertEquals(1, protectedRelationship.size());
        org.junit.jupiter.api.Assertions.assertFalse(
                protectedRelationship.getFirst().deleteOnParentDeletion());
    }

    @org.junit.jupiter.api.Test
    void shorthandUsesOrdinaryModelAndRelationshipReadDependencies() {
        var client = new LocalClient(null) {
            @Override protected EventStoreClient createEventStoreClient() {
                return spy(super.createEventStoreClient());
            }
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.executeModelCommit(new Message(new CreateRoot("p"))).join();
            clearInvocations(client.getEventStoreClient());

            app.executeModelCommit(new Message(new CreateChild("c", "p"))).join();

            var request = org.mockito.ArgumentCaptor.forClass(
                    io.fluxzero.common.api.modeling.CommitModels.class);
            verify(client.getEventStoreClient(), times(1)).commitModels(request.capture());
            assertTrue(request.getValue().getReadModelIds().contains("p"));
            assertTrue(request.getValue().getReadRelationships().stream().anyMatch(read ->
                    read.modelId().equals("p")
                    && read.direction() == io.fluxzero.common.api.modeling.ModelRelationshipRead.Direction.CHILDREN));
        }
    }

    @org.junit.jupiter.api.Test
    void unconstrainedParentAddsNoParentOrRelationshipReads() {
        var client = new LocalClient(null) {
            @Override protected EventStoreClient createEventStoreClient() {
                return spy(super.createEventStoreClient());
            }
        };
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.executeModelCommit(new Message(new CreateRoot("p"))).join();
            clearInvocations(client.getEventStoreClient());

            app.executeModelCommit(new Message(new CreateOptionalChild("c", "p"))).join();

            var request = org.mockito.ArgumentCaptor.forClass(
                    io.fluxzero.common.api.modeling.CommitModels.class);
            verify(client.getEventStoreClient(), times(1)).commitModels(request.capture());
            org.junit.jupiter.api.Assertions.assertFalse(
                    request.getValue().getReadModelIds().contains("p"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    request.getValue().getReadRelationships().isEmpty());
        }
    }

    @Model private record Root(@EntityId String id) {}
    @Model private record Child(
            @EntityId String id,
            @Parent(value = Root.class, required = true, maxChildren = 1,
                    preventParentDeletion = true) String rootId) {}
    @Model private record RequiredChild(
            @EntityId String id, @Parent(value = Root.class, required = true) String rootId) {}
    @Model private record RetainedChild(
            @EntityId String id,
            @Parent(value = Root.class, required = true, deleteOnParentDeletion = false) String rootId) {}
    @Model private record Group(
            @EntityId String id, @Parent(value = Root.class, required = true) java.util.List<String> rootIds) {}
    @Model private record ProtectedGroup(
            @EntityId String id,
            @Parent(value = Root.class, required = true, preventParentDeletion = true)
            java.util.List<String> rootIds) {}
    @Model private record OptionalChild(
            @EntityId String id, @Parent(Root.class) String rootId) {}
    @Model private record RoleChild(
            @EntityId String id,
            @Parent(value = Root.class, required = true, deleteOnParentDeletion = false) String requiredRoot,
            @Parent(value = Root.class, deleteOnParentDeletion = false) String optionalRoot) {}
    @Model private record DuplicateProtectedChild(
            @EntityId String id,
            @Parent(value = Root.class, preventParentDeletion = true) String protectedRoot,
            @Parent(Root.class) String owningRoot) {}
    @Model private record InvalidLimit(
            @EntityId String id, @Parent(value = Root.class, maxChildren = -2) String rootId) {}
    @Model private record UntypedRequired(
            @EntityId String id, @Parent(required = true) String rootId) {}

    private record CreateRoot(String id) { @Apply Root apply() { return new Root(id); } }
    private record DeleteRoot(String id) { @Apply Root apply(Root current) { return null; } }
    private record CreateChild(String id, String rootId) { @Apply Child apply() { return new Child(id, rootId); } }
    private record MoveChild(String id, String rootId) {
        @Apply Child apply(Child current) { return new Child(id, rootId); }
    }
    private record DeleteChild(String id) { @Apply Child apply(Child current) { return null; } }
    private record CreateRequiredChild(String id, String rootId) {
        @Apply RequiredChild apply() { return new RequiredChild(id, rootId); }
    }
    private record CreateRetainedChild(String id, String rootId) {
        @Apply RetainedChild apply() { return new RetainedChild(id, rootId); }
    }
    private record CreateGroup(String id, java.util.List<String> rootIds) {
        @Apply Group apply() { return new Group(id, rootIds); }
    }
    private record CreateProtectedGroup(String id, java.util.List<String> rootIds) {
        @Apply ProtectedGroup apply() { return new ProtectedGroup(id, rootIds); }
    }
    private record DetachAndDelete(String groupId, String deletedRootId, String retainedRootId) {
        @InterceptApply java.util.List<?> split() {
            return java.util.List.of(
                    new UpdateProtectedGroup(groupId, java.util.List.of(retainedRootId)),
                    new DeleteRoot(deletedRootId));
        }
    }
    private record UpdateProtectedGroup(String id, java.util.List<String> rootIds) {
        @Apply ProtectedGroup apply(ProtectedGroup current) { return new ProtectedGroup(id, rootIds); }
    }
    private record CreateOptionalChild(String id, String rootId) {
        @Apply OptionalChild apply() { return new OptionalChild(id, rootId); }
    }
    private record CreateRoleChild(String id, String requiredRoot, String optionalRoot) {
        @Apply RoleChild apply() { return new RoleChild(id, requiredRoot, optionalRoot); }
    }
    private record CreatePair(String rootId, String childId) {
        @InterceptApply java.util.List<?> split() {
            return java.util.List.of(new CreateRoot(rootId), new CreateChild(childId, rootId));
        }
    }
    private record DeletePair(String rootId, String childId) {
        @InterceptApply java.util.List<?> split() {
            return java.util.List.of(new DeleteChild(childId), new DeleteRoot(rootId));
        }
    }
    private record CreateOverCapacityPair(String rootId, String firstId, String secondId) {
        @InterceptApply java.util.List<?> split() {
            return java.util.List.of(
                    new CreateRoot(rootId),
                    new CreateChild(firstId, rootId),
                    new CreateChild(secondId, rootId));
        }
    }
}
