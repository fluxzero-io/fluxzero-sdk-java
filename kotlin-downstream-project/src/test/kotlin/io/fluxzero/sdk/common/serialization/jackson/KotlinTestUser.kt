package io.fluxzero.sdk.common.serialization.jackson

import io.fluxzero.sdk.tracking.handling.authentication.User

class KotlinTestUser(
    private vararg val roles: String,
) : User {
    override fun hasRole(role: String): Boolean = roles.contains(role)

    override fun getName(): String = "kotlinTestUser"
}
