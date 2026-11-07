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

import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.constraints.BetweenConstraint;
import io.fluxzero.common.api.search.constraints.FacetConstraint;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Storage-neutral orchestration for a reconstruction read. Snapshot selection may use a separate storage snapshot:
 * it is an optional acceleration seed, bounded by the selected Model revision. Event reads remain authoritative.
 */
public final class ModelReconstructionLoader {
    private ModelReconstructionLoader() {}

    /**
     * Selects an optional canonical snapshot and a bounded first event page from the same namespace.
     * Each event callback must honor the supplied selector; the snapshot callback may miss concurrent writes.
     * A selected revision that becomes unavailable during the read fails instead of using a snapshot alone.
     */
    public static GetModelReconstructionResult load(GetModelReconstruction request,
            Function<GetModelEvents, GetModelEventsResult> events,
            Function<SearchDocuments, SerializedDocument> snapshots) {
        var initialRequest = new GetModelEvents(
                List.of(new ModelEventStreamRequest(request.getModelId(), -1L, request.getMaxSize())),
                request.getBoundary(), request.getMaxBytes());
        ModelCommitValidator.validate(initialRequest);
        // A literal snapshot is only a candidate: the following event read proves its canonical owner and revision.
        // This preserves the ordinary primary snapshot -> suffix order without a redundant head-only observation.
        boolean literalChecked = request.getMaxSize() > 0 && !request.getBoundary().historical();
        SerializedDocument literalSnapshot = literalChecked
                ? snapshots.apply(snapshotQuery(request.getModelId(), Long.MAX_VALUE)) : null;
        long literalSequence = literalSnapshot == null ? -1L : sequence(literalSnapshot);
        var headRequest = new GetModelEvents(List.of(new ModelEventStreamRequest(
                request.getModelId(), literalSequence, literalSnapshot == null ? 0 : request.getMaxSize())),
                request.getBoundary(), request.getMaxBytes());
        if (request.isPrimaryOnly()) {
            headRequest = new GetModelEventsByIdentity(headRequest.getRequests(), headRequest.getBoundary(),
                    headRequest.getMaxBytes(), GetModelEventsByIdentity.Selection.PRIMARY);
        }
        var heads = ModelEventPageDecoder.expand(headRequest, events.apply(headRequest));
        var head = heads.getStreams().getFirst().getHead();
        String id = head == null ? request.getModelId() : head.getModelId();
        SerializedDocument snapshot = null;
        long sequence = -1L;
        if (head != null && head.isHistoryComplete() && request.getMaxSize() > 0) {
            if (literalSnapshot != null && id.equals(request.getModelId()) && fits(literalSnapshot, head)) {
                return new GetModelReconstructionResult(request.getRequestId(), literalSnapshot, heads);
            }
            // A concurrent newer snapshot may be missed, so a proven literal miss needs no second search.
            snapshot = literalChecked && literalSnapshot == null && id.equals(request.getModelId()) ? null
                    : snapshots.apply(snapshotQuery(id, Math.min(heads.getStateIndex(), head.getStateIndex())));
            if (snapshot != null) {
                if (!fits(snapshot, head)) {
                    throw new IllegalStateException("Snapshot does not fit the selected Model head for " + id);
                }
                sequence = sequence(snapshot);
            }
        }
        var suffixRequest = new GetModelEventsByIdentity(
                List.of(new ModelEventStreamRequest(id, sequence, request.getMaxSize())),
                ModelReadBoundary.state(heads.getStateIndex(), false), request.getMaxBytes(),
                GetModelEventsByIdentity.Selection.PRIMARY);
        var suffix = ModelEventPageDecoder.expand(suffixRequest, events.apply(suffixRequest));
        if (suffix.getStateIndex() != heads.getStateIndex()
                || !Objects.equals(head, suffix.getStreams().getFirst().getHead())) {
            throw new IllegalStateException("Selected Model revision is no longer available for " + id);
        }
        // Preserve exactness of commit/event-boundary resolution, rather than the derived numeric selector.
        var page = new GetModelEventsResult(suffix.getRequestId(), suffix.getStateIndex(), heads.isExactBoundary(),
                suffix.getPayloads(), suffix.getStreams());
        return new GetModelReconstructionResult(request.getRequestId(), snapshot, page);
    }

    /** Checks the canonical seed metadata against an authoritative, reconstructible Model head. */
    public static boolean fits(SerializedDocument snapshot, ModelHeadState head) {
        long sequence = sequence(snapshot), state = stateIndex(snapshot);
        return head.isHistoryComplete() && (!head.isDeleted() || sequence < head.getSequenceNumber())
                && head.getModelId().equals(facet(snapshot, ModelSnapshotMutation.MODEL_ID_FACET))
                && sequence >= 0 && sequence <= head.getSequenceNumber()
                && state >= 0 && state <= head.getStateIndex();
    }

    /** Selects the newest eligible retained snapshot; an older snapshot or absence is also a valid seed. */
    public static SearchDocuments snapshotQuery(String id, long boundary) {
        var query = SearchQuery.builder().collection(ModelSnapshotMutation.COLLECTION)
                .constraint(FacetConstraint.matchFacet(ModelSnapshotMutation.MODEL_ID_FACET, id));
        if (boundary < Long.MAX_VALUE) {
            query.constraint(BetweenConstraint.below(ModelStateIndexCodec.encode(boundary + 1L),
                    ModelSnapshotMutation.STATE_INDEX));
        }
        return SearchDocuments.builder().query(query.build()).sorting(List.of("-sequenceNumber")).maxSize(1).build();
    }

    /** Reads the snapshot's per-Model sequence facet. */
    public static long sequence(SerializedDocument snapshot) {
        return Long.parseLong(facet(snapshot, ModelSnapshotMutation.SEQUENCE_NUMBER));
    }

    /** Reads the snapshot's namespace-wide state facet. */
    public static long stateIndex(SerializedDocument snapshot) {
        return Long.parseLong(facet(snapshot, ModelSnapshotMutation.STATE_INDEX));
    }

    private static String facet(SerializedDocument snapshot, String name) {
        for (var facet : snapshot.getFacets()) {
            if (facet.getName().equals(name)) { return facet.getValue(); }
        }
        throw new IllegalStateException("Missing snapshot facet " + name);
    }
}
