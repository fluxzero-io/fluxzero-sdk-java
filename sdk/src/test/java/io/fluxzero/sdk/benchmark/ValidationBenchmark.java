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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.TimingUtils;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.handling.validation.DefaultValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Small validation hot-path benchmark that is intentionally runnable from test sources without extra tooling.
 */
public class ValidationBenchmark {
    private static final int ITERATIONS = Integer.getInteger("iterations", 1_000_000);
    private static final int WARM_UP = Integer.getInteger("warmup", 2);
    private static final int THREADS = Math.max(1, Integer.getInteger("threads", 1));
    private static final boolean NESTED = Boolean.getBoolean("nested");
    private static final DefaultValidator validator = DefaultValidator.createDefault();
    private static final Object validPayload = NESTED
            ? new NestedPayload("order-1", 5, new Details("customer-1", true),
                                List.of(new Details("line-1", true), new Details("line-2", true)))
            : new FlatPayload("order-1", 5, new Details("customer-1", true),
                              List.of(new Details("line-1", true), new Details("line-2", true)));
    private static final Object invalidPayload = NESTED
            ? new NestedPayload("", 1, new Details("", false), List.of(new Details("", false)))
            : new FlatPayload("", 1, new Details("", false), List.of(new Details("", false)));
    private static final Object[] validArguments = new Object[]{"order-1", validPayload};
    private static final Object[] invalidArguments = new Object[]{"", invalidPayload};
    private static final ContextPayload contextPayload = new ContextPayload();
    private static final MessageAwarePayload messageAwarePayload = new MessageAwarePayload();
    private static final DeserializingMessage contextMessage = message(contextPayload);
    private static final DeserializingMessage messageAwareContext = message(messageAwarePayload);
    private static Fluxzero fluxzero;
    private static final Method handleMethod;
    private static volatile Object blackhole;

    static {
        try {
            handleMethod = NESTED
                    ? Handler.class.getDeclaredMethod("handleNested", String.class, NestedPayload.class)
                    : Handler.class.getDeclaredMethod("handleFlat", String.class, FlatPayload.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) {
        try {
            System.out.printf("Benchmark config: iterations=%d, warmup=%d, threads=%d, nested=%s%n",
                              ITERATIONS, WARM_UP, THREADS, NESTED);
            for (int i = 0; i < WARM_UP; i++) {
                benchmarkValidPayload();
                benchmarkInvalidPayload();
                benchmarkValidParameters();
                benchmarkInvalidParameters();
            }
            run("validation valid payload", ValidationBenchmark::benchmarkValidPayload);
            run("validation invalid payload", ValidationBenchmark::benchmarkInvalidPayload);
            run("validation valid parameters", ValidationBenchmark::benchmarkValidParameters);
            run("validation invalid parameters", ValidationBenchmark::benchmarkInvalidParameters);
            for (int i = 0; i < WARM_UP; i++) {
                benchmarkContextPayload();
                benchmarkMessageAwarePayload();
            }
            run("validation context payload", ValidationBenchmark::benchmarkContextPayload);
            run("validation message-aware payload", ValidationBenchmark::benchmarkMessageAwarePayload);
        } finally {
            if (fluxzero != null) {
                fluxzero.close(true);
            }
        }
    }

    private static void run(String name, Runnable scenario) {
        TimingUtils.time(scenario, duration -> {
            long totalIterations = (long) ITERATIONS * THREADS;
            long operationsPerSecond = duration == 0 ? totalIterations : totalIterations * 1000L / duration;
            System.out.printf("%s: %d iterations in %dms (%d ops/s)%n",
                              name, totalIterations, duration, operationsPerSecond);
        });
    }

    private static void benchmarkValidPayload() {
        runIterations(handler -> validator.checkValidity(validPayload));
    }

    private static void benchmarkInvalidPayload() {
        runIterations(handler -> validator.checkValidity(invalidPayload));
    }

    private static void benchmarkValidParameters() {
        runIterations(handler -> validator.checkParameterValidity(handler, handleMethod, validArguments));
    }

    private static void benchmarkInvalidParameters() {
        runIterations(handler -> validator.checkParameterValidity(handler, handleMethod, invalidArguments));
    }

    private static void benchmarkContextPayload() {
        runIterations(handler -> fluxzero().apply(
                fc -> contextMessage.apply(message -> validator.checkValidity(contextPayload))));
    }

    private static void benchmarkMessageAwarePayload() {
        runIterations(handler -> fluxzero().apply(
                fc -> messageAwareContext.apply(message -> validator.checkValidity(messageAwarePayload))));
    }

    private static Fluxzero fluxzero() {
        if (fluxzero == null) {
            fluxzero = DefaultFluxzero.builder().build(LocalClient.newInstance(null));
        }
        return fluxzero;
    }

    private static void runIterations(ValidationOperation operation) {
        if (THREADS == 1) {
            blackhole = runWorker(operation);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>(THREADS);
            for (int i = 0; i < THREADS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return runWorker(operation);
                }));
            }
            start.countDown();
            Object result = null;
            for (Future<Object> future : futures) {
                result = future.get();
            }
            blackhole = result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validation benchmark interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Validation benchmark failed", e.getCause());
        } finally {
            executor.shutdown();
        }
    }

    private static Object runWorker(ValidationOperation operation) {
        Handler handler = new Handler();
        Object result = null;
        for (int i = 0; i < ITERATIONS; i++) {
            result = operation.validate(handler);
        }
        return result;
    }

    @FunctionalInterface
    private interface ValidationOperation {
        Object validate(Handler handler);
    }

    private record FlatPayload(@NotBlank String id,
                               @Min(5) long priority,
                               @NotNull Details details,
                               @Size(min = 1) List<Details> lines) {
    }

    private record NestedPayload(@NotBlank String id,
                                 @Min(5) long priority,
                                 @Valid @NotNull Details details,
                                 @Size(min = 1) List<@Valid Details> lines) {
    }

    private record Details(@NotBlank String name, @AssertTrue boolean active) {
    }

    private static class ContextPayload {
        @AssertTrue
        boolean isValid() {
            return true;
        }
    }

    private static class MessageAwarePayload {
        @AssertTrue
        boolean isValid(DeserializingMessage message) {
            return message != null && message.getPayloadClass() == MessageAwarePayload.class;
        }
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(new Message(payload, Metadata.empty()), MessageType.COMMAND, null);
    }

    private static class Handler {
        void handleFlat(@NotBlank String id, @NotNull FlatPayload payload) {
        }

        void handleNested(@NotBlank String id, @Valid @NotNull NestedPayload payload) {
        }
    }
}
