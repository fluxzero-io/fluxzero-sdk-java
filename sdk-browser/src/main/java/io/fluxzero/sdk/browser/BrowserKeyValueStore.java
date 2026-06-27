/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-safe in-memory key-value store.
 */
public final class BrowserKeyValueStore {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public void put(String key, Object value) {
        values.put(key, value);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }
}
