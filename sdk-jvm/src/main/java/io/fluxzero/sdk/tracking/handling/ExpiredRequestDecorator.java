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
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.metrics.IgnoreMessageEvent;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebUtils;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static io.fluxzero.common.MessageType.WEBREQUEST;

@Slf4j
class ExpiredRequestDecorator implements HandlerDecorator {
    private static final Duration REQUEST_TIMEOUT_GRACE = Duration.ofSeconds(30);
    private final boolean publishMetrics;
    private final Class<? extends Annotation> handlerAnnotation;

    ExpiredRequestDecorator(boolean publishMetrics, Class<? extends Annotation> handlerAnnotation) {
        this.publishMetrics = publishMetrics;
        this.handlerAnnotation = handlerAnnotation;
    }

    @Override
    public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        return new Handler.DelegatingHandler<>(handler) {
            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                Optional<HandlerInvoker> invoker = delegate.getInvoker(message);
                if (invoker.isEmpty() || !isExpiredForHandler(message, invoker.get())) {
                    return invoker;
                }
                if (publishMetrics) {
                    publishIgnoreMessageMetric(message, invoker.get().getMethod(), invoker.get().getTargetClass());
                }
                return Optional.empty();
            }

            @Override
            public String toString() {
                return delegate.toString();
            }
        };
    }

    private boolean isExpiredForHandler(DeserializingMessage message, HandlerInvoker invoker) {
        if (!message.getMessageType().isRequest() || message.getIndex() == null
            || !skipExpiredRequests(invoker.getMethod())) {
            return false;
        }
        Optional<Duration> timeout = requestTimeout(message);
        if (timeout.filter(t -> !t.isNegative()).isEmpty()) {
            return false;
        }
        Instant deadline = deadline(message.getIndex(), timeout.get());
        return deadline.isBefore(Fluxzero.currentTime());
    }

    private boolean skipExpiredRequests(Executable executable) {
        return ReflectionUtils.getAnnotationAs(executable, handlerAnnotation, HandleAnnotation.class)
                .map(HandleAnnotation::isSkipExpiredRequests).orElse(false);
    }

    private Optional<Duration> requestTimeout(DeserializingMessage message) {
        String timeoutMillis = message.getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY);
        if (timeoutMillis == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofMillis(Long.parseLong(timeoutMillis)));
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid request timeout metadata on request {}", message.getMessageId(), e);
            return Optional.empty();
        }
    }

    private Instant deadline(long index, Duration timeout) {
        return IndexUtils.timestampFromIndex(index).plus(timeout).plus(REQUEST_TIMEOUT_GRACE);
    }

    private void publishIgnoreMessageMetric(DeserializingMessage message, Executable executable, Class<?> targetClass) {
        try {
            String consumer = Tracker.current().map(Tracker::getName)
                    .orElseGet(() -> "local-" + message.getMessageType());
            Fluxzero.getOptionally().ifPresent(fc -> fc.metricsGateway().publish(new IgnoreMessageEvent(
                    consumer, targetClass.getSimpleName(), message.getIndex(), message.getMessageType(),
                    message.getTopic(), formatType(message, executable, targetClass),
                    IgnoreMessageEvent.EXPIRED_REQUEST)));
        } catch (Exception e) {
            log.error("Failed to publish ignore message metrics", e);
        }
    }

    private String formatType(DeserializingMessage message, Executable executable, Class<?> targetClass) {
        if (message.getMessageType() == WEBREQUEST) {
            try {
                var webPatterns = WebUtils.getWebPatterns(targetClass, null, executable);
                String uriPattern = webPatterns.size() == 1
                        ? webPatterns.getFirst().getUri() : WebRequest.getUrl(message.getMetadata());
                return "%s %s".formatted(WebRequest.getMethod(message.getMetadata()), uriPattern);
            } catch (Exception ignored) {}
        }
        return message.getType();
    }

    @Value
    static class HandleAnnotation {
        boolean skipExpiredRequests;
    }
}
