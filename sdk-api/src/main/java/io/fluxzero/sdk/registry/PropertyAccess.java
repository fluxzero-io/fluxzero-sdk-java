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

package io.fluxzero.sdk.registry;

import java.lang.annotation.Annotation;
import java.util.Optional;

/**
 * Incubating abstraction for reading component properties.
 * <p>
 * JVM execution can back this with fields/getters/setters. Browser/native execution can back it with generated codecs or
 * generated property accessors.
 *
 * @param <T> component/type handle
 * @param <P> property handle
 */
public interface PropertyAccess<T, P> {

    /**
     * Returns the first property annotated with the supplied annotation.
     */
    <A extends Annotation> Optional<P> annotatedProperty(T type, Class<A> annotationType);

    /**
     * Reads the first property value annotated with the supplied annotation.
     */
    <A extends Annotation> Optional<Object> annotatedPropertyValue(Object target, Class<A> annotationType);

    /**
     * Returns the stable property name for diagnostics, routing, and generated metadata.
     */
    String propertyName(P property);
}
