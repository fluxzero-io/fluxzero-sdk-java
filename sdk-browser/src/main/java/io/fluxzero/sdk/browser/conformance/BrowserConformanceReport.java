/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Browser conformance report returned to JavaScript.
 */
public final class BrowserConformanceReport {
    private final List<BrowserFeatureResult> results = new ArrayList<>();

    public void add(BrowserFeatureResult result) {
        results.add(result);
    }

    public List<BrowserFeatureResult> results() {
        return List.copyOf(results);
    }

    public long passed() {
        return results.stream().filter(BrowserFeatureResult::passed).count();
    }

    public long failed() {
        return results.size() - passed();
    }

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"passed\":").append(passed())
                .append(",\"failed\":").append(failed())
                .append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendResult(json, results.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendResult(StringBuilder json, BrowserFeatureResult result) {
        json.append("{\"name\":\"").append(escape(result.name())).append("\",")
                .append("\"passed\":").append(result.passed()).append(',')
                .append("\"details\":\"").append(escape(result.details())).append("\",")
                .append("\"evidence\":");
        appendMap(json, result.evidence());
        json.append('}');
    }

    private static void appendMap(StringBuilder json, Map<String, Object> map) {
        json.append('{');
        int index = 0;
        for (var entry : map.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(json, entry.getValue());
        }
        json.append('}');
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(String.valueOf(value));
        } else if (value instanceof Map<?, ?> map) {
            appendMap(json, (Map<String, Object>) map);
        } else if (value instanceof Iterable<?> iterable) {
            json.append('[');
            int index = 0;
            for (Object item : iterable) {
                if (index++ > 0) {
                    json.append(',');
                }
                appendValue(json, item);
            }
            json.append(']');
        } else {
            json.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
