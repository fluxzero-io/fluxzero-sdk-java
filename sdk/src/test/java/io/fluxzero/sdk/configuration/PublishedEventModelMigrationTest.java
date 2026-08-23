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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.ThrowingErrorHandler;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static io.fluxzero.sdk.tracking.ConsumerHandlingMode.SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedEventModelMigrationTest {

    @Test
    void fixesGlobalOrderingAndFailoverInTheConsumerContract() {
        ConsumerConfiguration configuration =
                PublishedEventModelMigration.consumerConfiguration(
                        "legacy-model-migration-v1", 100);

        assertEquals("legacy-model-migration-v1", configuration.getName());
        assertEquals(0L, configuration.getMinIndex());
        assertEquals(1, configuration.getThreads());
        assertEquals(100, configuration.getMaxFetchSize());
        assertTrue(configuration.singleTracker());
        assertTrue(configuration.exclusive());
        assertFalse(configuration.passive());
        assertEquals(SYNC, configuration.getHandlingMode());
        assertInstanceOf(ThrowingErrorHandler.class,
                         configuration.getErrorHandler());
    }

    @Test
    void ownsTheReplayAndAdoptionCommandLineContract() {
        assertNull(PublishedEventModelMigration.adoptionBoundary(
                new String[0]));
        assertEquals(123L, PublishedEventModelMigration.adoptionBoundary(
                new String[]{"adopt", "123"}));
        assertThrows(IllegalArgumentException.class,
                     () -> PublishedEventModelMigration.adoptionBoundary(
                             new String[]{"adopt"}));
        assertThrows(IllegalArgumentException.class,
                     () -> PublishedEventModelMigration.adoptionBoundary(
                             new String[]{"adopt", "-1"}));
        assertThrows(IllegalArgumentException.class,
                     () -> PublishedEventModelMigration.adoptionBoundary(
                             new String[]{"unknown", "123"}));

        PublishedEventModelMigration.requireCatchUp(
                new Position(123L), 123L);
        assertThrows(IllegalStateException.class,
                     () -> PublishedEventModelMigration.requireCatchUp(
                             new Position(122L), 123L));
        assertThrows(IllegalStateException.class,
                     () -> PublishedEventModelMigration.requireCatchUp(
                             Position.newPosition(), 123L));
    }

    @Test
    void rejectsAnEmptyOrInvalidModelCatalogBeforeStarting() {
        LocalClient emptyClient = LocalClient.newInstance(null);
        assertThrows(IllegalArgumentException.class,
                     () -> PublishedEventModelMigration.builder()
                             .name("empty")
                             .client(emptyClient)
                             .build());
        emptyClient.shutDown();

        LocalClient invalidClient = LocalClient.newInstance(null);
        assertThrows(IllegalArgumentException.class,
                     () -> PublishedEventModelMigration.builder()
                             .name("invalid")
                             .client(invalidClient)
                             .modelTypes(String.class)
                             .build());
        invalidClient.shutDown();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void replaysPublishedEventsWithoutAGlobalFluxzeroInstanceOrRepublishing() throws Exception {
        LocalClient client = LocalClient.newInstance(null);
        JacksonSerializer serializer = new JacksonSerializer();
        try (PublishedEventModelMigration migration =
                     PublishedEventModelMigration.builder()
                             .name("legacy-model-migration-v1")
                             .client(client)
                             .serializer(serializer)
                             .modelTypes(LegacyModel.class)
                             .build()) {
            migration.replay();

            client.getGatewayClient(EVENT).append(
                    Guarantee.STORED,
                    new Message(new LegacyIncrement("legacy", 1))
                            .serialize(serializer),
                    new Message(new LegacyIncrement("legacy", 2))
                            .serialize(serializer)).join();

            await(() -> migration.repository()
                    .load("legacy", LegacyModel.class)
                    .map(LegacyModel.class::cast)
                    .map(model -> model.value() == 3)
                    .orElse(false));

            assertEquals(2, client.getTrackingClient(EVENT)
                    .readFromIndex(0L, 10).size());
            assertFalse(client.getTrackingClient(EVENT)
                                .getPosition(migration.name())
                                .isNew(new int[]{0, MAX_SEGMENT}));
        }
    }

    @Test
    void replayRegistrationStopsOnlyReplayAndLeavesTheRunnerReusable() {
        LocalClient client = LocalClient.newInstance(null);
        try (PublishedEventModelMigration migration =
                     PublishedEventModelMigration.builder()
                             .name("reusable-model-migration")
                             .client(client)
                             .modelTypes(LegacyModel.class)
                             .build()) {
            Registration firstReplay = migration.replay();

            firstReplay.cancel();

            migration.replay().cancel();
            assertEquals("reusable-model-migration", migration.name());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Migration did not reach the expected state");
            }
            Thread.sleep(10L);
        }
    }

    @Model
    private record LegacyModel(@EntityId String id, int value) {
    }

    private record LegacyIncrement(String id, int delta) {
        @Apply
        LegacyModel apply(@Nullable LegacyModel model) {
            return new LegacyModel(
                    id, (model == null ? 0 : model.value()) + delta);
        }
    }
}
