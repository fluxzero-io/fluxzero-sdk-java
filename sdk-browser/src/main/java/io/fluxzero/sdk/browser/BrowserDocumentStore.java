/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser-safe in-memory document/search store.
 */
public final class BrowserDocumentStore {
    private final Map<String, Map<String, Object>> collections = new LinkedHashMap<>();

    public void index(String collection, String id, Object document) {
        collections.computeIfAbsent(collection, ignored -> new LinkedHashMap<>()).put(id, document);
    }

    public Object get(String collection, String id) {
        return collections.getOrDefault(collection, Map.of()).get(id);
    }

    public List<Object> search(String collection) {
        return new ArrayList<>(collections.getOrDefault(collection, Map.of()).values());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        collections.forEach((name, values) -> state.put(name, values.size()));
        return state;
    }
}
