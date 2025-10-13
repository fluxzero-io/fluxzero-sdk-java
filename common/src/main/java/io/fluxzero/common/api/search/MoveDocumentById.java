/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.common.api.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * Command to move a single document to another collection.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class MoveDocumentById extends Command {

    /**
     * The collection that contains the document.
     */
    String collection;

    /**
     * The unique ID of the document to move.
     */
    String id;

    /**
     * The name of the collection to which the document should be moved.
     */
    String targetCollection;

    /**
     * The guarantee that determines whether the move must be sent, acknowledged, or stored.
     */
    Guarantee guarantee;

    /**
     * Uses the document ID as the routing key, ensuring consistency during the move.
     */
    @Override
    public String routingKey() {
        return id;
    }
}
