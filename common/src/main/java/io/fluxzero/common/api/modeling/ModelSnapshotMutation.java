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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.SortableEntry;
import lombok.Value;

import java.util.Set;

/**
 * Optional serialized snapshot consequence of one model target transition.
 * <p>
 * The SDK sends this only when the locally predicted target sequence is due
 * for a snapshot. The runtime verifies that prediction against the assigned
 * target sequence and adds the authoritative sequence and state index.
 */
@Value
public class ModelSnapshotMutation {
    public static final String COLLECTION =
            "$modelSnapshots";
    public static final String MODEL_ID_FACET =
            "modelId";
    public static final String SEQUENCE_NUMBER =
            "sequenceNumber";
    public static final String STATE_INDEX =
            "stateIndex";

    /**
     * Serialized model value produced by the configured snapshot serializer.
     */
    Data<byte[]> value;

    /**
     * Timestamp of the applied model revision in epoch milliseconds.
     */
    long timestamp;

    /**
     * Configured number of stored events between snapshots.
     * <p>
     * The runtime uses this to verify that the assigned target sequence is
     * actually a snapshot boundary.
     */
    int snapshotPeriod;

    /**
     * Configured maximum number of snapshots retained for this model.
     */
    int maxSnapshotCount;

    /**
     * Returns the serialized value size without protocol framing.
     */
    public long getBytes() {
        return value == null || value.getValue() == null
                ? 0L : value.getValue().length;
    }

    /**
     * Creates the immutable search document after the runtime assigned its
     * durable positions.
     */
    public SerializedDocument toDocument(
            String modelId,
            long sequenceNumber,
            long stateIndex) {
        return new SerializedDocument(
                snapshotKey(modelId,
                            sequenceNumber),
                timestamp, null, COLLECTION,
                value, null,
                Set.of(
                        new FacetEntry(
                                MODEL_ID_FACET,
                                modelId),
                        new FacetEntry(
                                SEQUENCE_NUMBER,
                                Long.toString(
                                        sequenceNumber)),
                        new FacetEntry(
                                STATE_INDEX,
                                Long.toString(
                                        stateIndex))),
                Set.of(
                        new SortableEntry(
                                SEQUENCE_NUMBER,
                                sequenceNumber),
                        new SortableEntry(
                                STATE_INDEX,
                                stateIndex)));
    }

    /**
     * Stable immutable snapshot document key.
     */
    public static String snapshotKey(
            String modelId, long sequenceNumber) {
        return "$modelSnapshot_" + modelId
               + "_" + sequenceNumber;
    }
}
