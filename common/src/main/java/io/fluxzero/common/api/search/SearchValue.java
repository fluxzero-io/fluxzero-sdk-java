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

package io.fluxzero.common.api.search;

import io.fluxzero.common.api.search.constraints.BetweenConstraint;
import io.fluxzero.common.search.CollectionValues;
import io.fluxzero.common.search.Document;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Explicit minimum or maximum selection for range filters and ordering.
 * An annotated document without a minimum retains maximum semantics until the application reindexes it.
 */
public record SearchValue(String path, ValueSelection selection) {
    public SearchValue {
        Objects.requireNonNull(path, "path");
        if (selection != ValueSelection.MIN && selection != ValueSelection.MAX)
            throw new IllegalArgumentException("Select MIN or MAX for a search value");
    }
    /** Selects the lowest non-null field value. */
    public static SearchValue min(String path) { return new SearchValue(path, ValueSelection.MIN); }
    /** Selects the highest non-null field value. */
    public static SearchValue max(String path) { return new SearchValue(path, ValueSelection.MAX); }
    /** Compares the selected value against an inclusive lower and exclusive upper bound. */
    public BetweenConstraint between(Object lower, Object upper) {
        return BetweenConstraint.between(lower, upper, path).withValueSelection(selection);
    }
    /** Compares the selected value against an exclusive upper bound. */
    public BetweenConstraint below(Object upper) { return between(null, upper); }
    /** Compares the selected value against an inclusive lower bound. */
    public BetweenConstraint atLeast(Object lower) { return between(lower, null); }
    /** Internal wire spelling; the path is encoded to preserve arbitrary delimiters. */
    public String sortPath() {
        return "$values:" + selection + ":" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }
    /** Decodes an explicit collection-value ordering key, or returns null for an ordinary key. */
    public static SearchValue fromSortPath(String path) {
        if (!path.startsWith("$values:")) return null;
        int separator = path.indexOf(':', 8);
        if (separator < 0) throw new IllegalArgumentException("Invalid value sort key");
        return new SearchValue(new String(Base64.getUrlDecoder().decode(path.substring(separator + 1)),
                                          StandardCharsets.UTF_8), ValueSelection.valueOf(path.substring(8, separator)));
    }
    /** Computes the selected value from sortable entries, with maximum fallback for legacy entries, or raw unannotated values. */
    public String key(Document document) { return CollectionValues.extremum(document, path, selection); }

    /** Resolves a response-only continuation key, falling back to the complete document's field values. */
    public String key(SerializedDocument document) {
        return document.getCollectionSortKeys().containsKey(sortPath())
                ? document.getCollectionSortKeys().get(sortPath()) : key(document.deserializeDocument());
    }

    /** Projects a response while transporting its ordering values independently of stored document bytes. */
    public static SerializedDocument project(SerializedDocument source, java.util.List<String> sorting,
                                             java.util.function.Predicate<Document.Path> pathFilter) {
        var keys = new java.util.LinkedHashMap<String, String>();
        Document document = source.deserializeDocument();
        for (String sort : sorting) {
            String path = sort.startsWith("-") ? sort.substring(1) : sort;
            path = path.replaceFirst(":nulls(First|Last)$", "");
            SearchValue value = fromSortPath(path);
            if (value != null) {
                String key = value.key(source);
                if (key != null) keys.put(value.sortPath(), key);
            }
        }
        return new SerializedDocument(document.filterPaths(pathFilter)).toBuilder().collectionSortKeys(keys).build();
    }
}
