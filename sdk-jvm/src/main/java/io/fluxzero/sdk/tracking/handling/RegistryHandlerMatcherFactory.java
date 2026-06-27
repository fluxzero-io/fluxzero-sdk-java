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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.ExecutableInvocation;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerMatcher;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.RegistryExecutableViews;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RegistryHandlerMatcherFactory {
    private RegistryHandlerMatcherFactory() {
    }

    static Optional<HandlerMatcher<Object, DeserializingMessage>> create(
            Class<?> targetClass,
            List<ParameterResolver<? super DeserializingMessage>> parameterResolvers,
            HandlerConfiguration<DeserializingMessage> config) {
        return ComponentMetadataLookups.registeredLookup(targetClass)
                .flatMap(lookup -> {
                    List<ExecutableView> executableViews = lookup.executables(targetClass.getName()).stream()
                            .map(descriptor -> RegistryExecutableViews.executableView(targetClass, descriptor))
                            .filter(view -> config.methodMatches(targetClass, view))
                            .toList();
                    if (executableViews.isEmpty()) {
                        return Optional.empty();
                    }
                    Map<String, ExecutableInvocation> invocations = new LinkedHashMap<>();
                    for (ExecutableView view : executableViews) {
                        Optional<ExecutableInvocation> invocation =
                                GeneratedExecutableInvocations.find(targetClass, view.executableId());
                        if (invocation.isEmpty()) {
                            return Optional.empty();
                        }
                        invocations.put(view.executableId(), invocation.orElseThrow());
                    }
                    return Optional.of(HandlerInspector.inspectViews(
                            targetClass, executableViews, view -> invocations.get(view.executableId()),
                            parameterResolvers, config));
                });
    }
}
