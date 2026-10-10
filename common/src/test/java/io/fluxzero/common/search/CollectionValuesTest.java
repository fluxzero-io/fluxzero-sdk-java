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

import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.search.*;
import io.fluxzero.common.api.search.constraints.*;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static io.fluxzero.common.api.search.constraints.BetweenConstraint.between;
import static org.junit.jupiter.api.Assertions.*;

class CollectionValuesTest {
    @Test
    void intervalOverlapPreservesOpenBoundsAndStoresOnlyExtrema() {
        for (boolean indexed : List.of(false, true)) {
            var sparse = document("gap", List.of(10, 100), indexed);
            assertTrue(between(40, 60, "prices").withValueSelection(ValueSelection.INTERVAL).matches(sparse));
            assertFalse(between(40, 40, "prices").withValueSelection(ValueSelection.INTERVAL).matches(sparse));
            assertFalse(between(60, 40, "prices").withValueSelection(ValueSelection.INTERVAL).matches(sparse));
            assertTrue(between(100, 101, "prices").withValueSelection(ValueSelection.INTERVAL).matches(sparse));
            assertFalse(between(0, 10, "prices").withValueSelection(ValueSelection.INTERVAL).matches(sparse));
            var dense = document("many", java.util.stream.IntStream.range(0, 1000).boxed().toList(), indexed);
            assertEquals(List.of(CollectionValues.key(0), CollectionValues.key(999)),
                    CollectionValues.indexedValues(dense, List.of("prices")).get("prices"));
        }
    }

    @Test
    void ordinaryDocumentViewsRemainLazy() {
        var entries = new AbstractMap<Document.Entry, List<Document.Path>>() {
            @Override public Set<Map.Entry<Document.Entry, List<Document.Path>>> entrySet() {
                throw new AssertionError("Metadata-only access must not scan entries");
            }
        };
        var document = Document.builder().id("one").collection("values").summary(() -> "").entries(entries).build();
        var serialized = new SerializedDocument(document);
        assertSame(document, serialized.deserializeDocument());
        assertTrue(serialized.getCollectionSortKeys().isEmpty());
    }

    @Test
    void rangesAndOrderingIgnoreOptionalSortables() {
        for (boolean indexed : List.of(false, true)) {
            Document sparse = document("sparse", List.of(10, 100), indexed);
            Document middle = document("middle", List.of(40, 60), indexed);
            Document empty = document("empty", List.of(), indexed);
            var interval = between(30, 70, "prices").withValueSelection(ValueSelection.INTERVAL);
            assertTrue(interval.matches(sparse));
            assertTrue(interval.matches(middle));
            assertFalse(interval.matches(empty));
            assertTrue(NotConstraint.not(interval).matches(empty));
            assertFalse(SearchValue.max("prices").below(50).matches(sparse));
            assertTrue(SearchValue.min("prices").below(50).matches(sparse));
            var documents = List.of(empty, sparse, middle);
            for (boolean descending : List.of(false, true)) {
                SearchValue selection = descending ? SearchValue.max("prices") : SearchValue.min("prices");
                var query = SearchDocuments.builder().query(SearchQuery.builder().collection("products").build())
                        .collectionValueSorting(true).sorting(List.of((descending ? "-" : "") + selection.sortPath() + ":nullsLast")).build();
                assertEquals(List.of("sparse", "middle", "empty"), documents.stream()
                        .sorted(Document.createComparator(query)).map(Document::getId).toList());
            }
        }
    }

    @Test
    void bothBoundsApplyToOneValueAndOnePath() {
        var document = Document.builder().id("x").entries(Map.of(
                new Document.Entry(Document.EntryType.NUMERIC, "10"), List.of(new Document.Path("a")),
                new Document.Entry(Document.EntryType.NUMERIC, "100"), List.of(new Document.Path("b")))).build();
        assertFalse(between(30, 70, "a").withPaths(List.of("a", "b"))
                .withValueSelection(ValueSelection.INTERVAL).matches(document));
    }

