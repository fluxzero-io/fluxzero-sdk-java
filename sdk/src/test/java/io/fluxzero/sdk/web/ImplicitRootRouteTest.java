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

import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.fluxzero.sdk.web.HttpRequestMethod.HEAD;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;

class ImplicitRootRouteTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void servesImplicitRootWithAutomaticHeadAndOptions(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Root()) : TestFixture.create(new Root());
        try (var app = fixture.getFluxzero()) {
            fixture.whenGet("/").expectResult("root").expectNoErrors();
            fixture.whenGet("").expectResult("root").expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(HEAD).url("/").build())
                    .expectWebResult(r -> r.getStatus() == 200 && r.getPayload() == null).expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(OPTIONS).url("/").build())
                    .expectWebResult(r -> r.getStatus() == 204 && r.getHeader("Allow").contains(HEAD)).expectNoErrors();
        }
    }
    static class Root { @HandleGet String root() { return "root"; } }
}
