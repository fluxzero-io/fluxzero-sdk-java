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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.Decimal;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.HandlePost;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.fluxzero.common.api.search.constraints.BetweenConstraint.between;
import static org.junit.jupiter.api.Assertions.*;

class DecimalModelTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eventsSnapshotsGraphsAndNumericSearchPreserveDecimal(boolean async) {
        var builder = DefaultFluxzero.builder().configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT);
        var fixture = async ? TestFixture.createAsync(builder) : TestFixture.create(builder);
        fixture.givenCommands(new PutPrice("price", new Decimal("900.00")),
                              new PutDocumentPrice("document-price", new Decimal("900.00")))
                .whenCommand(new PutPrice("price", new Decimal("901.00")))
                .expectSuccessfulResult().expectNoErrors().expectThat(fc -> {
                    var price = Fluxzero.loadCurrentGraph("price", Price.class).get();
                    assertEquals(new Price("price", new Decimal("901")), price);
                    assertEquals(2, price.amount.scale());
                    assertEquals(new Decimal("900"), Fluxzero.loadCurrentGraph("document-price", DocumentPrice.class)
                            .get().amount);
                    assertEquals(1, Fluxzero.search(Price.class)
                            .constraint(between(new Decimal("900.5"), new Decimal("901.5"), "amount")).fetchAll().size());
                    assertTrue(Fluxzero.search(Price.class)
                            .constraint(between(new Decimal("902"), new Decimal("903"), "amount")).fetchAll().isEmpty());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void webAcceptsExactStringsAndLegacyNumbers(boolean async) {
        var fixture = async ? TestFixture.createAsync(new Endpoint()) : TestFixture.create(new Endpoint());
        fixture.whenPost("/decimal", "{\"amount\":\"9007199254740993.010000000000000001\"}")
                .expectResult(new Amount(new Decimal("9007199254740993.010000000000000001")))
                .andThen().whenPost("/decimal", "{\"amount\":900.00}")
                .expectResult(new Amount(new Decimal("900")));
    }

    @Model(searchable = true, cached = false, snapshotPeriod = 1)
    record Price(@EntityId String id, Decimal amount) { }
    record PutPrice(String id, Decimal amount) {
        @Apply Price apply(@jakarta.annotation.Nullable Price previous) { return new Price(id, amount); }
    }
    @Model(persistence = ModelPersistence.DOCUMENT, cached = false)
    record DocumentPrice(@EntityId String id, Decimal amount) { }
    record PutDocumentPrice(String id, Decimal amount) {
        @Apply DocumentPrice apply() { return new DocumentPrice(id, amount); }
    }
    record Amount(Decimal amount) { }
    static class Endpoint {
        @HandlePost("/decimal") Amount echo(Amount amount) { return amount; }
    }
}
