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

package io.fluxzero.sdk.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Small internal route matcher for Fluxzero web handlers.
 * <p>
 * The matcher intentionally keeps the route rules limited to the SDK's annotation model: literal path parts,
 * {@code {pathParam}} parameters, {@code {pathParam:regex}} parameters, and {@code *} wildcards.
 * <p>
 * Trailing slashes on non-root paths are ignored, so {@code /users} and {@code /users/} match the same route.
 * <p>
 * When multiple routes match, the most specific route wins. Literal path parts outrank parameters, constrained
 * parameters outrank unconstrained parameters, and wildcard or catch-all routes are treated as fallbacks.
 */
class WebRouteMatcher<T> {
    static final Comparator<Match<?>> MOST_SPECIFIC = Comparator
            .<Match<?>>comparingInt(m -> m.route().path().literalChars())
            .thenComparingInt(m -> m.route().path().literalSegments())
            .thenComparingInt(m -> m.route().path().segmentCount())
            .thenComparingInt(m -> m.route().path().regexParameterCount())
            .thenComparingInt(m -> -m.route().path().catchAllCount())
            .thenComparingInt(m -> -m.route().path().wildcardCount())
            .thenComparingInt(m -> -m.route().path().parameterCount())
            .thenComparingInt(m -> -m.route().order());

    private final List<Route<T>> routes = new ArrayList<>();

    void add(WebPattern pattern, T value) {
        routes.add(new Route<>(pattern, CompiledPath.compile(pattern.getPath()), value, routes.size()));
    }

    Optional<Match<T>> match(String method, String origin, String path) {
        return match(method, origin, path, pattern -> true);
    }

    Optional<Match<T>> match(String method, String origin, String path, Predicate<WebPattern> predicate) {
        return matches(origin, path, pattern -> Objects.equals(method, pattern.getMethod()) && predicate.test(pattern))
                .max((a, b) -> MOST_SPECIFIC.compare(a, b));
    }

    Stream<Match<T>> matches(String origin, String path, Predicate<WebPattern> predicate) {
        String normalizedPath = normalizePath(path);
        return routes.stream()
                .filter(route -> predicate.test(route.pattern()))
                .filter(route -> Objects.equals(origin, route.pattern().getOrigin()))
                .flatMap(route -> route.match(normalizedPath).stream());
    }

    static boolean matchesPath(String pattern, String path) {
        return CompiledPath.compile(pattern).match(normalizePath(path)).isPresent();
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    record Match<T>(Route<T> route, Map<String, String> pathParameters) {
        T value() {
            return route.value();
        }

        WebPattern pattern() {
            return route.pattern();
        }
    }

    record Route<T>(WebPattern pattern, CompiledPath path, T value, int order) {
        Optional<Match<T>> match(String path) {
            return this.path.match(path).map(parameters -> new Match<>(this, parameters));
        }
    }

    record CompiledPath(
            Pattern regex,
            List<ParameterGroup> parameters,
            int literalChars,
            int literalSegments,
            int segmentCount,
            int parameterCount,
            int regexParameterCount,
            int wildcardCount,
            int catchAllCount
    ) {
        static CompiledPath compile(String path) {
            String normalizedPath = normalizeRoutePath(normalizePath(path));
            StringBuilder regex = new StringBuilder("^");
            List<ParameterGroup> parameters = new ArrayList<>();
            int literalChars = 0;
            int parameterCount = 0;
            int regexParameterCount = 0;
            int wildcardCount = 0;
            int catchAllCount = 0;

            for (int i = 0; i < normalizedPath.length(); ) {
                char current = normalizedPath.charAt(i);
                if (current == '{') {
                    int end = findParameterEnd(normalizedPath, i);
                    Parameter parameter = parseParameter(normalizedPath.substring(i + 1, end));
                    String groupName = "fz" + parameters.size();
                    regex.append("(?<").append(groupName).append(">")
                            .append(parameter.regex()).append(")");
                    parameters.add(new ParameterGroup(parameter.name(), groupName));
                    parameterCount++;
                    if (parameter.hasRegex()) {
                        regexParameterCount++;
                    }
                    i = end + 1;
                    if (i == normalizedPath.length() - 1 && normalizedPath.charAt(i) == '*') {
                        i++;
                    }
                    continue;
                }
                if (current == '*') {
                    if (i == normalizedPath.length() - 1) {
                        regex.append(".*");
                        catchAllCount++;
                    } else {
                        regex.append("[^/]*");
                        wildcardCount++;
                    }
                    i++;
                    continue;
                }

                int start = i;
                while (i < normalizedPath.length()
                       && normalizedPath.charAt(i) != '{'
                       && normalizedPath.charAt(i) != '*') {
                    if (normalizedPath.charAt(i) != '/') {
                        literalChars++;
                    }
                    i++;
                }
                regex.append(Pattern.quote(normalizedPath.substring(start, i)));
            }
            if (!normalizedPath.isEmpty() && !"/".equals(normalizedPath)) {
                regex.append("/?");
            }
            regex.append("$");

            return new CompiledPath(
                    Pattern.compile(regex.toString()), List.copyOf(parameters), literalChars,
                    literalSegments(normalizedPath), segmentCount(normalizedPath), parameterCount,
                    regexParameterCount, wildcardCount, catchAllCount);
        }

        private static String normalizeRoutePath(String path) {
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path;
        }

        Optional<Map<String, String>> match(String path) {
            var matcher = regex.matcher(path);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            Map<String, String> pathParameters = new LinkedHashMap<>();
            for (ParameterGroup parameter : parameters) {
                pathParameters.put(parameter.name(), matcher.group(parameter.groupName()));
            }
            return Optional.of(pathParameters);
        }

        private static int findParameterEnd(String path, int start) {
            int depth = 0;
            for (int i = start; i < path.length(); i++) {
                char current = path.charAt(i);
                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            throw new IllegalArgumentException("Route path parameter closing delimiter '}' is missing in: " + path);
        }

        private static Parameter parseParameter(String value) {
            int regexStart = value.indexOf(':');
            String name = regexStart < 0 ? value : value.substring(0, regexStart);
            String regex = regexStart < 0 ? "[^/]+" : value.substring(regexStart + 1);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Route path parameter name is missing in: {" + value + "}");
            }
            return new Parameter(name, regex.isBlank() ? "[^/]+" : regex, regexStart >= 0);
        }

        private static int literalSegments(String path) {
            int result = 0;
            for (String segment : path.split("/", -1)) {
                if (!segment.isBlank() && segment.indexOf('{') < 0 && segment.indexOf('*') < 0) {
                    result++;
                }
            }
            return result;
        }

        private static int segmentCount(String path) {
            int result = 0;
            for (String segment : path.split("/", -1)) {
                if (!segment.isBlank()) {
                    result++;
                }
            }
            return result;
        }
    }

    record Parameter(String name, String regex, boolean hasRegex) {
    }

    record ParameterGroup(String name, String groupName) {
    }
}
