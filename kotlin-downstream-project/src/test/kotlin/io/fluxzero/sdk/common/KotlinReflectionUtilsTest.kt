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

package io.fluxzero.sdk.common

import io.fluxzero.common.reflection.ReflectionUtils
import io.fluxzero.sdk.persisting.search.Searchable
import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@Searchable(collection = "some")
data class SomeObject(val value: String)

class KotlinReflectionUtilsTest {
    private val testFixture: TestFixture = TestFixture.create()

    @Test
    fun retainsKotlinParameterNullability() {
        val constructor = NullabilityExample::class.java.getDeclaredConstructor(String::class.java, String::class.java)
        assertTrue(ReflectionUtils.isNullable(constructor.parameters[0]))
        assertFalse(ReflectionUtils.isNullable(constructor.parameters[1]))
        val method = NullabilityExample::class.java.getDeclaredMethod("accept", String::class.java)
        assertTrue(ReflectionUtils.isNullable(method.parameters[0]))
    }

    @Test
    fun javaOverrideRetainsInheritedKotlinNullability() {
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(NullableContract::class.java)
        ) { _, _, _ -> null }
        val method = proxy.javaClass.getDeclaredMethod("accept", String::class.java)
        assertFalse(method.declaringClass.declaredAnnotations.any { it.annotationClass.java.name == "kotlin.Metadata" })
        assertTrue(ReflectionUtils.isNullable(method.parameters[0]))
    }

    @Test
    fun getSearchCollectionUsingClass() {
        testFixture.whenApplying { ReflectionUtils.ifClass(SomeObject::class) }
            .expectResult(SomeObject::class.java)
    }
}

private class NullabilityExample(val optional: String?, val required: String) {
    fun accept(value: String?) = value
}

private interface NullableContract {
    fun accept(value: String?)
}
