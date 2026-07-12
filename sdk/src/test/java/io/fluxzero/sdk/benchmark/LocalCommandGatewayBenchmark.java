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

import com.sun.management.ThreadMXBean;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.Trigger;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * Measures the cost of reaching a local command handler through the public command gateway.
 *
 * <p>This deliberately complements {@link MethodInvokerBenchmark}: that benchmark isolates the final method invocation,
 * while this benchmark adds handler selection and the complete local gateway path. The raw handler scenario bridges
 * both layers, making it possible to distinguish handling costs from message and gateway costs.</p>
 */
public class LocalCommandGatewayBenchmark {
    private static final int commandCount = Integer.getInteger("commands", 1024);
    private static final int iterations = Integer.getInteger("iterations", 1_000_000);
    private static final int warmupIterations = Integer.getInteger("warmupIterations", 100_000);
    private static final int warmups = Integer.getInteger("warmups", 3);
    private static final int baselineMultiplier = Integer.getInteger("baselineMultiplier", 50);
    private static final int selfHandlerShapes = Integer.getInteger("selfHandlerShapes", 128);
    private static final int virtualThreadIterations = Integer.getInteger("virtualThreadIterations", 10_000);
    private static final boolean disableCorrelation = Boolean.getBoolean("disableCorrelation");
    private static final boolean disableDataProtection = Boolean.getBoolean("disableDataProtection");
    private static final boolean disableValidation = Boolean.getBoolean("disableValidation");
    private static final boolean disableMetrics = Boolean.getBoolean("disableMetrics");
    private static final boolean profileGatewayOnly = Boolean.getBoolean("profileGatewayOnly");
    private static final int measurementDelayMillis = Integer.getInteger("measurementDelayMillis", 0);
    private static final boolean disableUserProvider = Boolean.getBoolean("disableUserProvider");
    private static final boolean observeMetadata = Boolean.getBoolean("observeMetadata");
    private static final boolean customDispatchInterceptor = Boolean.getBoolean("customDispatchInterceptor");

    private static final BenchmarkCommand[] commands = createCommands();
    private static final Message[] gatewayMessages = createGatewayMessages();
    private static final DeserializingMessage[] handlerMessages = createHandlerMessages();
    private static final ThreadMXBean allocationBean = allocationBean();
    private static volatile long blackhole;
    private static volatile long virtualThreadInvocationAllocatedBytes;

