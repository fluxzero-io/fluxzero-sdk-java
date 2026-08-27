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

package io.fluxzero.common;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import static io.fluxzero.common.ObjectUtils.newPlatformThreadFactory;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A thread-safe batching queue that asynchronously flushes its content to a consumer in configurable batch sizes.
 * <p>
 * This utility is useful for scenarios where multiple values are being added over time and you want to consume
 * them in batches for efficiency—such as sending messages to a remote system, writing to a log, etc.
 *
 * <p>
 * Flushes are executed on a single background thread, and results (e.g. completion or failure) are tracked
 * via {@link CompletableFuture}s. Optional monitors may observe each flushed batch.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Supports both synchronous and asynchronous consumers</li>
 *   <li>Flushes automatically after new items are added</li>
 *   <li>Tracks flush progress with {@link CompletableFuture} per add</li>
 *   <li>Customizable error handling via {@link ErrorHandler}</li>
 *   <li>Monitoring support via {@link Monitored}</li>
 * </ul>
 *
 * <h2>Typical Use</h2>
 * <pre>{@code
 * Backlog<String> backlog = Backlog.forAsyncConsumer(batch -> {
 *     return sendToServer(batch); // returns CompletableFuture
 * });
 * backlog.add("a", "b", "c");
 * }</pre>
 *
 * @param <T> The type of item being buffered and processed.
 */
@Slf4j
public class Backlog<T> implements Monitored<List<T>> {

    private static final int MAX_INITIAL_BATCH_CAPACITY = 16;

    private final int maxBatchSize;
    private final ToLongFunction<? super T> itemWeight;
    private final long maxBatchWeight;
    /*
     * Untracked values are stored directly. Tracked adds use one Submission for the entire add call, so exact
     * completion does not add an allocation to the fire-and-forget hot path.
     */
    private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
    private final ThrowingFunction<List<T>, CompletableFuture<?>> consumer;
    private final ErrorHandler<List<T>> errorHandler;
    private final ExecutorService executorService;
    private final int maxInFlightBatches;
    private final long batchCollectionDelayNanos;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicInteger inFlightBatches = new AtomicInteger();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();

    private final Collection<Consumer<List<T>>> monitors = new CopyOnWriteArraySet<>();

    /**
     * Creates a new backlog for a synchronous consumer and default batch size and default logging error handler.
     */
    public static <T> Backlog<T> forConsumer(ThrowingConsumer<List<T>> consumer) {
        return forConsumer(consumer, 1024);
    }

    /**
     * Creates a backlog with custom max batch size and default logging error handler.
     */
    public static <T> Backlog<T> forConsumer(ThrowingConsumer<List<T>> consumer, int maxBatchSize) {
        return forConsumer(consumer, maxBatchSize, (e, batch) -> log.error("Consumer {} failed to handle batch of size {}. Continuing with next batch.", consumer, batch.size(), e));
    }

    /**
     * Creates a backlog with custom max batch size and error handler.
     */
    public static <T> Backlog<T> forConsumer(ThrowingConsumer<List<T>> consumer, int maxBatchSize, ErrorHandler<List<T>> errorHandler) {
        return new Backlog<>(list -> {
            consumer.accept(list);
            return null;
        }, maxBatchSize, errorHandler);
    }

    /**
     * Creates a backlog for an asynchronous consumer with default max batch size and default logging error handler.
     */
    public static <T> Backlog<T> forAsyncConsumer(ThrowingFunction<List<T>, CompletableFuture<?>> consumer) {
        return forAsyncConsumer(consumer, 1024);
    }

    /**
     * Creates a backlog for an asynchronous consumer with custom max batch size and default logging error handler.
     */
    public static <T> Backlog<T> forAsyncConsumer(ThrowingFunction<List<T>, CompletableFuture<?>> consumer, int maxBatchSize) {
        return forAsyncConsumer(consumer, maxBatchSize, (e, batch) -> log.error("Consumer {} failed to handle batch of size {}. Continuing with next batch.", consumer, batch.size(), e));
    }

    /**
     * Creates a backlog for an asynchronous consumer with custom max batch size and error handler.
     */
    public static <T> Backlog<T> forAsyncConsumer(ThrowingFunction<List<T>, CompletableFuture<?>> consumer, int maxBatchSize, ErrorHandler<List<T>> errorHandler) {
        return new Backlog<>(consumer, maxBatchSize, errorHandler);
    }