    @Test
    void comparisonsRetainLegacyNumericPrecisionAndRange() {
        for (BigDecimal value : List.of(new BigDecimal("-1E100"), new BigDecimal("1E100"),
                new BigDecimal("0.0000001"), new BigDecimal("0.0000002"), new BigDecimal("1.000001"))) {
            assertEquals(SortableEntry.formatSortable(value), CollectionValues.key(value));
        }
        assertEquals(CollectionValues.key(new BigDecimal("0.0000001")), CollectionValues.key(BigDecimal.ZERO));
        for (boolean indexed : List.of(false, true)) {
            var doc = document("precise", List.of(new BigDecimal("0.0000001")), indexed);
            assertTrue(between(0, new BigDecimal("0.000001"), "prices").withValueSelection(ValueSelection.INTERVAL).matches(doc));
            assertFalse(between(0, new BigDecimal("0.0000002"), "prices").withValueSelection(ValueSelection.INTERVAL).matches(doc));
        }
    }

    @Test
    void emptyAndNullCollectionsHaveNoValuesAndTextRetainsLegacyNormalization() {
        assertNull(CollectionValues.key(new Document.Entry(Document.EntryType.EMPTY_ARRAY, "[]")));
        assertNull(CollectionValues.key(new Document.Entry(Document.EntryType.NULL, "null")));
        assertEquals(CollectionValues.key("AbC"), CollectionValues.key("aBc"));
        assertEquals(CollectionValues.key("cafe"), CollectionValues.key("café"));
        for (String path : List.of("prices", "literal/1", "a:b:nullsFirst", "\\path", "😀")) {
            var value = SearchValue.min(path);
            assertEquals(value, SearchValue.fromSortPath(value.sortPath()));
        }
    }

    @Test
    void profileAndExplicitConstraintsRoundTripInsideFailClosedEnvelope() {
        var query = CollectionValuesConstraint.apply(SearchQuery.builder().collection("products")
                .constraint(between(30,70,"prices")).build());
        var search = SearchDocuments.builder().query(query).collectionValueSorting(true).sorting(List.of(SearchValue.min("prices").sortPath())).build();
        var envelope = new CollectionSearchRequest(search);
        var decoded = (CollectionSearchRequest) JsonUtils.fromJson(JsonUtils.asBytes(envelope), JsonType.class);
        assertEquals(envelope.getRequestId(), decoded.getRequestId());
        assertTrue(CollectionSearchProtocol.required(decoded.getSearch()));
        var restored = (SearchDocuments) decoded.getSearch();
        assertTrue(restored.getQuery().matches(new SerializedDocument(document("sparse", List.of(10,100), true))));
        assertTrue(restored.getQuery().matches(new SerializedDocument(document("middle", List.of(40,60), true))));
    }

    @Test
    void originalTextBoundsUseTheExistingSortableNormalization() {
        for (String value : List.of("I", "İ", "i", "ı")) {
            assertEquals(SortableEntry.formatSortable(value), CollectionValues.key(value));
            var document = Document.builder().id("text").entries(Map.of(
                    new Document.Entry(Document.EntryType.TEXT, value), List.of(new Document.Path("text")))).build();
            var range = between("İ", "j", "text").withValueSelection(ValueSelection.INTERVAL);
            var restored = (BetweenConstraint) JsonUtils.fromJson(JsonUtils.asBytes(range), Constraint.class);
            assertEquals(range.matches(document), restored.matches(document));
        }
    }

    @Test
    void legacyFieldNamesDoNotBecomeInternalSortInstructions() {
        for (String path : List.of("$values:foo", "$values:MIN:eA")) {
            Document a = Document.builder().id("a").entries(Map.of(new Document.Entry(Document.EntryType.NUMERIC, "1"),
                    List.of(new Document.Path(path)))).build();
            Document b = Document.builder().id("b").entries(Map.of(new Document.Entry(Document.EntryType.NUMERIC, "2"),
                    List.of(new Document.Path(path)))).build();
            var search = SearchDocuments.builder().query(SearchQuery.builder().collection("text").build()).sorting(List.of(path)).build();
            assertFalse(CollectionSearchProtocol.required(search));
            assertEquals(List.of("a", "b"), List.of(b, a).stream().sorted(Document.createComparator(search))
                    .map(Document::getId).toList());
        }
    }

