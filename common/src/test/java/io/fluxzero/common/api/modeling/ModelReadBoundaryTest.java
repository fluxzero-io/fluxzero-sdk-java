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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelReadBoundaryTest {

    @Test
    void readsOneValidatedCommitBoundaryFromEventMetadata() {
        assertEquals(
                ModelReadBoundary.commit("commit-1", 2),
                ModelEventMetadata.readBoundary(Metadata.of(
                        ModelEventMetadata.COMMIT_ID, "commit-1",
                        ModelEventMetadata.SUBSTEP, 2)));
        assertNull(ModelEventMetadata.readBoundary(Metadata.empty()));
        assertThrows(IllegalArgumentException.class, () -> ModelEventMetadata.readBoundary(Metadata.of(
                ModelEventMetadata.COMMIT_ID, "commit-1",
                ModelEventMetadata.SUBSTEP, "invalid")));
    }

    @Test
    void canonicalizesStorageRequestsWithoutDiscardingOpaqueIdentity() {
        ModelReadBoundary currentRequest = ModelReadBoundary.current().forRequest();
        assertFalse(currentRequest.historical());
        assertFalse(currentRequest.includeMessageBatch());
        assertSame(currentRequest, currentRequest.forRequest());

        ModelReadBoundary commit = ModelReadBoundary.commit("commit-1", 2);
        ModelReadBoundary resolved = commit.resolved(42L);
        assertEquals(42L, resolved.stateIndex());
        assertEquals(commit, resolved.forRequest());
    }

    @Test
    void retainsPendingValuesOnlyWhenResolutionExplicitlyRequestsThem() {
        assertFalse(ModelReadBoundary.current().resolved(42L).includeMessageBatch());
        ModelReadBoundary includingBatch = ModelReadBoundary.current().resolved(42L, true);
        assertTrue(includingBatch.includeMessageBatch());
        assertSame(includingBatch, includingBatch.resolved(42L, true));
    }
}
