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
import io.fluxzero.common.api.search.Constraint;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.modeling.Graph;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Search over complete independent-model graph views.
 * <p>
 * Unlike an ordinary model search, each result is a typed, lazily materialized {@link Graph}. No-type terminal
 * operations such as {@link #fetch(int)}, {@link #fetchAll()}, {@link #stream()} and {@link #streamHits()} therefore
 * return graphs by default. Raw JSON remains available explicitly through {@link #fetchJsonGraphs(int)}. Callers that
 * need transport or inverted-document details can request {@link SerializedDocument} or
 * {@link io.fluxzero.common.search.Document} explicitly.
 * <p>
 * The default route reads a configured materialized graph collection and otherwise composes the current graph live.
 * A caller can force live composition through {@link DocumentStore#searchGraph(Class, boolean)}.
 */
public interface GraphSearch<T> extends Search {

    @Override default GraphSearch<T> since(Instant start) { return castSearch(Search.super.since(start)); }
    @Override GraphSearch<T> since(Instant start, boolean inclusive);
    @Override default GraphSearch<T> since(LocalDate start) { return castSearch(Search.super.since(start)); }
    @Override default GraphSearch<T> before(Instant end) { return castSearch(Search.super.before(end)); }
    @Override GraphSearch<T> before(Instant end, boolean inclusive);
    @Override default GraphSearch<T> before(LocalDate end) { return castSearch(Search.super.before(end)); }
    @Override default GraphSearch<T> beforeLast(Duration period) {
        return castSearch(Search.super.beforeLast(period));
    }
    @Override default GraphSearch<T> inLast(Duration period) { return castSearch(Search.super.inLast(period)); }
    @Override default GraphSearch<T> inPeriod(Instant start, Instant end) {
        return castSearch(Search.super.inPeriod(start, end));
    }
    @Override GraphSearch<T> inPeriod(
            Instant start, boolean startInclusive, Instant end, boolean endInclusive);
    @Override default GraphSearch<T> inPeriod(LocalDate start, LocalDate end) {
        return castSearch(Search.super.inPeriod(start, end));
    }
    @Override default GraphSearch<T> lookAhead(String phrase, String... paths) {
        return castSearch(Search.super.lookAhead(phrase, paths));
    }
    @Override default GraphSearch<T> query(String phrase, String... paths) {
        return castSearch(Search.super.query(phrase, paths));
    }
    @Override default GraphSearch<T> match(Object value, String... paths) {
        return castSearch(Search.super.match(value, paths));
    }
    @Override default GraphSearch<T> match(Object value, boolean strict, String... paths) {
        return castSearch(Search.super.match(value, strict, paths));
    }
    @Override default GraphSearch<T> matchFacet(String name, Object value) {
        return castSearch(Search.super.matchFacet(name, value));
    }
    @Override default GraphSearch<T> matchMetadata(String key, Object value) {
        return castSearch(Search.super.matchMetadata(key, value));
    }
    @Override default GraphSearch<T> anyExist(String... paths) {
        return castSearch(Search.super.anyExist(paths));
    }
    @Override default GraphSearch<T> atLeast(Number min, String path) {
        return castSearch(Search.super.atLeast(min, path));
    }
    @Override default GraphSearch<T> below(Number max, String path) {
        return castSearch(Search.super.below(max, path));
    }
    @Override default GraphSearch<T> between(Number min, Number max, String path) {
        return castSearch(Search.super.between(min, max, path));
    }
    @Override default GraphSearch<T> all(Constraint... constraints) {
        return castSearch(Search.super.all(constraints));
    }
    @Override default GraphSearch<T> any(Constraint... constraints) {
        return castSearch(Search.super.any(constraints));
    }
    @Override default GraphSearch<T> not(Constraint constraint) {
        return castSearch(Search.super.not(constraint));
    }
    @Override GraphSearch<T> constraint(Constraint... constraints);
    @Override default GraphSearch<T> whereParent(Object collection, Constraint... constraints) {
        return castSearch(Search.super.whereParent(collection, constraints));
    }
    @Override default GraphSearch<T> whereAncestor(Object collection, Constraint... constraints) {
        return castSearch(Search.super.whereAncestor(collection, constraints));
    }
    @Override default GraphSearch<T> whereAncestor(
            Object collection, int minDepth, int maxDepth, Constraint... constraints) {
        return castSearch(Search.super.whereAncestor(
                collection, minDepth, maxDepth, constraints));
    }
    @Override default GraphSearch<T> whereChild(Object collection, Constraint... constraints) {
        return castSearch(Search.super.whereChild(collection, constraints));
    }
    @Override default GraphSearch<T> whereDescendant(Object collection, Constraint... constraints) {
        return castSearch(Search.super.whereDescendant(collection, constraints));
    }
    @Override default GraphSearch<T> whereDescendant(
            Object collection, int minDepth, int maxDepth, Constraint... constraints) {
        return castSearch(Search.super.whereDescendant(
                collection, minDepth, maxDepth, constraints));
    }
    @Override GraphSearch<T> relation(ModelRelationConstraint... constraints);
    @Override default GraphSearch<T> sortByTimestamp() {
        return castSearch(Search.super.sortByTimestamp());
    }
    @Override GraphSearch<T> sortByTimestamp(boolean descending);
    @Override default GraphSearch<T> sortByTimestamp(boolean descending, NullOrder nullOrder) {
        return castSearch(Search.super.sortByTimestamp(descending, nullOrder));
    }
    @Override GraphSearch<T> sortByScore();
    @Override default GraphSearch<T> sortBy(String path) { return castSearch(Search.super.sortBy(path)); }
    @Override GraphSearch<T> sortBy(String path, boolean descending);
    @Override default GraphSearch<T> sortBy(String path, NullOrder nullOrder) {
        return castSearch(Search.super.sortBy(path, nullOrder));
    }
    @Override default GraphSearch<T> sortBy(String path, boolean descending, NullOrder nullOrder) {
        return castSearch(Search.super.sortBy(path, descending, nullOrder));
    }
    @Override GraphSearch<T> exclude(String... paths);
    @Override GraphSearch<T> includeOnly(String... paths);
    @Override GraphSearch<T> skip(Integer n);

    /**
     * Fetches typed lazy graph results without relying on generic type inference.
     */
    default List<Graph<T>> fetchGraphs(
            int maxSize) {
        return cast(fetch(maxSize, Graph.class));
    }

    /**
     * Fetches all typed lazy graph results.
     */
    default List<Graph<T>> fetchAllGraphs() {
        return cast(fetchAll(Graph.class));
    }

    /**
     * Fetches typed lazy graph results asynchronously.
     */
    default CompletableFuture<List<Graph<T>>>
            fetchGraphsAsync(int maxSize) {
        return fetchAsync(maxSize, Graph.class)
                .thenApply(GraphSearch::cast);
    }

    /**
     * Streams typed lazy graph results.
     */
    default Stream<Graph<T>> streamGraphs() {
        return castStream(stream(Graph.class));
    }

    /**
     * Streams typed lazy graph hits with document identity and metadata.
     */
    default Stream<SearchHit<Graph<T>>>
            streamGraphHits() {
        return castHitStream(streamHits(Graph.class));
    }

    /** Fetches the legacy raw JSON graph representation explicitly. */
    default List<ObjectNode> fetchJsonGraphs(int maxSize) {
        return fetch(maxSize, ObjectNode.class);
    }

    /** Fetches all legacy raw JSON graph representations explicitly. */
    default List<ObjectNode> fetchAllJsonGraphs() {
        return fetchAll(ObjectNode.class);
    }

    /**
     * Fetches raw graph documents.
     */
    default List<SerializedDocument> fetchDocuments(
            int maxSize) {
        return fetch(
                maxSize, SerializedDocument.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> List<Graph<T>> cast(List<? extends Graph> graphs) {
        return (List) graphs;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Stream<Graph<T>> castStream(Stream<? extends Graph> graphs) {
        return (Stream) graphs;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Stream<SearchHit<Graph<T>>> castHitStream(
            Stream<? extends SearchHit<? extends Graph>> hits) {
        return (Stream) hits;
    }

    @SuppressWarnings("unchecked")
    private static <T> GraphSearch<T> castSearch(Search search) {
        return (GraphSearch<T>) search;
    }
}
