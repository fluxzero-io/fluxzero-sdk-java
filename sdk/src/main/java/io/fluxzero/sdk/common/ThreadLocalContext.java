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

    private static volatile ThreadLocal<?>[] participants = new ThreadLocal<?>[0];

    private ThreadLocalContext() {
    }

    /**
     * Creates a thread local whose value participates in this shared context.
     * <p>
     * The returned holder is an ordinary {@link ThreadLocal}; participation only adds work when a snapshot is captured
     * or activated.
     */
    public static <T> ThreadLocal<T> create() {
        ThreadLocal<T> result = new ThreadLocal<>();
        register(result);
        return result;
    }

    /** Returns an immutable snapshot of every context value active on the current thread. */
    public static Snapshot capture() {
        ThreadLocal<?>[] registered = participants;
        int count = 0;
        for (ThreadLocal<?> participant : registered) {
            if (participant.get() != null) {
                count++;
            }
        }
        if (count == 0) {
            return Snapshot.empty();
        }
        ThreadLocal<?>[] active = new ThreadLocal<?>[count];
        Object[] values = new Object[count];
        int index = 0;
        for (ThreadLocal<?> participant : registered) {
            Object value = participant.get();
            if (value != null) {
                active[index] = participant;
                values[index++] = value;
            }
        }
        return new Snapshot(active, values);
    }

    private static synchronized void register(ThreadLocal<?> participant) {
        ThreadLocal<?>[] current = participants;
        ThreadLocal<?>[] updated = Arrays.copyOf(current, current.length + 1);
        updated[current.length] = participant;
        participants = updated;
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
            activate();
            try {
                task.run();
            } finally {
                previous.activate();
            }
        }

        /** Supplies a value with this snapshot active and restores the previous context afterwards. */
        public <T> T supply(Supplier<T> task) {
            Snapshot previous = capture();
            activate();
            try {
                return task.get();
            } finally {
                previous.activate();
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
                activate();
                try {
                    task.accept(first, second);
                } finally {
                    previous.activate();
                }
            };
        }

        /** Wraps a function so that this snapshot is active whenever the function runs. */
        public <T, R> Function<T, R> wrap(Function<T, R> task) {
            return input -> {
                Snapshot previous = capture();
                activate();
                try {
                    return task.apply(input);
                } finally {
                    previous.activate();
                }
            };
        }

        private void activate() {
            for (ThreadLocal<?> participant : ThreadLocalContext.participants) {
                // capture() has already created a ThreadLocalMap entry while inspecting this participant. Retaining
                // that empty entry avoids recreating every registered entry on each async callback activation.
                setValue(participant, null);
            }
            for (int i = 0; i < participants.length; i++) {
                setValue(participants[i], values[i]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void setValue(ThreadLocal<?> participant, Object value) {
        ((ThreadLocal<Object>) participant).set(value);
    }
}
