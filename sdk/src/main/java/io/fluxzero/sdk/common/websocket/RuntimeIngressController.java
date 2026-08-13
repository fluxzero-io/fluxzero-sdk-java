/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common.websocket;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Transport-neutral admission, retained-resource accounting and bounded runtime-message dispatch.
 *
 * <p>The transport owns framing and supplies an assembly key. A retained message is released only when the completion
 * stage returned by the functional handler finishes. Capacity notifications are advisory and may be coalesced; a
 * transport adapter must re-check admission and make its own resume operation idempotent.</p>
 */
final class RuntimeIngressController<C> {
    private final Executor executor;
    private final int maxConcurrency;
    private final int maxRetainedMessages;
    private final long maxRetainedBytes;
    private final MessageHandler<C> messageHandler;
    private final Consumer<Throwable> failureHandler;
    private final Runnable capacityAvailableHandler;
    private final Consumer<Progress> progressHandler;
    private final boolean captureDispatchTiming;
    private final ArrayDeque<RuntimeMessage<C>> pendingMessages = new ArrayDeque<>();
    private final ArrayDeque<RuntimeTask> availableTasks = new ArrayDeque<>();
    private final Map<Object, Assembly> assemblies = new HashMap<>();
    private int createdTaskCount;
    private long retainedBytes;
    private int retainedMessages;
    private int inFlightMessages;
    private long inFlightBytes;
    private int activeMessages;
    private long activeBytes;
    private boolean accepting = true;
    private boolean discardPending;
    private boolean stopping;
    private Runnable terminalCallback;

    RuntimeIngressController(Executor executor, int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes,
                             MessageHandler<C> messageHandler, Consumer<Throwable> failureHandler,
                             Runnable capacityAvailableHandler, Consumer<Progress> progressHandler) {
        this(executor, maxConcurrency, maxRetainedMessages, maxRetainedBytes, messageHandler, failureHandler,
             capacityAvailableHandler, progressHandler, true);
    }

