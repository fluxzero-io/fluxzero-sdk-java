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

package io.fluxzero.common.handling;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Function;

/**
 * Default implementation of the {@link Handler} interface.
 */
@Slf4j
public class DefaultHandler<M> implements Handler<M> {
    private final Class<?> targetClass;
    private final Function<M, ?> targetSupplier;
    private final HandlerMatcher<Object, M> handlerMatcher;
    private final HandlerMethod<M> handlerMethod;
    private final HandlerMethodPlanner<M> handlerMethodPlanner;

    public DefaultHandler(Class<?> targetClass, Function<M, ?> targetSupplier,
                          HandlerMatcher<Object, M> handlerMatcher) {
        this(targetClass, targetSupplier, handlerMatcher, null, null);
    }

    public static <M> DefaultHandler<M> forTarget(Class<?> targetClass, Object target,
                                                  HandlerMatcher<Object, M> handlerMatcher) {
        return new DefaultHandler<>(targetClass, ignored -> target, handlerMatcher,
                                    handlerMatcher.bindHandlerMethod(target),
                                    handlerMatcher.bindHandlerMethodPlanner(target));
    }

    /**
     * Creates a handler for methods declared on the message payload itself.
     *
     * <p>Prepared invocations obtain their target from {@link HandlerInput#getPayload()} each time, so a cached plan
     * never retains an earlier payload instance. If an invocation cannot use the prepared path, the supplied fallback
     * resolves the target from the complete message.</p>
     *
     * @param targetClass the payload type that declares the handler methods
     * @param fallbackTargetSupplier function that obtains the handler target from a complete message
     * @param handlerMatcher the matcher used to select a handler method
     * @param <M> the message type used by the handling pipeline
     * @return a handler for methods whose invocation target is the current payload
     */
    public static <M> DefaultHandler<M> forPayloadTarget(
            Class<?> targetClass, Function<M, ?> fallbackTargetSupplier,
            HandlerMatcher<Object, M> handlerMatcher) {
        return new DefaultHandler<>(targetClass, fallbackTargetSupplier, handlerMatcher, null,
                                    handlerMatcher.bindPayloadHandlerMethodPlanner());
    }

    private DefaultHandler(Class<?> targetClass, Function<M, ?> targetSupplier, HandlerMatcher<Object, M> handlerMatcher,
                           HandlerMethod<M> handlerMethod, HandlerMethodPlanner<M> handlerMethodPlanner) {
        this.targetClass = targetClass;
        this.targetSupplier = targetSupplier;
        this.handlerMatcher = handlerMatcher;
        this.handlerMethod = handlerMethod;
        this.handlerMethodPlanner = handlerMethodPlanner;
    }

    @Override
    public Class<?> getTargetClass() {
        return targetClass;
    }

    @Override
    public Optional<HandlerInvoker> getInvoker(M message) {
        return Optional.ofNullable(getInvokerOrNull(message));
    }

    @Override
    public HandlerInvoker getInvokerOrNull(M message) {
        return handlerMatcher.getInvokerOrNull(targetSupplier.apply(message), message);
    }

    @Override
    public HandlerMethod<M> getHandlerMethodOrNull(M message) {
        return handlerMethod != null && handlerMethod.canHandle(message) ? handlerMethod : null;
    }

    @Override
    public HandlerMethodPlan<M> getHandlerMethodPlanOrNull(M message) {
        return handlerMethodPlanner == null ? null : handlerMethodPlanner.prepare(message).plan();
    }

    @Override
    public HandlerMethodPlanner<M> getHandlerMethodPlanner() {
        return handlerMethodPlanner;
    }

    @Override
    public String toString() {
        String simpleName = targetClass.getSimpleName();
        return String.format("\"%s\"", simpleName.isEmpty() ? "DefaultHandler" : simpleName);
    }
}
