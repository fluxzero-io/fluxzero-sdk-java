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
 * Command to move matching documents to a collection. The collection does not need to exist.
 * <p>
 * This command allows for bulk moving of documents that match a specified {@link SearchQuery},
 * across one or more collections.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class MoveDocuments extends Command {

    /**
     * The query describing which documents should be deleted.
     */
    SearchQuery query;

    /**
     * The name of the collection to which the documents should be moved.
     */
    String targetCollection;

    /**
     * The delivery/storage guarantee to apply for this deletion operation.
     */
    Guarantee guarantee;
}
