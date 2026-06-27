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

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.function.Function;

/**
 * Mechanism to resolve method parameters in message handler methods (e.g. those annotated with {@code HandleEvent},
 * {@code HandleCommand}, etc.).
 * <p>
 * A {@code ParameterResolver} determines how to inject values into the parameters of a handler method based on the
 * message being handled, the parameter type, and other context. This allows custom logic to inject fields such as the
 * {@code payload}, {@code metadata}, authenticated {@code User}, or other derived values.
 *
 * <p>
 * Custom {@code ParameterResolver ParameterResolvers} can be registered with a {@code FluxzeroBuilder} to influence
 * handler invocation logic across one or more message types.
 *
 * @param <M> the type of message object used by the handler invocation context, often {@code DeserializingMessage}
 */
@FunctionalInterface
public interface ParameterResolver<M> {

    /**
     * Resolves a {@link Parameter} of a handler method into a value function based on the given message.
     * <p>
     * If the parameter cannot be resolved by this resolver and {@link #matches} is not implemented, this method must
     * return {@code null}.
     *
     * @param parameter        the parameter to resolve
     * @param methodAnnotation the annotation present on the handler method (e.g., {@code @HandleEvent})
     * @return a function that takes a message and returns a value to be injected into the method parameter, or
     * {@code null} if the parameter cannot be resolved and {@link #matches} is not implemented.
     */
    Function<M, Object> resolve(Parameter parameter, Annotation methodAnnotation);

    /**
     * Resolves a handler parameter from a metadata view.
     * <p>
     * Existing JVM resolvers continue to implement {@link #resolve(Parameter, Annotation)}. Generated metadata
     * backends can override this method to avoid depending on a JVM {@link Parameter}.
     *
     * @param parameter        the parameter metadata view
     * @param methodAnnotation the annotation present on the handler method
     * @return a resolver function, or {@code null} if the parameter cannot be resolved
     */
    default Function<M, Object> resolve(ParameterView parameter, Annotation methodAnnotation) {
        return parameter.parameter().map(p -> resolve(p, methodAnnotation)).orElse(null);
    }

    /**
     * Prepares a resolver function when this resolver can determine compatibility from the parameter and method
     * annotation alone.
     * <p>
     * The default returns {@code null}, which keeps resolution message-dependent. Implementations should override this
     * only when {@link #matches(Parameter, Annotation, Object)} would always be {@code true} for the supplied parameter,
     * regardless of the message instance. Dynamic resolvers such as payload, trigger, or entity resolvers should keep
     * the default behavior.
     *
     * @param parameter        the parameter to resolve
     * @param methodAnnotation the annotation present on the handler method
     * @return a prepared resolver, or {@code null} when resolution must remain message-dependent
     */
    default Function<M, Object> prepare(Parameter parameter, Annotation methodAnnotation) {
        return null;
    }

    /**
     * Prepares a resolver function from a metadata parameter view.
     *
     * @param parameter        the parameter metadata view
     * @param methodAnnotation the annotation present on the handler method
     * @return a prepared resolver, or {@code null} when resolution must remain message-dependent
     */
    default Function<M, Object> prepare(ParameterView parameter, Annotation methodAnnotation) {
        return parameter.parameter().map(p -> prepare(p, methodAnnotation)).orElse(null);
    }

    /**
     * Indicates whether the resolved value is compatible with the declared parameter type.
     * <p>
     * This method helps determine whether the parameter can be injected for a given message. It first invokes
     * {@link #resolve} and then verifies that the returned value (if any) is assignable to the parameter type.
     *
     * @param parameter        the parameter being checked
     * @param methodAnnotation the annotation on the handler method
     * @param value            the message instance to use for resolution
     * @return {@code true} if the parameter can be resolved and assigned to, {@code false} otherwise
     */
    default boolean matches(Parameter parameter, Annotation methodAnnotation, M value) {
        Function<M, Object> function = resolve(parameter, methodAnnotation);
        if (function == null) {
            return false;
        }
        Object parameterValue = function.apply(value);
        return parameterValue == null || parameter.getType().isAssignableFrom(parameterValue.getClass());
    }

    /**
     * Indicates whether the resolved value is compatible with the declared metadata parameter view.
     *
     * @param parameter        the parameter being checked
     * @param methodAnnotation the annotation on the handler method
     * @param value            the message instance to use for resolution
     * @return {@code true} if the parameter can be resolved and assigned to, {@code false} otherwise
     */
    default boolean matches(ParameterView parameter, Annotation methodAnnotation, M value) {
        if (parameter.parameter().isPresent()) {
            return matches(parameter.parameter().orElseThrow(), methodAnnotation, value);
        }
        Function<M, Object> function = resolve(parameter, methodAnnotation);
        if (function == null) {
            return false;
        }
        return parameter.isAssignableFrom(function.apply(value));
    }

    /**
     * Indicates whether this resolver contributes to determining handler method specificity when multiple handler
     * candidates are available.
     * <p>
     * If {@code true}, the resolver will participate in determining the most specific handler for a message. This is
     * relevant when the system must choose between overlapping handler signatures, e.g. for a resolver of a message's
     * payload.
     *
     * @return {@code true} if this resolver influences specificity decisions, {@code false} otherwise
     */
    default boolean determinesSpecificity() {
        return false;
    }

    /**
     * Relative priority used when multiple specificity-determining resolvers could explain a matching handler.
     * <p>
     * Lower values win over higher values. This matches Fluxzero's regular ordering conventions and makes it possible
     * to prefer semantically stronger matches, such as a
     * direct payload parameter, over broader contextual injections such as an entity value.
     *
     * @return resolver priority for specificity comparisons; lower means more specific
     */
    default int specificityPriority() {
        return 0;
    }

    /**
     * Determines whether a given message should be passed to a handler method based on this parameter's
     * characteristics.
     * <p>
     * This hook is used after {@link #matches} is invoked but before {@link #resolve} and can thus be used to prevent
     * other parameter resolvers from supplying a candidate for parameter injection.
     *
     * @param message   the message being evaluated
     * @param parameter the method parameter to test
     * @return {@code true} if the message should be processed, {@code false} if it should be filtered out
     */
    default boolean test(M message, Parameter parameter) {
        return true;
    }

    /**
     * Determines whether a given message should be passed to a handler method based on this metadata parameter view.
     *
     * @param message   the message being evaluated
     * @param parameter the parameter metadata view to test
     * @return {@code true} if the message should be processed, {@code false} if it should be filtered out
     */
    default boolean test(M message, ParameterView parameter) {
        return parameter.parameter().map(p -> test(message, p)).orElse(true);
    }

    /**
     * Returns {@code true} if this resolver might apply to the given method. Implementations should perform only
     * inexpensive checks and never throw.
     *
     * @param method      the handler method or constructor
     * @param targetClass the declaring or target class
     * @return {@code true} if this resolver could apply, {@code false} otherwise
     */
    default boolean mayApply(Executable method, Class<?> targetClass) {
        return true;
    }

    /**
     * Returns {@code true} if this resolver might apply to the given executable metadata view.
     *
     * @param executable  the handler executable metadata view
     * @param targetClass the declaring or target class when available
     * @return {@code true} if this resolver could apply, {@code false} otherwise
     */
    default boolean mayApply(ExecutableView executable, Class<?> targetClass) {
        return executable.executable().map(method -> mayApply(method, targetClass)).orElse(true);
    }
}
