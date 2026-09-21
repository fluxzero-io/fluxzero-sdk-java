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

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Test-only instrumentation; acquisition counts are not counts of fresh native malloc calls. */
final class HeaderBufferPool extends ArrayByteBufferPool {
    private final Map<String, LongAdder> acquisitions = new ConcurrentHashMap<>();
    private final LongAdder outstanding = new LongAdder();

    @Override
    public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
        boolean http1Send = StackWalker.getInstance().walk(frames -> frames.anyMatch(
                f -> f.getClassName().equals("org.eclipse.jetty.server.internal.HttpConnection$SendCallback")
                     && f.getMethodName().equals("process")));
        // The send generator also requests 12-byte chunk buffers. Keep those separate.
        String key = (http1Send ? "http1-send/" : "other/") + (direct ? "direct/" : "heap/") + size;
        acquisitions.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        outstanding.increment();
        return new RetainableByteBuffer.Wrapper(super.acquire(size, direct)) {
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

    long sends(int size) {
        return sends(size, true);
    }

    long sends(int size, boolean direct) {
        return acquisitions.getOrDefault("http1-send/" + (direct ? "direct/" : "heap/") + size,
                                         new LongAdder()).sum();
    }
}
