package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.persisting.search.Searchable
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
        assertEquals("kotlin-models", annotation.searchProjection.collection)
        assertEquals(1, KotlinModel("model", emptyList()).rename(RenameKotlinModel("new")).parts.size)
    }
}

@Model(
    eventSourced = false,
    searchable = true,
    searchProjection = Searchable(collection = "kotlin-models"),
)
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

@Model
data class KotlinParent(@EntityId val id: KotlinParentId)

class KotlinParentId(id: String) : Id<KotlinParent>(id, "kotlin-parent-")

@Model
data class KotlinChild(
    @EntityId val id: String,
    @Parent(path = "children") val parentId: KotlinParentId,
    @Parent(value = KotlinParent::class, path = "externalChildren") val externalParentId: String,
)
