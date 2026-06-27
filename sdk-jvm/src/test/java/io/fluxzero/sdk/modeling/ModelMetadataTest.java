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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelMetadataTest {

    @Test
    void readsModelingAnnotationAttributesAsMetadataConfigs() throws Exception {
        var member = ModelMetadata.member(MetadataAggregate.class.getDeclaredField("children")).orElseThrow();
        var alias = ModelMetadata.alias(MetadataChild.class.getDeclaredField("alias")).orElseThrow();
        var apply = ModelMetadata.apply(MetadataUpdate.class.getDeclaredMethod("apply")).orElseThrow();

        assertEquals("customId", member.idProperty());
        assertEquals("withChildren", member.wither());
        assertEquals("pre-", alias.prefix());
        assertEquals("-post", alias.postfix());
        assertTrue(apply.disableCompatibilityCheck());
    }

    private static class MetadataAggregate {
        @Member(idProperty = "customId", wither = "withChildren")
        private MetadataChild children;
    }

    private static class MetadataChild {
        @Alias(prefix = "pre-", postfix = "-post")
        private String alias;
    }

    private static class MetadataUpdate {
        @Apply(disableCompatibilityCheck = true)
        void apply() {
        }
    }
}
