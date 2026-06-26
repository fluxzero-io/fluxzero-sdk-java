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


import java.util.List;
import java.util.Objects;

/**
 * Incubating source-level metadata for an HTTP or WebSocket handler route.
 *
 * @param paths indexed route paths
 * @param methods indexed HTTP or WebSocket methods
 * @param autoHead whether GET handlers may also serve HEAD
 * @param autoOptions whether the route can contribute to automatic OPTIONS responses
 */
public record WebRouteDescriptor(
        List<String> paths,
        List<String> methods,
        boolean autoHead,
        boolean autoOptions) {

    public WebRouteDescriptor {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    }
}
