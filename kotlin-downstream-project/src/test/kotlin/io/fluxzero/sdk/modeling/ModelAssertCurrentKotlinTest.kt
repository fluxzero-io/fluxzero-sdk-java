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

package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.persisting.eventsourcing.AssertCurrent
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.IllegalCommandException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ModelAssertCurrentKotlinTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun preservesCurrentInputAcrossReplacement(async: Boolean) {
        val fixture = if (async) TestFixture.createAsync() else TestFixture.create()
        try {
            fixture.givenCommands(CreateItem("item"))
                .whenCommand(ChangeItem("item", false)).expectExceptionalResult(IllegalCommandException::class.java)
            fixture.whenCommand(ChangeItem("item", true)).expectSuccessfulResult()
        } finally { fixture.fluxzero.close() }
    }
    @Model data class Item(@EntityId val itemId: String, val changed: Boolean)
    data class CreateItem(val itemId: String) { @Apply fun apply() = Item(itemId, false) }
    data class ChangeItem(val itemId: String, val allowed: Boolean) {
        @AssertLegal fun check(item: Item) {
            if (!allowed) throw IllegalCommandException("Change is not allowed.")
        }
        @InterceptApply(assertCurrent = AssertCurrent.ENABLED)
        fun intercept(item: Item) = RecordChange(itemId)
    }
    data class RecordChange(val itemId: String) {
        @Apply fun apply(item: Item) = item.copy(changed = true)
    }
}
