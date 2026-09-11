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

import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.search.SerializedDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Binds serialized Model state to its durable document fence without certifying ordinary search writes. */
public final class ModelDocumentProof {
    private ModelDocumentProof() {}

    /**
     * Fingerprints the full head and state envelope. This encoding is persisted compatibility data: strings are
     * length-prefixed UTF-8, integers are big-endian, and flags/discriminators are single bytes. Capture atomically on
     * trusted Model materialization/adoption, using the envelope as returned by the store (including its normalizations),
     * never by retroactively certifying an existing unproven fence. A null document has a distinct deletion proof.
     */
    public static String of(SerializedDocument document, ModelHeadState head) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Bind the version too: an older writer may advance the fence without updating this proof.
            update(digest, head.getModelId());
            update(digest, head.getModelType());
            update(digest, (int) (head.getStateIndex() >>> 32));
            update(digest, (int) head.getStateIndex());
            update(digest, (int) (head.getSequenceNumber() >>> 32));
            update(digest, (int) head.getSequenceNumber());
            digest.update((byte) (head.isDeleted() ? 1 : 0));
            digest.update((byte) (head.isHistoryComplete() ? 1 : 0));
            digest.update((byte) (document == null ? 0 : 1));
            if (document != null) {
                update(digest, document.getId());
                update(digest, document.getCollection());
                update(digest, document.getTimestamp());
                update(digest, document.getEnd());
                update(digest, document.getSummary());
                var facets = document.getFacets();
                update(digest, facets == null ? -1 : facets.size());
                if (facets != null && !facets.isEmpty()) {
                    for (var facet : new TreeSet<>(facets)) {
                        update(digest, facet.getName());
                        update(digest, facet.getValue());
                    }
                }
                var indexes = document.getIndexes();
                update(digest, indexes == null ? -1 : indexes.size());
                if (indexes != null && !indexes.isEmpty()) {
                    for (var index : new TreeSet<>(indexes)) {
                        update(digest, index.getName());
                        update(digest, index.getValue());
                    }
                }
                var data = document.getDocument();
                update(digest, data.getType());
                update(digest, data.getFormat());
                update(digest, data.getRevision());
                var bytes = data.byteArrayView();
                if (bytes == null) {
                    digest.update(data.getValue());
                } else {
                    digest.update(bytes.array(), bytes.offset(), bytes.length());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 Model document verification is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            update(digest, -1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            update(digest, bytes.length);
            digest.update(bytes);
        }
    }

    private static void update(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void update(MessageDigest digest, Long value) {
        digest.update((byte) (value == null ? 0 : 1));
        if (value != null) {
            update(digest, (int) (value >>> 32));
            update(digest, value.intValue());
        }
    }
}
