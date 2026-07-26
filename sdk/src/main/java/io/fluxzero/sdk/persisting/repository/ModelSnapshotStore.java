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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.common.search.SearchInclude;
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.Searchable;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.Guarantee.STORED;

/**
 * Snapshot persistence dedicated to independent models.
 * <p>
 * A snapshot records both the per-model sequence and namespace-wide state boundary. Historical reconstruction can
 * therefore choose a snapshot that was visible at its exact {@code stateIndex}; aggregate snapshot metadata is not
 * reused because it has no such boundary.
 */
@Slf4j
final class ModelSnapshotStore {
    static final String SNAPSHOT_COLLECTION = "$modelSnapshots";

    private final DocumentStore documentStore;
    private final Serializer serializer;

    ModelSnapshotStore(DocumentStore documentStore, Serializer serializer) {
        this.documentStore = documentStore;
        this.serializer = serializer;
    }

    Optional<Snapshot> getSnapshot(String modelId, Long maxStateIndex) {
        try {
            var search = documentStore.search(SNAPSHOT_COLLECTION)
                    .match(modelId, true, "modelId")
                    .sortBy("sequenceNumber", true);
            if (maxStateIndex != null && maxStateIndex < Long.MAX_VALUE) {
                search = search.below(maxStateIndex + 1L, "stateIndex");
            }
            return search.fetchFirst(SnapshotDocument.class)
                    .flatMap(this::deserialize);
        } catch (Exception e) {
            throw new EventSourcingException(
                    "Failed to obtain a snapshot for model " + modelId, e);
        }
    }

    CompletableFuture<Void> storeSnapshot(
            String modelId,
            Object value,
            long sequenceNumber,
            long stateIndex,
            Instant timestamp,
            int maxSnapshotCount) {
        if (value == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            SnapshotDocument document = new SnapshotDocument(
                    snapshotKey(modelId, sequenceNumber),
                    modelId, sequenceNumber, stateIndex, timestamp,
                    serializer.serialize(value));
            return documentStore.prepareIndex(document).index(STORED)
                    .thenCompose(ignored -> trim(modelId, maxSnapshotCount));
        } catch (Exception e) {
            throw new EventSourcingException(
                    "Failed to store a snapshot for model " + modelId, e);
        }
    }

    private CompletableFuture<Void> trim(
            String modelId, int configuredMaxSnapshotCount) {
        int maxSnapshotCount = Math.max(1, configuredMaxSnapshotCount);
        List<SnapshotDocument> snapshots = documentStore.search(SNAPSHOT_COLLECTION)
                .match(modelId, true, "modelId")
                .sortBy("sequenceNumber", true)
                .fetch(maxSnapshotCount + 1, SnapshotDocument.class);
        if (snapshots.size() <= maxSnapshotCount) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(
                snapshots.subList(maxSnapshotCount, snapshots.size()).stream()
                        .map(snapshot -> documentStore.deleteDocument(
                                snapshot.id(), SNAPSHOT_COLLECTION))
                        .toArray(CompletableFuture[]::new));
    }

    private Optional<Snapshot> deserialize(SnapshotDocument document) {
        try {
            return Optional.of(new Snapshot(
                    serializer.deserialize(document.serializedValue()),
                    document.sequenceNumber(), document.stateIndex(),
                    document.timestamp()));
        } catch (DeserializationException e) {
            log.warn("Failed to deserialize model snapshot {} for {}. Deleting snapshot.",
                     document.id(), document.modelId(), e);
            documentStore.deleteDocument(document.id(), SNAPSHOT_COLLECTION);
            return Optional.empty();
        }
    }

    private static String snapshotKey(String modelId, long sequenceNumber) {
        return "$modelSnapshot_" + modelId + "_" + sequenceNumber;
    }

    record Snapshot(
            Object value, long sequenceNumber, long stateIndex, Instant timestamp) {
    }

    @Searchable(collection = SNAPSHOT_COLLECTION, timestampPath = "timestamp")
    @SearchExclude
    private record SnapshotDocument(
            @EntityId String id,
            @SearchInclude String modelId,
            @Sortable long sequenceNumber,
            @Sortable long stateIndex,
            Instant timestamp,
            Data<byte[]> serializedValue) {
    }
}
