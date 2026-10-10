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

package io.fluxzero.common.search;

import io.fluxzero.common.api.search.ValueSelection;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Collection intervals using the existing sortable encoding and per-document legacy fallback. */
public final class CollectionValues {
    private CollectionValues() { }

    /** Compares normalized keys in Unicode codepoint order, matching PostgreSQL's binary C collation. */
    public static int compare(String left, String right) {
        if (left == right) return 0;
        for (int i = 0, j = 0; i < left.length() && j < right.length();) {
            int a = left.codePointAt(i), b = right.codePointAt(j);
            if (a != b) return Integer.compare(a, b);
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length(), right.length());
    }


    /**
     * Uses existing sortable numeric precision and normalization. Uppercase UTC ISO timestamp text, as emitted by
     * Instant JSON serialization, uses the existing fixed-millisecond timestamp encoding. The document text is
     * unchanged. Literal strings with this exact syntax are indistinguishable from serialized Instants here.
     */
    public static String key(Object value) {
        if (value instanceof String text && looksLikeTimestamp(text)) {
            try {
                // JSON inversion retains Instant as UTC text with optional fractional seconds. Use the existing
                // millisecond index encoding, without changing the stored text or truncating document contents.
                return io.fluxzero.common.SearchUtils.ISO_FULL.format(Instant.parse(text));
            } catch (DateTimeParseException ignored) {
                // Timestamp-shaped but invalid text remains ordinary searchable text.
            }
        }
        return value == null ? null : io.fluxzero.common.api.search.SortableEntry.formatSortable(value);
    }

    private static boolean looksLikeTimestamp(String value) {
        int length = value.length();
        if (length < 20 || length > 37 || value.charAt(length - 1) != 'Z') return false;
        int time = value.indexOf('T');
        return time >= 10 && length > time + 8 && value.charAt(time + 3) == ':'
                && value.charAt(time + 6) == ':';
    }

    /** Encodes a raw document scalar with the same rules as annotated values. */
    public static String key(Document.Entry entry) {
        return switch (entry.getType()) {
            case NULL, EMPTY_ARRAY, EMPTY_OBJECT -> null;
            case NUMERIC -> key(entry.asNumber());
            default -> key(entry.getValue());
        };
    }

    /** Streams comparison keys at one path. Nulls and internal metadata are excluded. */
    public static Stream<String> values(Document document, String path) {
        Predicate<Document.Path> predicate = Document.Path.pathPredicate(path)
                .and(ModelSearchDocument::isSearchablePath);
        return document.getMatchingEntries(predicate).map(CollectionValues::key)
                .filter(java.util.Objects::nonNull);
    }

    /** Returns the requested extremum, or null for missing, empty or null-only values. */
    public static String extremum(Document document, String path, ValueSelection selection) {
        var predicate = Document.Path.pathPredicate(path);
        var indexes = document.getSortableEntries(predicate).toList();
        var indexedPaths = indexes.stream().map(e -> e.getPath().getShortValue()).collect(java.util.stream.Collectors.toSet());
        var raw = document.getMatchingEntries(predicate.and(ModelSearchDocument::isSearchablePath)
                        .and(p -> !indexedPaths.contains(p.getShortValue())))
                .map(CollectionValues::key).filter(java.util.Objects::nonNull);
        var stream = Stream.concat(raw, indexes.stream().map(entry ->
                selection == ValueSelection.MIN ? entry.minimumOrValue() : entry.getValue()).filter(java.util.Objects::nonNull));
        return (selection == ValueSelection.MIN ? stream.min(CollectionValues::compare) : stream.max(CollectionValues::compare))
                .orElse(null);
    }

    /** Tests interval overlap; old annotated documents without a minimum retain their maximum-only interval. */
    public static boolean overlaps(Document document, String path, String lower, String upper) {
        if (lower != null && upper != null && compare(lower, upper) >= 0) return false;
        String min = extremum(document, path, ValueSelection.MIN);
        String max = extremum(document, path, ValueSelection.MAX);
        return min != null && (lower == null || compare(max, lower) >= 0)
                && (upper == null || compare(min, upper) < 0);
    }

    /** Materializes at most two extrema per annotated path, in one pass over the document entries. */
    public static Map<String, List<String>> indexedValues(Document document) {
        return indexedValues(document, document.getSortables().stream().map(s -> s.getPath().getShortValue()).toList());
    }

    /** Collects requested canonical extrema; empty means missing, singleton means minimum equals maximum. */
    public static Map<String, List<String>> indexedValues(Document document, java.util.Collection<String> pathsToIndex) {
        Map<String, Bounds> values = new TreeMap<>();
        pathsToIndex.forEach(path -> values.putIfAbsent(path, new Bounds()));
        if (values.isEmpty()) return Map.of();
        document.getEntries().forEach((entry, paths) -> {
            String key = null;
            boolean resolved = false;
            for (Document.Path path : paths) {
                if (!SearchExclusions.isMetadataPath(path) && ModelSearchDocument.isSearchablePath(path)) {
                    var target = values.get(path.getShortValue());
                    if (target != null) {
                        if (!resolved) { key = key(entry); resolved = true; }
                        if (key != null) target.add(key);
                    }
                }
            }
        });
        Map<String, List<String>> result = new TreeMap<>();
        values.forEach((path, bounds) -> result.put(path, bounds.min == null ? List.of()
                : bounds.min.equals(bounds.max) ? List.of(bounds.min) : List.of(bounds.min, bounds.max)));
        return result;
    }

    private static final class Bounds {
        private String min, max;
        private void add(String key) {
            if (min == null || compare(key, min) < 0) min = key;
            if (max == null || compare(key, max) > 0) max = key;
        }
    }
}
