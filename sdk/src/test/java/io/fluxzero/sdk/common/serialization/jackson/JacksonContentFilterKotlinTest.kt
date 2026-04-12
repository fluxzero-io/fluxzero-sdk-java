package io.fluxzero.sdk.common.serialization.jackson

import io.fluxzero.common.serialization.JsonUtils
import io.fluxzero.sdk.tracking.handling.authentication.MockUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JacksonContentFilterKotlinTest {

    private val serializer = JacksonSerializer()

    @Test
    fun `writer keeps kotlin constructor binding intact`() {
        val input = KotlinProfile("user-1", "visible")
        val result = JsonUtils.writer.convertValue(input, KotlinProfile::class.java)

        assertEquals(input, result)
    }

    @Test
    fun `writer copy keeps kotlin constructor binding intact`() {
        val input = KotlinProfile("user-1", "visible")
        val mapper = JsonUtils.writer.copy()

        val result = mapper.convertValue(input, KotlinProfile::class.java)

        assertEquals(input, result)
    }

    @Test
    fun `filterContent applies kotlin data class filter result`() {
        val input = KotlinProfile("user-1", "visible")

        val result = serializer.filterContent(input, MockUser("user"))

        assertNotNull(result)
        assertEquals(input.id, result.id)
        assertNull(result.name)
    }
}
