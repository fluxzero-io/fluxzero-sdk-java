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

package io.fluxzero.sdk.common.serialization;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SerializationDescriptionTest {
    private final Serializer serializer = new JacksonSerializer();

    record Case(Object payload, String format) {}

    static Stream<Case> headers() {
        return Stream.of(new Case(new Revised("value"), null), new Case(null, null),
                         new Case("text", Data.JSON_FORMAT), new Case("text", "text/plain"),
                         new Case(new byte[]{1, 2}, "application/octet-stream"), new Case(null, "text/plain"));
    }

    @ParameterizedTest
    @MethodSource("headers")
    void descriptionAgreesWithActualSerialization(Case input) {
        Data<byte[]> header = serializer.describeSerialization(input.payload(), input.format()).orElseThrow();
        Data<byte[]> actual = serializer.serialize(input.payload(), input.format());
        assertEquals(actual.getType(), header.getType());
        assertEquals(actual.getRevision(), header.getRevision());
        assertEquals(actual.getFormat(), header.getFormat());
        assertEquals(0, header.getValue().length);
    }

    @Test
    void descriptionDoesNotReadStreamsOrPayloadGetters() {
        InputStream stream = new InputStream() {
            @Override public int read() { throw new AssertionError("Stream must remain untouched"); }
        };
        Data<byte[]> header = serializer.describeSerialization(stream, "application/octet-stream").orElseThrow();
        assertEquals(byte[].class.getName(), header.getType());
        assertEquals(0, header.getRevision());
        assertEquals(ThrowingGetter.class.getName(),
                     serializer.describeSerialization(new ThrowingGetter("value"), null).orElseThrow().getType());
    }

    @Test
    void unknownSerializersAndContainerContentsRemainUndescribed() {
        assertTrue(new JacksonSerializer() {}.describeSerialization(new Revised("value"), null).isEmpty());
        assertTrue(serializer.describeSerialization(new Revised("value"), "unsupported").isEmpty());
        assertTrue(serializer.describeSerialization(new AbstractCollection<>() {
            @Override public Iterator<Object> iterator() { throw new AssertionError("Must not iterate"); }
            @Override public int size() { throw new AssertionError("Must not inspect contents"); }
        }, null).isEmpty());
        assertTrue(serializer.describeSerialization(java.util.Map.of("key", "value"), null).isEmpty());
    }

    @Revision(3)
    record Revised(String value) {}
    record ThrowingGetter(String value) {
        @Override public String value() { throw new AssertionError("Must not evaluate accessor"); }
    }
}
