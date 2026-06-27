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
 * Browser-safe in-memory event and snapshot store.
 */
public final class BrowserEventStore {
    private final Map<String, List<Object>> eventsByAggregate = new LinkedHashMap<>();
    private final Map<String, Object> snapshots = new LinkedHashMap<>();

    public void append(String aggregateId, Object event) {
        eventsByAggregate.computeIfAbsent(aggregateId, ignored -> new ArrayList<>()).add(event);
    }

    public List<Object> events(String aggregateId) {
        return List.copyOf(eventsByAggregate.getOrDefault(aggregateId, List.of()));
    }

    public void snapshot(String aggregateId, Object snapshot) {
        snapshots.put(aggregateId, snapshot);
    }

    public Object snapshot(String aggregateId) {
        return snapshots.get(aggregateId);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("aggregates", eventsByAggregate.keySet());
        state.put("eventCount", eventsByAggregate.values().stream().mapToInt(List::size).sum());
        state.put("snapshotCount", snapshots.size());
        return state;
    }
}
