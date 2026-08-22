/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;

class UntypedSearchableAggregateDeleteTest {

    private final TestFixture testFixture = TestFixture.create(new CommandHandler());

    @Test
    void deletesCachedSearchableAggregateCreatedThroughUntypedLoad() {
        String aggregateId = "untyped-cached-searchable";
        CachedCreate create = new CachedCreate(aggregateId, "value");

        testFixture.givenCommands(create)
                .whenSearching(CachedAggregate.class)
                .expectResult(List.of(new CachedAggregate("value")))
                .andThen()
                .whenCommand(new CachedDelete(aggregateId))
                .expectNoErrors()
                .andThen()
                .whenSearching(CachedAggregate.class)
                .expectResult(List::isEmpty)
                .andThen()
                .whenCommand(new CachedExists(aggregateId))
                .expectResult(false);
    }

    @Test
    void deletesUncachedSearchableAggregateCreatedThroughUntypedLoad() {
        String aggregateId = "untyped-uncached-searchable";
        UncachedCreate create = new UncachedCreate(aggregateId, "value");

        testFixture.givenCommands(create)
                .whenSearching(UncachedAggregate.class)
                .expectResult(List.of(new UncachedAggregate("value")))
                .andThen()
                .whenCommand(new UncachedDelete(aggregateId))
                .expectNoErrors()
                .andThen()
                .whenSearching(UncachedAggregate.class)
                .expectResult(List::isEmpty)
                .andThen()
                .whenCommand(new UncachedExists(aggregateId))
                .expectResult(false);
    }

    static class CommandHandler {

        @HandleCommand
        void handle(CachedCommand command) {
            loadAggregate(command.id()).apply(command);
        }

        @HandleCommand
        boolean handle(CachedExists command) {
            return loadAggregate(command.id()).isPresent();
        }

        @HandleCommand
        void handle(UncachedCommand command) {
            loadAggregate(command.id()).apply(command);
        }

        @HandleCommand
        boolean handle(UncachedExists command) {
            return loadAggregate(command.id()).isPresent();
        }
    }

    interface CachedCommand {

        @RoutingKey
        String id();
    }

    record CachedCreate(String id, String value) implements CachedCommand {

        @Apply
        CachedAggregate apply() {
            return new CachedAggregate(value);
        }
    }

    record CachedDelete(String id) implements CachedCommand {

        @Apply
        CachedAggregate apply(CachedAggregate aggregate) {
            return null;
        }
    }

    record CachedExists(@RoutingKey String id) {
    }

    interface UncachedCommand {

        @RoutingKey
        String id();
    }

    record UncachedCreate(String id, String value) implements UncachedCommand {

        @Apply
        UncachedAggregate apply() {
            return new UncachedAggregate(value);
        }
    }

    record UncachedDelete(String id) implements UncachedCommand {

        @Apply
        UncachedAggregate apply(UncachedAggregate aggregate) {
            return null;
        }
    }

    record UncachedExists(@RoutingKey String id) {
    }

    @Aggregate(eventSourced = false, searchable = true)
    record CachedAggregate(String value) {
    }

    @Aggregate(eventSourced = false, searchable = true, cached = false)
    record UncachedAggregate(String value) {
    }
}
