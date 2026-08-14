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
 * <p>The transport owns framing and supplies an assembly key. Decode capacity is released when the decoded work is
 * safely admitted by its functional dispatcher, while retained message and compressed-byte capacity is released only
 * after functional completion. Capacity notifications are advisory and may be coalesced; a transport adapter must
 * re-check admission and make its resume operation idempotent.</p>
 */
final class RuntimeIngressController<C> {
    private static final int MAX_SYNCHRONOUS_MESSAGES_PER_TASK = 32;
    private final Executor executor;
    private final int maxConcurrency;
    private final int maxRetainedMessages;
    private final long maxRetainedBytes;
    private final MessageHandler<C> messageHandler;
    private final Consumer<Throwable> failureHandler;
    private final Runnable capacityAvailableHandler;
    private final ProgressHandler progressHandler;
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
    private int admittedMessages;
    private long admittedBytes;
    private long progressSequence;
    private boolean accepting = true;
    private boolean capacityNotificationPending;
    private boolean discardPending;
    private boolean stopping;
    private Runnable terminalCallback;

    RuntimeIngressController(Executor executor, int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes,
                             MessageHandler<C> messageHandler, Consumer<Throwable> failureHandler,
                             Runnable capacityAvailableHandler, ProgressHandler progressHandler) {
        this(executor, maxConcurrency, maxRetainedMessages, maxRetainedBytes, messageHandler, failureHandler,
             capacityAvailableHandler, progressHandler, true);
    }

