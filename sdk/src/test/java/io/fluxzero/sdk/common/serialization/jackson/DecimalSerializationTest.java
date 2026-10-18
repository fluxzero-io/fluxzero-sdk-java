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

package io.fluxzero.sdk.common.serialization.jackson;

import io.fluxzero.common.Decimal;
import io.fluxzero.common.api.search.SortableEntry;
import io.fluxzero.common.search.DefaultDocumentSerializer;
import io.fluxzero.common.search.Document;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.common.search.Sortable;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecimalSerializationTest {
    record Amounts(@Sortable Decimal amount, String text, BigDecimal legacy,
                   List<Decimal> nested, @SearchExclude Decimal hidden) { }

    @Test
    void documentsRemainNumericalAndRoundTripThroughThePublicSerializer() {
        var serializer = new JacksonSerializer();
        var value = new Amounts(new Decimal("120.0000004"), "120.0000004", new BigDecimal("900.00"),
                                List.of(new Decimal("9007199254740993.010000000000000001")), Decimal.TEN);
        var document = serializer.toDocument(value, "1", "amounts", null, null);
        var entries = DefaultDocumentSerializer.INSTANCE.deserialize(document.getDocument());
        assertTrue(entries.containsKey(new Document.Entry(Document.EntryType.NUMERIC, "120.0000004")));
        assertTrue(entries.containsKey(new Document.Entry(Document.EntryType.TEXT, "120.0000004")));
        assertTrue(entries.containsKey(new Document.Entry(Document.EntryType.NUMERIC, "9007199254740993.010000000000000001")));
        assertEquals(value, serializer.fromDocument(document));
        assertEquals(SortableEntry.formatSortable(value.amount.toBigDecimal()),
                     SortableEntry.formatSortable(value.amount));
        assertEquals("{\"amount\":\"120.0000004\",\"text\":\"120.0000004\",\"legacy\":900.00,"
                     + "\"nested\":[\"9007199254740993.010000000000000001\"],\"hidden\":\"10\"}", JsonUtils.asJson(value));
    }

    @Test
    void sortEncodingDoesNotPassThroughDouble() {
        var decimal = new Decimal("1.00000049999999999999999999");
        assertEquals(SortableEntry.formatSortable(decimal.toBigDecimal()), SortableEntry.formatSortable(decimal));
        assertNotEquals(SortableEntry.formatSortable(decimal.doubleValue()), SortableEntry.formatSortable(decimal));
    }
}