    public static void main(String[] args) throws Throwable {
        if (Integer.bitCount(commandCount) != 1) {
            throw new IllegalArgumentException("commands must be a power of two");
        }

        CommandHandler target = observeMetadata ? new MetadataObservingCommandHandler() : new CommandHandler();
        SelfHandlingCommand00000000[] selfHandlingCommands = createSelfHandlingCommands();
        Object[] variedSelfHandlingCommands = createVariedSelfHandlingCommands();
        MethodHandle methodHandle = MethodHandles.lookup().findVirtual(
                CommandHandler.class, "handle", MethodType.methodType(BenchmarkResult.class, BenchmarkCommand.class));

        FluxzeroBuilder builder = createBuilder();
        try (Fluxzero fluxzero = builder.build(LocalClient.newInstance(null));
             Fluxzero isolatedFluxzero = createBuilder().build(LocalClient.newInstance(null));
             Fluxzero selfHandlingFluxzero = createBuilder().build(LocalClient.newInstance(null));
             Fluxzero userParameterFluxzero = createBuilder().build(LocalClient.newInstance(null));
             Fluxzero metadataParameterFluxzero = createBuilder().build(LocalClient.newInstance(null))) {
            CommandGateway gateway = fluxzero.commandGateway();
            CommandGateway isolatedGateway = isolatedFluxzero.commandGateway();
            CommandGateway selfHandlingGateway = selfHandlingFluxzero.commandGateway();
            CommandGateway userParameterGateway = userParameterFluxzero.commandGateway();
            CommandGateway metadataParameterGateway = metadataParameterFluxzero.commandGateway();
            gateway.registerHandler(target);
            gateway.registerHandler(new SecondaryCommandHandler());
            gateway.registerHandler(new DynamicContextCommandHandler());
            isolatedGateway.registerHandler(new CommandHandler());
            userParameterGateway.registerHandler(new UserParameterCommandHandler());
            metadataParameterGateway.registerHandler(new MetadataParameterCommandHandler());
            Handler<DeserializingMessage> rawHandler = HandlerInspector.createHandler(
                    target, HandleCommand.class, fluxzero.configuration().parameterResolvers());

            System.out.printf("config commands=%d iterations=%d warmups=%d baselineMultiplier=%d "
                              + "disableCorrelation=%s disableDataProtection=%s disableValidation=%s "
                              + "disableMetrics=%s disableUserProvider=%s%n", commandCount, iterations, warmups,
                              baselineMultiplier, disableCorrelation, disableDataProtection, disableValidation,
                              disableMetrics, disableUserProvider);
            System.out.printf("fallbacks observeMetadata=%s customDispatchInterceptor=%s%n",
                              observeMetadata, customDispatchInterceptor);
            System.out.printf("self handlers shapes=%d virtualThreadIterations=%d%n",
                              selfHandlerShapes, virtualThreadIterations);
            if (profileGatewayOnly) {
                for (int i = 0; i < warmups; i++) {
                    runGatewayPayloads(gateway, warmupIterations);
                }
                if (measurementDelayMillis > 0) {
                    Thread.sleep(measurementDelayMillis);
                }
                measure("local-gateway-payload", iterations, () -> runGatewayPayloads(gateway, iterations));
                System.out.println("blackhole=" + blackhole + ", invocations=" + target.invocations);
                return;
            }
            for (int i = 0; i < warmups; i++) {
                runDirect(target, warmupIterations * baselineMultiplier);
                runMethodHandle(methodHandle, target, warmupIterations * baselineMultiplier);
                runRawHandler(rawHandler, warmupIterations);
                runGatewayMessages(gateway, warmupIterations);
                runGatewayPayloads(gateway, warmupIterations);
                runGatewayPayloads(isolatedGateway, warmupIterations);
                runGatewayPayloads(userParameterGateway, warmupIterations);
                runGatewayPayloads(metadataParameterGateway, warmupIterations);
                runSelfHandlingPayloads(selfHandlingGateway, selfHandlingCommands, warmupIterations);
                runSelfHandlingPayloads(selfHandlingGateway, variedSelfHandlingCommands, warmupIterations);
                runSelfHandlingPayloads(gateway, selfHandlingCommands, warmupIterations);
                runSelfHandlingPayloads(gateway, variedSelfHandlingCommands, warmupIterations);
            }

            double directNanos = measure("direct-java", iterations * baselineMultiplier,
                                         () -> runDirect(target, iterations * baselineMultiplier));
            double methodHandleNanos = measure(
                    "method-handle", iterations * baselineMultiplier,
                    () -> runMethodHandle(methodHandle, target, iterations * baselineMultiplier));
            measure("fluxzero-handler", iterations, () -> runRawHandler(rawHandler, iterations));
            double prebuiltGatewayNanos = measure(
                    "local-gateway-prebuilt-message", iterations, () -> runGatewayMessages(gateway, iterations));
            double payloadGatewayNanos = measure(
                    "local-gateway-payload-mixed-dynamic", iterations,
                    () -> runGatewayPayloads(gateway, iterations));
            measure("local-gateway-payload-isolated", iterations,
                    () -> runGatewayPayloads(isolatedGateway, iterations));
            measure("local-gateway-user-parameter", iterations,
                    () -> runGatewayPayloads(userParameterGateway, iterations));
            measure("local-gateway-metadata-parameter", iterations,
                    () -> runGatewayPayloads(metadataParameterGateway, iterations));
            measure("local-gateway-self-handler", iterations,
                    () -> runSelfHandlingPayloads(selfHandlingGateway, selfHandlingCommands, iterations));
            measure("local-gateway-self-handler-varied", iterations,
                    () -> runSelfHandlingPayloads(
                            selfHandlingGateway, variedSelfHandlingCommands, iterations));
            measure("local-gateway-self-handler-mixed", iterations,
                    () -> runSelfHandlingPayloads(gateway, selfHandlingCommands, iterations));
            measure("local-gateway-self-handler-varied-mixed", iterations,
                    () -> runSelfHandlingPayloads(gateway, variedSelfHandlingCommands, iterations));
            if (virtualThreadIterations > 0) {
                int virtualThreadWarmup = Math.min(1_000, virtualThreadIterations);
                runVirtualThreadDirectSelfHandlingPayloads(selfHandlingCommands, virtualThreadWarmup);
                runVirtualThreadSelfHandlingPayloads(
                        selfHandlingGateway, selfHandlingCommands, virtualThreadWarmup);
                measure("direct-self-handler-virtual-thread-once", virtualThreadIterations,
                        () -> runVirtualThreadDirectSelfHandlingPayloads(
                                selfHandlingCommands, virtualThreadIterations));
                printVirtualThreadInvocationAllocations("direct-self-handler", virtualThreadIterations);
                measure("local-gateway-self-handler-virtual-thread-once", virtualThreadIterations,
                        () -> runVirtualThreadSelfHandlingPayloads(
                                selfHandlingGateway, selfHandlingCommands, virtualThreadIterations));
                printVirtualThreadInvocationAllocations(
                        "local-gateway-self-handler", virtualThreadIterations);
                System.out.println("virtual-thread bytes/op covers allocations on the initiating thread only");
            }
            System.out.printf("gateway/direct: %.1fx prebuilt, %.1fx payload%n",
                              prebuiltGatewayNanos / directNanos, payloadGatewayNanos / directNanos);
            System.out.printf("gateway/method-handle: %.1fx prebuilt, %.1fx payload%n",
                              prebuiltGatewayNanos / methodHandleNanos, payloadGatewayNanos / methodHandleNanos);
            System.out.println("blackhole=" + blackhole + ", invocations=" + target.invocations);
        }
    }

