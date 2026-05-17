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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Base implementation for built-in {@link RequestResult} types.
 */
public abstract class AbstractRequestResult implements RequestResult {
    private long requestReceivedTimestamp;

    /**
     * The timestamp (in epoch milliseconds) when the Fluxzero Runtime received the original request.
     */
    @Override
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public long getRequestReceivedTimestamp() {
        return requestReceivedTimestamp;
    }

    /**
     * Updates the timestamp at which the Fluxzero Runtime received the original request.
     */
    @Override
    public void setRequestReceivedTimestamp(long requestReceivedTimestamp) {
        this.requestReceivedTimestamp = requestReceivedTimestamp;
    }
}
