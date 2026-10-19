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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GraphSubscriptionBindingTest {
    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
                "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void graphSubscriptionUsesChangedRootsInsteadOfAmbiguousPayloadReferences(
            boolean related, boolean notification, boolean async) {
        var source = new AccountId("source");
        var destination = new AccountId("destination");
        var task = new TaskId("task");
        var observed = new CopyOnWriteArrayList<Graph<Account>>();
        Object handler = notification ? new Object() {
            @HandleNotification void handle(Graph<Account> graph) { observed.add(graph); }
        } : new Object() {
            @HandleEvent void handle(Graph<Account> graph) { observed.add(graph); }
        };
        var fixture = TestFixture.create(handler);
        (async ? fixture.async() : fixture).givenCommands(new CreateAccount(source), new CreateAccount(destination),
                        new CreateTask(task, related ? source : null))
                .whenExecuting(f -> {
                    observed.clear();
                    f.commandGateway().sendAndWait(new RelinkTask(task, source, destination, related));
                })
                .expectNoErrors()
                .expectThat(f -> {
                    var expected = related
                            ? List.of(source.toString(), destination.toString()).stream().sorted().toList() : List.of();
                    assertEquals(expected, observed.stream().map(g -> g.id().toString()).sorted().toList());
                    for (Graph<Account> graph : observed) {
                        boolean oldRoot = graph.id().equals(source.toString());
                        assertEquals(oldRoot ? List.of() : List.of(task),
                                graph.childModels("tasks", Task.class).stream().map(Task::taskId).toList());
                        assertEquals(oldRoot ? List.of(task) : List.of(),
                                graph.previous().childModels("tasks", Task.class).stream().map(Task::taskId).toList());
                    }
                })
                .andThen().whenCommand(new CreateAccount(new AccountId("after")))
                .expectNoErrors()
                .expectThat(f -> {
                    assertEquals(related ? 3 : 1, observed.size());
                    assertEquals(new AccountId("after").toString(), observed.getLast().id());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryEventsWithAmbiguousReferencesDoNotInvokeSubscriptions(boolean async) {
        var observed = new CopyOnWriteArrayList<Graph<Account>>();
        var fixture = TestFixture.create(new Object() {
            @HandleEvent @HandleNotification
            void changed(Graph<Account> graph) { observed.add(graph); }
        });
        (async ? fixture.async() : fixture)
                .whenEvent(new References(new AccountId("source"), new AccountId("destination")))
                .expectNoErrors().expectThat(f -> assertTrue(observed.isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitPayloadKeepsPrecedenceOverCompleteSubscription(boolean async) {
        var selected = new CopyOnWriteArrayList<String>();
        var fixture = TestFixture.create(new Object() {
            @HandleEvent
            void changed(Graph<Account> graph) { selected.add("graph"); }
            @HandleEvent
            void relinked(RelinkTask event, Graph<Task> graph) {
                assertEquals(event.taskId().toString(), graph.id());
                selected.add("payload");
            }
        });
        var source = new AccountId("source");
        var destination = new AccountId("destination");
        var task = new TaskId("task");
        (async ? fixture.async() : fixture)
                .givenCommands(new CreateAccount(source), new CreateAccount(destination), new CreateTask(task, source))
                .whenExecuting(f -> {
                    selected.clear();
                    f.commandGateway().sendAndWait(new RelinkTask(task, source, destination, true));
                })
                .expectNoErrors().expectThat(f -> assertEquals(List.of("payload"), selected));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void genuinePayloadAmbiguityIsNotHiddenByACompleteSubscription(boolean async) {
        var failures = new CopyOnWriteArrayList<Throwable>();
        var fixture = TestFixture.create(DefaultFluxzero.builder().configureDefaultConsumer(MessageType.EVENT,
                c -> c.toBuilder().errorHandler((error, description, retry) -> {
                    failures.add(error);
                    return null;
                }).build())).registerHandlers(new Object() {
            @HandleEvent
            void changed(Graph<Account> graph) {}
            @HandleEvent
            void ambiguous(References event, Graph<Account> graph) {}
        });
        var result = (async ? fixture.async() : fixture)
                .whenEvent(new References(new AccountId("source"), new AccountId("destination")));
        if (async) {
            // Tracking selection failures go to the configured consumer error handler.
            result.expectThat(f -> {
                assertEquals(1, failures.size());
                var failure = assertInstanceOf(IllegalStateException.class, failures.getFirst());
                assertTrue(failure.getMessage().contains("ambiguous Id<Account> properties"));
            });
        } else {
            result.expectError((IllegalStateException e) -> e.getMessage().contains("ambiguous Id<Account> properties"));
        }
    }

    record References(AccountId sourceId, AccountId destinationId) {}

    @Model(searchable = false) record Account(@EntityId AccountId accountId) {}
    static final class AccountId extends Id<Account> { AccountId(String id) { super(id, "subscription-account-"); } }
    record CreateAccount(AccountId accountId) { @Apply Account apply() { return new Account(accountId); } }
    @Model(searchable = false) record Task(@EntityId TaskId taskId, @Parent(pathInParent = "tasks") AccountId ownerId, int version) {}
    static final class TaskId extends Id<Task> { TaskId(String id) { super(id, "subscription-task-"); } }
    record CreateTask(TaskId taskId, AccountId ownerId) { @Apply Task apply() { return new Task(taskId, ownerId, 1); } }
    record RelinkTask(TaskId taskId, AccountId sourceId, AccountId destinationId, boolean related) {
        @Apply Task apply(Task task) { return new Task(taskId, related ? destinationId : null, task.version() + 1); }
    }
}
