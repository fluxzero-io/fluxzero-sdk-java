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

package io.fluxzero.common.api.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxzero.common.serialization.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Lightweight microbenchmark harness for Position performance.
 */
public class PositionBenchmark {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int WARMUP_ITERATIONS = 200_000;
    private static final int MEASURE_ITERATIONS = 1_000_000;
    private static final int[] HALF_ONE = new int[]{0, 64};
    private static final int[] HALF_TWO = new int[]{64, 128};
    private static final int[] CROSS_HALF = new int[]{32, 96};

    private static volatile Object blackhole;

    public static void main(String[] args) {
        new PositionBenchmark().run();
    }

    private void run() {
        Position fullA = position("[[0,128],1000]");
        Position fullB = position("[[0,128],1010]");
        Position splitA = position("[[0,64],1000,[64,128],980]");
        Position splitB = position("[[0,32],1005,[32,64],999,[64,96],990,[96,128],970]");
        String positionJson = JsonUtils.asJson(splitA);

        int[] segments = randomSegments(2 * MEASURE_ITERATIONS);
        long[] messageIndexes = randomIndexes(2 * MEASURE_ITERATIONS);

        benchmark("Position.merge full overlap", () -> blackhole = fullA.merge(fullB));
        benchmark("Position.merge rebalance overlap", () -> blackhole = splitA.merge(splitB));
        benchmark("Position.lowestIndexForSegment [0,64]", () -> blackhole = splitA.lowestIndexForSegment(HALF_ONE));
        benchmark("Position.lowestIndexForSegment [32,96]", () -> blackhole = splitA.lowestIndexForSegment(CROSS_HALF));
        benchmarkIndexed("Position.isNewIndex random segments", i ->
                blackhole = splitA.isNewIndex(segments[i], messageIndexes[i]));
        benchmarkIndexed("Position.getIndex random segments", i -> blackhole = splitA.getIndex(segments[i]));
        benchmark("Position serialize to JSON", () -> blackhole = JsonUtils.asJson(splitA));
        benchmark("Position deserialize from JSON", () -> blackhole = JsonUtils.fromJson(positionJson, Position.class));

        blackhole = List.of(fullA, fullB, splitA, splitB, HALF_TWO);
    }

    private static void benchmark(String name, Runnable op) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            op.run();
        }

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            op.run();
        }
        long elapsedNs = System.nanoTime() - start;

        printResult(name, elapsedNs, MEASURE_ITERATIONS);
    }

    private static void benchmarkIndexed(String name, IndexedRunnable op) {
        int warmupLength = Math.min(WARMUP_ITERATIONS, MEASURE_ITERATIONS);
        for (int i = 0; i < warmupLength; i++) {
            op.run(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            op.run(i);
        }
        long elapsedNs = System.nanoTime() - start;

        printResult(name, elapsedNs, MEASURE_ITERATIONS);
    }

    private static void printResult(String name, long elapsedNs, int operations) {
        double nsPerOp = (double) elapsedNs / operations;
        double opsPerSec = 1_000_000_000d / nsPerOp;
        System.out.printf("%-55s %12.2f ns/op %14.2f ops/s%n", name, nsPerOp, opsPerSec);
    }

    private static Position position(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) {
                return Position.newPosition();
            }
            List<SegmentRange> ranges = new ArrayList<>(root.size() / 2);
            for (int i = 0; i < root.size(); i += 2) {
                JsonNode segment = root.get(i);
                int start = segment.get(0).asInt();
                int end = segment.get(1).asInt();
                long index = root.get(i + 1).asLong();
                ranges.add(new SegmentRange(start, end, index));
            }
            return new Position(ranges);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid position json: " + json, e);
        }
    }

    private static int[] randomSegments(int count) {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = random.nextInt(0, SegmentRange.MAX_SEGMENT);
        }
        return result;
    }

    private static long[] randomIndexes(int count) {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = random.nextLong(900, 1_200);
        }
        return result;
    }

    @FunctionalInterface
    private interface IndexedRunnable {
        void run(int index);
    }
}
