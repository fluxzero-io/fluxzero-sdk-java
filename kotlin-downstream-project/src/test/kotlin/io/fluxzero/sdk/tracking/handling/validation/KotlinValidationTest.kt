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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class KotlinValidationTest {
    @Test
    fun validatesConstructorPropertyAnnotationsWithoutFieldUseSiteTarget() {
        val exception = assertFailsWith<ValidationException> {
            ValidationUtils.assertValid(KotlinConstructorProperty(null))
        }

        assertEquals("name must not be null", exception.message)
    }

    data class KotlinConstructorProperty(@NotNull val name: String?)
}
