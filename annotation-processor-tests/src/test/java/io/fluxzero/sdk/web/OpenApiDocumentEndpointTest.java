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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.openapiauto.DocumentedPackageAutoHandler;
import io.fluxzero.sdk.web.openapiauto.ExcludedPackageAutoHandler;
import io.fluxzero.sdk.web.openapiauto.SecondDocumentedPackageAutoHandler;
import org.junit.jupiter.api.Test;

class OpenApiDocumentEndpointTest {
    @Test
    void servesGeneratedDocumentForPackageScopedInstanceHandlers() {
        TestFixture.create(
                        new ExcludedPackageAutoHandler(),
                        new SecondDocumentedPackageAutoHandler(),
                        new DocumentedPackageAutoHandler())
                .whenGet("/packageAuto/openapi.json")
                .expectWebResult(response -> {
                    String payload = response.getPayloadAs(String.class);
                    JsonNode document = JsonUtils.fromJson(payload, JsonNode.class);
                    return response.getStatus() == 200
                           && "application/json".equals(response.getContentType())
                           && document.path("paths").has("/packageAuto/items")
                           && document.path("paths").has("/packageAuto/second")
                           && !document.path("paths").has("/packageAuto/hidden");
                });
    }
}
