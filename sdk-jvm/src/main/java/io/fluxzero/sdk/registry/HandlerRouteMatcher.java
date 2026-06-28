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

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.web.DefaultWebRequestContext;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebPattern;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Matches registry handler routes against runtime messages before delegating to normal Fluxzero handler resolution.
 */
public final class HandlerRouteMatcher {
    private static final ConcurrentMap<LoadClassKey, Optional<Class<?>>> classCache = new ConcurrentHashMap<>();

    private HandlerRouteMatcher() {
    }

    /**
     * Returns whether the indexed route can be considered for the supplied runtime message.
     * <p>
     * The result is intentionally conservative: if route metadata is incomplete or cannot be parsed, the method returns
     * {@code true} so the concrete handler remains responsible for the final decision.
     */
    public static boolean canHandle(HandlerRoute route, DeserializingMessage message) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(message, "message");
        if (!canHandlePayload(route, message.getMessageType(), message.getPayloadClass())) {
            return false;
        }
        return message.getMessageType() != MessageType.WEBREQUEST
               || !route.hasWebRouteMetadata()
               || canHandleWebRequest(route, message);
    }

    private static boolean canHandlePayload(HandlerRoute route, MessageType messageType, Class<?> payloadType) {
        if (route.disabled() || route.messageType() != messageType) {
            return false;
        }
        if (route.payloadTypeNames().isEmpty()) {
            return true;
        }
        for (String typeName : route.payloadTypeNames()) {
            if (matchesPayloadType(typeName, payloadType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPayloadType(String typeName, Class<?> payloadType) {
        String canonicalName = payloadType.getCanonicalName();
        if (Objects.equals(typeName, payloadType.getName())
            || canonicalName != null && Objects.equals(typeName, canonicalName)
            || Objects.equals(typeName, payloadType.getSimpleName())
            || Object.class.getName().equals(typeName)
            || Object.class.getSimpleName().equals(typeName)) {
            return true;
        }
        return canLoadType(typeName) && isAssignable(typeName, payloadType);
    }

    private static boolean canLoadType(String typeName) {
        return typeName.indexOf('.') >= 0 || typeName.indexOf('$') >= 0;
    }

    private static boolean isAssignable(String typeName, Class<?> payloadType) {
        ClassLoader classLoader = payloadType.getClassLoader() == null
                ? ClassLoader.getSystemClassLoader()
                : payloadType.getClassLoader();
        return resolveClass(typeName, classLoader)
                .map(type -> type.isAssignableFrom(payloadType))
                .orElse(false);
    }

    private static Optional<Class<?>> resolveClass(String typeName, ClassLoader classLoader) {
        return classCache.computeIfAbsent(new LoadClassKey(classLoader, typeName),
                                          key -> loadClass(key.typeName(), key.classLoader()));
    }

    private static Optional<Class<?>> loadClass(String typeName, ClassLoader classLoader) {
        try {
            return Optional.of(Class.forName(typeName, false, classLoader));
        } catch (ClassNotFoundException | LinkageError ignored) {
            for (int dot = typeName.lastIndexOf('.'); dot > 0; dot = typeName.lastIndexOf('.', dot - 1)) {
                try {
                    return Optional.of(Class.forName(
                            typeName.substring(0, dot) + "$" + typeName.substring(dot + 1),
                            false, classLoader));
                } catch (ClassNotFoundException | LinkageError ignoredNestedCandidate) {
                    // try the next outer-class boundary
                }
            }
            return Optional.empty();
        }
    }

    private record LoadClassKey(ClassLoader classLoader, String typeName) {
    }

    private static boolean canHandleWebRequest(HandlerRoute route, DeserializingMessage message) {
        DefaultWebRequestContext context = DefaultWebRequestContext.getWebRequestContext(message);
        for (WebRouteDescriptor webRoute : route.webRoutes()) {
            if (webRouteMatches(context, webRoute)) {
                return true;
            }
        }
        return false;
    }

    private static boolean webRouteMatches(DefaultWebRequestContext context, WebRouteDescriptor route) {
        return methodMatches(context.getMethod(), route) && pathMatches(context, route);
    }

    private static boolean methodMatches(String requestMethod, WebRouteDescriptor route) {
        if (route.methods().isEmpty()) {
            return true;
        }
        for (String method : route.methods()) {
            if (methodMatches(requestMethod, method, route)) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodMatches(String requestMethod, String routeMethod, WebRouteDescriptor route) {
        return anyMethod(routeMethod)
               || Objects.equals(requestMethod, routeMethod)
               || HttpRequestMethod.HEAD.equals(requestMethod)
                  && HttpRequestMethod.GET.equals(routeMethod)
                  && route.autoHead()
               || HttpRequestMethod.OPTIONS.equals(requestMethod)
                  && route.autoOptions()
                  && !HttpRequestMethod.isWebsocket(routeMethod);
    }

    private static boolean anyMethod(String routeMethod) {
        return HttpRequestMethod.ANY.equals(routeMethod) || "ANY".equals(routeMethod);
    }

    private static boolean pathMatches(DefaultWebRequestContext context, WebRouteDescriptor route) {
        if (route.paths().isEmpty()) {
            return true;
        }
        for (String path : route.paths()) {
            if (pathMatches(context, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pathMatches(DefaultWebRequestContext context, String path) {
        try {
            WebPattern pattern = new WebPattern(path, "");
            return Objects.equals(context.getOrigin(), pattern.getOrigin()) && context.matches(pattern.getPath());
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
