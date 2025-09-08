package io.fluxzero.javaclient.modeling

import io.fluxzero.javaclient.modeling.AggregateEntitiesTest.MissingChild
import io.fluxzero.javaclient.tracking.handling.IllegalCommandException

data class KotlinUpdateCommandThatFailsIfChildDoesNotExist (val missingChildId: String) {
    @AssertLegal
    fun assertLegal(child: MissingChild?) {
        if (child == null) {
            throw IllegalCommandException("Expected a child")
        }
    }
}