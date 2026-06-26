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
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.logging.ConsoleError;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.repository.AggregateRepository;
import io.fluxzero.sdk.persisting.repository.CachingAggregateRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.BatchInterceptor;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleError;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import lombok.Builder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.ERROR;
import static io.fluxzero.sdk.Fluxzero.loadAggregate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void aggregateMetadataEntityResolutionIsCachedForMessage() throws NoSuchMethodException {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        CountingEntityParameterResolver resolver = new CountingEntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            Entity<SampleAggregate> aggregate = loadAggregate("sample", SampleAggregate.class);
            aggregate.apply(new CreateSampleAggregate());
            aggregate.apply(new SetPrimaryValue("value-1"));

            DeserializingMessage primaryValueEvent = fc.eventStore().getEvents("sample").toList().get(1);
            primaryValueEvent.apply(message -> {
                assertTrue(resolver.matches(parameter, handleEvent, message));
                assertResolvedAtPrimaryValueEvent(resolveEntity(resolver, parameter, handleEvent, message));
                assertTrue(resolver.matches(parameter, handleEvent, message));
                assertResolvedAtPrimaryValueEvent(resolveEntity(resolver, parameter, handleEvent, message));
                return null;
            });

            assertEquals(1, resolver.loadCount);
        }).expectNoErrors();
    }

    @Test
    void aggregateMetadataLoadsAggregateRootWhenLegacyEventsHaveNoRelationships() throws NoSuchMethodException {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            storeLegacyEvent(fc, "legacy", new CreateSampleAggregate(), "legacy-client");
            storeEvent(fc, "legacy", SampleAggregate.class, new SetPrimaryValue("value-1"), "legacy-client", 1L);

            assertTrue(fc.aggregateRepository().getAggregatesFor("legacy").isEmpty());

            DeserializingMessage primaryValueEvent = fc.eventStore().getEvents("legacy").toList().get(1);
            assertEquals(SampleAggregate.class, Entity.getAggregateType(primaryValueEvent));
            assertTrue(resolver.matches(parameter, handleEvent, primaryValueEvent));

            Entity<SampleAggregate> resolved = primaryValueEvent.apply(message ->
                    resolveEntity(resolver, parameter, handleEvent, message));

            assertNotNull(resolved);
            assertEquals(1L, resolved.sequenceNumber());
            assertEquals("value-1", resolved.get().primaryValue());
            assertNotNull(resolved.previous());
            assertNull(resolved.previous().get().primaryValue());
        }).expectNoErrors();
    }

    @Test
    void aggregateParameterInjectionRespectsIgnoreUnknownEventsForColdAggregate() {
        testFixture.whenExecuting(fc -> {
            String aggregateId = "ignore-unknown";
            storeEvent(fc, aggregateId, IgnoringUnknownAggregate.class,
                       new CreateIgnoringUnknownAggregate("created"), "legacy-client", 0L);
            storeUnknownEvent(fc, aggregateId, IgnoringUnknownAggregate.class);

            assertTrue(fc.aggregateRepository().getAggregatesFor(aggregateId).isEmpty());

            DeserializingMessage createEvent = fc.eventStore().getEvents(aggregateId, -1L, 1)
                    .findFirst().orElseThrow();
            IgnoringUnknownParameterHandler handler = new IgnoringUnknownParameterHandler();
            var inspectedHandler = HandlerInspector.createHandler(
                    handler, HandleEvent.class, List.of(new PayloadParameterResolver(), new EntityParameterResolver()));

            assertDoesNotThrow(() -> createEvent.apply(message ->
                    inspectedHandler.getInvoker(message).orElseThrow().invoke()));
            assertEquals(new IgnoringUnknownAggregate("created"), handler.observed.get());
        }).expectNoErrors();
    }

    @Test
    void untypedAggregateLoadIgnoresUnknownEventsAfterDiscoveringIgnoringAggregateType() {
        String aggregateId = "untyped-ignore-unknown";

        testFixture.given(fc -> {
                    storeLegacyEvent(fc, aggregateId, new CreateIgnoringUnknownAggregate("created"), "legacy-client");
                    storeUnknownEvent(fc, aggregateId, IgnoringUnknownAggregate.class);
                    assertTrue(fc.aggregateRepository().getAggregatesFor(aggregateId).isEmpty());
                })
                .whenApplying(fc -> Fluxzero.<IgnoringUnknownAggregate>loadAggregate(aggregateId))
                .<Entity<IgnoringUnknownAggregate>>expectResult(
                        aggregate -> aggregate.type().equals(IgnoringUnknownAggregate.class)
                                     && aggregate.sequenceNumber() == 1L
                                     && new IgnoringUnknownAggregate("created").equals(aggregate.get()));
    }

    @Test
    void untypedAggregateLoadTreatsUnknownRelationshipTypeAsDiscoverableFromEvents() {
        String aggregateId = "unknown-relationship-type";

        testFixture.given(fc -> {
                    storeLegacyEvent(fc, aggregateId, new CreateIgnoringUnknownAggregate("created"), "legacy-client");
                    storeUnknownEvent(fc, aggregateId, IgnoringUnknownAggregate.class);
                    fc.client().getEventStoreClient().repairRelationships(new RepairRelationships(
                            aggregateId, "com.example.MovedAggregate", Set.of(aggregateId), STORED)).get();
                    assertEquals(Void.class, fc.aggregateRepository().getAggregatesFor(aggregateId).get(aggregateId));
                })
                .whenApplying(fc -> Fluxzero.<IgnoringUnknownAggregate>loadAggregate(aggregateId))
                .<Entity<IgnoringUnknownAggregate>>expectResult(
                        aggregate -> aggregate.type().equals(IgnoringUnknownAggregate.class)
                                     && aggregate.sequenceNumber() == 1L
                                     && new IgnoringUnknownAggregate("created").equals(aggregate.get()));
    }

    @Test
    void untypedAggregateLoadFailsWhenFirstEventTypeIsUnknown() {
        String aggregateId = "first-event-unknown";

        testFixture.given(fc -> {
                    storeUnknownEvent(fc, aggregateId, IgnoringUnknownAggregate.class);
                    storeLegacyEvent(fc, aggregateId, new CreateIgnoringUnknownAggregate("created"), "legacy-client");
                    assertTrue(fc.aggregateRepository().getAggregatesFor(aggregateId).isEmpty());
                })
                .whenApplying(fc -> Fluxzero.loadAggregate(aggregateId))
                .expectExceptionalResult(EventSourcingException.class);
    }

    @Test
    void untypedAggregateLoadStillFailsForUnknownEventsWhenDiscoveredTypeDoesNotIgnoreThem() {
        String aggregateId = "untyped-fail-unknown";

        testFixture.given(fc -> {
                    storeLegacyEvent(fc, aggregateId, new CreateStrictUnknownAggregate("created"), "legacy-client");
                    storeUnknownEvent(fc, aggregateId, StrictUnknownAggregate.class);
                    assertTrue(fc.aggregateRepository().getAggregatesFor(aggregateId).isEmpty());
                })
                .whenApplying(fc -> Fluxzero.loadAggregate(aggregateId))
                .expectExceptionalResult(EventSourcingException.class);
    }

    @Test
    void aggregateMetadataDoesNotLoadAggregateForIncompatiblePayloadParameter() throws NoSuchMethodException {
        Method method = IncompatibleErrorHandler.class.getDeclaredMethod("handle", SetPrimaryValue.class);
        Parameter parameter = method.getParameters()[0];
        HandleError handleError = method.getAnnotation(HandleError.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            storeUnknownEvent(fc, "legacy", SampleAggregate.class);
            Message error = new Message(new ConsoleError(), Metadata.of(
                    Entity.AGGREGATE_ID_METADATA_KEY, "legacy",
                    Entity.AGGREGATE_TYPE_METADATA_KEY, SampleAggregate.class.getName()));
            DeserializingMessage message = fc.serializer().deserializeMessage(error.serialize(fc.serializer()), ERROR);

            assertEquals(ConsoleError.class, message.getPayloadClass());
            assertFalse(resolver.matches(parameter, handleError, message));
        }).expectNoErrors();
    }

    @Test
    void aggregateMetadataDoesNotInferAggregateTypeWhenMetadataTypeCannotResolve() throws NoSuchMethodException {
        Method method = ProbeHandler.class.getDeclaredMethod("handle", Entity.class);
        Parameter parameter = method.getParameters()[0];
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            storeUnknownEvent(fc, "legacy", SampleAggregate.class);
            Message event = new Message(new SetPrimaryValue("value-1"), Metadata.of(
                    Entity.AGGREGATE_ID_METADATA_KEY, "legacy",
                    Entity.AGGREGATE_TYPE_METADATA_KEY, "com.example.MovedAggregate"));
            DeserializingMessage message = fc.serializer().deserializeMessage(event.serialize(fc.serializer()), EVENT);

            assertNull(Entity.getAggregateType(message));
            assertFalse(resolver.matches(parameter, handleEvent, message));
        }).expectNoErrors();
    }

    @Test
    void aggregateMetadataDoesNotInjectEntityIntoCommandHandler() throws NoSuchMethodException {
        Method method = CommandEntityHandler.class.getDeclaredMethod("handle", Entity.class);
        EntityParameterResolver resolver = new EntityParameterResolver();

        testFixture.whenExecuting(fc -> {
            storeUnknownEvent(fc, "legacy", SampleAggregate.class);
            Message command = new Message(new SetPrimaryValue("value-1"), Metadata.of(
                    Entity.AGGREGATE_ID_METADATA_KEY, "legacy",
                    Entity.AGGREGATE_TYPE_METADATA_KEY, SampleAggregate.class.getName()));
            DeserializingMessage message = fc.serializer().deserializeMessage(command.serialize(fc.serializer()), COMMAND);

            assertFalse(resolver.mayApply(method, CommandEntityHandler.class));
            assertFalse(HandlerInspector.createHandler(new CommandEntityHandler(), HandleCommand.class, List.of(resolver))
                                .getInvoker(message).isPresent());
        }).expectNoErrors();
    }

    @Test
    void entityResolverMayApplyOnlyToEventAndNotificationMessageHandlers() throws NoSuchMethodException {
        EntityParameterResolver resolver = new EntityParameterResolver();

        assertTrue(resolver.mayApply(entityMethod("handleEvent"), EntityInjectionEligibilityHandler.class));
        assertTrue(resolver.mayApply(entityMethod("handleNotification"), EntityInjectionEligibilityHandler.class));
        assertTrue(resolver.mayApply(entityMethod("validate"), EntityInjectionEligibilityHandler.class));
        assertFalse(resolver.mayApply(entityMethod("handleCommand"), EntityInjectionEligibilityHandler.class));
        assertFalse(resolver.mayApply(entityMethod("handleError"), EntityInjectionEligibilityHandler.class));
        assertFalse(resolver.mayApply(OverridingCommandEntityHandler.class.getDeclaredMethod("handle", Entity.class),
                                      OverridingCommandEntityHandler.class));
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
            assertRelationshipLookupCached(fc, "sample", SampleAggregate.class);
            cacheRelationshipLookup(fc, "stale-sample-child", "sample", SampleAggregate.class);
            invokeCachingAggregateTracker(
                    fc, sampleEvents.stream().map(DeserializingMessage::getSerializedObject).toList());
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
            assertRelationshipLookupInvalidated(fc, "sample");
            assertRelationshipLookupInvalidated(fc, "stale-sample-child");
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
    void ifModifiedNoOpDoesNotCreatePendingCachedVersion() throws Exception {
        testFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            Entity<IfModifiedPlaybackAggregate> afterCreate =
                    delegateRepository.load("if-modified", IfModifiedPlaybackAggregate.class)
                            .apply(new CreateIfModifiedPlaybackAggregate("value-1"));
            assertEquals(0L, afterCreate.sequenceNumber());

            Entity<IfModifiedPlaybackAggregate> afterNoOp =
                    delegateRepository.load("if-modified", IfModifiedPlaybackAggregate.class)
                            .apply(new SetIfModifiedPlaybackValue("value-1"));
            assertEquals(0L, afterNoOp.sequenceNumber());
            assertEquals(afterCreate.lastEventId(), afterNoOp.lastEventId());

            Entity<IfModifiedPlaybackAggregate> afterUpdate =
                    delegateRepository.load("if-modified", IfModifiedPlaybackAggregate.class)
                            .apply(new SetIfModifiedPlaybackValue("value-2"));
            assertEquals(1L, afterUpdate.sequenceNumber());

            List<DeserializingMessage> events = fc.eventStore().getEvents("if-modified").toList();
            assertEquals(2, events.size());
            events.forEach(e -> e.getSerializedObject().setSource(fc.client().id()));

            assertDoesNotThrow(() -> invokeCachingAggregateTracker(
                    fc, events.stream().map(DeserializingMessage::getSerializedObject).toList()));
            Entity<IfModifiedPlaybackAggregate> cached =
                    fc.aggregateRepository().load("if-modified", IfModifiedPlaybackAggregate.class);
            assertEquals("value-2", cached.get().value());
            assertEquals(events.getLast().getIndex(), cached.lastEventIndex());
            assertEquals(events.getFirst().getIndex(), cached.previous().lastEventIndex());
        }).expectNoErrors();
    }

    @Test
    void publishOnlyWithoutStateChangeSkipsCacheAndSearchUpdates() throws Exception {
        TestFixture spyFixture = TestFixture.create().spy();
        spyFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("publish-only", PublishOnlyPlaybackAggregate.class)
                    .apply(new CreatePublishOnlyPlaybackAggregate());
            List<DeserializingMessage> events = fc.eventStore().getEvents("publish-only").toList();
            assertEquals(1, events.size());
            SerializedMessage createEvent = events.getFirst().getSerializedObject();
            createEvent.setSource(fc.client().id());
            invokeCachingAggregateTracker(fc, List.of(createEvent));

            Entity<PublishOnlyPlaybackAggregate> afterCreate =
                    fc.aggregateRepository().load("publish-only", PublishOnlyPlaybackAggregate.class);
            assertEquals(0L, afterCreate.sequenceNumber());
            assertEquals(createEvent.getIndex(), afterCreate.lastEventIndex());

            clearInvocations(fc.client().getSearchClient());
            delegateRepository.load("publish-only", PublishOnlyPlaybackAggregate.class)
                    .apply(new PublishOnlySideEffect());
            verify(fc.client().getSearchClient(), never()).index(anyList(), eq(Guarantee.STORED), anyBoolean());

            Entity<PublishOnlyPlaybackAggregate> afterPublishOnly =
                    fc.aggregateRepository().load("publish-only", PublishOnlyPlaybackAggregate.class);
            assertEquals(0L, afterPublishOnly.sequenceNumber());
            assertEquals(createEvent.getIndex(), afterPublishOnly.lastEventIndex());

            Entity<PublishOnlyPlaybackAggregate> latest = delegateRepository.load(
                    "publish-only", PublishOnlyPlaybackAggregate.class).apply(new SetStoredValue("value-1"));

            assertEquals(1L, latest.sequenceNumber());
            assertEquals("value-1", latest.get().storedValue());
            assertNull(latest.lastEventIndex());
            assertNotNull(latest.previous());
            assertEquals(createEvent.getMessageId(), latest.previous().lastEventId());
            assertEquals(createEvent.getIndex(), latest.previous().lastEventIndex());

            events = fc.eventStore().getEvents("publish-only").toList();
            assertEquals(2, events.size());
            SerializedMessage storedEvent = events.getLast().getSerializedObject();
            storedEvent.setSource(fc.client().id());

            clearInvocations(fc.client().getSearchClient());
            assertTrue(fc.cache().containsKey(aggregateCacheKey("publish-only")));
            invokeCachingAggregateTracker(fc, List.of(storedEvent));
            assertTrue(fc.cache().containsKey(aggregateCacheKey("publish-only")));
            verify(fc.client().getSearchClient(), never()).index(anyList(), eq(Guarantee.STORED), anyBoolean());
        }).expectNoErrors();
    }

    @Test
    void publishOnlyStateChangePublishesWithoutChangingAggregateState() throws Exception {
        TestFixture spyFixture = TestFixture.create().spy();
        spyFixture.whenExecuting(fc -> {
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load("publish-only-state", PublishOnlyPlaybackAggregate.class)
                    .apply(new CreatePublishOnlyPlaybackAggregate());

            List<DeserializingMessage> events = fc.eventStore().getEvents("publish-only-state").toList();
            assertEquals(1, events.size());
            SerializedMessage createEvent = events.getFirst().getSerializedObject();
            createEvent.setSource(fc.client().id());
            invokeCachingAggregateTracker(fc, List.of(createEvent));

            clearInvocations(fc.client().getSearchClient());
            Entity<PublishOnlyPlaybackAggregate> afterPublishOnly =
                    delegateRepository.load("publish-only-state", PublishOnlyPlaybackAggregate.class)
                            .apply(new PublishOnlySetStoredValue("transient"));
            assertNull(afterPublishOnly.get().storedValue());

            Entity<PublishOnlyPlaybackAggregate> cachedAfterPublishOnly =
                    fc.aggregateRepository().load("publish-only-state", PublishOnlyPlaybackAggregate.class);
            assertNull(cachedAfterPublishOnly.get().storedValue());
            assertEquals(0L, cachedAfterPublishOnly.sequenceNumber());
            assertEquals(createEvent.getIndex(), cachedAfterPublishOnly.lastEventIndex());
            verify(fc.client().getSearchClient(), never()).index(anyList(), eq(Guarantee.STORED), anyBoolean());
            assertEquals(1, fc.eventStore().getEvents("publish-only-state").toList().size());

            Entity<PublishOnlyPlaybackAggregate> latest = delegateRepository.load(
                    "publish-only-state", PublishOnlyPlaybackAggregate.class).apply(new SetStoredValue("value-1"));
            assertEquals(1L, latest.sequenceNumber());
            assertEquals("value-1", latest.get().storedValue());

            events = fc.eventStore().getEvents("publish-only-state").toList();
            assertEquals(2, events.size());
            SerializedMessage storedEvent = events.getLast().getSerializedObject();
            storedEvent.setSource(fc.client().id());

            assertDoesNotThrow(() -> invokeCachingAggregateTracker(fc, List.of(storedEvent)));
            Entity<PublishOnlyPlaybackAggregate> cachedLatest =
                    fc.aggregateRepository().load("publish-only-state", PublishOnlyPlaybackAggregate.class);
            assertEquals("value-1", cachedLatest.get().storedValue());
            assertEquals(storedEvent.getIndex(), cachedLatest.lastEventIndex());
            assertEquals(createEvent.getIndex(), cachedLatest.previous().lastEventIndex());
        }).expectNoErrors();
    }

    @Test
    void sideEffectFreeEntityStillAppliesPublishOnlyStateChanges() {
        testFixture.whenExecuting(fc -> {
            Entity<PublishOnlyPlaybackAggregate> after =
                    Fluxzero.asEntity(PublishOnlyPlaybackAggregate.builder().build())
                            .apply(new PublishOnlySetStoredValue("transient"));
            assertEquals("transient", after.get().storedValue());
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
            DeserializingMessage valueEvent = fc.eventStore().getEvents("sample").skip(1).findFirst().orElseThrow();
            assertEquals(valueEvent.getMessageId(), latest.lastEventId());
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
            assertRelationshipLookupCached(fc, "sample", SampleAggregate.class);

            stale.update(aggregate -> aggregate.toBuilder().auxiliaryFlag(true).build());
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
            assertRelationshipLookupInvalidated(fc, "sample");
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
            assertRelationshipLookupCached(fc, "sample", SampleAggregate.class);
            stale.update(aggregate -> aggregate.toBuilder().auxiliaryFlag(true).build());
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
            assertRelationshipLookupInvalidated(fc, "sample");
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
            assertRelationshipLookupCached(fc, "sample", SampleAggregate.class);

            storeEvent(fc, "sample", SampleAggregate.class,
                       new SetSecondFlag(), "foreign-client", 4L);

            List<DeserializingMessage> sampleEvents = fc.eventStore().getEvents("sample").toList();
            DeserializingMessage foreignEvent = sampleEvents.get(4);
            DeserializingMessage primaryValueEvent = sampleEvents.get(1);

            assertEquals("foreign-client", foreignEvent.getSerializedObject().getSource());
            invokeCachingAggregateTracker(fc, List.of(foreignEvent.getSerializedObject()));
            assertFalse(fc.cache().containsKey(aggregateCacheKey("sample")));
            assertRelationshipLookupInvalidated(fc, "sample");

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
    void foreignTrackerReplayFailureClearsRelationshipLookupCache() throws Exception {
        testFixture.whenExecuting(fc -> {
            String aggregateId = "failing-replay";
            AggregateRepository delegateRepository = getCachingDelegate(fc);
            delegateRepository.load(aggregateId, SampleAggregate.class).apply(new CreateSampleAggregate());

            DeserializingMessage createEvent = fc.eventStore().getEvents(aggregateId).findFirst().orElseThrow();
            createEvent.getSerializedObject().setSource(fc.client().id());
            invokeCachingAggregateTracker(fc, List.of(createEvent.getSerializedObject()));

            assertTrue(fc.cache().containsKey(aggregateCacheKey(aggregateId)));
            assertRelationshipLookupCached(fc, aggregateId, SampleAggregate.class);

            storeEvent(fc, aggregateId, SampleAggregate.class, new FailingPlaybackEvent(), "foreign-client", 1L);
            DeserializingMessage failingEvent = fc.eventStore().getEvents(aggregateId).skip(1).findFirst().orElseThrow();

            invokeCachingAggregateTracker(fc, List.of(failingEvent.getSerializedObject()));

            assertFalse(fc.cache().containsKey(aggregateCacheKey(aggregateId)));
            assertRelationshipLookupInvalidated(fc, aggregateId);
        }).expectNoErrors();
    }

    @Test
    void commitFailureClearsRelationshipLookupCache() throws Exception {
        TestFixture spyFixture = TestFixture.create().spy();
        spyFixture.whenExecuting(fc -> {
            String aggregateId = "commit-failure";
            assertTrue(getCachingDelegate(fc).getAggregatesFor(aggregateId).isEmpty());
            assertTrue(fc.configuration().relationshipsCache().containsKey(aggregateId));

            doReturn(CompletableFuture.failedFuture(new IllegalStateException("relationship update failed")))
                    .when(fc.client().getEventStoreClient()).updateRelationships(any());

            CompletionException error = assertThrows(CompletionException.class,
                                                     () -> getCachingDelegate(fc)
                                                             .load(aggregateId, SampleAggregate.class)
                                                             .apply(new CreateSampleAggregate()));
            assertEquals("relationship update failed", error.getCause().getMessage());

            assertFalse(fc.cache().containsKey(aggregateCacheKey(aggregateId)));
            assertRelationshipLookupInvalidated(fc, aggregateId);
            assertTrue(fc.eventStore().getEvents(aggregateId).findAny().isEmpty());
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

    private static void assertRelationshipLookupCached(Fluxzero fluxzero, String entityId,
                                                       Class<?> aggregateType) throws Exception {
        assertEquals(aggregateType, getCachingDelegate(fluxzero).getAggregatesFor(entityId).get(entityId));
        assertTrue(fluxzero.configuration().relationshipsCache().containsKey(entityId));
    }

    private static void assertRelationshipLookupInvalidated(Fluxzero fluxzero, String entityId) {
        assertFalse(fluxzero.configuration().relationshipsCache().containsKey(entityId));
    }

    private static void cacheRelationshipLookup(Fluxzero fluxzero, String entityId, String aggregateId,
                                                Class<?> aggregateType) {
        LinkedHashMap<String, Class<?>> lookup = new LinkedHashMap<>();
        lookup.put(aggregateId, aggregateType);
        fluxzero.configuration().relationshipsCache().put(entityId, lookup);
    }

    private static Method entityMethod(String methodName) throws NoSuchMethodException {
        return EntityInjectionEligibilityHandler.class.getDeclaredMethod(methodName, Entity.class);
    }

    private static void storeLegacyEvent(Fluxzero fluxzero, String aggregateId, Object payload, String source) throws Exception {
        SerializedMessage serializedMessage = new Message(payload).serialize(fluxzero.serializer());
        serializedMessage.setSource(source);
        serializedMessage.setSegment(ConsistentHashing.computeSegment(aggregateId));
        fluxzero.client().getEventStoreClient().storeEvents(aggregateId, List.of(serializedMessage), false).get();
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

    private static void storeUnknownEvent(Fluxzero fluxzero, String aggregateId, Class<?> aggregateType) throws Exception {
        SerializedMessage serializedMessage = new SerializedMessage(
                new Data<>("{}".getBytes(StandardCharsets.UTF_8),
                           aggregateType.getPackageName() + ".MissingHistoricalEvent", 0, Data.JSON_FORMAT),
                Metadata.empty(), "unknown-event", System.currentTimeMillis());
        serializedMessage.setSource("legacy-client");
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

    static class CountingEntityParameterResolver extends EntityParameterResolver {
        private int loadCount;

        @Override
        Entity<?> loadAggregate(String aggregateId, Class<?> aggregateType) {
            loadCount++;
            return super.loadAggregate(aggregateId, aggregateType);
        }
    }

    static class IgnoringUnknownParameterHandler {
        final AtomicReference<IgnoringUnknownAggregate> observed = new AtomicReference<>();

        @HandleEvent
        void handle(CreateIgnoringUnknownAggregate event, IgnoringUnknownAggregate aggregate) {
            observed.set(aggregate);
        }
    }

    static class IncompatibleErrorHandler {
        @HandleError
        void handle(SetPrimaryValue event) {
        }
    }

    static class CommandEntityHandler {
        @HandleCommand
        void handle(Entity<SampleAggregate> entity) {
        }
    }

    static class EntityInjectionEligibilityHandler {
        @HandleEvent
        void handleEvent(Entity<SampleAggregate> entity) {
        }

        @HandleNotification
        void handleNotification(Entity<SampleAggregate> entity) {
        }

        @HandleCommand
        void handleCommand(Entity<SampleAggregate> entity) {
        }

        @HandleError
        void handleError(Entity<SampleAggregate> entity) {
        }

        void validate(Entity<SampleAggregate> entity) {
        }
    }

    static class BaseCommandEntityHandler {
        @HandleCommand
        void handle(Entity<SampleAggregate> entity) {
        }
    }

    static class OverridingCommandEntityHandler extends BaseCommandEntityHandler {
        @Override
        void handle(Entity<SampleAggregate> entity) {
        }
    }

    record CreateIgnoringUnknownAggregate(String value) {
        @Apply
        IgnoringUnknownAggregate apply() {
            return new IgnoringUnknownAggregate(value);
        }
    }

    @Aggregate(ignoreUnknownEvents = true)
    record IgnoringUnknownAggregate(String value) {
    }

    record CreateStrictUnknownAggregate(String value) {
        @Apply
        StrictUnknownAggregate apply() {
            return new StrictUnknownAggregate(value);
        }
    }

    @Aggregate
    record StrictUnknownAggregate(String value) {
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

    record FailingPlaybackEvent() {
        @Apply
        SampleAggregate apply(SampleAggregate aggregate) {
            throw new IllegalStateException("failing playback");
        }
    }

    @Aggregate
    @Builder(toBuilder = true)
    record SampleAggregate(String primaryValue, boolean firstFlag,
                           boolean secondFlag, boolean auxiliaryFlag) {
    }

    record CreateIfModifiedPlaybackAggregate(String value) {
        @Apply
        IfModifiedPlaybackAggregate apply() {
            return IfModifiedPlaybackAggregate.builder().value(value).build();
        }
    }

    record SetIfModifiedPlaybackValue(String value) {
        @Apply
        IfModifiedPlaybackAggregate apply(IfModifiedPlaybackAggregate aggregate) {
            return aggregate.toBuilder().value(value).build();
        }
    }

    @Aggregate(eventPublication = EventPublication.IF_MODIFIED)
    @Builder(toBuilder = true)
    record IfModifiedPlaybackAggregate(String value) {
    }

    record CreatePublishOnlyPlaybackAggregate() {
        @Apply
        PublishOnlyPlaybackAggregate apply() {
            return PublishOnlyPlaybackAggregate.builder().build();
        }
    }

    record PublishOnlySideEffect() {
        @Apply(eventPublication = EventPublication.ALWAYS, publicationStrategy = EventPublicationStrategy.PUBLISH_ONLY)
        void publish(PublishOnlyPlaybackAggregate aggregate) {
        }
    }

    record SetStoredValue(String value) {
        @Apply
        PublishOnlyPlaybackAggregate apply(PublishOnlyPlaybackAggregate aggregate) {
            return aggregate.toBuilder().storedValue(value).build();
        }
    }

    record PublishOnlySetStoredValue(String value) {
        @Apply(publicationStrategy = EventPublicationStrategy.PUBLISH_ONLY)
        PublishOnlyPlaybackAggregate apply(PublishOnlyPlaybackAggregate aggregate) {
            return aggregate.toBuilder().storedValue(value).build();
        }
    }

    @Aggregate(searchable = true)
    @Builder(toBuilder = true)
    record PublishOnlyPlaybackAggregate(String storedValue) {
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
