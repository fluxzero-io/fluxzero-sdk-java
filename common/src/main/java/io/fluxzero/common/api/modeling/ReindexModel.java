/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.search.SerializedDocument;
import lombok.NonNull;
import lombok.Value;

/**
 * Refreshes one canonical source at an unchanged authoritative Model head. The Model store owns the head guard;
 * the search store atomically preserves provenance and records a fresh storage-time index and targeted invalidation.
 * A BooleanResult reports success (including a cutoff skip), or false when inspection must be retried.
 */
@Value
public class ReindexModel extends Request {
    /** Current serialized Model state; never a historical Graph or an ordinary public projection. */
    @NonNull SerializedDocument document;
    /** Complete authoritative head at reconstruction time. */
    @NonNull ModelHeadState expectedHead;
    /** Verified previous source proof, or null when creating a source from complete event history. */
    String expectedProof;
    /** Fixed consumer time cutoff, or null to refresh unconditionally. */
    Long cutoff;

    /** Validates the non-deleting source envelope. */
    public void validate() {
        if (expectedHead.isDeleted() || !document.getId().equals(expectedHead.getModelId())
            || expectedHead.getModelType() == null || expectedHead.getStateIndex() < 0
            || expectedProof == null && !expectedHead.isHistoryComplete()
            || expectedProof != null && expectedProof.isBlank()
            || cutoff != null && (cutoff <= 0 || cutoff > (System.currentTimeMillis() << 16))) {
            throw new IllegalArgumentException("Reindex requires a live Model, valid source evidence and a past time cutoff");
        }
    }

    /** Returns operational measurements without Model identifiers, proofs or payloads. */
    @Override
    public Metric toMetric() {
        return new Metric(document.bytes(), cutoff != null);
    }

    /** Payload-free reindex measurements. */
    @Value
    public static class Metric {
        long documentBytes;
        boolean bounded;
    }
}
