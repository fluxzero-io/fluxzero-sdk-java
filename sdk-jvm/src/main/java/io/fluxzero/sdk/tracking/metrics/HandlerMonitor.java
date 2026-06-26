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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static io.fluxzero.sdk.common.ClientUtils.getLocalHandlerAnnotation;
import static io.fluxzero.sdk.common.ClientUtils.isLocalSelfHandler;
import static io.fluxzero.sdk.web.WebUtils.getWebPatterns;
import static java.time.temporal.ChronoUnit.NANOS;

@Slf4j
public class HandlerMonitor implements HandlerInterceptor {
    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        if (metricsDisabled(invoker)) {
            return function;
        }
        return message -> {
            if (!logMetrics(invoker, message)) {
                return function.apply(message);
            }
            Instant start = Instant.now();
            try {
                Object result = function.apply(message);
                publishMetrics(invoker, message, false, start, result);
                return result;
            } catch (Throwable e) {
                publishMetrics(invoker, message, true, start, e);
                throw e;
            }
        };
    }

    protected void publishMetrics(HandlerInvoker invoker, DeserializingMessage message,
                                  boolean exceptionalResult, Instant start, Object result) {
        try {
            String consumer = Tracker.current().map(Tracker::getName).orElseGet(() -> "local-" + message.getMessageType());
            boolean completed = !(result instanceof CompletableFuture<?>) || ((CompletableFuture<?>) result).isDone();
            Fluxzero.getOptionally().ifPresent(fc -> fc.metricsGateway().publish(new HandleMessageEvent(
                    consumer, invoker.getTargetClass().getSimpleName(),
                    message.getIndex(), message.getMessageType(), message.getTopic(),
                    formatType(message, invoker), exceptionalResult, start.until(Instant.now(), NANOS), completed)));
            if (!completed) {
                Map<String, String> correlationData = Fluxzero.currentCorrelationData();
                ((CompletionStage<?>) result).whenComplete((r, e) -> message.run(
                        m -> Fluxzero.getOptionally().ifPresent(fc -> fc.metricsGateway().publish(
                                new CompleteMessageEvent(
                                        consumer, invoker.getTargetClass().getSimpleName(),
                                        m.getIndex(), m.getMessageType(), m.getTopic(), formatType(m, invoker),
                                        e != null, start.until(Instant.now(), NANOS)),
                                Metadata.of(correlationData)))));
            }
        } catch (Exception e) {
            log.error("Failed to publish handler metrics", e);
        }
    }

    protected String formatType(DeserializingMessage message, HandlerInvoker invoker) {
        if (message.getMessageType() == MessageType.WEBREQUEST) {
            try {
                var webPatterns = getWebPatterns(invoker.getTargetClass(), null, invoker.getMethod());
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
