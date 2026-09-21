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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

class StaticFilePathDecodingTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 7, WebResponseGateway.MAX_RESPONSE_SIZE + 17})
    void servesStreamedFilesAsynchronously(int size) throws Exception {
        byte[] content = new byte[size];
        new java.util.Random(42).nextBytes(content);
        Files.write(directory.resolve("file.bin"), content);
        var handler = new StaticFileHandler("/files", directory, null, null, Set.of(), Set.of(), 0);
        var fixture = TestFixture.createAsync(handler);
        try {
            fixture.whenGet("/files/file.bin")
                    .expectWebResult(r -> r.getStatus() == 200
                            && java.util.Arrays.equals(content, r.getPayloadAs(byte[].class)))
                    .expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,gzip", "true,gzip", "false,identity", "true,identity"})
    void servesTextAndHeadWithRepresentationHeaders(boolean async, String encoding) throws Exception {
        String content = "café ".repeat(1000);
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(directory.resolve("file.txt"), bytes);
        Files.write(directory.resolve("file.txt.gz"),
                    io.fluxzero.common.serialization.compression.CompressionAlgorithm.GZIP.compress(bytes));
        var handler = new StaticFileHandler("/files", directory, null, null, Set.of(), Set.of(), 0);
        var fixture = async ? TestFixture.createAsync(handler) : TestFixture.create(handler);
        try {
            fixture.whenWebRequest(WebRequest.get("/files/file.txt").header("Accept-Encoding", encoding).build())
                    .expectWebResult(r -> r.getStatus() == 200 && content.equals(r.getPayloadAs(String.class)))
                    .expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(HttpRequestMethod.HEAD).url("/files/file.txt")
                                           .header("Accept-Encoding", encoding).build())
                    .expectWebResult(r -> r.getStatus() == 200 && r.getPayload() == null)
                    .expectNoErrors();
            fixture.whenGet("/files/missing.txt").expectWebResult(r -> r.getStatus() == 404).expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"filesystem", "resource", "jar"})
    void servesDecodedNamesWithoutReinterpretingUriSyntax(String storage) throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Map<String, String> files = Map.of("space name.txt", "space", "a+b.txt", "plus", "%2e%2e.txt", "percent",
                                         "a#b?.txt", "delimiters", "a:b.txt", "colon", "café.txt", "unicode");
        for (var entry : files.entrySet()) {
            Files.writeString(root.resolve(entry.getKey()), entry.getValue());
        }
        Files.writeString(directory.resolve("outside.txt"), "must not escape");
        URI resource = root.toUri();
        if (storage.equals("jar")) {
            Path jar = directory.resolve("assets.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
                for (var entry : files.entrySet()) {
                    output.putNextEntry(new JarEntry("root/" + entry.getKey()));
                    output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.closeEntry();
                }
                output.putNextEntry(new JarEntry("outside.txt"));
                output.write("must not escape".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
            resource = URI.create("jar:" + jar.toUri() + "!/root/");
        }
        var handler = new StaticFileHandler("/files", storage.equals("filesystem") ? root : null,
                storage.equals("filesystem") ? null : resource, null, Set.of(), Set.of(), 0);
        TestFixture fixture = TestFixture.create(handler);
        try {
            Map.of("space%20name.txt", "space", "a+b.txt", "plus", "%252e%252e.txt", "percent",
                   "a%23b%3F.txt", "delimiters", "a%3Ab.txt", "colon", "caf%C3%A9.txt", "unicode")
                    .forEach((path, content) -> fixture.whenGet("/files/" + path)
                            .expectWebResult(r -> r.getStatus() == 200 && content.equals(r.getPayloadAs(String.class)))
                            .expectNoErrors());
            for (String path : new String[]{"%2e%2e/outside.txt", "%2E%2E%2Foutside.txt",
                    "%2Foutside.txt", "%5Coutside.txt", "%252e%252e/outside.txt"}) {
                fixture.whenGet("/files/" + path).expectWebResult(r -> r.getStatus() == 404).expectNoErrors();
            }
        } finally {
            fixture.getFluxzero().close();
        }
    }
}
