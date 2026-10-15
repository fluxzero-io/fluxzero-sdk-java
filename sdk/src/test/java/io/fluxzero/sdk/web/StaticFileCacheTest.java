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

import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Isolated("StaticFileHandler.close closes shared JAR filesystems")
class StaticFileCacheTest {
    @TempDir
    Path directory;
    private static final FileTime FIXED_TIME = FileTime.fromMillis(1_600_000_000_000L);

    @ParameterizedTest
    @CsvSource({"false,GET,identity", "true,GET,identity", "false,HEAD,identity", "true,HEAD,identity",
                "false,GET,gzip", "true,GET,gzip", "false,HEAD,gzip", "true,HEAD,gzip"})
    void entityTagTakesPrecedenceAndSupportsWeakListsAndWildcard(boolean async, String method, String encoding)
            throws Exception {
        write("index.html", "current");
        Files.write(directory.resolve("index.html.gz"), CompressionAlgorithm.GZIP.compress("current".getBytes(UTF_8)));
        Files.setLastModifiedTime(directory.resolve("index.html.gz"), FIXED_TIME);
        var handler = handler(directory);
        var initial = handler.serveStaticFile("index.html", WebRequest.get("/")
                .header("Accept-Encoding", encoding).build());
        String tag = initial.getHeader("ETag");
        String modified = initial.getHeader("Last-Modified");
        var fixture = async ? TestFixture.createAsync(handler) : TestFixture.create(handler);
        try {
            for (String value : List.of("\"different\"", "", "not-an-etag", "\"other,*,tag\"")) {
                fixture.whenWebRequest(WebRequest.builder().method(method).url("/files/index.html")
                                .header("Accept-Encoding", encoding)
                                .header("If-None-Match", value).header("If-Modified-Since", modified).build())
                        .expectWebResult(r -> r.getStatus() == 200 && tag.equals(r.getHeader("ETag"))
                                && (HttpRequestMethod.HEAD.equals(method) ? r.getPayload() == null
                                : "current".equals(r.getPayloadAs(String.class))))
                        .expectNoErrors();
            }
            for (String value : List.of(tag, "W/" + tag, "\"other,tag\", W/" + tag, "*")) {
                fixture.whenWebRequest(WebRequest.builder().method(method).url("/files/index.html")
                                .header("Accept-Encoding", encoding)
                                .header("If-None-Match", value).build())
                        .expectWebResult(r -> r.getStatus() == 304 && tag.equals(r.getHeader("ETag"))
                                && "Accept-Encoding".equals(r.getHeader("Vary")))
                        .expectNoErrors();
            }
            fixture.whenWebRequest(WebRequest.builder().method(method).url("/files/index.html")
                            .header("Accept-Encoding", encoding)
                            .header("If-None-Match", "\"other\"").header("If-None-Match", tag).build())
                    .expectWebResult(r -> r.getStatus() == 304).expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(method).url("/files/index.html")
                            .header("Accept-Encoding", encoding)
                            .header("If-Modified-Since", modified).build())
                    .expectWebResult(r -> r.getStatus() == 304).expectNoErrors();
            fixture.whenWebRequest(WebRequest.builder().method(method).url("/files/index.html")
                            .header("Accept-Encoding", encoding)
                            .header("If-Modified-Since", "invalid").build())
                    .expectWebResult(r -> r.getStatus() == 200).expectNoErrors();
        } finally {
            fixture.getFluxzero().close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"filesystem", "jar"})
    void changedContentWithIdenticalSizeAndTimestampInvalidatesTag(String storage) throws Exception {
        var first = resource(storage, "first", "old bytes");
        var initial = first.serveStaticFile("index.html", WebRequest.get("/").build());
        String tag = initial.getHeader("ETag");
        String modified = initial.getHeader("Last-Modified");
        StaticFileHandler changed;
        if (storage.equals("filesystem")) {
            write("index.html", "new bytes");
            changed = first;
        } else {
            changed = resource(storage, "second", "new bytes");
        }
        try {
            var response = changed.serveStaticFile("index.html", WebRequest.get("/")
                    .header("If-None-Match", tag).header("If-Modified-Since", modified).build());
            assertEquals(200, response.getStatus());
            assertEquals(modified, response.getHeader("Last-Modified"));
            assertNotEquals(tag, response.getHeader("ETag"));
            assertEquals("9", response.getHeader("Content-Length"));
            assertArrayEquals("new bytes".getBytes(UTF_8), read(response));
            assertEquals(304, changed.serveStaticFile("index.html", WebRequest.get("/")
                    .header("If-None-Match", response.getHeader("ETag")).build()).getStatus());
        } finally {
            first.close();
            changed.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "br"})
    void tagsIdentifySelectedRepresentationAndRangesRemainUncompressed(String encoding) throws Exception {
        write("index.html", "identity bytes");
        byte[] compressed = encoding.equals("gzip")
                ? CompressionAlgorithm.GZIP.compress("identity bytes".getBytes(UTF_8))
                : new byte[]{0x0b, 0x02, (byte) 0x80, 'h', 'e', 'l', 'l', 'o', 0x03};
        Path encodedPath = directory.resolve("index.html" + (encoding.equals("gzip") ? ".gz" : ".br"));
        Files.write(encodedPath, compressed);
        Files.setLastModifiedTime(encodedPath, FIXED_TIME);
        try (var handler = handler(directory)) {
            var identity = handler.serveStaticFile("index.html", WebRequest.get("/")
                    .header("Accept-Encoding", "identity").build());
            var response = handler.serveStaticFile("index.html", WebRequest.get("/")
                    .header("Accept-Encoding", encoding).header("If-None-Match", identity.getHeader("ETag")).build());
            assertEquals(200, response.getStatus());
            assertEquals(encoding, response.getHeader("Content-Encoding"));
            assertNotEquals(identity.getHeader("ETag"), response.getHeader("ETag"));
            assertArrayEquals(compressed, read(response));
            var cached = handler.serveStaticFile("index.html", WebRequest.get("/")
                    .header("Accept-Encoding", encoding).header("If-None-Match", response.getHeader("ETag")).build());
            assertEquals(304, cached.getStatus());
            assertEquals(response.getHeader("ETag"), cached.getHeader("ETag"));
            var range = handler.serveStaticFile("index.html", WebRequest.get("/").header("Accept-Encoding", encoding)
                    .header("Range", "bytes=0-3").build());
            assertEquals(206, range.getStatus());
            assertNull(range.getHeader("Content-Encoding"));
            assertEquals(identity.getHeader("ETag"), range.getHeader("ETag"));
            assertEquals("bytes 0-3/14", range.getHeader("Content-Range"));
            assertArrayEquals("iden".getBytes(UTF_8), read(range));
            compressed[compressed.length - 1] ^= 1;
            Files.write(encodedPath, compressed);
            Files.setLastModifiedTime(encodedPath, FIXED_TIME);
            var updated = handler.serveStaticFile("index.html", WebRequest.get("/")
                    .header("Accept-Encoding", encoding).header("If-None-Match", response.getHeader("ETag"))
                    .header("If-Modified-Since", response.getHeader("Last-Modified")).build());
            assertEquals(200, updated.getStatus());
            assertNotEquals(response.getHeader("ETag"), updated.getHeader("ETag"));
            assertArrayEquals(compressed, read(updated));
        }
    }

    @Test
    void reusesJarDigestAcrossRequestsHandlersAndAliases() throws Exception {
        try (var first = resource("jar", "cached", "immutable")) {
            try (var hashing = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
                String tag = first.serveStaticFile("index.html", WebRequest.get("/").build()).getHeader("ETag");
                var second = new StaticFileHandler("/other", null, first.getResourceBaseUri(), null,
                                                   Set.of(), Set.of(), 0);
                for (var handler : List.of(first, second)) {
                    assertEquals(304, handler.serveStaticFile("./index.html", WebRequest.get("/")
                            .header("If-None-Match", tag).build()).getStatus());
                    assertEquals(tag, handler.serveStaticFile("index.html", WebRequest.builder().url("/")
                            .method("HEAD").build()).getHeader("ETag"));
                    assertEquals(tag, handler.serveStaticFile("index.html", WebRequest.get("/")
                            .header("Range", "bytes=0-2").build()).getHeader("ETag"));
                }
                hashing.verify(() -> MessageDigest.getInstance("SHA-256"), times(1));
            }
        }
    }

    @Test
    void reopeningReplacedJarAtSameUriDoesNotReuseOldDigest() throws Exception {
        var first = resource("jar", "replaced", "old bytes");
        String tag = first.serveStaticFile("index.html", WebRequest.get("/").build()).getHeader("ETag");
        first.close();
        try (var replacement = resource("jar", "replaced", "new bytes")) {
            var response = replacement.serveStaticFile("index.html", WebRequest.get("/")
                    .header("If-None-Match", tag).build());
            assertEquals(200, response.getStatus());
            assertNotEquals(tag, response.getHeader("ETag"));
            assertArrayEquals("new bytes".getBytes(UTF_8), read(response));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"filesystem", "exploded", "zip-filesystem"})
    void mutableSourcesAreReadAgainEvenWhenJarFallbackExists(String storage) throws Exception {
        try (var fallback = resource("jar", "fallback", "fallback")) {
            if (storage.equals("zip-filesystem")) {
                Path archive = directory.resolve("mutable.zip");
                try (var fs = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
                    verifyMutableSource(fs.getPath("/"), fallback.getResourceBaseUri(), false);
                }
            } else {
                verifyMutableSource(directory, fallback.getResourceBaseUri(), storage.equals("exploded"));
            }
        }
    }

    private void verifyMutableSource(Path root, URI jarFallback, boolean exploded) throws Exception {
        Path file = root.resolve("index.html");
        Files.writeString(file, "old bytes");
        Files.setLastModifiedTime(file, FIXED_TIME);
        var handler = new StaticFileHandler("/files", exploded ? null : root,
                exploded ? root.toUri() : jarFallback, null, Set.of(), Set.of(), 0);
        try (var hashing = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
            String tag = handler.serveStaticFile("index.html", WebRequest.get("/").build()).getHeader("ETag");
            Files.writeString(file, "new bytes");
            Files.setLastModifiedTime(file, FIXED_TIME);
            var response = handler.serveStaticFile("index.html", WebRequest.get("/")
                    .header("If-None-Match", tag).build());
            assertEquals(200, response.getStatus());
            assertNotEquals(tag, response.getHeader("ETag"));
            assertArrayEquals("new bytes".getBytes(UTF_8), read(response));
            hashing.verify(() -> MessageDigest.getInstance("SHA-256"), times(2));
        }
    }

    @Test
    void failedJarDigestCanBeRetried() throws Exception {
        try (var handler = resource("jar", "retry", "immutable")) {
            try (var hashing = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
                hashing.when(() -> MessageDigest.getInstance("SHA-256"))
                        .thenThrow(new IllegalStateException("digest failure")).thenCallRealMethod();
                assertThrows(IllegalStateException.class,
                             () -> handler.serveStaticFile("index.html", WebRequest.get("/").build()));
                var response = handler.serveStaticFile("index.html", WebRequest.get("/").build());
                assertEquals(200, response.getStatus());
                assertArrayEquals("immutable".getBytes(UTF_8), read(response));
                assertEquals(304, handler.serveStaticFile("index.html", WebRequest.get("/")
                        .header("If-None-Match", response.getHeader("ETag")).build()).getStatus());
                hashing.verify(() -> MessageDigest.getInstance("SHA-256"), times(2));
            }
        }
    }

    @Test
    void concurrentJarRequestsAndCompressedRepresentationsRemainConsistent() throws Exception {
        Path jar = directory.resolve("representations.jar");
        Map<String, byte[]> content = Map.of("index.html", "identity".getBytes(UTF_8),
                "index.html.gz", CompressionAlgorithm.GZIP.compress("identity".getBytes(UTF_8)),
                "index.html.br", new byte[]{1, 2, 3});
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : content.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        try (var handler = new StaticFileHandler("/files", null, URI.create("jar:" + jar.toUri() + "!/"), null,
                                                 Set.of(), Set.of(), 0);
             var executor = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Callable<Map<String, String>>>();
            for (int i = 0; i < 12; i++) {
                calls.add(() -> {
                    Map<String, String> tags = new java.util.HashMap<>();
                    for (String encoding : List.of("identity", "gzip", "br")) {
                        var response = handler.serveStaticFile("index.html", WebRequest.get("/")
                                .header("Accept-Encoding", encoding).build());
                        assertArrayEquals(content.get("index.html" + (encoding.equals("identity") ? ""
                                : encoding.equals("gzip") ? ".gz" : ".br")), read(response));
                        tags.put(encoding, response.getHeader("ETag"));
                    }
                    return tags;
                });
            }
            Map<String, String> expected = null;
            for (var result : executor.invokeAll(calls)) {
                var tags = result.get();
                assertEquals(3, Set.copyOf(tags.values()).size());
                if (expected == null) {
                    expected = tags;
                } else {
                    assertEquals(expected, tags);
                }
            }
            try (var hashing = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
                for (String encoding : List.of("identity", "gzip", "br")) {
                    assertEquals(304, handler.serveStaticFile("index.html", WebRequest.get("/")
                            .header("Accept-Encoding", encoding).header("If-None-Match", expected.get(encoding))
                            .build()).getStatus());
                }
                hashing.verifyNoInteractions();
            }
        }
    }

    @Test
    void missingFileDoesNotMatchWildcard() {
        try (var handler = handler(directory)) {
            assertEquals(404, handler.serveStaticFile("missing.html", WebRequest.get("/")
                    .header("If-None-Match", "*").build()).getStatus());
        }
    }

    private void write(String name, String content) throws Exception {
        var path = directory.resolve(name);
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FIXED_TIME);
    }

    private StaticFileHandler resource(String storage, String name, String content) throws Exception {
        if (storage.equals("filesystem")) {
            write("index.html", content);
            return handler(directory);
        }
        Path jar = directory.resolve(name + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            var entry = new JarEntry("root/index.html");
            entry.setLastModifiedTime(FIXED_TIME);
            output.putNextEntry(entry);
            output.write(content.getBytes(UTF_8));
            output.closeEntry();
        }
        return new StaticFileHandler("/files", null, URI.create("jar:" + jar.toUri() + "!/root/"), null,
                                     Set.of(), Set.of(), 0);
    }

    private StaticFileHandler handler(Path root) {
        return new StaticFileHandler("/files", root, null, null, Set.of(), Set.of(), 0);
    }

    private byte[] read(WebResponse response) throws Exception {
        try (var stream = (InputStream) response.getEncodedPayload()) {
            return stream.readAllBytes();
        }
    }
}
