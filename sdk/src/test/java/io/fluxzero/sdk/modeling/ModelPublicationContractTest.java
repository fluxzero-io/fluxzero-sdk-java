/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelEventMetadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ModelPublicationContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishOnlyDocumentLifecycleDoesNotRequireStoredEvents(boolean async) {
        fixture(async, PublishedDocument.class)
                .whenCommand(new Create("document"))
                .expectEvents(new Create("document")).expectSuccessfulResult().expectNoErrors()
                .andThen().whenCommand(new Update("document"))
                .expectEvents(new Update("document")).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("document"));
                    assertEquals(new PublishedDocument("document", 2),
                                 Fluxzero.loadModel("document", PublishedDocument.class).get());
                    assertEquals(0, fc.eventStore().getEvents("document").count());
                })
                .andThen().whenCommand(new Delete("document"))
                .expectEvents(new Delete("document")).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("document"));
                    assertNull(Fluxzero.loadModel("document", PublishedDocument.class).get());
                    assertTrue(Fluxzero.search(PublishedDocument.class).fetchAll().isEmpty());
                    assertEquals(0, fc.eventStore().getEvents("document").count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eventSourcedNeverRejectsCreationUpdateAndDeletionWithoutLosingState(boolean async) {
        Class<? extends Throwable> failure = async ? TechnicalException.class : IllegalStateException.class;
        fixture(async, Sourced.class)
                .whenCommand(new SilentCreate("source"))
                .expectExceptionalResult(failure).expectNoEvents()
                .expectThat(fc -> assertNull(Fluxzero.loadModel("source", Sourced.class).get()))
                .andThen().givenCommands(new Create("source"))
                .whenCommand(new SilentUpdate("source"))
                .expectExceptionalResult(failure).expectNoEvents()
                .andThen().whenCommand(new SilentDelete("source"))
                .expectExceptionalResult(failure).expectNoEvents()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("source"));
                    assertEquals(new Sourced("source", 1), Fluxzero.loadModel("source", Sourced.class).get());
                    assertEquals(1, fc.eventStore().getEvents("source").count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishOnlyNoOpCanNotifyWithoutChangingReplayState(boolean async) {
        fixture(async, Sourced.class).givenCommands(new Create("source"))
                .whenCommand(new Notify("source"))
                .expectEvents(new Notify("source")).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("source"));
                    assertEquals(new Sourced("source", 1), Fluxzero.loadModel("source", Sourced.class).get());
                    assertEquals(1, fc.eventStore().getEvents("source").count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticCommandPublishesAnIndependentEventIdentity(boolean async) {
        Message command = new Message(new Create("identity"), Metadata.of("business", "value"),
                                      "command-identity", Instant.parse("2026-01-01T12:00:00Z"));
        fixture(async, Sourced.class).whenCommand(command)
                .expectOnlyEvents((Predicate<Message>) event -> event.getPayload().equals(command.getPayload())
                        && !event.getMessageId().equals(command.getMessageId())
                        && event.getTimestamp().equals(command.getTimestamp()))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    var event = fc.eventStore().getEvents("identity").findFirst().orElseThrow();
                    assertNotEquals(command.getMessageId(), event.getMessageId());
                    assertEquals(command.getTimestamp(), event.getTimestamp());
                    assertEquals("value", event.getMetadata().get("business"));
                    assertEquals(command.getMessageId(), event.getMetadata().get(ModelEventMetadata.COMMIT_ID));
                    String eventId = event.getMessageId();
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("identity"));
                    assertEquals(new Sourced("identity", 1), Fluxzero.loadModel("identity", Sourced.class).get());
                    assertEquals(eventId, fc.eventStore().getEvents("identity").findFirst().orElseThrow().getMessageId());
                });
    }

    private TestFixture fixture(boolean async, Class<?> type) {
        return async ? TestFixture.createAsync(type) : TestFixture.create(type);
    }

    record Create(String id) {}
    record Update(String id) {}
    record Delete(String id) {}
    record SilentCreate(String id) {}
    record SilentUpdate(String id) {}
    record SilentDelete(String id) {}
    record Notify(String id) {}

    @Model(searchable = true, persistence = ModelPersistence.DOCUMENT, publicationStrategy = EventPublicationStrategy.PUBLISH_ONLY)
    record PublishedDocument(@EntityId String id, int value) {
        @Apply static PublishedDocument create(Create event) { return new PublishedDocument(event.id(), 1); }
        @Apply PublishedDocument update(Update event) { return new PublishedDocument(id, value + 1); }
        @Apply PublishedDocument delete(Delete event) { return null; }
    }

    @Model(searchable = false)
    record Sourced(@EntityId String id, int value) {
        @Apply static Sourced create(Create event) { return new Sourced(event.id(), 1); }
        @Apply(eventPublication = EventPublication.NEVER)
        static Sourced create(SilentCreate event) { return new Sourced(event.id(), 1); }
        @Apply(eventPublication = EventPublication.NEVER)
        Sourced update(SilentUpdate event) { return new Sourced(id, value + 1); }
        @Apply(eventPublication = EventPublication.NEVER)
        Sourced delete(SilentDelete event) { return null; }
        @Apply(eventPublication = EventPublication.ALWAYS, publicationStrategy = EventPublicationStrategy.PUBLISH_ONLY)
        Sourced notify(Notify event) { return this; }
    }
}
