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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiProcessorJsonTest {
    @Test
    void openApiJsonDoesNotUseJacksonServiceLoader(@TempDir Path tempDir) throws IOException {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = serviceLoaderPoisoningClassLoader(tempDir)) {
            Thread.currentThread().setContextClassLoader(classLoader);
            assertThrows(ServiceConfigurationError.class,
                         () -> ServiceLoader.load(com.fasterxml.jackson.databind.Module.class, classLoader)
                                 .stream().forEach(provider -> provider.get()));

            JsonNode parsed = OpenApiProcessor.parseOpenApiJson("{ /* comment */ \"example\": 1.20 }");
            assertEquals("1.20", parsed.path("example").decimalValue().toPlainString());

            ObjectNode document = JsonNodeFactory.instance.objectNode();
            document.put("openapi", "3.0.1");
            document.putObject("info").put("title", "Processor API").put("version", "v1");

            String json = OpenApiProcessor.asOpenApiJson(document);
            assertTrue(json.contains("\"openapi\" : \"3.0.1\""));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static URLClassLoader serviceLoaderPoisoningClassLoader(Path tempDir) throws IOException {
        Path serviceFile = tempDir.resolve("META-INF/services/com.fasterxml.jackson.databind.Module");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, NotJacksonModule.class.getName() + "\n");
        URL[] urls = {tempDir.toUri().toURL()};
        return new URLClassLoader(urls, OpenApiProcessorJsonTest.class.getClassLoader());
    }

    public static final class NotJacksonModule {
    }
}
