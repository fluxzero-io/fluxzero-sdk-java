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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;

import java.net.URI;
import java.util.regex.Pattern;

/** Producer-side projection of web addressing onto the generic message route field. */
public final class WebRequestRoute {
    private WebRequestRoute() {}

    /**
     * Refreshes the route from the final serialized request metadata. Missing or malformed addressing clears the
     * route, retaining the ordinary web error path. Call this after serialized dispatch interceptors.
     */
    public static SerializedMessage update(SerializedMessage message) {
        if (message != null) {
            message.setRoute(describe(message.getMetadata()));
        }
        return message;
    }

    /** Returns method plus origin/raw path; query, fragment and percent-decoding never participate in selection. */
    public static String describe(Metadata metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            String method = WebRequest.getMethod(metadata);
            if (method.isBlank() || method.chars().anyMatch(Character::isWhitespace)) {
                return null;
            }
            URI uri = URI.create(WebRequest.getUrl(metadata));
            String route = method + " " + origin(uri) + WebRouteMatcher.normalizePath(uri.getRawPath());
            // Native UTF-8 cannot preserve an unpaired UTF-16 surrogate. Leave such input unrestricted.
            for (int i = 0; i < route.length(); i++) {
                char c = route.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == route.length() || !Character.isLowSurrogate(route.charAt(i))) return null;
                } else if (Character.isLowSurrogate(c)) {
                    return null;
                }
            }
            return route;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    static String origin(URI uri) {
        return uri.getScheme() == null || uri.getHost() == null ? ""
                : uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    /** Exports a compiled web path without changing the path regex's anchors or lookaround scope. */
    public static ConsumerFilter.RoutePattern pattern(String method, String origin, String pathRegex) {
        String prefix = (method == null ? "[^ ]+" : Pattern.quote(method)) + " "
                        + Pattern.quote(origin == null ? "" : origin);
        return new ConsumerFilter.RoutePattern(prefix, pathRegex);
    }
}
