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

/**
 * Controls whether command-result completion waits for affected materialized model-graph documents.
 */
public enum GraphProjectionCompletion {
    /**
     * Inherit from the next broader scope.
     */
    DEFAULT,

    /**
     * Let durable graph projection continue asynchronously after the authoritative model commit completes.
     */
    ASYNC,

    /**
     * Delay the handler result until all affected graph roots crossed the committed model state boundary.
     */
    AWAIT;

    /** Returns this explicit policy, or the supplied broader-scope policy when this value is {@link #DEFAULT}. */
    public GraphProjectionCompletion orElse(GraphProjectionCompletion broaderScope) {
        return this == DEFAULT ? broaderScope : this;
    }
}
