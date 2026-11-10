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
 *
 */

package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Iterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamInputStreamTest {
    @Test
    void preservesPartialRecordTailWhenFetchingNextPageFails() throws Exception {
        var failure = new IllegalStateException("page failed");
        var fetches = new AtomicInteger();
        var closes = new AtomicInteger();
        String record = "x".repeat(1075) + "\n";
        var source = Stream.concat(Stream.of(record), Stream.<String>generate(() -> {
            fetches.incrementAndGet();
            throw failure;
        })).onClose(closes::incrementAndGet);
        try (var input = input(source)) {
            assertEquals(record.substring(0, 291), new String(input.readNBytes(291), UTF_8));
            byte[] target = new byte[2000];
            assertEquals(785, input.read(target, 7, 1500));
            assertEquals(record.substring(291), new String(target, 7, 785, UTF_8));
            assertSame(failure, assertThrows(IllegalStateException.class, input::read));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> input.read(target)));
            assertEquals(0, input.read(target, 0, 0));
            assertEquals(1, fetches.get());
            assertEquals(0, closes.get());
        }
        assertEquals(1, closes.get());
    }

    @Test
    void preservesBytesWhenIteratorNextFails() throws Exception {
        var failure = new IllegalArgumentException("next failed");
        Iterator<String> iterator = new Iterator<>() {
            int count;
            public boolean hasNext() { return true; }
            public String next() {
                if (count++ == 0) { return "ok"; }
                throw failure;
            }
        };
        try (var input = input(StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false))) {
            assertEquals(2, input.read(new byte[10]));
            assertSame(failure, assertThrows(IllegalArgumentException.class, input::read));
        }
    }

    @Test
    void discardsOnlyIncompleteSerializationAndRetainsOriginalIOException() throws Exception {
        var failure = new IOException("writer failed");
        var writes = new AtomicInteger();
        try (var input = new StreamInputStream<>(Stream.of("ok", "bad", "later"), (value, out) -> {
            writes.incrementAndGet();
            out.write(value.getBytes(UTF_8));
            if (value.equals("bad")) { throw failure; }
        })) {
            byte[] target = new byte[20];
            assertEquals(2, input.read(target));
            assertEquals("ok", new String(target, 0, 2, UTF_8));
            assertSame(failure, assertThrows(IOException.class, input::read));
            assertSame(failure, assertThrows(IOException.class, input::read));
            assertEquals(2, writes.get());
        }
    }

    @Test
    void preservesWriterExceptionWrapping() throws Exception {
        var failure = new IllegalStateException("writer failed");
        try (var input = new StreamInputStream<>(Stream.of("ok", "bad"), (value, out) -> {
            if (value.equals("bad")) { throw failure; }
            out.write(value.getBytes(UTF_8));
        })) {
            assertEquals(2, input.read(new byte[10]));
            assertSame(failure, assertThrows(IOException.class, input::read).getCause());
        }
    }

    @Test
    void failsImmediatelyWhenNoBytesWereCopied() {
        var failure = new IllegalStateException("fetch failed");
        try (var input = input(Stream.generate(() -> { throw failure; }))) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> input.read(new byte[10])));
        }
    }

    @Test
    void returnsFinalBytesBeforeReportingAutomaticCloseFailure() throws Exception {
        var failure = new IllegalStateException("close failed");
        var closes = new AtomicInteger();
        var input = input(Stream.of("ok").onClose(() -> {
            closes.incrementAndGet();
            throw failure;
        }));
        assertEquals(2, input.read(new byte[10]));
        assertSame(failure, assertThrows(IllegalStateException.class, input::read));
        input.close();
        assertEquals(-1, input.read());
        assertEquals(1, closes.get());
    }

    @Test
    void preservesEmptyElementsOffsetsAndAutomaticClose() throws Exception {
        var closes = new AtomicInteger();
        try (var input = input(Stream.of("", "ab", "", "cd", "").onClose(closes::incrementAndGet))) {
            byte[] target = new byte[8];
            assertEquals(4, input.read(target, 2, 6));
            assertArrayEquals(new byte[] {0, 0, 'a', 'b', 'c', 'd', 0, 0}, target);
            assertEquals(-1, input.read());
            assertEquals(-1, input.read(target));
            assertEquals(0, input.available());
        }
        assertEquals(1, closes.get());
    }

    @Test
    void exactSizedReadDoesNotFetchAhead() throws Exception {
        var failure = new IllegalStateException("fetch failed");
        var fetches = new AtomicInteger();
        try (var input = input(Stream.concat(Stream.of("ab"), Stream.generate(() -> {
            fetches.incrementAndGet();
            throw failure;
        })))) {
            assertEquals(2, input.read(new byte[2]));
            assertEquals(0, fetches.get());
            assertSame(failure, assertThrows(IllegalStateException.class, input::read));
        }
    }

    @Test
    void preservesSingleByteReadsAndExplicitClose() throws Exception {
        var closes = new AtomicInteger();
        var input = new StreamInputStream<>(Stream.of(255, 128), (value, out) -> out.write(value));
        assertEquals(255, input.read());
        assertEquals(128, input.read());
        assertEquals(-1, input.read());
        var early = input(Stream.of("abc").onClose(closes::incrementAndGet));
        assertEquals('a', early.read());
        assertEquals(2, early.available());
        early.close();
        early.close();
        assertEquals(1, closes.get());
        assertEquals(-1, early.read());
        assertEquals(0, early.available());
    }

    @Test
    void validatesArgumentsBeforeAccessingSource() throws Exception {
        var reads = new AtomicInteger();
        try (var input = input(Stream.generate(() -> { reads.incrementAndGet(); return "a"; }))) {
            assertThrows(NullPointerException.class, () -> input.read(null, 0, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[2], -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[2], 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[2], 1, 2));
            assertEquals(0, input.read(new byte[0]));
            assertEquals(0, reads.get());
        }
    }

    private StreamInputStream<String> input(Stream<String> source) {
        return new StreamInputStream<>(source, (value, out) -> out.write(value.getBytes(UTF_8)));
    }
}
