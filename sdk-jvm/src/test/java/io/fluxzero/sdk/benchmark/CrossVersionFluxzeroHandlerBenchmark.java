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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.QueryGateway;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cross-version comparable benchmark for full Fluxzero handler dispatch.
 * <p>
 * This benchmark intentionally uses only stable public SDK APIs so the same source can be copied to another worktree
 * when comparing branches that do not share the generated-metadata backend APIs.
 * <pre>{@code
 * ./mvnw -q -pl sdk-jvm -DskipTests test-compile exec:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=io.fluxzero.sdk.benchmark.CrossVersionFluxzeroHandlerBenchmark \
 *   -Diterations=100000 -Dwarmup=2 -DsetupIterations=15
 * }</pre>
 */
public class CrossVersionFluxzeroHandlerBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 100_000);
    private static final int WARM_UP = Integer.getInteger("warmup", 2);
    private static final int SETUP_ITERATIONS = Integer.getInteger("setupIterations", 15);
    private static final List<Dispatch> DISPATCHES = dispatches();
    private static volatile long blackhole;

    public static void main(String[] args) {
        System.out.printf(
                Locale.ROOT,
                "CrossVersionFluxzeroHandlerBenchmark config: handlers=%d, iterations=%d, warmup=%d, setupIterations=%d%n",
                DISPATCHES.size(), ITERATIONS, WARM_UP, SETUP_ITERATIONS);

        for (int i = 0; i < WARM_UP; i++) {
            measureWarmDispatch(false);
        }

        Measurement buildAndRegister = measureBuildAndRegister();
        Measurement warmDispatch = measureWarmDispatch(true);

        System.out.printf("%-30s %12s %14s%n", "measurement", "millis", "ops/sec");
        System.out.printf("%-30s %12s %14s%n", "-----------", "------", "-------");
        print(buildAndRegister, SETUP_ITERATIONS);
        print(warmDispatch, ITERATIONS);
        System.out.println("blackhole=" + blackhole);
    }

    private static Measurement measureBuildAndRegister() {
        long started = System.nanoTime();
        long checksum = 0L;
        for (int i = 0; i < SETUP_ITERATIONS; i++) {
            BenchmarkHandlers handler = new BenchmarkHandlers();
            try (Fluxzero fluxzero = newFluxzero()) {
                fluxzero.registerHandlers(handler).cancel();
                checksum += handler.state();
            }
        }
        long nanos = System.nanoTime() - started;
        blackhole = checksum;
        return new Measurement("build+register.20Handlers", nanos);
    }

    private static Measurement measureWarmDispatch(boolean capture) {
        BenchmarkHandlers handler = new BenchmarkHandlers();
        try (Fluxzero fluxzero = newFluxzero()) {
            fluxzero.registerHandlers(handler);
            CommandGateway commands = fluxzero.commandGateway();
            QueryGateway queries = fluxzero.queryGateway();
            long started = System.nanoTime();
            long checksum = 0L;
            for (int i = 0; i < ITERATIONS; i++) {
                Dispatch dispatch = DISPATCHES.get(i % DISPATCHES.size());
                Number result = dispatch.query()
                        ? queries.<Number>sendAndWait(dispatch.payload())
                        : commands.<Number>sendAndWait(dispatch.payload());
                checksum += result.longValue();
            }
            long nanos = System.nanoTime() - started;
            blackhole = checksum + handler.state();
            return new Measurement(capture ? "warmDispatch.20Handlers" : "warmup", nanos);
        }
    }

    private static Fluxzero newFluxzero() {
        return DefaultFluxzero.builder()
                .disableShutdownHook()
                .build(LocalClient.newInstance(null));
    }

    private static void print(Measurement measurement, int operations) {
        double millis = measurement.nanos() / 1_000_000.0;
        double opsPerSecond = operations / (measurement.nanos() / 1_000_000_000.0);
        System.out.printf(Locale.ROOT, "%-30s %12.3f %14.1f%n",
                          measurement.name(), millis, opsPerSecond);
    }

    private static List<Dispatch> dispatches() {
        List<Dispatch> result = new ArrayList<>();
        result.add(new Dispatch(new Command01(1), false));
        result.add(new Dispatch(new Command02(2), false));
        result.add(new Dispatch(new Command03(3), false));
        result.add(new Dispatch(new Command04(4), false));
        result.add(new Dispatch(new Command05(5), false));
        result.add(new Dispatch(new Command06(6), false));
        result.add(new Dispatch(new Command07(7), false));
        result.add(new Dispatch(new Command08(8), false));
        result.add(new Dispatch(new Command09(9), false));
        result.add(new Dispatch(new Command10(10), false));
        result.add(new Dispatch(new Query01(11), true));
        result.add(new Dispatch(new Query02(12), true));
        result.add(new Dispatch(new Query03(13), true));
        result.add(new Dispatch(new Query04(14), true));
        result.add(new Dispatch(new Query05(15), true));
        result.add(new Dispatch(new Query06(16), true));
        result.add(new Dispatch(new Query07(17), true));
        result.add(new Dispatch(new Query08(18), true));
        result.add(new Dispatch(new Query09(19), true));
        result.add(new Dispatch(new Query10(20), true));
        return List.copyOf(result);
    }

    private record Measurement(String name, long nanos) {
    }

    private record Dispatch(Object payload, boolean query) {
    }

    record Command01(int value) {
    }

    record Command02(int value) {
    }

    record Command03(int value) {
    }

    record Command04(int value) {
    }

    record Command05(int value) {
    }

    record Command06(int value) {
    }

    record Command07(int value) {
    }

    record Command08(int value) {
    }

    record Command09(int value) {
    }

    record Command10(int value) {
    }

    record Query01(int value) {
    }

    record Query02(int value) {
    }

    record Query03(int value) {
    }

    record Query04(int value) {
    }

    record Query05(int value) {
    }

    record Query06(int value) {
    }

    record Query07(int value) {
    }

    record Query08(int value) {
    }

    record Query09(int value) {
    }

    record Query10(int value) {
    }

    @LocalHandler
    static class BenchmarkHandlers {
        private long state = 0x9E3779B97F4A7C15L;

        @HandleCommand
        Integer handle(Command01 command) {
            return next(command.value(), 1);
        }

        @HandleCommand
        Integer handle(Command02 command) {
            return next(command.value(), 2);
        }

        @HandleCommand
        Integer handle(Command03 command) {
            return next(command.value(), 3);
        }

        @HandleCommand
        Integer handle(Command04 command) {
            return next(command.value(), 4);
        }

        @HandleCommand
        Integer handle(Command05 command) {
            return next(command.value(), 5);
        }

        @HandleCommand
        Integer handle(Command06 command) {
            return next(command.value(), 6);
        }

        @HandleCommand
        Integer handle(Command07 command) {
            return next(command.value(), 7);
        }

        @HandleCommand
        Integer handle(Command08 command) {
            return next(command.value(), 8);
        }

        @HandleCommand
        Integer handle(Command09 command) {
            return next(command.value(), 9);
        }

        @HandleCommand
        Integer handle(Command10 command) {
            return next(command.value(), 10);
        }

        @HandleQuery
        Integer handle(Query01 query) {
            return next(query.value(), 11);
        }

        @HandleQuery
        Integer handle(Query02 query) {
            return next(query.value(), 12);
        }

        @HandleQuery
        Integer handle(Query03 query) {
            return next(query.value(), 13);
        }

        @HandleQuery
        Integer handle(Query04 query) {
            return next(query.value(), 14);
        }

        @HandleQuery
        Integer handle(Query05 query) {
            return next(query.value(), 15);
        }

        @HandleQuery
        Integer handle(Query06 query) {
            return next(query.value(), 16);
        }

        @HandleQuery
        Integer handle(Query07 query) {
            return next(query.value(), 17);
        }

        @HandleQuery
        Integer handle(Query08 query) {
            return next(query.value(), 18);
        }

        @HandleQuery
        Integer handle(Query09 query) {
            return next(query.value(), 19);
        }

        @HandleQuery
        Integer handle(Query10 query) {
            return next(query.value(), 20);
        }

        long state() {
            return state;
        }

        private Integer next(int value, int salt) {
            long next = state + 0x9E3779B97F4A7C15L + salt;
            next ^= next >>> 30;
            next *= 0xBF58476D1CE4E5B9L;
            next ^= next >>> 27;
            next *= 0x94D049BB133111EBL;
            next ^= next >>> 31;
            state = next;
            return (int) (next ^ value ^ salt);
        }
    }
}
