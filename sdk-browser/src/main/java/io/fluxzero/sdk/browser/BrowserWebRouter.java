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
 * Browser-native web route simulator.
 */
public final class BrowserWebRouter {
    private final List<Route> routes = new ArrayList<>();
    private final List<BrowserWebExchange> exchanges = new ArrayList<>();

    public void register(String method, String path, BrowserWebHandler handler) {
        routes.add(new Route(method, path, handler));
    }

    public BrowserWebExchange handle(BrowserWebExchange exchange) {
        for (Route route : routes) {
            BrowserWebExchange matched = route.match(exchange);
            if (matched != null) {
                BrowserWebExchange response = route.handler().handle(matched);
                exchanges.add(response);
                return response;
            }
        }
        BrowserWebExchange notFound = exchange.withResponse(404, "Not found");
        exchanges.add(notFound);
        return notFound;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("routes", routes.size());
        state.put("exchanges", exchanges.size());
        return state;
    }

    private static final class Route {
        private final String method;
        private final String path;
        private final BrowserWebHandler handler;

        private Route(String method, String path, BrowserWebHandler handler) {
            this.method = method;
            this.path = path;
            this.handler = handler;
        }

        BrowserWebHandler handler() {
            return handler;
        }

        BrowserWebExchange match(BrowserWebExchange exchange) {
            if (!method.equalsIgnoreCase(exchange.method())) {
                return null;
            }
            Map<String, String> pathParameters = matchPath(path, exchange.path());
            return pathParameters == null ? null : exchange.withPathParameters(pathParameters);
        }

        private static Map<String, String> matchPath(String pattern, String actual) {
            String[] patternParts = trimSlashes(pattern).split("/");
            String[] actualParts = trimSlashes(actual).split("/");
            if (patternParts.length != actualParts.length) {
                return null;
            }
            Map<String, String> pathParameters = new LinkedHashMap<>();
            for (int i = 0; i < patternParts.length; i++) {
                String patternPart = patternParts[i];
                String actualPart = actualParts[i];
                if (patternPart.startsWith("{") && patternPart.endsWith("}")) {
                    pathParameters.put(patternPart.substring(1, patternPart.length() - 1), actualPart);
                } else if (!patternPart.equals(actualPart)) {
                    return null;
                }
            }
            return pathParameters;
        }

        private static String trimSlashes(String value) {
            int start = 0;
            int end = value.length();
            while (start < end && value.charAt(start) == '/') {
                start++;
            }
            while (end > start && value.charAt(end - 1) == '/') {
                end--;
            }
            return value.substring(start, end);
        }
    }
}
