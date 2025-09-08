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

package io.fluxzero.common.api;

import lombok.Value;

/**
 * A generic response containing a boolean value, typically used to indicate success/failure.
 * <p>
 * Often used for requests such as checks or validations where the outcome is binary.
 * </p>
 *
 * @see RequestResult
 */
@Value
public class BooleanResult implements RequestResult {

    /**
     * ID correlating this result with its originating request.
     */
    long requestId;

    /**
     * Whether the operation was successful.
     */
    boolean success;

    /**
     * Time at which the result was generated.
     */
    long timestamp = System.currentTimeMillis();
}