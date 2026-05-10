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
import java.util.HashMap;
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
 * Optional path fragments can be declared with square brackets, for example {@code /users[/{id}]}.
 * <p>
 * Trailing slashes on non-root paths are ignored, so {@code /users} and {@code /users/} match the same route.
 * <p>
 * When multiple routes match, the most specific route wins. Literal path parts outrank parameters, constrained
 * parameters outrank unconstrained parameters, and wildcard or catch-all routes are treated as fallbacks.
 */
class WebRouteMatcher<T> {
    static final Comparator<Match<?>> MOST_SPECIFIC = (a, b) -> compareRoutes(a.route(), b.route());

    private final List<Route<T>> routes = new ArrayList<>();
    private final Map<MethodOriginKey, Map<String, List<Route<T>>>> routesByMethodOriginAndPrefix = new HashMap<>();

    void add(WebPattern pattern, T value) {
        for (RouteVariant variant : RouteVariants.expand(pattern.getPath())) {
            Route<T> route = new Route<>(
                    pattern, CompiledPath.compile(variant.path(), variant.optionalFragmentCount()), value,
                    routes.size());
            routes.add(route);
            routesByMethodOriginAndPrefix
                    .computeIfAbsent(new MethodOriginKey(pattern.getMethod(), pattern.getOrigin()),
                                     ignored -> new HashMap<>())
                    .computeIfAbsent(route.path().literalPrefix(), ignored -> new ArrayList<>())
                    .add(route);
        }
    }

    Optional<Match<T>> match(String method, String origin, String path) {
        return match(method, origin, path, pattern -> true);
    }

    Optional<Match<T>> match(String method, String origin, String path, Predicate<WebPattern> predicate) {
        String normalizedPath = normalizePath(path);
        Route<T> best = bestRoute(method, origin, normalizedPath, predicate);
        return best == null ? Optional.empty() : best.match(normalizedPath);
    }

    Stream<Match<T>> matches(String origin, String path, Predicate<WebPattern> predicate) {
        String normalizedPath = normalizePath(path);
        return routes.stream()
                .filter(route -> predicate.test(route.pattern()))
                .filter(route -> Objects.equals(origin, route.pattern().getOrigin()))
                .flatMap(route -> route.match(normalizedPath).stream());
    }

