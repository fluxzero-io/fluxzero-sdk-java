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

import io.fluxzero.sdk.common.serialization.DeserializingMessage;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Map;

/** Test-only bridge that keeps the production Change carrier package-private. */
public final class ModelCommitTestBuilder {
    private ModelCommitTestBuilder() {
    }

    public static CommitAttempt attempt(
            long readStateIndex,
            String modelId,
            Class<?> modelType,
            long beforeSequenceNumber,
            Object before,
            Object after,
            Executable handler,
            DeserializingMessage message) {
        Change change = Change.applied(
                modelId, modelType, beforeSequenceNumber, null,
                before, after, handler, null, false);
        return CommitAttempt.fromChanges(
                readStateIndex, List.of(modelId), Map.of(modelId, modelType),
                message, List.of(change));
    }
}
