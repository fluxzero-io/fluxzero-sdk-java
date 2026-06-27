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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Incubating abstraction for reading Fluxzero component metadata.
 * <p>
 * This interface is intentionally handle-based: JVM code can use reflection handles while browser/native code can use
 * generated registry handles. Shared Fluxzero logic should depend on this boundary instead of directly depending on a
 * reflection library.
 *
 * @param <T> component/type handle
 * @param <E> executable handle
 * @param <P> property handle
 */
public interface ComponentIntrospector<T, E, P> {

    /**
     * Returns normalized annotations on a component/type handle.
     */
    List<AnnotationDescriptor> typeAnnotations(T type);

    /**
     * Returns normalized annotations on an executable handle.
     */
    List<AnnotationDescriptor> executableAnnotations(E executable);

    /**
     * Returns normalized annotations on a property handle.
     */
    List<AnnotationDescriptor> propertyAnnotations(P property);

    /**
     * Returns normalized package annotations that apply to the supplied component/type handle.
     */
    List<AnnotationDescriptor> packageAnnotations(T type);

    /**
     * Returns the component/type annotation projected to the supplied value object.
     */
    <A extends Annotation, R> Optional<R> typeAnnotationAs(
            T type, Class<A> annotationType, Class<R> projectionType);

    /**
     * Returns the package annotation projected to the supplied value object.
     */
    <A extends Annotation, R> Optional<R> packageAnnotationAs(
            T type, Class<A> annotationType, Class<R> projectionType);

    /**
     * Returns the executable annotation projected to the supplied value object.
     */
    <A extends Annotation, R> Optional<R> executableAnnotationAs(
            E executable, Class<A> annotationType, Class<R> projectionType);

    /**
     * Returns all matching executable annotations projected to the supplied value object.
     */
    <A extends Annotation, R> List<R> executableAnnotationsAs(
            E executable, Class<A> annotationType, Class<R> projectionType);

    /**
     * Returns a comparator that orders component/type handles by Fluxzero specificity semantics.
     */
    Comparator<T> typeSpecificityComparator();
}
