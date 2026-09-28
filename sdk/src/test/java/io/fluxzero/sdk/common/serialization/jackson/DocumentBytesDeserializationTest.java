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
package io.fluxzero.sdk.common.serialization.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.DefaultDocumentSerializer;
import io.fluxzero.common.search.Document;
import io.fluxzero.common.search.JacksonInverter;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentBytesDeserializationTest {
    private final JacksonSerializer serializer = new JacksonSerializer();

    @Test
    void retainsTheConverterHooksWhileReusingAlreadyDecodedEntries() {
        ObservingInverter inverter = new ObservingInverter();
        JacksonSerializer subject = new JacksonSerializer(new JsonMapper(), List.of(), inverter);
        SerializedDocument document = document(Map.of("value", "original"));
        document = prepared(document);

        assertEquals("original", subject.fromDocument(document, JsonNode.class).path("value").asText());
        assertEquals(1, inverter.formatCalls);
        assertEquals(1, inverter.conversionCalls);
        assertEquals(1, inverter.cachedCalls);
    }

    @Test
    void aReplacementPayloadCannotUseTheOldDocumentEntries() {
        SerializedDocument original = prepared(document(Map.of("value", "old")));
        original.getMetadata();
        SerializedDocument replacement = document(Map.of("value", "replacement"));
        SerializedDocument changed = original.withData(replacement::getDocument);

        assertEquals("replacement", serializer.fromDocument(changed, JsonNode.class).path("value").asText());
        assertEquals(Map.of("value", "replacement"), serializer.fromDocument(changed));
    }

    @Test
    void typeAndRevisionChangesKeepTheCasterPipelineAndDoNotMutateCachedEntries() {
        var caster = new Rename();
        JacksonSerializer subject = new JacksonSerializer(List.of(caster));
        subject.registerTypeCaster("legacy.Renamed", Renamed.class.getName());
        SerializedDocument original = document(Map.of("old", "retained"));
        original.getMetadata();
        Data<byte[]> prepared = prepared(original).getDocument().withType("legacy.Renamed").withRevision(0);
        SerializedDocument changed = original.withData(() -> prepared);

        assertEquals(new Renamed("retained"), subject.fromDocument(changed, Renamed.class));
        assertEquals(new Renamed("retained"), subject.fromDocument(changed));
        assertEquals(2, caster.calls.get());
        assertEquals("retained", serializer.fromDocument(original, JsonNode.class).path("old").asText());
    }

    @Test
    void mappingThePayloadDropsTheReadOnlyEntriesView() {
        SerializedDocument original = document(Map.of("value", "old"));
        original.getMetadata();
        Data<byte[]> prepared = prepared(original).getDocument();
        assertTrue(prepared.byteArrayView() instanceof JacksonInverter.DocumentBytes);
        Data<byte[]> replaced = prepared.map(ignored -> document(Map.of("value", "new")).getDocument().getValue());

        assertFalse(replaced.byteArrayView() instanceof JacksonInverter.DocumentBytes);
        assertEquals(Map.of("value", "new"), serializer.deserialize(replaced));
    }

    @Test
    void jsonDataDoesNotReuseTheEmptyDocumentView() {
        Data<byte[]> data = serializer.serialize(Map.of("value", "json"));
        SerializedDocument document = new SerializedDocument("id", null, null, "docs", data, "", null, null);
        document.getMetadata();

        assertSame(data, document.getDocument());
        assertEquals("json", serializer.fromDocument(document, JsonNode.class).path("value").asText());
    }

    @Test
    void customFromDocumentOverridesStillOwnTheDocumentEnvelope() {
        JacksonSerializer custom = new JacksonSerializer() {
            @Override public <T> T fromDocument(SerializedDocument document, Class<T> type) {
                assertEquals("docs", document.getCollection());
                assertEquals("source", document.getMetadata().get("label"));
                return super.fromDocument(document.withData(() -> serializer.serialize(Map.of("value", "custom"))), type);
            }
        };
        SerializedDocument document = prepared(document(Map.of("value", "original")));
        document.getMetadata();

        assertEquals("custom", custom.fromDocument(document, JsonNode.class).path("value").asText());
    }

    @Test
    void ordinaryDocumentSuppliersAndPublicEntriesRetainTheirIndependentBehavior() {
        SerializedDocument original = document(Map.of("value", "original"));
        original.deserializeDocument().getEntries().clear();
        assertEquals("original", serializer.fromDocument(original, JsonNode.class).path("value").asText());
        var bytes = new AtomicReference<>(original.getDocument().getValue());
        Data<byte[]> supplied = new Data<>(bytes::get, original.getDocument().getType(), 0, Data.DOCUMENT_FORMAT);
        SerializedDocument changing = new SerializedDocument("id", null, null, "docs", supplied, "", null, null);
        changing.getMetadata();
        bytes.set(document(Map.of("value", "changed")).getDocument().getValue());
        assertEquals("changed", serializer.fromDocument(changing, JsonNode.class).path("value").asText());
    }

    private SerializedDocument prepared(SerializedDocument document) {
        Data<byte[]> source = document.getDocument();
        byte[] bytes = source.getValue().clone();
        Map<Document.Entry, List<Document.Path>> entries = DefaultDocumentSerializer.INSTANCE.deserialize(
                new Data<>(bytes, source.getType(), source.getRevision(), source.getFormat()));
        entries.replaceAll((entry, paths) -> Collections.unmodifiableList(paths));
        JacksonInverter.DocumentBytes input = new JacksonInverter.DocumentBytes() {
            @Override public Map<Document.Entry, List<Document.Path>> entries() { return Collections.unmodifiableMap(entries); }
            @Override public byte[] get() { return bytes.clone(); }
            @Override public byte[] array() { return bytes; }
            @Override public int offset() { return 0; }
            @Override public int length() { return bytes.length; }
        };
        return document.withData(() -> new Data<>(input, source.getType(), source.getRevision(), source.getFormat()));
    }

    private SerializedDocument document(Object value) {
        SerializedDocument source = serializer.toDocument(value, "id", "docs", null, null, Metadata.of("label", "source"));
        return new SerializedDocument(source.getId(), source.getTimestamp(), source.getEnd(), source.getCollection(),
                                      source.getDocument(), source.getSummary(), source.getFacets(), source.getIndexes());
    }

    record Renamed(String value) {}
    static class Rename {
        final AtomicInteger calls = new AtomicInteger();
        @Upcast(type = "legacy.Renamed", revision = 0)
        ObjectNode rename(ObjectNode value) {
            calls.incrementAndGet();
            value.set("value", value.remove("old"));
            return value;
        }
    }

    static class ObservingInverter extends JacksonInverter {
        int formatCalls, conversionCalls, cachedCalls;
        @Override public Data<?> convertFormat(Data<byte[]> data) {
            formatCalls++;
            return super.convertFormat(data);
        }
        @Override protected Data<JsonNode> fromData(Data<byte[]> data) {
            conversionCalls++;
            if (data.byteArrayView() instanceof JacksonInverter.DocumentBytes) { cachedCalls++; }
            return super.fromData(data);
        }
    }
}
