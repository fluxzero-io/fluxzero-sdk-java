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

package io.fluxzero.sdk.common.serialization.casting;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static io.fluxzero.common.api.Data.JSON_FORMAT;
import static io.fluxzero.common.serialization.JsonUtils.asBytes;
import static io.fluxzero.common.serialization.JsonUtils.fromFile;

public class GivenWhenThenUpcasterChainTest {
    private static final String aggregateId = "test";

    private final TestFixture testFixture = TestFixture.create(new Handler()).registerCasters(new JsonNodeUpcaster());

    @Test
    void testUpcastingWithDataInput() {
        testFixture.givenAppliedEvents(aggregateId, TestModel.class, "create-model-revision-0.json")
                .whenQuery(new GetModel()).expectResult(new TestModel(List.of(new CreateModel("patchedContent"))));
    }

    @Test
    void givenAppliedEventsFlattensSplitUpcastResults() {
        testFixture.givenAppliedEvents(aggregateId, TestModel.class, splitModelMessage())
                .whenQuery(new GetModel())
                .expectResult(new TestModel(List.of(new CreateModel("created"), new UpdateModel("one", "two"))));
    }

    @Test
    void givenEventsFlattensSplitUpcastResults() {
        testFixture.givenEvents(splitModelMessage())
                .whenQuery(new GetHandledEvents())
                .expectResult(List.of(new CreateModel("created"), new UpdateModel("one", "two")));
    }

    @Test
    void upcastCommand() {
        testFixture.whenCommand("create-model-revision-0.json")
                .expectEvents(new CreateModel("patchedContent"));
    }

    @Test
    void whenUpcast() {
        testFixture.whenUpcasting("create-model-revision-0.json")
                .expectResult(new CreateModel("patchedContent"));
    }

    @Test
    void revisionPropertyRemainsPartOfPayload() {
        testFixture.whenUpcasting("revisioned-payload-revision-0.json")
                .expectResult(new RevisionedPayload(42, "patchedContent"));
    }

    @Test
    void plainRevisionPropertyDoesNotTriggerUpcasting() {
        testFixture.whenUpcasting("revisioned-payload-current.json")
                .expectResult(new RevisionedPayload(42, "someContent"));
    }

    @Test
    void revisionMetadataCanBeInherited() {
        testFixture.whenUpcasting("extended-revisioned-payload.json")
                .expectResult(new RevisionedPayload(43, "patchedContent"));
    }

    @Test
    void droppedCommandDoesNotInvokeHandler() {
        testFixture.whenCommand("dropped-create-model-revision-0.json").expectNoEvents().expectNoErrors();
    }

    @Test
    void testApplyingUnknownTypeThrows() {
        testFixture
                .given(fc -> fc.client().getEventStoreClient().storeEvents(
                        "test", List.of((SerializedMessage) fromFile("create-model-unknown-type.json")), true))
                .whenApplying(fc -> Fluxzero.loadAggregate("test", TestModel.class))
                .expectExceptionalResult(EventSourcingException.class);
    }

    @Test
    void testApplyingUnknownTypeIgnoredIfConfigured() {
        testFixture
                .given(fc -> fc.client().getEventStoreClient().storeEvents(
                        "test", List.of((SerializedMessage) fromFile("create-model-unknown-type.json")), true))
                .whenApplying(fc -> Fluxzero.loadAggregate("test", TestModelIgnoringUnknownEvent.class))
                .<Entity<TestModelIgnoringUnknownEvent>>expectResult(
                        e -> e != null && e.type().equals(TestModelIgnoringUnknownEvent.class)
                             && e.isEmpty() && e.sequenceNumber() == 0L);
    }

    private SerializedMessage splitModelMessage() {
        Data<byte[]> data = new Data<>(asBytes(Map.of("content", "created", "one", "one", "two", "two")),
                                      LegacyModelCreated.class.getName(), 0, JSON_FORMAT);
        return new SerializedMessage(data, Metadata.empty(), "split-model-message", 0L);
    }

    public static class JsonNodeUpcaster {
        @Upcast(type = "io.fluxzero.sdk.common.serialization.casting.GivenWhenThenUpcasterChainTest$CreateModel",
                revision = 0)
        public ObjectNode upcast(ObjectNode input) {
            return input.put("content", "patchedContent");
        }

        @Upcast(type = "io.fluxzero.sdk.common.serialization.casting.GivenWhenThenUpcasterChainTest$RevisionedPayload",
                revision = 0)
        public ObjectNode upcastRevisionedPayload(ObjectNode input) {
            return input.put("content", "patchedContent");
        }

        @Upcast(type = "io.fluxzero.sdk.common.serialization.casting.GivenWhenThenUpcasterChainTest$DroppedCreateModel",
                revision = 0)
        public ObjectNode drop(ObjectNode input) {
            return null;
        }

        @Upcast(type = "io.fluxzero.sdk.common.serialization.casting.GivenWhenThenUpcasterChainTest$LegacyModelCreated",
                revision = 0)
        public Stream<Data<JsonNode>> split(Data<JsonNode> input) {
            ObjectNode createModel = input.getValue().deepCopy();
            createModel.remove(List.of("one", "two"));
            ObjectNode updateModel = input.getValue().deepCopy();
            updateModel.remove("content");
            return Stream.of(new Data<>(createModel, CreateModel.class.getName(), 1, input.getFormat()),
                             new Data<>(updateModel, UpdateModel.class.getName(), 3, input.getFormat()));
        }
    }

    private record LegacyModelCreated(String content, String one, String two) {
    }

    @Value
    public static class DroppedCreateModel {
        String content;
    }

    @Value
    @Revision(1)
    public static class CreateModel {
        String content;
    }

    @Value
    @Revision(1)
    public static class RevisionedPayload {
        int revision;
        String content;
    }

    @Value
    @Revision(3)
    public static class UpdateModel {
        String one, two;
    }

    @Value
    public static class GetModel {
    }

    @Value
    public static class GetHandledEvents {
    }

    private static class Handler {
        private final List<Object> handledEvents = new CopyOnWriteArrayList<>();

        @HandleCommand
        void handle(@NonNull Object command, Metadata metadata) {
            Fluxzero.loadAggregate(aggregateId, TestModel.class).assertLegal(command).apply(command, metadata);
        }

        @HandleEvent
        void handleEvent(Object event) {
            handledEvents.add(event);
        }

        @HandleQuery
        TestModel handle(GetModel query) {
            return Fluxzero.loadAggregate(aggregateId, TestModel.class).get();
        }

        @HandleQuery
        List<Object> handle(GetHandledEvents query) {
            return List.copyOf(handledEvents);
        }

    }

    @Aggregate
    @Value
    @Builder(toBuilder = true)
    public static class TestModel {
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
        @Singular
        List<Object> events;

        @Apply
        public static TestModel handle(CreateModel event) {
            return new TestModel(List.of(event));
        }

        @Apply
        public TestModel handle(UpdateModel event) {
            return toBuilder().event(event).build();
        }
    }

    @Aggregate(ignoreUnknownEvents = true)
    public static class TestModelIgnoringUnknownEvent {
    }
}
