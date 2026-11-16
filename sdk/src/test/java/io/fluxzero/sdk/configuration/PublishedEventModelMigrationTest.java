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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
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

import java.util.List;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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

            // Materialized state can become visible before the replay batch acknowledges its position.
            // Wait for both milestones within the existing deadline.
            await(() -> !client.getTrackingClient(EVENT).getPosition(migration.name())
                    .isNew(new int[]{0, MAX_SEGMENT}) && migration.repository()
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
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void replayPassesPublicationOnlyTargetsAndRetainsOtherStateChanges() throws Exception {
        var client = LocalClient.newInstance(null);
        var serializer = new JacksonSerializer();
        try (var migration = PublishedEventModelMigration.builder().name("publication-only-replay")
                .client(client).serializer(serializer).modelTypes(LegacyModel.class, CounterModel.class).build()) {
            client.getGatewayClient(EVENT).append(Guarantee.STORED,
                    new Message(new LegacyIncrement("legacy", 1)).serialize(serializer),
                    new Message(new PublicationOnly("legacy")).serialize(serializer),
                    new Message(new MixedPublication("legacy", "counter")).serialize(serializer),
                    new Message(new LegacyIncrement("legacy", 2)).serialize(serializer)).join();
            var source = client.getTrackingClient(EVENT).readFromIndex(0L, 100);
            var replay = migration.replay();
            await(() -> client.getTrackingClient(EVENT).getPosition(migration.name())
                    .lowestIndexForSegment(new int[]{0, MAX_SEGMENT}).orElse(-1L) >= source.getLast().getIndex());
            replay.cancel();
            assertEquals(3, migration.repository().load("legacy", LegacyModel.class).get().value());
            assertEquals(1, migration.repository().load("counter", CounterModel.class).get().value());
            assertEquals(source, client.getTrackingClient(EVENT).readFromIndex(0L, 100));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void replayRetryDoesNotRunLiveInterceptorsOrLoseTheOriginalEvent() throws Exception {
        var rejected = new AtomicBoolean();
        var client = conflictingClient(rejected);
        var serializer = new JacksonSerializer();
        try (var migration = PublishedEventModelMigration.builder().name("replay-retry-contract")
                .client(client).serializer(serializer).modelTypes(LegacyModel.class).build()) {
            client.getGatewayClient(EVENT).append(Guarantee.STORED,
                    new Message(new LegacyIncrement("legacy", 1)).serialize(serializer),
                    new Message(new AcceptedLegacyUpdate("legacy")).serialize(serializer)).join();
            var source = client.getTrackingClient(EVENT).readFromIndex(0L, 100);
            var replay = migration.replay();
            await(() -> client.getTrackingClient(EVENT).getPosition(migration.name())
                    .lowestIndexForSegment(new int[]{0, MAX_SEGMENT}).orElse(-1L) >= source.getLast().getIndex());
            replay.cancel();
            assertTrue(rejected.get());
            assertEquals(2, migration.repository().load("legacy", LegacyModel.class).get().value());
            assertEquals(source, client.getTrackingClient(EVENT).readFromIndex(0L, 100));
        }
    }

    @Test
    void storedEventRetryAlsoRemainsApplyOnlyWithoutPublication() {
        var rejected = new AtomicBoolean();
        var client = conflictingClient(rejected);
        try (var application = DefaultFluxzero.builder().disableAutomaticTracking().build(client)) {
            application.registerHandlers(LegacyModel.class);
            application.executeStoredModelEvent(new Message(new LegacyIncrement("legacy", 1))).join();
            application.executeStoredModelEvent(new Message(new AcceptedLegacyUpdate("legacy"))).join();
            assertTrue(rejected.get());
            assertEquals(2, application.modelRepository().load("legacy", LegacyModel.class).get().value());
            assertTrue(client.getTrackingClient(EVENT).readFromIndex(0L, 100).isEmpty());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void storedEventRetryReloadsAConcurrentlyChangedDocumentModel() {
        var base = LocalClient.newInstance(null);
        var client = org.mockito.Mockito.spy(base);
        var store = org.mockito.Mockito.spy(base.getEventStoreClient());
        org.mockito.Mockito.doReturn(store).when(client).getEventStoreClient();
        var advanced = new AtomicBoolean();
        try (var competitor = DefaultFluxzero.builder().disableAutomaticTracking().build(base);
             var application = DefaultFluxzero.builder().disableAutomaticTracking().build(client)) {
            competitor.registerHandlers(DocumentCounter.class);
            application.registerHandlers(DocumentCounter.class);
            org.mockito.Mockito.doAnswer(invocation -> {
                CommitModels commit = invocation.getArgument(0);
                if (commit.getCommitId().equals("retry-source") && advanced.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(() -> competitor.executeStoredModelEvent(
                            new Message(new DocumentIncrement("document", 10))).join()).join();
                    @SuppressWarnings("unchecked")
                    var result = (CompletableFuture<CommitModelsResult>)
                            invocation.callRealMethod();
                    return result.thenApply(conflict -> {
                        assertFalse(conflict.isAccepted());
                        assertTrue(conflict.getConflicts().getFirst().getCurrentStateIndex() > commit.getReadStateIndex());
                        return conflict;
                    });
                }
                return invocation.callRealMethod();
            }).when(store).commitModels(org.mockito.ArgumentMatchers.any());
            application.executeStoredModelEvent(new Message(new DocumentIncrement("document", 1))).join();
            application.executeStoredModelEvent(new Message(new DocumentIncrement("document", 2),
                    Metadata.empty(), "retry-source", Instant.parse("2026-01-01T00:00:00Z"))).join();
            assertTrue(advanced.get());
            assertEquals(13, application.modelRepository().load("document", DocumentCounter.class).get().value());
            assertTrue(client.getTrackingClient(EVENT).readFromIndex(0L, 100).isEmpty());
        }
    }

    @Test
    void refusesLiveReplayWhenLegacyStagedDocumentsRemain() {
        LocalClient client = org.mockito.Mockito.spy(LocalClient.newInstance(null));
        var search = org.mockito.Mockito.spy(client.getSearchClient());
        org.mockito.Mockito.doReturn(search).when(client).getSearchClient();
        org.mockito.Mockito.doReturn(new io.fluxzero.common.api.search.GetModelMigrationsResult(1L,
                List.of(new io.fluxzero.common.api.modeling.ModelHeadState(
                        "legacy", "LegacyModel", 0L, 1L, true, false))))
                .when(search).getModelMigrations(org.mockito.ArgumentMatchers.any());
        try (var migration = PublishedEventModelMigration.builder().name("legacy-staging-guard")
                .client(client).modelTypes(LegacyModel.class).build()) {
            var failure = assertThrows(IllegalStateException.class, migration::replay);
            assertTrue(failure.getMessage().contains("verify and adopt"));
            assertTrue(client.getTrackingClient(EVENT).getPosition(migration.name())
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

    private static LocalClient conflictingClient(AtomicBoolean rejected) {
        var client = org.mockito.Mockito.spy(LocalClient.newInstance(null));
        var store = org.mockito.Mockito.spy(client.getEventStoreClient());
        org.mockito.Mockito.doReturn(store).when(client).getEventStoreClient();
        org.mockito.Mockito.doAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            if (commit.getSubsteps().stream().anyMatch(step -> step.getEvent() != null
                    && step.getEvent().getData().getType().endsWith("AcceptedLegacyUpdate"))
                    && rejected.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(
                        CommitModelsResult.conflict(commit.getRequestId(), commit.getCommitId(),
                                List.of(new ModelCommitConflict(
                                        "legacy", commit.getReadStateIndex(), -1)), true));
            }
            return invocation.callRealMethod();
        }).when(store).commitModels(org.mockito.ArgumentMatchers.any());
        return client;
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

    @Model(searchable = false)
    private record LegacyModel(@EntityId String id, int value) {
    }

    private record LegacyIncrement(String id, int delta) {
        @Apply
        LegacyModel apply(@Nullable LegacyModel model) {
            return new LegacyModel(
                    id, (model == null ? 0 : model.value()) + delta);
        }
    }
    @Model(persistence = ModelPersistence.DOCUMENT)
    private record DocumentCounter(@EntityId String id, int value) {}

    private record DocumentIncrement(String id, int delta) {
        @InterceptApply
        Object intercept() { throw new AssertionError("Accepted events must not rerun interceptors"); }

        @AssertLegal
        void assertLegal() { throw new AssertionError("Accepted events must not rerun assertions"); }

        @Apply
        DocumentCounter apply(@Nullable DocumentCounter current) {
            return new DocumentCounter(id, (current == null ? 0 : current.value()) + delta);
        }
    }

    private record AcceptedLegacyUpdate(String id) {
        @AssertLegal
        void assertLegal() { throw new AssertionError("Accepted events must not rerun command assertions"); }


        @InterceptApply
        Object intercept(Graph<LegacyModel> model) { return model.delete(); }

        @Apply
        LegacyModel apply(LegacyModel model) { return new LegacyModel(id, model.value() + 1); }
    }

    private record PublicationOnly(String id) {
        @Apply(eventPublication = io.fluxzero.sdk.modeling.EventPublication.ALWAYS,
               publicationStrategy = io.fluxzero.sdk.modeling.EventPublicationStrategy.PUBLISH_ONLY)
        LegacyModel apply(LegacyModel model) { return model; }
    }

    @Model(searchable = false)
    private record CounterModel(@EntityId String counterId, int value) {}

    private record MixedPublication(String id, String counterId) {
        @Apply(eventPublication = io.fluxzero.sdk.modeling.EventPublication.ALWAYS,
               publicationStrategy = io.fluxzero.sdk.modeling.EventPublicationStrategy.PUBLISH_ONLY)
        LegacyModel apply(LegacyModel model) { return model; }

        @Apply
        CounterModel count(@Nullable CounterModel model) {
            return new CounterModel(counterId, model == null ? 1 : model.value() + 1);
        }
    }

}
