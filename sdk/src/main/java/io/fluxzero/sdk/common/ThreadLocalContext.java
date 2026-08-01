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

package io.fluxzero.sdk.common;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared, opt-in context for thread-local values that belong to the same logical request.
 * <p>
 * A participating value is created using {@link #create()}. Calling {@link #capture()} takes one snapshot of all
 * participating values, which can subsequently be activated around work on another thread. Activation is nested and
 * restores the context that was previously present on that thread.
 * <p>
 * Participating values remain regular thread locals on the hot path. The shared registry is only consulted when a
 * context is captured or activated, so merely entering and leaving a request context does not allocate a map.
 * Removing a participating value removes its thread-local value in the usual way; once the final child is removed,
 * there is therefore no request context left on that thread.
 */
public final class ThreadLocalContext {

    private static final ThreadLocal<ActiveValues> activeValues =
            ThreadLocal.withInitial(ActiveValues::new);

    private ThreadLocalContext() {
    }

    /**
     * Creates a thread local whose value participates in this shared context.
     * <p>
     * The returned holder is an ordinary {@link ThreadLocal}; participation only adds work when a snapshot is captured
     * or activated.
     */
    public static <T> ThreadLocal<T> create() {
        return new ParticipatingThreadLocal<>();
    }

    /** Returns an immutable snapshot of every context value active on the current thread. */
    public static Snapshot capture() {
        return activeValues.get().capture();
    }

    /**
     * Opens a scope that can switch directly between multiple snapshots and restores the original context on close.
     *
     * <p>This is useful for ordered batches whose items each carry their own request context. Unlike invoking
     * {@link Snapshot#run(Runnable)} for every item, intermediate switches do not first restore an empty worker
     * context. Context changes made while processing an item are still detected and cleared by the next switch.</p>
     */
    public static Activation openActivation() {
        return new Activation(capture());
    }

    /** A reusable context-switching scope created by {@link #openActivation()}. */
    public static final class Activation implements AutoCloseable {
        private final Snapshot previous;
        private boolean closed;

        private Activation(Snapshot previous) {
            this.previous = previous;
        }

        /** Replaces the currently active context with the supplied snapshot. */
        public void use(Snapshot next) {
            if (closed) {
                throw new IllegalStateException("Context activation has already been closed");
            }
            Snapshot.activate(capture(), Objects.requireNonNull(next, "snapshot"));
        }

        /** Restores the context that was active when this scope was opened. */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Snapshot.activate(capture(), previous);
            }
        }
    }

    /** A reusable snapshot of the context that was active when {@link #capture()} was called. */
    public static final class Snapshot {
        private static final Snapshot empty = new Snapshot(new ThreadLocal<?>[0], new Object[0]);

        private final ThreadLocal<?>[] participants;
        private final Object[] values;

        private Snapshot(ThreadLocal<?>[] participants, Object[] values) {
            this.participants = participants;
            this.values = values;
        }

        private static Snapshot empty() {
            return empty;
        }

        /** Returns whether this snapshot contains no participating values. */
        public boolean isEmpty() {
            return participants.length == 0;
        }

        /** Runs a task with this snapshot active and restores the previous context afterwards. */
        public void run(Runnable task) {
            Snapshot previous = capture();
            activate(previous, this);
            try {
                task.run();
            } finally {
                activate(capture(), previous);
            }
        }

        /** Supplies a value with this snapshot active and restores the previous context afterwards. */
        public <T> T supply(Supplier<T> task) {
            Snapshot previous = capture();
            activate(previous, this);
            try {
                return task.get();
            } finally {
                activate(capture(), previous);
            }
        }

        /** Wraps a task so that this snapshot is active whenever the task runs. */
        public Runnable wrap(Runnable task) {
            return () -> run(task);
        }

        /** Wraps a supplier so that this snapshot is active whenever the supplier runs. */
        public <T> Supplier<T> wrap(Supplier<T> task) {
            return () -> supply(task);
        }

        /** Wraps a two-argument consumer, such as a completion callback, with this snapshot. */
        public <T, U> BiConsumer<T, U> wrap(BiConsumer<T, U> task) {
            return (first, second) -> {
                Snapshot previous = capture();
                activate(previous, this);
                try {
                    task.accept(first, second);
                } finally {
                    activate(capture(), previous);
                }
            };
        }

        /** Wraps a function so that this snapshot is active whenever the function runs. */
        public <T, R> Function<T, R> wrap(Function<T, R> task) {
            return input -> {
                Snapshot previous = capture();
                activate(previous, this);
                try {
                    return task.apply(input);
                } finally {
                    activate(capture(), previous);
                }
            };
        }

        private static void activate(
                Snapshot previous,
                Snapshot next) {
            if (previous == next) {
                return;
            }
            boolean sameParticipants =
                    previous.participants.length
                    == next.participants.length;
            if (sameParticipants) {
                for (int i = 0;
                     i < previous.participants.length;
                     i++) {
                    if (previous.participants[i]
                        != next.participants[i]) {
                        sameParticipants = false;
                        break;
                    }
                }
            }
            if (!sameParticipants) {
                for (ThreadLocal<?> participant :
                        previous.participants) {
                    setSnapshotValue(participant, null);
                }
            }
            for (int i = 0;
                 i < next.participants.length;
                 i++) {
                setSnapshotValue(
                        next.participants[i],
                        next.values[i]);
            }
            activeValues.get().restore(next, sameParticipants);
        }
    }

    private static final class ParticipatingThreadLocal<T>
            extends ThreadLocal<T> {

        @Override
        public void set(T value) {
            super.set(value);
            if (value == null) {
                activeValues.get().remove(this);
            } else {
                activeValues.get().put(this, value);
            }
        }

        @Override
        public void remove() {
            super.remove();
            activeValues.get().remove(this);
        }

        private void setFromSnapshot(T value) {
            super.set(value);
        }
    }

    private static final class ActiveValues {
        private ThreadLocal<?>[] participants = new ThreadLocal<?>[8];
        private Object[] values = new Object[8];
        private int size;
        private Snapshot snapshot = Snapshot.empty();
        private boolean dirty;

        private Snapshot capture() {
            if (!dirty) {
                return snapshot;
            }
            int active = 0;
            for (int i = 0; i < size; i++) {
                if (values[i] != null) {
                    active++;
                }
            }
            if (active == 0) {
                snapshot = Snapshot.empty();
            } else {
                ThreadLocal<?>[] activeParticipants =
                        new ThreadLocal<?>[active];
                Object[] activeValues = new Object[active];
                int target = 0;
                for (int i = 0; i < size; i++) {
                    if (values[i] != null) {
                        activeParticipants[target] = participants[i];
                        activeValues[target++] = values[i];
                    }
                }
                snapshot = new Snapshot(
                        activeParticipants, activeValues);
            }
            dirty = false;
            return snapshot;
        }

        private void put(
                ThreadLocal<?> participant,
                Object value) {
            for (int i = 0; i < size; i++) {
                if (participants[i] == participant) {
                    if (values[i] != value) {
                        values[i] = value;
                        dirty = true;
                    }
                    return;
                }
            }
            ensureCapacity(size + 1);
            participants[size] = participant;
            values[size++] = value;
            dirty = true;
        }

        private void remove(
                ThreadLocal<?> participant) {
            for (int i = 0; i < size; i++) {
                if (participants[i] != participant) {
                    continue;
                }
                if (values[i] != null) {
                    values[i] = null;
                    dirty = true;
                }
                return;
            }
        }

        private void restore(Snapshot next, boolean sameParticipants) {
            if (!sameParticipants) {
                Arrays.fill(values, 0, size, null);
            }
            for (int i = 0; i < next.participants.length; i++) {
                putRestored(
                        next.participants[i],
                        next.values[i]);
            }
            snapshot = next;
            dirty = false;
        }

        private void putRestored(
                ThreadLocal<?> participant,
                Object value) {
            for (int i = 0; i < size; i++) {
                if (participants[i] == participant) {
                    values[i] = value;
                    return;
                }
            }
            ensureCapacity(size + 1);
            participants[size] = participant;
            values[size++] = value;
        }

        private void ensureCapacity(int required) {
            if (required <= participants.length) {
                return;
            }
            int capacity = Math.max(
                    required,
                    participants.length << 1);
            participants = Arrays.copyOf(
                    participants, capacity);
            values = Arrays.copyOf(values, capacity);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setSnapshotValue(
            ThreadLocal<?> participant,
            Object value) {
        if (participant
            instanceof ParticipatingThreadLocal<?> participating) {
            ((ParticipatingThreadLocal<Object>) participating)
                    .setFromSnapshot(value);
            return;
        }
        setValue(participant, value);
    }

    @SuppressWarnings("unchecked")
    private static void setValue(ThreadLocal<?> participant, Object value) {
        ((ThreadLocal<Object>) participant).set(value);
    }
}
