/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.handling;

import lombok.AllArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.function.BiFunction;

/**
 * A reusable, target-bound handler method.
 *
 * <p>Unlike a {@link HandlerInvoker}, a {@code HandlerMethod} is not resolved for one specific message. It can be
 * checked and invoked repeatedly for messages that match the same method and target.</p>
 *
 * @param <M> the message type
 */
public interface HandlerMethod<M> extends HandlerDescriptor {

    /**
     * Returns whether this method can handle the supplied message.
     *
     * @param message the message to check
     * @return {@code true} if this handler method can handle the message
     */
    boolean canHandle(M message);

    /**
     * Invokes this handler method for the supplied message using the default result-combining strategy.
     *
     * @param message the message to handle
     * @return the handler result
     */
    default Object invoke(M message) {
        return invoke(message, (firstResult, secondResult) -> firstResult);
    }

    /**
     * Invokes this handler method for the supplied message and combines multiple results using the given combiner.
     *
     * @param message        the message to handle
     * @param resultCombiner function to combine multiple results
     * @return the handler result
     */
    Object invoke(M message, BiFunction<Object, Object, Object> resultCombiner);

    /**
     * A {@code HandlerMethod} that delegates all behavior to another instance.
     *
     * @param <M> the message type
     */
    @AllArgsConstructor
    abstract class DelegatingHandlerMethod<M> implements HandlerMethod<M> {
        protected final HandlerMethod<M> delegate;

        @Override
        public Class<?> getTargetClass() {
            return delegate.getTargetClass();
        }

        @Override
        public Executable getMethod() {
            return delegate.getMethod();
        }

        @Override
        public <A extends Annotation> A getMethodAnnotation() {
            return delegate.getMethodAnnotation();
        }

        @Override
        public boolean expectResult() {
            return delegate.expectResult();
        }

        @Override
        public boolean isPassive() {
            return delegate.isPassive();
        }

        @Override
        public boolean canHandle(M message) {
            return delegate.canHandle(message);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
