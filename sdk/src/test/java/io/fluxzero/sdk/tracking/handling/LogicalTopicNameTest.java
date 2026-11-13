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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogicalTopicNameTest {
    private static final String PREFIX = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    private static final String FIRST = PREFIX + "a";
    private static final String SECOND = PREFIX + "ab";
    private static final String UNICODE = "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀界";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customAndDocumentSubscriptionsRetainCompleteLogicalNames(boolean async) {
        assertEquals(62, PREFIX.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(63, FIRST.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(64, SECOND.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(67, UNICODE.getBytes(StandardCharsets.UTF_8).length);
        var fixture = async ? TestFixture.createAsync(new Endpoints()) : TestFixture.create(new Endpoints());
        try (var app = fixture.getFluxzero()) {
            fixture.whenCustom(PREFIX, "input").expectResult("prefix:input")
                    .andThen().whenCustom(FIRST, "input").expectResult("first:input")
                    .andThen().whenCustom(SECOND, "input").expectResult("second:input")
                    .andThen().whenCustom(UNICODE, "input").expectResult("unicode:input")
                    .andThen().whenExecuting(fc -> Fluxzero.index(new Indexed("one"), "id", FIRST).join())
                    .expectOnlyEvents("first:one")
                    .andThen().whenExecuting(fc -> Fluxzero.index(new Indexed("two"), "id", SECOND).join())
                    .expectOnlyEvents("second:two")
                    .andThen().whenExecuting(fc -> Fluxzero.index(new Indexed("three"), "id", UNICODE).join())
                    .expectOnlyEvents("unicode:three");
        }
    }

    record Indexed(String value) {}

    static class Endpoints {
        @HandleCustom(PREFIX) String prefix(String value) { return "prefix:" + value; }
        @HandleCustom(FIRST) String first(String value) { return "first:" + value; }
        @HandleCustom(SECOND) String second(String value) { return "second:" + value; }
        @HandleCustom(UNICODE) String unicode(String value) { return "unicode:" + value; }
        @HandleDocument(FIRST) void first(Indexed value) { Fluxzero.publishEvent("first:" + value.value()); }
        @HandleDocument(SECOND) void second(Indexed value) { Fluxzero.publishEvent("second:" + value.value()); }
        @HandleDocument(UNICODE) void unicode(Indexed value) { Fluxzero.publishEvent("unicode:" + value.value()); }
    }
}
