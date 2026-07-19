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
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInvoker;
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
import java.util.function.Function;

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
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        return prepare(invoker).interceptHandling(function, invoker);
    }

    @Override
    public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
        HandlerErrorPolicy policy = policy(handler);
        if (policy.localHandler()) {
            return PreparedHandlerInterceptor.noOp;
        }
        return (message, descriptor, combiner, next) -> {
            if (policy.isLocalHandler(descriptor, message)) {
                return next.apply(message, descriptor, combiner);
            }
            try {
                return monitorResult(next.apply(message, descriptor, combiner), descriptor, message);
            } catch (Throwable e) {
                reportError(e, descriptor, message);
                throw e;
            }
        };
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(HandlerDescriptor handler) {
        HandlerErrorPolicy policy = policy(handler);
        if (policy.localHandler()) {
            return PreparedHandlerInputInterceptor.noOp;
        }
        return (input, descriptor, next) -> {
            if (policy.isLocalHandler(descriptor, input.getPayload())) {
                return next.apply(input, descriptor);
            }
            try {
                Object result = next.apply(input, descriptor);
                return result instanceof CompletionStage<?>
                        ? monitorResult(result, descriptor, input.getMessage()) : result;
            } catch (Throwable e) {
                reportError(e, descriptor, input.getMessage());
                throw e;
            }
        };
    }

    @Override
    public boolean supportsPreparation() {
        return true;
    }

    private Object monitorResult(Object result, HandlerDescriptor invoker, DeserializingMessage message) {
        if (result instanceof CompletionStage<?> s) {
            var context = message.captureContext();
            s.whenComplete(context.wrap((r, e) -> {
                if (e != null) {
                    reportError(e, invoker, message);
                }
            }));
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

    private record HandlerErrorPolicy(boolean localHandler, boolean reportSelfHandlers) {
        private static final HandlerErrorPolicy reportErrors = new HandlerErrorPolicy(false, true);

        private boolean isLocalHandler(HandlerDescriptor invoker, DeserializingMessage message) {
            return localHandler || !reportSelfHandlers
                   && Objects.equals(invoker.getTargetClass(), message.getPayloadClass());
        }

        private boolean isLocalHandler(HandlerDescriptor invoker, Object payload) {
            return localHandler || !reportSelfHandlers && payload != null
                   && Objects.equals(invoker.getTargetClass(), payload.getClass());
        }
    }
}
