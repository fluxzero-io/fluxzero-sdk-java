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

import io.fluxzero.common.handling.ExecutableAnnotationResolver;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import lombok.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Comparator;
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
    private static final Comparator<Class<?>> CLASS_SPECIFICITY_COMPARATOR = PayloadFilter::compareSpecificity;
    private final ExecutableAnnotationResolver annotationResolver;

    public PayloadFilter() {
        this(MetadataExecutableAnnotationResolver.create());
    }

    PayloadFilter(JvmComponentIntrospector introspector) {
        this(MetadataExecutableAnnotationResolver.create());
    }

    PayloadFilter(JvmComponentIntrospector introspector, ExecutableAnnotationResolver annotationResolver) {
        this(annotationResolver);
    }

    PayloadFilter(ExecutableAnnotationResolver annotationResolver) {
        this.annotationResolver = Objects.requireNonNull(annotationResolver, "annotationResolver");
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
                .max(CLASS_SPECIFICITY_COMPARATOR).orElse(null);
        return new PreparedPayloadFilter(allowedClasses, leastSpecificAllowedClass);
    }

    @Override
    public MessageFilter<? super HasMessage> prepare(ExecutableView executable,
                                                     Class<? extends Annotation> handlerAnnotation,
                                                     Class<?> targetClass) {
        HandleAnnotation annotation = lookupHandleAnnotation(executable, handlerAnnotation);
        if (annotation == null || annotation.getAllowedClasses().isEmpty()) {
            return ALLOW_ALL;
        }
        Class<?>[] allowedClasses = annotation.getAllowedClasses().toArray(Class[]::new);
        Class<?> leastSpecificAllowedClass = Arrays.stream(allowedClasses)
                .max(CLASS_SPECIFICITY_COMPARATOR).orElse(null);
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
    public boolean test(HasMessage message, ExecutableView executable, Class<? extends Annotation> handlerAnnotation,
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
                        .max(CLASS_SPECIFICITY_COMPARATOR));
    }

    @Override
    public Optional<Class<?>> getLeastSpecificAllowedClass(ExecutableView executable,
                                                           Class<? extends Annotation> handlerAnnotation) {
        return Optional.ofNullable(lookupHandleAnnotation(executable, handlerAnnotation))
                .flatMap(a -> a.getAllowedClasses().stream()
                        .max(CLASS_SPECIFICITY_COMPARATOR));
    }

    private HandleAnnotation lookupHandleAnnotation(Executable executable,
                                                    Class<? extends Annotation> handlerAnnotation) {
        if (annotationResolver instanceof MetadataExecutableAnnotationResolver metadataResolver) {
            Optional<HandleAnnotation> metadata = metadataResolver.getAnnotationAs(
                    executable, handlerAnnotation, HandleAnnotation.class);
            if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
                return metadata.orElse(null);
            }
        }
        return annotationResolver.getAnnotation(executable, handlerAnnotation)
                .flatMap(annotation -> JvmCompatibilityBackend.introspector()
                        .getAnnotationAs(annotation, handlerAnnotation, HandleAnnotation.class))
                .orElse(null);
    }

    private HandleAnnotation lookupHandleAnnotation(ExecutableView executable,
                                                    Class<? extends Annotation> handlerAnnotation) {
        if (annotationResolver instanceof MetadataExecutableAnnotationResolver metadataResolver) {
            Optional<HandleAnnotation> metadata = metadataResolver.getAnnotationAs(
                    executable, handlerAnnotation, HandleAnnotation.class);
            if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
                return metadata.orElse(null);
            }
        }
        Optional<Executable> method = executable.executable();
        if (method.isPresent()) {
            return lookupHandleAnnotation(method.orElseThrow(), handlerAnnotation);
        }
        return executable.annotation(handlerAnnotation)
                .flatMap(annotation -> JvmCompatibilityBackend.introspector()
                        .getAnnotationAs(annotation, handlerAnnotation, HandleAnnotation.class))
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
        public boolean test(HasMessage message, ExecutableView executable,
                            Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
            return allowedClasses.length == 0 || allowed(allowedClasses, message.getPayloadClass());
        }

        @Override
        public Optional<Class<?>> getLeastSpecificAllowedClass(Executable executable,
                                                               Class<? extends Annotation> handlerAnnotation) {
            return Optional.ofNullable(leastSpecificAllowedClass);
        }

        @Override
        public Optional<Class<?>> getLeastSpecificAllowedClass(ExecutableView executable,
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

    private static int compareSpecificity(Class<?> left, Class<?> right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        if (left.isAssignableFrom(right)) {
            return 1;
        }
        if (right.isAssignableFrom(left)) {
            return -1;
        }
        if (left.isInterface() && !right.isInterface()) {
            return 1;
        }
        if (!left.isInterface() && right.isInterface()) {
            return -1;
        }
        return specificity(right) - specificity(left);
    }

    private static int specificity(Class<?> type) {
        int depth = 0;
        Class<?> current = type;
        if (type.isInterface()) {
            while (current.getInterfaces().length > 0) {
                depth++;
                current = current.getInterfaces()[0];
            }
        } else {
            while (current != null) {
                depth++;
                current = current.getSuperclass();
            }
        }
        return depth;
    }

    @Value
    static class HandleAnnotation {
        List<Class<?>> allowedClasses;
    }
}
