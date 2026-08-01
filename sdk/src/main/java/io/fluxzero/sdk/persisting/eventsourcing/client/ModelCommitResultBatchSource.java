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

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.modeling.CommitModelsResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Internal capability for processing model-commit results in transport batches before individual request futures are
 * released.
 */
public interface ModelCommitResultBatchSource {

    /**
     * Registers a processor whose completion fences the individual results in the same transport batch.
     */
    Registration registerModelCommitResultProcessor(
            Function<List<CommitModelsResult>, CompletableFuture<Void>> processor);
}
