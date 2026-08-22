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
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedSearchableAggregateDeleteTest {

    private static final String AGGREGATE_ID = "cached-searchable";
    private static final String CACHE_KEY = "$Aggregate:" + AGGREGATE_ID;
    private static final String NON_EVENT_SOURCED_AGGREGATE_ID = "cached-searchable-document";
    private static final String NON_EVENT_SOURCED_CACHE_KEY = "$Aggregate:" + NON_EVENT_SOURCED_AGGREGATE_ID;

    private final TestFixture testFixture = TestFixture.create(new CommandHandler());

    @Test
    void functionalDeleteLeavesEmptyAggregateValueInCache() {
        testFixture.givenCommands(new CreateAggregate(AGGREGATE_ID, "value"))
                .whenCommand(new AggregateExists(AGGREGATE_ID))
                .expectResult(true)
                .andThen()
                .whenCommand(new DeleteAggregate(AGGREGATE_ID))
                .expectThat(fc -> {
                    assertTrue(fc.cache().containsKey(CACHE_KEY));
                    Entity<?> cached = fc.cache().get(CACHE_KEY);
                    assertNotNull(cached);
                    assertTrue(cached.isEmpty());
                    assertNull(cached.get());
                    assertTrue(fc.documentStore().search(CachedSearchableAggregate.class).fetchAll().isEmpty());
                })
                .andThen()
                .whenCommand(new AggregateExists(AGGREGATE_ID))
                .expectResult(false);
    }

    @Test
    void functionalDeleteLeavesEmptyNonEventSourcedAggregateValueInCache() {
        testFixture.givenCommands(new CreateNonEventSourcedAggregate(NON_EVENT_SOURCED_AGGREGATE_ID, "value"))
                .whenCommand(new NonEventSourcedAggregateExists(NON_EVENT_SOURCED_AGGREGATE_ID))
                .expectResult(true)
                .andThen()
                .whenCommand(new DeleteNonEventSourcedAggregate(NON_EVENT_SOURCED_AGGREGATE_ID))
                .expectThat(fc -> {
                    assertTrue(fc.cache().containsKey(NON_EVENT_SOURCED_CACHE_KEY));
                    Entity<?> cached = fc.cache().get(NON_EVENT_SOURCED_CACHE_KEY);
                    assertNotNull(cached);
                    assertTrue(cached.isEmpty());
                    assertNull(cached.get());
                    assertTrue(fc.documentStore().search(NonEventSourcedAggregate.class).fetchAll().isEmpty());
                })
                .andThen()
                .whenCommand(new NonEventSourcedAggregateExists(NON_EVENT_SOURCED_AGGREGATE_ID))
                .expectResult(false);
    }

    static class CommandHandler {

        @HandleCommand
        void handle(AggregateCommand command) {
            loadAggregate(command.id(), CachedSearchableAggregate.class).apply(command);
        }

        @HandleCommand
        boolean handle(AggregateExists command) {
            return loadAggregate(command.id(), CachedSearchableAggregate.class).isPresent();
        }

        @HandleCommand
        void handle(NonEventSourcedAggregateCommand command) {
            loadAggregate(command.id(), NonEventSourcedAggregate.class).apply(command);
        }

        @HandleCommand
        boolean handle(NonEventSourcedAggregateExists command) {
            return loadAggregate(command.id(), NonEventSourcedAggregate.class).isPresent();
        }
    }

    interface AggregateCommand {

        @RoutingKey
        String id();
    }

    record CreateAggregate(String id, String value) implements AggregateCommand {

        @Apply
        CachedSearchableAggregate apply() {
            return new CachedSearchableAggregate(value);
        }
    }

    record DeleteAggregate(String id) implements AggregateCommand {

        @Apply
        CachedSearchableAggregate apply(CachedSearchableAggregate aggregate) {
            return null;
        }
    }

    record AggregateExists(@RoutingKey String id) {
    }

    interface NonEventSourcedAggregateCommand {

        @RoutingKey
        String id();
    }

    record CreateNonEventSourcedAggregate(String id, String value) implements NonEventSourcedAggregateCommand {

        @Apply
        NonEventSourcedAggregate apply() {
            return new NonEventSourcedAggregate(value);
        }
    }

    record DeleteNonEventSourcedAggregate(String id) implements NonEventSourcedAggregateCommand {

        @Apply
        NonEventSourcedAggregate apply(NonEventSourcedAggregate aggregate) {
            return null;
        }
    }

    record NonEventSourcedAggregateExists(@RoutingKey String id) {
    }

    @Aggregate(searchable = true)
    record CachedSearchableAggregate(String value) {
    }

    @Aggregate(eventSourced = false, searchable = true)
    record NonEventSourcedAggregate(String value) {
    }
}
