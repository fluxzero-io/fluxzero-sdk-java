package io.fluxzero.sdk.modeling

import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository
import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelKotlinTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun embeddedMemberUpdatesUseKotlinCopyAndReplay(async: Boolean) {
        val fixture = if (async) TestFixture.createAsync() else TestFixture.create()
        fixture.givenCommands(CreateMemberOwner("owner"))
            .whenExecuting { _ ->
                val before = Fluxzero.loadModel("owner", KotlinMemberOwner::class.java).get()
                Fluxzero.loadGraph("owner", KotlinMemberOwner::class.java)
                    .assertAndApply(RenameMember("member", "after"))
                assertEquals("before", before.parts.single().name)
            }.expectSuccessfulResult().expectNoErrors()
            .expectThat { fc ->
                (fc.modelRepository() as DefaultModelRepository).invalidateModels(listOf("owner"))
                assertEquals(KotlinMemberOwner("owner", listOf(KotlinMember("member", "after"))),
                    Fluxzero.loadModel("owner", KotlinMemberOwner::class.java).get())
                assertEquals(2L, fc.eventStore().getEvents("owner").count())
                assertEquals(0L, fc.eventStore().getEvents("member").count())
            }
    }

    @Test
    fun discoversModelsFromKaptWithoutRegisterType() {
        assertTrue(ModelTypes.discover().contains(io.fluxzero.models.KotlinDiscoveredModel::class.java))
    }

    @Test
    fun modelApiIsAvailableToKotlinProjects() {
        val annotation = KotlinModel::class.java.getAnnotation(Model::class.java)

        assertNotNull(annotation)
        assertContentEquals(arrayOf(ModelPersistence.DOCUMENT), annotation.persistence)
        assertEquals("kotlin-models", annotation.document.collection)
        assertEquals(1, KotlinModel("model", emptyList()).rename(RenameKotlinModel("new")).parts.size)
    }

    @Suppress("unused")
    private fun typedGraphSearch(): List<Graph<KotlinModel>> {
        return Fluxzero.searchGraph(KotlinModel::class.java).fetchAll()
    }
}

@Model
data class KotlinMemberOwner(@EntityId val id: String, @Member val parts: List<KotlinMember>)
data class KotlinMember(@EntityId val memberId: String, val name: String) {
    @Apply fun rename(command: RenameMember) = copy(name = command.name)
}
data class RenameMember(val memberId: String, val name: String)
data class CreateMemberOwner(val id: String) {
    @Apply fun create() = KotlinMemberOwner(id, listOf(KotlinMember("member", "before")))
}

@Model(
    persistence = [ModelPersistence.DOCUMENT],
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
