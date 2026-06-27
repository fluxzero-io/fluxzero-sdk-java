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
 * Options for browser application source generation.
 *
 * @param packageName generated Java package
 * @param className generated application class name
 * @param registerRoutes whether generated source should register lazy route handlers in the browser core
 */
public record BrowserGeneratorOptions(String packageName, String className, boolean registerRoutes) {

    public BrowserGeneratorOptions {
        packageName = Objects.requireNonNull(packageName, "packageName");
        className = Objects.requireNonNull(className, "className");
    }

    public BrowserGeneratorOptions(String packageName, String className) {
        this(packageName, className, true);
    }

    /**
     * Returns the default conformance application options.
     */
    public static BrowserGeneratorOptions defaults() {
        return new BrowserGeneratorOptions("io.fluxzero.browser.generated", "GeneratedFluxzeroBrowserApplication");
    }
}