    /**
     * @deprecated Use {@link #forAsyncConsumer(ThrowingFunction, int, int)} with one in-flight batch.
     */
    @Deprecated(forRemoval = true)
    public static <T> Backlog<T> forOrderedAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer) {
        return forAsyncConsumer(consumer, 1024, 1);
    }

    /**
     * @deprecated Use {@link #forAsyncConsumer(ThrowingFunction, int, int)} with one in-flight batch.
     */
    @Deprecated(forRemoval = true)
    public static <T> Backlog<T> forOrderedAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize) {
        return forAsyncConsumer(consumer, maxBatchSize, 1);
    }

    /**
     * @deprecated Use {@link #forAsyncConsumer(ThrowingFunction, int, int, ErrorHandler)} with one in-flight batch.
     */
    @Deprecated(forRemoval = true)
    public static <T> Backlog<T> forOrderedAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ErrorHandler<List<T>> errorHandler) {
        return forAsyncConsumer(consumer, maxBatchSize, 1, errorHandler);
    }

    /**
     * @deprecated Use {@link #forAsyncConsumer(ThrowingFunction, int, ToLongFunction, long, int)} with one
     * in-flight batch.
     */
    @Deprecated(forRemoval = true)
    public static <T> Backlog<T> forOrderedAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ToLongFunction<? super T> batchWeight,
            long maxBatchWeight) {
        return forAsyncConsumer(consumer, maxBatchSize, batchWeight, maxBatchWeight, 1);
    }

    /**
     * @deprecated Use {@link #forAsyncConsumer(ThrowingFunction, int, ToLongFunction, long, int, Duration)} with one
     * in-flight batch.
     */
    @Deprecated(forRemoval = true)
    public static <T> Backlog<T> forOrderedAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ToLongFunction<? super T> batchWeight,
            long maxBatchWeight,
            Duration batchCollectionDelay) {
        return forAsyncConsumer(
                consumer, maxBatchSize, batchWeight, maxBatchWeight, 1, batchCollectionDelay);
    }

    /**
     * Creates a backlog for an asynchronous consumer with a bounded number of in-flight batches. A batch remains
     * in flight until the future returned by the consumer completes. A new batch is dispatched as soon as capacity
     * becomes available.
     */
    public static <T> Backlog<T> forAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            int maxInFlightBatches) {
        return forAsyncConsumer(
                consumer, maxBatchSize, maxInFlightBatches,
                (e, batch) -> log.error(
                        "Consumer {} failed to handle batch of size {}. Continuing with next batch.",
                        consumer, batch.size(), e));
    }

    /**
     * Creates a bounded asynchronous backlog with a custom error handler.
     */
    public static <T> Backlog<T> forAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            int maxInFlightBatches,
            ErrorHandler<List<T>> errorHandler) {
        return new Backlog<>(consumer, maxBatchSize, errorHandler, maxInFlightBatches);
    }

    /**
     * Creates an asynchronous backlog bounded by item count, cumulative item weight and in-flight batches.
     * <p>
     * The first item is always admitted, even when it exceeds {@code maxBatchWeight}, so an oversized item can make
     * progress as a one-item batch.
     *
     * @param consumer       asynchronous batch consumer
     * @param maxBatchSize   maximum number of items in one batch
     * @param itemWeight     function that returns the non-negative weight of an item
     * @param maxBatchWeight maximum cumulative weight, except for one individually oversized item
     * @param maxInFlightBatches maximum number of consumer batches whose returned future has not completed
     */
    public static <T> Backlog<T> forAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ToLongFunction<? super T> itemWeight,
            long maxBatchWeight,
            int maxInFlightBatches) {
        return forAsyncConsumer(
                consumer, maxBatchSize, itemWeight, maxBatchWeight, maxInFlightBatches, Duration.ZERO);
    }

    /**
     * Creates an asynchronous backlog bounded by item count, cumulative item weight and in-flight batches, with a
     * bounded collection delay whenever an idle backlog starts flushing.
     * <p>
     * The delay only applies to the first batch after the backlog was idle. Batches already queued
     * behind an active consumer are drained immediately. This allows very short micro-batching
     * windows without delaying a sustained backlog once it has filled.
     *
     * @param consumer             asynchronous batch consumer
     * @param maxBatchSize         maximum number of items in one batch
     * @param itemWeight           function that returns the non-negative weight of an item
     * @param maxBatchWeight       maximum cumulative weight, except for one individually oversized item
     * @param maxInFlightBatches   maximum number of consumer batches whose returned future has not completed
     * @param batchCollectionDelay maximum time to collect concurrent items after an idle start
     */
    public static <T> Backlog<T> forAsyncConsumer(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ToLongFunction<? super T> itemWeight,
            long maxBatchWeight,
            int maxInFlightBatches,
            Duration batchCollectionDelay) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("Maximum batch size must be positive");
        }
        if (maxBatchWeight <= 0L) {
            throw new IllegalArgumentException("Maximum batch weight must be positive");
        }
        if (batchCollectionDelay == null || batchCollectionDelay.isNegative()) {
            throw new IllegalArgumentException("Batch collection delay must not be negative");
        }
        return new Backlog<>(
                consumer, maxBatchSize,
                (e, batch) -> log.error(
                        "Consumer {} failed to handle batch of size {}. Continuing with next batch.",
                        consumer, batch.size(), e),
                maxInFlightBatches, itemWeight, maxBatchWeight, batchCollectionDelay.toNanos());
    }

    protected Backlog(ThrowingFunction<List<T>, CompletableFuture<?>> consumer) {
        this(consumer, 1024);
    }

    protected Backlog(ThrowingFunction<List<T>, CompletableFuture<?>> consumer, int maxBatchSize) {
        this(consumer, maxBatchSize,
             (e, batch) -> log.error("Consumer {} failed to handle batch {}. Continuing with next batch.", consumer, batch, e));
    }

    protected Backlog(ThrowingFunction<List<T>, CompletableFuture<?>> consumer, int maxBatchSize, ErrorHandler<List<T>> errorHandler) {
        this(consumer, maxBatchSize, errorHandler, Integer.MAX_VALUE);
    }

    protected Backlog(ThrowingFunction<List<T>, CompletableFuture<?>> consumer, int maxBatchSize,
                      ErrorHandler<List<T>> errorHandler, int maxInFlightBatches) {
        this(consumer, maxBatchSize, errorHandler, maxInFlightBatches, null, Long.MAX_VALUE);
    }

    private Backlog(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ErrorHandler<List<T>> errorHandler,
            int maxInFlightBatches,
            ToLongFunction<? super T> itemWeight,
            long maxBatchWeight) {
        this(consumer, maxBatchSize, errorHandler, maxInFlightBatches,
             itemWeight, maxBatchWeight, 0L);
    }

    private Backlog(
            ThrowingFunction<List<T>, CompletableFuture<?>> consumer,
            int maxBatchSize,
            ErrorHandler<List<T>> errorHandler,
            int maxInFlightBatches,
            ToLongFunction<? super T> itemWeight,
            long maxBatchWeight,
            long batchCollectionDelayNanos) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("Maximum batch size must be positive");
        }
        if (maxInFlightBatches <= 0) {
            throw new IllegalArgumentException("Maximum in-flight batches must be positive");
        }
        this.maxBatchSize = maxBatchSize;
        this.consumer = consumer;
        this.executorService = Executors.newSingleThreadExecutor(newPlatformThreadFactory("Backlog"));
        this.errorHandler = errorHandler;
        this.maxInFlightBatches = maxInFlightBatches;
        this.itemWeight = itemWeight;
        this.maxBatchWeight = maxBatchWeight;
        this.batchCollectionDelayNanos = batchCollectionDelayNanos;
    }

    /**
     * Adds values to the backlog.
     *
     * @param values one or more values to enqueue
     * @return a future that completes when the values are processed by the consumer.
     */
    @SafeVarargs
    public final CompletableFuture<Void> add(T... values) {
        if (values.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        Object[] snapshot = values.clone();
        validateValues(snapshot);
        Submission<T> submission = Submission.tracked(snapshot);
        enqueue(submission);
        return submission.result();
    }

    /**
     * Adds a collection of values to the backlog.
     *
     * @param values collection of values to enqueue
     * @return a future that completes when the values are processed by the consumer.
     */
    public CompletableFuture<Void> add(Collection<? extends T> values) {
        if (values.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Object[] snapshot = values.toArray();
        validateValues(snapshot);
        Submission<T> submission = Submission.tracked(snapshot);
        enqueue(submission);
        return submission.result();
    }

    /**
     * Adds one value without allocating a separate flush future.
     * <p>
     * Use this only when the asynchronous consumer owns completion and failure propagation for the
     * value itself. Consumer failures still reach the configured backlog error handler.
     */
    public void addUntracked(T value) {
        Objects.requireNonNull(value, "Backlog values must not be null");
        enqueueUntracked(value);
    }

    /**
     * Adds multiple values without creating per-value flush futures.
     *
     * @see #addUntracked(Object)
     */
    public void addAllUntracked(Collection<? extends T> values) {
        if (values.isEmpty()) {
            return;
        }
        for (T value : values) {
            Objects.requireNonNull(value, "Backlog values must not be null");
        }
        boolean collect = isIdle();
        queue.addAll(values);
        scheduleIfCapacityAvailable(collect);
    }

    private void validateValues(Object[] values) {
        for (Object value : values) {
            Objects.requireNonNull(value, "Backlog values must not be null");
        }
    }

    private void enqueue(Submission<T> submission) {
        boolean collect = isIdle();
        queue.add(submission);
        scheduleIfCapacityAvailable(collect);
    }

    private void enqueueUntracked(T value) {
        boolean collect = isIdle();
        queue.add(value);
        scheduleIfCapacityAvailable(collect);
    }

    private boolean isIdle() {
        return queue.isEmpty() && inFlightBatches.get() == 0 && !flushing.get();
    }

    private void scheduleIfCapacityAvailable(boolean collectBeforeFlush) {
        if (inFlightBatches.get() < maxInFlightBatches) {
            flushIfNotFlushing(collectBeforeFlush);
        }
    }

    private void flushIfNotFlushing(boolean collectBeforeFlush) {
        if (flushing.compareAndSet(false, true)) {
            executorService.execute(() -> flush(collectBeforeFlush));
        }
    }

    private void flush(boolean collectBeforeFlush) {
        try {
            if (collectBeforeFlush && batchCollectionDelayNanos > 0L) {
                LockSupport.parkNanos(batchCollectionDelayNanos);
            }
            while (!queue.isEmpty() && inFlightBatches.get() < maxInFlightBatches) {
                dispatch(nextBatch());
            }
            flushing.set(false);
            if (!queue.isEmpty() && inFlightBatches.get() < maxInFlightBatches) {
                // A value or consumer completion may have raced with flushing being reset.
                flushIfNotFlushing(false);
            } else {
                tryShutdownExecutor();
            }
        } catch (Throwable e) {
            log.error("Failed to flush the backlog", e);
            flushing.set(false);
            tryShutdownExecutor();
            throw e;
        }
    }

    private ConsumerBatch<T> nextBatch() {
        List<T> values = new ArrayList<>(initialBatchCapacity(maxBatchSize));
        List<SubmissionSlice<T>> slices = new ArrayList<>();
        long weight = 0L;
        Throwable constructionError = null;
        while (values.size() < maxBatchSize) {
            Object queued = queue.peek();
            if (queued == null) {
                break;
            }
            Submission<T> submission = asSubmission(queued);
            T value = submission == null ? asValue(queued) : submission.peek();
            long itemWeight;
            try {
                itemWeight = weightOf(value);
            } catch (Throwable e) {
                if (values.isEmpty()) {
                    consume(queued, submission, value, values, slices);
                    constructionError = e;
                }
                break;
            }
            if (!values.isEmpty() && itemWeight > maxBatchWeight - weight) {
                break;
            }
            consume(queued, submission, value, values, slices);
            weight = itemWeight > Long.MAX_VALUE - weight ? Long.MAX_VALUE : weight + itemWeight;
        }
        return new ConsumerBatch<>(values, slices, constructionError);
    }

    private void consume(Object queued, Submission<T> submission, T value, List<T> values,
                         List<SubmissionSlice<T>> slices) {
        values.add(value);
        if (submission == null) {
            Object removed = queue.poll();
            if (removed != queued) {
                throw new IllegalStateException("Backlog item order changed while dispatching");
            }
        } else {
            submission.advance();
            if (slices.isEmpty() || slices.getLast().submission() != submission) {
                slices.add(new SubmissionSlice<>(submission));
            } else {
                slices.getLast().increment();
            }
            if (submission.isFullyDispatched()) {
                Object removed = queue.poll();
                if (removed != submission) {
                    throw new IllegalStateException("Backlog submission order changed while dispatching");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Submission<T> asSubmission(Object queued) {
        return queued instanceof Submission<?> submission ? (Submission<T>) submission : null;
    }

    @SuppressWarnings("unchecked")
    private T asValue(Object queued) {
        return (T) queued;
    }

    private void dispatch(ConsumerBatch<T> batch) {
        inFlightBatches.incrementAndGet();
        CompletableFuture<?> future;
        if (batch.constructionError() == null) {
            try {
                future = consumer.apply(batch.values());
            } catch (Throwable e) {
                future = CompletableFuture.failedFuture(e);
            }
        } else {
            future = CompletableFuture.failedFuture(batch.constructionError());
        }
        if (future == null) {
            finishBatch(batch, null);
        } else {
            future.whenComplete((ignored, failure) -> finishBatch(batch, unwrap(failure)));
        }
        monitors.forEach(m -> m.accept(batch.values()));
    }

    private Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while (result instanceof java.util.concurrent.CompletionException && result.getCause() != null
               && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private void finishBatch(ConsumerBatch<T> batch, Throwable failure) {
        if (failure != null) {
            try {
                errorHandler.handleError(failure, batch.values());
            } catch (Throwable handlerFailure) {
                log.error("Backlog error handler failed", handlerFailure);
            }
        }
        inFlightBatches.decrementAndGet();
        if (!queue.isEmpty()) {
            flushIfNotFlushing(false);
        } else {
            tryShutdownExecutor();
        }
        /*
         * Refill the newly available slot before completing producer futures. CompletableFuture dependants execute
         * inline by default and must not turn an already completed consumer batch into accidental backpressure.
         */
        for (SubmissionSlice<T> slice : batch.slices()) {
            slice.submission().complete(slice.count(), failure);
        }
    }

    static int initialBatchCapacity(int maxBatchSize) {
        return Math.min(maxBatchSize, MAX_INITIAL_BATCH_CAPACITY);
    }

    private long weightOf(T value) {
        if (itemWeight == null) {
            return 0L;
        }
        long result = itemWeight.applyAsLong(value);
        if (result < 0L) {
            throw new IllegalArgumentException("Batch item weight must not be negative");
        }
        return result;
    }

    /**
     * Adds a monitor to observe flushed batches.
     *
     * @param monitor the observer
     * @return a {@link Registration} that can be used to remove the monitor
     */
    @Override
    public Registration registerMonitor(Consumer<List<T>> monitor) {
        monitors.add(monitor);
        return () -> monitors.remove(monitor);
    }

    /**
     * Shuts down the internal executor service cleanly.
     */
    public void shutDown() {
        try {
            shutdownRequested.set(true);
            if (!queue.isEmpty()) {
                flushIfNotFlushing(false);
            }
            tryShutdownExecutor();
            try {
                executorService.awaitTermination(1L, SECONDS);
            } catch (InterruptedException e) {
                log.warn("Shutdown of executor was interrupted", e);
                Thread.currentThread().interrupt();
            }
        } catch (Throwable e) {
            log.warn("Failed to shutdown a backlog", e);
        }
    }

    private void tryShutdownExecutor() {
        if (shutdownRequested.get() && queue.isEmpty() && inFlightBatches.get() == 0 && !flushing.get()) {
            executorService.shutdown();
        }
    }

    private static final class Submission<T> {
        private final Object[] values;
        private final CompletableFuture<Void> result;
        private final AtomicInteger remaining;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private int nextIndex;

        private Submission(Object[] values) {
            this.values = values;
            this.result = new CompletableFuture<>();
            this.remaining = new AtomicInteger(values.length);
        }

        static <T> Submission<T> tracked(Object[] values) {
            return new Submission<>(values);
        }

        @SuppressWarnings("unchecked")
        T peek() {
            return (T) values[nextIndex];
        }

        void advance() {
            nextIndex++;
        }

        boolean isFullyDispatched() {
            return nextIndex == values.length;
        }

        CompletableFuture<Void> result() {
            return result;
        }

        void complete(int count, Throwable batchFailure) {
            if (batchFailure != null) {
                failure.compareAndSet(null, batchFailure);
            }
            int remaining = this.remaining.addAndGet(-count);
            if (remaining < 0) {
                throw new IllegalStateException("Backlog submission completed more items than it contained");
            }
            if (remaining == 0) {
                Throwable submissionFailure = failure.get();
                if (submissionFailure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(submissionFailure);
                }
            }
        }
    }

    private static final class SubmissionSlice<T> {
        private final Submission<T> submission;
        private int count = 1;

        private SubmissionSlice(Submission<T> submission) {
            this.submission = submission;
        }

        Submission<T> submission() {
            return submission;
        }

        int count() {
            return count;
        }

        void increment() {
            count++;
        }
    }

    private record ConsumerBatch<T>(List<T> values, List<SubmissionSlice<T>> slices,
                                    Throwable constructionError) {
    }

    /**
     * A function that consumes a batch of items and returns a future that completes when processing is done.
     */
    @FunctionalInterface
    public interface BatchConsumer<T> {
        CompletableFuture<Void> accept(List<T> batch) throws Exception;
    }
}
