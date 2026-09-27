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

package io.fluxzero.downstream;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.QueryGateway;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestGuaranteeDownstreamTest {
    @ParameterizedTest
    @EnumSource(value = Guarantee.class, names = {"DEFAULT", "NONE", "SENT", "STORED"})
    void typedRequestsKeepResultInferenceAndLocalHandling(Guarantee guarantee) {
        try (var fluxzero = TestFixture.create(new Handler()).getFluxzero()) {
            fluxzero.execute(f -> {
                var request = new Echo("response");
                var metadata = Metadata.empty();
                CompletableFuture<String> command = Fluxzero.sendCommand(request, metadata, guarantee);
                CompletableFuture<String> query = Fluxzero.query(request, metadata, guarantee);
                String commandResult = Fluxzero.sendCommandAndWait(request, metadata, guarantee);
                String queryResult = Fluxzero.queryAndWait(request, metadata, guarantee);
                assertEquals("response", command.join());
                assertEquals("response", query.join());
                assertEquals("response", commandResult);
                assertEquals("response", queryResult);
                assertEquals("response", f.commandGateway().send(request, metadata, guarantee).join());
                assertEquals("response", f.queryGateway().send(request, metadata, guarantee).join());
                assertEquals("response", f.commandGateway().sendAndWait(request, metadata, guarantee));
                assertEquals("response", f.queryGateway().sendAndWait(request, metadata, guarantee));
            });
        }
    }

    // Compile-only compatibility checks: no new ambiguity for existing two-argument calls.
    private void existingNullOverloads(CommandGateway commands, QueryGateway queries) {
        commands.send(null, Metadata.empty());
        commands.sendAndWait(null, Metadata.empty());
        queries.send(null, Metadata.empty());
        queries.sendAndWait(null, Metadata.empty());
        Fluxzero.sendCommand(null, Metadata.empty());
        Fluxzero.sendCommandAndWait(null, Metadata.empty());
        Fluxzero.query(null, Metadata.empty());
        Fluxzero.queryAndWait(null, Metadata.empty());
    }

    record Echo(String value) implements Request<String> {}

    static class Handler {
        @HandleCommand
        @HandleQuery
        String handle(Echo request) { return request.value(); }
    }
}
