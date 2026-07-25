package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.persisting.eventsourcing.Apply
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelKotlinTest {
    @Test
    fun modelApiIsAvailableToKotlinProjects() {
        val annotation = KotlinModel::class.java.getAnnotation(Model::class.java)

        assertNotNull(annotation)
        assertFalse(annotation.eventSourced)
        assertTrue(annotation.searchable)
        assertEquals("kotlin-models", annotation.collection)
        assertEquals(1, KotlinModel("model", emptyList()).rename(RenameKotlinModel("new")).parts.size)
    }
}

@Model(eventSourced = false, searchable = true, collection = "kotlin-models")
data class KotlinModel(
    @EntityId val id: String,
    @Member val parts: List<KotlinModelPart>,
) {
    @Apply
    fun rename(command: RenameKotlinModel): KotlinModel {
        return copy(parts = listOf(KotlinModelPart(command.value)))
    }
}

data class KotlinModelPart(@EntityId val id: String)

data class RenameKotlinModel(val value: String)
