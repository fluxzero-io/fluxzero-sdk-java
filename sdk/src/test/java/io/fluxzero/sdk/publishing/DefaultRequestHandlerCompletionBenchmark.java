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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.AbstractList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;

/**
 * Standalone Java 25 diagnostic for the real request-correlation and caller-completion path.
 *
 * <p>The benchmark deliberately keeps only a bounded number of requests live at once. It still assigns a fresh
 * request ID, callback and future to every operation, so a ten-million-operation run exercises ten million distinct
 * logical requests without retaining several gigabytes of completed futures. Incoming results use the same batched
 * {@link DefaultRequestHandler#handleResults(List)} entry point as the WebSocket result tracker.</p>
 */
public final class DefaultRequestHandlerCompletionBenchmark {

    private static final int OPERATIONS = Integer.getInteger("completionBenchmark.operations", 10_485_760);
    private static final int WARMUP_OPERATIONS = Integer.getInteger("completionBenchmark.warmupOperations", 1_048_576);
    private static final int WINDOW_SIZE = Integer.getInteger("completionBenchmark.windowSize", 65_536);
    private static final int BATCH_SIZE = Integer.getInteger("completionBenchmark.batchSize", 2_048);

    private static final Duration NO_TIMEOUT = Duration.ofMillis(-1);
    private static final JacksonSerializer SERIALIZER = new JacksonSerializer();
    private static final Data<byte[]> REQUEST_DATA = new Data<>(new byte[16], "benchmark-command", 0);
    private static final Data<byte[]> RESULT_DATA = SERIALIZER.serialize(null);
    private static final Metadata RESULT_METADATA = Metadata.of(
            "$applicationId", "benchmark-app",
            "$clientId", "handler-client",
            "$clientName", "handler",
            "$consumer", "command-consumer",
            "$tracker", "tracker-0",
            "$invocation", "invocation-0",
            "$handler", "NoApplyControlHandler.handle",
            "$correlationId", "123456789",
            "$traceId", "123456789",
            "$trigger", "io.fluxzero.benchmarks.UpdateModel",
            "$triggerType", "COMMAND",
            "$triggerNamespace", "public",
            "$delay", "0");
    private static volatile Object blackhole;

    private DefaultRequestHandlerCompletionBenchmark() {
    }

    public static void main(String[] args) {
        validateConfiguration();
        System.out.printf(Locale.ROOT,
                          "Java %s; operations=%,d; warmup=%,d; window=%,d; result batch=%,d%n",
                          Runtime.version(), OPERATIONS, WARMUP_OPERATIONS, WINDOW_SIZE, BATCH_SIZE);

        String selected = System.getProperty("completionBenchmark.scenario", "all");
        boolean executed = false;
        for (Scenario scenario : Scenario.values()) {
            if ("all".equalsIgnoreCase(selected) || scenario.name().equalsIgnoreCase(selected)) {
                runScenario(scenario.label, scenario);
                executed = true;
            }
        }
        if (!executed) {
            throw new IllegalArgumentException("Unknown completionBenchmark.scenario: " + selected);
        }
        System.out.println("blackhole identity=" + System.identityHashCode(blackhole));
    }

    private static void runScenario(String name, Scenario scenario) {
        ExecutorService responseExecutor = newWorkerPool("completion-benchmark", 8);
        DefaultRequestHandler handler = new DefaultRequestHandler(
                benchmarkClient(), MessageType.RESULT, NO_TIMEOUT, "completion-benchmark", responseExecutor);
        try {
            runPhase(handler, WARMUP_OPERATIONS, scenario);
            PhaseResult result = runPhase(handler, OPERATIONS, scenario);
            System.out.printf(Locale.ROOT,
                              "%s: registration %,.3fM/s; completion %,.3fM/s; complete loop %,.3fM/s; "
                                      + "%,.1f ns/result completion%n",
                              name,
                              rate(result.operations(), result.registrationNanos()) / 1_000_000.0,
                              rate(result.operations(), result.completionNanos()) / 1_000_000.0,
                              rate(result.operations(), result.wallNanos()) / 1_000_000.0,
                              (double) result.completionNanos() / result.operations());
        } finally {
            handler.close();
        }
    }

