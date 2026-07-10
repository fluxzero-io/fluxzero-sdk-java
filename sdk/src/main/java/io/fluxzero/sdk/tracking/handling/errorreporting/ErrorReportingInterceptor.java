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

package io.fluxzero.sdk.tracking.handling.errorreporting;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerInvoker.DelegatingHandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.common.handling.HandlerMethod.DelegatingHandlerMethod;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.ErrorGateway;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Executable;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.common.ObjectUtils.stackTrace;
import static io.fluxzero.sdk.common.ClientUtils.getLocalHandlerAnnotation;
import static io.fluxzero.sdk.common.ClientUtils.isSelfTracking;

/**
 * {@link HandlerInterceptor} that reports exceptions to the configured {@link ErrorGateway}.
 * <p>
 * This interceptor captures any uncaught exceptions thrown during message handling and appends them to the error log.
 * It also supports reporting errors from asynchronous {@link CompletionStage} results returned by handler methods.
 * <p>
 * Local handler invocations (typically in-process invocations marked as {@code @LocalHandler}) are excluded from error reporting.
 *
 * <h2>Behavior</h2>
 * <ul>
 *     <li>If the handler returns a {@link CompletionStage}, any exception occurring during its completion is reported.</li>
 *     <li>If the handler throws an exception synchronously, it is reported immediately and rethrown.</li>
 *     <li>Exceptions marked as {@link FunctionalException} or {@link TechnicalException} are passed through as-is.</li>
 *     <li>Other exceptions are wrapped in a {@link TechnicalException} and enriched with stack trace metadata.</li>
 * </ul>
 *
 * @see ErrorGateway
 * @see HandlerInterceptor
 * @see TechnicalException
 * @see FunctionalException
 */
@AllArgsConstructor
@Slf4j
public class ErrorReportingInterceptor implements HandlerInterceptor {

    private final ErrorGateway errorGateway;
    private final ClassValue<ConcurrentHashMap<Executable, HandlerErrorPolicy>> policyCache = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<Executable, HandlerErrorPolicy> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @Override
    public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        return new Handler<>() {
            private final ConcurrentHashMap<HandlerMethod<DeserializingMessage>, HandlerMethod<DeserializingMessage>>
                    reportingMethods = new ConcurrentHashMap<>();

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                HandlerInvoker invoker = handler.getInvokerOrNull(message);
                if (invoker == null) {
                    return null;
                }
                HandlerErrorPolicy policy = policy(invoker);
                if (policy.isLocalHandler(invoker, message)) {
                    return invoker;
                }
                return new DelegatingHandlerInvoker(invoker) {
                    @Override
                    public Object invoke(BiFunction<Object, Object, Object> combiner) {
                        try {
                            return monitorResult(invoker.invoke(combiner), invoker, message);
                        } catch (Throwable e) {
                            reportError(e, invoker, message);
                            throw e;
                        }
                    }
                };
            }

            @Override
            public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
                HandlerMethod<DeserializingMessage> method = handler.getHandlerMethodOrNull(message);
                if (method == null) {
                    return null;
                }
                HandlerErrorPolicy policy = policy(method);
                if (policy.isLocalHandler(method, message)) {
                    return method;
                }
                HandlerMethod<DeserializingMessage> reportingMethod = reportingMethods.get(method);
                if (reportingMethod != null) {
                    return reportingMethod;
                }
                reportingMethod = new ReportingHandlerMethod(method);
                HandlerMethod<DeserializingMessage> existing = reportingMethods.putIfAbsent(method, reportingMethod);
                return existing == null ? reportingMethod : existing;
            }

            @Override
            public Class<?> getTargetClass() {
                return handler.getTargetClass();
            }

            @Override
            public String toString() {
                return handler.toString();
            }
        };
    }

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        return message -> handle(message, invoker, () -> function.apply(message));
    }

    private Object handle(DeserializingMessage message, HandlerInvoker invoker, Supplier<Object> action) {
        if (policy(invoker).isLocalHandler(invoker, message)) {
            return action.get();
        }
        try {
            return monitorResult(action.get(), invoker, message);
        } catch (Throwable e) {
            reportError(e, invoker, message);
            throw e;
        }
    }

    private Object monitorResult(Object result, HandlerDescriptor invoker, DeserializingMessage message) {
        if (result instanceof CompletionStage<?> s) {
            s.whenComplete((r, e) -> {
                if (e != null) {
                    message.run(m -> reportError(e, invoker, m));
                }
            });
        }
        return result;
    }

    private HandlerErrorPolicy policy(HandlerDescriptor invoker) {
        Class<?> targetClass = invoker.getTargetClass();
        Executable method = invoker.getMethod();
        if (targetClass == null || method == null) {
            return HandlerErrorPolicy.reportErrors;
        }
        ConcurrentHashMap<Executable, HandlerErrorPolicy> policies = policyCache.get(targetClass);
        HandlerErrorPolicy cached = policies.get(method);
        if (cached != null) {
            return cached;
        }
        HandlerErrorPolicy computed = new HandlerErrorPolicy(
                getLocalHandlerAnnotation(targetClass, method).isPresent(), !isSelfTracking(targetClass, method));
        HandlerErrorPolicy existing = policies.putIfAbsent(method, computed);
        return existing != null ? existing : computed;
    }

    protected void reportError(Throwable e, HandlerDescriptor invoker, DeserializingMessage cause) {
        e = unwrapException(e);
        Metadata metadata = cause.getMetadata();
        if (!(e instanceof FunctionalException || e instanceof TechnicalException)) {
            metadata = metadata.with("stackTrace", stackTrace(e));
            e = new TechnicalException(FluxzeroErrors.handlerInvocationFailed(
                    invoker.getTargetClass().getName(), cause.toString(), e));
        }
        errorGateway.report(new Message(e, metadata));
    }

    private class ReportingHandlerMethod extends DelegatingHandlerMethod<DeserializingMessage> {
        private ReportingHandlerMethod(HandlerMethod<DeserializingMessage> delegate) {
            super(delegate);
        }

        @Override
        public Object invoke(DeserializingMessage message, BiFunction<Object, Object, Object> resultCombiner) {
            try {
                return monitorResult(delegate.invoke(message, resultCombiner), delegate, message);
            } catch (Throwable e) {
                reportError(e, delegate, message);
                throw e;
            }
        }
    }

    private record HandlerErrorPolicy(boolean localHandler, boolean reportSelfHandlers) {
        private static final HandlerErrorPolicy reportErrors = new HandlerErrorPolicy(false, true);

        private boolean isLocalHandler(HandlerDescriptor invoker, DeserializingMessage message) {
            return localHandler || !reportSelfHandlers
                   && Objects.equals(invoker.getTargetClass(), message.getPayloadClass());
        }
    }
}
