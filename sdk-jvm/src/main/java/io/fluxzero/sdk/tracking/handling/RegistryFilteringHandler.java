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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.HandlerRouteMatcher;
import io.fluxzero.sdk.registry.ParameterDescriptor;
import io.fluxzero.sdk.web.ApiReferenceEndpoint;
import io.fluxzero.sdk.web.OpenApiDocumentEndpoint;
import io.fluxzero.sdk.web.StaticFileHandler;

import java.util.List;
import java.util.Optional;

final class RegistryFilteringHandler extends Handler.DelegatingHandler<DeserializingMessage> {
    private final List<HandlerRoute> routes;

    private RegistryFilteringHandler(Handler<DeserializingMessage> delegate, List<HandlerRoute> routes) {
        super(delegate);
        this.routes = routes;
    }

    static Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler, MessageType messageType) {
        if (isSyntheticWebEndpoint(handler.getTargetClass())) {
            return handler;
        }
        List<HandlerRoute> routes;
        try {
            routes = routes(handler.getTargetClass()).stream()
                    .filter(route -> !route.disabled())
                    .filter(route -> route.messageType() == messageType)
                    .toList();
        } catch (RuntimeException ignored) {
            return handler;
        }
        if (routes.isEmpty() || routes.stream().anyMatch(route -> !isSafeNormalDispatchFilter(route))) {
            return handler;
        }
        return new RegistryFilteringHandler(handler, routes);
    }

    private static List<HandlerRoute> routes(Class<?> type) {
        return ComponentMetadataLookups.lookup(type)
                .flatMap(lookup -> lookup.component(type.getName()))
                .map(ComponentDescriptor::routes)
                .orElseGet(List::of);
    }

    private static boolean isSafeNormalDispatchFilter(HandlerRoute route) {
        if (route.hasWebRouteMetadata()) {
            return false;
        }
        return !route.allowedClassNames().isEmpty()
               || route.executableMetadata()
                       .map(RegistryFilteringHandler::hasSinglePlainPayloadParameter)
                       .orElse(false);
    }

    private static boolean hasSinglePlainPayloadParameter(ExecutableDescriptor executable) {
        if (executable.parameters().size() != 1) {
            return false;
        }
        ParameterDescriptor parameter = executable.parameters().getFirst();
        return parameter.annotations().isEmpty() && !isFrameworkResolvedParameter(parameter.typeName());
    }

    private static boolean isFrameworkResolvedParameter(String typeName) {
        return "io.fluxzero.sdk.modeling.Entity".equals(typeName)
               || "io.fluxzero.sdk.common.Message".equals(typeName)
               || "io.fluxzero.sdk.common.HasMessage".equals(typeName)
               || "io.fluxzero.sdk.common.serialization.DeserializingMessage".equals(typeName)
               || "io.fluxzero.common.api.Metadata".equals(typeName)
               || "io.fluxzero.common.api.SerializedMessage".equals(typeName);
    }

    private static boolean isSyntheticWebEndpoint(Class<?> targetClass) {
        return OpenApiDocumentEndpoint.class.isAssignableFrom(targetClass)
               || ApiReferenceEndpoint.class.isAssignableFrom(targetClass)
               || StaticFileHandler.class.isAssignableFrom(targetClass);
    }

    @Override
    public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
        return Optional.ofNullable(getInvokerOrNull(message));
    }

    @Override
    public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
        return canHandle(message) ? delegate.getInvokerOrNull(message) : null;
    }

    @Override
    public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
        return canHandle(message) ? delegate.getHandlerMethodOrNull(message) : null;
    }

    private boolean canHandle(DeserializingMessage message) {
        return routes.stream().anyMatch(route -> HandlerRouteMatcher.canHandle(route, message));
    }
}