    private static PhaseResult runPhase(DefaultRequestHandler handler, int operations, Scenario scenario) {
        int capacity = Math.min(WINDOW_SIZE, operations);
        List<SerializedMessage> requests = messages(capacity, REQUEST_DATA, "request");
        @SuppressWarnings("unchecked")
        CompletableFuture<?>[] callerFutures = new CompletableFuture[capacity];
        Map<String, CompletableFuture<Message>> gatewayCallbacks = new ConcurrentHashMap<>();
        HarnessState harness = scenario == Scenario.SDK_CALLER_AND_HARNESS ? new HarnessState(operations) : null;

        long registrationNanos = 0L;
        long completionNanos = 0L;
        long wallStarted = System.nanoTime();
        int completed = 0;
        while (completed < operations) {
            int window = Math.min(capacity, operations - completed);
            List<SerializedMessage> responses = envelopeMessages(window, RESULT_DATA);
            long registrationStarted = System.nanoTime();
            for (int i = 0; i < window; i++) {
                SerializedMessage request = requests.get(i);
                CompletableFuture<SerializedMessage> raw = handler.prepareRequest(request, NO_TIMEOUT, null);
                callerFutures[i] = switch (scenario) {
                    case REQUEST_HANDLER_ONLY -> raw;
                    case SDK_CALLER_CHAIN, SDK_CALLER_AND_HARNESS -> callerFuture(
                            request, raw, gatewayCallbacks, harness, completed + i);
                };
            }
            registrationNanos += System.nanoTime() - registrationStarted;

            for (int i = 0; i < window; i++) {
                responses.get(i).setRequestId(requests.get(i).getRequestId());
            }

            long completionStarted = System.nanoTime();
            for (int from = 0; from < window; from += BATCH_SIZE) {
                int until = Math.min(window, from + BATCH_SIZE);
                handler.handleResults(responses.subList(from, until));
            }
            blackhole = callerFutures[window - 1].join();
            completionNanos += System.nanoTime() - completionStarted;
            completed += window;
        }
        if (!gatewayCallbacks.isEmpty()) {
            throw new IllegalStateException("Gateway callback map retained " + gatewayCallbacks.size() + " requests");
        }
        if (harness != null && harness.completed().getCount() != 0L) {
            throw new IllegalStateException("Harness did not observe every caller completion");
        }
        return new PhaseResult(operations, registrationNanos, completionNanos, System.nanoTime() - wallStarted);
    }

    private static CompletableFuture<Object> callerFuture(
            SerializedMessage request,
            CompletableFuture<SerializedMessage> raw,
            Map<String, CompletableFuture<Message>> gatewayCallbacks,
            HarnessState harness,
            int ordinal) {
        CompletableFuture<Message> mapped = raw.thenCompose(response -> {
            Object payload = SERIALIZER.deserialize(response);
            return CompletableFuture.completedFuture(new Message(payload, response.getMetadata()));
        });
        String messageId = request.getMessageId();
        gatewayCallbacks.put(messageId, mapped);
        CompletableFuture<Object> caller = mapped.whenComplete(
                        (message, error) -> gatewayCallbacks.remove(messageId))
                .thenApply(Message::getPayload);
        return harness == null ? caller : caller.whenComplete((result, error) -> {
            harness.latencies()[ordinal] = System.nanoTime();
            harness.permits().release();
            harness.completed().countDown();
        });
    }

    private static List<SerializedMessage> messages(int count, Data<byte[]> data, String idPrefix) {
        SerializedMessage[] messages = new SerializedMessage[count];
        for (int i = 0; i < count; i++) {
            messages[i] = new SerializedMessage(data, Metadata.empty(), idPrefix + i, 0L);
        }
        return new AbstractList<>() {
            @Override
            public SerializedMessage get(int index) {
                return messages[index];
            }

            @Override
            public int size() {
                return messages.length;
            }
        };
    }

    private static List<SerializedMessage> envelopeMessages(int count, Data<byte[]> data) {
        SerializedMessage encoded = SerializedMessage.encode(
                new SerializedMessage(data, RESULT_METADATA, "result", 0L));
        byte[] template = encoded.copyEnvelope();
        SerializedMessage[] messages = new SerializedMessage[count];
        for (int i = 0; i < count; i++) {
            byte[] envelope = template.clone();
            try {
                messages[i] = SerializedMessage.decodeView(envelope, 0, envelope.length);
            } catch (Exception e) {
                throw new IllegalStateException("Could not construct benchmark result envelope", e);
            }
        }
        return List.of(messages);
    }

    private static Client benchmarkClient() {
        return (Client) Proxy.newProxyInstance(
                Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id", "name", "applicationId" -> "completion-benchmark";
                    case "namespace" -> "public";
                    case "toString" -> "CompletionBenchmarkClient";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static void validateConfiguration() {
        if (OPERATIONS <= 0 || WARMUP_OPERATIONS <= 0 || WINDOW_SIZE <= 0 || BATCH_SIZE <= 0) {
            throw new IllegalArgumentException("All benchmark sizes must be positive");
        }
        if (BATCH_SIZE > WINDOW_SIZE) {
            throw new IllegalArgumentException("completionBenchmark.batchSize cannot exceed windowSize");
        }
    }

    private static double rate(long operations, long nanos) {
        return operations * 1_000_000_000.0 / nanos;
    }

    private record PhaseResult(long operations, long registrationNanos, long completionNanos, long wallNanos) {
    }

    private record HarnessState(long[] latencies, Semaphore permits, CountDownLatch completed) {
        private HarnessState(int operations) {
            this(new long[operations], new Semaphore(0), new CountDownLatch(operations));
        }
    }

    private enum Scenario {
        REQUEST_HANDLER_ONLY("request-handler-only"),
        SDK_CALLER_CHAIN("full-sdk-caller-chain"),
        SDK_CALLER_AND_HARNESS("full-sdk-caller-chain-plus-benchmark-callback");

        private final String label;

        Scenario(String label) {
            this.label = label;
        }
    }
}
