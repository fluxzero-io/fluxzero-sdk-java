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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.persisting.search.client.InMemorySearchStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static io.fluxzero.common.Guarantee.STORED;

/**
 * Manual scaling diagnostic for monitored document indexing in the in-memory search store.
 */
public class InMemorySearchStoreMonitoringBenchmark {

    private static final int[] SIZES = {1_000, 5_000, 10_000};
    private static final int WARMUPS = Integer.getInteger("warmups", 2);
    private static final int RUNS = Integer.getInteger("runs", 5);

    public static void main(String[] args) {
        new InMemorySearchStoreMonitoringBenchmark().reportsScaling();
    }

    @Test
    void reportsScaling() {
        List<List<SerializedDocument>> documents = Arrays.stream(SIZES)
                .mapToObj(InMemorySearchStoreMonitoringBenchmark::documents)
                .toList();
        for (int i = 0; i < WARMUPS; i++) {
            index(documents.getLast());
        }
        long[][] samples = new long[SIZES.length][RUNS];
        for (int run = 0; run < RUNS; run++) {
            for (int step = 0; step < SIZES.length; step++) {
                int sizeIndex = run % 2 == 0 ? step : SIZES.length - step - 1;
                samples[sizeIndex][run] = index(documents.get(sizeIndex));
            }
        }
        for (int sizeIndex = 0; sizeIndex < SIZES.length; sizeIndex++) {
            Arrays.sort(samples[sizeIndex]);
            double medianMillis = samples[sizeIndex][RUNS / 2] / 1_000_000d;
            int size = SIZES[sizeIndex];
            System.out.printf("monitored documents: %,d, median %.3f ms, %.3f us/document%n",
                              size, medianMillis, medianMillis * 1_000d / size);
        }
    }

    private static long index(List<SerializedDocument> documents) {
        InMemorySearchStore store = new InMemorySearchStore(Duration.ofDays(1));
        store.registerMonitor((collection, messages) -> { });
        long start = System.nanoTime();
        store.index(documents, STORED, false).join();
        long elapsed = System.nanoTime() - start;
        long stored = store.openStream("benchmark", null, Integer.MAX_VALUE).count();
        if (stored != documents.size()) {
            throw new IllegalStateException("Expected " + documents.size() + " messages but found " + stored);
        }
        return elapsed;
    }

    private static List<SerializedDocument> documents(int size) {
        Data<byte[]> data = new Data<>(new byte[0], "BenchmarkDocument", 0);
        return IntStream.range(0, size)
                .mapToObj(index -> new SerializedDocument(
                        "document-" + index, null, null, "benchmark", data,
                        null, Set.of(), Set.of()))
                .toList();
    }
}
