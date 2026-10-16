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
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.ImmutableAggregateRoot;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateMarkerContractTest {
    static Stream<Arguments> invalidAccesses() {
        return Stream.of(false, true).flatMap(async -> Arrays.stream(Access.values())
                .map(access -> Arguments.of(async, access)));
    }

    @ParameterizedTest
    @MethodSource("invalidAccesses")
    void rejectsUnmarkedRootsBeforePersistingOrPublishing(boolean async, Access access) {
        var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new UseRoot(access))
                    .expectExceptionalResult(error -> (async ? error instanceof TechnicalException
                            : error instanceof IllegalArgumentException)
                            && error.getMessage().contains("@Aggregate")
                            && error.getMessage().contains(switch (access) {
                                case NESTED -> Container.Unmarked.class.getName();
                                case APPLY_WITHOUT_ROOT, SNAPSHOT_WITHOUT_ROOT, RELATIONSHIPS_WITHOUT_ROOT ->
                                        Object.class.getName();
                                default -> Unmarked.class.getName();
                            }))
                    .expectError(IllegalArgumentException.class)
                    .expectNoEvents();
            assertEquals(0, app.eventStore().getEvents("root").count());
            assertTrue(app.aggregateRepository().getAggregatesFor("root").isEmpty());
            assertTrue(app.snapshotStore().getSnapshot("root").isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void rejectsTypesInferredFromLegacyStreams(boolean async, boolean withTypeMetadata) {
        var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
        try (var app = fixture.getFluxzero()) {
            fixture.given(fc -> {
                        Message event = new Message(new CreateUnmarked());
                        fc.eventStore().storeEvents("root", withTypeMetadata ? event.addMetadata(
                                Entity.AGGREGATE_TYPE_METADATA_KEY, Unmarked.class.getName()) : event).join();
                    })
                    .whenCommand(new ReadUntyped())
                    .expectExceptionalResult(error -> (async ? error instanceof TechnicalException
                            : error instanceof IllegalArgumentException)
                            && error.getMessage().contains(Unmarked.class.getName()))
                    .expectError(IllegalArgumentException.class)
                    .expectNoEvents();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsInheritedMarkersAndPreservesUntypedCreation(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new CreateMarked()).expectResult(new Marked("value"))
                    .andThen().whenCommand(new ReadUntyped()).expectResult(new Marked("value"));
            assertTrue(app.aggregateRepository().load("subclass", MarkedSubclass.class).isEmpty());
            assertEquals(new Unmarked("plain"), app.aggregateRepository().asEntity(new Unmarked("plain")).get());
        }
    }

    enum Access {
        TYPED, ID, REPOSITORY, OWNER_FALLBACK, NAMESPACE, APPLY_UNTYPED, UPDATE_UNTYPED, NESTED,
        APPLY_WITHOUT_ROOT, SNAPSHOT, SNAPSHOT_WITHOUT_ROOT, RELATIONSHIPS, RELATIONSHIPS_WITHOUT_ROOT
    }
    record UseRoot(Access access) {}
    record ReadUntyped() {}
    record CreateUnmarked() {
        @Apply Unmarked apply() { return new Unmarked("value"); }
    }
    record CreateMarked() {
        @Apply Marked apply() { return new Marked("value"); }
    }
    record Unmarked(String value) {}
    @Aggregate interface MarkedRoot {}
    record Marked(String value) implements MarkedRoot {}
    @Aggregate static class MarkedBase {}
    static class MarkedSubclass extends MarkedBase {}
    @Aggregate static class Container {
        record Unmarked(String value) {}
    }

    static class Handler {
        @HandleCommand Object handle(UseRoot command) {
            return switch (command.access()) {
                case TYPED -> Fluxzero.loadAggregate("root", Unmarked.class);
                case ID -> Fluxzero.loadAggregate(new Id<>("root", Unmarked.class) {});
                case REPOSITORY -> Fluxzero.get().aggregateRepository().load("root", Unmarked.class);
                case OWNER_FALLBACK -> Fluxzero.loadAggregateFor("root", Unmarked.class);
                case NAMESPACE -> Fluxzero.get().aggregateRepository().forNamespace("other")
                        .load("root", Unmarked.class);
                case APPLY_UNTYPED -> Fluxzero.loadAggregate("root").apply(new CreateUnmarked());
                case UPDATE_UNTYPED -> Fluxzero.loadAggregate("root").update(ignored -> new Unmarked("value"));
                case NESTED -> Fluxzero.loadAggregate("root", Container.Unmarked.class);
                case APPLY_WITHOUT_ROOT -> Fluxzero.loadAggregate("root").apply("no apply handler");
                case SNAPSHOT -> Fluxzero.get().snapshotStore().storeSnapshot(ImmutableAggregateRoot.builder()
                        .id("root").value(new Unmarked("value")).build());
                case SNAPSHOT_WITHOUT_ROOT -> Fluxzero.get().snapshotStore().storeSnapshot(
                        Fluxzero.loadAggregate("root"));
                case RELATIONSHIPS -> Fluxzero.get().aggregateRepository().repairRelationships(
                        ImmutableAggregateRoot.builder().id("root").value(new Unmarked("value")).build());
                case RELATIONSHIPS_WITHOUT_ROOT -> Fluxzero.get().aggregateRepository().repairRelationships(
                        Fluxzero.loadAggregate("root"));
            };
        }
        @HandleCommand Object handle(ReadUntyped command) { return Fluxzero.loadAggregate("root").get(); }
        @HandleCommand Object handle(CreateMarked command) {
            return Fluxzero.loadAggregate("root").apply(command).get();
        }
    }
}
