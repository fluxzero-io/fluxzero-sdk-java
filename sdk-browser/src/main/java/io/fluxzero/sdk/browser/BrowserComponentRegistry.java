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

package io.fluxzero.sdk.browser;

import io.fluxzero.common.MessageType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TeaVM-safe registry lowered from the shared Fluxzero component registry.
 */
public final class BrowserComponentRegistry {
    private final int packages;
    private final int components;
    private final int registeredTypes;
    private final List<BrowserRouteMetadata> routes;

    public BrowserComponentRegistry(int packages, int components, int registeredTypes,
                                    List<BrowserRouteMetadata> routes) {
        this.packages = packages;
        this.components = components;
        this.registeredTypes = registeredTypes;
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    public static BrowserComponentRegistry empty() {
        return new BrowserComponentRegistry(0, 0, 0, List.of());
    }

    public BrowserRouteMetadata findRoute(String componentName, MessageType messageType, String payloadTypeName) {
        Objects.requireNonNull(componentName, "componentName");
        Objects.requireNonNull(messageType, "messageType");
        for (BrowserRouteMetadata route : routes) {
            if (componentName.equals(route.componentName()) && route.matches(messageType, payloadTypeName)) {
                return route;
            }
        }
        return null;
    }

    public List<BrowserRouteMetadata> routes() {
        return routes;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("packages", packages);
        snapshot.put("components", components);
        snapshot.put("routes", routes.size());
        snapshot.put("registeredTypes", registeredTypes);
        return snapshot;
    }
}
