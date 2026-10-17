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

package io.fluxzero.common.search;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration.ModelRevision;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.SerializedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGraphInvalidationTest {
    @Test
    void recognizesMarkerPresenceBeforeAndAfterBinaryRoundTrip() {
        String key = ModelGraphInvalidation.METADATA_KEY;
        for (Metadata metadata : List.of(Metadata.empty(), Metadata.of(key, true), Metadata.of(key, false),
                Metadata.of(key, "null"), Metadata.of(key, Map.of("nested", List.of(1, 2))),
                Metadata.of(key + "suffix", true), Metadata.of(key + "/nested", true))) {
            var source = new JacksonInverter().toDocument(Map.of(key, true), "type", 0, "id", "docs",
                    null, null, metadata);
            var restored = new SerializedDocument(source.getId(), source.getTimestamp(), source.getEnd(),
                    source.getCollection(), source.getDocument(), source.getSummary(), source.getFacets(),
                    source.getIndexes());
            for (SerializedDocument document : List.of(source, restored)) {
                assertEquals(document.getMetadata().containsKey(key), ModelGraphInvalidation.isInvalidation(document));
                assertFalse(ModelGraphDocumentManifest.isGraphDocument(document));
            }
        }
    }

    @Test
    void recognizesNotificationsCreatedByTheProjection() {
        var root = new JacksonInverter().toDocument(Map.of("value", "text"), "type", 0, "id", "docs",
                null, null, Metadata.empty());
        var configuration = new ModelGraphProjectionConfiguration("type", "docs", "graphs",
                ModelGraphComposition.builder().build(), List.of(new ModelRevision("type", 0)), List.of(), false);
        var notification = ModelGraphInvalidation.create(root, configuration, 123L);
        assertTrue(ModelGraphInvalidation.isInvalidation(notification));
        assertTrue(ModelGraphDocumentManifest.isGraphDocument(notification));
    }
}
