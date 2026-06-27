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

import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import lombok.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link MessageFilter} used to restrict message handling based on the payload type.
 *
 * <p>This filter determines whether a handler method should be invoked based on whether the incoming message's payload
 * class is compatible with the types explicitly allowed in the method's handler annotation (e.g.,
 * {@code @HandleCommand}, {@code @HandleQuery}, etc.) via the {@code allowedClasses} method.
 *
 * <p>The filtering logic checks if the method has a supported handler annotation and whether the annotation defines
 * a non-empty set of allowed payload types. If so, the message's payload class must be assignable to at least one of
 * those allowed classes for the handler to be eligible.
 *
 * <p>If no types are specified in the annotation, the handler is assumed to be permissive and accepts all types. In
 * those cases a method is typically filtered by the {@link PayloadParameterResolver} instead.
 *
 * <h2>Example Usage</h2>
 * Given a handler:
 * <pre>{@code
 * @HandleCommand(allowedClasses = {CommandA.class, CommandB.class})
 * public void handle(DeserializingMessage message) { ... }
 * }</pre>
 * This filter ensures that only messages with a `CommandA` or `CommandB` payload (or their subtypes) are dispatched to
 * the method.
 *
 * @see HasMessage#getPayloadClass()
 * @see JvmComponentIntrospector#typeSpecificityComparator()
 */
public class PayloadFilter implements MessageFilter<HasMessage> {

    private static final MessageFilter<HasMessage> ALLOW_ALL = MessageFilter.allowAll();
    private final JvmComponentIntrospector introspector;

    public PayloadFilter() {
        this(JvmComponentIntrospector.getInstance());
    }

    PayloadFilter(JvmComponentIntrospector introspector) {
        this.introspector = Objects.requireNonNull(introspector, "introspector");
    }

    @Override
    public MessageFilter<? super HasMessage> prepare(Executable executable,
                                                     Class<? extends Annotation> handlerAnnotation,
                                                     Class<?> targetClass) {
        HandleAnnotation annotation = lookupHandleAnnotation(executable, handlerAnnotation);
        if (annotation == null || annotation.getAllowedClasses().isEmpty()) {
            return ALLOW_ALL;
        }
        Class<?>[] allowedClasses = annotation.getAllowedClasses().toArray(Class[]::new);
        Class<?> leastSpecificAllowedClass = Arrays.stream(allowedClasses)
                .max(introspector.typeSpecificityComparator()).orElse(null);
        return new PreparedPayloadFilter(allowedClasses, leastSpecificAllowedClass);
    }

    @Override
    public boolean test(HasMessage message, Executable executable, Class<? extends Annotation> handlerAnnotation,
                        Class<?> targetClass) {
        HandleAnnotation annotation = lookupHandleAnnotation(executable, handlerAnnotation);
        if (annotation == null || annotation.getAllowedClasses().isEmpty()) {
            return true;
        }
        return allowed(annotation.getAllowedClasses().toArray(Class[]::new), message.getPayloadClass());
    }

    @Override
    public Optional<Class<?>> getLeastSpecificAllowedClass(Executable executable,
                                                           Class<? extends Annotation> handlerAnnotation) {
        return Optional.ofNullable(lookupHandleAnnotation(executable, handlerAnnotation))
                .flatMap(a -> a.getAllowedClasses().stream()
                        .max(introspector.typeSpecificityComparator()));
    }

    private HandleAnnotation lookupHandleAnnotation(Executable executable,
                                                    Class<? extends Annotation> handlerAnnotation) {
        return introspector.executableAnnotationAs(executable, handlerAnnotation, HandleAnnotation.class)
                .orElse(null);
    }

    private record PreparedPayloadFilter(Class<?>[] allowedClasses, Class<?> leastSpecificAllowedClass)
            implements MessageFilter<HasMessage> {

        @Override
        public boolean test(HasMessage message, Executable executable, Class<? extends Annotation> handlerAnnotation,
                            Class<?> targetClass) {
            return allowedClasses.length == 0 || allowed(allowedClasses, message.getPayloadClass());
        }

        @Override
        public Optional<Class<?>> getLeastSpecificAllowedClass(Executable executable,
                                                               Class<? extends Annotation> handlerAnnotation) {
            return Optional.ofNullable(leastSpecificAllowedClass);
        }
    }

    private static boolean allowed(Class<?>[] allowedClasses, Class<?> payloadClass) {
        for (Class<?> allowedClass : allowedClasses) {
            if (allowedClass.isAssignableFrom(payloadClass)) {
                return true;
            }
        }
        return false;
    }

    @Value
    static class HandleAnnotation {
        List<Class<?>> allowedClasses;
    }
}
