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

package io.fluxzero.sdk.browser;

import io.fluxzero.common.MessageType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory browser message bus used by generated dispatch code and conformance tests.
 */
public final class BrowserMessageBus {
    private final Clock clock;
    private final List<BrowserHandlerRegistration> registrations = new ArrayList<>();
    private final List<BrowserDispatchInterceptor> dispatchInterceptors = new ArrayList<>();
    private final List<BrowserHandlerInterceptor> handlerInterceptors = new ArrayList<>();
    private final List<BrowserBatchInterceptor> batchInterceptors = new ArrayList<>();
    private final List<BrowserErrorReporter> errorReporters = new ArrayList<>();
    private final List<BrowserMessage> emittedMessages = new ArrayList<>();
    private final List<String> invocations = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private boolean recursivePublicationGuard;
    private boolean dispatching;
    private int recursiveDispatchesBlocked;

    public BrowserMessageBus(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void register(BrowserHandlerRegistration registration) {
        registrations.add(Objects.requireNonNull(registration, "registration"));
    }

    public void addDispatchInterceptor(BrowserDispatchInterceptor interceptor) {
        dispatchInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    public void addHandlerInterceptor(BrowserHandlerInterceptor interceptor) {
        handlerInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    public void addBatchInterceptor(BrowserBatchInterceptor interceptor) {
        batchInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    public void addErrorReporter(BrowserErrorReporter errorReporter) {
        errorReporters.add(Objects.requireNonNull(errorReporter, "errorReporter"));
    }

    public void enableRecursivePublicationGuard() {
        recursivePublicationGuard = true;
    }

    public Object dispatch(MessageType messageType, Object payload) {
        return dispatch(messageType, "", payload, Map.of());
    }

    public Object dispatch(MessageType messageType, String payloadTypeName, Object payload) {
        return dispatch(messageType, "", payloadTypeName, payload, Map.of());
    }

    public Object dispatch(MessageType messageType, String topic, Object payload, Map<String, String> metadata) {
        BrowserMessage message = BrowserMessage.of(messageType, topic, payload, metadata, clock.instant());
        return dispatch(message);
    }

    public Object dispatch(MessageType messageType, String topic, String payloadTypeName, Object payload,
                           Map<String, String> metadata) {
        BrowserMessage message = BrowserMessage.of(messageType, topic, payload, payloadTypeName, metadata,
                                                   clock.instant());
        return dispatch(message);
    }

    public Object dispatch(BrowserMessage message) {
        Objects.requireNonNull(message, "message");
        if (recursivePublicationGuard && dispatching) {
            recursiveDispatchesBlocked++;
            return null;
        }
        BrowserMessage intercepted = interceptDispatch(message);
        if (intercepted == null) {
            return null;
        }
        emittedMessages.add(intercepted);
        dispatching = true;
        try {
            List<Object> results = publish(intercepted);
            return results.isEmpty() ? null : results.get(results.size() - 1);
        } finally {
            dispatching = false;
        }
    }

    public List<BrowserMessage> processBatch(List<BrowserMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        List<BrowserMessage> result = List.copyOf(messages);
        for (BrowserBatchInterceptor interceptor : batchInterceptors) {
            result = List.copyOf(interceptor.intercept(result));
        }
        return result;
    }

    public int recursiveDispatchesBlocked() {
        return recursiveDispatchesBlocked;
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    private BrowserMessage interceptDispatch(BrowserMessage message) {
        BrowserMessage current = message;
        for (BrowserDispatchInterceptor interceptor : dispatchInterceptors) {
            if (current == null) {
                return null;
            }
            current = interceptor.intercept(current);
        }
        return current;
    }

    private BrowserHandler interceptedHandler(BrowserHandler handler) {
        BrowserHandler current = handler;
        for (int i = handlerInterceptors.size() - 1; i >= 0; i--) {
            BrowserHandler next = current;
            BrowserHandlerInterceptor interceptor = handlerInterceptors.get(i);
            current = message -> interceptor.intercept(message, next);
        }
        return current;
    }

    private void reportError(RuntimeException error, BrowserMessage message) {
        errors.add("handler-error");
        for (BrowserErrorReporter errorReporter : errorReporters) {
            errorReporter.report(error, message);
        }
    }

    private Object invoke(BrowserHandlerRegistration registration, BrowserMessage message) {
        try {
            return interceptedHandler(registration.handler()).handle(message);
        } catch (RuntimeException e) {
            reportError(e, message);
            throw e;
        }
    }

    public List<Object> publish(BrowserMessage message) {
        Objects.requireNonNull(message, "message");
        List<Object> results = new ArrayList<>();
        for (BrowserHandlerRegistration registration : registrations) {
            if (registration.matches(message)) {
                invocations.add(registration.feature() + ":" + message.messageType() + ":" + message.payloadTypeName());
                Object result = invoke(registration, message);
                if (!registration.passive() && result != null) {
                    results.add(result);
                }
            }
        }
        return results;
    }

    public List<BrowserMessage> emittedMessages() {
        return List.copyOf(emittedMessages);
    }

    public List<String> invocations() {
        return List.copyOf(invocations);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("messages", emittedMessages.size());
        snapshot.put("handlers", registrations.size());
        snapshot.put("metadataHandlers", registrations.stream().filter(BrowserHandlerRegistration::metadataBacked)
                .count());
        snapshot.put("dispatchInterceptors", dispatchInterceptors.size());
        snapshot.put("handlerInterceptors", handlerInterceptors.size());
        snapshot.put("batchInterceptors", batchInterceptors.size());
        snapshot.put("errors", errors());
        snapshot.put("recursiveDispatchesBlocked", recursiveDispatchesBlocked);
        snapshot.put("invocations", invocations());
        return snapshot;
    }
}
