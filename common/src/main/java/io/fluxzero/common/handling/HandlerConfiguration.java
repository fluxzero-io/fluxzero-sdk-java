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

package io.fluxzero.common.handling;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.Accessors;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Configuration object used to define how message handler methods are selected and filtered for a given message type.
 * <p>
 * This configuration is passed to {@link HandlerInspector} and {@link HandlerMatcher} instances to determine:
 * <ul>
 *   <li>Which methods should be considered as valid message handlers,</li>
 *   <li>Whether multiple methods on the same target are allowed to handle the same message,</li>
 *   <li>And whether a particular message is eligible for handling by a given method.</li>
 * </ul>
 *
 * <p>The generic type {@code M} represents the expected message wrapper type (e.g. {@code HasMessage} or a subclass).
 *
 * @param <M> the message wrapper type this configuration applies to
 */
@Value
@Builder(toBuilder = true)
@Accessors(fluent = true)
public class HandlerConfiguration<M> {
    private static final ConcurrentMap<Class<? extends Annotation>, Optional<Method>> disabledMethods =
            new ConcurrentHashMap<>();

    /**
     * The annotation type that marks a method as a handler (e.g. {@code @HandleCommand}, {@code @HandleQuery}).
     * If {@code null}, all methods are considered eligible for inspection.
     */
    Class<? extends Annotation> methodAnnotation;

    /**
     * If {@code true}, allows multiple methods in the same class to handle a single message.
     * <p>
     * If {@code false}, only the first matching handler (by inspection order) will be invoked.
     */
    @Default
    boolean invokeMultipleMethods = false;

    /**
     * Optional comparator used to order methods that have the same annotation priority. Returning {@code 0} preserves
     * the default specificity and inspection-order tie-breakers.
     */
    @Default
    Comparator<Executable> samePriorityMethodComparator = (left, right) -> 0;

    /**
     * A filter applied to candidate methods to determine if they are valid handlers for the given context.
     * <p>
     * The filter receives the owning class and the method as inputs. By default, all methods are accepted.
     */
    @Default
    HandlerFilter handlerFilter = (c, e) -> true;

    /**
     * A filter applied at runtime to check if a given message should be routed to the specified method.
     * <p>
     * This is used after the handler method has been identified and may use logic such as payload class matching,
     * topic filtering, or segment-based routing.
     */
    @Default
    MessageFilter<? super M> messageFilter = MessageFilter.allowAll();

    /**
     * Optional hook to validate resolved arguments before invoking a handler method.
     */
    @Default
    MethodInvocationValidator<? super M> methodInvocationValidator = MethodInvocationValidator.noOp();

    /**
     * Backend used to prepare handler executable invocation.
     */
    @Default
    ExecutableInvocationBackend executableInvocationBackend = ExecutableInvocationBackend.reflection();

    /**
     * Resolver used to read semantic handler annotations from an executable.
     */
    @Default
    ExecutableAnnotationResolver executableAnnotationResolver = ExecutableAnnotationResolver.reflection();

    /**
     * Determines whether the given method is eligible to handle messages according to this configuration.
     * <p>
     * This includes both:
     * <ul>
     *   <li>Checking that the method is annotated with {@link #methodAnnotation} (if applicable), and</li>
     *   <li>That it passes the {@link #handlerFilter}.</li>
     * </ul>
     *
     * @param c the class owning the method
     * @param e the method to check
     * @return {@code true} if the method should be included as a handler
     */
    public boolean methodMatches(Class<?> c, Executable e) {
        return isEnabled(e) && handlerFilter.test(c, e);
    }

    /**
     * Determines whether the given executable metadata view is eligible to handle messages according to this
     * configuration.
     *
     * @param c the class owning the method, when available
     * @param e the executable metadata view to check
     * @return {@code true} if the executable should be included as a handler
     */
    public boolean methodMatches(Class<?> c, ExecutableView e) {
        return isEnabled(e) && handlerFilter.test(c, e);
    }

    /**
     * Determines whether the given method is "enabled" based on the presence of {@link #methodAnnotation}
     * and whether the annotation declares a {@code disabled()} attribute.
     * <p>
     * If the annotation exists and its {@code disabled()} attribute evaluates to {@code true}, the method is skipped.
     *
     * @param e the method to evaluate
     * @return {@code true} if the method is enabled and can be considered as a handler
     */
    @SneakyThrows
    boolean isEnabled(Executable e) {
        if (methodAnnotation == null) {
            return true;
        }
        var annotation = getAnnotation(e).orElse(null);
        if (annotation == null) {
            return false;
        }
        Optional<Method> match = disabledMethod(annotation.annotationType());
        if (match.isPresent()) {
            var result = (boolean) match.get().invoke(annotation);
            return !result;
        }
        return true;
    }

    /**
     * Determines whether the given executable metadata view is enabled.
     *
     * @param e the executable metadata view to evaluate
     * @return {@code true} if the method is enabled and can be considered as a handler
     */
    boolean isEnabled(ExecutableView e) {
        if (methodAnnotation == null) {
            return true;
        }
        var annotation = getAnnotation(e).orElse(null);
        if (annotation == null) {
            return false;
        }
        Optional<Method> match = disabledMethod(annotation.annotationType());
        if (match.isPresent()) {
            var result = silentBoolean(() -> (boolean) match.get().invoke(annotation));
            return !result;
        }
        return true;
    }

    /**
     * Retrieves the configured method annotation on the given method, if present.
     *
     * @param e the method to inspect
     * @return an optional containing the annotation if present, or empty otherwise
     */
    public Optional<? extends Annotation> getAnnotation(Executable e) {
        return methodAnnotation == null ? Optional.empty()
                : executableAnnotationResolver.getAnnotation(e, methodAnnotation);
    }

    /**
     * Retrieves the configured method annotation on the given executable metadata view, if present.
     *
     * @param e the executable metadata view to inspect
     * @return an optional containing the annotation if present, or empty otherwise
     */
    public Optional<? extends Annotation> getAnnotation(ExecutableView e) {
        if (methodAnnotation == null) {
            return Optional.empty();
        }
        Optional<? extends Annotation> reflectionAnnotation = e.executable()
                .flatMap(method -> executableAnnotationResolver.getAnnotation(method, methodAnnotation));
        if (reflectionAnnotation.isPresent()) {
            return reflectionAnnotation;
        }
        @SuppressWarnings("unchecked")
        Class<Annotation> annotationType = (Class<Annotation>) methodAnnotation;
        return e.annotation(annotationType);
    }

    private static Optional<Method> disabledMethod(Class<? extends Annotation> annotationType) {
        return disabledMethods.computeIfAbsent(annotationType, HandlerConfiguration::findDisabledMethod);
    }

    private static Optional<Method> findDisabledMethod(Class<? extends Annotation> annotationType) {
        return Arrays.stream(annotationType.getMethods())
                .filter(m -> m.getName().equals("disabled")).findFirst();
    }

    @SneakyThrows
    private static boolean silentBoolean(BooleanSupplier supplier) {
        return supplier.getAsBoolean();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
