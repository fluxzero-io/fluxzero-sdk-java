package io.fluxzero.common.reflection.typeregistry.kotlin

import io.fluxzero.common.serialization.RegisterType

@RegisterType
data class KotlinDirectlyRegisteredType(val value: String = "")
