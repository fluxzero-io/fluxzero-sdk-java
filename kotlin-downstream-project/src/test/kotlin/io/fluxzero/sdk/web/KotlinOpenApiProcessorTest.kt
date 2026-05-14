package io.fluxzero.sdk.web

import com.fasterxml.jackson.databind.JsonNode
import io.fluxzero.common.serialization.JsonUtils
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

        val schema = document.path("components").path("schemas").path("KotlinOpenApiItem")
        assertEquals("Item identifier", schema.path("properties").path("id").path("description").asText())
        assertEquals("Visible labels", schema.path("properties").path("labels").path("description").asText())
    }
}
