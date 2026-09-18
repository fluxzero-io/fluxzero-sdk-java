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

import io.fluxzero.common.api.modeling.ModelConflictPolicy
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.IllegalCommandException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ModelReadBoundaryDocumentationKotlinTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun independentlyBoundProductMustBeActive(async: Boolean) {
        val fixture = if (async) TestFixture.createAsync() else TestFixture.create()
        try {
            fixture.givenCommands(PutProduct("active", true), PutProduct("inactive", false))
                .whenCommand(Reserve("allowed", "active")).expectSuccessfulResult()
                .andThen().whenCommand(Reserve("denied", "inactive"))
                .expectExceptionalResult(IllegalCommandException::class.java)
                .andThen().whenCommand(Reserve("absent", "missing"))
                .expectExceptionalResult(IllegalCommandException::class.java)
        } finally { fixture.fluxzero.close() }
    }

    @Model data class Product(@EntityId val productId: String, val active: Boolean)
    @Model data class Reservation(@EntityId val reservationId: String)

    data class Reserve(val reservationId: String, val productId: String) {
        @AssertLegal
        fun check(product: Product?) {
            if (product?.active != true) throw IllegalCommandException("Product is not active")
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        fun apply() = Reservation(reservationId)
    }

    data class PutProduct(val productId: String, val active: Boolean) {
        @Apply fun apply() = Product(productId, active)
    }
}
