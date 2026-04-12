package io.fluxzero.sdk.common.serialization.jackson

import com.fasterxml.jackson.annotation.JsonIgnore
import io.fluxzero.common.serialization.JsonUtils
import io.fluxzero.sdk.common.serialization.FilterContent
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

        val result = serializer.filterContent(input, KotlinTestUser("user"))

        assertNotNull(result)
        assertEquals(input.id, result.id)
        assertNull(result.name)
    }

    @Test
    fun `filterContent keeps ignored kotlin property when filter returns same object`() {
        val input = KotlinIgnoredProfile("user-1", "visible", "private")

        val result = serializer.filterContent(input, KotlinTestUser("admin"))

        assertNotNull(result)
        assertEquals(input, result)
    }

    @Test
    fun `filterContent keeps ignored kotlin property when filter returns a copy`() {
        val input = KotlinIgnoredProfile("user-1", "visible", "private")

        val result = serializer.filterContent(input, KotlinTestUser("user"))

        assertNotNull(result)
        assertEquals(input.id, result.id)
        assertEquals(input.name, result.name)
        assertEquals("redacted", result.hidden)
    }

    data class KotlinIgnoredProfile(
        val id: String,
        val name: String?,
        @get:JsonIgnore val hidden: String,
    ) {
        @FilterContent
        fun filter(viewer: KotlinTestUser): KotlinIgnoredProfile =
            if (viewer.hasRole("admin")) this else copy(hidden = "redacted")
    }
}
