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
package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.scheduling.Deadline
import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.assertTrue

class ModelDeadlineKotlinTest {
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun payloadDeadlineUsesPropertyCronOnce(async: Boolean) {
        val fixture = if (async) TestFixture.createAsync(Reminder::class.java) else TestFixture.create(Reminder::class.java)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val id = ReminderId("kotlin-deadline")
        fixture.withProperty("reminder.cron", "0 * * * *").atFixedTime(start)
            .givenCommands(CreateReminder(id)).whenTimeAdvancesTo(start.plusSeconds(3600))
            .expectNoErrors().expectOnlyActiveScheduledCommands()
            .expectThat { assertTrue(Fluxzero.loadModel(id).get().completed) }
            .andThen().whenCommand(RenameReminder(id, "changed"))
            .expectSuccessfulResult().expectNoErrors().expectOnlyActiveScheduledCommands()
    }

    class ReminderId(value: String) : Id<Reminder>(value)
    @Model(searchable = false)
    data class Reminder(@EntityId val reminderId: ReminderId, val label: String = "", val completed: Boolean = false) {
        // Returning the same payload after execution must not create a recurring schedule.
        @Deadline(cron = "\${reminder.cron}")
        fun deadline() = CompleteReminder(reminderId)
    }
    data class CreateReminder(val reminderId: ReminderId) {
        @Apply fun apply() = Reminder(reminderId)
    }
    data class RenameReminder(val reminderId: ReminderId, val label: String) {
        @Apply fun apply(current: Reminder) = current.copy(label = label)
    }
    data class CompleteReminder(val reminderId: ReminderId) {
        @Apply fun apply(current: Reminder) = current.copy(completed = true)
    }
}
