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

import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static io.fluxzero.common.search.Document.EntryType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGraphDocumentSearchTest {

    @Test
    void appliesConstraintsAndPathFiltersToComposedChildPaths() {
        SerializedDocument wanted =
                document(
                        "root-wanted",
                        Map.of(
                                "name", "first",
                                "children/0/name",
                                "wanted"));
        SerializedDocument other =
                document(
                        "root-other",
                        Map.of(
                                "name", "second",
                                "children/0/name",
                                "other"));

        List<SerializedDocument> result =
                ModelGraphDocumentSearch.apply(
                        List.of(other, wanted),
                        SearchDocuments.builder()
                                .query(SearchQuery.builder()
                                               .collection("roots")
                                               .constraint(match(
                                                       "wanted", true,
                                                       "children/name"))
                                               .build())
                                .pathFilters(
                                        List.of("children"))
                                .build());

        assertEquals(
                List.of("root-wanted"),
                result.stream()
                        .map(SerializedDocument::getId)
                        .toList());
        Document document =
                result.getFirst()
                        .deserializeDocument();
        assertEquals(
                "wanted",
                document.getEntryAtPath(
                                "children/0/name")
                        .orElseThrow()
                        .getValue());
        assertTrue(document.getEntryAtPath(
                "name").isEmpty());
    }

    @Test
    void sortsAndPagesOnComposedChildPaths() {
        SerializedDocument first =
                document(
                        "root-first",
                        Map.of("children/0/rank", "A"));
        SerializedDocument second =
                document(
                        "root-second",
                        Map.of("children/0/rank", "B"));

        List<SerializedDocument> result =
                ModelGraphDocumentSearch.apply(
                        List.of(first, second),
                        SearchDocuments.builder()
                                .query(SearchQuery.builder()
                                               .collection("roots")
                                               .build())
                                .sorting(List.of(
                                        "children/0/rank"))
                                .skip(1)
                                .maxSize(1)
                                .build());

        assertEquals(
                List.of("root-second"),
                result.stream()
                        .map(SerializedDocument::getId)
                        .toList());
    }

    private static SerializedDocument document(
            String id,
            Map<String, String> values) {
        var entries =
                new java.util.LinkedHashMap<
                        Document.Entry,
                        List<Document.Path>>();
        values.forEach((path, value) ->
                               entries.put(
                                       new Document.Entry(
                                               TEXT, value),
                                       List.of(
                                               new Document.Path(
                                                       path))));
        return new SerializedDocument(
                Document.builder()
                        .id(id)
                        .type("type")
                        .revision(0)
                        .collection("roots")
                        .timestamp(
                                Instant.ofEpochMilli(1L))
                        .entries(entries)
                        .summary(() ->
                                         String.join(
                                                 " ",
                                                 values.values()))
                        .facets(Set.of())
                        .sortables(Set.of())
                        .build());
    }
}
