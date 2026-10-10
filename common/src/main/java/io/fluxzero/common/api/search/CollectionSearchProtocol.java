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

package io.fluxzero.common.api.search;

import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.search.constraints.*;

/** Identifies operations which must never be interpreted by a legacy receiver. */
public final class CollectionSearchProtocol {
    private CollectionSearchProtocol() { }
    public static boolean required(Request request) {
        return switch (request) {
            case SearchDocuments r -> required(r.getQuery()) || r.isCollectionValueSorting();
            case SearchModelDocuments r -> required(r.getSearch()) || r.getRelations().stream()
                    .anyMatch(c -> c.getQuery() != null && required(c.getQuery()));
            case SearchModelGraphDocuments r -> required(r.getSearch()) || r.getRelations().stream()
                    .anyMatch(c -> c.getQuery() != null && required(c.getQuery()));
            case GetDocumentStats r -> required(r.getQuery());
            case GetFacetStats r -> required(r.getQuery());
            case GetSearchHistogram r -> required(r.getQuery());
            case DeleteDocuments r -> required(r.getQuery());
            case MoveDocuments r -> required(r.getQuery());
            default -> false;
        };
    }
    public static boolean required(SearchQuery query) { return query.getConstraints().stream().anyMatch(CollectionSearchProtocol::required); }
    public static boolean required(Constraint constraint) {
        return switch (constraint) {
            case CollectionValuesConstraint c -> true;
            case BetweenConstraint c -> c.getValueSelection() != null;
            case AllConstraint c -> c.getAll().stream().anyMatch(CollectionSearchProtocol::required);
            case AnyConstraint c -> c.getAny().stream().anyMatch(CollectionSearchProtocol::required);
            case NotConstraint c -> required(c.getNot());
            default -> false;
        };
    }
}
