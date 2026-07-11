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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerInvoker.DelegatingHandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.common.handling.HandlerMethod.DelegatingHandlerMethod;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.BatchInterceptor;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Intercepts individual message handling operations, enabling cross-cutting behavior around handler invocation.
 * <p>
 * A {@code HandlerInterceptor} can be used to inspect or modify messages before they are passed to a handler, monitor
 * and log handler executions, block certain messages from being handled, or inspect and modify the return value after
 * handling.
 * </p>
 *
 * <p>
 * Interceptors are typically configured via
 * {@link io.fluxzero.sdk.tracking.Consumer#handlerInterceptors()}, or applied programmatically using the
 * {@link #wrap(Handler)} method.
 * </p>
 *
 * <p>
 * Implementations can also be registered via Java's {@link ServiceLoader}. Service-loaded interceptors are picked up
 * automatically by Fluxzero, including when using the {@code TestFixture}, and are ordered using {@link
 * io.fluxzero.sdk.common.Order @Order}.
 * </p>
 *
 * <h2>Common Use Cases:</h2>
 * <ul>
 *   <li>Validating or transforming a message before it reaches the handler</li>
 *   <li>Adding logging, tracing, or metrics for observability</li>
 *   <li>Conditionally suppressing handler invocation</li>
 *   <li>Decorating or modifying the result of a handler method</li>
 * </ul>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * public class LoggingHandlerInterceptor implements HandlerInterceptor {
 *     @Override
 *     public Function<DeserializingMessage, Object> interceptHandling(
 *             Function<DeserializingMessage, Object> next, HandlerInvoker invoker) {
 *         return message -> {
 *             log.info("Before handling: {}", message.getPayload());
 *             Object result = next.apply(message);
 *             log.info("After handling: {}", result);
 *             return result;
 *         };
 *     }
 * }
 * }</pre>
 *
 * @see io.fluxzero.sdk.tracking.Consumer#handlerInterceptors()
 * @see BatchInterceptor
 */
@FunctionalInterface
public interface HandlerInterceptor extends HandlerDecorator {

    /**
     * Default handler interceptors discovered via Java's service loader, sorted by
     * {@link io.fluxzero.sdk.common.Order}. These interceptors are applied automatically by Fluxzero.
     */
    List<HandlerInterceptor> defaultInterceptors = ClientUtils.loadServices(HandlerInterceptor.class);

    /**
     * Intercepts the message handling logic.
     * <p>
     * The {@code function} parameter represents the next step in the handling chain— typically the actual message
     * handler. The {@code invoker} provides metadata and invocation logic for the underlying handler method.
     * </p>
     *
     * <p>
     * Within this method, an interceptor may:
     * <ul>
     *   <li>Modify the {@code DeserializingMessage} before passing it to the handler</li>
     *   <li>Bypass the handler entirely and return a value directly</li>
     *   <li>Wrap the result after the handler is invoked</li>
     * </ul>
     *
     * <p>
     * Note: Interceptors may return a different {@code DeserializingMessage}, but it must be compatible
     * with a handler method in the same target class. If no suitable handler is found, an exception will be thrown.
     * </p>
     *
     * @param function the next step in the handler chain (typically the handler itself)
     * @param invoker  the metadata and execution strategy for the actual handler method
     * @return a decorated function that wraps handling behavior
     */
    Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                             HandlerInvoker invoker);

    /**
     * Prepares this interceptor for a specific handler method.
     *
     * <p>The returned interceptor is cached and reused concurrently for invocations with the same stable handler
     * metadata. Implementations may use this hook to resolve annotation- or signature-based policy once while keeping
     * message-dependent decisions inside {@link PreparedHandlerInterceptor#interceptHandling}.</p>
     *
     * <p>The default adapter preserves the existing {@link #interceptHandling(Function, HandlerInvoker)} contract and
     * deliberately disables the reusable {@link HandlerMethod} path. Custom interceptors therefore retain their
     * current behavior unless they explicitly opt into preparation.</p>
     *
     * @param handler stable metadata for the handler method
     * @return a thread-safe prepared interceptor
     */
    default PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
        HandlerInterceptor interceptor = this;
        return new PreparedHandlerInterceptor() {
            @Override
            public Object interceptHandling(DeserializingMessage message, HandlerDescriptor handler,
                                            BiFunction<Object, Object, Object> combiner,
                                            PreparedHandlerFunction next) {
                return interceptor.interceptHandling(
                                nextMessage -> next.apply(message, nextMessage, handler, combiner),
                                (HandlerInvoker) handler)
                        .apply(message);
            }

            @Override
            public boolean supportsHandlerMethod() {
                return false;
            }
        };
    }

    /**
     * Indicates whether this interceptor opts into the prepared, mergeable handler wrapper.
     *
     * <p>The default is {@code false}, preserving the exact wrapper behavior of existing custom interceptors. An
     * interceptor should return {@code true} only when {@link #prepare(HandlerDescriptor)} returns a thread-safe plan
     * that fully represents its handling behavior. If a subclass overrides only the legacy
     * {@link #interceptHandling(Function, HandlerInvoker)} method, the legacy wrapper is retained automatically.</p>
     *
     * @return {@code true} when this interceptor may be merged into a prepared wrapper
     */
    default boolean supportsPreparation() {
        return false;
    }

    /**
     * Wraps a {@link Handler} with this interceptor, producing an intercepted handler.
     *
     * @param handler the original handler to wrap
     * @return an intercepted handler that applies this interceptor to all handled messages
     */
    default Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        if (!hasCompatiblePreparedContract()) {
            return new InterceptedHandler(handler, this);
        }
        if (handler != null && handler.getClass() == PreparedInterceptedHandler.class) {
            return ((PreparedInterceptedHandler) handler).withOuterInterceptor(this);
        }
        return new PreparedInterceptedHandler(handler, this);
    }

    private boolean hasCompatiblePreparedContract() {
        if (!supportsPreparation()) {
            return false;
        }
        try {
            Method legacyMethod = getClass().getMethod(
                    "interceptHandling", Function.class, HandlerInvoker.class);
            Method preparedMethod = getClass().getMethod("prepare", HandlerDescriptor.class);
            Class<?> legacyOwner = legacyMethod.getDeclaringClass();
            Class<?> preparedOwner = preparedMethod.getDeclaringClass();
            return legacyOwner == preparedOwner || !preparedOwner.isAssignableFrom(legacyOwner);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return false;
        }
    }

    /**
     * A handler-method-specific interceptor plan. Plans are created once and may be invoked concurrently.
     */
    @FunctionalInterface
    interface PreparedHandlerInterceptor {
        /**
         * A prepared no-op policy that proceeds directly to the next function.
         */
        PreparedHandlerInterceptor noOp = (message, handler, combiner, next) ->
                next.apply(message, handler, combiner);

        /**
         * Intercepts one handling operation using previously prepared handler metadata.
         *
         * @param message  current message
         * @param handler  the resolved per-message invoker or reusable handler method
         * @param combiner result combiner supplied to the handler
         * @param next     the remaining prepared handling chain
         * @return the handling result
         */
        Object interceptHandling(DeserializingMessage message, HandlerDescriptor handler,
                                 BiFunction<Object, Object, Object> combiner, PreparedHandlerFunction next);

        /**
         * Adapts this prepared interceptor to the original function-based interceptor contract.
         *
         * @param function next handling function
         * @param invoker  resolved handler invoker
         * @return an intercepted handling function
         */
        default Function<DeserializingMessage, Object> interceptHandling(
                Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
            PreparedHandlerFunction next = (currentMessage, nextMessage, handler, combiner) ->
                    function.apply(nextMessage);
            return message -> interceptHandling(message, invoker, (first, second) -> first, next);
        }

        /**
         * Whether this plan can safely wrap a reusable {@link HandlerMethod}. Implementations returning {@code true}
         * must depend only on the supplied descriptor and message, not on mutable state of a per-message invoker.
         *
         * @return {@code true} when the plan supports reusable handler methods
         */
        default boolean supportsHandlerMethod() {
            return true;
        }
    }

    /**
     * Remaining portion of a prepared handler interceptor chain.
     */
    @FunctionalInterface
    interface PreparedHandlerFunction {
        /**
         * Proceeds to the remaining chain with an unchanged message.
         *
         * @param message  current message
         * @param handler  resolved handler descriptor
         * @param combiner result combiner
         * @return handling result
         */
        default Object apply(DeserializingMessage message, HandlerDescriptor handler,
                             BiFunction<Object, Object, Object> combiner) {
            return apply(message, message, handler, combiner);
        }

        /**
         * Proceeds to the remaining chain, resolving it again when {@code nextMessage} differs from
         * {@code currentMessage}.
         *
         * @param currentMessage message received by the current interceptor
         * @param nextMessage    message passed to the remainder of the chain
         * @param handler        resolved handler descriptor
         * @param combiner       result combiner
         * @return handling result
         */
        Object apply(DeserializingMessage currentMessage, DeserializingMessage nextMessage,
                     HandlerDescriptor handler, BiFunction<Object, Object, Object> combiner);
    }

    /**
     * Implementation of {@link Handler} that delegates to another handler and applies a {@code HandlerInterceptor}.
     */
    class InterceptedHandler implements Handler<DeserializingMessage> {
        private final Handler<DeserializingMessage> delegate;
        private final HandlerInterceptor interceptor;

        public InterceptedHandler(Handler<DeserializingMessage> delegate, HandlerInterceptor interceptor) {
            this.delegate = delegate;
            this.interceptor = interceptor;
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return Optional.ofNullable(getInvokerOrNull(message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
            HandlerInvoker invoker = delegate.getInvokerOrNull(message);
            if (invoker == null) {
                return null;
            }
            return new DelegatingHandlerInvoker(invoker) {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    return interceptor.interceptHandling(m -> {
                        if (m != message) {
                            HandlerInvoker changedInvoker = InterceptedHandler.this.delegate.getInvokerOrNull(m);
                            if (changedInvoker == null) {
                                throw new UnsupportedOperationException(
                                        "Changing the payload type in a HandlerInterceptor is not supported.");
                            }
                            return m.apply(msg -> changedInvoker.invoke(combiner));
                        }
                        return invoker.invoke(combiner);
                    }, invoker).apply(message);
                }
            };
        }

        @Override
        public Class<?> getTargetClass() {
            return delegate.getTargetClass();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /**
     * Merged wrapper for interceptors that explicitly support method preparation.
     */
    class PreparedInterceptedHandler implements Handler<DeserializingMessage> {
        private final Handler<DeserializingMessage> delegate;
        private final List<HandlerInterceptor> interceptors;
        private final ClassValue<ConcurrentHashMap<Executable, PreparedInterceptorPlan>>
                preparedInterceptors = new ClassValue<>() {
            @Override
            protected ConcurrentHashMap<Executable, PreparedInterceptorPlan> computeValue(Class<?> type) {
                return new ConcurrentHashMap<>();
            }
        };
        private final ConcurrentHashMap<HandlerMethod<DeserializingMessage>,
                Optional<HandlerMethod<DeserializingMessage>>> interceptedMethods = new ConcurrentHashMap<>();
        private volatile PreparedPolicyEntry lastPreparedPolicy;
        private volatile PreparedMethodEntry lastPreparedMethod;

        public PreparedInterceptedHandler(Handler<DeserializingMessage> delegate, HandlerInterceptor interceptor) {
            this(delegate, List.of(interceptor));
        }

        private PreparedInterceptedHandler(Handler<DeserializingMessage> delegate,
                                           List<HandlerInterceptor> interceptors) {
            this.delegate = delegate;
            this.interceptors = List.copyOf(interceptors);
        }

        private Handler<DeserializingMessage> withOuterInterceptor(HandlerInterceptor interceptor) {
            List<HandlerInterceptor> combined = new ArrayList<>(interceptors.size() + 1);
            combined.add(interceptor);
            combined.addAll(interceptors);
            return new PreparedInterceptedHandler(delegate, combined);
        }

        int interceptorCount() {
            return interceptors.size();
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return Optional.ofNullable(getInvokerOrNull(message));
        }

        @Override
        public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
            HandlerInvoker invoker = delegate.getInvokerOrNull(message);
            if (invoker == null) {
                return null;
            }
            PreparedInterceptorPlan prepared = preparedInterceptors(invoker);
            if (prepared.empty()) {
                return invoker;
            }
            return new DelegatingHandlerInvoker(invoker) {
                @Override
                public Object invoke(BiFunction<Object, Object, Object> combiner) {
                    return prepared.function().apply(message, invoker, combiner);
                }
            };
        }

        @Override
        public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
            HandlerMethod<DeserializingMessage> method = delegate.getHandlerMethodOrNull(message);
            if (method == null) {
                return null;
            }
            PreparedMethodEntry cached = lastPreparedMethod;
            if (cached != null && cached.source() == method) {
                return cached.result().orElse(null);
            }
            Optional<HandlerMethod<DeserializingMessage>> result = interceptedMethods.computeIfAbsent(
                    method, this::prepareHandlerMethod);
            lastPreparedMethod = new PreparedMethodEntry(method, result);
            return result.orElse(null);
        }

        private Optional<HandlerMethod<DeserializingMessage>> prepareHandlerMethod(
                HandlerMethod<DeserializingMessage> method) {
            PreparedInterceptorPlan prepared = prepareInterceptors(method);
            if (prepared.empty()) {
                return Optional.of(method);
            }
            if (!prepared.supportsHandlerMethod()) {
                return Optional.empty();
            }
            return Optional.of(new DelegatingHandlerMethod<>(method) {
                @Override
                public Object invoke(DeserializingMessage handledMessage,
                                     BiFunction<Object, Object, Object> resultCombiner) {
                    return prepared.function().apply(handledMessage, method, resultCombiner);
                }
            });
        }

        private Object invokeChangedMessage(int nextInterceptor, DeserializingMessage message,
                                            BiFunction<Object, Object, Object> combiner) {
            Handler<DeserializingMessage> remaining = nextInterceptor == interceptors.size()
                    ? delegate : new PreparedInterceptedHandler(delegate, interceptors.subList(
                            nextInterceptor, interceptors.size()));
            HandlerInvoker invoker = remaining.getInvokerOrNull(message);
            if (invoker == null) {
                throw new UnsupportedOperationException(
                        "Changing the payload type in a HandlerInterceptor is not supported.");
            }
            return message.apply(m -> invoker.invoke(combiner));
        }

        private PreparedInterceptorPlan preparedInterceptors(HandlerDescriptor handler) {
            Class<?> targetClass = handler.getTargetClass();
            Executable method = handler.getMethod();
            if (targetClass == null || method == null) {
                return prepareInterceptors(handler);
            }
            PreparedPolicyEntry cached = lastPreparedPolicy;
            if (cached != null && cached.targetClass() == targetClass && cached.method() == method) {
                return cached.plan();
            }
            PreparedInterceptorPlan result = preparedInterceptors.get(targetClass).computeIfAbsent(
                    method, ignored -> prepareInterceptors(handler));
            lastPreparedPolicy = new PreparedPolicyEntry(targetClass, method, result);
            return result;
        }

        @SuppressWarnings("unchecked")
        private PreparedInterceptorPlan prepareInterceptors(HandlerDescriptor handler) {
            List<PreparedInterceptor> result = new ArrayList<>(interceptors.size());
            boolean supportsHandlerMethod = true;
            for (int i = 0; i < interceptors.size(); i++) {
                PreparedHandlerInterceptor prepared = interceptors.get(i).prepare(handler);
                if (prepared != PreparedHandlerInterceptor.noOp) {
                    result.add(new PreparedInterceptor(i, prepared));
                    supportsHandlerMethod &= prepared.supportsHandlerMethod();
                }
            }
            if (result.isEmpty()) {
                return PreparedInterceptorPlan.noOp;
            }
            PreparedHandlerFunction chain = (currentMessage, nextMessage, descriptor, combiner) ->
                    descriptor instanceof HandlerInvoker invoker
                            ? invoker.invoke(combiner)
                            : ((HandlerMethod<DeserializingMessage>) descriptor).invoke(nextMessage, combiner);
            for (int i = result.size() - 1; i >= 0; i--) {
                PreparedInterceptor current = result.get(i);
                PreparedHandlerFunction remaining = chain;
                PreparedHandlerFunction next = (currentMessage, nextMessage, descriptor, combiner) ->
                        currentMessage == nextMessage
                                ? remaining.apply(nextMessage, descriptor, combiner)
                                : invokeChangedMessage(current.originalIndex() + 1, nextMessage, combiner);
                chain = (currentMessage, nextMessage, descriptor, combiner) ->
                        current.interceptor().interceptHandling(nextMessage, descriptor, combiner, next);
            }
            return new PreparedInterceptorPlan(chain, supportsHandlerMethod, false);
        }

        @Override
        public Class<?> getTargetClass() {
            return delegate.getTargetClass();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }

        private record PreparedInterceptor(int originalIndex, PreparedHandlerInterceptor interceptor) {
        }

        private record PreparedInterceptorPlan(PreparedHandlerFunction function, boolean supportsHandlerMethod,
                                               boolean empty) {
            private static final PreparedInterceptorPlan noOp = new PreparedInterceptorPlan(null, true, true);
        }

        private record PreparedPolicyEntry(Class<?> targetClass, Executable method,
                                           PreparedInterceptorPlan plan) {
        }

        private record PreparedMethodEntry(HandlerMethod<DeserializingMessage> source,
                                           Optional<HandlerMethod<DeserializingMessage>> result) {
        }

    }
}
