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
import io.fluxzero.common.api.search.SerializedDocument;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Applies graph-view constraints, sorting, field selection and pagination after live graph composition.
 * <p>
 * Keeping this operation shared ensures that a live graph query has the same result semantics as an ordinary query
 * against a materialized graph collection. Candidate-root discovery and graph composition remain explicitly bounded
 * by the caller before this operation is invoked.
 */
public final class ModelGraphDocumentSearch {

    private ModelGraphDocumentSearch() {
    }

    /**
     * Applies the complete search request to already composed graph documents.
     */
    public static List<SerializedDocument> apply(
            List<SerializedDocument> documents,
            SearchDocuments search) {
        Objects.requireNonNull(
                documents, "Graph documents");
        Objects.requireNonNull(
                search, "Graph search");

        Stream<SerializedDocument> result =
                documents.stream()
                        .filter(document -> matches(
                                document,
                                search.getQuery()))
                        .sorted(java.util.Comparator.comparing(
                                SerializedDocument::deserializeDocument,
                                Document.createComparator(search)));
        if (!search.getPathFilters().isEmpty()) {
            Predicate<Document.Path> pathFilter =
                    search.computePathFilter();
            result = result.map(document ->
                                        new SerializedDocument(
                                                document.deserializeDocument()
                                                        .filterPaths(
                                                                pathFilter)));
        }
        if (search.getSkip() > 0) {
            result = result.skip(
                    search.getSkip());
        }
        if (search.getLastHit() != null) {
            SerializedDocument lastHit =
                    search.getLastHit();
            result = result.dropWhile(document ->
                                              !sameDocument(
                                                      document,
                                                      lastHit))
                    .skip(1);
        }
        if (search.getMaxSize() != null) {
            result = result.limit(
                    search.getMaxSize());
        }
        return result.toList();
    }

    private static boolean matches(
            SerializedDocument document,
            io.fluxzero.common.api.search.SearchQuery query) {
        /*
         * A JDBC search result exposes its PostgreSQL tsvector as a summary. That representation is deliberately
         * lossy: for example, "root-00000" may be returned as the lexemes "root -00000". Summary checks are a safe
         * prefilter while PostgreSQL owns the complete query, but can cause false negatives when constraints are
         * evaluated in Java after graph composition. The composed document contains every entry, so evaluate those
         * entries without the summary shortcut and return the original document unchanged.
         */
        Document searchable =
                document.deserializeDocument()
                        .toBuilder()
                        .summary(() -> null)
                        .build();
        return query.matches(
                new SerializedDocument(searchable));
    }

    private static boolean sameDocument(
            SerializedDocument first,
            SerializedDocument second) {
        return Objects.equals(
                       first.getId(),
                       second.getId())
               && Objects.equals(
                       first.getCollection(),
                       second.getCollection());
    }
}
