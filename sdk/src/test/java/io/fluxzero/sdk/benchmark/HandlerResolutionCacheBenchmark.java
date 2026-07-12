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
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.EntityParameterResolver;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.JsonPayloadParameterResolver;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.MetadataParameterResolver;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import io.fluxzero.sdk.tracking.handling.TimestampParameterResolver;
import io.fluxzero.sdk.tracking.handling.TriggerParameterResolver;
import io.fluxzero.sdk.tracking.metrics.HandlerMonitor;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-version benchmark for dynamic handler selection and handler interceptor wrapping.
 *
 * <p>The dynamic selection scenarios rotate through 128 distinct proxy payload classes. This keeps more than one
 * hundred entries hot in every applicable resolver cache and deliberately prevents a monomorphic last-key cache from
 * dominating the result. The source only uses APIs that predate keyed resolver caching, so the same compiled benchmark
 * can run against SDK versions before and after the optimization.</p>
 */
public class HandlerResolutionCacheBenchmark {
    private static final int keyCount = Integer.getInteger("keys", 128);
    private static final int iterations = Integer.getInteger("iterations", 2_000_000);
    private static final int warmupIterations = Integer.getInteger("warmupIterations", 500_000);
    private static final int warmups = Integer.getInteger("warmups", 3);
    private static final int interceptorCount = Integer.getInteger("interceptors", 5);

    private static final DeserializingMessage[] shapeMessages = createShapeMessages();
    private static final DeserializingMessage stableMessage = new DeserializingMessage(
            new Message("stable"), MessageType.COMMAND, null);
    private static final List<ParameterResolver<? super DeserializingMessage>> parameterResolvers = List.of(
            new TriggerParameterResolver(null, null), new MessageParameterResolver(),
            new MetadataParameterResolver(), new TimestampParameterResolver(), new PayloadParameterResolver(),
            new JsonPayloadParameterResolver(), new EntityParameterResolver());
    private static final Handler<DeserializingMessage> selectionHandler = HandlerInspector.createHandler(
            new ShapeHandler(), HandleCommand.class, parameterResolvers);
    private static final Handler<DeserializingMessage> singleSelectionHandler = HandlerInspector.createHandler(
            new SingleShapeHandler(), HandleCommand.class, parameterResolvers);
    private static final Handler<DeserializingMessage> interceptedSelectionHandler =
            wrapMonitors(selectionHandler);
    private static final Handler<DeserializingMessage> interceptedStableHandler = wrapMonitors(
            HandlerInspector.createHandler(new StableHandler(), HandleCommand.class, List.of()));
    private static final ThreadMXBean allocationBean = allocationBean();
    private static volatile long blackhole;

    public static void main(String[] args) {
        if (Integer.bitCount(keyCount) != 1 || keyCount > 128) {
            throw new IllegalArgumentException("keys must be a power of two up to 128");
        }
        System.out.printf("config keys=%d cacheTarget=>100 iterations=%d warmups=%d interceptors=%d%n",
                          keyCount, iterations, warmups, interceptorCount);
        for (int i = 0; i < warmups; i++) {
            runSelection(singleSelectionHandler, warmupIterations);
            runSelection(selectionHandler, warmupIterations);
            runStableInterceptors(warmupIterations);
            runSelection(interceptedSelectionHandler, warmupIterations);
        }
        measure("selection-single-128-keys", () -> runSelection(singleSelectionHandler, iterations));
        measure("selection-128-keys", () -> runSelection(selectionHandler, iterations));
        measure("interceptors-5-stable", () -> runStableInterceptors(iterations));
        measure("combined-128-keys-5-interceptors",
                () -> runSelection(interceptedSelectionHandler, iterations));
        System.out.println("blackhole=" + blackhole);
    }

    private static void runSelection(Handler<DeserializingMessage> handler, int count) {
        long result = 0L;
        int mask = keyCount - 1;
        for (int i = 0; i < count; i++) {
            DeserializingMessage message = shapeMessages[(i * 73) & mask];
            HandlerInvoker invoker = handler.getInvokerOrNull(message);
            result += (Integer) invoker.invoke();
        }
        blackhole = result;
    }

    private static void runStableInterceptors(int count) {
        long result = 0L;
        for (int i = 0; i < count; i++) {
            result += (Integer) interceptedStableHandler.getInvokerOrNull(stableMessage).invoke();
        }
        blackhole = result;
    }

    private static void measure(String name, Runnable scenario) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        scenario.run();
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%s: %.2f ns/op, %.2f bytes/op, %.1f M ops/s%n", name,
                          (double) elapsed / iterations, (double) allocated / iterations,
                          iterations * 1_000d / elapsed);
    }

    private static Handler<DeserializingMessage> wrapMonitors(Handler<DeserializingMessage> handler) {
        Handler<DeserializingMessage> result = handler;
        for (int i = 0; i < interceptorCount; i++) {
            result = new HandlerMonitor().wrap(result);
        }
        return result;
    }

    private static DeserializingMessage[] createShapeMessages() {
        if (keyCount > 128) {
            throw new IllegalArgumentException("At most 128 proxy shapes are supported");
        }
        Class<?>[] bits = {Shape0.class, Shape1.class, Shape2.class, Shape3.class,
                           Shape4.class, Shape5.class, Shape6.class};
        DeserializingMessage[] result = new DeserializingMessage[keyCount];
        for (int key = 0; key < keyCount; key++) {
            int shapeKey = key;
            Class<?>[] interfaces = new Class<?>[1 + Integer.bitCount(key)];
            interfaces[0] = ShapePayload.class;
            int next = 1;
            for (int bit = 0; bit < bits.length; bit++) {
                if ((key & 1 << bit) != 0) {
                    interfaces[next++] = bits[bit];
                }
            }
            Object payload = Proxy.newProxyInstance(
                    HandlerResolutionCacheBenchmark.class.getClassLoader(), interfaces,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "shape-" + shapeKey;
                        default -> null;
                    });
            result[key] = new DeserializingMessage(new Message(payload), MessageType.COMMAND, null);
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

    @LocalHandler
    private static class SingleShapeHandler {
        @HandleCommand
        int handle(ShapePayload payload) {
            return System.identityHashCode(payload);
        }
    }

    @LocalHandler
    private static class ShapeHandler {
        @HandleCommand
        int handle(String ignored) {
            throw new AssertionError();
        }

        @HandleCommand
        int handle(Integer ignored) {
            throw new AssertionError();
        }

        @HandleCommand
        int handle(Long ignored) {
            throw new AssertionError();
        }

        @HandleCommand
        int handle(Instant ignored) {
            throw new AssertionError();
        }

        @HandleCommand
        int handle(UUID ignored) {
            throw new AssertionError();
        }

        @HandleCommand
        int handle(ShapePayload payload) {
            return System.identityHashCode(payload);
        }
    }

    @LocalHandler
    private static class StableHandler {
        private int invocation;

        @HandleCommand
        int handle() {
            return ++invocation;
        }
    }

    public interface ShapePayload {
    }

    public interface Shape0 {
    }

    public interface Shape1 {
    }

    public interface Shape2 {
    }

    public interface Shape3 {
    }

    public interface Shape4 {
    }

    public interface Shape5 {
    }

    public interface Shape6 {
    }
}
