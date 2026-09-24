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

package io.fluxzero.sdk.tracking.handling.validation

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.AssertTrue
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class KotlinValidationTest {
    @Test
    fun reminderExampleNeedsNoRedundantNullGuard() {
        ValidationUtils.assertValid(ConfigureReminder(Duration.ZERO))
        assertFailsWith<ValidationException> {
            ValidationUtils.assertValid(ConfigureReminder(Duration.ofSeconds(-1)))
        }
    }

    data class ConfigureReminder(@field:NotNull val delay: Duration) {
        @AssertTrue(message = "Choose a non-negative delay.")
        fun hasNonNegativeDelay(): Boolean = !delay.isNegative
    }

    @Test
    fun validatesConstructorPropertyAnnotationsWithoutFieldUseSiteTarget() {
        val exception = assertFailsWith<ValidationException> {
            ValidationUtils.assertValid(KotlinConstructorProperty(null))
        }

        assertEquals("name must not be null", exception.message)
    }

    data class KotlinConstructorProperty(@param:NotNull val name: String?)
}
