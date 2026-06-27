/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser.conformance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single browser conformance scenario result.
 */
public final class BrowserFeatureResult {
    private final String name;
    private final boolean passed;
    private final String details;
    private final Map<String, Object> evidence;

    public BrowserFeatureResult(String name, boolean passed, String details, Map<String, Object> evidence) {
        this.name = Objects.requireNonNull(name, "name");
        this.passed = passed;
        this.details = details == null ? "" : details;
        this.evidence = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(evidence, "evidence")));
    }

    public String name() {
        return name;
    }

    public boolean passed() {
        return passed;
    }

    public String details() {
        return details;
    }

    public Map<String, Object> evidence() {
        return evidence;
    }

    public static BrowserFeatureResult passed(String name, Map<String, Object> evidence) {
        return new BrowserFeatureResult(name, true, "", evidence);
    }

    public static BrowserFeatureResult failed(String name, String details, Map<String, Object> evidence) {
        return new BrowserFeatureResult(name, false, details, evidence);
    }
}
