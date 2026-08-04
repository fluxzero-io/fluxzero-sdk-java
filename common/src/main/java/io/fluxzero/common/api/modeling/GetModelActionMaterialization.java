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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.Request;
import lombok.Value;

/**
 * Loads the exact direct-document and snapshot materialization retained for a committed model action.
 * <p>
 * This request is the process-restart repair path for deployments where the runtime owns model streams while the SDK
 * owns the document store. It never asks application code to re-evaluate an {@code @Apply}; it returns only the
 * serialized mutations committed with the original action.
 */
@Value
public class GetModelActionMaterialization extends Request {

    /**
     * Durable model-action idempotency key.
     */
    String actionId;

    @Override
    public Object toMetric() {
        return new Metric(actionId);
    }

    public record Metric(String actionId) {
    }
}
