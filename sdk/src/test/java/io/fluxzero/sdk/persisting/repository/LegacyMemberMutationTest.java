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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Member;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMemberMutationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void findsAffixedMembersBeforeCommitAndPreservesFunctionalIds(boolean async) {
        var fixture = fixture(async);
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new Lookup()).expectSuccessfulResult().expectNoErrors();
            app.cache().clear();
            assertEquals(initial(), app.aggregateRepository().load("root", Root.class).get());
            assertTrue(app.aggregateRepository().getAggregatesFor("prefix-child-suffix").containsKey("root"));
            assertEquals(new Affixed("child", "alternate"),
                         app.apply(fc -> Fluxzero.loadEntity("child", Affixed.class).get()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeAffixedRelationshipsRemainScopedToTheirRepository(boolean async) {
        var fixture = fixture(async);
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new Namespaces()).expectSuccessfulResult().expectNoErrors();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removesMembersFromListMapScalarAndNestedOwner(boolean async) {
        var fixture = fixture(async);
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new Remove()).expectSuccessfulResult().expectNoErrors();
            app.cache().clear();
            Root stored = app.aggregateRepository().load("root", Root.class).get();
            assertEquals(List.of(new Plain("sibling", "keep")), stored.children());
            assertTrue(stored.mapped().isEmpty());
            assertNull(stored.single());
            assertTrue(stored.branch().children().isEmpty());
            assertEquals("keep branch", stored.branch().value());
            for (String id : List.of("list", "key", "single", "nested")) {
                assertTrue(app.aggregateRepository().getAggregatesFor(id).isEmpty(), id);
            }
            assertTrue(app.aggregateRepository().getAggregatesFor("sibling").containsKey("root"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMemberUpdateLeavesOwnerAndPersistenceUnchanged(boolean async) {
        var fixture = fixture(async);
        try (var app = fixture.getFluxzero()) {
            fixture.given(fc -> Fluxzero.loadAggregate("root", Root.class).update(v -> initial()).commit())
                    .whenCommand(new Fail()).expectSuccessfulResult().expectNoErrors();
            app.cache().clear();
            assertEquals(initial(), app.aggregateRepository().load("root", Root.class).get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinctAffixesAndAliasesDoNotChangeIdentityPrecedence(boolean async) {
        var fixture = fixture(async);
        try (var app = fixture.getFluxzero()) {
            fixture.whenCommand(new Collisions()).expectSuccessfulResult().expectNoErrors();
        }
    }

    private TestFixture fixture(boolean async) {
        return async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
    }

    static Root initial() {
        return new Root("root", List.of(new Plain("list", "remove"), new Plain("sibling", "keep")),
                        Map.of("key", new Plain("map", "remove")), new Plain("single", "remove"),
                        new Branch("branch", "keep branch", List.of(new Plain("nested", "remove"))),
                        List.of(new Affixed("child", "alternate")));
    }

    record Lookup() {}
    record Namespaces() {}
    record Remove() {}
    record Fail() {}
    record Collisions() {}
    record OtherAffixed(@EntityId(prefix = "other-") String id) {}
    @Aggregate(eventSourced = false, searchable = true)
    record OtherRoot(@EntityId String id, @Member OtherAffixed child) {}
    record Plain(@EntityId String id, String value) {}
    record Affixed(@EntityId(prefix = "prefix-", postfix = "-suffix") String id, @Alias String alias) {}
    record Branch(@EntityId String id, String value, @Member List<Plain> children) {}
    @Aggregate(eventSourced = false, searchable = true)
    record Root(@EntityId String id, @Member List<Plain> children, @Member Map<String, Plain> mapped,
                @Member Plain single, @Member Branch branch, @Member List<Affixed> affixed) {}

    static class Handler {
        @HandleCommand void handle(Lookup ignored) {
            var root = Fluxzero.loadAggregate("root", Root.class).update(v -> initial());
            for (Entity<Affixed> child : List.of(Fluxzero.loadEntity("child", Affixed.class),
                    Fluxzero.loadEntity(new Id<Affixed>("child", Affixed.class) {}))) {
                assertEquals("root", child.root().id());
                assertEquals("child", child.id());
                assertEquals(new Affixed("child", "alternate"), child.get());
            }
            assertEquals(root.get(), Fluxzero.loadAggregateFor("prefix-child-suffix").get());
            assertEquals(root.get(), Fluxzero.loadAggregateFor("child").get());
            assertEquals(new Affixed("child", "alternate"), Fluxzero.loadEntity("alternate").get());
        }

        @HandleCommand void handle(Namespaces ignored) {
            var repository = Fluxzero.get().aggregateRepository();
            var first = repository.forNamespace("first");
            var second = repository.forNamespace("second");
            first.load("first-root", Root.class).update(v -> initial());
            second.load("second-root", Root.class).update(v -> initial());
            assertEquals(Map.of("first-root", Root.class), first.getAggregatesFor("prefix-child-suffix"));
            assertEquals(Map.of("second-root", Root.class), second.getAggregatesFor("prefix-child-suffix"));
            assertTrue(repository.getAggregatesFor("prefix-child-suffix").isEmpty());
        }

        @HandleCommand void handle(Remove ignored) {
            var root = Fluxzero.loadAggregate("root", Root.class).update(v -> initial());
            for (String id : List.of("list", "key", "single", "nested")) {
                assertTrue(root.getEntity(id).isPresent(), id);
                var calls = new AtomicInteger();
                assertNull(Fluxzero.loadEntity(id, Plain.class).update(v -> { calls.incrementAndGet(); return null; }));
                assertEquals(1, calls.get());
                assertTrue(root.getEntity(id).isEmpty());
            }
            assertEquals(new Plain("sibling", "keep"), Fluxzero.loadEntity("sibling", Plain.class).get());
        }

        @HandleCommand void handle(Collisions ignored) {
            var value = initial();
            var root = Fluxzero.loadAggregate("root", Root.class).update(v -> new Root(value.id(),
                    List.of(new Plain("alternate", "raw collision")), value.mapped(), value.single(),
                    value.branch(), value.affixed()));
            Fluxzero.loadAggregate("other-root", OtherRoot.class)
                    .update(v -> new OtherRoot("other-root", new OtherAffixed("child")));
            assertEquals("root", Fluxzero.loadEntity("child", Affixed.class).root().id());
            assertEquals("other-root", Fluxzero.loadEntity("child", OtherAffixed.class).root().id());
            assertEquals(new Affixed("child", "alternate"), Fluxzero.loadEntity("alternate").get());
            assertEquals(new Affixed("child", "alternate"), root.getEntity("alternate").orElseThrow().get());
            assertTrue(root.getEntity("prefix-child-suffix").isEmpty());
        }

        @HandleCommand void handle(Fail ignored) {
            assertThrows(IllegalStateException.class, () -> Fluxzero.loadEntity("list", Plain.class)
                    .update(v -> { throw new IllegalStateException("failed"); }));
        }
    }
}
