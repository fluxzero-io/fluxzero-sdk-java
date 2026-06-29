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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generated browser application output and manifest metadata.
 *
 */
public final class BrowserGenerationResult {
    private final List<BrowserGeneratedSource> sources;
    private final String manifestJson;
    private final List<BrowserConformanceFeature> features;
    private final Map<String, Integer> counters;

    public BrowserGenerationResult(
            List<BrowserGeneratedSource> sources,
            String manifestJson,
            List<BrowserConformanceFeature> features,
            Map<String, Integer> counters) {
        this.sources = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(sources, "sources")));
        this.manifestJson = Objects.requireNonNull(manifestJson, "manifestJson");
        this.features = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(features, "features")));
        this.counters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(counters, "counters")));
    }

    public List<BrowserGeneratedSource> sources() {
        return sources;
    }

    public String manifestJson() {
        return manifestJson;
    }

    public List<BrowserConformanceFeature> features() {
        return features;
    }

    public Map<String, Integer> counters() {
        return counters;
    }
}
