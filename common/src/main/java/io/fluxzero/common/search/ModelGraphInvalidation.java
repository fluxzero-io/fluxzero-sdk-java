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

package io.fluxzero.common.search;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.SerializedDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small durable root notification. It contains no Model content or descendants. */
public final class ModelGraphInvalidation {
    /** Marks a notification that must be hydrated from the current canonical node documents. */
    public static final String METADATA_KEY = "$fluxzeroModelGraphLive";

    private static final JacksonInverter INVERTER = new JacksonInverter();

    private ModelGraphInvalidation() { }

    /** Captures the root identity and invalidation boundary without copying any of its content. */
    public static SerializedDocument create(SerializedDocument root, ModelGraphProjectionConfiguration configuration,
                                            long stateIndex) {
        ModelGraphDocumentManifest manifest = new ModelGraphDocumentManifest(
                stateIndex, List.of(configuration.getRootModelType()), List.of(root.getDocument().getType()), List.of(),
                List.of(new ModelGraphDocumentManifest.Node(root.getId(), 0, 0, root.getDocument().getRevision(), -1, -1, 0)));
        Map<Document.Entry, List<Document.Path>> entries = new LinkedHashMap<>();
        INVERTER.addMetadataEntries(entries, Metadata.of(
                METADATA_KEY, true, ModelGraphDocumentManifest.METADATA_KEY, manifest.serialize()));
        return new SerializedDocument(Document.builder().id(root.getId()).collection(configuration.getCollection())
                .type(ModelGraphInvalidation.class.getName()).entries(entries).summary(() -> "")
                .facets(Set.of(new FacetEntry(ModelGraphDocumentManifest.FACET_NAME, "1"))).build());
    }
}
