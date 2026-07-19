/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.tracking.metrics;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.WebRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.sdk.common.ClientUtils.getLocalHandlerAnnotation;
import static io.fluxzero.sdk.common.ClientUtils.isLocalSelfHandler;
import static io.fluxzero.sdk.common.ClientUtils.isSelfTracking;
import static io.fluxzero.sdk.web.WebUtils.getWebPatterns;
import static java.time.temporal.ChronoUnit.NANOS;

@Slf4j
public class HandlerMonitor implements HandlerInterceptor {
    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        PreparedHandlerInterceptor prepared = prepare(invoker);
        return prepared.interceptHandling(function, invoker);
    }

    @Override
    public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
        Optional<LocalHandler> localHandler = getLocalHandlerAnnotation(handler.getTargetClass(), handler.getMethod());
        if (localHandler.filter(lh -> !lh.logMetrics()).isPresent()) {
            return PreparedHandlerInterceptor.noOp;
        }
        boolean alwaysLog = localHandler.map(LocalHandler::logMetrics).orElse(false);
        boolean selfTracking = handler.getTargetClass() != null
                               && isSelfTracking(handler.getTargetClass(), handler.getMethod());
        return new PreparedHandlerInterceptor() {
            @Override
            public Object interceptHandling(DeserializingMessage message, HandlerDescriptor descriptor,
                                            BiFunction<Object, Object, Object> combiner,
                                            PreparedHandlerFunction next) {
                if (!alwaysLog && !selfTracking
                    && Objects.equals(descriptor.getTargetClass(), message.getPayloadClass())) {
                    return next.apply(message, descriptor, combiner);
                }
                Instant start = Instant.now();
                try {
                    Object result = next.apply(message, descriptor, combiner);
                    if (!(descriptor instanceof HandlerInvoker invoker) || !invoker.wasSkipped()) {
                        publishPreparedMetrics(descriptor, message, false, start, result);
                    }
                    return result;
                } catch (Throwable e) {
                    publishPreparedMetrics(descriptor, message, true, start, e);
                    throw e;
                }
            }

            @Override
            public boolean supportsHandlerMethod() {
                return HandlerMonitor.this.getClass() == HandlerMonitor.class;
            }
        };
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(HandlerDescriptor handler) {
        return getLocalHandlerAnnotation(handler.getTargetClass(), handler.getMethod())
                .filter(localHandler -> !localHandler.logMetrics())
                .map(ignored -> PreparedHandlerInputInterceptor.noOp).orElse(null);
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(
            HandlerDescriptor handler, HandlerInput<DeserializingMessage> input) {
        Optional<LocalHandler> localHandler = getLocalHandlerAnnotation(
                handler.getTargetClass(), handler.getMethod());
        if (localHandler.isPresent()) {
            return localHandler.get().logMetrics() ? null : PreparedHandlerInputInterceptor.noOp;
        }
        Object payload = input.getPayload();
        boolean selfTracking = handler.getTargetClass() != null
                               && isSelfTracking(handler.getTargetClass(), handler.getMethod());
        return !selfTracking && payload != null && handler.getTargetClass() == payload.getClass()
                ? PreparedHandlerInputInterceptor.noOp : null;
    }

    @Override
    public boolean supportsPreparation() {
        return true;
    }

    private void publishPreparedMetrics(HandlerDescriptor handler, DeserializingMessage message,
                                        boolean exceptionalResult, Instant start, Object result) {
        if (handler instanceof HandlerInvoker invoker) {
            publishMetrics(invoker, message, exceptionalResult, start, result);
        } else {
            publishHandlerMethodMetrics(handler, message, exceptionalResult, start, result);
        }
    }

    protected void publishMetrics(HandlerInvoker invoker, DeserializingMessage message,
                                  boolean exceptionalResult, Instant start, Object result) {
        publishMetrics(invoker, message, exceptionalResult, start, result, m -> formatType(m, invoker));
    }

    private void publishHandlerMethodMetrics(HandlerDescriptor handler, DeserializingMessage message,
                                             boolean exceptionalResult, Instant start, Object result) {
        publishMetrics(handler, message, exceptionalResult, start, result, m -> formatHandlerType(m, handler));
    }

    private void publishMetrics(HandlerDescriptor handler, DeserializingMessage message,
                                boolean exceptionalResult, Instant start, Object result,
                                Function<DeserializingMessage, String> typeFormatter) {
        try {
            String consumer = Tracker.current().map(Tracker::getName).orElseGet(() -> "local-" + message.getMessageType());
            boolean completed = !(result instanceof CompletableFuture<?>) || ((CompletableFuture<?>) result).isDone();
            Fluxzero.getOptionally().ifPresent(fc -> fc.metricsGateway().publish(new HandleMessageEvent(
                    consumer, handler.getTargetClass().getSimpleName(),
                    message.getIndex(), message.getMessageType(), message.getTopic(),
                    typeFormatter.apply(message), exceptionalResult, start.until(Instant.now(), NANOS), completed)));
            if (!completed) {
                Map<String, String> correlationData = Fluxzero.currentCorrelationData();
                var context = message.captureContext();
                ((CompletionStage<?>) result).whenComplete(context.wrap((r, e) ->
                        Fluxzero.getOptionally().ifPresent(fc -> fc.metricsGateway().publish(
                                new CompleteMessageEvent(
                                        consumer, handler.getTargetClass().getSimpleName(),
                                        message.getIndex(), message.getMessageType(), message.getTopic(),
                                        typeFormatter.apply(message),
                                        e != null, start.until(Instant.now(), NANOS)),
                                Metadata.of(correlationData)))));
            }
        } catch (Exception e) {
            log.error("Failed to publish handler metrics", e);
        }
    }

    protected String formatType(DeserializingMessage message, HandlerInvoker invoker) {
        return formatHandlerType(message, invoker);
    }

    private String formatHandlerType(DeserializingMessage message, HandlerDescriptor handler) {
        if (message.getMessageType() == MessageType.WEBREQUEST) {
            try {
                var webPatterns = getWebPatterns(handler.getTargetClass(), null, handler.getMethod());
                String uriPattern = webPatterns.size() == 1
                        ? webPatterns.getFirst().getUri() : WebRequest.getUrl(message.getMetadata());
                return "%s %s".formatted(WebRequest.getMethod(message.getMetadata()), uriPattern);
            } catch (Exception ignored) {}
        }
        return message.getType();
    }

    protected boolean metricsDisabled(HandlerInvoker invoker) {
        return getLocalHandlerAnnotation(invoker).map(lh -> !lh.logMetrics()).orElse(false);
    }

    protected boolean logMetrics(HandlerInvoker invoker, HasMessage message) {
        return getLocalHandlerAnnotation(invoker).map(LocalHandler::logMetrics)
                .orElseGet(() -> !isLocalSelfHandler(invoker, message));
    }

}