    private static FluxzeroBuilder createBuilder() {
        FluxzeroBuilder builder = DefaultFluxzero.builder();
        if (!disableUserProvider) {
            builder.registerUserProvider(new FixedUserProvider(new MockUser("benchmark")));
        }
        if (disableCorrelation) {
            builder.disableMessageCorrelation();
        }
        if (disableDataProtection) {
            builder.disableDataProtection();
        }
        if (disableValidation) {
            builder.disablePayloadValidation();
        }
        if (disableMetrics) {
            builder.disableTrackingMetrics();
        }
        if (customDispatchInterceptor) {
            builder.addDispatchInterceptor((message, messageType, topic) -> message, MessageType.COMMAND);
        }
        return builder;
    }

    private static double measure(String name, int operationCount, ThrowingRunnable scenario) throws Throwable {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        scenario.run();
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        double nanosPerOperation = (double) elapsed / operationCount;
        System.out.printf("%s: %.2f ns/op, %.2f bytes/op, %.1f M ops/s%n", name, nanosPerOperation,
                          (double) allocated / operationCount, operationCount * 1_000d / elapsed);
        return nanosPerOperation;
    }

    private static void runDirect(CommandHandler target, int count) {
        long result = 0L;
        int mask = commands.length - 1;
        for (int i = 0; i < count; i++) {
            result += target.handle(commands[i & mask]).value;
        }
        blackhole = result;
    }

    private static void runMethodHandle(MethodHandle methodHandle, CommandHandler target, int count) throws Throwable {
        long result = 0L;
        int mask = commands.length - 1;
        for (int i = 0; i < count; i++) {
            BenchmarkResult value = (BenchmarkResult) methodHandle.invokeExact(target, commands[i & mask]);
            result += value.value();
        }
        blackhole = result;
    }

    private static void runRawHandler(Handler<DeserializingMessage> handler, int count) {
        long result = 0L;
        int mask = handlerMessages.length - 1;
        for (int i = 0; i < count; i++) {
            BenchmarkResult value = (BenchmarkResult) handler.getInvokerOrNull(handlerMessages[i & mask]).invoke();
            result += value.value;
        }
        blackhole = result;
    }

    private static void runGatewayMessages(CommandGateway gateway, int count) {
        long result = 0L;
        int mask = gatewayMessages.length - 1;
        for (int i = 0; i < count; i++) {
            BenchmarkResult value = gateway.sendAndWait(gatewayMessages[i & mask]);
            result += value.value;
        }
        blackhole = result;
    }

    private static void runGatewayPayloads(CommandGateway gateway, int count) {
        long result = 0L;
        int mask = commands.length - 1;
        for (int i = 0; i < count; i++) {
            BenchmarkResult value = gateway.sendAndWait(commands[i & mask]);
            result += value.value;
        }
        blackhole = result;
    }

