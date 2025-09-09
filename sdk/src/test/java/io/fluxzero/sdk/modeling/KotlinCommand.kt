package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.modeling.AggregateEntitiesTest.MissingChild
import io.fluxzero.sdk.tracking.handling.IllegalCommandException

data class KotlinUpdateCommandThatFailsIfChildDoesNotExist (val missingChildId: String) {
    @AssertLegal
    fun assertLegal(child: MissingChild?) {
        if (child == null) {
            throw IllegalCommandException("Expected a child")
        }
    }
}