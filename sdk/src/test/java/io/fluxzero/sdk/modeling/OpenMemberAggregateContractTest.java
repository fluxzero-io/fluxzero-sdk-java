/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenMemberAggregateContractTest {
    @Test
    void subtypeOnlyApplyWorksOnAnOpenAggregateMember() {
        TestFixture.create().given(fc -> Fluxzero.loadAggregate("owner", Owner.class)
                        .update(ignored -> new Owner("owner", List.of(new ConcreteItem("item", "before")))))
                .whenExecuting(fc -> Fluxzero.loadAggregate("owner", Owner.class).apply(new Rename("item", "after")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(new ConcreteItem("item", "after"),
                                               Fluxzero.loadAggregate("owner", Owner.class).get().items().getFirst()));
    }

    @Aggregate record Owner(@EntityId String id, @Member List<Item> items) {}
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS)
    interface Item { @EntityId String itemId(); }
    record ConcreteItem(String itemId, String name) implements Item {
        @Apply ConcreteItem rename(Rename event) { return new ConcreteItem(itemId, event.name()); }
    }
    record Rename(String itemId, String name) {}
}
