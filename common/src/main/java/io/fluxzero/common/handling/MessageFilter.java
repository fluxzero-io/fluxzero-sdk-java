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

import lombok.NonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Optional;

/**
 * Defines a predicate that determines whether a given message is applicable to a handler method.
 * <p>
 * This interface enables conditional dispatching of messages to handler methods based on runtime message properties,
 * handler annotations, and method metadata.
 *
 * <p>
 * {@code MessageFilter}s can be composed using {@code and()}, making them useful for defining cross-cutting message
 * acceptance rules (e.g. for authentication, message type filtering, etc.).
 *
 * @param <M> the message type to evaluate
 * @see HandlerMatcher
 * @see HandlerInvoker
 */
@FunctionalInterface
public interface MessageFilter<M> {
    MessageFilter<Object> ALLOW_ALL = new MessageFilter<>() {
        @Override
        public boolean test(Object message, Executable executable, Class<? extends Annotation> handlerAnnotation,
                            Class<?> targetClass) {
            return true;
        }

        @Override
        public boolean isAlwaysTrue() {
            return true;
        }

        @Override
        public MessageFilter<? super Object> prepare(Executable executable,
                                                     Class<? extends Annotation> handlerAnnotation,
                                                     Class<?> targetClass) {
            return this;
        }
    };

    @SuppressWarnings("unchecked")
    static <M> MessageFilter<M> allowAll() {
        return (MessageFilter<M>) ALLOW_ALL;
    }

    /**
     * Evaluates whether a message should be handled by a given method annotated with a specific handler annotation.
     *
     * @param message           the message instance to evaluate
     * @param executable        the candidate handler method
     * @param handlerAnnotation the annotation that marks the method as a handler (e.g. {@code @HandleCommand})
     * @param targetClass       the class of the handler object
     * @return {@code true} if the message is accepted by this filter for the given handler method
     */
    boolean test(M message, Executable executable, Class<? extends Annotation> handlerAnnotation, Class<?> targetClass);

    /**
     * Returns whether this filter accepts every message without inspecting runtime state.
     */
    default boolean isAlwaysTrue() {
        return false;
    }

    /**
     * Returns a filter prepared for the supplied handler method.
     * <p>
     * Implementations may use this hook to precompute annotation or reflection metadata once when a handler is
     * constructed, instead of repeating that work for every message.
     */
    default MessageFilter<? super M> prepare(Executable executable, Class<? extends Annotation> handlerAnnotation,
                                             Class<?> targetClass) {
        return this;
    }

    /**
     * Provides the least specific class type that is allowed to match this filter for a given method and annotation.
     * <p>
     * This can be used to restrict or optimize handler matching, especially when working with inheritance or interface
     * hierarchies.
     *
     * @param executable        the candidate handler method
     * @param handlerAnnotation the annotation present on the handler method
     * @return an optional type indicating the base class that messages must extend or implement
     */
    default Optional<Class<?>> getLeastSpecificAllowedClass(Executable executable,
                                                            Class<? extends Annotation> handlerAnnotation) {
        return Optional.empty();
    }

    /**
     * Combines this filter with another using logical AND. The resulting filter passes only if both filters pass.
     *
     * @param second another {@code MessageFilter} to combine with
     * @return a new {@code MessageFilter} that passes only if both this and the second filter pass
     */
    default MessageFilter<M> and(@NonNull MessageFilter<? super M> second) {
        var first = this;
        if (first.isAlwaysTrue()) {
            @SuppressWarnings("unchecked")
            MessageFilter<M> result = (MessageFilter<M>) second;
            return result;
        }
        if (second.isAlwaysTrue()) {
            return first;
        }
        return new MessageFilter<>() {
            @Override
            public boolean test(M m, Executable e, Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
                return first.test(m, e, handlerAnnotation, targetClass) && second.test(m, e, handlerAnnotation, targetClass);
            }

            @Override
            public Optional<Class<?>> getLeastSpecificAllowedClass(Executable executable,
                                                                   Class<? extends Annotation> handlerAnnotation) {
                return first.getLeastSpecificAllowedClass(executable, handlerAnnotation)
                        .or(() -> second.getLeastSpecificAllowedClass(executable, handlerAnnotation));
            }

            @Override
            public MessageFilter<? super M> prepare(Executable executable,
                                                    Class<? extends Annotation> handlerAnnotation,
                                                    Class<?> targetClass) {
                @SuppressWarnings("unchecked")
                MessageFilter<? super M> preparedFirst =
                        (MessageFilter<? super M>) first.prepare(executable, handlerAnnotation, targetClass);
                @SuppressWarnings("unchecked")
                MessageFilter<? super M> preparedSecond =
                        (MessageFilter<? super M>) second.prepare(executable, handlerAnnotation, targetClass);
                if (preparedFirst.isAlwaysTrue()) {
                    return preparedSecond;
                }
                if (preparedSecond.isAlwaysTrue()) {
                    return preparedFirst;
                }
                return new MessageFilter<>() {
                    @Override
                    public boolean test(M m, Executable e, Class<? extends Annotation> a, Class<?> t) {
                        return preparedFirst.test(m, e, a, t) && preparedSecond.test(m, e, a, t);
                    }

                    @Override
                    public Optional<Class<?>> getLeastSpecificAllowedClass(
                            Executable e, Class<? extends Annotation> a) {
                        return preparedFirst.getLeastSpecificAllowedClass(e, a)
                                .or(() -> preparedSecond.getLeastSpecificAllowedClass(e, a));
                    }
                };
            }
        };
    }
}
