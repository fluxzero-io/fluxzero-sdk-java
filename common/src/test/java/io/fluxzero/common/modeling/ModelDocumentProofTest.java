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
package io.fluxzero.common.modeling;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.SortableEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ModelDocumentProofTest {
    private final ModelHeadState head = new ModelHeadState("m-α", "Account", 3, 1099511627781L, true, false);
    private final SerializedDocument document = document("m-α", "accounts", "example.Account", 2,
                                                         Data.JSON_FORMAT, "{\"value\":1}");

    @Test
    void retainsPersistedEncoding() {
        assertEquals("e23ba13a42c91dafde07ff6c1c7e9dc5902aafaad62519f0831f35306dc4a29f",
                     ModelDocumentProof.of(document, head));
        assertEquals("a8869172bdef4e0cdd5426d97083c2a0e64cb2849d37f6e359359e77f9085e22",
                     ModelDocumentProof.of(null, head));
    }

    @Test
    void bindsEveryHeadField() {
        String proof = ModelDocumentProof.of(document, head);
        for (var changed : List.of(
                new ModelHeadState("other", "Account", 3, 1099511627781L, true, false),
                new ModelHeadState("m-α", "Other", 3, 1099511627781L, true, false),
                new ModelHeadState("m-α", "Account", 4, 1099511627781L, true, false),
                new ModelHeadState("m-α", "Account", 3, 5, true, false),
                new ModelHeadState("m-α", "Account", 3, 1099511627782L, true, false),
                new ModelHeadState("m-α", "Account", 3, 1099511627781L, false, false),
                new ModelHeadState("m-α", "Account", 3, 1099511627781L, true, true))) {
            assertNotEquals(proof, ModelDocumentProof.of(document, changed));
        }
    }

    @Test
    void bindsIdentityAndSerializedEnvelope() {
        String proof = ModelDocumentProof.of(document, head);
        for (var changed : List.of(
                document("other", "accounts", "example.Account", 2, Data.JSON_FORMAT, "{\"value\":1}"),
                document("m-α", "other", "example.Account", 2, Data.JSON_FORMAT, "{\"value\":1}"),
                document("m-α", "accounts", "example.Other", 2, Data.JSON_FORMAT, "{\"value\":1}"),
                document("m-α", "accounts", "example.Account", 3, Data.JSON_FORMAT, "{\"value\":1}"),
                document("m-α", "accounts", "example.Account", 2, "other", "{\"value\":1}"),
                document("m-α", "accounts", "example.Account", 2, Data.JSON_FORMAT, "{\"value\":2}"))) {
            assertNotEquals(proof, ModelDocumentProof.of(changed, head));
        }
        assertNotEquals(proof, ModelDocumentProof.of(null, head));
    }

    @Test
    void hashesOnlyTheByteViewWithoutMaterializingACopy() {
        byte[] bytes = "prefix{\"value\":1}suffix".getBytes(StandardCharsets.UTF_8);
        var slice = new Data.ByteArrayView() {
            @Override public byte[] array() { return bytes; }
            @Override public int offset() { return 6; }
            @Override public int length() { return 11; }
            @Override public byte[] get() { throw new AssertionError("Do not copy the byte view"); }
        };
        var viewed = document.withData(() -> new Data<byte[]>(slice, "example.Account", 2, Data.JSON_FORMAT));
        assertEquals(ModelDocumentProof.of(document, head), ModelDocumentProof.of(viewed, head));
    }

    @Test
    void bindsTheCompleteDocumentSerializerEnvelope() {
        String proof = ModelDocumentProof.of(document, head);
        for (var changed : List.of(
                document.toBuilder().timestamp(1L << 40).build(),
                document.toBuilder().end(1L << 40).build(),
                document.toBuilder().summary("changed").build(),
                document.toBuilder().facets(Set.of(new FacetEntry("role", "admin"))).build(),
                document.toBuilder().indexes(Set.of(new SortableEntry("value", "new"))).build())) {
            assertNotEquals(proof, ModelDocumentProof.of(changed, head));
        }
        var first = new FacetEntry("first", "one");
        var second = new FacetEntry("second", "two");
        var indexA = new SortableEntry("a", "one");
        var indexB = new SortableEntry("b", "two");
        assertEquals(ModelDocumentProof.of(document.toBuilder()
                             .facets(new LinkedHashSet<>(List.of(first, second)))
                             .indexes(new LinkedHashSet<>(List.of(indexA, indexB))).build(), head),
                     ModelDocumentProof.of(document.toBuilder()
                             .facets(new LinkedHashSet<>(List.of(second, first)))
                             .indexes(new LinkedHashSet<>(List.of(indexB, indexA))).build(), head));
    }

    private SerializedDocument document(String id, String collection, String type, int revision, String format,
                                        String body) {
        return new SerializedDocument(id, null, null, collection,
                new Data<>(body.getBytes(StandardCharsets.UTF_8), type, revision, format), null, null, null);
    }
}
