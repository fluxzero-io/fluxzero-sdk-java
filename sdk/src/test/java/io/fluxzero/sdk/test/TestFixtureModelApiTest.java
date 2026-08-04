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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFixtureModelApiTest {

    @Test
    void synchronousFixtureDistinguishesModelEventFromSourceCommandWithSameMessageId() {
        AtomicInteger handled = new AtomicInteger();
        CreateModel command = new CreateModel("model-1");

        TestFixture.create(new ModelEventHandler(handled))
                .whenCommand(command)
                .expectOnlyEvents(command)
                .expectTrue(ignored -> handled.get() == 1);
    }

    @Test
    void modelEventsUseTheModelRepositoryWithoutAggregateMetadata() {
        TestFixture fixture = mock(TestFixture.class, CALLS_REAL_METHODS);
        Fluxzero fluxzero = mock(Fluxzero.class);
        ModelRepository repository = mock(ModelRepository.class);
        @SuppressWarnings("unchecked")
        Entity<TestModel> entity = (Entity<TestModel>) mock(Entity.class);
        List<Message> events = List.of(new Message("created"));
        when(fluxzero.modelRepository()).thenReturn(repository);
        when(repository.load("model-1", TestModel.class)).thenReturn(entity);

        fixture.applyModelEvents("model-1", TestModel.class, fluxzero, events);

        verify(repository).load("model-1", TestModel.class);
        verify(entity).apply(events);
    }

    private record TestModel(String id) {
    }

    @Model
    private record FixtureModel(@EntityId String id) {
    }

    private record CreateModel(String id) {
        @Apply
        FixtureModel apply() {
            return new FixtureModel(id);
        }
    }

    private record ModelEventHandler(AtomicInteger handled) {
        @HandleEvent
        void handle(CreateModel ignored) {
            handled.incrementAndGet();
        }
    }
}
