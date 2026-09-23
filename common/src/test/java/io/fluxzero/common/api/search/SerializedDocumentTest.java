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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.search.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SerializedDocumentTest {
    @Test
    void customDecoderIsLazyMemoizedAndRetainsTheOriginalEnvelopeAndFields() {
        var data = new Data<>(new byte[]{1, 2}, "type", 3, Data.DOCUMENT_FORMAT);
        var source = new SerializedDocument("id", 20L, 10L, "collection", data, "summary",
                Set.of(new FacetEntry("facet", "value")), Set.of(new SortableEntry("sort", "value")));
        var entries = Map.of(new Document.Entry(Document.EntryType.TEXT, "value"),
                             List.of(new Document.Path("field")));
        var calls = new AtomicInteger();
        var decoded = source.withDocumentDecoder(input -> {
            assertSame(data, input);
            calls.incrementAndGet();
            return entries;
        });
        assertSame(data, decoded.getDocument());
        assertEquals(0, calls.get());
        Document document = decoded.deserializeDocument();
        assertSame(document, decoded.deserializeDocument());
        assertEquals(1, calls.get());
        assertEquals(entries, document.getEntries());
        assertEquals("id", document.getId());
        assertEquals("type", document.getType());
        assertEquals(3, document.getRevision());
        assertEquals("collection", document.getCollection());
        assertEquals(Instant.ofEpochMilli(20), document.getTimestamp());
        assertEquals(Instant.ofEpochMilli(20), document.getEnd());
        assertEquals(Instant.ofEpochMilli(10), document.toBuilder().timestamp(Instant.EPOCH).build().getEnd());
        assertEquals("summary", document.getSummary());
        assertEquals(source.getFacets(), document.getFacets());
        assertEquals(source.getIndexes(), document.getSortables());
        assertSame(data, decoded.getDocument());
        assertEquals("other", decoded.withCollection("other").deserializeDocument().getCollection());
        assertEquals("collection", decoded.deserializeDocument().getCollection());
    }
}