    RuntimeIngressController(Executor executor, int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes,
                             MessageHandler<C> messageHandler, Consumer<Throwable> failureHandler,
                             Runnable capacityAvailableHandler, Consumer<Progress> progressHandler,
                             boolean captureDispatchTiming) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Runtime message concurrency must be at least 1");
        }
        if (maxRetainedMessages < maxConcurrency) {
            throw new IllegalArgumentException(
                    "Retained runtime messages must be at least runtime message concurrency");
        }
        if (maxRetainedBytes < 1) {
            throw new IllegalArgumentException("Retained runtime bytes must be positive");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        this.maxConcurrency = maxConcurrency;
        this.maxRetainedMessages = maxRetainedMessages;
        this.maxRetainedBytes = maxRetainedBytes;
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.capacityAvailableHandler = Objects.requireNonNull(
                capacityAvailableHandler, "capacityAvailableHandler");
        this.progressHandler = progressHandler;
        this.captureDispatchTiming = captureDispatchTiming;
    }

    Admission beginMessage(Object assemblyKey, int firstFrameBytes) {
        if (firstFrameBytes < 0) {
            throw new IllegalArgumentException("Runtime message frame bytes must not be negative");
        }
        Progress progress;
        synchronized (this) {
            if (!accepting) {
                return Admission.CLOSED;
            }
            if (assemblies.containsKey(assemblyKey)) {
                throw new IllegalStateException("A runtime message is already being assembled for " + assemblyKey);
            }
            if (!hasCapacity(firstFrameBytes)) {
                return Admission.BACKPRESSURED;
            }
            boolean firstRetainedMessage = retainedMessages == 0;
            assemblies.put(assemblyKey, new Assembly(firstFrameBytes));
            retainedMessages++;
            retainedBytes += firstFrameBytes;
            progress = firstRetainedMessage && progressHandler != null
                    ? new Progress(Progress.Type.RETAINED_WORK_STARTED, state()) : null;
        }
        if (progress != null) {
            progressHandler.accept(progress);
        }
        return Admission.ACCEPTED;
    }

    synchronized Admission retainMessageFragmentBytes(Object assemblyKey, int nextFragmentBytes) {
        if (nextFragmentBytes < 0) {
            throw new IllegalArgumentException("Runtime message frame bytes must not be negative");
        }
        if (!accepting) {
            return Admission.CLOSED;
        }
        Assembly assembly = assemblies.get(assemblyKey);
        if (assembly == null) {
            throw new IllegalStateException("No runtime message is being assembled for " + assemblyKey);
        }
        if (retainedMessages > 1 && exceedsByteCapacity(nextFragmentBytes)) {
            return Admission.BACKPRESSURED;
        }
        try {
            assembly.bytes = Math.addExact(assembly.bytes, nextFragmentBytes);
            retainedBytes = Math.addExact(retainedBytes, nextFragmentBytes);
        } catch (ArithmeticException e) {
            return Admission.OVERFLOW;
        }
        return Admission.ACCEPTED;
    }

    Admission dispatchAssembledMessage(Object assemblyKey, byte[] bytes, C messageContext) {
        RuntimeMessage<C> message = new RuntimeMessage<>(
                bytes, messageContext, captureDispatchTiming ? System.currentTimeMillis() : 0L,
                captureDispatchTiming ? System.nanoTime() : 0L);
        synchronized (this) {
            if (!accepting) {
                return Admission.CLOSED;
            }
            Assembly assembly = assemblies.get(assemblyKey);
            if (assembly == null) {
                throw new IllegalStateException("No retained runtime assembly for " + assemblyKey);
            }
            if (assembly.bytes != bytes.length) {
                throw new IllegalStateException("Retained bytes do not match the assembled runtime message");
            }
            assemblies.remove(assemblyKey);
            pendingMessages.addLast(message);
        }
        scheduleAvailable();
        return Admission.ACCEPTED;
    }

    synchronized boolean canBeginMessage() {
        return accepting && retainedMessages < maxRetainedMessages
               && (retainedMessages == 0 || retainedBytes < maxRetainedBytes);
    }

    private boolean hasCapacity(int nextMessageBytes) {
        return retainedMessages < maxRetainedMessages
               && (retainedMessages == 0 || !exceedsByteCapacity(nextMessageBytes));
    }

    private boolean exceedsByteCapacity(int additionalBytes) {
        return additionalBytes > maxRetainedBytes || retainedBytes > maxRetainedBytes - additionalBytes;
    }

    private void scheduleAvailable() {
        RuntimeMessage<C> message;
        RuntimeTask task;
        synchronized (this) {
            if (discardPending || stopping || inFlightMessages >= maxConcurrency) {
                return;
            }
            message = pendingMessages.pollFirst();
            if (message == null) {
                return;
            }
            inFlightMessages++;
            inFlightBytes += message.bytes.length;
            task = availableTasks.pollFirst();
            if (task == null) {
                if (createdTaskCount >= maxConcurrency) {
                    throw new IllegalStateException("Missing reusable runtime dispatch task");
                }
                task = new RuntimeTask();
                createdTaskCount++;
            }
            task.message = message;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            State rejectedState = discardRejected(task, message);
            failureHandler.accept(IngressException.executorRejected(rejectedState, e));
        }
    }

    private void process(RuntimeTask task, RuntimeMessage<C> message) {
        if (!markActive(message)) {
            complete(task, message, false, null);
            return;
        }
        CompletableFuture<Void> future;
        try {
            long startedNanos = captureDispatchTiming ? System.nanoTime() : 0L;
            DispatchTiming dispatchTiming = captureDispatchTiming
                    ? new DispatchTiming(
                            message.queuedTimestamp, System.currentTimeMillis(),
                            NANOSECONDS.toMillis(Math.max(0L, startedNanos - message.queuedNanos))) : null;
            CompletionStage<Void> completion = Objects.requireNonNull(
                    messageHandler.handle(message.bytes, message.messageContext, dispatchTiming),
                    "Runtime message completion");
            future = Objects.requireNonNull(
                    completion.toCompletableFuture(), "Runtime message completion future");
        } catch (Throwable e) {
            complete(task, message, true, unwrap(e));
            return;
        }
        if (future.isDone()) {
            Throwable failure = null;
            try {
                future.join();
            } catch (Throwable e) {
                failure = unwrap(e);
            }
            complete(task, message, true, failure);
        } else {
            future.whenComplete((ignored, failure) -> complete(task, message, true, unwrap(failure)));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private synchronized boolean markActive(RuntimeMessage<C> message) {
        if (discardPending || stopping) {
            return false;
        }
        activeMessages++;
        activeBytes += message.bytes.length;
        return true;
    }

    private void complete(RuntimeTask task, RuntimeMessage<C> message, boolean active, Throwable failure) {
        Runnable terminal;
        boolean scheduleMore;
        boolean capacityAvailable;
        Progress progress;
        synchronized (this) {
            boolean publishProgress = !discardPending;
            inFlightMessages--;
            inFlightBytes -= message.bytes.length;
            if (active) {
                activeMessages--;
                activeBytes -= message.bytes.length;
            }
            retainedMessages--;
            retainedBytes -= message.bytes.length;
            task.message = null;
            availableTasks.addLast(task);
            if (failure != null) {
                accepting = false;
                stopping = true;
            }
            if (failure == null && !stopping && retainedMessages == 0 && terminalCallback != null) {
                terminal = terminalCallback;
                terminalCallback = null;
                discardPending = true;
            } else {
                terminal = null;
            }
            scheduleMore = failure == null && !discardPending && !pendingMessages.isEmpty();
            capacityAvailable = failure == null && accepting && !discardPending;
            progress = failure == null && publishProgress && progressHandler != null
                    ? new Progress(Progress.Type.FUNCTIONAL_MESSAGE_COMPLETED, state()) : null;
        }
        if (failure != null) {
            failureHandler.accept(failure);
            return;
        }
        if (progress != null) {
            progressHandler.accept(progress);
        }
        if (scheduleMore) {
            scheduleAvailable();
        }
        if (capacityAvailable) {
            capacityAvailableHandler.run();
        }
        if (terminal != null) {
            terminal.run();
        }
    }

    private synchronized State discardRejected(RuntimeTask task, RuntimeMessage<C> rejectedMessage) {
        State rejectedState = state();
        accepting = false;
        stopping = true;
        inFlightMessages--;
        inFlightBytes -= rejectedMessage.bytes.length;
        retainedMessages--;
        retainedBytes -= rejectedMessage.bytes.length;
        task.message = null;
        availableTasks.addLast(task);
        return rejectedState;
    }

    synchronized Runnable close() {
        if (discardPending) {
            return null;
        }
        accepting = false;
        discardPending = true;
        Runnable deferredClose = terminalCallback;
        terminalCallback = null;
        assemblies.values().forEach(assembly -> {
            retainedMessages--;
            retainedBytes -= assembly.bytes;
        });
        assemblies.clear();
        RuntimeMessage<C> message;
        while ((message = pendingMessages.pollFirst()) != null) {
            retainedMessages--;
            retainedBytes -= message.bytes.length;
        }
        return deferredClose;
    }

    synchronized void discardAssembly(Object assemblyKey) {
        Assembly assembly = assemblies.remove(assemblyKey);
        if (assembly != null) {
            retainedMessages--;
            retainedBytes -= assembly.bytes;
        }
    }

    synchronized boolean hasAssembly(Object assemblyKey) {
        return assemblies.containsKey(assemblyKey);
    }

    synchronized State state() {
        int pendingMessageCount = pendingMessages.size() + assemblies.size();
        return new State(
                retainedMessages, retainedBytes, inFlightMessages, inFlightBytes, activeMessages, activeBytes,
                pendingMessageCount, retainedBytes - inFlightBytes, maxConcurrency,
                maxRetainedMessages, maxRetainedBytes);
    }

    void closeAfterDrain(Runnable closeCallback) {
        boolean runNow;
        synchronized (this) {
            if (stopping && !discardPending) {
                terminalCallback = closeCallback;
                runNow = false;
            } else if (!accepting) {
                runNow = true;
            } else {
                accepting = false;
                runNow = retainedMessages == 0;
                if (runNow) {
                    discardPending = true;
                } else {
                    terminalCallback = closeCallback;
                }
            }
        }
        if (runNow) {
            closeCallback.run();
        }
    }

    enum Admission {
        ACCEPTED, BACKPRESSURED, CLOSED, OVERFLOW
    }

    record Progress(Type type, State state) {
        enum Type {
            RETAINED_WORK_STARTED, FUNCTIONAL_MESSAGE_COMPLETED
        }
    }

    record State(int retainedMessages, long retainedBytes, int inFlightMessages, long inFlightBytes,
                 int activeMessages, long activeBytes, int pendingMessages, long pendingBytes,
                 int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes) {
    }

    static final class IngressException extends RejectedExecutionException {
        enum Reason {
            OVERFLOW, EXECUTOR_REJECTED
        }

        private final Reason reason;
        private final State state;

        private IngressException(Reason reason, State state, Throwable cause) {
            super(message(reason, state), cause);
            this.reason = reason;
            this.state = state;
        }

        static IngressException overflow(State state) {
            return new IngressException(Reason.OVERFLOW, state, null);
        }

        static IngressException executorRejected(State state, Throwable cause) {
            return new IngressException(Reason.EXECUTOR_REJECTED, state, cause);
        }

        Reason reason() {
            return reason;
        }

        State state() {
            return state;
        }

        private static String message(Reason reason, State state) {
            return "SDK runtime ingress %s: retained=%d/%d messages, retainedBytes=%d/%d, active=%d, pending=%d"
                    .formatted(reason.name().toLowerCase(), state.retainedMessages, state.maxRetainedMessages,
                               state.retainedBytes, state.maxRetainedBytes, state.activeMessages,
                               state.pendingMessages);
        }
    }

    @FunctionalInterface
    interface MessageHandler<C> {
        CompletionStage<Void> handle(byte[] bytes, C messageContext, DispatchTiming dispatchTiming);
    }

    record DispatchTiming(long queuedTimestamp, long startedTimestamp, long queueDurationMillis) {
    }

    private static final class Assembly {
        private long bytes;

        private Assembly(long bytes) {
            this.bytes = bytes;
        }
    }

    private record RuntimeMessage<C>(byte[] bytes, C messageContext, long queuedTimestamp, long queuedNanos) {
    }

    private final class RuntimeTask implements Runnable {
        private RuntimeMessage<C> message;

        @Override
        public void run() {
            RuntimeMessage<C> currentMessage = message;
            if (currentMessage == null) {
                throw new IllegalStateException("Runtime dispatch task has no message");
            }
            process(this, currentMessage);
        }
    }
}
