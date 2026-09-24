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

import io.fluxzero.common.search.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GetModelMigrationResultTest {

    @Test
    void rejectsAStagedDocumentWithoutDurableHeadEvidence() {
        assertThrows(IllegalArgumentException.class, () ->
                new GetModelMigrationResult(
                        0L, null, null,
                        new SerializedDocument(Document.builder()
                                .id("model").collection("$modelMigrations")
                                .type("example.Model").revision(0)
                                .entries(Map.of()).summary(() -> null)
                                .facets(Set.of()).sortables(Set.of())
                                .build()),
                        null));
    }
}
