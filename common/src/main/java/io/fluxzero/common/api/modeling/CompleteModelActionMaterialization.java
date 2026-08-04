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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * Confirms that SDK-owned direct documents and snapshots for one committed model action are durably materialized.
 * <p>
 * Runtimes with a co-located search store complete materialization themselves. This idempotent acknowledgement closes
 * the same readiness fence when those stores are owned by the SDK instead.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class CompleteModelActionMaterialization extends Command {

    /**
     * Durable model-action idempotency key.
     */
    String actionId;

    /**
     * Last state index assigned to the acknowledged action.
     */
    long lastStateIndex;

    /**
     * Delivery guarantee for the acknowledgement.
     */
    Guarantee guarantee = Guarantee.STORED;

    @Override
    public String routingKey() {
        return actionId;
    }
}
