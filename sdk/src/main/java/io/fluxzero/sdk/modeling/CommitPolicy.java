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

package io.fluxzero.sdk.modeling;

/**
 * Common timing characteristics exposed by aggregate and independent-model commit policies.
 */
public interface CommitPolicy {

    /**
     * Returns whether commits start at batch completion rather than handler completion.
     */
    boolean commitAfterBatch();

    /**
     * Returns whether commit completion is awaited at batch completion rather than handler completion.
     */
    boolean awaitAfterBatch();

    /**
     * Returns whether multiple commits in the same completion phase may run concurrently.
     */
    boolean async();
}
