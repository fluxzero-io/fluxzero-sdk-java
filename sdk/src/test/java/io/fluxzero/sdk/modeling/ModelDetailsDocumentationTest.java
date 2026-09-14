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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.With;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exercises the Java Model-state documentation's complete details/command example. */
class ModelDetailsDocumentationTest {
    private final ProjectId projectId = new ProjectId("details-project");
    private final Sender owner = new Sender(new UserId("owner"));
    private final ProjectDetails details = new ProjectDetails("Launch", "Keep this description");

    private TestFixture fixture(boolean async) {
        var builder = DefaultFluxzero.builder().registerUserProvider(new AbstractUserProvider(Sender.class) {
            @Override
            public User getUserById(Object id) {
                return id instanceof Sender sender ? sender : new Sender(new UserId(id.toString()));
            }

            @Override
            public User getSystemUser() { return owner; }
        });
        return async ? TestFixture.createAsync(builder) : TestFixture.create(builder);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsWithValidatedDetails(boolean async) {
        fixture(async).whenCommandByUser(owner, new CreateProject(projectId, details))
                .expectSuccessfulResult()
                .expectThat(fc -> assertEquals(new Project(projectId, details, owner.userId()),
                                              Fluxzero.loadModel(projectId).get()))
                .expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renamePreservesOtherDetailsAndOwnership(boolean async) {
        var rename = new RenameProject(projectId, "Release");
        fixture(async).givenCommandsByUser(owner, new CreateProject(projectId, details))
                .whenCommandByUser(owner, rename)
                .expectSuccessfulResult().expectEvents(rename)
                .expectThat(fc -> assertEquals(new Project(projectId, details.withName("Release"), owner.userId()),
                                              Fluxzero.loadModel(projectId).get()))
                .expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsInvalidNestedCreationDetails(boolean async) {
        for (var invalid : new ProjectDetails[]{new ProjectDetails("", null),
                new ProjectDetails("Launch", "x".repeat(501)), null}) {
            fixture(async).whenCommandByUser(owner, new CreateProject(projectId, invalid))
                    .expectExceptionalResult(ValidationException.class).expectNoEvents()
                    .expectThat(fc -> assertNull(Fluxzero.loadModel(projectId).get()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidRenameDoesNotChangeState(boolean async) {
        fixture(async).givenCommandsByUser(owner, new CreateProject(projectId, details))
                .whenCommandByUser(owner, new RenameProject(projectId, ""))
                .expectExceptionalResult(ValidationException.class).expectNoEvents()
                .expectThat(fc -> assertEquals(details, Fluxzero.loadModel(projectId).get().details()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unauthorizedRenameDoesNotChangeState(boolean async) {
        fixture(async).givenCommandsByUser(owner, new CreateProject(projectId, details))
                .whenCommandByUser(new Sender(new UserId("other")), new RenameProject(projectId, "Release"))
                .expectExceptionalResult(UnauthorizedException.class).expectNoEvents()
                .expectThat(fc -> assertEquals(new Project(projectId, details, owner.userId()),
                                              Fluxzero.loadModel(projectId).get()));
    }

    @Model
    @With
    public record Project(
            @EntityId ProjectId projectId,
            ProjectDetails details,
            UserId ownerId) {
    }

    @With
    public record ProjectDetails(
            @NotBlank String name,
            @Size(max = 500) String description) {
    }

    public record CreateProject(@NotNull ProjectId projectId,
                                @NotNull @Valid ProjectDetails details) {
        @Apply
        Project apply(Sender sender) {
            return new Project(projectId, details, sender.userId());
        }
    }

    public record RenameProject(@NotNull ProjectId projectId,
                                @NotBlank String name) {
        @AssertLegal
        void assertOwner(Project project, Sender sender) {
            if (!project.ownerId().equals(sender.userId())) {
                throw new UnauthorizedException("Not allowed to rename project");
            }
        }

        @Apply
        Project apply(Project project) {
            return project.withDetails(project.details().withName(name));
        }
    }

    public static class ProjectId extends Id<Project> {
        public ProjectId(String value) { super(value); }
    }

    public static class UserId extends Id<Sender> {
        public UserId(String value) { super(value); }
    }

    public record Sender(UserId userId) implements User {
        @Override
        public String id() { return userId.toString(); }

        @Override
        public boolean hasRole(String role) { return false; }
    }
}
