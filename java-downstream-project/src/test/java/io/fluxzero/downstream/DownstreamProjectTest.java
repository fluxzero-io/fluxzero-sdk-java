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

import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.serialization.TypeRegistryProcessor;
import io.fluxzero.proxy.ProxyServer;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.OpenApiProcessor;
import io.fluxzero.testserver.TestServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownstreamProjectTest {

    @Test
    void bomManagedMainAndTestArtifactsAreUsableWithoutInheritedParentDependencies() {
        assertEquals("io.fluxzero.testserver.TestServer", TestServer.class.getName());
        assertEquals("io.fluxzero.proxy.ProxyServer", ProxyServer.class.getName());
        assertNotNull(ProxyServer.class.getDeclaredMethods());

        TestFixture.create(new DownstreamHandler())
                .whenCommand(new DownstreamCommand("external-downstream"))
                .expectResult(new DownstreamResult("external-downstream"));
    }

    @Test
    void annotationProcessorsRunFromDownstreamCompilerConfiguration() throws IOException {
        String registry = readResource(TypeRegistryProcessor.TYPES_FILE);
        assertTrue(registry.contains(DownstreamCommand.class.getName()));
        assertTrue(registry.contains(DownstreamResult.class.getName()));

        var openApi = JsonUtils.readTree(resourceBytes(OpenApiProcessor.DEFAULT_OUTPUT));
        assertEquals("Downstream Project API", openApi.path("info").path("title").asText());
        assertEquals("getDownstreamCommand",
                     openApi.path("paths").path("/downstream/{id}").path("get").path("operationId").asText());
    }

    private static String readResource(String name) throws IOException {
        return new String(resourceBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (var input = DownstreamProjectTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, "Missing classpath resource " + name);
            return input.readAllBytes();
        }
    }
}
