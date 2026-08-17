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

package io.fluxzero.sdk.common.websocket;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeIngressControllerTest {

    @Test
    void retainsMessageUntilFunctionalCompletionWithoutSpuriousCapacityNotification() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> functionalCompletion = new CompletableFuture<>();
        AtomicInteger capacityNotifications = new AtomicInteger();
        List<RuntimeIngressController.Progress> progressEvents = new ArrayList<>();
        List<Long> progressSequences = new ArrayList<>();
        RuntimeIngressController<Object> controller = new RuntimeIngressController<>(
                executor, 1, 2, 16, (bytes, receiveTiming, dispatchTiming) ->
                        RuntimeIngressController.MessageDispatch.admitted(functionalCompletion),
                failure -> {}, capacityNotifications::incrementAndGet,
                (progress, retainedMessages, sequence) -> {
                    progressEvents.add(progress);
                    progressSequences.add(sequence);
                });

        assertEquals(RuntimeIngressController.Admission.ACCEPTED, controller.beginMessage("stream", 4));
        assertEquals(RuntimeIngressController.Admission.ACCEPTED,
                     controller.dispatchAssembledMessage("stream", new byte[4], null));
        executor.runNext();

        RuntimeIngressController.State active = controller.state();
        assertEquals(1, active.retainedMessages());
        assertEquals(0, active.inFlightMessages());
        assertEquals(0, active.activeMessages());
        assertEquals(1, active.admittedMessages());
        assertEquals(0, capacityNotifications.get());

        functionalCompletion.complete(null);

        assertEquals(0, controller.state().retainedMessages());
        assertEquals(0, capacityNotifications.get());
        assertEquals(List.of(RuntimeIngressController.Progress.RETAINED_WORK_STARTED,
                             RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED), progressEvents);
        assertEquals(List.of(1L, 2L), progressSequences);
    }

    @Test
    void functionalCompletionDoesNotRetainDecodeCapacityAfterDispatch() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> firstFunctionalCompletion = new CompletableFuture<>();
        AtomicInteger handled = new AtomicInteger();
        RuntimeIngressController<Object> controller = controller(
                executor, 1, 3, 16,
                (bytes, receiveTiming, dispatchTiming) -> handled.getAndIncrement() == 0
                        ? firstFunctionalCompletion : CompletableFuture.completedFuture(null),
                failure -> {}, () -> {});
        controller.beginMessage("first", 2);
        controller.dispatchAssembledMessage("first", new byte[2], null);
        controller.beginMessage("second", 2);
        controller.dispatchAssembledMessage("second", new byte[2], null);

        executor.runNext();

        assertEquals(2, handled.get(),
                     "A functionally incomplete message must not keep the sole decode permit");
        assertEquals(1, controller.state().retainedMessages(),
                     "Functional work must remain retained after its decode permit is released");

        firstFunctionalCompletion.complete(null);

        assertEquals(0, controller.state().retainedMessages());
    }

    @Test
    void pendingAdmissionsHoldExactlyTheDecodeConcurrencyBound() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        List<CompletableFuture<Void>> admissions = List.of(
                new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        AtomicInteger handled = new AtomicInteger();
        RuntimeIngressController<Object> controller = new RuntimeIngressController<>(
                executor, 3, 5, 32, (bytes, context, timing) -> {
            int index = handled.getAndIncrement();
            return index < admissions.size()
                    ? RuntimeIngressController.MessageDispatch.of(
                            admissions.get(index), CompletableFuture.completedFuture(null))
                    : RuntimeIngressController.MessageDispatch.admitted(CompletableFuture.completedFuture(null));
        }, failure -> {}, () -> {}, (progress, retainedMessages, sequence) -> {});
        for (int i = 0; i < 4; i++) {
            controller.beginMessage("stream-" + i, 2);
            controller.dispatchAssembledMessage("stream-" + i, new byte[2], null);
        }

        executor.runAll();

        assertEquals(3, handled.get());
        assertEquals(3, controller.state().inFlightMessages());
        assertEquals(3, controller.state().activeMessages());
        assertEquals(1, controller.state().pendingMessages());

        admissions.getFirst().complete(null);

        assertEquals(3, handled.get(), "Admission completion must only schedule decode on its executor");
        assertEquals(1, executor.pendingTaskCount());
        executor.runAll();

        assertEquals(4, handled.get());
        assertEquals(2, controller.state().inFlightMessages());
        assertEquals(0, controller.state().pendingMessages());

        admissions.get(1).complete(null);
        admissions.get(2).complete(null);
        assertEquals(0, controller.state().retainedMessages());
    }

    @Test
    void admissionFailureReleasesAccountingOnceAndStopsIngress() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> admission = new CompletableFuture<>();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        RuntimeIngressController<Object> controller = new RuntimeIngressController<>(
                executor, 1, 2, 16,
                (bytes, context, timing) -> RuntimeIngressController.MessageDispatch.of(
                        admission, CompletableFuture.completedFuture(null)),
                reportedFailure::set, () -> {}, (progress, retainedMessages, sequence) -> {});
        controller.beginMessage("stream", 4);
        controller.dispatchAssembledMessage("stream", new byte[4], null);
        executor.runAll();
        IllegalStateException failure = new IllegalStateException("admission failed");

        admission.completeExceptionally(failure);

        assertSame(failure, reportedFailure.get());
        assertEquals(0, controller.state().retainedMessages());
        assertEquals(0L, controller.state().retainedBytes());
        assertEquals(0, controller.state().inFlightMessages());
        assertFalse(controller.canBeginMessage());
    }

    @Test
    void functionalFailureReleasesAdmittedAccountingAndStopsIngress() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> functionalCompletion = new CompletableFuture<>();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        RuntimeIngressController<Object> controller = controller(
                executor, 1, 2, 16, (bytes, context, timing) -> functionalCompletion,
                reportedFailure::set, () -> {});
        controller.beginMessage("stream", 4);
        controller.dispatchAssembledMessage("stream", new byte[4], null);
        executor.runAll();

        assertEquals(1, controller.state().admittedMessages());
        IllegalStateException failure = new IllegalStateException("functional completion failed");

        functionalCompletion.completeExceptionally(failure);

        assertSame(failure, reportedFailure.get());
        assertEquals(0, controller.state().retainedMessages());
        assertEquals(0L, controller.state().retainedBytes());
        assertEquals(0, controller.state().admittedMessages());
        assertFalse(controller.canBeginMessage());
    }

    @Test
    void coalescesCapacityNotificationAfterAdmissionActuallyWaited() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> firstCompletion = new CompletableFuture<>();
        CompletableFuture<Void> secondAdmission = new CompletableFuture<>();
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger capacityNotifications = new AtomicInteger();
        RuntimeIngressController<Object> controller = new RuntimeIngressController<>(
                executor, 1, 2, 16, (bytes, receiveTiming, dispatchTiming) -> {
            if (handled.getAndIncrement() == 0) {
                return RuntimeIngressController.MessageDispatch.admitted(firstCompletion);
            }
            return RuntimeIngressController.MessageDispatch.of(
                    secondAdmission, CompletableFuture.completedFuture(null));
        }, failure -> {}, capacityNotifications::incrementAndGet,
                (progress, retainedMessages, sequence) -> {});
        controller.beginMessage("first", 2);
        controller.dispatchAssembledMessage("first", new byte[2], null);
        controller.beginMessage("second", 2);
        controller.dispatchAssembledMessage("second", new byte[2], null);
        executor.runNext();

        assertFalse(controller.canBeginMessage());
        assertFalse(controller.canBeginMessage());

        firstCompletion.complete(null);

        assertEquals(1, capacityNotifications.get());
        assertEquals(2, handled.get());
        assertEquals(0, executor.pendingTaskCount());

        secondAdmission.complete(null);

        assertEquals(0, controller.state().retainedMessages());
        assertEquals(1, capacityNotifications.get());
    }

    @Test
    void supportsIndependentAssembliesForFutureMultiplexedTransports() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        List<Integer> handledSizes = new ArrayList<>();
        RuntimeIngressController<Object> controller = controller(
                executor, 2, 3, 16, (bytes, receiveTiming, dispatchTiming) -> {
                    handledSizes.add(bytes.length);
                    return CompletableFuture.completedFuture(null);
                }, failure -> {}, () -> {});

        assertEquals(RuntimeIngressController.Admission.ACCEPTED, controller.beginMessage("stream-a", 2));
        assertEquals(RuntimeIngressController.Admission.ACCEPTED, controller.beginMessage("stream-b", 3));
        assertEquals(RuntimeIngressController.Admission.ACCEPTED,
                     controller.retainMessageFragmentBytes("stream-a", 2));
        assertEquals(RuntimeIngressController.Admission.ACCEPTED,
                     controller.dispatchAssembledMessage("stream-b", new byte[3], null));
        assertEquals(RuntimeIngressController.Admission.ACCEPTED,
                     controller.dispatchAssembledMessage("stream-a", new byte[4], null));

        executor.runAll();

        assertEquals(List.of(3, 4), handledSizes);
        assertEquals(0, controller.state().retainedMessages());
        assertEquals(0L, controller.state().retainedBytes());
    }

    @Test
    void synchronousCompletionsReuseRuntimeWorkerForPendingMessages() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        AtomicInteger handled = new AtomicInteger();
        RuntimeIngressController<Object> controller = controller(
                executor, 1, 3, 16, (bytes, receiveTiming, dispatchTiming) -> {
                    handled.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }, failure -> {}, () -> {});
        for (int i = 0; i < 3; i++) {
            controller.beginMessage("stream-" + i, 2);
            controller.dispatchAssembledMessage("stream-" + i, new byte[2], null);
        }

        assertEquals(1, executor.pendingTaskCount());
        executor.runNext();

        assertEquals(3, handled.get());
        assertEquals(0, executor.pendingTaskCount());
        assertEquals(0, controller.state().retainedMessages());
    }

    @Test
    void synchronousWorkerBatchEventuallyYieldsToTheExecutor() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        AtomicInteger handled = new AtomicInteger();
        RuntimeIngressController<Object> controller = controller(
                executor, 1, 64, 256, (bytes, receiveTiming, dispatchTiming) -> {
                    handled.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }, failure -> {}, () -> {});
        for (int i = 0; i < 64; i++) {
            controller.beginMessage("stream-" + i, 2);
            controller.dispatchAssembledMessage("stream-" + i, new byte[2], null);
        }

        executor.runNext();

        assertTrue(handled.get() > 1, "The common synchronous path should still reuse its worker");
        assertTrue(handled.get() < 64, "A large synchronous burst must eventually yield to shared executor work");
        assertEquals(1, executor.pendingTaskCount());
        executor.runAll();
        assertEquals(64, handled.get());
        assertEquals(0, controller.state().retainedMessages());
    }

    @Test
    void countAndCompressedByteLimitsBackpressureWithoutChangingAccounting() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        RuntimeIngressController<Object> controller = controller(
                executor, 1, 2, 4, (bytes, receiveTiming, dispatchTiming) -> new CompletableFuture<>(),
                failure -> {}, () -> {});

        assertEquals(RuntimeIngressController.Admission.ACCEPTED, controller.beginMessage("a", 3));
        assertEquals(RuntimeIngressController.Admission.BACKPRESSURED, controller.beginMessage("b", 2));
        assertEquals(1, controller.state().retainedMessages());
        assertEquals(3L, controller.state().retainedBytes());

        assertEquals(RuntimeIngressController.Admission.ACCEPTED,
                     controller.dispatchAssembledMessage("a", new byte[3], null));
        assertEquals(RuntimeIngressController.Admission.ACCEPTED, controller.beginMessage("b", 1));
        assertEquals(RuntimeIngressController.Admission.BACKPRESSURED,
                     controller.retainMessageFragmentBytes("b", 1));
        assertEquals(2, controller.state().retainedMessages());
        assertEquals(4L, controller.state().retainedBytes());
    }

    @Test
    void soleOversizedMessageCanProgressButAdditionalWorkWaits() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        RuntimeIngressController<Object> controller = controller(
                executor, 1, 2, 4, (bytes, receiveTiming, dispatchTiming) -> CompletableFuture.completedFuture(null),
                failure -> {}, () -> {});

        assertEquals(RuntimeIngressController.Admission.ACCEPTED, controller.beginMessage("stream", 5));
        assertEquals(RuntimeIngressController.Admission.BACKPRESSURED, controller.beginMessage("other", 1));
        assertEquals(5L, controller.state().retainedBytes());
    }

    @Test
    void executorRejectionIsReportedWithTheBoundedState() {
        RejectedExecutionException rejection = new RejectedExecutionException("stopped");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RuntimeIngressController<Object> controller = controller(
                task -> { throw rejection; }, 1, 2, 16,
                (bytes, receiveTiming, dispatchTiming) -> CompletableFuture.completedFuture(null),
                failure::set, () -> {});

        controller.beginMessage("stream", 2);
        controller.dispatchAssembledMessage("stream", new byte[2], null);

        RuntimeIngressController.IngressException reported = assertInstanceOf(
                RuntimeIngressController.IngressException.class, failure.get());
        assertEquals(RuntimeIngressController.IngressException.Reason.EXECUTOR_REJECTED, reported.reason());
        assertSame(rejection, reported.getCause());
        assertEquals(1, reported.state().retainedMessages());
        assertEquals(0, controller.state().retainedMessages());
        assertFalse(controller.canBeginMessage());
    }

    @Test
    void peerCloseWaitsForFunctionalCompletionBeforeClosing() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> functionalCompletion = new CompletableFuture<>();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger capacityNotifications = new AtomicInteger();
        List<RuntimeIngressController.Progress> progressEvents = new ArrayList<>();
        RuntimeIngressController<Object> controller = new RuntimeIngressController<>(
                executor, 1, 2, 16, (bytes, receiveTiming, dispatchTiming) ->
                        RuntimeIngressController.MessageDispatch.admitted(functionalCompletion),
                failure -> {}, capacityNotifications::incrementAndGet,
                (progress, retainedMessages, sequence) -> progressEvents.add(progress));
        controller.beginMessage("stream", 2);
        controller.dispatchAssembledMessage("stream", new byte[2], null);
        executor.runNext();

        controller.closeAfterDrain(closes::incrementAndGet);

        assertEquals(0, closes.get());
        assertFalse(controller.canBeginMessage());

        functionalCompletion.complete(null);

        assertEquals(1, closes.get());
        assertEquals(0, capacityNotifications.get());
        assertEquals(List.of(RuntimeIngressController.Progress.RETAINED_WORK_STARTED,
                             RuntimeIngressController.Progress.FUNCTIONAL_MESSAGE_COMPLETED), progressEvents);
    }

    @Test
    void localCloseSuppressesLaterProgressFromAlreadyActiveMessages() {
        ManuallyTriggeredExecutor executor = new ManuallyTriggeredExecutor();
        CompletableFuture<Void> functionalCompletion = new CompletableFuture<>();
        List<RuntimeIngressController.Progress> progressEvents = new ArrayList<>();
        RuntimeIngressController<Object> controller = new RuntimeIngressController<>(
                executor, 1, 2, 16, (bytes, receiveTiming, dispatchTiming) ->
                        RuntimeIngressController.MessageDispatch.admitted(functionalCompletion),
                failure -> {}, () -> {},
                (progress, retainedMessages, sequence) -> progressEvents.add(progress));
        controller.beginMessage("stream", 2);
        controller.dispatchAssembledMessage("stream", new byte[2], null);
        executor.runNext();
        progressEvents.clear();

        controller.close();
        functionalCompletion.complete(null);

        assertTrue(progressEvents.isEmpty());
    }

    private static RuntimeIngressController<Object> controller(
            Executor executor, int maxConcurrency, int maxRetainedMessages, long maxRetainedBytes,
            FunctionalHandler<Object> handler,
            java.util.function.Consumer<Throwable> failureHandler,
            Runnable capacityHandler) {
        return new RuntimeIngressController<>(executor, maxConcurrency, maxRetainedMessages, maxRetainedBytes,
                                              (bytes, context, timing) -> RuntimeIngressController.MessageDispatch
                                                      .admitted(handler.handle(bytes, context, timing)),
                                              failureHandler, capacityHandler,
                                              (progress, retainedMessages, sequence) -> {});
    }

    @FunctionalInterface
    private interface FunctionalHandler<C> {
        CompletionStage<Void> handle(byte[] bytes, C context, RuntimeIngressController.DispatchTiming timing);
    }

    private static class ManuallyTriggeredExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }

        int pendingTaskCount() {
            return tasks.size();
        }
    }
}
