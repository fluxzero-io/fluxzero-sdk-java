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

package io.fluxzero.sdk.persisting.eventsourcing;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.common.search.SearchInclude;
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.ImmutableAggregateRoot;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.Searchable;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.Guarantee.STORED;
import static java.lang.Math.max;
import static java.lang.String.format;

/**
 * Default implementation of the {@link SnapshotStore} interface.
 *
 * <p>Snapshots are stored as documents, keyed by aggregate id and sequence number. The latest snapshot is loaded by
 * sorting on the stored sequence number, while older snapshots can be retrieved to support historical
 * {@link io.fluxzero.sdk.modeling.Entity#previous()} traversal.
 */
@Slf4j
@AllArgsConstructor
public class DefaultSnapshotStore implements SnapshotStore {
    static final String SNAPSHOT_COLLECTION = "$snapshots";

    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final EventStore eventStore;

    @Override
    public <T> CompletableFuture<Void> storeSnapshot(Entity<T> snapshot) {
        try {
            return documentStore.prepareIndex(SnapshotDocument.of(snapshot, serializer.serialize(
                            ImmutableAggregateRoot.from(snapshot, null, null, eventStore))))
                    .index(STORED)
                    .thenCompose(v -> trimSnapshots(snapshot));
        } catch (Exception e) {
            throw new EventSourcingException(format("Failed to store a snapshot: %s", snapshot), e);
        }
    }

    @Override
    public <T> Optional<Entity<T>> getSnapshot(Object aggregateId) {
        return findSnapshot(aggregateId, null);
    }

    @Override
    public <T> Optional<Entity<T>> getSnapshotBefore(Object aggregateId, long sequenceNumberExclusive) {
        return findSnapshot(aggregateId, sequenceNumberExclusive);
    }

    @Override
    public CompletableFuture<Void> deleteSnapshot(Object aggregateId) {
        try {
            return documentStore.search(SNAPSHOT_COLLECTION).match(aggregateId.toString(), true, "aggregateId").delete();
        } catch (Exception e) {
            throw new EventSourcingException(format("Failed to delete snapshot for aggregate %s", aggregateId), e);
        }
    }

    protected <T> Optional<Entity<T>> findSnapshot(Object aggregateId, Long sequenceNumberExclusive) {
        try {
            var search = documentStore.search(SNAPSHOT_COLLECTION).match(aggregateId.toString(), true, "aggregateId")
                    .sortBy("sequenceNumber", true);
            if (sequenceNumberExclusive != null) {
                search = search.below(sequenceNumberExclusive, "sequenceNumber");
            }
            return search.fetchFirst(SnapshotDocument.class).flatMap(this::deserializeSnapshot);
        } catch (Exception e) {
            throw new EventSourcingException(format("Failed to obtain snapshot for aggregate %s", aggregateId), e);
        }
    }

    protected CompletableFuture<Void> trimSnapshots(Entity<?> snapshot) {
        int maxSnapshotCount = max(1, snapshot.rootAnnotation().maxSnapshotCount());
        try {
            var snapshots = documentStore.search(SNAPSHOT_COLLECTION)
                    .match(snapshot.id().toString(), true, "aggregateId")
                    .sortBy("sequenceNumber", true)
                    .fetch(maxSnapshotCount + 1, SnapshotDocument.class);
            if (snapshots.size() <= maxSnapshotCount) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.allOf(
                    snapshots.subList(maxSnapshotCount, snapshots.size()).stream()
                            .map(document -> documentStore.deleteDocument(document.id(), SNAPSHOT_COLLECTION))
                            .toArray(CompletableFuture[]::new));
        } catch (Exception e) {
            throw new EventSourcingException(format("Failed to trim snapshots for aggregate %s", snapshot.id()), e);
        }
    }

    protected <T> Optional<Entity<T>> deserializeSnapshot(SnapshotDocument document) {
        try {
            return Optional.ofNullable(serializer.deserialize(document.serializedSnapshot()));
        } catch (DeserializationException e) {
            log.warn("Failed to deserialize snapshot {} for {}. Deleting snapshot.",
                     document.id(), document.aggregateId(), e);
            documentStore.deleteDocument(document.id(), SNAPSHOT_COLLECTION);
            return Optional.empty();
        }
    }

    @Searchable(collection = SNAPSHOT_COLLECTION, timestampPath = "timestamp")
    @SearchExclude
    protected record SnapshotDocument(@EntityId String id, @SearchInclude String aggregateId,
                                      @Sortable long sequenceNumber, Instant timestamp,
                                      Data<byte[]> serializedSnapshot) {
        static SnapshotDocument of(Entity<?> snapshot, Data<byte[]> serializedSnapshot) {
            return new SnapshotDocument(snapshotKey(snapshot.id(), snapshot.sequenceNumber()),
                                        snapshot.id().toString(),
                                        snapshot.sequenceNumber(),
                                        snapshot.timestamp(),
                                        serializedSnapshot);
        }
    }

    static String snapshotKey(Object aggregateId, long sequenceNumber) {
        return "$snapshot_" + aggregateId + "_" + sequenceNumber;
    }
}