    @Test
    void logicalGroupingRetainsDistinctOriginalTextBounds() {
        var a = BetweenConstraint.below("İ", "text");
        var b = BetweenConstraint.below("i\u0307", "text");
        assertNotEquals(a, b);
        var all = AllConstraint.all(a, b);
        var query = CollectionValuesConstraint.apply(SearchQuery.builder().collection("text").constraint(all).build());
        var reversed = CollectionValuesConstraint.apply(SearchQuery.builder().collection("text").constraint(AllConstraint.all(b, a)).build());
        Document doc = Document.builder().id("x").entries(Map.of(new Document.Entry(Document.EntryType.TEXT, "i"),
                List.of(new Document.Path("text")))).build();
        assertEquals(query.decomposeConstraints().matches(doc), reversed.decomposeConstraints().matches(doc));
        assertEquals(a.getMax(), a.withValueSelection(ValueSelection.INTERVAL).withValueSelection(null).getMax());
    }

    @Test
    void optionalMinimumSurvivesWireAndLegacyRowsFallBackPerDocument() {
        var legacy = new SortableEntry("prices", 100);
        assertFalse(new String(JsonUtils.asBytes(legacy), java.nio.charset.StandardCharsets.UTF_8).contains("minimum"));
        var current = legacy.merge(new SortableEntry("prices", 10));
        assertEquals(current, JsonUtils.fromJson(JsonUtils.asBytes(current), SortableEntry.class));
        assertEquals(legacy, JsonUtils.fromJson(JsonUtils.asBytes(legacy), SortableEntry.class));
        assertNull(legacy.merge(new SortableEntry("prices", 100)).getMinimum());
        var raw = document("sparse", List.of(10, 100), false);
        var old = raw.toBuilder().sortables(Set.of(legacy)).build();
        var updated = raw.toBuilder().sortables(Set.of(current)).build();
        var range = between(30, 70, "prices").withValueSelection(ValueSelection.INTERVAL);
        assertFalse(range.matches(old));
        assertTrue(range.matches(updated));
        assertTrue(range.matches(raw));
        assertFalse(SearchValue.min("prices").below(30).matches(old));
        assertTrue(SearchValue.min("prices").below(30).matches(updated));
    }

    @Test
    void wildcardRangesCombineIndexedAndUnindexedPaths() {
        var raw = Document.builder().id("mixed").entries(Map.of(
                new Document.Entry(Document.EntryType.NUMERIC, "10"), List.of(new Document.Path("a")),
                new Document.Entry(Document.EntryType.NUMERIC, "100"), List.of(new Document.Path("b")))).build();
        for (var doc : List.of(raw, raw.toBuilder().sortables(Set.of(new SortableEntry("a", 10))).build())) {
            assertTrue(between(40, 60, "**").withValueSelection(ValueSelection.INTERVAL).matches(doc));
            assertEquals(CollectionValues.key(100), SearchValue.max("**").key(doc));
        }
    }

    @Test
    void encodedEmptyStringSurvivesWireWithoutBecomingAnOpenBound() {
        var entry = new SortableEntry("text", "");
        assertEquals(entry, JsonUtils.fromJson(JsonUtils.asBytes(entry), SortableEntry.class));
        assertEquals("", CollectionValues.key(""));
        assertEquals("", CollectionValues.key("  "));
        var range = between("", "", "text").withValueSelection(ValueSelection.INTERVAL);
        assertFalse(range.matches(Document.builder().id("empty").sortables(Set.of(entry)).build()));
    }

