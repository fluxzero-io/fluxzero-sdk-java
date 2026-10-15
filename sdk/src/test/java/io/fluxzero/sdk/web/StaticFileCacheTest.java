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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

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
