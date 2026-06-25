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

package io.fluxzero.sdk.execution;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.HandlerRouteMatcher;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;

class LazyExecutionHandler implements Handler<DeserializingMessage>, AutoCloseable {
    private final LazyExecutionUnit unit;
    private final HandlerRoute route;
    private final HandlerFactory handlerFactory;

    LazyExecutionHandler(LazyExecutionUnit unit, HandlerRoute route, HandlerFactory handlerFactory) {
        this.unit = unit;
        this.route = route;
        this.handlerFactory = handlerFactory;
    }

    @Override
    public Class<?> getTargetClass() {
        return LazyExecutionHandler.class;
    }

    @Override
    public java.util.Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
        return java.util.Optional.ofNullable(getInvokerOrNull(message));
    }

    @Override
    public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
        if (!canHandle(message)) {
            return null;
        }
        return delegate().getInvokerOrNull(message);
    }

    @Override
    public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
        if (!canHandle(message)) {
            return null;
        }
        return delegate().getHandlerMethodOrNull(message);
    }

    private boolean canHandle(DeserializingMessage message) {
        return HandlerRouteMatcher.canHandle(route, message);
    }

    private Handler<DeserializingMessage> delegate() {
        return unit.handler(route.messageType(), handlerFactory);
    }

    void prewarm() {
        delegate();
    }

    OnDemandCompiler.CompilationRequest compilationRequestIfNeeded() {
        return unit.compilationRequestIfNeeded();
    }

    OnDemandCompiler compiler() {
        return unit.compiler();
    }

    LazyExecutionUnit unit() {
        return unit;
    }

    @Override
    public void close() {
        unit.close();
    }

    @Override
    public String toString() {
        return "\"OnDemandExecution:%s:%s\"".formatted(route.messageType(), unit.component().fullClassName());
    }

    MessageType messageType() {
        return route.messageType();
    }

    boolean local() {
        return route.local();
    }

    boolean tracked() {
        return route.tracked();
    }
}
