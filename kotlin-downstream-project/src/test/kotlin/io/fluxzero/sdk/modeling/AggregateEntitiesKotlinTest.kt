package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero.loadAggregate
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.publishing.routing.RoutingKey
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

    @Test
    fun updatesNestedMemberInDataClassOwnerWithoutExplicitWither() {
        TestFixture.create()
            .given {
                loadAggregate("kotlin-copy", KotlinConstructorAggregate::class.java).update {
                    KotlinConstructorAggregate(
                        id = "kotlin-copy",
                        child = KotlinConstructorChild(
                            childId = "child",
                            grandChild = KotlinConstructorGrandChild("grand-child"),
                        ),
                    )
                }
            }
            .registerHandlers(KotlinConstructorCommandHandler())
            .whenCommand(KotlinUpdateGrandChild("grand-child", "updated-grand-child"))
            .expectTrue {
                loadAggregate("kotlin-copy", KotlinConstructorAggregate::class.java)
                    .get()
                    .child
                    .grandChild
                    .grandChildId == "updated-grand-child"
            }
    }

    class CommandHandler {
        @HandleCommand
        fun handle(command: KotlinUpdateCommandThatFailsIfChildDoesNotExist) {
            loadAggregate("test", KotlinAggregate::class.java).assertAndApply(command)
        }
    }

    class KotlinConstructorCommandHandler {
        @HandleCommand
        fun handle(command: KotlinUpdateGrandChild) {
            loadAggregate("kotlin-copy", KotlinConstructorAggregate::class.java).apply(command)
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

data class KotlinConstructorAggregate(
    @EntityId val id: String,
    @Member val child: KotlinConstructorChild,
)

data class KotlinConstructorChild(
    @EntityId val childId: String,
    @Member val grandChild: KotlinConstructorGrandChild,
)

data class KotlinConstructorGrandChild(
    @EntityId val grandChildId: String,
)

data class KotlinUpdateGrandChild(
    @RoutingKey val grandChildId: String,
    val newGrandChildId: String,
) {
    @Apply
    fun apply(grandChild: KotlinConstructorGrandChild): KotlinConstructorGrandChild {
        return KotlinConstructorGrandChild(newGrandChildId)
    }
}