    static boolean matchesPath(String pattern, String path) {
        String normalizedPath = normalizePath(path);
        return RouteVariants.expand(pattern).stream()
                .anyMatch(variant -> CompiledPath.compile(
                        variant.path(), variant.optionalFragmentCount()).match(normalizedPath).isPresent());
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private Route<T> bestRoute(String method, String origin, String normalizedPath, Predicate<WebPattern> predicate) {
        Map<String, List<Route<T>>> routesByPrefix =
                routesByMethodOriginAndPrefix.get(new MethodOriginKey(method, origin));
        if (routesByPrefix == null) {
            return null;
        }
        Route<T> best = bestCandidate(null, routesByPrefix.get(""), normalizedPath, predicate);
        String routePath = CompiledPath.normalizeRoutePath(normalizedPath);
        if (routePath.isEmpty() || "/".equals(routePath)) {
            return best;
        }
        int segmentStart = 1;
        while (segmentStart <= routePath.length()) {
            int slash = routePath.indexOf('/', segmentStart);
            String prefix = slash < 0 ? routePath : routePath.substring(0, slash);
            best = bestCandidate(best, routesByPrefix.get(prefix), normalizedPath, predicate);
            if (slash < 0) {
                return best;
            }
            segmentStart = slash + 1;
        }
        return best;
    }

    private Route<T> bestCandidate(Route<T> best, List<Route<T>> candidates, String normalizedPath,
                                   Predicate<WebPattern> predicate) {
        if (candidates == null) {
            return best;
        }
        for (Route<T> route : candidates) {
            if (predicate.test(route.pattern()) && route.matches(normalizedPath)
                && (best == null || compareRoutes(route, best) > 0)) {
                best = route;
            }
        }
        return best;
    }

    private static int compareRoutes(Route<?> first, Route<?> second) {
        int result = Integer.compare(first.path().literalChars(), second.path().literalChars());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(first.path().literalSegments(), second.path().literalSegments());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(first.path().segmentCount(), second.path().segmentCount());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(first.path().regexParameterCount(), second.path().regexParameterCount());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(second.path().catchAllCount(), first.path().catchAllCount());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(second.path().wildcardCount(), first.path().wildcardCount());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(second.path().parameterCount(), first.path().parameterCount());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(second.path().optionalFragmentCount(), first.path().optionalFragmentCount());
        return result != 0 ? result : Integer.compare(second.order(), first.order());
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
        boolean matches(String path) {
            return this.path.matches(path);
        }

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
            int catchAllCount,
            int optionalFragmentCount,
            String literalPrefix
    ) {
        static CompiledPath compile(String path) {
            return compile(path, 0);
        }

        static CompiledPath compile(String path, int optionalFragmentCount) {
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
                    regexParameterCount, wildcardCount, catchAllCount, optionalFragmentCount,
                    literalPrefix(normalizedPath));
        }

        private static String normalizeRoutePath(String path) {
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path;
        }

        private static String literalPrefix(String normalizedPath) {
            if (normalizedPath.isEmpty() || "/".equals(normalizedPath)) {
                return "";
            }
            int dynamicStart = firstDynamicPathElement(normalizedPath);
            if (dynamicStart < 0) {
                return normalizedPath;
            }
            int segmentStart = normalizedPath.lastIndexOf('/', Math.max(0, dynamicStart - 1));
            if (segmentStart <= 0) {
                return "";
            }
            return normalizedPath.substring(0, segmentStart);
        }

        private static int firstDynamicPathElement(String path) {
            int parameter = path.indexOf('{');
            int wildcard = path.indexOf('*');
            if (parameter < 0) {
                return wildcard;
            }
            if (wildcard < 0) {
                return parameter;
            }
            return Math.min(parameter, wildcard);
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

        boolean matches(String path) {
            return regex.matcher(path).matches();
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

    record MethodOriginKey(String method, String origin) {
    }

    record RouteVariant(String path, int optionalFragmentCount) {
    }

    static class RouteVariants {
        static List<RouteVariant> expand(String path) {
            return expand(path, 0);
        }

        private static List<RouteVariant> expand(String path, int optionalFragmentCount) {
            OptionalFragment fragment = firstOptionalFragment(path);
            if (fragment == null) {
                return List.of(new RouteVariant(path, optionalFragmentCount));
            }
            String prefix = path.substring(0, fragment.start());
            String optional = path.substring(fragment.start() + 1, fragment.end());
            String suffix = path.substring(fragment.end() + 1);
            List<RouteVariant> result = new ArrayList<>();
            result.addAll(expand(prefix + optional + suffix, optionalFragmentCount + 1));
            result.addAll(expand(prefix + suffix, optionalFragmentCount + 1));
            return result;
        }

        private static OptionalFragment firstOptionalFragment(String path) {
            int parameterDepth = 0;
            int optionalDepth = 0;
            int start = -1;
            for (int i = 0; i < path.length(); i++) {
                char current = path.charAt(i);
                if (current == '{') {
                    parameterDepth++;
                } else if (current == '}') {
                    parameterDepth = Math.max(0, parameterDepth - 1);
                } else if (parameterDepth == 0 && current == '[') {
                    if (optionalDepth == 0) {
                        start = i;
                    }
                    optionalDepth++;
                } else if (parameterDepth == 0 && current == ']') {
                    if (optionalDepth == 0) {
                        throw new IllegalArgumentException(
                                "Route optional fragment opening delimiter '[' is missing in: " + path);
                    }
                    optionalDepth--;
                    if (optionalDepth == 0) {
                        return new OptionalFragment(start, i);
                    }
                }
            }
            if (optionalDepth > 0) {
                throw new IllegalArgumentException(
                        "Route optional fragment closing delimiter ']' is missing in: " + path);
            }
            return null;
        }
    }

    record OptionalFragment(int start, int end) {
    }
}
