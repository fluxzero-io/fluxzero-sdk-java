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

package io.fluxzero.sdk.browser.generator;

import java.util.Objects;

/**
 * A browser conformance feature that must be represented by generated metadata or an executable scenario.
 *
 * @param name stable feature key
 * @param category high-level feature group
 * @param description human-readable intent
 */
public record BrowserConformanceFeature(String name, String category, String description) {

    public BrowserConformanceFeature {
        name = Objects.requireNonNull(name, "name");
        category = Objects.requireNonNull(category, "category");
        description = Objects.requireNonNull(description, "description");
    }
}
