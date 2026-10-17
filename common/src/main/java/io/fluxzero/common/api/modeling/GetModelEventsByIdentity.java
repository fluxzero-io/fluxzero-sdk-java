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

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Objects;

/**
 * Selects Model streams explicitly by primary identity or alias, without fallback between those namespaces.
 * The distinct wire type requires peer support. Ordinary {@link GetModelEvents} retains primary-first lookup.
 * Alias selection is current-only; retain the returned canonical identity for subsequent pages and historical reads.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public final class GetModelEventsByIdentity extends GetModelEvents {
    /** The identity namespace to consult. */
    private final Selection selection;

    @ConstructorProperties({"requests", "boundary", "maxBytes", "selection"})
    public GetModelEventsByIdentity(List<ModelEventStreamRequest> requests, ModelReadBoundary boundary,
                                    long maxBytes, Selection selection) {
        super(requests, boundary, maxBytes);
        this.selection = Objects.requireNonNull(selection, "selection");
        if (selection == Selection.ALIAS && !ModelReadBoundary.current().forRequest().equals(getBoundary())) {
            throw new IllegalArgumentException("Explicit alias selection requires a current boundary");
        }
    }

    /** Explicit identity namespaces; neither mode falls back to the other. */
    public enum Selection { PRIMARY, ALIAS }
}
