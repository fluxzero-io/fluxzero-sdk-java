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

    public DefaultHandler(Class<?> targetClass, Function<M, ?> targetSupplier,
                          HandlerMatcher<Object, M> handlerMatcher) {
        this(targetClass, targetSupplier, handlerMatcher, null);
    }

    public static <M> DefaultHandler<M> forTarget(Class<?> targetClass, Object target,
                                                  HandlerMatcher<Object, M> handlerMatcher) {
        return new DefaultHandler<>(targetClass, ignored -> target, handlerMatcher,
                                    handlerMatcher.bindHandlerMethod(target));
    }

    private DefaultHandler(Class<?> targetClass, Function<M, ?> targetSupplier, HandlerMatcher<Object, M> handlerMatcher,
                           HandlerMethod<M> handlerMethod) {
        this.targetClass = targetClass;
        this.targetSupplier = targetSupplier;
        this.handlerMatcher = handlerMatcher;
        this.handlerMethod = handlerMethod;
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
    public String toString() {
        String simpleName = targetClass.getSimpleName();
        return String.format("\"%s\"", simpleName.isEmpty() ? "DefaultHandler" : simpleName);
    }
}
