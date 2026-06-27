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

package io.fluxzero.sdk.tracking.handling.contentfiltering;

import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AllArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@AllArgsConstructor
public class ContentFilterInterceptor implements HandlerInterceptor {
    private static final ClassValue<FilterContentMetadata> metadataCache = new ClassValue<>() {
        @Override
        protected FilterContentMetadata computeValue(Class<?> type) {
            return new FilterContentMetadata(type);
        }
    };

    private final Serializer serializer;

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        var filterContent = metadataCache.get(invoker.getTargetClass()).filterContent(invoker.getMethod()).orElse(null);
        if (filterContent == null) {
            return function;
        }
        return m -> serializer.filterContent(function.apply(m), User.getCurrent());
    }

    private static final class FilterContentMetadata {
        private final Class<?> targetClass;
        private final Optional<ComponentMetadataLookup> lookup;
        private final ConcurrentHashMap<Executable, Optional<FilterContent>> filterContent = new ConcurrentHashMap<>();

        private FilterContentMetadata(Class<?> targetClass) {
            this.targetClass = targetClass;
            this.lookup = ComponentMetadataLookups.lookup(targetClass);
        }

        private Optional<FilterContent> filterContent(Executable executable) {
            Optional<FilterContent> cached = filterContent.get(executable);
            if (cached != null) {
                return cached;
            }
            Optional<FilterContent> metadata = lookup.flatMap(l -> filterContent(
                    ComponentMetadataLookups.executableAnnotations(l, executable))
                    .or(() -> filterContent(l.typeAnnotations(targetClass.getName())))
                    .or(() -> filterContent(l.packageAnnotations(targetClass.getPackageName()))));
            Optional<FilterContent> computed = metadata.isPresent() || lookup.isPresent()
                                               || ComponentMetadataLookups.generatedOnlyMode()
                    ? metadata
                    : JvmComponentIntrospector.getInstance().getAnnotation(executable, FilterContent.class)
                            .or(() -> Optional.ofNullable(JvmComponentIntrospector.getInstance()
                                    .getTypeAnnotation(targetClass, FilterContent.class)))
                            .or(() -> JvmComponentIntrospector.getInstance()
                                    .getPackageAnnotation(targetClass.getPackage(), FilterContent.class));
            Optional<FilterContent> existing = filterContent.putIfAbsent(executable, computed);
            return existing != null ? existing : computed;
        }

        private static Optional<FilterContent> filterContent(List<AnnotationDescriptor> annotations) {
            return annotations.stream()
                    .filter(annotation -> annotation.qualifiedName().equals(FilterContent.class.getName())
                                          || annotation.name().equals(FilterContent.class.getSimpleName()))
                    .findFirst()
                    .map(ignored -> new FilterContentMetadataProjection());
        }
    }

    private record FilterContentMetadataProjection() implements FilterContent {
        @Override
        public Class<? extends Annotation> annotationType() {
            return FilterContent.class;
        }
    }
}
