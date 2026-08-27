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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.MessageType;

/**
 * Reserved metadata keys identifying events emitted by an independent-model commit.
 */
public final class ModelEventMetadata {

    /**
     * Durable idempotency identity of the model commit that emitted an event.
     */
    public static final String COMMIT_ID = "$modelCommitId";

    /**
     * Ordered substep within the model commit.
     */
    public static final String SUBSTEP = "$modelCommitSubstep";

    /** Returns the commit boundary carried by {@code metadata}, or {@code null} when it is not a Model event. */
    public static ModelReadBoundary readBoundary(Metadata metadata) {
        if (metadata == null) {
            return null;
        }
        String commitId = metadata.get(COMMIT_ID);
        String value = metadata.get(SUBSTEP);
        if (commitId == null || value == null) {
            return null;
        }
        try {
            return ModelReadBoundary.commit(commitId, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid model commit substep " + value, e);
        }
    }

    /**
     * Returns the Model boundary carried by a Model event, or falls back to an existing global event index.
     *
     * <p>The fallback lets events that predate Model metadata address state reconstructed from that same published
     * event. Without migration coordination, an event without a Model mapping retains ordinary current-state behavior.
     * A repository configured with {@code followPublishedEventMigration} may instead wait for the mapping or reject a
     * processed event that produced none. An event index is never interpreted as a Model state index.</p>
     */
    public static ModelReadBoundary readBoundary(
            Metadata metadata, MessageType messageType, Long messageIndex) {
        ModelReadBoundary boundary = readBoundary(metadata);
        return boundary != null
               || messageType != MessageType.EVENT
               || messageIndex == null
                ? boundary : ModelReadBoundary.eventOrCurrent(messageIndex);
    }

    private ModelEventMetadata() {
    }
}
