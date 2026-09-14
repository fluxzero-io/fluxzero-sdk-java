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
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.persisting.repository.ModelGraphResolver;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphCurrentTest {
    @Test
    void preservesAffixedIdentityAndSourceWhenDeletedAndRecreated() {
        try (Fluxzero app = app()) {
            ProjectId id = new ProjectId("one");
            commit(app, new PutProject(id, 1));
            Graph<Project> before = Graphs.lazyCurrent(id, Project.class, app.modelRepository());
            assertEquals("model-project-one-state", before.id());
            commit(app, new DeleteProject(id));
            Graph<Project> deleted = before.current();
            assertEquals(before.id(), deleted.id());
            assertNull(deleted.get());
            commit(app, new PutProject(id, 2));
            Graph<Project> recreated = deleted.current();
            assertEquals(new Project(id, 2), recreated.get());
            assertNull(deleted.get());
            assertEquals(new Project(id, 1), before.get());
        }
    }

    @Test
    void retainsParentScopedIdentityAndOwningRepositoryOutsideOrInsideAnotherApplication() {
        try (Fluxzero first = app(); Fluxzero other = app()) {
            ProjectId id = new ProjectId("scope");
            commit(first, new PutProject(id, 1));
            commit(first, new PutNote("same", id, 1));
            commit(other, new PutProject(id, 9));
            commit(other, new PutNote("same", id, 9));
            Graph<Note> original = Graphs.lazy(id, Project.class, "same", Note.class, first.modelRepository());
            Object key = original.id();
            assertEquals(1, original.get().version());
            commit(first, new PutNote("same", id, 2));
            Graph<Note> current = other.apply(fc -> original.current());
            assertEquals(key, current.id());
            assertEquals(2, current.get().version());
            assertEquals(1, original.get().version());
            assertEquals(key, current.current().id());
        }
    }

    @Test
    void preservesNamespace() {
        LocalClient shared = LocalClient.newInstance();
        try (Fluxzero blue = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(shared.forNamespace("blue"));
             Fluxzero red = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(shared.forNamespace("red"))) {
            ProjectId id = new ProjectId("same");
            commit(blue, new PutProject(id, 1));
            commit(red, new PutProject(id, 9));
            Graph<Project> original = Graphs.lazy(id, Project.class, blue.modelRepository());
            assertEquals(1, original.get().version());
            commit(blue, new PutProject(id, 2));
            Graph<Project> current = red.apply(fc -> original.current());
            assertEquals(2, current.get().version());
            assertEquals(1, original.get().version());
            assertEquals(9, red.apply(fc -> Fluxzero.loadCurrentGraph(id)).get().version());
        }
    }

    @Test
    void nodeRefreshUsesItsNewParentsAndDropsViewOnlyTransformations() {
        try (Fluxzero app = app()) {
            ProjectId left = new ProjectId("left"), right = new ProjectId("right");
            commit(app, new PutProject(left, 1));
            commit(app, new PutProject(right, 1));
            commit(app, new PutDevice("device", left));
            Graph<Device> original = Graphs.lazy(left, Project.class, app.modelRepository())
                    .withContext("old-response").children(Device.class).getFirst();
            commit(app, new PutDevice("device", right));
            Graph<Device> current = original.current();
            assertEquals(original.id(), current.id());
            assertEquals("model-project-right-state", current.parent(Project.class).orElseThrow().id());
            assertEquals("model-project-left-state", original.parent(Project.class).orElseThrow().id());
            assertTrue(current.context(String.class).isEmpty());
            assertEquals("old-response", original.context(String.class).orElseThrow());
            Graph<Project> mapped = Graphs.mapValues(current.parent(Project.class).orElseThrow(),
                    graph -> graph.knownType().orElse(null) == Project.class ? new Project(right, 99) : graph.get())
                    .selectPaths(List.of());
            assertEquals(99, mapped.get().version());
            assertEquals(1, mapped.current().get().version());
            assertEquals(1, mapped.current().children(Device.class).size());
        }
    }

    @Test
    void unsupportedRepositoryFailsWithoutGlobalFallbackOrValueReads() {
        ModelRepository repository = mock(ModelRepository.class);
        Graph<Project> graph = Graphs.lazyRepositoryId("model-project-missing-state", Project.class, repository);
        assertThrows(UnsupportedOperationException.class, graph::current);
        verifyNoInteractions(repository);
        Graph<?> custom = mock(Graph.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, custom::current);
    }

    @Test
    void customResolverWithoutMetadataIdentityDoesNotReplayAnAliasedSourceToRejectCurrent() {
        ModelRepository repository = mock(ModelRepository.class,
                withSettings().extraInterfaces(ModelGraphResolver.class));
        Graph<Aliased> graph = Graphs.lazy("alias", Aliased.class, repository);
        assertThrows(UnsupportedOperationException.class, graph::current);
        verify((ModelGraphResolver) repository, never()).loadGraphValue(any(), anyBoolean(), any(), any());
        verify(repository, never()).load(any(), any(Class.class));
    }

    private static Fluxzero app() {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance());
    }
    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    @Model record Project(@EntityId(prefix = "model-", postfix = "-state") ProjectId projectId, int version) {}
    @Model record Aliased(@EntityId String id, @Alias String alias) {}
    static class ProjectId extends Id<Project> {
        ProjectId(String value) { super(value, "project-"); }
    }
    @Model record Note(@EntityId(parentScoped = true) String noteId,
                       @Parent(pathInParent = "notes") ProjectId projectId, int version) {}
    @Model record Device(@EntityId String deviceId, @Parent(pathInParent = "devices") ProjectId projectId) {}
    record PutProject(ProjectId projectId, int version) {
        @Apply Project apply(@Nullable Project current) { return new Project(projectId, version); }
    }
    record DeleteProject(ProjectId projectId) {
        @Apply Project apply(Project current) { return null; }
    }
    record PutNote(String noteId, ProjectId projectId, int version) {
        @Apply Note apply(@Nullable Note current) { return new Note(noteId, projectId, version); }
    }
    record PutDevice(String deviceId, ProjectId projectId) {
        @Apply Device apply(@Nullable Device current) { return new Device(deviceId, projectId); }
    }
}
