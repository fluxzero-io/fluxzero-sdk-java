package io.fluxzero.common.reflection.typeregistry.fixture.kotlin

import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "fluxzero.maven.enabled", matches = "true")
class GivenWhenThenKotlinFixtureTest {
    private val testFixture = TestFixture.create(KotlinFixtureCommandHandler())

    @Test
    fun testGivenCommandsAsJson() {
        testFixture.givenCommands("kotlin-yields-result.json")
            .whenCommand(KotlinFixtureYieldsNoResult())
            .expectNoResult()
            .expectNoEvents()
    }

    @Test
    fun testExpectAsJson() {
        testFixture.whenCommand("kotlin-yields-result.json")
            .expectResult("kotlin-result.json")
    }
}
