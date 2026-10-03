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

package io.fluxzero.proxy;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Test-only instrumentation; acquisition counts are not counts of fresh native malloc calls. */
final class HeaderBufferPool extends ArrayByteBufferPool {
    private final Map<String, LongAdder> acquisitions = new ConcurrentHashMap<>();
    private final Set<ByteBuffer> http2Buffers = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private volatile boolean trackHttp2BufferReuse;
    private final LongAdder outstanding = new LongAdder();

    @Override
    public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
        String source = StackWalker.getInstance().walk(frames -> frames.map(f -> {
            if (f.getClassName().equals("org.eclipse.jetty.server.internal.HttpConnection$SendCallback")
                && f.getMethodName().equals("process")) {
                return "http1-send/";
            }
            if (f.getClassName().equals("org.eclipse.jetty.http2.generator.FrameGenerator")
                && f.getMethodName().equals("encode")) {
                return "http2-headers/";
            }
            return null;
        }).filter(java.util.Objects::nonNull).findFirst().orElse("other/"));
        // The send generator also requests 12-byte chunk buffers. Keep those separate.
        String key = source + (direct ? "direct/" : "heap/") + size;
        acquisitions.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        outstanding.increment();
        var buffer = super.acquire(size, direct);
        if (trackHttp2BufferReuse && source.equals("http2-headers/")) {
            http2Buffers.add(buffer.getByteBuffer());
        }
        return new RetainableByteBuffer.Wrapper(buffer) {
            @Override
            public boolean release() {
                boolean released = super.release();
                if (released) {
                    outstanding.decrement();
                }
                return released;
            }
        };
    }

    Map<String, Long> snapshot() {
        Map<String, Long> result = new TreeMap<>();
        acquisitions.forEach((key, count) -> result.put(key, count.sum()));
        return result;
    }

    long outstanding() {
        return outstanding.sum();
    }

    // Opt in only for short reuse tests: retaining identities would distort long allocation benchmarks.
    void trackHttp2BufferReuse() {
        trackHttp2BufferReuse = true;
    }

    int distinctHttp2Buffers() {
        return http2Buffers.size();
    }

    long http2Headers(int size, boolean direct) {
        return acquisitions.getOrDefault("http2-headers/" + (direct ? "direct/" : "heap/") + size,
                                         new LongAdder()).sum();
    }

    long http2HeadersAbove(int size) {
        return snapshot().entrySet().stream().filter(e -> e.getKey().startsWith("http2-headers/"))
                .filter(e -> Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('/') + 1)) > size)
                .mapToLong(Map.Entry::getValue).sum();
    }

    long sends(int size) {
        return sends(size, true);
    }

    long sends(int size, boolean direct) {
        return acquisitions.getOrDefault("http1-send/" + (direct ? "direct/" : "heap/") + size,
                                         new LongAdder()).sum();
    }
}
