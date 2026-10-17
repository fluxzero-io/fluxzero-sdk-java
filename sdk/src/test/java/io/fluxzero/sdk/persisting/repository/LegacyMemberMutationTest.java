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
import io.fluxzero.sdk.modeling.EntityId;
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

    private TestFixture fixture(boolean async) {
        return async ? TestFixture.createAsync(new Handler()) : TestFixture.create(new Handler());
    }

    static Root initial() {
        return new Root("root", List.of(new Plain("list", "remove"), new Plain("sibling", "keep")),
                        Map.of("key", new Plain("map", "remove")), new Plain("single", "remove"),
                        new Branch("branch", "keep branch", List.of(new Plain("nested", "remove"))),
                        List.of(new Affixed("child", "alternate")));
    }

    record Remove() {}
    record Fail() {}
    record Plain(@EntityId String id, String value) {}
    record Affixed(@EntityId String id, @Alias String alias) {}
    record Branch(@EntityId String id, String value, @Member List<Plain> children) {}
    @Aggregate(eventSourced = false, searchable = true)
    record Root(@EntityId String id, @Member List<Plain> children, @Member Map<String, Plain> mapped,
                @Member Plain single, @Member Branch branch, @Member List<Affixed> affixed) {}

    static class Handler {
        @HandleCommand void handle(Remove ignored) {
            var root = Fluxzero.loadAggregate("root", Root.class).update(v -> initial());
            for (String id : List.of("list", "key", "single", "nested")) {
                assertTrue(root.getEntity(id).isPresent(), id);
                var calls = new AtomicInteger();
                assertNull(Fluxzero.<Plain>loadEntity(id).update(v -> { calls.incrementAndGet(); return null; }));
                assertEquals(1, calls.get());
                assertTrue(root.getEntity(id).isEmpty());
            }
            assertEquals(new Plain("sibling", "keep"), Fluxzero.<Plain>loadEntity("sibling").get());
        }

        @HandleCommand void handle(Fail ignored) {
            assertThrows(IllegalStateException.class, () -> Fluxzero.<Plain>loadEntity("list")
                    .update(v -> { throw new IllegalStateException("failed"); }));
        }
    }
}
