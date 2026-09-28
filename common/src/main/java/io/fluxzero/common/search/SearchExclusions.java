/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.common.search;

import io.fluxzero.common.SearchUtils;
import io.fluxzero.common.search.Document.Entry;
import io.fluxzero.common.search.Document.EntryType;
import io.fluxzero.common.search.Document.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sparse field exclusions for text search, encoded as ordinary internal document metadata.
 * The binary document format and retained property values remain unchanged. Documents written before this
 * information was available retain summary-based matching until they are reindexed from their typed values.
 */
public final class SearchExclusions {
    /** Reserved metadata key mapping concrete, escaped document paths to {@code true}. */
    public static final String METADATA_KEY = "$searchExcluded";
    private static final String PATH = JacksonInverter.metadataPath(METADATA_KEY);
    private static final String PREFIX = PATH + "/";
    private static final Entry TRUE = new Entry(EntryType.BOOLEAN, "true");

    private SearchExclusions() {
    }

    /** Returns whether a path belongs to the internal exclusion metadata rather than user content. */
    public static boolean isMetadataPath(Path path) {
        return path.getValue().equals(PATH) || path.getValue().startsWith(PREFIX);
    }

    /** Adds excluded raw paths using the existing binary document metadata representation. */
    public static void addTo(Map<Entry, List<Path>> entries, Collection<String> excludedPaths) {
        if (excludedPaths.isEmpty()) {
            return;
        }
        var paths = new ArrayList<>(entries.getOrDefault(TRUE, List.of()));
        excludedPaths.stream().map(path -> new Path(PREFIX + SearchUtils.escapeFieldName(path)))
                .forEach(paths::add);
        entries.put(TRUE, paths);
    }

    /** Reads concrete excluded paths without reconstructing the document body or all of its metadata. */
    public static Set<String> read(Map<Entry, List<Path>> entries) {
        var paths = entries.get(TRUE);
        return paths == null ? Set.of() : paths.stream().map(Path::getValue)
                .filter(p -> p.startsWith(PREFIX))
                .map(p -> SearchUtils.unescapeFieldName(p.substring(PREFIX.length())))
                .collect(Collectors.toUnmodifiableSet());
    }
}
