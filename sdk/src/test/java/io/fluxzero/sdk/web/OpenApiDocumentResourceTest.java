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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiDocumentResourceTest {
    @Test
    void mergesEveryDocumentOnTheClasspath(@TempDir Path tempDir) throws Exception {
        Path first = writeResource(tempDir.resolve("first"), document("3.0.1", "/first", "getFirst"));
        Path second = writeResource(tempDir.resolve("second"), document("3.0.1", "/second", "getSecond"));

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{second.toUri().toURL(), first.toUri().toURL()}, null)) {
            JsonNode result = parsedDocument(classLoader);

            assertTrue(result.path("paths").has("/first"));
            assertTrue(result.path("paths").has("/second"));
        }
    }

    @Test
    void readsDocumentsConcatenatedByAnApplicationShadeTransformer(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("application.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(OpenApiProcessor.DEFAULT_OUTPUT));
            output.write((document("3.1.0", "/first", "getFirst") + System.lineSeparator()
                          + document("3.1.0", "/second", "getSecond")).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            JsonNode result = parsedDocument(classLoader);

            assertEquals("3.1.0", result.path("openapi").textValue());
            assertTrue(result.path("paths").has("/first"));
            assertTrue(result.path("paths").has("/second"));
        }
    }

    @Test
    void supportsNestedJarResourceUrlsExposedByTheApplicationClassLoader() {
        ClassLoader classLoader = new ResourceClassLoader(List.of(
                resourceUrl("nested:/application.jar/!BOOT-INF/lib/first.jar!/" + OpenApiProcessor.DEFAULT_OUTPUT,
                            document("3.0.1", "/first", "getFirst")),
                resourceUrl("nested:/application.jar/!BOOT-INF/lib/second.jar!/" + OpenApiProcessor.DEFAULT_OUTPUT,
                            document("3.0.1", "/second", "getSecond"))));

        JsonNode result = parsedDocument(classLoader);

        assertTrue(result.path("paths").has("/first"));
        assertTrue(result.path("paths").has("/second"));
    }

    @Test
    void reportsTheConflictingClasspathResourcesAtRegistrationTime(@TempDir Path tempDir) throws Exception {
        Path first = writeResource(tempDir.resolve("first"), document("3.0.1", "/first", "getFirst"));
        Path second = writeResource(tempDir.resolve("second"), document("3.1.0", "/second", "getSecond"));

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{first.toUri().toURL(), second.toUri().toURL()}, null)) {
            var error = assertThrows(IllegalArgumentException.class,
                                     () -> OpenApiDocumentEndpoint.readGeneratedDocument(classLoader));

            assertTrue(error.getMessage().contains("/openapi"));
            assertTrue(error.getMessage().contains("first"));
            assertTrue(error.getMessage().contains("second"));
        }
    }

    private static JsonNode parsedDocument(ClassLoader classLoader) {
        String json = OpenApiDocumentEndpoint.readGeneratedDocument(classLoader).orElseThrow();
        return JsonUtils.fromJson(json, JsonNode.class);
    }

    private static Path writeResource(Path root, String content) throws IOException {
        Path resource = root.resolve(OpenApiProcessor.DEFAULT_OUTPUT);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, content);
        return root;
    }

    private static String document(String openApiVersion, String path, String operationId) {
        return """
                {
                  "openapi": "%s",
                  "info": {"title": "Test API", "version": "1.0.0"},
                  "paths": {
                    "%s": {
                      "get": {"operationId": "%s", "responses": {"200": {"description": "OK"}}}
                    }
                  }
                }
                """.formatted(openApiVersion, path, operationId);
    }

    private static URL resourceUrl(String description, String content) {
        try {
            return URL.of(URI.create(description), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override
                        public void connect() {
                        }

                        @Override
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static class ResourceClassLoader extends ClassLoader {
        private final List<URL> resources;

        private ResourceClassLoader(List<URL> resources) {
            super(null);
            this.resources = resources;
        }

        @Override
        protected Enumeration<URL> findResources(String name) {
            return OpenApiProcessor.DEFAULT_OUTPUT.equals(name)
                    ? Collections.enumeration(resources) : Collections.emptyEnumeration();
        }
    }
}
