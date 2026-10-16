package io.fluxzero.sdk.modeling

@Aggregate
data class KotlinAggregate(
    @EntityId val id: String = "test",
    @Member val missingChild: KotlinMissingChild? = null,
)

data class KotlinMissingChild(
    @EntityId val missingChildId: String,
)
