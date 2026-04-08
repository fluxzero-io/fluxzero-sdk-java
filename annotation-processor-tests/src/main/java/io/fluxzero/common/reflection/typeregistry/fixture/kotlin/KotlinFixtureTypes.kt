package io.fluxzero.common.reflection.typeregistry.fixture.kotlin

import io.fluxzero.common.serialization.RegisterType
import io.fluxzero.sdk.tracking.handling.HandleCommand

@RegisterType(root = "io.fluxzero.common.reflection.typeregistry.fixture.kotlin")
object KotlinFixtureTypeRegistryMarker

data class KotlinFixtureYieldsNoResult(val ignored: String = "")

data class KotlinFixtureYieldsResult(val ignored: String = "")

data class KotlinFixtureResult(val value: String = "kotlin-result")

class KotlinFixtureCommandHandler {
    @HandleCommand
    fun handle(command: KotlinFixtureYieldsNoResult) {
    }

    @HandleCommand
    fun handle(command: KotlinFixtureYieldsResult): KotlinFixtureResult {
        return KotlinFixtureResult()
    }
}
