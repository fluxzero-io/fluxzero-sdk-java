package io.fluxzero.sdk.common.serialization.jackson

import io.fluxzero.sdk.common.serialization.FilterContent

data class KotlinProfile(
    val id: String,
    val name: String?,
) {
    @FilterContent
    fun filter(viewer: KotlinTestUser): KotlinProfile? = if (viewer.hasRole("admin")) this else copy(name = null)
}
