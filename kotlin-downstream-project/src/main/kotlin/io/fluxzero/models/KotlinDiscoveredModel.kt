package io.fluxzero.models

import io.fluxzero.sdk.modeling.EntityId
import io.fluxzero.sdk.modeling.Model

// Deliberately outside every @RegisterType root: Model discovery is its own contract.
@Model
data class KotlinDiscoveredModel(@EntityId val id: String)