    RuntimeIngressController(Executor executor, int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes,
                             MessageHandler<C> messageHandler, Consumer<Throwable> failureHandler,
                             Runnable capacityAvailableHandler, ProgressHandler progressHandler,
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
        int progressRetainedMessages;
        long currentProgressSequence;
        synchronized (this) {
            if (!accepting) {
                return Admission.CLOSED;
            }
            if (assemblies.containsKey(assemblyKey)) {
                throw new IllegalStateException("A runtime message is already being assembled for " + assemblyKey);
            }
            if (!hasCapacity(firstFrameBytes)) {
                capacityNotificationPending = true;
                return Admission.BACKPRESSURED;
            }
            boolean firstRetainedMessage = retainedMessages == 0;
            assemblies.put(assemblyKey, new Assembly(firstFrameBytes));
            retainedMessages++;
            retainedBytes += firstFrameBytes;
            progress = firstRetainedMessage && progressHandler != null ? Progress.RETAINED_WORK_STARTED : null;
            progressRetainedMessages = retainedMessages;
            currentProgressSequence = progress == null ? 0L : ++progressSequence;
        }
        if (progress != null) {
            progressHandler.accept(progress, progressRetainedMessages, currentProgressSequence);
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
            capacityNotificationPending = true;
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
        boolean result = accepting && retainedMessages < maxRetainedMessages
                         && (retainedMessages == 0 || retainedBytes < maxRetainedBytes);
        if (accepting && !result) {
            capacityNotificationPending = true;
        }
        return result;
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

    private RuntimeMessage<C> process(RuntimeTask task, RuntimeMessage<C> message, boolean allowWorkerReuse) {
        if (!markActive(message)) {
            return discardBeforeAdmission(task, message, false, null, allowWorkerReuse);
        }
        CompletableFuture<Void> admission;
        CompletableFuture<Void> functionalCompletion;
        try {
            long startedNanos = captureDispatchTiming ? System.nanoTime() : 0L;
            DispatchTiming dispatchTiming = captureDispatchTiming
                    ? new DispatchTiming(
                            message.queuedTimestamp, System.currentTimeMillis(),
                            NANOSECONDS.toMillis(Math.max(0L, startedNanos - message.queuedNanos))) : null;
            MessageDispatch dispatch = Objects.requireNonNull(
                    messageHandler.handle(message.bytes, message.messageContext, dispatchTiming),
                    "Runtime message dispatch");
            admission = Objects.requireNonNull(dispatch.admission(), "Runtime message admission future");
            functionalCompletion = Objects.requireNonNull(
                    dispatch.completion(), "Runtime message completion future");
        } catch (Throwable e) {
            return discardBeforeAdmission(task, message, true, unwrap(e), allowWorkerReuse);
        }
        if (admission.isDone()) {
            Throwable failure = completionFailure(admission);
            return failure == null
                    ? admit(task, message, functionalCompletion, allowWorkerReuse)
                    : discardBeforeAdmission(task, message, true, failure, allowWorkerReuse);
        }
        admission.whenComplete((ignored, failure) -> {
            Throwable unwrapped = unwrap(failure);
            if (unwrapped == null) {
                admit(task, message, functionalCompletion, false);
            } else {
                discardBeforeAdmission(task, message, true, unwrapped, false);
            }
        });
        return null;
    }

    private static Throwable completionFailure(CompletableFuture<Void> completion) {
        try {
            completion.join();
            return null;
        } catch (Throwable e) {
            return unwrap(e);
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

    private RuntimeMessage<C> admit(RuntimeTask task, RuntimeMessage<C> message,
                                    CompletableFuture<Void> functionalCompletion, boolean reuseWorker) {
        RuntimeMessage<C> nextMessage;
        synchronized (this) {
            inFlightMessages--;
            inFlightBytes -= message.bytes.length;
            activeMessages--;
            activeBytes -= message.bytes.length;
            admittedMessages++;
            admittedBytes += message.bytes.length;
            nextMessage = releaseTask(task, reuseWorker);
        }
        if (functionalCompletion.isDone()) {
            completeFunctional(message, completionFailure(functionalCompletion));
        } else {
            functionalCompletion.whenComplete(
                    (ignored, failure) -> completeFunctional(message, unwrap(failure)));
        }
        scheduleAvailable();
        return nextMessage;
    }

    private RuntimeMessage<C> discardBeforeAdmission(
            RuntimeTask task, RuntimeMessage<C> message, boolean active, Throwable failure, boolean reuseWorker) {
        RuntimeMessage<C> nextMessage;
        synchronized (this) {
            inFlightMessages--;
            inFlightBytes -= message.bytes.length;
            if (active) {
                activeMessages--;
                activeBytes -= message.bytes.length;
            }
            retainedMessages--;
            retainedBytes -= message.bytes.length;
            if (failure != null) {
                accepting = false;
                stopping = true;
            }
            nextMessage = releaseTask(task, failure == null && reuseWorker);
        }
        if (failure != null) {
            failureHandler.accept(failure);
        } else {
            scheduleAvailable();
        }
        return nextMessage;
    }

    private RuntimeMessage<C> releaseTask(RuntimeTask task, boolean reuseWorker) {
        RuntimeMessage<C> nextMessage = reuseWorker && !discardPending && !stopping
                ? pendingMessages.pollFirst() : null;
        if (nextMessage == null) {
            task.message = null;
            availableTasks.addLast(task);
        } else {
            inFlightMessages++;
            inFlightBytes += nextMessage.bytes.length;
            task.message = nextMessage;
        }
        return nextMessage;
    }

    private void completeFunctional(RuntimeMessage<C> message, Throwable failure) {
        Runnable terminal;
        boolean capacityAvailable;
        Progress progress;
        int progressRetainedMessages;
        long currentProgressSequence;
        synchronized (this) {
            boolean publishProgress = !discardPending;
            admittedMessages--;
            admittedBytes -= message.bytes.length;
            retainedMessages--;
            retainedBytes -= message.bytes.length;
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
            capacityAvailable = failure == null && accepting && !discardPending && capacityNotificationPending;
            if (capacityAvailable) {
                capacityNotificationPending = false;
            }
            progress = failure == null && publishProgress && progressHandler != null
                    ? Progress.FUNCTIONAL_MESSAGE_COMPLETED : null;
            progressRetainedMessages = retainedMessages;
            currentProgressSequence = progress == null ? 0L : ++progressSequence;
        }
        if (failure != null) {
            failureHandler.accept(failure);
            return;
        }
        if (progress != null) {
            progressHandler.accept(progress, progressRetainedMessages, currentProgressSequence);
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
                admittedMessages, admittedBytes, pendingMessageCount,
                retainedBytes - inFlightBytes - admittedBytes, maxConcurrency,
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

    enum Progress {
        RETAINED_WORK_STARTED, FUNCTIONAL_MESSAGE_COMPLETED
    }

    record State(int retainedMessages, long retainedBytes, int inFlightMessages, long inFlightBytes,
                 int activeMessages, long activeBytes, int admittedMessages, long admittedBytes,
                 int pendingMessages, long pendingBytes, int maxConcurrency, int maxRetainedMessages,
                 long maxRetainedBytes) {
    }

    interface MessageDispatch {
        CompletableFuture<Void> admission();

        CompletableFuture<Void> completion();

        static MessageDispatch of(CompletionStage<Void> admission, CompletionStage<Void> completion) {
            return new DefaultMessageDispatch(admission.toCompletableFuture(), completion.toCompletableFuture());
        }

        static MessageDispatch admitted(CompletionStage<Void> completion) {
            return new DefaultMessageDispatch(
                    CompletableFuture.completedFuture(null), completion.toCompletableFuture());
        }

        static MessageDispatch failed(Throwable failure) {
            CompletableFuture<Void> failed = CompletableFuture.failedFuture(failure);
            return new DefaultMessageDispatch(failed, failed);
        }
    }

    private record DefaultMessageDispatch(
            CompletableFuture<Void> admission, CompletableFuture<Void> completion) implements MessageDispatch {
        private DefaultMessageDispatch {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(completion, "completion");
        }
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
        MessageDispatch handle(byte[] bytes, C messageContext, DispatchTiming dispatchTiming);
    }

    @FunctionalInterface
    interface ProgressHandler {
        void accept(Progress progress, int retainedMessages, long sequence);
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
            for (int processed = 1; currentMessage != null; processed++) {
                currentMessage = process(
                        this, currentMessage, processed < MAX_SYNCHRONOUS_MESSAGES_PER_TASK);
            }
        }
    }
}
