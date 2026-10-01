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

/** Materialization and completion policy for the optional composed Graph document. */
public enum GraphProjectionMode {
    /**
     * Default. Do not store a complete composed Graph document; keep the indexed node documents and assemble Graph
     * results live when read. This does not disable Graph search.
     */
    NONE,
    /** Also maintain the composed Graph asynchronously. */
    ASYNC,
    /** Also maintain the composed Graph and wait for affected projections at commit completion. */
    AWAIT;

    /** Completion policy for Graph update processing. A NONE Graph subscription stores only invalidation markers;
     * ordinary NONE queries do not register a notification stream. */
    public GraphProjectionCompletion completion() {
        return this == AWAIT ? GraphProjectionCompletion.AWAIT : GraphProjectionCompletion.DEFAULT;
    }
}
