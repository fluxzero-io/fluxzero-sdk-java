package io.fluxzero.sdk.modeling

data class KotlinAggregate(
    @EntityId val id: String = "test",
    @Member val missingChild: KotlinMissingChild? = null,
)

data class KotlinMissingChild(
    @EntityId val missingChildId: String,
)
