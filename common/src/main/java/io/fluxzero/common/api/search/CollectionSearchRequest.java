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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import java.beans.ConstructorProperties;

/** Fail-closed wire envelope for reads requiring annotation-independent value semantics. */
@Getter
@EqualsAndHashCode(callSuper = true)
public final class CollectionSearchRequest extends Request {
    private final Request search;
    @ConstructorProperties("search")
    public CollectionSearchRequest(Request search) {
        super(search.getRequestId());
        if (!(search instanceof SearchDocuments || search instanceof SearchModelDocuments
                || search instanceof SearchModelGraphDocuments || search instanceof GetDocumentStats
                || search instanceof GetFacetStats || search instanceof GetSearchHistogram))
            throw new IllegalArgumentException("Unsupported collection search request");
        this.search = search;
    }
}
