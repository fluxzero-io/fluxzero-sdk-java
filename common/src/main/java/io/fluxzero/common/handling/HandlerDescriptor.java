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

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Describes the handler method that is involved in a handling operation.
 *
 * <p>A descriptor carries stable metadata only. It does not imply that the handler is already bound to a specific
 * message, nor that it can be invoked through this interface.</p>
 *
 * @see HandlerInvoker
 * @see HandlerMethod
 */
public interface HandlerDescriptor {

    /**
     * The target class that contains the handler method.
     *
     * @return the declaring class of the handler
     */
    Class<?> getTargetClass();

    /**
     * The {@link Executable} representing the handler method (can be a static or instance {@link Method} or
     * {@link Constructor}).
     *
     * @return the executable method
     */
    Executable getMethod();

    /**
     * Retrieves the configured handler annotation from the handler method, if present.
     *
     * @param <A> the annotation type
     * @return the annotation instance, or {@code null} if not found
     */
    <A extends Annotation> A getMethodAnnotation();

    /**
     * Indicates whether the handler method has a return value.
     *
     * @return {@code true} if the method returns a value; {@code false} if it is {@code void}
     */
    boolean expectResult();

    /**
     * Indicates whether this handler operates in passive mode (i.e., results will not be published).
     *
     * @return {@code true} if passive; otherwise {@code false}
     */
    boolean isPassive();
}
