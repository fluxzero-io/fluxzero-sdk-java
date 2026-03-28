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
 *
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.AggregateRepository;
import io.fluxzero.sdk.persisting.repository.CachingAggregateRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.BatchInterceptor;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import lombok.Builder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregatePlaybackTest {

    private final TestFixture testFixture = TestFixture.create();

    @Test
    void resolvesAggregateToHandledEventEvenIfLaterApplyExistsInCache() throws NoSuchMethodException {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            Entity<SampleAggregate> aggregate = loadAggregate("sample", SampleAggregate.class);
            aggregate.apply(new CreateSampleAggregate());
            aggregate.apply(new SetPrimaryValue("value-1"));
            aggregate.apply(new SetFirstFlag());
            aggregate.apply(new SetSecondFlag());

            Entity<SampleAggregate> latest = loadAggregate("sample", SampleAggregate.class);
            assertEquals(3L, latest.sequenceNumber());
            assertTrue(latest.get().firstFlag());
            assertTrue(latest.get().secondFlag());

            List<DeserializingMessage> events = fc.eventStore().getEvents("sample").toList();
            DeserializingMessage primaryValueEvent = events.get(1);
            assertEquals(1L, Entity.getSequenceNumber(primaryValueEvent));
            assertTrue(resolver.matches(parameter, handleEvent, primaryValueEvent));

            Entity<SampleAggregate> resolved = primaryValueEvent.apply(message ->
                    resolveEntity(resolver, parameter, handleEvent, message));

            assertNotNull(resolved);
            assertEquals(1L, resolved.sequenceNumber());
            assertEquals("value-1", resolved.get().primaryValue());
            assertFalse(resolved.get().firstFlag());
            assertFalse(resolved.get().secondFlag());
            assertNotNull(resolved.previous());
            assertNull(resolved.previous().get().primaryValue());
        }).expectNoErrors();
    }

    @Test
    void sameClientTrackerBatchWithInterleavedOtherAggregateStillRewindsInjectedEntity() throws Exception {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            loadAggregate("sample", SampleAggregate.class).apply(new CreateSampleAggregate());
            loadAggregate("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));
            loadAggregate("sample", SampleAggregate.class).apply(new SetFirstFlag());
            loadAggregate("sample", SampleAggregate.class).apply(new SetSecondFlag());
            loadAggregate("secondary", SecondaryAggregate.class).apply(new TouchSecondaryAggregate("secondary-1"));

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            List<SerializedMessage> trackerBatch = Stream.concat(
                            sampleEvents.stream().map(DeserializingMessage::getSerializedObject),
                            fc.eventStore().getEvents("secondary")
                                    .map(DeserializingMessage::getSerializedObject))
                    .sorted(Comparator.comparing(SerializedMessage::getIndex))
                    .toList();

            invokeCachingAggregateTracker(fc, trackerBatch);

            Entity<SampleAggregate> latest = fc.aggregateRepository().load("sample", SampleAggregate.class);
            assertEquals(3L, latest.sequenceNumber());
            assertEquals(sampleEvents.get(3).getIndex(), latest.lastEventIndex());

            Entity<SampleAggregate> resolved = sampleEvents.get(1).apply(message ->
                    resolveEntity(resolver, parameter, handleEvent, message));
            assertResolvedAtPrimaryValueEvent(resolved);
        }).expectNoErrors();
    }

    @Test
    void reloadsFromEventStoreAndRewindsWhenOtherAggregateAdvancesTrackerPastCurrentEvent() throws Exception {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            loadAggregate("sample", SampleAggregate.class).apply(new CreateSampleAggregate());
            loadAggregate("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));
            loadAggregate("sample", SampleAggregate.class).apply(new SetFirstFlag());
            loadAggregate("sample", SampleAggregate.class).apply(new SetSecondFlag());
            loadAggregate("secondary", SecondaryAggregate.class).apply(new TouchSecondaryAggregate("secondary-1"));

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            DeserializingMessage primaryValueEvent = sampleEvents.get(1);
            DeserializingMessage otherAggregateEvent = fc.eventStore().getEvents("secondary").findFirst().orElseThrow();

            fc.cache().remove(aggregateCacheKey("sample"));
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));

            invokeCachingAggregateTracker(fc, List.of(otherAggregateEvent.getSerializedObject()));

            AtomicReference<Entity<SampleAggregate>> rawHead = new AtomicReference<>();
            Entity<SampleAggregate> resolved = primaryValueEvent.apply(message -> {
                rawHead.set(Fluxzero.get().aggregateRepository().load("sample", SampleAggregate.class));
                return resolveEntity(resolver, parameter, handleEvent, message);
            });

            assertNotNull(rawHead.get());
            assertEquals(3L, rawHead.get().sequenceNumber());
            assertTrue(rawHead.get().get().firstFlag());
            assertTrue(rawHead.get().get().secondFlag());
            assertResolvedAtPrimaryValueEvent(resolved);
        }).expectNoErrors();
    }

    @Test
    void sameClientOutOfOrderIndicesClearCache() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class).apply(new CreateSampleAggregate());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetFirstFlag());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetAuxiliaryFlag());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetSecondFlag());

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            long[] syntheticIndices = {400L, 409L, 410L, 411L, 408L};
            for (int i = 0; i < sampleEvents.size(); i++) {
                SerializedMessage serializedMessage = sampleEvents.get(i).getSerializedObject();
                serializedMessage.setSource(fc.client().id());
                serializedMessage.setIndex(syntheticIndices[i]);
            }
            assertTrue(fc.cache().containsKey(aggregateCacheKey("sample")));
            invokeCachingAggregateTracker(
                    fc, sampleEvents.stream().map(DeserializingMessage::getSerializedObject).toList());
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
        }).expectNoErrors();
    }

    @Test
    void sameClientIndexingNewestEventKeepsBaselineWithoutEventIdInCache() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class)
                    .update(ignored -> SampleAggregate.builder().build());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            SerializedMessage serializedMessage = sampleEvents.getFirst().getSerializedObject();
            serializedMessage.setSource(fc.client().id());

            assertTrue(fc.cache().containsKey(aggregateCacheKey("sample")));
            assertDoesNotThrow(() -> invokeCachingAggregateTracker(fc, List.of(serializedMessage)));
            assertTrue(fc.cache().containsKey(aggregateCacheKey("sample")));

            Entity<SampleAggregate> cached = fc.aggregateRepository().load("sample", SampleAggregate.class);
            assertEquals(serializedMessage.getIndex(), cached.lastEventIndex());
            assertNull(cached.previous().lastEventId());
            assertNull(cached.previous().lastEventIndex());
        }).expectNoErrors();
    }

    @Test
    void sameClientIndexingNonOldestPendingVersionClearsCacheImmediately() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class).apply(new CreateSampleAggregate());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetFirstFlag());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetAuxiliaryFlag());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetSecondFlag());

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            long[] syntheticIndices = {400L, 409L, 410L, 411L, 408L};
            for (int i = 0; i < sampleEvents.size(); i++) {
                SerializedMessage serializedMessage = sampleEvents.get(i).getSerializedObject();
                serializedMessage.setSource(fc.client().id());
                serializedMessage.setIndex(syntheticIndices[i]);
            }

            assertTrue(fc.cache().containsKey(aggregateCacheKey("sample")));
            invokeCachingAggregateTracker(fc, List.of(sampleEvents.get(4).getSerializedObject()));
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
        }).expectNoErrors();
    }

    @Test
    void staleCommitPreservesExistingBackfilledIndicesInCache() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class).apply(new CreateSampleAggregate());

            DeserializingMessage createEvent = fc.eventStore().getEvents("sample").findFirst().orElseThrow();
            Entity<SampleAggregate> stale = delegateRepository.load("sample", SampleAggregate.class);
            assertNull(stale.lastEventIndex());

            invokeCachingAggregateTracker(fc, List.of(createEvent.getSerializedObject()));
            assertEquals(createEvent.getIndex(),
                         fc.aggregateRepository().load("sample", SampleAggregate.class).lastEventIndex());

            stale.apply(new SetPrimaryValue("value-1"));

            Entity<SampleAggregate> latest = fc.aggregateRepository().load("sample", SampleAggregate.class);
            assertNull(latest.lastEventIndex());
            assertNotNull(latest.previous());
            assertEquals(createEvent.getMessageId(), latest.previous().lastEventId());
            assertEquals(createEvent.getIndex(), latest.previous().lastEventIndex());
        }).expectNoErrors();
    }

    @Test
    void noEventCommitAfterBackfillClearsCache() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class).apply(new CreateSampleAggregate());

            DeserializingMessage createEvent = fc.eventStore().getEvents("sample").findFirst().orElseThrow();
            Entity<SampleAggregate> stale = delegateRepository.load("sample", SampleAggregate.class);

            invokeCachingAggregateTracker(fc, List.of(createEvent.getSerializedObject()));
            Entity<SampleAggregate> indexedHead = fc.aggregateRepository().load("sample", SampleAggregate.class);
            assertEquals(createEvent.getMessageId(), indexedHead.lastEventId());
            assertEquals(createEvent.getIndex(), indexedHead.lastEventIndex());
            assertFalse(indexedHead == stale);

            stale.update(aggregate -> aggregate.toBuilder().auxiliaryFlag(true).build());
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
        }).expectNoErrors();
    }

    @Test
    void noEventCommitAgainstDifferentCachedHeadClearsCache() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class).apply(new CreateSampleAggregate());

            Entity<SampleAggregate> stale = delegateRepository.load("sample", SampleAggregate.class);
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));

            assertTrue(fc.cache().containsKey(aggregateCacheKey("sample")));
            stale.update(aggregate -> aggregate.toBuilder().auxiliaryFlag(true).build());
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
        }).expectNoErrors();
    }

    @Test
    void foreignTrackerEventEvictsCachedAggregateButResolverStillReloadsAndRewinds() throws Exception {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("sample", SampleAggregate.class).apply(new CreateSampleAggregate());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetPrimaryValue("value-1"));
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetFirstFlag());
            delegateRepository.load("sample", SampleAggregate.class).apply(new SetSecondFlag());
            assertTrue(fc.cache().containsKey(aggregateCacheKey("sample")));
            assertNull(delegateRepository.load("sample", SampleAggregate.class).lastEventIndex());

            storeEvent(fc, "sample", SampleAggregate.class,
                       new SetSecondFlag(), "foreign-client", 4L);

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            DeserializingMessage foreignEvent = sampleEvents.get(4);
            DeserializingMessage primaryValueEvent = sampleEvents.get(1);

            assertEquals("foreign-client", foreignEvent.getSerializedObject().getSource());
            invokeCachingAggregateTracker(fc, List.of(foreignEvent.getSerializedObject()));
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));

            AtomicReference<Entity<SampleAggregate>> rawHead = new AtomicReference<>();
            Entity<SampleAggregate> resolved = primaryValueEvent.apply(message -> {
                rawHead.set(Fluxzero.get().aggregateRepository().load("sample", SampleAggregate.class));
                return resolveEntity(resolver, parameter, handleEvent, message);
            });

            assertNotNull(rawHead.get());
            assertEquals(4L, rawHead.get().sequenceNumber());
            assertTrue(rawHead.get().get().firstFlag());
            assertTrue(rawHead.get().get().secondFlag());
            assertResolvedAtPrimaryValueEvent(resolved);
        }).expectNoErrors();
    }

    @Test
    void asyncConsumerInterleavingRewindsInjectedEntityAgainstLaterCachedHead() {
        AsyncPlaybackScenario.reset();

        TestFixture asyncFixture = TestFixture.createAsync(
                        DefaultFluxzero.builder(),
                        new AsyncCommandHandler(),
                        new AsyncHeadAdvancer(),
                        new AsyncSummaryUpdater())
                .resultTimeout(Duration.ofSeconds(3))
                .consumerTimeout(Duration.ofSeconds(3));

        asyncFixture.given(AggregatePlaybackTest::storeForeignCreateEvent)
                .whenCommand(new SetPrimaryValueCommand(AsyncPlaybackScenario.aggregateId, "value-1"))
                .expectNoErrors()
                .expectThat(fc -> assertTrue(fc.aggregateRepository() instanceof CachingAggregateRepository))
                .expectThat(fc -> assertEquals(0L, AsyncPlaybackScenario.sequenceBeforePrimaryValueCommand.get()))
                .expectThat(fc -> assertTrue(AsyncPlaybackScenario.stateBeforePrimaryValueCommand.get().created()))
                .expectThat(fc -> assertDoesNotThrow(() ->
                        assertTrue(AsyncPlaybackScenario.summaryHandled.await(3, TimeUnit.SECONDS))))
                .expectThat(fc -> {
                    ObservedState observed = AsyncPlaybackScenario.observedState.get();
                    assertNotNull(observed);
                    assertEquals(1L, observed.sequenceNumber());
                    assertTrue(observed.rawHeadSequenceNumber() > observed.sequenceNumber());
                    assertEquals("value-1", observed.primaryValue());
                    assertFalse(observed.firstFlag());
                    assertFalse(observed.secondFlag());
                    assertNull(observed.previousPrimaryValue());
                    assertFalse(observed.previousFirstFlag());
                    assertFalse(observed.previousSecondFlag());
                })
                .expectThat(fc -> {
                    Entity<AsyncSampleAggregate> latest =
                            loadAggregate(AsyncPlaybackScenario.aggregateId, AsyncSampleAggregate.class);
                    assertEquals(3L, latest.sequenceNumber());
                    assertTrue(latest.get().created());
                    assertEquals("value-1", latest.get().primaryValue());
                    assertTrue(latest.get().firstFlag());
                    assertTrue(latest.get().secondFlag());
                });
    }

    @SuppressWarnings("unchecked")
    private static Entity<SampleAggregate> resolveEntity(EntityParameterResolver resolver, Parameter parameter,
                                                         HandleEvent handleEvent, DeserializingMessage message) {
        return (Entity<SampleAggregate>) resolver.resolve(parameter, handleEvent).apply(message);
    }

    private static void assertResolvedAtPrimaryValueEvent(Entity<SampleAggregate> resolved) {
        assertNotNull(resolved);
        assertEquals(1L, resolved.sequenceNumber());
        assertEquals("value-1", resolved.get().primaryValue());
        assertFalse(resolved.get().firstFlag());
        assertFalse(resolved.get().secondFlag());
        assertNotNull(resolved.previous());
        assertNull(resolved.previous().get().primaryValue());
    }

    private static void invokeCachingAggregateTracker(Fluxzero fluxzero, List<SerializedMessage> messages) throws Exception {
        Method handleEvents = CachingAggregateRepository.class.getDeclaredMethod("handleEvents", List.class);
        handleEvents.setAccessible(true);
        handleEvents.invoke(fluxzero.aggregateRepository(), messages);
    }

    private static AggregateRepository getCachingDelegate(Fluxzero fluxzero) throws Exception {
        Field delegateField = CachingAggregateRepository.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        return (AggregateRepository) delegateField.get(fluxzero.aggregateRepository());
    }

    private static String aggregateCacheKey(String aggregateId) {
        return "$Aggregate:" + aggregateId;
    }

    private static void storeEvent(Fluxzero fluxzero, String aggregateId, Class<?> aggregateType,
                                   Object payload, String source, Long sequenceNumber) throws Exception {
        Message message = new Message(
                payload,
                Metadata.of(
                        Entity.AGGREGATE_ID_METADATA_KEY, aggregateId,
                        Entity.AGGREGATE_TYPE_METADATA_KEY, aggregateType.getName(),
                        Entity.AGGREGATE_SN_METADATA_KEY, sequenceNumber.toString()));
        SerializedMessage serializedMessage = message.serialize(fluxzero.serializer());
        serializedMessage.setSource(source);
        serializedMessage.setSegment(ConsistentHashing.computeSegment(aggregateId));
        fluxzero.client().getEventStoreClient().storeEvents(aggregateId, List.of(serializedMessage), false).get();
    }

    private static void storeForeignCreateEvent(io.fluxzero.sdk.Fluxzero fluxzero) throws Exception {
        storeEvent(fluxzero, AsyncPlaybackScenario.aggregateId, AsyncSampleAggregate.class,
                   new CreateSampleAggregateEvent(AsyncPlaybackScenario.aggregateId), "foreign-client", 0L);
    }

    static class ProbeHandler {
        @HandleEvent
        void handle(Entity<SampleAggregate> entity) {
        }
    }

    record CreateSampleAggregate() {
        @Apply
        SampleAggregate apply() {
            return SampleAggregate.builder().build();
        }
    }

    record SetPrimaryValue(String primaryValue) {
        @Apply
        SampleAggregate apply(SampleAggregate aggregate) {
            return aggregate.toBuilder().primaryValue(primaryValue).build();
        }
    }

    record SetFirstFlag() {
        @Apply
        SampleAggregate apply(SampleAggregate aggregate) {
            return aggregate.toBuilder().firstFlag(true).build();
        }
    }

    record SetAuxiliaryFlag() {
        @Apply
        SampleAggregate apply(SampleAggregate aggregate) {
            return aggregate.toBuilder().auxiliaryFlag(true).build();
        }
    }

    record SetSecondFlag() {
        @Apply
        SampleAggregate apply(SampleAggregate aggregate) {
            return aggregate.toBuilder().secondFlag(true).build();
        }
    }

    @Aggregate
    @Builder(toBuilder = true)
    record SampleAggregate(String primaryValue, boolean firstFlag,
                           boolean secondFlag, boolean auxiliaryFlag) {
    }

    record TouchSecondaryAggregate(String secondaryId) {
        @Apply
        SecondaryAggregate apply() {
            return SecondaryAggregate.builder().secondaryId(secondaryId).build();
        }
    }

    @Aggregate
    @Builder(toBuilder = true)
    record SecondaryAggregate(String secondaryId) {
    }

    static class AsyncPlaybackScenario {
        static final String aggregateId = "async-sample";
        static volatile CountDownLatch allowSummaryUpdater;
        static volatile CountDownLatch summaryHandled;
        static final AtomicReference<ObservedState> observedState = new AtomicReference<>();
        static final AtomicReference<Long> sequenceBeforePrimaryValueCommand = new AtomicReference<>();
        static final AtomicReference<AsyncSampleAggregate> stateBeforePrimaryValueCommand = new AtomicReference<>();

        static void reset() {
            allowSummaryUpdater = new CountDownLatch(1);
            summaryHandled = new CountDownLatch(1);
            observedState.set(null);
            sequenceBeforePrimaryValueCommand.set(null);
            stateBeforePrimaryValueCommand.set(null);
        }
    }

    record ObservedState(long sequenceNumber, long rawHeadSequenceNumber, String primaryValue,
                         boolean firstFlag, boolean secondFlag,
                         String previousPrimaryValue,
                         boolean previousFirstFlag,
                         boolean previousSecondFlag) {
    }

    @Consumer(name = "async-command-handler")
    static class AsyncCommandHandler {
        @HandleCommand
        String handle(SetPrimaryValueCommand command) {
            Entity<AsyncSampleAggregate> aggregate =
                    loadAggregate(command.aggregateId(), AsyncSampleAggregate.class);
            AsyncPlaybackScenario.sequenceBeforePrimaryValueCommand.set(aggregate.sequenceNumber());
            AsyncPlaybackScenario.stateBeforePrimaryValueCommand.set(aggregate.get());
            aggregate.apply(new SetPrimaryValueEvent(command.aggregateId(), command.primaryValue()));
            return "primary-value";
        }

        @HandleCommand
        String handle(SetFirstFlagCommand command) {
            loadAggregate(command.aggregateId(), AsyncSampleAggregate.class)
                    .apply(new SetFirstFlagEvent(command.aggregateId()));
            return "first-flag";
        }

        @HandleCommand
        String handle(SetSecondFlagCommand command) {
            loadAggregate(command.aggregateId(), AsyncSampleAggregate.class)
                    .apply(new SetSecondFlagEvent(command.aggregateId()));
            return "second-flag";
        }
    }

    @Consumer(name = "async-head-advancer")
    static class AsyncHeadAdvancer {
        @HandleEvent
        void handle(SetPrimaryValueEvent event) {
            io.fluxzero.sdk.Fluxzero.sendCommandAndWait(
                    new SetFirstFlagCommand(event.aggregateId()));
            io.fluxzero.sdk.Fluxzero.sendCommandAndWait(
                    new SetSecondFlagCommand(event.aggregateId()));
            AsyncPlaybackScenario.allowSummaryUpdater.countDown();
        }
    }

    @Consumer(name = "async-summary-updater", batchInterceptors = GateSummaryUpdaterBatchInterceptor.class)
    static class AsyncSummaryUpdater {
        @HandleEvent
        void handle(SetPrimaryValueEvent event, Entity<AsyncSampleAggregate> entity) {
            Entity<AsyncSampleAggregate> previous = entity.previous();
            Entity<AsyncSampleAggregate> rawHead =
                    Fluxzero.get().aggregateRepository().load(event.aggregateId(), AsyncSampleAggregate.class);
            AsyncPlaybackScenario.observedState.set(new ObservedState(
                    entity.sequenceNumber(),
                    rawHead.sequenceNumber(),
                    entity.get().primaryValue(),
                    entity.get().firstFlag(),
                    entity.get().secondFlag(),
                    previous == null || previous.get() == null ? null : previous.get().primaryValue(),
                    previous != null && previous.get() != null && previous.get().firstFlag(),
                    previous != null && previous.get() != null && previous.get().secondFlag()));
            AsyncPlaybackScenario.summaryHandled.countDown();
        }
    }

    public static class GateSummaryUpdaterBatchInterceptor implements BatchInterceptor {
        @Override
        public java.util.function.Consumer<MessageBatch> intercept(
                java.util.function.Consumer<MessageBatch> consumer, Tracker tracker) {
            return batch -> {
                boolean containsPrimaryValueEvent = batch.getMessages().stream().map(SerializedMessage::getType)
                        .anyMatch(SetPrimaryValueEvent.class.getName()::equals);
                if (containsPrimaryValueEvent) {
                    try {
                        if (!AsyncPlaybackScenario.allowSummaryUpdater.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting for later aggregate updates");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while waiting for later aggregate updates", e);
                    }
                }
                consumer.accept(batch);
            };
        }
    }

    record SetPrimaryValueCommand(String aggregateId, String primaryValue) {
    }

    record SetFirstFlagCommand(String aggregateId) {
    }

    record SetSecondFlagCommand(String aggregateId) {
    }

    record CreateSampleAggregateEvent(String aggregateId) {
        @Apply
        AsyncSampleAggregate apply() {
            return AsyncSampleAggregate.builder().created(true).build();
        }
    }

    record SetPrimaryValueEvent(String aggregateId, String primaryValue) {
        @Apply
        AsyncSampleAggregate apply(AsyncSampleAggregate aggregate) {
            return aggregate.toBuilder().primaryValue(primaryValue).build();
        }
    }

    record SetFirstFlagEvent(String aggregateId) {
        @Apply
        AsyncSampleAggregate apply(AsyncSampleAggregate aggregate) {
            return aggregate.toBuilder().firstFlag(true).build();
        }
    }

    record SetSecondFlagEvent(String aggregateId) {
        @Apply
        AsyncSampleAggregate apply(AsyncSampleAggregate aggregate) {
            return aggregate.toBuilder().secondFlag(true).build();
        }
    }

    @Aggregate
    @Builder(toBuilder = true)
    record AsyncSampleAggregate(boolean created, String primaryValue,
                                boolean firstFlag, boolean secondFlag) {
    }
}
