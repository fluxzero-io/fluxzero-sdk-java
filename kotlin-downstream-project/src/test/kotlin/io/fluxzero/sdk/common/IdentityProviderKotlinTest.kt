package io.fluxzero.sdk.common

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityProviderKotlinTest {
    @Test
    fun defaultProviderRetainsItsInheritedFieldAccess() {
        val provider: IdentityProvider = IdentityProvider.defaultIdentityProvider
        assertTrue(provider is UuidFactory)
        assertSame(provider, IdentityProvider::class.java.getField("defaultIdentityProvider").get(null))
    }
}
