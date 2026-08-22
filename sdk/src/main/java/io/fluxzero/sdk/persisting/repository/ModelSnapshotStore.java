/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static io.fluxzero.common.api.modeling.ModelSnapshotMutation.MODEL_ID_FACET;
import static io.fluxzero.common.api.modeling.ModelSnapshotMutation.SEQUENCE_NUMBER;
import static io.fluxzero.common.api.modeling.ModelSnapshotMutation.STATE_INDEX;

/**
 * Snapshot persistence dedicated to independent models.
 * <p>
 * A snapshot records both the per-model sequence and namespace-wide state boundary. Historical reconstruction can
 * therefore choose a snapshot that was visible at its exact {@code stateIndex}; aggregate snapshot metadata is not
 * reused because it has no such boundary.
 */
@Slf4j
final class ModelSnapshotStore {
    static final String SNAPSHOT_COLLECTION =
            ModelSnapshotMutation.COLLECTION;

    private final DocumentStore documentStore;
    private final Serializer serializer;

    ModelSnapshotStore(DocumentStore documentStore, Serializer serializer) {
        this.documentStore = documentStore;
        this.serializer = serializer;
    }

    Optional<Snapshot> getSnapshot(String modelId, Long maxStateIndex) {
        try {
            var search = documentStore.search(SNAPSHOT_COLLECTION)
                    .matchFacet(MODEL_ID_FACET,
                                modelId)
                    .sortBy("sequenceNumber", true);
            // State indices span the full opaque long range. Select against their exact facet; snapshot retention
            // bounds this stream independently of the document store's sortable encoding.
            try (Stream<SerializedDocument> snapshots =
                         search.stream(SerializedDocument.class)) {
                return snapshots
                        .filter(document -> maxStateIndex == null
                                            || facetLong(document, STATE_INDEX) <= maxStateIndex)
                        .findFirst()
                        .flatMap(this::deserialize);
            }
        } catch (Exception e) {
            throw new EventSourcingException(
                    "Failed to obtain a snapshot for model " + modelId, e);
        }
    }

    private Optional<Snapshot> deserialize(
            SerializedDocument document) {
        try {
            return Optional.of(new Snapshot(
                    serializer.deserialize(document.getDocument()),
                    facetLong(document,
                              SEQUENCE_NUMBER),
                    facetLong(document,
                              STATE_INDEX),
                    Instant.ofEpochMilli(
                            document.getTimestamp())));
        } catch (DeserializationException e) {
            log.warn("Failed to deserialize model snapshot {} for {}. Deleting snapshot.",
                     document.getId(),
                     facetValue(document,
                                MODEL_ID_FACET),
                     e);
            documentStore.deleteDocument(document.getId(), SNAPSHOT_COLLECTION);
            return Optional.empty();
        }
    }

    private static long facetLong(
            SerializedDocument document,
            String name) {
        return Long.parseLong(
                facetValue(document, name));
    }

    private static String facetValue(
            SerializedDocument document,
            String name) {
        return document.getFacets().stream()
                .filter(facet ->
                                name.equals(
                                        facet.getName()))
                .map(FacetEntry::getValue)
                .findFirst()
                .orElseThrow(() ->
                                     new IllegalStateException(
                                             "Model snapshot %s has no %s facet"
                                                     .formatted(
                                                             document.getId(),
                                                             name)));
    }

    record Snapshot(
            Object value, long sequenceNumber, long stateIndex, Instant timestamp) {
    }
}