    @Test
    void annotatedCollectionsProduceOnlyDistinctMinimaAndKeepAliases() {
        record Values(@Sortable("alias") List<Integer> values, @Sortable String scalar) { }
        for (var values : List.of(List.of(10), List.of(10, 10), List.of(100, 10), List.of(10, 100))) {
            var indexes = new JacksonInverter().getSortables(new Values(values, "keep"));
            var array = indexes.stream().filter(e -> e.getName().equals("alias")).findFirst().orElseThrow();
            assertEquals(SortableEntry.formatSortable(values.stream().max(Integer::compare).orElseThrow()), array.getValue());
            assertEquals(values.contains(100) ? SortableEntry.formatSortable(10) : null, array.getMinimum());
            assertNull(indexes.stream().filter(e -> e.getName().equals("scalar")).findFirst().orElseThrow().getMinimum());
        }
    }

    @Test
    void utcTimestampsUseExistingMillisecondKeysWithAndWithoutSortables() {
        record Plain(List<java.time.Instant> dates) { }
        record Indexed(@Sortable List<java.time.Instant> dates) { }
        var start = java.time.Instant.parse("2026-10-10T12:00:00Z");
        var next = start.plusMillis(1);
        var inverter = new JacksonInverter();
        for (boolean indexed : List.of(false, true)) {
            var documents = new ArrayList<Document>();
            var values = List.of(List.of(start, next), List.of(next), List.of(start.plusNanos(1)),
                                 List.of(start.plusSeconds(1)), List.<java.time.Instant>of());
            for (int i = 0; i < values.size(); i++) {
                Object value = indexed ? new Indexed(values.get(i)) : new Plain(values.get(i));
                var serialized = inverter.toDocument(value, value.getClass().getName(), 0, "d" + i,
                        "dates", null, null, null);
                // Force the old binary document representation rather than relying on a live object.
                documents.add(serialized.toBuilder().document(null).build().deserializeDocument());
            }
            assertEquals(SortableEntry.formatSortable(start), SearchValue.min("dates").key(documents.getFirst()));
            assertEquals(SortableEntry.formatSortable(next), SearchValue.max("dates").key(documents.getFirst()));
            assertTrue(documents.get(2).getEntries().keySet().stream()
                    .anyMatch(e -> e.getValue().equals("2026-10-10T12:00:00.000000001Z")));
            for (var constraint : List.of(between(start, next, "dates").withValueSelection(ValueSelection.INTERVAL),
                    SearchValue.min("dates").between(start, next))) {
                var restored = JsonUtils.fromJson(JsonUtils.asBytes(constraint), Constraint.class);
                assertEquals(List.of("d0", "d2"), documents.stream().filter(restored::matches)
                        .map(Document::getId).toList());
            }
            assertEquals(List.of("d2"), documents.stream().filter(SearchValue.max("dates").below(next)::matches)
                    .map(Document::getId).toList());
            for (var selection : List.of(SearchValue.min("dates"), SearchValue.max("dates"))) {
                for (boolean descending : List.of(false, true)) {
                    var request = SearchDocuments.builder().query(SearchQuery.builder().collection("dates").build())
                            .collectionValueSorting(true).sorting(List.of((descending ? "-" : "")
                                    + selection.sortPath() + ":nullsLast")).build();
                    var expected = selection.selection() == ValueSelection.MIN
                            ? (descending ? List.of("d3", "d1", "d2", "d0", "d4") : List.of("d0", "d2", "d1", "d3", "d4"))
                            : (descending ? List.of("d3", "d1", "d0", "d2", "d4") : List.of("d2", "d0", "d1", "d3", "d4"));
                    assertEquals(expected, documents.stream().sorted(Document.createComparator(request))
                            .map(Document::getId).toList());
                }
            }
        }
        assertEquals("2026-99-99t12:00:00z", CollectionValues.key("2026-99-99T12:00:00Z"));
        assertEquals("plain text", CollectionValues.key(" Plain TEXT "));
    }

