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

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Invocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateRepositoryNamespaceTest {

    private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void loadsAndCommitsAggregateWithinSelectedNamespaceWithAutomaticCaching() {
        verifyNamespaceIsolation(TestFixture.create().spy());
    }

    @Test
    void loadsAndCommitsAggregateWithinSelectedNamespaceWithoutAutomaticCaching() {
        verifyNamespaceIsolation(TestFixture.create(DefaultFluxzero.builder().disableAutomaticAggregateCaching()));
    }

    @Test
    void tracksCachedAggregateWithinSelectedNamespace() throws Throwable {
        var fixture = TestFixture.create();
        var fluxzero = fixture.getFluxzero();
        AggregateRepository repository = fluxzero.aggregateRepository().forNamespace("first");

        assertFalse(trackerStarted(repository));
        Invocation.performInvocation(() -> {
            repository.load("shared", NamespacedAggregate.class)
                    .assertAndApply(new SetValue("shared", "initial value"));
            return null;
        });
        assertTrue(trackerStarted(repository));

        storeForeignEvent(fluxzero, "first", new ChangeValue("shared", "external value"), 1L);

        assertEventually(() -> assertEquals(
                "external value", repository.load("shared", NamespacedAggregate.class).get().value()));
    }

    @Test
    void doesNotStartTrackerWhenCustomNamespaceAggregateIsNotCached() throws Exception {
        AggregateRepository repository = TestFixture.create().getFluxzero()
                .aggregateRepository().forNamespace("first");

        repository.load("uncached", UncachedAggregate.class);

        assertFalse(trackerStarted(repository));
    }

    @Test
    void startsTrackerWhenCustomNamespaceRelationshipCacheIsPopulated() throws Exception {
        AggregateRepository repository = TestFixture.create().getFluxzero()
                .aggregateRepository().forNamespace("first");

        repository.getAggregatesFor("entity");

        assertTrue(trackerStarted(repository));
    }

    @Test
    void namespaceViewsReturnCanonicalApplicationRepository() {
        var fluxzero = TestFixture.create().getFluxzero();
        AggregateRepository applicationRepository = fluxzero.aggregateRepository();
        AggregateRepository firstRepository = applicationRepository.forNamespace("first");

        assertSame(applicationRepository, firstRepository.forNamespace(null));
        assertSame(applicationRepository, firstRepository.forNamespace(fluxzero.client().namespace()));
    }

    private void verifyNamespaceIsolation(TestFixture fixture) {
        var fluxzero = fixture.getFluxzero();
        AggregateRepository defaultRepository = fluxzero.aggregateRepository();
        AggregateRepository firstRepository = defaultRepository.forNamespace("first");
        AggregateRepository secondRepository = defaultRepository.forNamespace("second");

        assertSame(firstRepository, firstRepository.forNamespace("first"));

        Invocation.performInvocation(() -> {
            firstRepository.load("shared", NamespacedAggregate.class)
                    .assertAndApply(new SetValue("shared", "first value"));
            secondRepository.load("shared", NamespacedAggregate.class)
                    .assertAndApply(new SetValue("shared", "second value"));
            return null;
        });

        assertEquals("first value", fluxzero.snapshotStore().forNamespace("first")
                .<NamespacedAggregate>getSnapshot("shared").orElseThrow().get().value());
        assertEquals("second value", fluxzero.snapshotStore().forNamespace("second")
                .<NamespacedAggregate>getSnapshot("shared").orElseThrow().get().value());
        assertTrue(fluxzero.snapshotStore().getSnapshot("shared").isEmpty());

        fluxzero.cache().clear();

        assertEquals("first value", firstRepository.load("shared", NamespacedAggregate.class).get().value());
        assertEquals("second value", secondRepository.load("shared", NamespacedAggregate.class).get().value());
        assertTrue(defaultRepository.load("shared", NamespacedAggregate.class).isEmpty());
    }

    private static void storeForeignEvent(Fluxzero fluxzero, String namespace, ChangeValue event,
                                          long sequenceNumber) throws Exception {
        String aggregateId = event.aggregateId();
        SerializedMessage serializedMessage = new Message(event, Metadata.of(
                Entity.AGGREGATE_ID_METADATA_KEY, aggregateId,
                Entity.AGGREGATE_TYPE_METADATA_KEY, NamespacedAggregate.class.getName(),
                Entity.AGGREGATE_SN_METADATA_KEY, Long.toString(sequenceNumber)))
                .serialize(fluxzero.serializer());
        serializedMessage.setSource("foreign-client");
        serializedMessage.setSegment(ConsistentHashing.computeSegment(aggregateId));
        fluxzero.client().forNamespace(namespace).getEventStoreClient()
                .storeEvents(aggregateId, List.of(serializedMessage), false).get();
    }

    private static void assertEventually(Executable assertion) throws Throwable {
        AssertionError lastError = null;
        long deadline = System.nanoTime() + EVENTUALLY_TIMEOUT.toNanos();
        do {
            try {
                assertion.execute();
                return;
            } catch (AssertionError e) {
                lastError = e;
                Thread.sleep(10);
            }
        } while (System.nanoTime() < deadline);
        throw lastError;
    }

    private static boolean trackerStarted(AggregateRepository repository) throws ReflectiveOperationException {
        Field started = CachingAggregateRepository.class.getDeclaredField("started");
        started.setAccessible(true);
        return ((AtomicBoolean) started.get(repository)).get();
    }

    record SetValue(String aggregateId, String value) {
    }

    record ChangeValue(String aggregateId, String value) {
    }

    @Aggregate(cached = false)
    record UncachedAggregate(@EntityId String aggregateId) {
    }

    @Aggregate(snapshotPeriod = 1)
    record NamespacedAggregate(@EntityId String aggregateId, String value) {

        @Apply
        static NamespacedAggregate apply(SetValue event) {
            return new NamespacedAggregate(event.aggregateId(), event.value());
        }

        @Apply
        NamespacedAggregate apply(ChangeValue event) {
            return new NamespacedAggregate(aggregateId, event.value());
        }
    }
}
