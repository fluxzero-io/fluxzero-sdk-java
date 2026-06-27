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

package io.fluxzero.sdk.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class WebRoutePaths {
    private static final Pattern ABSOLUTE_URL_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z\\d+\\-.]*://.*");

    private WebRoutePaths() {
    }

    static Optional<String> pathValue(List<AnnotationDescriptor> annotations, String blankDefault) {
        return annotations.stream()
                .map(annotation -> annotation.find("Path", "io.fluxzero.sdk.web.Path"))
                .flatMap(Optional::stream)
                .reduce((first, second) -> second)
                .flatMap(annotation -> annotation.firstValue("value"))
                .map(value -> value.isBlank() ? blankDefault : value);
    }

    static List<String> paths(
            List<String> packagePaths, Optional<String> typePath, Optional<String> methodPath,
            List<String> handlerPaths) {
        List<String> hierarchy = Stream.concat(
                packagePaths.stream(), Stream.concat(typePath.stream(), methodPath.stream())).toList();
        String rootPath = hierarchy.stream()
                .reduce((first, second) -> isAbsolutePathOrUrl(second) ? second : concatenateUrlParts(first, second))
                .orElse("");
        List<String> paths = handlerPaths.isEmpty() ? List.of("") : handlerPaths;
        return paths.stream().map(path -> concatenateUrlParts(rootPath, path)).distinct().toList();
    }

    private static String concatenateUrlParts(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        List<String> cleaned = new ArrayList<>();
        boolean first = true;
        boolean startsWithSlash = false;
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (isAbsoluteUrl(part)) {
                cleaned.clear();
                cleaned.add(part.replaceAll("/+$", ""));
                first = false;
                startsWithSlash = false;
                continue;
            }
            if (first) {
                startsWithSlash = part.startsWith("/");
                part = part.replaceAll("^/+", "").replaceAll("/+$", "");
                if (!part.isEmpty()) {
                    cleaned.add(part);
                }
                first = false;
            } else {
                cleaned.add(part.replaceAll("^/+", "").replaceAll("/+$", ""));
            }
        }
        String joined = String.join("/", cleaned);
        if (!joined.contains("://")
            && (startsWithSlash || (!joined.isBlank() && !joined.startsWith("/") && !joined.equals("*")))) {
            joined = "/" + joined;
        }
        return joined;
    }

    private static boolean isAbsolutePathOrUrl(String value) {
        return value != null && (value.startsWith("/") || isAbsoluteUrl(value));
    }

    private static boolean isAbsoluteUrl(String value) {
        return value != null && ABSOLUTE_URL_PATTERN.matcher(value).matches();
    }
}
