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

package io.fluxzero.common.api.search.constraints;

import io.fluxzero.common.api.search.Constraint;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.ValueSelection;
import io.fluxzero.common.search.Document;
import lombok.Value;

import java.util.List;

/** Explicit query profile whose range constraints compare field values independently of index annotations. */
@Value
public class CollectionValuesConstraint implements Constraint {
    List<Constraint> collectionValues;

    @java.beans.ConstructorProperties("collectionValues")
    public CollectionValuesConstraint(List<Constraint> collectionValues) {
        this.collectionValues = collectionValues.stream().map(CollectionValuesConstraint::convert).toList();
    }

    /** Resolves this profile before transport, without changing the caller's query. */
    public static SearchQuery apply(SearchQuery query) {
        if (query.getConstraints().stream().anyMatch(CollectionValuesConstraint.class::isInstance)) return query;
        return query.toBuilder().clearConstraints().constraint(new CollectionValuesConstraint(query.getConstraints())).build();
    }

    private static Constraint convert(Constraint constraint) {
        return switch (constraint.decompose()) {
            case BetweenConstraint c -> c.getValueSelection() == null ? c.withValueSelection(ValueSelection.INTERVAL) : c;
            case AllConstraint c -> AllConstraint.all(c.getAll().stream().map(CollectionValuesConstraint::convert).toList());
            case AnyConstraint c -> AnyConstraint.any(c.getAny().stream().map(CollectionValuesConstraint::convert).toList());
            case NotConstraint c -> NotConstraint.not(convert(c.getNot()));
            default -> constraint.decompose();
        };
    }

    /** The resolved filters, retaining the marker even for an unconstrained query. */
    public Constraint filters() { return AllConstraint.all(collectionValues); }

    @Override public boolean matches(Document document) { return collectionValues.stream().allMatch(c -> c.matches(document)); }
    @Override public boolean hasPathConstraint() { return collectionValues.stream().anyMatch(Constraint::hasPathConstraint); }
}
