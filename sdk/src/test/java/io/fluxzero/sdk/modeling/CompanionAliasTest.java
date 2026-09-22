/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanionAliasTest {
    enum Read { MODEL, CURRENT_MODEL, GRAPH, CURRENT_GRAPH, GRAPH_IDENTITY }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coldAliasCombinesFallbackHeadWithPrimaryRead(boolean graph) {
        var client = spy(LocalClient.newInstance(null));
        var store = spy(client.getEventStoreClient());
        doReturn(store).when(client).getEventStoreClient();
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            app.apply(fc -> {
                var id = new ProjectId("one");
                fc.executeModelCommit(Message.asMessage(new CreateProject(id))).join();
                fc.executeModelCommit(Message.asMessage(new CreateDetails(id, "lookup"))).join();
                ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("details-" + id));
                clearInvocations(store);
                assertEquals(new Details(id, "lookup"), read("lookup", graph ? Read.GRAPH : Read.MODEL));
                var requests = org.mockito.ArgumentCaptor.forClass(GetModelEvents.class);
                verify(store, times(2)).getModelEvents(requests.capture());
                var first = requests.getAllValues().getFirst();
                assertEquals(List.of("details-project-lookup", "lookup"), first.getRequests().stream()
                        .map(r -> r.getModelId()).toList());
                assertEquals(0, first.getRequests().getLast().getMaxSize());
                assertNotNull(requests.getAllValues().getLast().getBoundary().stateIndex());
                return null;
            });
        }
    }

    static Stream<Object[]> reads() {
        return Stream.of(false, true).flatMap(async -> Stream.of(false, true).flatMap(cold ->
                Stream.of(Read.values()).map(read -> new Object[]{async, cold, read})));
    }

    @ParameterizedTest
    @MethodSource("reads")
    void absentCompanionDoesNotLoadParent(boolean async, boolean cold, Read read) {
        var id = new ProjectId("one");
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new CreateProject(id))
                .whenExecuting(fc -> {
                    if (cold) {
                        ((DefaultModelRepository) fc.modelRepository()).invalidateModels(
                                List.of(id.toString(), "details-" + id));
                    }
                    assertNull(read(id, read));
                    assertNotNull(Fluxzero.loadModel(id).get());
                    assertNull(Fluxzero.loadModel(id, PlainDetails.class).get());
                    assertNull(Fluxzero.loadGraph(id, PlainDetails.class).get());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @MethodSource("reads")
    void presentCompanionAndRealAliasStillResolve(boolean async, boolean cold, Read read) {
        var id = new ProjectId("one");
        var details = new Details(id, "lookup");
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new CreateProject(id), new CreateDetails(id, "lookup"))
                .whenExecuting(fc -> {
                    if (cold) {
                        ((DefaultModelRepository) fc.modelRepository()).invalidateModels(
                                List.of(id.toString(), "details-" + id));
                    }
                    assertEquals(details, read(id, read));
                    assertEquals(details, read("lookup", read));
                    // Object-typed canonical IDs have always been accepted via the undecorated fallback.
                    assertEquals(details, read("details-" + id, read));
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitWrongTypeAndWrongTypeAliasStillFail(boolean async) {
        var id = new ProjectId("one");
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new CreateProject(id), new CreateOther("other", "wrong-alias"))
                .whenExecuting(fc -> {
                    assertThrows(EventSourcingException.class,
                            () -> fc.modelRepository().load(id.toString(), Details.class));
                    assertThrows(EventSourcingException.class,
                            () -> fc.modelRepository().loadCurrent(id.toString(), Details.class));
                    for (Read read : Read.values()) {
                        assertThrows(EventSourcingException.class, () -> read("wrong-alias", read));
                    }
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historicalEventReadStaysAbsentAfterNestedCompanionCreation(boolean async) {
        Object observer = new Object() {
            @HandleEvent void observe(CreateProject event) {
                Fluxzero.sendCommandAndWait(new CreateDetails(event.projectId(), "later"));
                assertNull(read(event.projectId(), Read.MODEL));
                assertNull(read(event.projectId(), Read.GRAPH));
                assertNotNull(read(event.projectId(), Read.CURRENT_MODEL));
                assertNotNull(read(event.projectId(), Read.CURRENT_GRAPH));
            }
        };
        (async ? TestFixture.createAsync(observer) : TestFixture.create(observer))
                .whenCommand(new CreateProject(new ProjectId("event")))
                .expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentCompanionDoesNotRequireTheParentsLocalTypeRegistration(boolean async) {
        var id = new ProjectId("foreign");
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new CreateProject(id))
                .whenExecuting(fc -> {
                    var serializer = new io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer();
                    var repository = new DefaultModelRepository(fc.client(), fc.documentStore(), serializer,
                            new DefaultEntityHelper(List.of(), false), serializer,
                            io.fluxzero.common.caching.NoOpCache.INSTANCE, List.of());
                    assertTrue(repository.knownModelType("Project", id.toString()).isEmpty());
                    assertNull(repository.load(id, Details.class).get());
                    assertNull(repository.loadCurrent((Object) id, Details.class).get());
                    assertNull(Graphs.lazy(id, Details.class, repository).get());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentCompanionKeepsItsCurrentAuthority(boolean async) {
        var id = new ProjectId("document");
        var value = new DocumentDetails(id, "document-code");
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new CreateProject(id))
                .whenExecuting(fc -> {
                    assertNull(Fluxzero.loadModel(id, DocumentDetails.class).get());
                    assertNull(Fluxzero.loadCurrentGraph(id, DocumentDetails.class).get());
                    Fluxzero.assertAndApply(new CreateDocumentDetails(id, "document-code"));
                    assertEquals(value, Fluxzero.loadModel((Object) ("document-" + id), DocumentDetails.class).get());
                    assertEquals(value, fc.modelRepository().loadCurrent((Object) ("document-" + id), DocumentDetails.class).get());
                    assertEquals(value, Fluxzero.loadModel((Object) "document-code", DocumentDetails.class).get());
                    assertEquals(value, Fluxzero.loadCurrentGraph("document-code", DocumentDetails.class).get());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @MethodSource("reads")
    void deletedCompanionDoesNotFallBackToParent(boolean async, boolean cold, Read read) {
        var id = new ProjectId("deleted");
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new CreateProject(id), new CreateDetails(id, "removed"), new RemoveDetails(id))
                .whenExecuting(fc -> {
                    if (cold) {
                        ((DefaultModelRepository) fc.modelRepository()).invalidateModels(
                                List.of(id.toString(), "details-" + id));
                    }
                    assertNull(read(id, read));
                    assertNull(read("removed", read));
                }).expectSuccessfulResult().expectNoErrors();
    }

    private static Object read(Object id, Read read) {
        return switch (read) {
            case MODEL -> Fluxzero.loadModel(id, Details.class).get();
            case CURRENT_MODEL -> Fluxzero.get().modelRepository().loadCurrent(id, Details.class).get();
            case GRAPH -> Fluxzero.loadGraph(id, Details.class).get();
            case CURRENT_GRAPH -> Fluxzero.loadCurrentGraph(id, Details.class).get();
            case GRAPH_IDENTITY -> {
                var graph = Fluxzero.loadGraph(id, Details.class);
                assertNotNull(graph.id());
                yield graph.get();
            }
        };
    }

    static final class ProjectId extends Id<Project> {
        ProjectId(String value) { super(value, "project-"); }
    }
    @Model record Project(@EntityId ProjectId projectId) {}
    @Model record Details(@EntityId(prefix = "details-") @Parent(pathInParent = "details") ProjectId projectId,
                          @Alias String alias) {}
    @Model record Other(@EntityId String otherId, @Alias String alias) {}
    @Model record PlainDetails(@EntityId(prefix = "plain-") @Parent ProjectId projectId) {}
    @Model(persistence = ModelPersistence.DOCUMENT)
    record DocumentDetails(@EntityId(prefix = "document-") ProjectId projectId, @Alias String alias) {}
    record CreateOther(String otherId, String alias) {
        @Apply Other create() { return new Other(otherId, alias); }
    }
    record CreateProject(ProjectId projectId) {
        @Apply Project create() { return new Project(projectId); }
    }
    record CreateDetails(ProjectId projectId, String alias) {
        @Apply Details create() { return new Details(projectId, alias); }
    }
    record RemoveDetails(ProjectId projectId) {
        @Apply Details remove(Details existing) { return null; }
    }
    record CreateDocumentDetails(ProjectId projectId, String alias) {
        @Apply DocumentDetails create() { return new DocumentDetails(projectId, alias); }
    }
}
