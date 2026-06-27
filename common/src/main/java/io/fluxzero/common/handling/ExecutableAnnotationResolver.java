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
 */

package io.fluxzero.common.handling;

import io.fluxzero.common.reflection.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;

/**
 * Resolves semantic annotations for handler executables.
 * <p>
 * JVM compatibility can resolve real annotations from reflection, while metadata-first runtimes can resolve generated
 * annotation views from a component registry.
 */
@FunctionalInterface
public interface ExecutableAnnotationResolver {

    /**
     * Resolves the supplied annotation type for the executable.
     */
    Optional<? extends Annotation> getAnnotation(
            Executable executable, Class<? extends Annotation> annotationType);

    /**
     * Resolves the supplied annotation type for an executable metadata view.
     */
    default Optional<? extends Annotation> getAnnotation(
            ExecutableView executable, Class<? extends Annotation> annotationType) {
        Optional<Executable> reflectionExecutable = executable.executable();
        return reflectionExecutable.isPresent()
                ? getAnnotation(reflectionExecutable.get(), annotationType)
                : executable.annotation(annotationType);
    }

    /**
     * Resolves all matching annotations of the supplied type for the executable.
     */
    default List<? extends Annotation> getAnnotations(
            Executable executable, Class<? extends Annotation> annotationType) {
        return getAnnotation(executable, annotationType).stream().toList();
    }

    /**
     * Resolves all matching annotations of the supplied type for an executable metadata view.
     */
    default List<? extends Annotation> getAnnotations(
            ExecutableView executable, Class<? extends Annotation> annotationType) {
        return getAnnotation(executable, annotationType).stream().toList();
    }

    /**
     * Returns the default reflection-backed annotation resolver.
     */
    static ExecutableAnnotationResolver reflection() {
        return new ExecutableAnnotationResolver() {
            @Override
            public Optional<? extends Annotation> getAnnotation(
                    Executable executable, Class<? extends Annotation> annotationType) {
                return ReflectionUtils.getMethodAnnotation(executable, annotationType);
            }

            @Override
            public List<? extends Annotation> getAnnotations(
                    Executable executable, Class<? extends Annotation> annotationType) {
                return ReflectionUtils.getMethodAnnotations(executable, annotationType);
            }
        };
    }
}
