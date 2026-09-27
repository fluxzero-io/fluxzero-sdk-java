/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.publishing

import io.fluxzero.common.Guarantee
import io.fluxzero.common.api.Metadata
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.HandleCommand
import io.fluxzero.sdk.tracking.handling.HandleQuery
import io.fluxzero.sdk.tracking.handling.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CompletableFuture

class RequestGuaranteeKotlinTest {
    @ParameterizedTest
    @EnumSource(value = Guarantee::class, names = ["DEFAULT", "NONE", "SENT", "STORED"])
    fun typedRequestsKeepResultInferenceAndLocalHandling(guarantee: Guarantee) {
        TestFixture.create(Handler()).fluxzero.use { fluxzero ->
            fluxzero.execute { f ->
                val request = Echo("response")
                val metadata = Metadata.empty()
                val command: CompletableFuture<String> = Fluxzero.sendCommand(request, metadata, guarantee)
                val query: CompletableFuture<String> = Fluxzero.query(request, metadata, guarantee)
                val commandResult: String = Fluxzero.sendCommandAndWait(request, metadata, guarantee)
                val queryResult: String = Fluxzero.queryAndWait(request, metadata, guarantee)
                assertEquals("response", command.join())
                assertEquals("response", query.join())
                assertEquals("response", commandResult)
                assertEquals("response", queryResult)
                assertEquals("response", f.commandGateway().send(request, metadata, guarantee).join())
                assertEquals("response", f.queryGateway().send(request, metadata, guarantee).join())
                assertEquals("response", f.commandGateway().sendAndWait(request, metadata, guarantee))
                assertEquals("response", f.queryGateway().sendAndWait(request, metadata, guarantee))
            }
        }
    }

    // Compile-only: preserve existing overload selection with a null literal.
    private fun existingNullOverloads(commands: CommandGateway, queries: QueryGateway) {
        commands.send<String>(null, Metadata.empty())
        commands.sendAndWait<String>(null, Metadata.empty())
        queries.send<String>(null, Metadata.empty())
        queries.sendAndWait<String>(null, Metadata.empty())
        Fluxzero.sendCommand<String>(null, Metadata.empty())
        Fluxzero.sendCommandAndWait<String>(null, Metadata.empty())
        Fluxzero.query<String>(null, Metadata.empty())
        Fluxzero.queryAndWait<String>(null, Metadata.empty())
    }

    data class Echo(val value: String) : Request<String>

    class Handler {
        @HandleCommand
        @HandleQuery
        fun handle(request: Echo): String = request.value
    }
}
