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

package io.fluxzero.sdk.web;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;

import static io.fluxzero.sdk.web.HttpRequestMethod.WS_MESSAGE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_OPEN;

class SocketErrorDisclosureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestErrorsRespectPublicBoundaryWithSyncAndAsyncTracking(boolean async) {
        for (String command : new String[]{"technical", "async-technical", "functional", "async-functional"}) {
            SocketRequest request = SocketRequest.valueOf(command);
            TestFixture fixture = async ? TestFixture.createAsync(Endpoint.class) : TestFixture.create(Endpoint.class);
            fixture.givenWebRequest(frame(WS_OPEN, null))
                    .whenWebRequest(frame(WS_MESSAGE, request))
                    .expectResult(SocketResponse.error(request.getRequestId(),
                            command.endsWith("functional") ? "Choose another date" : "Request failed"));
        }
    }

    private WebRequest frame(String method, Object payload) {
        return WebRequest.builder().method(method).url("/socket")
                .metadata(Metadata.of("sessionId", "session")).payload(payload).build();
    }

    @SocketEndpoint
    @Path("/socket")
    static class Endpoint {
        @HandleSocketOpen
        void open() {
        }

        @HandleSocketMessage
        Object handle(String command) {
            RuntimeException failure = command.endsWith("functional")
                    ? new IllegalCommandException("Choose another date")
                    : new IllegalStateException("private implementation detail");
            if (command.startsWith("async")) {
                return CompletableFuture.failedFuture(failure);
            }
            throw failure;
        }
    }
}
