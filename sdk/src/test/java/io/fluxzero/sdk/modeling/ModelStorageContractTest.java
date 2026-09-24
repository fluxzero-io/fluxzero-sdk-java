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
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies annotation settings through real commits and cold loads, not only reflected metadata. */
class ModelStorageContractTest {
    static Stream<Arguments> storageModes() {
        return Stream.of(DefaultCounter.class, LatestCounter.class, HistoryCounter.class, UncachedCounter.class,
                         SnapshotCounter.class, ClampedSnapshotCounter.class, CheckpointCounter.class,
                         DocumentCounter.class, CombinedCounter.class).flatMap(type ->
                Stream.of(false, true).map(async -> Arguments.of(type, async)));
    }

    @ParameterizedTest
    @MethodSource("storageModes")
    void createUpdateDeleteRecreateSurvivesColdLoads(Class<? extends Counter<?>> type, boolean async) {
        fixture(type, async)
                .givenCommands(new Create("counter"), new Add("counter", 2))
                .whenExecuting(fc -> {
                    assertValue(type, 3);
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("counter"));
                    assertValue(type, 3);
                    assertEquals(2, fc.eventStore().getEvents("counter").toList().size());
                    assertEquals(type.getAnnotation(Model.class).persistence().length == 2
                                    || type == DocumentCounter.class ? 1 : 0,
                            Fluxzero.search(type).fetchAll().size());
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenCommand(new Delete("counter")).expectSuccessfulResult().expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("counter"));
                    assertNull(Fluxzero.loadModel("counter", type).get());
                    assertTrue(Fluxzero.search(type).fetchAll().isEmpty());
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenCommand(new Create("counter")).expectSuccessfulResult().expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("counter"));
                    assertValue(type, 1);
                    assertEquals(4, fc.eventStore().getEvents("counter").toList().size());
                }).expectSuccessfulResult().expectNoErrors();
    }

    static Stream<Arguments> snapshotModes() {
        return Stream.of(false, true).flatMap(async -> Stream.of(
                Arguments.of(DefaultCounter.class, async, 0L),
                Arguments.of(SnapshotCounter.class, async, 2L),
                Arguments.of(ClampedSnapshotCounter.class, async, 1L)));
    }

    @ParameterizedTest
    @MethodSource("snapshotModes")
    void snapshotPeriodAndRetentionApplyToActualStorage(
            Class<? extends Counter<?>> type, boolean async, long retained) {
        AtomicLong boundary = new AtomicLong();
        fixture(type, async).givenCommands(new Create("counter"), new Add("counter", 1))
                .whenExecuting(fc -> boundary.set(fc.modelRepository()
                        .loadGraph("counter", type, Graph.Options.DEFAULT).revisionStateIndex()))
                .expectSuccessfulResult().andThen().givenCommands(new Add("counter", 1), new Add("counter", 1),
                        new Add("counter", 1), new Add("counter", 1))
                .whenExecuting(fc -> {
                    assertEquals(retained, fc.documentStore().search("$modelSnapshots").count());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("counter"));
                    assertValue(type, 6);
                    assertEquals(6, fc.eventStore().getEvents("counter").toList().size(),
                            "Snapshot retention must not truncate Model event history");
                    assertEquals(2, fc.modelRepository().loadGraphAt("counter", type,
                            boundary.get(), Graph.Options.DEFAULT).get().value());
                }).expectSuccessfulResult().expectNoErrors();
    }

    static Stream<Arguments> cacheDepths() {
        return Stream.of(false, true).flatMap(async -> Stream.of(
                Arguments.of(DefaultCounter.class, async, 1),
                Arguments.of(LatestCounter.class, async, 0),
                Arguments.of(HistoryCounter.class, async, 3)));
    }

    @ParameterizedTest
    @MethodSource("cacheDepths")
    void cacheDepthDoesNotLimitDurableHistory(Class<? extends Counter<?>> type, boolean async, int depth) {
        AtomicLong boundary = new AtomicLong();
        fixture(type, async).givenCommands(new Create("counter"))
                .whenExecuting(fc -> boundary.set(fc.modelRepository()
                        .loadGraph("counter", type, Graph.Options.DEFAULT).revisionStateIndex()))
                .expectSuccessfulResult().andThen().givenCommands(
                        new Add("counter", 1), new Add("counter", 1), new Add("counter", 1))
                .whenExecuting(fc -> {
                    Entity<?> current = Fluxzero.loadModel("counter", type);
                    for (int i = 0; i <= depth; i++) {
                        assertNotNull(current);
                        assertEquals(4 - i, ((Counter<?>) current.get()).value());
                        current = current.previous();
                    }
                    assertNull(current);
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("counter"));
                    assertEquals(1, fc.modelRepository().loadGraphAt("counter", type,
                            boundary.get(), Graph.Options.DEFAULT).get().value());
                }).expectSuccessfulResult().expectNoErrors();
    }

    private static TestFixture fixture(Class<?> type, boolean async) {
        return async ? TestFixture.createAsync(type) : TestFixture.create(type);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentTimePathsFollowStateOnCreateAndUpdate(boolean async) {
        Instant start = Instant.parse("2020-01-01T00:00:00Z");
        Instant end = start.plusSeconds(3600);
        fixture(TimedDocument.class, async)
                .givenCommands(new CreateTimedDocument("window", new Window(start, end)))
                .whenExecuting(fc -> {
                    var stored = fc.client().getSearchClient()
                            .fetch(new GetDocument("window", "contract-windows")).orElseThrow();
                    assertEquals(start.toEpochMilli(), stored.getTimestamp());
                    assertEquals(end.toEpochMilli(), stored.getEnd());
                    assertEquals(List.of(new TimedDocument("window", new Window(start, end))),
                                 Fluxzero.search(TimedDocument.class).fetchAll());
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenCommand(new ChangeWindow("window", new Window(end, end.plusSeconds(7200))))
                .expectSuccessfulResult().expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    var stored = fc.client().getSearchClient()
                            .fetch(new GetDocument("window", "contract-windows")).orElseThrow();
                    assertEquals(end.toEpochMilli(), stored.getTimestamp());
                    assertEquals(end.plusSeconds(7200).toEpochMilli(), stored.getEnd());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("window"));
                    assertEquals(new Window(end, end.plusSeconds(7200)),
                                 Fluxzero.loadModel("window", TimedDocument.class).get().window());
                }).expectSuccessfulResult().expectNoErrors();
    }

    record Window(Instant start, Instant end) {}
    record CreateTimedDocument(String id, Window window) {}
    record ChangeWindow(String id, Window window) {}

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT},
            document = @DocumentProjection(collection = "contract-windows",
                    timestampPath = "window/start", endPath = "window/end"))
    record TimedDocument(@EntityId String id, Window window) {
        @Apply
        static TimedDocument create(CreateTimedDocument event) {
            return new TimedDocument(event.id(), event.window());
        }

        @Apply
        TimedDocument apply(ChangeWindow event) {
            return new TimedDocument(id, event.window());
        }
    }

    private static void assertValue(Class<? extends Counter<?>> type, int expected) {
        assertEquals(expected, Fluxzero.loadModel("counter", type).get().value());
    }

    interface Counter<T> {
        String id();
        int value();
        T withValue(int value);

        @Apply
        default T apply(Add update) { return withValue(value() + update.amount()); }

        @Apply
        default T apply(Delete update) { return null; }
    }

    record Create(String id) {}
    record Add(String id, int amount) {}
    record Delete(String id) {}

    @Model
    record DefaultCounter(@EntityId String id, int value) implements Counter<DefaultCounter> {
        @Apply static DefaultCounter create(Create event) { return new DefaultCounter(event.id(), 1); }
        public DefaultCounter withValue(int value) { return new DefaultCounter(id, value); }
    }

    @Model(cachingDepth = 0)
    record LatestCounter(@EntityId String id, int value) implements Counter<LatestCounter> {
        @Apply static LatestCounter create(Create event) { return new LatestCounter(event.id(), 1); }
        public LatestCounter withValue(int value) { return new LatestCounter(id, value); }
    }

    @Model(cachingDepth = -1)
    record HistoryCounter(@EntityId String id, int value) implements Counter<HistoryCounter> {
        @Apply static HistoryCounter create(Create event) { return new HistoryCounter(event.id(), 1); }
        public HistoryCounter withValue(int value) { return new HistoryCounter(id, value); }
    }

    @Model(cached = false)
    record UncachedCounter(@EntityId String id, int value) implements Counter<UncachedCounter> {
        @Apply static UncachedCounter create(Create event) { return new UncachedCounter(event.id(), 1); }
        public UncachedCounter withValue(int value) { return new UncachedCounter(id, value); }
    }

    @Model(snapshotPeriod = 2, maxSnapshotCount = 2, cached = false)
    record SnapshotCounter(@EntityId String id, int value) implements Counter<SnapshotCounter> {
        @Apply static SnapshotCounter create(Create event) { return new SnapshotCounter(event.id(), 1); }
        public SnapshotCounter withValue(int value) { return new SnapshotCounter(id, value); }
    }

    @Model(snapshotPeriod = 2, maxSnapshotCount = 0, cached = false)
    record ClampedSnapshotCounter(@EntityId String id, int value) implements Counter<ClampedSnapshotCounter> {
        @Apply static ClampedSnapshotCounter create(Create event) { return new ClampedSnapshotCounter(event.id(), 1); }
        public ClampedSnapshotCounter withValue(int value) { return new ClampedSnapshotCounter(id, value); }
    }

    @Model(checkpointPeriod = 1, cached = false)
    record CheckpointCounter(@EntityId String id, int value) implements Counter<CheckpointCounter> {
        @Apply static CheckpointCounter create(Create event) { return new CheckpointCounter(event.id(), 1); }
        public CheckpointCounter withValue(int value) { return new CheckpointCounter(id, value); }
    }

    @Model(persistence = ModelPersistence.DOCUMENT)
    record DocumentCounter(@EntityId String id, int value) implements Counter<DocumentCounter> {
        @Apply static DocumentCounter create(Create event) { return new DocumentCounter(event.id(), 1); }
        public DocumentCounter withValue(int value) { return new DocumentCounter(id, value); }
    }

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT})
    record CombinedCounter(@EntityId String id, int value) implements Counter<CombinedCounter> {
        @Apply static CombinedCounter create(Create event) { return new CombinedCounter(event.id(), 1); }
        public CombinedCounter withValue(int value) { return new CombinedCounter(id, value); }
    }

}