    private static void runSelfHandlingPayloads(CommandGateway gateway, Object[] payloads, int count) {
        long result = 0L;
        int mask = payloads.length - 1;
        for (int i = 0; i < count; i++) {
            SelfHandlingResult value = gateway.sendAndWait(payloads[i & mask]);
            result += value.value();
        }
        blackhole = result;
    }

    private static void runVirtualThreadSelfHandlingPayloads(
            CommandGateway gateway, Object[] payloads, int count) throws InterruptedException {
        SelfHandlingResult[] results = new SelfHandlingResult[count];
        long[] invocationAllocations = new long[count];
        int mask = payloads.length - 1;
        long result = 0L;
        for (int i = 0; i < count; i++) {
            int index = i;
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                long threadId = Thread.currentThread().threadId();
                long allocatedBefore = allocationBean == null
                        ? -1L : allocationBean.getThreadAllocatedBytes(threadId);
                results[index] = gateway.sendAndWait(payloads[index & mask]);
                long allocatedAfter = allocationBean == null
                        ? -1L : allocationBean.getThreadAllocatedBytes(threadId);
                invocationAllocations[index] = allocatedBefore < 0L || allocatedAfter < 0L
                        ? -1L : allocatedAfter - allocatedBefore;
            });
            thread.start();
            thread.join();
            result += results[i].value();
        }
        blackhole = result;
        virtualThreadInvocationAllocatedBytes = sum(invocationAllocations);
    }

    private static void runVirtualThreadDirectSelfHandlingPayloads(
            SelfHandlingCommand00000000[] payloads, int count) throws InterruptedException {
        SelfHandlingResult[] results = new SelfHandlingResult[count];
        long[] invocationAllocations = new long[count];
        int mask = payloads.length - 1;
        long result = 0L;
        for (int i = 0; i < count; i++) {
            int index = i;
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                long threadId = Thread.currentThread().threadId();
                long allocatedBefore = allocationBean == null
                        ? -1L : allocationBean.getThreadAllocatedBytes(threadId);
                results[index] = payloads[index & mask].handle();
                long allocatedAfter = allocationBean == null
                        ? -1L : allocationBean.getThreadAllocatedBytes(threadId);
                invocationAllocations[index] = allocatedBefore < 0L || allocatedAfter < 0L
                        ? -1L : allocatedAfter - allocatedBefore;
            });
            thread.start();
            thread.join();
            result += results[i].value();
        }
        blackhole = result;
        virtualThreadInvocationAllocatedBytes = sum(invocationAllocations);
    }

    private static long sum(long[] values) {
        long result = 0L;
        for (long value : values) {
            if (value < 0L) {
                return -1L;
            }
            result += value;
        }
        return result;
    }

    private static void printVirtualThreadInvocationAllocations(String name, int operationCount) {
        if (virtualThreadInvocationAllocatedBytes < 0L) {
            System.out.printf("%s virtual invocation: allocation measurement unavailable%n", name);
        } else {
            System.out.printf("%s virtual invocation: %.2f bytes/op%n", name,
                              (double) virtualThreadInvocationAllocatedBytes / operationCount);
        }
    }

    private static BenchmarkCommand[] createCommands() {
        BenchmarkCommand[] result = new BenchmarkCommand[commandCount];
        for (int i = 0; i < result.length; i++) {
            result[i] = new BenchmarkCommand(new BenchmarkResult(i));
        }
        return result;
    }

    private static SelfHandlingCommand00000000[] createSelfHandlingCommands() {
        SelfHandlingCommand00000000[] result = new SelfHandlingCommand00000000[commandCount];
        for (int i = 0; i < result.length; i++) {
            result[i] = new SelfHandlingCommand00000000(new SelfHandlingResult(i));
        }
        return result;
    }

    private static Object[] createVariedSelfHandlingCommands() throws Throwable {
        if (Integer.bitCount(selfHandlerShapes) != 1) {
            throw new IllegalArgumentException("selfHandlerShapes must be a power of two");
        }
        byte[] template = readClassBytes(SelfHandlingCommand00000000.class);
        Object[] result = new Object[selfHandlerShapes];
        for (int i = 0; i < result.length; i++) {
            String className = "SelfHandlingCommand%08x".formatted(i + 1);
            Class<?> commandClass = MethodHandles.lookup().defineClass(renameTemplateClass(template, className));
            MethodHandle constructor = MethodHandles.lookup().findConstructor(
                    commandClass, MethodType.methodType(void.class, SelfHandlingResult.class));
            result[i] = constructor.invoke(new SelfHandlingResult(i));
        }
        return result;
    }

    private static byte[] renameTemplateClass(byte[] template, String simpleName) {
        byte[] result = template.clone();
        byte[] originalName = SelfHandlingCommand00000000.class.getName().replace('.', '/')
                .getBytes(StandardCharsets.UTF_8);
        byte[] replacement = (SelfHandlingCommand00000000.class.getPackageName().replace('.', '/')
                              + "/" + simpleName).getBytes(StandardCharsets.UTF_8);
        if (originalName.length != replacement.length) {
            throw new IllegalArgumentException("Generated class name must have the same length as the template name");
        }
        boolean replaced = false;
        for (int offset = 0; offset <= result.length - originalName.length; offset++) {
            int index = 0;
            while (index < originalName.length && result[offset + index] == originalName[index]) {
                index++;
            }
            if (index == originalName.length) {
                System.arraycopy(replacement, 0, result, offset, replacement.length);
                replaced = true;
                offset += replacement.length - 1;
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("Could not rename benchmark command template");
        }
        return result;
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Could not load " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static Message[] createGatewayMessages() {
        Message[] result = new Message[commands.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Message(commands[i]);
        }
        return result;
    }

    private static DeserializingMessage[] createHandlerMessages() {
        DeserializingMessage[] result = new DeserializingMessage[commands.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = new DeserializingMessage(gatewayMessages[i], MessageType.COMMAND, null);
        }
        return result;
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean threadBean) || !threadBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }
        return threadBean;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private record BenchmarkCommand(BenchmarkResult result) {
    }

    private record BenchmarkResult(int value) {
    }

    private record UnrelatedCommandA() {
    }

    private record UnrelatedCommandB() {
    }

    private record UnrelatedCommandC() {
    }

    private record UnrelatedCommandD() {
    }

    @LocalHandler
    private static class CommandHandler {
        private long invocations;

        @HandleCommand
        BenchmarkResult handle(BenchmarkCommand command) {
            invocations++;
            return command.result;
        }

        @HandleCommand
        BenchmarkResult handle(UnrelatedCommandA ignored) {
            invocations++;
            return new BenchmarkResult(-1);
        }

        @HandleCommand
        BenchmarkResult handle(UnrelatedCommandB ignored) {
            invocations++;
            return new BenchmarkResult(-1);
        }
    }

    @LocalHandler
    private static class MetadataObservingCommandHandler extends CommandHandler {
        @Override
        @HandleCommand
        BenchmarkResult handle(BenchmarkCommand command) {
            DeserializingMessage current = DeserializingMessage.getCurrent();
            if (current != null) {
                current.getMetadata().containsKey("benchmark");
            }
            return super.handle(command);
        }
    }

    @LocalHandler
    private static class SecondaryCommandHandler {
        @HandleCommand
        BenchmarkResult handle(UnrelatedCommandC ignored) {
            return new BenchmarkResult(-1);
        }
    }

    @LocalHandler
    private static class DynamicContextCommandHandler {
        @HandleCommand
        BenchmarkResult handle(@Trigger Object ignoredTrigger, UnrelatedCommandD ignored) {
            return new BenchmarkResult(-1);
        }
    }

    @LocalHandler
    private static class UserParameterCommandHandler {
        @HandleCommand
        BenchmarkResult handle(BenchmarkCommand command, User user) {
            if (user == null) {
                throw new AssertionError("Expected an injected user");
            }
            return command.result;
        }
    }

    @LocalHandler
    private static class MetadataParameterCommandHandler {
        @HandleCommand
        BenchmarkResult handle(BenchmarkCommand command, Metadata metadata) {
            if (metadata == null) {
                throw new AssertionError("Expected injected metadata");
            }
            return command.result;
        }
    }
}

class SelfHandlingCommand00000000 {
    private final SelfHandlingResult result;

    SelfHandlingCommand00000000(SelfHandlingResult result) {
        this.result = result;
    }

    @HandleCommand
    SelfHandlingResult handle() {
        return result;
    }
}

record SelfHandlingResult(int value) {
}
