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
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.handling.errorreporting.ErrorReportingInterceptor;
import io.fluxzero.sdk.tracking.metrics.HandlerMonitor;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cross-version benchmark for callbacks attached to incomplete asynchronous handler results.
 *
 * <p>Every operation invokes a handler inside the regular message and invocation contexts. The handler returns a new,
 * incomplete future, after which those contexts are left and the future is completed on the benchmark thread. The raw
 * scenario measures the unavoidable future and invocation costs. The monitored scenario additionally exercises the
 * completion callbacks installed by handler metrics and error reporting.</p>
 */
public class AsyncHandlerContextBenchmark {
    private static final int iterations = Integer.getInteger("iterations", 500_000);
    private static final int warmupIterations = Integer.getInteger("warmupIterations", 100_000);
    private static final int warmups = Integer.getInteger("warmups", 4);

    private static final DeserializingMessage message = new DeserializingMessage(
            new Message("payload"), MessageType.COMMAND, new JacksonSerializer());
    private static final PendingHandler target = new PendingHandler();
    private static final Handler<DeserializingMessage> rawHandler = HandlerInspector.createHandler(
            target, HandleCommand.class, List.of());
    private static final Handler<DeserializingMessage> monitoredHandler = wrap(rawHandler);
    private static final HandlerInvoker rawInvoker = rawHandler.getInvokerOrNull(message);
    private static final HandlerInvoker monitoredInvoker = monitoredHandler.getInvokerOrNull(message);
    private static final Callable<Object> rawInvocation = rawInvoker::invoke;
    private static final Callable<Object> monitoredInvocation = monitoredInvoker::invoke;
    private static final ThreadMXBean allocationBean = allocationBean();
    private static volatile long blackhole;

    public static void main(String[] args) {
        System.out.printf("config iterations=%d warmups=%d%n", iterations, warmups);
        for (int i = 0; i < warmups; i++) {
            runRaw(warmupIterations);
            runMonitored(warmupIterations);
        }
        measure("async-handler-raw", AsyncHandlerContextBenchmark::runRaw);
        measure("async-handler-context-callbacks", AsyncHandlerContextBenchmark::runMonitored);
        System.out.println("blackhole=" + blackhole);
    }

    private static void runRaw(int count) {
        run(count, rawInvoker, rawInvocation);
    }

    private static void runMonitored(int count) {
        run(count, monitoredInvoker, monitoredInvocation);
    }

    private static void run(int count, HandlerInvoker invoker, Callable<Object> invocation) {
        long result = 0L;
        for (int i = 0; i < count; i++) {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            target.next = future;
            Object handlerResult = message.apply(
                    ignored -> Invocation.performInvocation(invoker, invocation));
            future.complete(1);
            result += (Integer) ((CompletionStage<?>) handlerResult).toCompletableFuture().join();
        }
        blackhole = result;
    }

    private static void measure(String name, java.util.function.IntConsumer scenario) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        scenario.accept(iterations);
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null ? 0L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        System.out.printf("%s: %.2f ns/op, %.2f bytes/op, %.1f M ops/s%n", name,
                          (double) elapsed / iterations, (double) allocated / iterations,
                          iterations * 1_000d / elapsed);
    }

    private static Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        Handler<DeserializingMessage> result = new HandlerMonitor().wrap(handler);
        return new ErrorReportingInterceptor(null).wrap(result);
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

    private static class PendingHandler {
        private CompletableFuture<Integer> next;

        @HandleCommand
        CompletionStage<Integer> handle() {
            return next;
        }
    }
}
