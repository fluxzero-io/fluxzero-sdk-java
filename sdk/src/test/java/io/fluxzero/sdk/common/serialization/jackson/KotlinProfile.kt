package io.fluxzero.sdk.common.serialization.jackson

import io.fluxzero.sdk.common.serialization.FilterContent
import io.fluxzero.sdk.tracking.handling.authentication.MockUser

data class KotlinProfile(
    val id: String,
    val name: String?
) {
    @FilterContent
    fun filter(viewer: MockUser): KotlinProfile? = if (viewer.hasRole("admin")) this else copy(name = null)
}
