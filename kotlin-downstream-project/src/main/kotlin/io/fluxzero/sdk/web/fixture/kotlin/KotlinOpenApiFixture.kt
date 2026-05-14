package io.fluxzero.sdk.web.fixture.kotlin

import io.fluxzero.sdk.web.ApiDoc
import io.fluxzero.sdk.web.ApiDocInfo
import io.fluxzero.sdk.web.HandleGet
import io.fluxzero.sdk.web.Path
import io.fluxzero.sdk.web.PathParam
import io.fluxzero.sdk.web.QueryParam

@ApiDocInfo(title = "Kotlin Processor API", version = "v1")
@ApiDoc(tags = ["kotlin"])
@Path("/kotlin")
class KotlinOpenApiEndpoint {

    @HandleGet("/items/{itemId}")
    @ApiDoc(summary = "Fetch Kotlin item", operationId = "getKotlinItem")
    fun getItem(
        @PathParam itemId: String,
        @QueryParam @ApiDoc(description = "Optional item filter") filter: String?,
    ): KotlinOpenApiItem {
        return KotlinOpenApiItem(itemId, listOf(filter ?: "default"))
    }
}

data class KotlinOpenApiItem(
    @field:ApiDoc(description = "Item identifier")
    val id: String,
    @field:ApiDoc(description = "Visible labels")
    val labels: List<String>,
)
