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

package io.fluxzero.sdk.web;

import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Global options for rendering an {@link ApiDocCatalog} as an OpenAPI document.
 */
public record OpenApiOptions(
        String title,
        String version,
        String description,
        List<String> servers
) {
    public OpenApiOptions {
        title = isBlank(title) ? "Fluxzero API" : title;
        version = isBlank(version) ? "0.0.0" : version;
        description = isBlank(description) ? "" : description;
        servers = servers == null ? List.of() : servers.stream().filter(s -> !isBlank(s)).toList();
    }

    public static OpenApiOptions defaults() {
        return new OpenApiOptions("Fluxzero API", "0.0.0", "", List.of());
    }

    public static OpenApiOptions of(String title, String version) {
        return new OpenApiOptions(title, version, "", List.of());
    }
}
