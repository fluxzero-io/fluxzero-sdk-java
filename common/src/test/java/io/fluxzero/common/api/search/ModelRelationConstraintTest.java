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

package io.fluxzero.common.api.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.common.api.search.ModelRelationConstraint.RelationDirection.ANCESTOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRelationConstraintTest {

    @Test
    void acceptsEitherAQueryOrExactModelIds() {
        ModelRelationConstraint query = ModelRelationConstraint.builder()
                .direction(ANCESTOR)
                .query(SearchQuery.builder().collection("parents").build())
                .build();
        ModelRelationConstraint ids = ModelRelationConstraint.builder()
                .direction(ANCESTOR)
                .relatedModelId("parent-1")
                .relatedModelId("parent-1")
                .relatedModelId("parent-2")
                .build();

        assertEquals(List.of(), query.getRelatedModelIds());
        assertEquals(List.of("parent-1", "parent-2"), ids.getRelatedModelIds());
    }

    @Test
    void rejectsMissingOrAmbiguousRelationSources() {
        assertThrows(IllegalArgumentException.class,
                     () -> ModelRelationConstraint.builder()
                             .direction(ANCESTOR)
                             .build());
        assertThrows(IllegalArgumentException.class,
                     () -> ModelRelationConstraint.builder()
                             .direction(ANCESTOR)
                             .query(SearchQuery.builder().collection("parents").build())
                             .relatedModelId("parent-1")
                             .build());
        assertThrows(IllegalArgumentException.class,
                     () -> ModelRelationConstraint.builder()
                             .direction(ANCESTOR)
                             .relatedModelId(" ")
                             .build());
    }

    @Test
    void boundsExactIdsWithTheExistingRelatedModelLimit() {
        assertThrows(IllegalArgumentException.class,
                     () -> ModelRelationConstraint.builder()
                             .direction(ANCESTOR)
                             .relatedModelIds(List.of("parent-1", "parent-2"))
                             .maxRelatedModels(1)
                             .build());
    }
}
