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

package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.search.SerializedDocument;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Search over complete independent-model graph views.
 * <p>
 * Unlike an ordinary model search, a graph result is not the root model type: it additionally contains descendants
 * placed through explicit parent paths. No-type terminal operations such as {@link #fetch(int)}, {@link #fetchAll()},
 * {@link #stream()} and {@link #streamHits()} therefore return {@link ObjectNode} values by default. Callers that need
 * transport or inverted-document details can request {@link SerializedDocument} or
 * {@link io.fluxzero.common.search.Document} explicitly.
 * <p>
 * The default route reads a configured materialized graph collection and otherwise composes the current graph live.
 * A caller can force live composition through {@link DocumentStore#searchGraph(Class, boolean)}.
 */
public interface GraphSearch extends Search {

    /**
     * Fetches graph-shaped JSON results without relying on generic type inference.
     */
    default List<ObjectNode> fetchGraphs(
            int maxSize) {
        return fetch(maxSize, ObjectNode.class);
    }

    /**
     * Fetches all graph-shaped JSON results.
     */
    default List<ObjectNode> fetchAllGraphs() {
        return fetchAll(ObjectNode.class);
    }

    /**
     * Fetches graph-shaped JSON results asynchronously.
     */
    default CompletableFuture<List<ObjectNode>>
            fetchGraphsAsync(int maxSize) {
        return fetchAsync(
                maxSize, ObjectNode.class);
    }

    /**
     * Streams graph-shaped JSON results.
     */
    default Stream<ObjectNode> streamGraphs() {
        return stream(ObjectNode.class);
    }

    /**
     * Streams graph-shaped JSON hits with document identity and metadata.
     */
    default Stream<SearchHit<ObjectNode>>
            streamGraphHits() {
        return streamHits(ObjectNode.class);
    }

    /**
     * Fetches raw graph documents.
     */
    default List<SerializedDocument> fetchDocuments(
            int maxSize) {
        return fetch(
                maxSize, SerializedDocument.class);
    }
}
