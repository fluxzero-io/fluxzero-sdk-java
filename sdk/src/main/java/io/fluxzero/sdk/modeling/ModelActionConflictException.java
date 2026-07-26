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

import io.fluxzero.common.api.modeling.CommitModelActionResult;
import io.fluxzero.common.api.modeling.ModelActionConflict;
import lombok.Getter;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Default SDK error when a rolled-back model action conflict is not retried.
 */
@Getter
public class ModelActionConflictException extends RuntimeException {
    private final CommitModelActionResult result;

    /**
     * Creates an exception containing the runtime's current model and relationship positions.
     */
    public ModelActionConflictException(CommitModelActionResult result) {
        super(message(result));
        this.result = result;
    }

    private static String message(CommitModelActionResult result) {
        Objects.requireNonNull(result, "result");
        if (result.isAccepted()) {
            throw new IllegalArgumentException("A conflict exception requires a rejected result");
        }
        return "Model action %s conflicted with %s".formatted(
                result.getActionId(),
                result.getConflicts().stream().map(ModelActionConflict::getModelId)
                        .collect(Collectors.joining(", ")));
    }
}
