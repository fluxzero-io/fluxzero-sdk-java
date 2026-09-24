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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import lombok.Getter;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Model mutation cannot commit because its state conflicted with another mutation.
 * A rejected storage commit exposes {@code getResult()}; loss of a pinned document during preparation instead
 * exposes {@code getReadConflict()}. The latter is terminal: preparation cannot replay missing document history
 * and must not silently rerun the mutation against a newer state.
 */
@Getter
public class ModelCommitConflictException extends RuntimeException {
    /** Storage rejection, or {@code null} when preparation failed before submitting a commit. */
    private final CommitModelsResult result;
    /** Pinned-read failure, or {@code null} for a rejected storage commit. */
    private final ReadConflict readConflict;

    /**
     * Creates an exception containing the runtime's current model and relationship positions.
     */
    public ModelCommitConflictException(CommitModelsResult result) {
        super(message(result));
        this.result = result;
        this.readConflict = null;
    }

    /** Creates a terminal preparation conflict without inventing current storage positions or a storage response. */
    public ModelCommitConflictException(ReadConflict readConflict, Throwable cause) {
        super("Model commit %s lost the pinned document for %s at state %s".formatted(
                readConflict.commitId(), readConflict.modelId(), readConflict.readStateIndex()), cause);
        this.result = null;
        this.readConflict = readConflict;
    }

    /**
     * Identifies a document that could no longer be reconstructed at the mutation's pinned read boundary.
     * These are read-side positions, not claims about the current storage head or relationship revision.
     *
     * @param commitId original mutation message ID
     * @param modelId canonical ID of the unavailable document
     * @param readStateIndex pinned mutation boundary
     */
    public record ReadConflict(String commitId, String modelId, long readStateIndex) {
    }

    private static String message(CommitModelsResult result) {
        Objects.requireNonNull(result, "result");
        if (result.isAccepted()) {
            throw new IllegalArgumentException("A conflict exception requires a rejected result");
        }
        return "Model commit %s conflicted with %s".formatted(
                result.getCommitId(),
                result.getConflicts().stream().map(ModelCommitConflict::getModelId)
                        .collect(Collectors.joining(", ")));
    }
}
