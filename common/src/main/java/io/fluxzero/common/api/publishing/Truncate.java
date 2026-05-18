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

package io.fluxzero.common.api.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.Value;

import static java.util.Optional.ofNullable;

/**
 * Command to truncate a message log and its associated tracking positions.
 * <p>
 * The command is handled by the gateway endpoint that receives it, so the target log is defined by the websocket
 * endpoint rather than by fields in the command payload.
 */
@Value
public class Truncate extends Command {

    /**
     * The delivery guarantee to use for the truncate command.
     */
    Guarantee guarantee;

    @Override
    public Guarantee getGuarantee() {
        return ofNullable(guarantee).orElse(Guarantee.STORED);
    }

}
