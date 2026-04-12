package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero.loadAggregate
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.HandleCommand
import io.fluxzero.sdk.tracking.handling.IllegalCommandException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

class AggregateEntitiesKotlinTest {
    private lateinit var testFixture: TestFixture

    @BeforeEach
    fun setUp() {
        testFixture = TestFixture.create()
            .given { loadAggregate("test", KotlinAggregate::class.java).update { KotlinAggregate() } }
        testFixture.registerHandlers(CommandHandler())
    }

    @Test
    fun updateCommandExpectsExistingChild() {
        testFixture.whenCommand(KotlinUpdateCommandThatFailsIfChildDoesNotExist("whatever"))
            .expectExceptionalResult(IllegalCommandException::class.java)
            .expectNoEvents()
    }

    class CommandHandler {
        @HandleCommand
        fun handle(command: KotlinUpdateCommandThatFailsIfChildDoesNotExist) {
            loadAggregate("test", KotlinAggregate::class.java).assertAndApply(command)
        }
    }
}

data class KotlinUpdateCommandThatFailsIfChildDoesNotExist(val missingChildId: String) {
    @AssertLegal
    fun assertLegal(child: KotlinMissingChild?, aggregate: KotlinAggregate) {
        if (child == null) {
            throw IllegalCommandException("Expected a child")
        }
    }
}
