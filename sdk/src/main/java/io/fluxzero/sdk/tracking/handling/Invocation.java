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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.Registration;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.NonFinal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * Tracks the lifecycle and identity of a single message handler invocation.
 * <p>
 * This class enables consistent tagging and correlation of all side effects (e.g. metrics, queries, event sourcing,
 * message publication) produced during the execution of a handler. Each invocation is assigned a unique
 * {@link #getId() invocation ID}. When available, it also keeps track of the active handler class so metadata and
 * logging contexts can trace functional and non-functional effects back to the triggering handler.
 *
 * <h2>Automatic Invocation Wrapping</h2>
 * The Fluxzero client automatically wraps all handler invocations using this class. This includes:
 * <ul>
 *     <li>Local handlers (i.e. message handling in the publishing thread)</li>
 *     <li>Tracked handlers (i.e. message tracking via the Fluxzero Runtime)</li>
 * </ul>
 * <p>
 * As a result, developers typically do not need to call {@link #performInvocation(Callable)} directly,
 * unless they are manually invoking a handler outside of the Fluxzero infrastructure.
 *
 * <h2>Usage</h2>
 * When used manually, wrap handler logic with {@link #performInvocation(Callable)} to activate an invocation context:
 *
 * <pre>{@code
 * Invocation.performInvocation(() -> {
 *     // handler logic
 *     Fluxzero.publishEvent(new SomeEvent());
 *     return result;
 * });
 * }</pre>
 * <p>
 * This ensures:
 * <ul>
 *     <li>A consistent invocation ID is available throughout the thread</li>
 *     <li>Any emitted messages, metrics, or queries can include that ID as a correlation token</li>
 *     <li>Callbacks can be registered via {@link #whenHandlerCompletes(BiConsumer)} to react to success/failure</li>
 * </ul>
 *
 * @see #performInvocation(Callable)
 * @see #getCurrent()
 * @see #whenHandlerCompletes(BiConsumer)
 */
@Value
public class Invocation {

    private static final ThreadLocal<Invocation> current = ThreadLocalContext.create();
    private static final CompletableFuture<Void> completedResultPublicationBarrier =
            CompletableFuture.completedFuture(null);
    String handler;
    @Getter(AccessLevel.NONE)
    @NonFinal
    String id;
    @Getter(AccessLevel.NONE)
    @NonFinal
    transient List<BiConsumer<Object, Throwable>> callbacks;
    @Getter(AccessLevel.NONE)
    @NonFinal
    transient ResultObservation resultObservation;

    private Invocation(String handler) {
        this.handler = handler;
    }

    static Invocation forHandler(HandlerDescriptor handler) {
        return new Invocation(getHandlerName(handler));
    }

    /**
     * Wraps the given {@link Callable} in an invocation context.
     * <p>
     * This method ensures that callbacks registered via {@link #whenHandlerCompletes(BiConsumer)} are executed
     * upon completion of the callable.
     *
     * @param callable the task to run
     * @return the callable result
     */
    @SneakyThrows
    public static <V> V performInvocation(Callable<V> callable) {
        return performInvocation((String) null, callable);
    }

    /**
     * Wraps the given {@link Callable} in an invocation context for the supplied handler.
     *
     * @param handlerInvoker the handler that is being invoked
     * @param callable       the task to run
     * @return the callable result
     */
    public static <V> V performInvocation(HandlerInvoker handlerInvoker, Callable<V> callable) {
        return performInvocation(getHandlerName(handlerInvoker), callable);
    }

    /**
     * Wraps the given {@link Callable} in an invocation context for the supplied handler descriptor.
     *
     * @param handlerDescriptor the handler that is being invoked
     * @param callable          the task to run
     * @return the callable result
     */
    public static <V> V performInvocation(HandlerDescriptor handlerDescriptor, Callable<V> callable) {
        return performInvocation(getHandlerName(handlerDescriptor), callable);
    }

    /**
     * Invokes the supplied reusable handler method inside an invocation context without allocating a per-message
     * {@link Callable} adapter.
     *
     * @param handlerMethod the handler method that is being invoked
     * @param message       the message to handle
     * @param <M>           the message type
     * @return the handler result
     */
    public static <M> Object performInvocation(HandlerMethod<? super M> handlerMethod, M message) {
        if (current.get() != null) {
            return handlerMethod.invoke(message);
        }
        Invocation invocation = new Invocation(getHandlerName(handlerMethod));
        current.set(invocation);
        try {
            Object result = handlerMethod.invoke(message);
            current.set(null);
            invocation.complete(result, null);
            return result;
        } catch (Throwable e) {
            current.set(null);
            invocation.complete(null, e);
            throw e;
        }
    }

    @SneakyThrows
    private static <V> V performInvocation(String handler, Callable<V> callable) {
        if (current.get() != null) {
            return callable.call();
        }
        Invocation invocation = new Invocation(handler);
        current.set(invocation);
        try {
            V result = callable.call();
            current.set(null);
            invocation.complete(result, null);
            return result;
        } catch (Throwable e) {
            current.set(null);
            invocation.complete(null, e);
            throw e;
        }
    }

    private static String getHandlerName(HandlerDescriptor handlerDescriptor) {
        if (handlerDescriptor == null) {
            return null;
        }
        Class<?> targetClass = handlerDescriptor.getTargetClass();
        return targetClass == null || HandlerInvoker.SimpleInvoker.class.equals(targetClass)
                ? null : targetClass.getSimpleName();
    }

    public String getId() {
        if (id == null) {
            id = IdentityProvider.defaultIdentityProvider.nextTechnicalId();
        }
        return id;
    }

    /**
     * Returns the current {@code Invocation} bound to this thread, or {@code null} if none exists.
     */
    public static Invocation getCurrent() {
        Invocation result = current.get();
        return result == null ? LocalExecution.currentInvocation() : result;
    }

    /**
     * Transfers a deferred automatic handler result to the active tracking invocation, if it observes results.
     * The returned completion represents error handling, not durability; dependencies must keep using the original
     * completion. Local/manual invocations return {@code null} and retain their existing completion contract.
     *
     * @param completion original deferred handler result
     * @param progress releases deferred work when an error handler explicitly retries this invocation
     * @return completion owned by tracking, or {@code null} when no observer is active
     */
    public static CompletableFuture<Void> observeDeferredResult(CompletableFuture<Object> completion, Runnable progress) {
        Invocation invocation = getCurrent();
        ResultObservation observation = invocation == null ? null : invocation.resultObservation;
        if (observation == null || observation.message != DeserializingMessage.getCurrent()
            || observation.deferred != null) {
            return null;
        }
        observation.deferred = completion;
        observation.progress = progress;
        if (observation.owner.handled == null) {
            observation.owner.handled = new CompletableFuture<>();
            if (observation.owner.resultObserved) {
                observation.owner.settle(observation.owner.result);
            }
        }
        return observation.owner.handled;
    }

    /**
     * Tracking's invocation-local ownership of a deferred result. Installing an observation does not change ordinary
     * handler results. Only explicitly attached deferred results replace the handler's immediate placeholder result.
     */
    public static final class ResultObservation {
        private final ResultObservation owner;
        private final DeserializingMessage message;
        private CompletableFuture<Object> deferred;
        private volatile CompletableFuture<Void> handled;
        private Object result;
        private volatile boolean resultObserved;
        private Runnable progress;

        /** Creates an observation for exactly the message being handled, excluding nested local messages. */
        public ResultObservation(DeserializingMessage message) {
            owner = this;
            this.message = message;
        }

        private ResultObservation(ResultObservation owner) {
            this.owner = owner;
            this.message = owner.message;
        }

        /** Whether this invocation has transferred a deferred result to tracking. */
        public boolean observesDeferredResult() {
            return deferred != null;
        }

        /** Invokes a handler inside an existing {@link Invocation} and exposes its deferred result, if any. */
        public Object invoke(Callable<Object> action) throws Exception {
            Invocation invocation = getCurrent();
            ResultObservation previous = invocation.resultObservation;
            invocation.resultObservation = this;
            try {
                Object result = action.call();
                if (deferred == null || result == null) {
                    return deferred == null ? result : deferred;
                }
                // Decorators may replace the placeholder with a response. Preserve it after durability.
                return result instanceof CompletionStage<?> stage
                        ? deferred.thenCompose(ignored -> stage)
                        : deferred.thenApply(ignored -> result);
            } finally {
                invocation.resultObservation = previous;
            }
        }

        /** Retries only this handler, releasing and awaiting its deferred commit before reporting retry success. */
        public Object retry(HandlerDescriptor handler, Callable<Object> action) {
            ResultObservation retry = new ResultObservation(owner);
            Object result = performInvocation(handler, () -> retry.invoke(action));
            if (retry.deferred != null) {
                retry.progress.run();
                return ((CompletionStage<?>) result).toCompletableFuture().join();
            }
            return result;
        }

        /** Settles batch ownership after tracking's error handler and any asynchronous recovery have finished. */
        public Object complete(Object result) {
            this.result = result;
            resultObserved = true;
            if (handled != null) {
                settle(result);
            }
            return result;
        }

        private void settle(Object result) {
            if (result instanceof CompletionStage<?> stage) {
                stage.whenComplete((value, failure) -> {
                    if (failure == null) {
                        settle(value);
                    } else {
                        handled.completeExceptionally(failure);
                    }
                });
            } else {
                handled.complete(null);
            }
        }

        /** Keeps an unhandled error exceptional for batch recovery. */
        public void fail(Throwable failure) {
            complete(CompletableFuture.failedFuture(failure));
        }
    }

    static boolean hasThreadLocalContext() {
        return current.get() != null;
    }

    /**
     * Registers a callback to be executed when the current handler invocation completes.
     * <p>
     * If no invocation is active, the callback is executed immediately with {@code null} values.
     *
     * @param callback the handler result/error consumer
     * @return a {@link Registration} handle to cancel the callback
     */
    public static Registration whenHandlerCompletes(BiConsumer<Object, Throwable> callback) {
        Invocation invocation = getCurrent();
        if (invocation == null) {
            callback.accept(null, null);
            return Registration.noOp();
        } else {
            return invocation.registerCallback(callback);
        }
    }

    private Registration registerCallback(BiConsumer<Object, Throwable> callback) {
        if (callbacks == null) {
            callbacks = new ArrayList<>();
        }
        callbacks.add(callback);
        return () -> callbacks.remove(callback);
    }

    /**
     * Registers asynchronous work that must complete before the current handler result is published.
     * <p>
     * This method is intended for handler-completion callbacks, such as aggregate commit policies that start work after
     * the handler returned but still want request results to reflect the committed state.
     *
     * @param completion completion to await before result publication; {@code null} is ignored
     */
    public static void awaitBeforeResultPublication(CompletableFuture<?> completion) {
        DeserializingMessage currentMessage = DeserializingMessage.getCurrent();
        if (currentMessage != null) {
            awaitBeforeResultPublication(currentMessage, completion);
        }
    }

    /**
     * Registers asynchronous work that must complete before the supplied message's handler result is published.
     *
     * @param message    message whose result should wait for the completion
     * @param completion completion to await before result publication; {@code null} is ignored
     */
    public static void awaitBeforeResultPublication(DeserializingMessage message, CompletableFuture<?> completion) {
        if (completion == null) {
            return;
        }
        message.computeContextIfAbsent(ResultPublicationBarrier.class, ignored -> new ResultPublicationBarrier())
                .add(completion);
    }

    /**
     * Returns the barrier that must complete before the supplied message's handler result is published.
     *
     * @param message message whose result publication barrier should be resolved
     * @return completion future, or an already completed future when no post-handler work was registered
     */
    public static CompletableFuture<Void> resultPublicationBarrier(DeserializingMessage message) {
        return message.getContext(ResultPublicationBarrier.class)
                .map(ResultPublicationBarrier::completion)
                .orElse(completedResultPublicationBarrier);
    }

    private void complete(Object result, Throwable error) {
        if (callbacks != null) {
            AsyncCompletionScope.runAndAwait(() -> callbacks.forEach(c -> c.accept(result, error)));
        }
    }

    void completeLocal(Object result, Throwable error) {
        complete(result, error);
    }

    private static final class ResultPublicationBarrier {
        private final List<CompletableFuture<?>> completions = new ArrayList<>();

        private synchronized void add(CompletableFuture<?> completion) {
            completions.add(completion);
        }

        private synchronized CompletableFuture<Void> completion() {
            if (completions.isEmpty()) {
                return completedResultPublicationBarrier;
            }
            return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
        }
    }
}
