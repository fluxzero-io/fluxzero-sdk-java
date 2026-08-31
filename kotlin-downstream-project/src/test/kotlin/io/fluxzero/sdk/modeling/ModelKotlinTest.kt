package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ModelKotlinTest {
    @Test
    fun modelApiIsAvailableToKotlinProjects() {
        val annotation = KotlinModel::class.java.getAnnotation(Model::class.java)

        assertNotNull(annotation)
        assertEquals(ModelPersistence.DOCUMENT, annotation.persistence)
        assertEquals("kotlin-models", annotation.document.collection)
        assertEquals(1, KotlinModel("model", emptyList()).rename(RenameKotlinModel("new")).parts.size)
    }

    @Suppress("unused")
    private fun typedGraphSearch(): List<Graph<KotlinModel>> {
        return Fluxzero.searchGraph(KotlinModel::class.java).fetchAll()
    }
}

@Model(
    persistence = ModelPersistence.DOCUMENT,
    document = DocumentProjection(collection = "kotlin-models"),
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
    @Parent(pathInParent = "children") val parentId: KotlinParentId,
    @Parent(value = KotlinParent::class, pathInParent = "externalChildren") val externalParentId: String,
)