    @Test
    void supplementaryTextUsesTheSameOrderWithAndWithoutSortables() {
        record Plain(List<String> values) { }
        record Indexed(@Sortable List<String> values) { }
        String bmp = "\uE000", supplementary = "😀";
        var inverter = new JacksonInverter();
        for (boolean indexed : List.of(false, true)) {
            var documents = new ArrayList<Document>();
            var inputs = List.of(List.of(bmp), List.of(supplementary), List.of(bmp, supplementary),
                    List.of("a", bmp, supplementary));
            for (int i = 0; i < inputs.size(); i++) {
                Object value = indexed ? new Indexed(inputs.get(i)) : new Plain(inputs.get(i));
                documents.add(inverter.toDocument(value, value.getClass().getName(), 0, "d" + i,
                        "unicode", null, null, null).toBuilder().document(null).build().deserializeDocument());
            }
            assertEquals(bmp, SearchValue.min("values").key(documents.get(2)));
            assertEquals(supplementary, SearchValue.max("values").key(documents.get(2)));
            assertEquals(supplementary, SearchValue.max("values").key(documents.get(3)));
            for (var selection : ValueSelection.values()) {
                var constraint = BetweenConstraint.atLeast(supplementary, "values").withValueSelection(selection);
                assertFalse(constraint.matches(documents.getFirst()));
                assertTrue(constraint.matches(documents.get(1)));
                assertFalse(between(supplementary, bmp, "values").withValueSelection(selection)
                        .matches(documents.get(2)));
            }
            assertTrue(SearchValue.min("values").below(supplementary).matches(documents.get(2)));
            assertTrue(SearchValue.max("values").atLeast(supplementary).matches(documents.get(3)));
            for (var selection : List.of(SearchValue.min("values"), SearchValue.max("values"))) {
                for (boolean descending : List.of(false, true)) {
                    var request = SearchDocuments.builder().query(SearchQuery.builder().collection("unicode").build())
                            .collectionValueSorting(true).sorting(List.of((descending ? "-" : "")
                                    + selection.sortPath())).build();
                    var expected = selection.selection() == ValueSelection.MIN
                            ? (descending ? List.of("d1", "d2", "d0", "d3") : List.of("d3", "d0", "d2", "d1"))
                            : (descending ? List.of("d3", "d2", "d1", "d0") : List.of("d0", "d1", "d2", "d3"));
                    assertEquals(expected, documents.stream().sorted(Document.createComparator(request))
                            .map(Document::getId).toList());
                }
            }
        }
    }

    @Test
    void timestampRecognitionIsLimitedToValidUppercaseUtcText() {
        String instantText = "2026-10-10T12:00:00Z";
        assertEquals("2026-10-10T12:00:00.000Z", CollectionValues.key(instantText));
        assertEquals("2026-10-10t12:00:00z", CollectionValues.key("2026-10-10t12:00:00z"));
        assertEquals("2026-10-10t12:00:00+01:00", CollectionValues.key("2026-10-10T12:00:00+01:00"));
        assertEquals("2026-10-10t99:00:00z", CollectionValues.key("2026-10-10T99:00:00Z"));
        // A String sortable remains ordinary normalized text; do not rewrite existing string indexes as dates.
        assertEquals("2026-10-10t12:00:00z", SortableEntry.formatSortable(instantText));
        assertEquals("2026-10-10t12:00:00z", CollectionValues.key(SortableEntry.formatSortable(instantText)));
    }

    private Document document(String id, List<? extends Number> prices, boolean indexed) {
        Map<Document.Entry, List<Document.Path>> entries = new LinkedHashMap<>();
        for (int i = 0; i < prices.size(); i++) {
            entries.computeIfAbsent(new Document.Entry(Document.EntryType.NUMERIC, prices.get(i).toString()), ignored -> new ArrayList<>())
                    .add(new Document.Path("prices/" + i));
        }
        Set<SortableEntry> sortables = indexed && !prices.isEmpty()
                ? Set.of(prices.stream().map(n -> new SortableEntry("prices", n)).reduce(SortableEntry::merge).orElseThrow())
                : Set.of();
        return Document.builder().id(id).summary(() -> "").collection("products").entries(entries).sortables(sortables).build();
    }
}
