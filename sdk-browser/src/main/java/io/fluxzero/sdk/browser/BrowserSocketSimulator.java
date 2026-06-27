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
 * Browser-native WebSocket lifecycle simulator.
 */
public final class BrowserSocketSimulator {
    private final List<String> lifecycle = new ArrayList<>();

    public void handshake(String path) {
        lifecycle.add("handshake:" + path);
    }

    public void open(String sessionId) {
        lifecycle.add("open:" + sessionId);
    }

    public void message(String sessionId, Object payload) {
        lifecycle.add("message:" + sessionId + ":" + String.valueOf(payload));
    }

    public void pong(String sessionId) {
        lifecycle.add("pong:" + sessionId);
    }

    public void close(String sessionId) {
        lifecycle.add("close:" + sessionId);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("events", List.copyOf(lifecycle));
        return state;
    }
}
