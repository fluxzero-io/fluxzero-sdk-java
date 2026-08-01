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

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;

import java.util.concurrent.CompletableFuture;

/** Allows already-prepared, independent model commits to enter the websocket queue as one batch. */
public interface ModelCommitBatchingClient {

    ModelCommitBatch beginModelCommitBatch(int capacity);

    interface ModelCommitBatch {

        CompletableFuture<CommitModelsResult> add(int slot, CommitModels commit);

        void flush();

        void fail(Throwable failure);
    }
}
