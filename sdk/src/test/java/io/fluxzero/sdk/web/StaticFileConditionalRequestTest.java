/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.web;

import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StaticFileConditionalRequestTest {
    @TempDir Path directory;
    TestFixture fixture;
    String etag;
    String modified;
    final FileTime timestamp = FileTime.fromMillis(1_700_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        write("<html>old</html>");
        fixture = TestFixture.create(new StaticFileHandler("/files", directory, null, null, Set.of(), Set.of(), 0));
        AtomicReference<WebResponse> initial = new AtomicReference<>();
        fixture.whenGet("/files/index.html").expectWebResult(response -> {
            initial.set(response);
            return response.getStatus() == 200;
        }).expectNoErrors();
        etag = initial.get().getHeader("ETag");
        modified = initial.get().getHeader("Last-Modified");
    }

    @AfterEach
    void tearDown() { if (fixture != null) fixture.getFluxzero().close(); }

    @Test
    void changedEntityTagOverridesMatchingModificationTime() throws Exception {
        String updated = "<html>new application with another helper</html>";
        write(updated);
        fixture.whenWebRequest(WebRequest.get("/files/index.html").header("If-None-Match", etag)
                .header("If-Modified-Since", modified).build())
                .expectWebResult(response -> {
                    assertEquals(200, response.getStatus());
                    assertNotEquals(etag, response.getHeader("ETag"));
                    assertEquals(updated, response.getPayloadAs(String.class));
                    return true;
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%s", "W/%s", "\"other,tag\", W/%s", "*"})
    void recognizesMatchingEntityTags(String value) {
        fixture.whenWebRequest(WebRequest.get("/files/index.html")
                .header("If-None-Match", value.formatted(etag)).build())
                .expectWebResult(response -> response.getStatus() == 304
                        && response.getPayload() == null && etag.equals(response.getHeader("ETag")))
                .expectNoErrors();
    }

    @Test
    void considersRepeatedEntityTagHeaders() {
        fixture.whenWebRequest(WebRequest.get("/files/index.html").header("If-None-Match", "\"other\"")
                .header("If-None-Match", "W/" + etag).build())
                .expectWebResult(response -> response.getStatus() == 304).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"other\"", "\"other,tag\"", "invalid"})
    void doesNotFallBackToDateWhenEntityTagHeaderIsPresent(String value) {
        fixture.whenWebRequest(WebRequest.get("/files/index.html").header("If-None-Match", value)
                .header("If-Modified-Since", modified).build())
                .expectWebResult(response -> response.getStatus() == 200).expectNoErrors();
    }

    @Test
    void retainsDateOnlyConditionalRequests() {
        fixture.whenWebRequest(WebRequest.get("/files/index.html").header("If-Modified-Since", modified).build())
                .expectWebResult(response -> response.getStatus() == 304).expectNoErrors();
        fixture.whenWebRequest(WebRequest.get("/files/index.html")
                .header("If-Modified-Since", "Tue, 14 Nov 2023 21:00:00 GMT").build())
                .expectWebResult(response -> response.getStatus() == 200).expectNoErrors();
    }

    private void write(String content) throws Exception {
        Path file = directory.resolve("index.html");
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, timestamp);
    }
}
