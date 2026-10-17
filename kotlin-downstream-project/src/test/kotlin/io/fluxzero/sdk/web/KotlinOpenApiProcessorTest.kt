package io.fluxzero.sdk.web

import com.fasterxml.jackson.databind.JsonNode
import io.fluxzero.common.serialization.JsonUtils
import io.fluxzero.sdk.web.fixture.kotlin.KotlinRootEndpoint
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class KotlinOpenApiProcessorTest {

    @Test
    fun generatesOpenApiDocumentFromKotlinKaptStubs() {
        val json = javaClass.classLoader.getResourceAsStream(OpenApiProcessor.DEFAULT_OUTPUT)?.use {
            it.readAllBytes()
        }

        assertNotNull(json)
        val document = JsonUtils.fromJson(json, JsonNode::class.java)

        assertEquals("Kotlin Processor API", document.path("info").path("title").asText())
        assertEquals("v1", document.path("info").path("version").asText())
        assertTrue(document.path("paths").has("/kotlin/items/{itemId}"))

        val operation = document.path("paths").path("/kotlin/items/{itemId}").path("get")
        assertEquals("Fetch Kotlin item", operation.path("summary").asText())
        assertEquals("getKotlinItem", operation.path("operationId").asText())
        assertEquals("kotlin", operation.path("tags").get(0).asText())
        assertEquals("Optional item filter", operation.path("parameters").get(1).path("description").asText())

        val runtime = OpenApiRenderer.render(ApiDocExtractor.extract(KotlinRootEndpoint::class.java))
        for (generated in listOf(document, runtime)) {
            val paths = generated.path("paths")
            assertFalse(paths.has(""))
            val root = paths.path("/")
            assertEquals("kotlinRoot", root.path("get").path("operationId").asText())
            assertEquals("kotlinExplicitRoot", root.path("post").path("operationId").asText())
            assertEquals("kotlinAbsoluteRoot", root.path("put").path("operationId").asText())
            assertEquals("https://kotlin-root.example",
                root.path("put").path("servers").get(0).path("url").asText())
        }

        val schema = document.path("components").path("schemas").path("KotlinOpenApiItem")
        assertEquals("Item identifier", schema.path("properties").path("id").path("description").asText())
        assertEquals("Visible labels", schema.path("properties").path("labels").path("description").asText())
    }
}
