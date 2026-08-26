/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small allocation and throughput benchmark for the forward-proxy scheduler without network latency.
 *
 * <p>Example:</p>
 * <pre>{@code
 * ./mvnw -pl proxy -am -DskipTests install
 * ./mvnw -pl proxy -DskipTests \
 *   org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=io.fluxzero.proxy.ForwardProxySchedulerBenchmark \
 *   -Drequests=500000 -Drepeats=5
 * }</pre>
 */
public final class ForwardProxySchedulerBenchmark {
    private static final int MAX_CONCURRENT = 8;
    private static final int MAX_OUTSTANDING = 1024;
    private static final int SEGMENTS = 128;

    public static void main(String[] args) throws Exception {
        int requests = Integer.getInteger("requests", 500_000);
        int warmup = Integer.getInteger("warmup", 50_000);
        int repeats = Integer.getInteger("repeats", 5);
        run(warmup, false);
        run(warmup, true);
        long[] baseline = new long[repeats];
        long[] scheduled = new long[repeats];
        for (int i = 0; i < repeats; i++) {
            baseline[i] = run(requests, false);
            scheduled[i] = run(requests, true);
        }
        Arrays.sort(baseline);
        Arrays.sort(scheduled);
        long baselineNanos = baseline[repeats / 2];
        long scheduledNanos = scheduled[repeats / 2];
        System.out.printf("Forward scheduler benchmark: requests=%d, concurrent=%d, outstanding=%d, segments=%d%n",
                          requests, MAX_CONCURRENT, MAX_OUTSTANDING, SEGMENTS);
        System.out.printf("bounded concurrency: %,.0f requests/s%n", throughput(requests, baselineNanos));
        System.out.printf("segment scheduler: %,.0f requests/s, overhead=%+.1f%%%n",
                          throughput(requests, scheduledNanos),
                          100.0 * (scheduledNanos - baselineNanos) / baselineNanos);
    }

    private static long run(int requests, boolean scheduled) throws Exception {
        ExecutorService completions = Executors.newFixedThreadPool(MAX_CONCURRENT);
        CompletableFuture<Void> allDone = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(requests);
        SegmentSerialScheduler<BenchmarkTask> scheduler = scheduled
                ? new SegmentSerialScheduler<>(MAX_CONCURRENT, MAX_OUTSTANDING) : null;
        Semaphore concurrentCapacity = scheduled ? null : new Semaphore(MAX_CONCURRENT);
        long start = System.nanoTime();
        try {
            for (int i = 0; i < requests; i++) {
                BenchmarkTask task = new BenchmarkTask(i % SEGMENTS, completions, remaining, allDone);
                if (scheduler == null) {
                    concurrentCapacity.acquire();
                    task.completion().whenComplete((ignored, error) -> concurrentCapacity.release());
                    task.start();
                } else if (!scheduler.schedule(task)) {
                    throw new IllegalStateException("Scheduler stopped during benchmark");
                }
            }
            allDone.get(30, TimeUnit.SECONDS);
            return System.nanoTime() - start;
        } finally {
            completions.shutdownNow();
        }
    }

    private static double throughput(int requests, long nanos) {
        return requests / (nanos / (double) Duration.ofSeconds(1).toNanos());
    }

    private record BenchmarkTask(int segment, ExecutorService executor, AtomicInteger remaining,
                                 CompletableFuture<Void> allDone, CompletableFuture<Void> completion)
            implements SegmentSerialScheduler.Task {
        private BenchmarkTask(int segment, ExecutorService executor, AtomicInteger remaining,
                              CompletableFuture<Void> allDone) {
            this(segment, executor, remaining, allDone, new CompletableFuture<>());
        }

        @Override
        public void admitted() {
        }

        @Override
        public void start() {
            executor.execute(() -> {
                completion.complete(null);
                if (remaining.decrementAndGet() == 0) {
                    allDone.complete(null);
                }
            });
        }

        @Override
        public void fail(Throwable error) {
            allDone.completeExceptionally(error);
        }
    }
}
