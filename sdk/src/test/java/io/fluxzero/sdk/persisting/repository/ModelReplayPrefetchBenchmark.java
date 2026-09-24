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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Diagnostic ABBA comparison for overlapping one bounded transport page with Model event application. */
public class ModelReplayPrefetchBenchmark {

    private static final int PAGE_COUNT = Integer.getInteger("pages", 64);
    private static final int ITERATIONS = Integer.getInteger("iterations", 5);
    private static final long NETWORK_DELAY_NANOS = TimeUnit.MICROSECONDS.toNanos(
            Long.getLong("networkMicros", 2_000L));
    private static final long APPLY_DELAY_NANOS = TimeUnit.MICROSECONDS.toNanos(
            Long.getLong("applyMicros", 2_000L));

    public static void main(String[] args) {
        if (PAGE_COUNT <= 0 || ITERATIONS <= 0) {
            throw new IllegalArgumentException("pages and iterations must be positive");
        }
        run(true, 1);
        run(false, 1);

        System.out.printf(
                "Model replay prefetch: pages=%d, iterations=%d, network=%dµs/page, apply=%dµs/page%n",
                PAGE_COUNT, ITERATIONS,
                TimeUnit.NANOSECONDS.toMicros(NETWORK_DELAY_NANOS),
                TimeUnit.NANOSECONDS.toMicros(APPLY_DELAY_NANOS));
        measure("A sequential", false);
        measure("B prefetched", true);
        measure("B prefetched", true);
        measure("A sequential", false);
    }

    private static void measure(String label, boolean prefetch) {
        long nanos = run(prefetch, ITERATIONS);
        System.out.printf(
                "%s: %.2f ms/load%n", label,
                nanos / 1_000_000d / ITERATIONS);
    }

    private static long run(boolean prefetch, int iterations) {
        EventStoreClient client = client();
        ModelReplayCursor cursor = new ModelReplayCursor(
                client,
                new ModelReplayCursor.Settings(
                        4, 1, 1, 1_024L, prefetch));
        AtomicInteger applied = new AtomicInteger();
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; iteration++) {
            cursor.load(List.of("benchmark"), null, ignored -> {
                applied.incrementAndGet();
                LockSupport.parkNanos(APPLY_DELAY_NANOS);
            });
        }
        long result = System.nanoTime() - start;
        int expected = PAGE_COUNT * iterations;
        if (applied.get() != expected) {
            throw new IllegalStateException(
                    "Expected %d applied pages but got %d".formatted(expected, applied.get()));
        }
        return result;
    }

    private static EventStoreClient client() {
        return (EventStoreClient) Proxy.newProxyInstance(
                ModelReplayPrefetchBenchmark.class.getClassLoader(),
                new Class<?>[]{EventStoreClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getModelEvents")) {
                        LockSupport.parkNanos(NETWORK_DELAY_NANOS);
                        return page((GetModelEvents) arguments[0]);
                    }
                    if (method.getName().equals("toString")) {
                        return "DelayedModelEventStore";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == arguments[0];
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }

    private static GetModelEventsResult page(GetModelEvents request) {
        long sequenceNumber = request.getRequests().getFirst().getLastSequenceNumber() + 1L;
        long stateIndex = PAGE_COUNT - 1L;
        SerializedMessage event = new SerializedMessage(
                new Data<>(new byte[]{1}, "benchmark.Event", 0),
                Metadata.empty(), "event-" + sequenceNumber, 1L);
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex,
                List.of(new ModelEventPayload(sequenceNumber, event)),
                List.of(new ModelEventStream(
                        "benchmark",
                        new ModelHeadState(
                                "benchmark", "benchmark.Model",
                                stateIndex, stateIndex, true, false),
                        List.of(new ModelEventMembership(
                                sequenceNumber, sequenceNumber,
                                sequenceNumber == 0L ? -1L : sequenceNumber - 1L,
                                "commit-" + sequenceNumber, 0)))));
    }
}
