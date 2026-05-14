package io.fluxzero.sdk.common.serialization

import com.fasterxml.jackson.annotation.JsonIgnore
import io.fluxzero.common.serialization.JsonUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonUtilsKotlinTest {

    @Test
    fun `disableJsonIgnore restores ignored kotlin property during serialization`() {
        val mapper = JsonUtils.writer.copy()
        JsonUtils.disableJsonIgnore(mapper)
        val input = JsonIgnoredKotlinSample("public", "private")

        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(input)

        assertTrue(tree.has("hidden"))
        assertEquals(input.visible, tree.get("visible").asText())
        assertEquals(input.hidden, tree.get("hidden").asText())
    }

    @Test
    fun `disableJsonIgnore keeps kotlin round trip working for ignored non-null property`() {
        val mapper = JsonUtils.writer.copy()
        JsonUtils.disableJsonIgnore(mapper)
        val input = JsonIgnoredKotlinSample("public", "private")

        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(input)
        val output = mapper.treeToValue(tree, JsonIgnoredKotlinSample::class.java)

        assertEquals(input, output)
    }

    data class JsonIgnoredKotlinSample(
        val visible: String,
        @get:JsonIgnore val hidden: String,
    )
}
