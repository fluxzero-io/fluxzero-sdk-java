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
package io.fluxzero.sdk.test;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class ActiveScheduledCommandsTest {
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DEADLINE = START.plusSeconds(3600);

    TestFixture fixture(boolean async) {
        return (async ? TestFixture.createAsync() : TestFixture.create()).atFixedTime(START);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void includesGivenAndPriorPhasesWithoutChangingWhenAssertions(boolean async) {
        fixture(async).givenScheduledCommands(new Schedule("old command", "old", DEADLINE))
                .whenExecuting(fc -> {})
                .expectOnlyScheduledCommands()
                .expectOnlyActiveScheduledCommands(matches("old", DEADLINE, "old command"))
                .andThen().whenExecuting(fc -> {})
                .expectOnlyScheduledCommands()
                .expectOnlyActiveScheduledCommands("old command");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void detectsForgottenScheduleAndChecksAllThreeFields(boolean async) {
        var result = fixture(async).givenScheduledCommands(new Schedule("old command", "old", DEADLINE))
                .whenExecuting(fc -> Fluxzero.scheduleCommand("new command", "new", DEADLINE.plusSeconds(60)))
                .expectOnlyScheduledCommands("new command")
                .expectOnlyActiveScheduledCommands(matches("old", DEADLINE, "old command"),
                        matches("new", DEADLINE.plusSeconds(60), "new command"));
        assertThrows(GivenWhenThenAssertionError.class,
                () -> result.expectOnlyActiveScheduledCommands("new command"));
        assertThrows(GivenWhenThenAssertionError.class, () -> result.expectOnlyActiveScheduledCommands(
                "old command", matches("wrong", DEADLINE.plusSeconds(60), "new command")));
        assertThrows(GivenWhenThenAssertionError.class, () -> result.expectOnlyActiveScheduledCommands(
                "old command", matches("new", DEADLINE, "new command")));
        assertThrows(GivenWhenThenAssertionError.class, () -> result.expectOnlyActiveScheduledCommands(
                "old command", matches("new", DEADLINE.plusSeconds(60), "wrong")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replacementAndCancellationReflectActiveState(boolean async) {
        Message replacement = new Message("new command").addMetadata("reason", "changed");
        fixture(async).givenScheduledCommands(new Schedule("old command", "same", DEADLINE))
                .whenExecuting(fc -> Fluxzero.scheduleCommand(replacement, "same", DEADLINE.plusSeconds(60)))
                .expectOnlyActiveScheduledCommands(replacement)
                .expectOnlyActiveScheduledCommands(matches("same", DEADLINE.plusSeconds(60), "new command"))
                .andThen().whenExecuting(fc -> Fluxzero.cancelSchedule("same"))
                .expectOnlyActiveScheduledCommands().expectNoSchedules();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinarySchedulesAreNotScheduledCommands(boolean async) {
        var result = fixture(async).givenSchedules(new Schedule("ordinary", "schedule", DEADLINE))
                .whenExecuting(fc -> {}).expectOnlyActiveScheduledCommands();
        assertThrows(GivenWhenThenAssertionError.class, result::expectNoSchedules);
    }

    @Test
    void unsupportedClientCannotProduceFalseEmptySuccess() {
        LocalClient client = spy(LocalClient.newInstance());
        doReturn(mock(SchedulingClient.class, delegatesTo(client.getSchedulingClient())))
                .when(client).getSchedulingClient();
        var result = TestFixture.createAsync(DefaultFluxzero.builder(), client).whenExecuting(fc -> {});
        assertThrows(UnsupportedOperationException.class, result::expectOnlyActiveScheduledCommands);
    }

    static Predicate<Schedule> matches(String id, Instant deadline, Object command) {
        return schedule -> schedule.getScheduleId().equals(id)
                           && schedule.getDeadline().equals(deadline)
                           && schedule.getPayload().equals(command);
    }
}
