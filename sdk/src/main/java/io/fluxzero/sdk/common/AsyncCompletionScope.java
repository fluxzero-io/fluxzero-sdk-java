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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Thread-local scope for asynchronous work started from completion callbacks.
 * <p>
 * Completion callbacks sometimes need to start asynchronous side effects, such as aggregate commits, without blocking
 * each callback immediately. This scope lets those callbacks register their futures and waits for all registered work
 * after the callback group has finished running.
 * <p>
 * The scope is intentionally thread-local. Work registered from other threads is only included when that thread is
 * executing inside the same logical scope.
 */
public final class AsyncCompletionScope {

    private static final ThreadLocal<ScopeStack> scopes = ThreadLocalContext.create();

    private AsyncCompletionScope() {
    }

    /**
     * Runs the supplied task inside a new completion scope and waits for all futures registered in that scope.
     * <p>
     * If both the task and asynchronous completion fail, the asynchronous failure is added as a suppressed exception
     * to the task failure.
     *
     * @param task the task that may register asynchronous completion work
     */
    public static void runAndAwait(Runnable task) {
        runAndAwait(task, false);
    }

    /**
     * Runs a task and awaits its asynchronous work before committing progress.
     * <p>
     * Unlike {@link #runAndAwait(Runnable)}, a completion failure takes precedence over a task failure. In particular,
     * a task that intentionally stops partway through a batch must not authorize a partial position commit when
     * asynchronous work for earlier messages has failed. The task failure is retained as a suppressed exception.
     *
     * @param task task whose registered asynchronous work must complete successfully before progress is committed
     */
    public static void runAndAwaitBeforeCommit(Runnable task) {
        runAndAwait(task, true);
    }

    private static void runAndAwait(Runnable task, boolean beforeCommit) {
        Scope scope = new Scope();
        ScopeStack previous = scopes.get();
        scopes.set(new ScopeStack(scope, previous));
        Throwable taskFailure = null;
        try {
            task.run();
        } catch (Throwable e) {
            taskFailure = e;
        } finally {
            if (previous == null) {
                scopes.remove();
            } else {
                scopes.set(previous);
            }
        }
        Throwable waitFailure = scope.await();
        if (beforeCommit && taskFailure != null && waitFailure != null) {
            CompletionException failure = new CompletionException("Asynchronous batch completion failed", waitFailure);
            failure.addSuppressed(taskFailure);
            throw failure;
        }
        if (taskFailure != null) {
            if (waitFailure != null && waitFailure != taskFailure) {
                taskFailure.addSuppressed(waitFailure);
            }
            throwUnchecked(taskFailure);
        }
        if (waitFailure != null) {
            throwUnchecked(waitFailure);
        }
    }

    /**
     * Registers a future with the current completion scope.
     * <p>
     * When no scope is active, the future is returned unchanged and no waiting behavior is added. This makes callers
     * free to register futures unconditionally while preserving the normal behavior outside handler or batch
     * completion.
     *
     * @param future future to await at the end of the active scope; {@code null} is treated as already completed
     * @param <T>    result type of the future
     * @return the supplied future, or a completed future when {@code future} is {@code null}
     */
    public static <T> CompletableFuture<T> register(CompletableFuture<T> future) {
        return register(future, null);
    }

    /**
     * Registers a future with the current completion scope and runs a callback after scoped futures have completed.
     * <p>
     * The callback runs on the thread that owns the completion scope, after all registered futures have completed and
     * before any asynchronous failure is rethrown from {@link #runAndAwait(Runnable)}. This is useful for cleaning up
     * thread-local state that must remain visible until asynchronous completion work has finished.
     * <p>
     * When no scope is active, the future is returned unchanged and the callback is not invoked.
     *
     * @param future          future to await at the end of the active scope; {@code null} is treated as already
     *                        completed
     * @param afterCompletion callback to run after scoped futures have completed; may be {@code null}
     * @param <T>             result type of the future
     * @return the supplied future, or a completed future when {@code future} is {@code null}
     */
    public static <T> CompletableFuture<T> register(CompletableFuture<T> future, Runnable afterCompletion) {
        CompletableFuture<T> completion = future == null ? CompletableFuture.completedFuture(null) : future;
        ScopeStack stack = scopes.get();
        if (stack != null) {
            stack.scope().add(completion, afterCompletion);
        }
        return completion;
    }

    /**
     * Returns whether the current thread is executing inside an async completion scope.
     *
     * @return {@code true} when futures registered by this thread will be awaited by a surrounding scope
     */
    public static boolean isActive() {
        return scopes.get() != null;
    }

    /**
     * Captures the current completion scope and returns a supplier that re-enters it when executed.
     * <p>
     * This is intended for framework-managed worker threads that still belong to the same logical handler or batch
     * processing operation. If no scope is active, the original supplier is returned unchanged.
     *
     * @param supplier supplier to execute with the captured scope
     * @param <T>      result type
     * @return a context-aware supplier
     */
    public static <T> Supplier<T> captureContext(Supplier<T> supplier) {
        ScopeStack captured = scopes.get();
        return captured == null ? supplier : () -> runWithScope(captured, supplier);
    }

    private static <T> T runWithScope(ScopeStack captured, Supplier<T> supplier) {
        ScopeStack previous = scopes.get();
        scopes.set(captured);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                scopes.remove();
            } else {
                scopes.set(previous);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable error) throws E {
        throw (E) error;
    }

    private static final class Scope {
        private final List<Completion> completions = new ArrayList<>();

        synchronized void add(CompletableFuture<?> future, Runnable afterCompletion) {
            completions.add(new Completion(future, afterCompletion));
        }

        Throwable await() {
            List<Runnable> callbacks = null;
            Throwable waitFailure = null;
            while (true) {
                List<Completion> snapshot;
                synchronized (this) {
                    if (completions.isEmpty()) {
                        break;
                    }
                    snapshot = List.copyOf(completions);
                    completions.clear();
                }
                for (Completion completion : snapshot) {
                    if (completion.afterCompletion() != null) {
                        if (callbacks == null) {
                            callbacks = new ArrayList<>();
                        }
                        callbacks.add(completion.afterCompletion());
                    }
                }
                try {
                    CompletableFuture.allOf(snapshot.stream()
                                                    .map(Completion::future)
                                                    .toArray(CompletableFuture[]::new)).join();
                } catch (Throwable e) {
                    if (waitFailure == null) {
                        waitFailure = e;
                    } else if (waitFailure != e) {
                        waitFailure.addSuppressed(e);
                    }
                }
                // Framework workers may register publications before completing their registered invocation future.
                // Drain those additions too, rather than committing after only the first snapshot has completed.
            }
            Throwable callbackFailure = runCompletionCallbacks(callbacks);
            if (waitFailure != null) {
                if (callbackFailure != null && callbackFailure != waitFailure) {
                    waitFailure.addSuppressed(callbackFailure);
                }
                return waitFailure;
            }
            return callbackFailure;
        }

        private Throwable runCompletionCallbacks(List<Runnable> callbacks) {
            Throwable failure = null;
            if (callbacks == null) {
                return null;
            }
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Throwable e) {
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
            }
            return failure;
        }
    }

    private record Completion(CompletableFuture<?> future, Runnable afterCompletion) {
    }

    private record ScopeStack(Scope scope, ScopeStack parent) {
    }
}
