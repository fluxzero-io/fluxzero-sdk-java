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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentPathReconstructionTest {
    private final JacksonInverter inverter = new JacksonInverter();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'' | {\"\":\"value\"}",
            "/leading | {\"\":{\"leading\":\"value\"}}",
            "trailing/ | {\"trailing\":{\"\":\"value\"}}",
            "/ | {\"\":{\"\":\"value\"}}",
            "array/1234/name | {\"array\":[{\"name\":\"value\"}]}",
            "array/0000000001 | {\"array\":[\"value\"]}",
            "array/١٢٣ | {\"array\":[\"value\"]}",
            "array/-1 | {\"array\":{\"-1\":\"value\"}}",
            "array/2147483647 | {\"array\":[\"value\"]}",
            "array/2147483648 | {\"array\":{\"2147483648\":\"value\"}}",
            "object/\"0\" | {\"object\":{\"0\":\"value\"}}",
            "key\\/with\\/slashes | {\"key/with/slashes\":\"value\"}",
            "key\\name/ | {\"key\\\\name\":\"value\"}"
    })
    void keepsPathSegmentAndArrayConventions(String path, String expected) throws Exception {
        var data = document(path);
        assertEquals(io.fluxzero.common.serialization.JsonUtils.writer.readTree(expected),
                     inverter.convert(data).getValue());
    }

    @Test
    void repeatedNamesRemainIndependentAcrossLargeObjectsAndConversions() {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            body.put("field-" + i, List.of(Map.of("nested", "value-" + i), Map.of("nested", "value-" + (i + 1))));
        }
        var stored = inverter.toDocument(body, "type", 2, "id", "docs", null, null,
                                         io.fluxzero.common.api.Metadata.empty()).getDocument();
        var expected = io.fluxzero.common.serialization.JsonUtils.writer.valueToTree(body);
        assertEquals(expected, inverter.convert(stored).getValue());
        assertEquals(expected, inverter.convert(stored).getValue());
    }

    @Test
    void stillRejectsCollidingUnescapedObjectKeys() {
        var data = document("same", "\"same\"");
        assertTrue(assertThrows(IllegalStateException.class, () -> inverter.convert(data))
                           .getMessage().startsWith("Duplicate key same"));
    }

    @Test
    void collidingFieldNameHashesDoNotConflatePaths() {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            StringBuilder name = new StringBuilder();
            for (int bit = 0; bit < 6; bit++) {
                name.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
            }
            body.put(name.toString(), "value-" + i);
        }
        var data = inverter.toDocument(Map.of("nested", body), "type", 2, "id", "docs", null, null,
                                       io.fluxzero.common.api.Metadata.empty()).getDocument();
        assertEquals(JsonUtils.writer.valueToTree(Map.of("nested", body)), inverter.convert(data).getValue());
    }

    @Test
    void preservesScalarRootDocuments() {
        assertEquals(JsonUtils.writer.getNodeFactory().textNode("value"), inverter.convert(document()).getValue());
    }

    private static Data<byte[]> document(String... paths) {
        return DefaultDocumentSerializer.INSTANCE.serialize(Document.builder().id("id").collection("docs")
                .type("type").revision(2).entries(Map.of(new Document.Entry(Document.EntryType.TEXT, "value"),
                        Arrays.stream(paths).map(Document.Path::new).toList())).build());
    }
}
