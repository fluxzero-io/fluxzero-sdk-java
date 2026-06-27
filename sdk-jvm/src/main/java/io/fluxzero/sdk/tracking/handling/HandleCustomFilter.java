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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Objects;

/**
 * A {@link MessageFilter} implementation that filters {@link DeserializingMessage} instances
 * based on a custom topic defined in the {@link HandleCustom} annotation on handler methods.
 *
 * <p>This filter ensures that a message is only routed to handler methods annotated with
 * {@code @HandleCustom} if the topic of the message matches the value defined in the annotation.
 *
 * @see HandleCustom
 * @see DeserializingMessage
 */
public class HandleCustomFilter implements MessageFilter<DeserializingMessage> {
    private final JvmComponentIntrospector introspector = JvmComponentIntrospector.getInstance();

    @Override
    public boolean test(DeserializingMessage message, Executable executable,
                        Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
        return introspector.executableAnnotation(executable, HandleCustom.class)
                .map(c -> Objects.equals(customTopic(c), message.getTopic()))
                .orElse(false);
    }

    @Override
    public boolean test(DeserializingMessage message, ExecutableView executable,
                        Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
        return executable.executable()
                .flatMap(method -> introspector.executableAnnotation(method, HandleCustom.class))
                .or(() -> executable.annotation(HandleCustom.class))
                .map(c -> Objects.equals(customTopic(c), message.getTopic()))
                .orElse(false);
    }

    private static String customTopic(HandleCustom annotation) {
        return annotation.value().isBlank() ? annotation.topic() : annotation.value();
    }
}
