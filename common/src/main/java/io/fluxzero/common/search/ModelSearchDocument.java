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

import io.fluxzero.common.api.search.SerializedDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Preserves a Model's exact text-search summary across stores that expose only a tokenized search index. */
public final class ModelSearchDocument {
    /** Internal metadata, excluded from business state and ordinary text entries. */
    public static final String SUMMARY = "$modelSearchSummary";

    private static final String SUMMARY_PATH = JacksonInverter.metadataPath(SUMMARY);
    private static final String NESTED_SUMMARY_PATH = "/" + SUMMARY_PATH;

    private ModelSearchDocument() {}

    /** Internal summary storage is not a business field, including when nested inside a composed Graph. */
    public static boolean isSearchablePath(Document.Path path) {
        return !path.getValue().equals(SUMMARY_PATH) && !path.getValue().endsWith(NESTED_SUMMARY_PATH);
    }

    /** Keeps the original summary in the serialized document, including an explicitly empty search surface. */
    public static SerializedDocument preserveSummary(SerializedDocument source) {
        return new SerializedDocument(preserveSummary(source.deserializeDocument()));
    }

    /** Keeps the summary with the entries so composition does not need to guess which values were excluded. */
    public static Document preserveSummary(Document source) {
        Map<Document.Entry, List<Document.Path>> entries = new LinkedHashMap<>(source.getEntries());
        entries.replaceAll((entry, paths) -> {
            if (paths.stream().noneMatch(p -> p.getValue().equals(SUMMARY_PATH))) { return paths; }
            return paths.stream().filter(p -> !p.getValue().equals(SUMMARY_PATH)).toList();
        });
        entries.values().removeIf(List::isEmpty);
        String summary = source.getSummary() == null ? "" : source.getSummary();
        Document.Entry entry = new Document.Entry(Document.EntryType.TEXT, summary);
        List<Document.Path> paths = new ArrayList<>(entries.getOrDefault(entry, List.of()));
        paths.add(new Document.Path(SUMMARY_PATH));
        entries.put(entry, paths);
        return source.toBuilder().entries(entries).build();
    }

    /** Restores the original summary while retaining all indexed facets, sortables and deserializable content. */
    public static Document restoreSummary(Document source) {
        String summary = source.getMetadata().get(SUMMARY);
        return summary == null ? source : source.toBuilder().summary(() -> summary).build();
    }
}
