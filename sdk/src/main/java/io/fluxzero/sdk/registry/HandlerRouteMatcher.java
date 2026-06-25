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

/**
 * Matches registry handler routes against runtime messages before delegating to normal Fluxzero handler resolution.
 */
public final class HandlerRouteMatcher {
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
        if (!route.canHandle(message.getMessageType(), message.getPayloadClass())) {
            return false;
        }
        return message.getMessageType() != MessageType.WEBREQUEST
               || !route.hasWebRouteMetadata()
               || canHandleWebRequest(route, message);
    }

    private static boolean canHandleWebRequest(HandlerRoute route, DeserializingMessage message) {
        DefaultWebRequestContext context = DefaultWebRequestContext.getWebRequestContext(message);
        return route.webRoutes().stream().anyMatch(webRoute -> webRouteMatches(context, webRoute));
    }

    private static boolean webRouteMatches(DefaultWebRequestContext context, WebRouteDescriptor route) {
        return methodMatches(context.getMethod(), route) && pathMatches(context, route);
    }

    private static boolean methodMatches(String requestMethod, WebRouteDescriptor route) {
        return route.methods().isEmpty()
               || route.methods().stream().anyMatch(method -> methodMatches(requestMethod, method, route));
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
        return route.paths().isEmpty() || route.paths().stream().anyMatch(path -> pathMatches(context, path));
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
