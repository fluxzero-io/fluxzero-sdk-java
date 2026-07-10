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

package io.fluxzero.sdk.configuration.spring;

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;

import java.lang.reflect.Executable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Spring {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor} that detects classes
 * annotated with {@link TrackSelf}
 * and registers them as {@link FluxzeroPrototype} beans for use by Fluxzero.
 * <p>
 * The scan follows Spring's {@link ComponentScan} boundaries so that applications control which self-tracked payload
 * types become active without exposing those payload types as regular Spring beans.
 * <p>
 * All detected {@code @TrackSelf} types and concrete implementations of annotated interfaces are wrapped as
 * {@link FluxzeroPrototype} and registered as Spring bean definitions, allowing Fluxzero to discover and register them
 * as self-tracking projections at runtime. Interface-level conditions are evaluated before their implementations are
 * discovered.
 *
 * <h2>Usage</h2>
 * To enable self-tracking on a class use e.g.:
 * <pre>{@code
 * @TrackSelf
 * public interface UserUpdate {
 *     UserId getUserId();
 *
 *     @HandleCommand
 *     default void handle() {
 *         Fluxzero.loadAggregate(getUserId()).assertAndApply(this);
 *     }
 * }
 * }</pre>
 * <p>
 * Make sure Spring picks up this processor, for example by including
 * {@link FluxzeroSpringConfig} in your configuration:
 * <pre>{@code
 * @SpringBootApplication
 * @Import(FluxzeroSpringConfig.class)
 * public class MyApp { ... }
 * }</pre>
 *
 * @see TrackSelf
 * @see StatefulPostProcessor
 * @see FluxzeroPrototype
 * @see FluxzeroSpringConfig
 */
@Slf4j
public class TrackSelfPostProcessor extends ComponentScanPrototypePostProcessor {
    @Override
    protected Class<TrackSelf> getTargetAnnotation() {
        return TrackSelf.class;
    }

    @Override
    protected String getBeanNameSuffix() {
        return "$$SelfTracked";
    }

    @Override
    protected TypeFilter getTargetTypeFilter() {
        return new AnnotationTypeFilter(TrackSelf.class) {
            @Override
            protected Boolean matchSuperClass(String superClassName) {
                return false;
            }
        };
    }

    @Override
    protected Stream<Class<?>> expandCandidateTypes(List<Class<?>> candidates, List<String> basePackages,
                                                    ComponentScan componentScan,
                                                    BeanDefinitionRegistry registry,
                                                    ResourceLoader resourceLoader) {
        List<Class<?>> roots = candidates.stream()
                .filter(type -> type.getDeclaredAnnotation(TrackSelf.class) != null).toList();
        if (roots.isEmpty()) {
            return candidates.stream();
        }
        List<Class<?>> discoveredTypes = Stream.concat(
                candidates.stream(),
                findImplementations(roots, basePackages, componentScan, registry, resourceLoader))
                .distinct().toList();
        Set<Class<?>> originalCandidates = new HashSet<>(candidates);
        return discoveredTypes.stream().filter(type -> originalCandidates.contains(type)
                                                       || requiresSpecificRegistration(type, discoveredTypes));
    }

    private boolean requiresSpecificRegistration(Class<?> type, List<Class<?>> discoveredTypes) {
        if (!hasHandlerAnnotations(type)) {
            return false;
        }
        if (isHandlerSpecialization(type)) {
            return true;
        }
        return discoveredTypes.stream().noneMatch(superType -> !superType.equals(type)
                                                               && superType.isAssignableFrom(type)
                                                               && hasHandlerAnnotations(superType));
    }

    private boolean isHandlerSpecialization(Class<?> type) {
        return type.getDeclaredAnnotation(TrackSelf.class) != null
               || type.getDeclaredAnnotation(Consumer.class) != null
               || declaredExecutables(type).anyMatch(this::hasDirectHandlerAnnotation);
    }

    private boolean hasHandlerAnnotations(Class<?> type) {
        return Stream.concat(ReflectionUtils.getAllMethods(type).stream(),
                             Stream.of(type.getDeclaredConstructors()))
                .anyMatch(method -> ReflectionUtils.getMethodAnnotation(method, HandleMessage.class).isPresent());
    }

    private Stream<Executable> declaredExecutables(Class<?> type) {
        return Stream.concat(Stream.of(type.getDeclaredMethods()), Stream.of(type.getDeclaredConstructors()));
    }

    private boolean hasDirectHandlerAnnotation(Executable executable) {
        return ReflectionUtils.getAnnotations(executable).stream()
                .anyMatch(annotation -> annotation.annotationType().isAnnotationPresent(HandleMessage.class));
    }
}
