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

package io.fluxzero.sdk.tracking.handling.contentfiltering;

import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AllArgsConstructor;

import java.lang.reflect.Executable;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.common.reflection.ReflectionUtils.getTypeAnnotation;
import static java.util.Optional.ofNullable;

@AllArgsConstructor
public class ContentFilterInterceptor implements HandlerInterceptor {
    private static final BiFunction<Class<?>, Executable, FilterContent> filterContentCache = memoize(
            (payloadClass, executable) ->
                    ReflectionUtils.<FilterContent>getMethodAnnotation(executable, FilterContent.class)
                            .or(() -> ofNullable(getTypeAnnotation(payloadClass, FilterContent.class)))
                            .or(() -> ReflectionUtils.getPackageAnnotation(payloadClass.getPackage(), FilterContent.class))
                            .orElse(null));

    private final Serializer serializer;

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        var filterContent = filterContentCache.apply(invoker.getTargetClass(), invoker.getMethod());
        if (filterContent == null) {
            return function;
        }
        return m -> serializer.filterContent(function.apply(m), User.getCurrent());
    }
}
