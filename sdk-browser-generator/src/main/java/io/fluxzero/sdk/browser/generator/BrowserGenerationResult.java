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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generated browser application output and manifest metadata.
 *
 * @param sources generated Java/resource files
 * @param manifestJson JavaScript-readable conformance manifest
 * @param features coverage matrix features
 * @param counters generated model counters
 */
public record BrowserGenerationResult(
        List<BrowserGeneratedSource> sources,
        String manifestJson,
        List<BrowserConformanceFeature> features,
        Map<String, Integer> counters) {

    public BrowserGenerationResult {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        manifestJson = Objects.requireNonNull(manifestJson, "manifestJson");
        features = List.copyOf(Objects.requireNonNull(features, "features"));
        counters = Map.copyOf(Objects.requireNonNull(counters, "counters"));
    }
}
