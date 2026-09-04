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

package io.fluxzero.sdk.givenwhenthen;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.Nullable;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class TestFixtureResourceSequenceTest {

    @Test
    void givenCommandsLoadsCurrentAndRevisionedCommandsFromRootArrayInOrder() {
        RecordingHandler handler = new RecordingHandler();

        fixture(handler).givenCommands("sequence-commands.json")
                .whenQuery(new HandledCommands())
                .expectResult(List.of("current", "upcasted legacy"));
    }

    @Test
    void givenCommandsLoadsNdjsonRecordsInOrder() {
        RecordingHandler handler = new RecordingHandler();

        fixture(handler).givenCommands("sequence-commands.ndjson")
                .whenQuery(new HandledCommands())
                .expectResult(List.of("first", "second"));
    }

    @Test
    void givenCommandsLoadsSingleElementRootArray() {
        RecordingHandler handler = new RecordingHandler();

        fixture(handler).givenCommands("single-sequence-command.json")
                .whenQuery(new HandledCommands())
                .expectResult(List.of("only"));
    }

    @Test
    void givenCommandsByUserAddsTheSameUserToEveryArrayCommand() {
        RecordingHandler handler = new RecordingHandler();

        fixture(handler).givenCommandsByUser("user-123", "sequence-commands.json")
                .whenQuery(new HandlingUsers())
                .expectResult(List.of("user-123", "user-123"));
    }

    @Test
    void directCollectionsAndJavaArraysRetainTheirExistingBehavior() {
        RecordingHandler handler = new RecordingHandler();

        fixture(handler).givenCommands(List.of(new SequenceCommand("collection")),
                                       new SequenceCommand[]{new SequenceCommand("array")})
                .whenQuery(new HandledCommands())
                .expectResult(List.of("collection", "array"));
    }

    private static TestFixture fixture(RecordingHandler handler) {
        NamedUser user = new NamedUser("user-123");
        return TestFixture.create(DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(user)), handler)
                .registerCasters(new SequenceCommandUpcaster());
    }

    public static class SequenceCommandUpcaster {
        @Upcast(type = "io.fluxzero.sdk.givenwhenthen.TestFixtureResourceSequenceTest$SequenceCommand", revision = 0)
        ObjectNode upcast(ObjectNode input) {
            return input.put("value", "upcasted " + input.remove("legacyValue").textValue());
        }
    }

    private static class RecordingHandler {
        private final List<String> commands = new ArrayList<>();
        private final List<String> users = new ArrayList<>();

        @HandleCommand
        void handle(SequenceCommand command, @Nullable User user) {
            commands.add(command.value());
            users.add(user == null ? "anonymous" : user.getName());
        }

        @HandleQuery
        List<String> handle(HandledCommands query) {
            return List.copyOf(commands);
        }

        @HandleQuery
        List<String> handle(HandlingUsers query) {
            return List.copyOf(users);
        }
    }

    @Revision(1)
    public record SequenceCommand(String value) {
    }

    private record HandledCommands() {
    }

    private record HandlingUsers() {
    }

    private record NamedUser(String name) implements User {
        @Override
        public boolean hasRole(String role) {
            return false;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
