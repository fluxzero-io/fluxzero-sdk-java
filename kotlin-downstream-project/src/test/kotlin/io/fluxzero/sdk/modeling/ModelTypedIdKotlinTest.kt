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
package io.fluxzero.sdk.modeling

import io.fluxzero.common.reflection.ReflectionUtils
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ModelTypedIdKotlinTest {
    private val id = WidgetId("widget")

    @Test fun recognizesGeneratedJvmMangledComponents() {
        val component = Class.forName("kotlin.time.TimedValue").declaredMethods
            .first { it.name.startsWith("component2-") }
        assertTrue(ReflectionUtils.isKotlinDataClassComponent(component))
    }

    @Test fun generatedComponentIsNotASecondTypedId() {
        TestFixture.create().givenCommands(CreateWidget(id))
            .whenCommand(RenameViaId(id)).expectSuccessfulResult().expectNoErrors()
            .expectThat { assertEquals(1, Fluxzero.loadModel(id).get().version) }
        assertTrue(ReflectionUtils.isKotlinDataClassComponent(RenameViaId::class.java.getMethod("component1")))
    }

    @Test fun handwrittenComponentOnOrdinaryClassRemainsAValidComputedId() {
        TestFixture.create().givenCommands(CreateWidget(id))
            .whenCommand(ComputedId("widget")).expectSuccessfulResult().expectNoErrors()
        assertFalse(ReflectionUtils.isKotlinDataClassComponent(ComputedId::class.java.getMethod("component1")))
    }

    @Test fun additionalHandwrittenDataClassComponentRemainsAValidComputedId() {
        TestFixture.create().givenCommands(CreateWidget(id))
            .whenCommand(AdditionalComponent("widget")).expectSuccessfulResult().expectNoErrors()
        assertFalse(ReflectionUtils.isKotlinDataClassComponent(AdditionalComponent::class.java.getMethod("component2")))
    }

    @Test fun distinctTypedPropertiesStillRejectAmbiguity() {
        TestFixture.create().givenCommands(CreateWidget(id))
            .whenCommand(Ambiguous(id, WidgetId("other"))).expectExceptionalResult(IllegalStateException::class.java)
            .expectNoEvents()
    }

    @Model data class Widget(@EntityId val widgetId: WidgetId, val version: Int)
    class WidgetId(value: String) : Id<Widget>(value)
    data class CreateWidget(val widgetId: WidgetId) { @Apply fun apply() = Widget(widgetId, 0) }
    data class RenameViaId(val requested: WidgetId) {
        @Apply fun apply(current: Widget) = current.copy(version = current.version + 1)
    }
    class ComputedId(val raw: String) {
        fun component1() = WidgetId(raw)
        @Apply fun apply(current: Widget) = current.copy(version = current.version + 1)
    }
    data class AdditionalComponent(val raw: String) {
        fun component2() = WidgetId(raw)
        @Apply fun apply(current: Widget) = current.copy(version = current.version + 1)
    }
    data class Ambiguous(val first: WidgetId, val second: WidgetId) {
        @Apply fun apply(current: Widget) = current.copy(version = current.version + 1)
    }
}
