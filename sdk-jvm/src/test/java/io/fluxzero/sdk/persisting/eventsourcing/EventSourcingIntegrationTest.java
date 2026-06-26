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

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

@Slf4j
class EventSourcingIntegrationTest {
    private final TestFixture testFixture = TestFixture.createAsync(new CommandHandler(), new EventHandler()).spy();

    @Test
    void testHandleBatch() {
        testFixture.givenCommands(new UpsertCommand("test", "0"), new UpsertCommand("test", "1"),
                                  new UpsertCommand("test", "2"))
                .whenCommand(new UpsertCommand("test", "3"))
                .expectOnlyEvents(new UpsertCommand("test", "3"))
                .expectOnlyCommands(new SecondOrderCommand());

        assertEquals(4, testFixture.getFluxzero().eventStore().getEvents("test").count());

        verify(testFixture.getFluxzero().client().getEventStoreClient(), atMost(3))
                .storeEvents(eq("test"), anyList(), eq(false));
    }

    @Test
    void testHandleUpdateAfterDelete() {
        testFixture.givenCommands(new UpsertCommand("test", "0"), new DeleteCommand("test"))
                .whenCommand(new UpsertCommand("test", "1"))
                .expectOnlyEvents(new UpsertCommand("test", "1"))
                .expectOnlyCommands(new SecondOrderCommand())
                .expectThat(fc -> assertEquals("create", loadAggregate("test", AggregateRoot.class).get().event));
    }

    @Test
    void testDeleteAggregate() {
        testFixture.givenCommands(new UpsertCommand("test", "0"))
                .whenApplying(fc -> Fluxzero.loadAggregate("test", AggregateRoot.class))
                .expectResult(Entity::isPresent)
                .andThen()
                .whenExecuting(fc -> fc.aggregateRepository().deleteAggregate("test"))
                .expectTrue(fc -> fc.cache().isEmpty())
                .expectTrue(fc -> fc.eventStore().getEvents("test").toList().isEmpty())
                .expectTrue(fc -> fc.aggregateRepository().getAggregatesFor("test").isEmpty())
                .andThen()
                .whenApplying(fc -> Fluxzero.loadAggregate("test", AggregateRoot.class))
                .expectResult(Entity::isEmpty);
    }

    static class CommandHandler {
        @HandleCommand
        void handle(AggregateCommand command) {
            loadAggregate(command.getId(), AggregateRoot.class).apply(command);
        }
    }

    @Slf4j
    static class EventHandler {
        @HandleEvent
        void handle(AggregateCommand event) {
            Fluxzero.sendAndForgetCommand(new SecondOrderCommand());
        }
    }

    @Aggregate(searchable = true, snapshotPeriod = 1)
    @Value
    @Builder(toBuilder = true)
    static class AggregateRoot {
        String id, value, event;
    }

    interface AggregateCommand {
        @RoutingKey
        String getId();
    }

    @Value
    static class UpsertCommand implements AggregateCommand {
        String id, value;


        @Apply
        AggregateRoot create() {
            return AggregateRoot.builder().id(id).value(value).event("create").build();
        }

        @Apply
        AggregateRoot update(AggregateRoot model) {
            return model.toBuilder().value(value).event("update").build();
        }
    }

    @Value
    static class DeleteCommand implements AggregateCommand {
        String id;

        @Apply
        AggregateRoot apply(AggregateRoot model) {
            return null;
        }
    }


    @Value
    static class SecondOrderCommand {
    }
}