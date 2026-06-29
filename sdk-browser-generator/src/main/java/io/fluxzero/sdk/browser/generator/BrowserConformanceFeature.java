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
 */
public final class BrowserConformanceFeature {
    private final String name;
    private final String category;
    private final String description;

    public BrowserConformanceFeature(String name, String category, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.description = Objects.requireNonNull(description, "description");
    }

    public String name() {
        return name;
    }

    public String category() {
        return category;
    }

    public String description() {
        return description;
    }
}
