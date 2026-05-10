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

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static io.fluxzero.sdk.web.HttpRequestMethod.ANY;
import static io.fluxzero.sdk.web.HttpRequestMethod.DELETE;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.HEAD;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;
import static io.fluxzero.sdk.web.HttpRequestMethod.PATCH;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static io.fluxzero.sdk.web.HttpRequestMethod.PUT;
import static io.fluxzero.sdk.web.HttpRequestMethod.TRACE;

/**
 * Shared registry of web routes known to a handler factory.
 */
class WebRouteRegistry {
    private static final List<String> STANDARD_HTTP_METHODS = List.of(GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS,
                                                                      TRACE);

    private final WebRouteMatcher<Entry> routes = new WebRouteMatcher<>();

    synchronized void register(Object owner, List<WebPattern> patterns) {
        patterns.forEach(pattern -> routes.add(pattern, new Entry(owner)));
    }

    synchronized Optional<Entry> automaticHeadOwner(String origin, String path) {
        if (hasExplicit(HEAD, origin, path)) {
            return Optional.empty();
        }
        return bestMatch(origin, path, pattern -> GET.equals(pattern.getMethod()) && pattern.isAutoHead());
    }

    synchronized Optional<AutomaticOptions> automaticOptions(String origin, String path) {
        if (hasExplicit(OPTIONS, origin, path)) {
            return Optional.empty();
        }
        List<WebRouteMatcher.Match<Entry>> matches = activeMatches(
                origin, path, pattern -> pattern.isAutoOptions()
                                     && !HttpRequestMethod.isWebsocket(pattern.getMethod()));
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        WebRouteMatcher.Match<Entry> owner = matches.stream()
                .max((a, b) -> WebRouteMatcher.MOST_SPECIFIC.compare(a, b)).orElseThrow();
        return Optional.of(new AutomaticOptions(owner.value().owner(), allowedMethods(matches)));
    }

    private boolean hasExplicit(String method, String origin, String path) {
        return routes.matches(origin, path, pattern -> method.equals(pattern.getMethod())
                                                     || ANY.equals(pattern.getMethod()))
                .anyMatch(m -> m.value().owner() != null);
    }

    private Optional<Entry> bestMatch(String origin, String path, Predicate<WebPattern> predicate) {
        return activeMatches(origin, path, predicate).stream()
                .max((a, b) -> WebRouteMatcher.MOST_SPECIFIC.compare(a, b))
                .map(WebRouteMatcher.Match::value);
    }

    private List<WebRouteMatcher.Match<Entry>> activeMatches(
            String origin, String path, Predicate<WebPattern> predicate) {
        return routes.matches(origin, path, predicate)
                .filter(match -> match.value().owner() != null).toList();
    }

    private static List<String> allowedMethods(List<WebRouteMatcher.Match<Entry>> matches) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String method : STANDARD_HTTP_METHODS) {
            for (WebRouteMatcher.Match<Entry> match : matches) {
                WebPattern pattern = match.pattern();
                if (ANY.equals(pattern.getMethod())) {
                    allowed.add(method);
                } else if (method.equals(pattern.getMethod())) {
                    allowed.add(method);
                } else if (HEAD.equals(method) && GET.equals(pattern.getMethod()) && pattern.isAutoHead()) {
                    allowed.add(method);
                }
            }
        }
        allowed.add(OPTIONS);
        return List.copyOf(allowed);
    }

    record Entry(WeakReference<Object> ownerRef) {
        Entry(Object owner) {
            this(new WeakReference<>(owner));
        }

        Object owner() {
            return ownerRef.get();
        }
    }

    record AutomaticOptions(Object owner, List<String> allowedMethods) {
    }
}
