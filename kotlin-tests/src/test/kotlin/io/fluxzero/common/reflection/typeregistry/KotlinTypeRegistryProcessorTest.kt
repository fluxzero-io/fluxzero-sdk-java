package io.fluxzero.common.reflection.typeregistry

import io.fluxzero.common.reflection.ReflectionUtils
import io.fluxzero.common.reflection.typeregistry.kotlin.KotlinDirectlyRegisteredType
import io.fluxzero.common.reflection.typeregistry.kotlin.events.KotlinPackageRegisteredType
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "fluxzero.maven.enabled", matches = "true")
class KotlinTypeRegistryProcessorTest {

    @Test
    fun classForName() {
        assertEquals(KotlinDirectlyRegisteredType::class.java, ReflectionUtils.classForName("KotlinDirectlyRegisteredType"))
        assertEquals(KotlinPackageRegisteredType::class.java, ReflectionUtils.classForName("KotlinPackageRegisteredType"))
        assertEquals(
            KotlinPackageRegisteredType::class.java,
            ReflectionUtils.classForName("kotlin.events.KotlinPackageRegisteredType"),
        )
    }
}
