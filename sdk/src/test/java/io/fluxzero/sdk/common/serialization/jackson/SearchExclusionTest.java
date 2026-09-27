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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.fluxzero.common.search.Facet;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.Document;
import io.fluxzero.common.search.JacksonInverter;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.common.search.SearchExclusions;
import io.fluxzero.common.search.SearchInclude;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.api.search.constraints.BetweenConstraint.between;
import static io.fluxzero.common.api.search.constraints.ContainsConstraint.contains;
import static io.fluxzero.common.api.search.constraints.ExistsConstraint.exists;
import static io.fluxzero.common.api.search.constraints.FacetConstraint.matchFacet;
import static io.fluxzero.common.api.search.constraints.LookAheadConstraint.lookAhead;
import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static io.fluxzero.common.api.search.constraints.QueryConstraint.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchExclusionTest {
    private final JacksonSerializer serializer = new JacksonSerializer();

    @Test
    void keepsDuplicateValuesAtTheirOwnConcretePathsAfterSerialization() {
        var value = new Parent("sharedvalue", List.of(new Child("sharedvalue", "sharedvalue", 42),
                new Child("other", "sharedvalue", 7)));
        var document = roundTrip(value);
        for (String path : List.of("children/secret", "children/0/secret", "children/*/secret", "**/secret")) {
            for (var constraint : List.of(match("sharedvalue", path), match("sharedvalue", true, path),
                    contains("sharedvalue", path), lookAhead("shared", path), query("sharedvalue", path))) {
                assertFalse(constraint.matches(document.deserializeDocument()), constraint::toString);
            }
        }
        assertTrue(match("sharedvalue", "children/0/name").matches(document.deserializeDocument()));
        assertTrue(match("sharedvalue").matches(document.deserializeDocument()));
        assertFalse(match("sharedvalue", "children/secret").matches(document.deserializeDocument()
                .filterPaths(Document.Path.pathPredicate("children/secret"))));
        assertTrue(exists("children/secret").matches(document.deserializeDocument()));
        assertTrue(between(40, 50, "children/number").matches(document.deserializeDocument()));
        assertEquals(value, serializer.fromDocument(document));
        assertFalse(match("sharedvalue", "children/secret").matches(document.deserializeDocument()
                .toBuilder().summary(() -> null).build()));
    }

    @Test
    void handlesExcludedObjectsEscapedNamesAndSearchInclude() {
        var value = Map.of("a/b.c~d", new Restricted("same", "same"),
                "hidden", new HiddenObject(new Restricted("same", "same")));
        var document = roundTrip(value).deserializeDocument();
        assertTrue(match("same", "a/b\\.c~d/included").matches(document));
        assertFalse(match("same", "a/b\\.c~d/excluded").matches(document));
        assertFalse(match("same", "hidden/**").matches(document));
        assertTrue(exists("hidden/**").matches(document));
    }

    @Test
    void doesNotConfuseNormalizedOrArrayPaths() {
        var document = roundTrip(List.of(new Restricted("same", "same"), Map.of("excluded", "same")))
                .deserializeDocument();
        assertFalse(match("same", "0/excluded").matches(document));
        assertTrue(match("same", "1/excluded").matches(document));
        assertTrue(match("same", "excluded").matches(document));
        var named = roundTrip(new Names("same", "same")).deserializeDocument();
        assertFalse(match("same", "with\\.dot").matches(named));
        assertTrue(match("same", "with/slash").matches(named));
    }

    @Test
    void doesNotSearchTechnicalMetadataOrChangeRangeAndExistence() {
        var document = roundTrip(new NullOnly(null)).deserializeDocument();
        assertFalse(exists("**").matches(document));
        assertFalse(match(true).matches(document));
        assertTrue(document.getMatchingEntries(Document.Path.pathPredicate("**"))
                           .allMatch(e -> e.getType() == Document.EntryType.NULL));
        var withTrue = roundTrip(new BooleanValue(true, true)).deserializeDocument();
        assertFalse(match(true, "$metadata/**").matches(withTrue));
        assertTrue(match(true, "visible").matches(withTrue));
        assertFalse(match(true, "hidden").matches(withTrue));
        assertTrue(exists("hidden").matches(withTrue));
    }

    @Test
    void refreshesExclusionsWhenReindexingAndRetainsOrdinaryMetadata() {
        var original = roundTrip(new Child("same", "same", 3));
        var stale = original.getMetadata().with("tenant", "blue");
        var updated = serializer.toDocument(Map.of("name", "same", "secret", "same", "number", 3),
                "id", "docs", null, null, stale);
        assertFalse(updated.getMetadata().containsKey(SearchExclusions.METADATA_KEY));
        assertTrue(match("same", "secret").matches(updated.deserializeDocument()));
        assertTrue(match("blue", "$metadata/tenant").matches(updated.deserializeDocument()));
        assertFalse(match(true).matches(updated.deserializeDocument()));
    }

    @Test
    void preservesCustomSummarizersAndSubclassOverrides() {
        var calls = new AtomicInteger();
        var overridden = new JacksonInverter() {
            @Override
            public String summarize(Object value) {
                calls.incrementAndGet();
                return "same";
            }
        };
        var value = new Child("same", "same", 3);
        var overriddenDocument = overridden.toDocument(value, "child", 0, "id", "docs", null, null, Metadata.empty());
        assertEquals("same", overriddenDocument.getSummary());
        assertTrue(match("same", "secret").matches(overriddenDocument.deserializeDocument()));
        assertEquals(1, calls.get());
        var custom = new JacksonInverter(JsonUtils.writer, ignored -> "same");
        var customDocument = custom.toDocument(value, "child", 0, "id", "docs", null, null, Metadata.empty());
        assertEquals("same", customDocument.getSummary());
        assertTrue(match("same", "secret").matches(customDocument.deserializeDocument()));
    }

    @Test
    void retainsRangeFacetAndInheritedDefaultSummaryContracts() {
        var document = roundTrip(new FacetedNumber(42)).deserializeDocument();
        assertTrue(matchFacet("number", 42).matches(document));
        assertTrue(between(40, 50, "number").matches(document));
        assertFalse(match(42, "number").matches(document));
        var inherited = new JacksonInverter() {};
        var searchable = inherited.toDocument(new Child("same", "same", 3), "child", 0, "id", "docs", null, null,
                Metadata.empty()).deserializeDocument();
        assertFalse(match("same", "secret").matches(searchable));
        assertTrue(match("same", "name").matches(searchable));
    }

    @Test
    void readsLegacyDocumentsWithoutExclusionMetadata() {
        var newDocument = roundTrip(new Child("visible", "hidden", 4));
        var entries = new java.util.LinkedHashMap<Document.Entry, List<Document.Path>>();
        newDocument.deserializeDocument().getEntries().forEach((entry, paths) -> {
            var retained = paths.stream().filter(p -> !SearchExclusions.isMetadataPath(p)).toList();
            if (!retained.isEmpty()) { entries.put(entry, retained); }
        });
        var oldDocument = newDocument.deserializeDocument().toBuilder().entries(entries).build();
        var serialized = new SerializedDocument(oldDocument);
        assertEquals(new Child("visible", "hidden", 4), serializer.fromDocument(serialized));
        assertFalse(match("hidden", "secret").matches(oldDocument));
    }

    private SerializedDocument roundTrip(Object value) {
        var document = serializer.toDocument(value, "id", "docs", null, null);
        return new SerializedDocument(document.getId(), document.getTimestamp(), document.getEnd(),
                document.getCollection(), document.getDocument(), document.getSummary(), document.getFacets(),
                document.getIndexes());
    }

    public record Parent(String name, List<Child> children) {}
    public record Child(String name, @SearchExclude String secret, @SearchExclude int number) {}
    @SearchExclude public record Restricted(@SearchInclude String included, String excluded) {}
    public record HiddenObject(@SearchExclude Restricted data) {}
    public record Names(@SearchExclude @JsonProperty("with.dot") String hidden,
                        @JsonProperty("with/slash") String visible) {}
    public record NullOnly(@JsonInclude(JsonInclude.Include.ALWAYS) @SearchExclude String hidden) {}
    public record FacetedNumber(@SearchExclude @Facet int number) {}
    public record BooleanValue(boolean visible, @SearchExclude boolean hidden) {}
}
