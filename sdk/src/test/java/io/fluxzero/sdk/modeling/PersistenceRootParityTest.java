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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersistenceRootParityTest {

    @ParameterizedTest
    @EnumSource(PersistenceRoot.class)
    void eventSourcedLifecycleSearchAndPreviousRevisionAreEquivalent(
            PersistenceRoot root) {
        TestFixture fixture = TestFixture.create();
        String functionalId = "event-sourced";

        fixture.whenExecuting(
                        fluxzero -> root.create(
                                functionalId, "first"))
                .expectThat(fluxzero -> {
                    assertEquals("first", root.load(functionalId));
                    assertEquals(
                            List.of("first"),
                            root.searchValues());
                    assertEquals(
                            1L, root.eventCount(
                                    fluxzero, functionalId));
                })
                .andThen()
                .whenExecuting(
                        fluxzero -> root.update(
                                functionalId, "second"))
                .expectThat(fluxzero -> {
                    Entity<?> current =
                            root.loadEntity(functionalId);
                    assertEquals("second", root.value(current));
                    assertEquals(
                            "first",
                            root.value(current.previous()));
                    assertEquals(
                            List.of("second"),
                            root.searchValues());
                    assertEquals(
                            2L, root.eventCount(
                                    fluxzero, functionalId));
                })
                .andThen()
                .whenExecuting(
                        fluxzero -> root.delete(functionalId))
                .expectThat(fluxzero -> {
                    assertNull(root.load(functionalId));
                    assertEquals(List.of(), root.searchValues());
                    assertEquals(
                            3L, root.eventCount(
                                    fluxzero, functionalId));
                })
                .andThen()
                .whenExecuting(
                        fluxzero -> root.create(
                                functionalId, "recreated"))
                .expectThat(fluxzero -> {
                    assertEquals(
                            "recreated", root.load(functionalId));
                    assertEquals(
                            List.of("recreated"),
                            root.searchValues());
                    assertEquals(
                            4L, root.eventCount(
                                    fluxzero, functionalId));
                });
    }

    @ParameterizedTest
    @EnumSource(PersistenceRoot.class)
    void documentBasedLoadingStillStoresAppliedEvents(
            PersistenceRoot root) {
        TestFixture fixture = TestFixture.create();
        String functionalId = "document";

        fixture.whenExecuting(
                        fluxzero -> root.createDocument(
                                functionalId, "first"))
                .expectEvents(root.documentCreateEventType())
                .andThen()
                .whenExecuting(
                        fluxzero -> root.updateDocument(
                                functionalId, "second"))
                .expectEvents(root.documentUpdateEventType())
                .expectThat(fluxzero -> {
                    assertEquals(
                            "second",
                            root.loadDocument(functionalId));
                    assertEquals(
                            List.of("second"),
                            root.searchDocumentValues());
                });
    }

    @ParameterizedTest
    @EnumSource(PersistenceRoot.class)
    void publicationNeverStillCommitsDirectDocument(
            PersistenceRoot root) {
        TestFixture fixture = TestFixture.create();
        String functionalId = "without-event";

        fixture.whenExecuting(
                        fluxzero -> root.createWithoutEvent(
                                functionalId, "current"))
                .expectNoEvents()
                .expectThat(fluxzero -> {
                    assertEquals(
                            "current",
                            root.loadWithoutEvent(functionalId));
                    assertEquals(
                            List.of("current"),
                            root.searchWithoutEventValues());
                    assertEquals(
                            0L, root.eventCount(
                                    fluxzero, functionalId));
                });
    }

    private enum PersistenceRoot {
        AGGREGATE {
            @Override
            void create(String id, String value) {
                Fluxzero.loadAggregate(id, ParityAggregate.class)
                        .apply(new CreateAggregate(id, value));
            }

            @Override
            void update(String id, String value) {
                Fluxzero.loadAggregate(id, ParityAggregate.class)
                        .apply(new UpdateAggregate(value));
            }

            @Override
            void delete(String id) {
                Fluxzero.loadAggregate(id, ParityAggregate.class)
                        .apply(new DeleteAggregate());
            }

            @Override
            Entity<?> loadEntity(String id) {
                return Fluxzero.loadAggregate(
                        id, ParityAggregate.class);
            }

            @Override
            String value(Entity<?> entity) {
                return entity == null || entity.get() == null
                        ? null
                        : ((ParityAggregate) entity.get()).value();
            }

            @Override
            List<String> searchValues() {
                return Fluxzero.search(ParityAggregate.class)
                        .fetchAll(ParityAggregate.class).stream()
                        .map(ParityAggregate::value).toList();
            }

            @Override
            void createDocument(String id, String value) {
                Fluxzero.loadAggregate(
                                id, DocumentAggregate.class)
                        .apply(new CreateDocumentAggregate(
                                id, value));
            }

            @Override
            void updateDocument(String id, String value) {
                Fluxzero.loadAggregate(
                                id, DocumentAggregate.class)
                        .apply(new UpdateDocumentAggregate(value));
            }

            @Override
            String loadDocument(String id) {
                DocumentAggregate value = Fluxzero.loadAggregate(
                        id, DocumentAggregate.class).get();
                return value == null ? null : value.value();
            }

            @Override
            List<String> searchDocumentValues() {
                return Fluxzero.search(DocumentAggregate.class)
                        .fetchAll(DocumentAggregate.class).stream()
                        .map(DocumentAggregate::value).toList();
            }

            @Override
            Class<?> documentCreateEventType() {
                return CreateDocumentAggregate.class;
            }

            @Override
            Class<?> documentUpdateEventType() {
                return UpdateDocumentAggregate.class;
            }

            @Override
            void createWithoutEvent(String id, String value) {
                Fluxzero.loadAggregate(
                                id, SilentAggregate.class)
                        .apply(new CreateSilentAggregate(
                                id, value));
            }

            @Override
            String loadWithoutEvent(String id) {
                SilentAggregate value = Fluxzero.loadAggregate(
                        id, SilentAggregate.class).get();
                return value == null ? null : value.value();
            }

            @Override
            List<String> searchWithoutEventValues() {
                return Fluxzero.search(SilentAggregate.class)
                        .fetchAll(SilentAggregate.class).stream()
                        .map(SilentAggregate::value).toList();
            }

            @Override
            String storageId(String functionalId) {
                return functionalId;
            }
        },
        MODEL {
            @Override
            void create(String id, String value) {
                Fluxzero.assertAndApply(
                        new CreateParityModel(
                                new ParityModelId(id), value));
            }

            @Override
            void update(String id, String value) {
                Fluxzero.assertAndApply(
                        new UpdateParityModel(
                                new ParityModelId(id), value));
            }

            @Override
            void delete(String id) {
                Fluxzero.assertAndApply(
                        new DeleteParityModel(
                                new ParityModelId(id)));
            }

            @Override
            Entity<?> loadEntity(String id) {
                return Fluxzero.loadModel(new ParityModelId(id));
            }

            @Override
            String value(Entity<?> entity) {
                return entity == null || entity.get() == null
                        ? null
                        : ((ParityModel) entity.get()).value();
            }

            @Override
            List<String> searchValues() {
                return Fluxzero.search(ParityModel.class)
                        .fetchAll(ParityModel.class).stream()
                        .map(ParityModel::value).toList();
            }

            @Override
            void createDocument(String id, String value) {
                Fluxzero.assertAndApply(
                        new CreateDocumentModel(
                                new DocumentModelId(id), value));
            }

            @Override
            void updateDocument(String id, String value) {
                Fluxzero.assertAndApply(
                        new UpdateDocumentModel(
                                new DocumentModelId(id), value));
            }

            @Override
            String loadDocument(String id) {
                DocumentModel value = Fluxzero.loadModel(
                        new DocumentModelId(id)).get();
                return value == null ? null : value.value();
            }

            @Override
            List<String> searchDocumentValues() {
                return Fluxzero.search(DocumentModel.class)
                        .fetchAll(DocumentModel.class).stream()
                        .map(DocumentModel::value).toList();
            }

            @Override
            Class<?> documentCreateEventType() {
                return CreateDocumentModel.class;
            }

            @Override
            Class<?> documentUpdateEventType() {
                return UpdateDocumentModel.class;
            }

            @Override
            void createWithoutEvent(String id, String value) {
                Fluxzero.assertAndApply(
                        new CreateSilentModel(
                                new SilentModelId(id), value));
            }

            @Override
            String loadWithoutEvent(String id) {
                SilentModel value = Fluxzero.loadModel(
                        new SilentModelId(id)).get();
                return value == null ? null : value.value();
            }

            @Override
            List<String> searchWithoutEventValues() {
                return Fluxzero.search(SilentModel.class)
                        .fetchAll(SilentModel.class).stream()
                        .map(SilentModel::value).toList();
            }

            @Override
            String storageId(String functionalId) {
                return new ParityModelId(
                        functionalId).toString();
            }
        };

        abstract void create(String id, String value);

        abstract void update(String id, String value);

        abstract void delete(String id);

        abstract Entity<?> loadEntity(String id);

        abstract String value(Entity<?> entity);

        abstract List<String> searchValues();

        abstract void createDocument(String id, String value);

        abstract void updateDocument(String id, String value);

        abstract String loadDocument(String id);

        abstract List<String> searchDocumentValues();

        abstract Class<?> documentCreateEventType();

        abstract Class<?> documentUpdateEventType();

        abstract void createWithoutEvent(
                String id, String value);

        abstract String loadWithoutEvent(String id);

        abstract List<String> searchWithoutEventValues();

        abstract String storageId(String functionalId);

        String load(String id) {
            return value(loadEntity(id));
        }

        long eventCount(
                io.fluxzero.sdk.Fluxzero fluxzero,
                String functionalId) {
            return fluxzero.eventStore()
                    .getEvents(storageId(functionalId)).count();
        }
    }

    @Aggregate(
            searchable = true, cachingDepth = 1,
            snapshotPeriod = 2)
    private record ParityAggregate(
            @EntityId String id, String value) {
    }

    private record CreateAggregate(String id, String value) {
        @Apply
        ParityAggregate apply() {
            return new ParityAggregate(id, value);
        }
    }

    private record UpdateAggregate(String value) {
        @Apply
        ParityAggregate apply(ParityAggregate current) {
            return new ParityAggregate(current.id(), value);
        }
    }

    private record DeleteAggregate() {
        @Apply
        ParityAggregate apply(ParityAggregate current) {
            return null;
        }
    }

    @Model(
            searchable = true, cachingDepth = 1,
            snapshotPeriod = 2)
    private record ParityModel(
            @EntityId ParityModelId id, String value) {
    }

    private static final class ParityModelId
            extends Id<ParityModel> {
        private ParityModelId(String id) {
            super(id, "parity-model-");
        }
    }

    private record CreateParityModel(
            ParityModelId id, String value) {
        @Apply
        ParityModel apply() {
            return new ParityModel(id, value);
        }
    }

    private record UpdateParityModel(
            ParityModelId id, String value) {
        @Apply
        ParityModel apply(ParityModel current) {
            return new ParityModel(id, value);
        }
    }

    private record DeleteParityModel(ParityModelId id) {
        @Apply
        ParityModel apply(ParityModel current) {
            return null;
        }
    }

    @Aggregate(eventSourced = false, searchable = true)
    private record DocumentAggregate(
            @EntityId String id, String value) {
    }

    private record CreateDocumentAggregate(
            String id, String value) {
        @Apply
        DocumentAggregate apply() {
            return new DocumentAggregate(id, value);
        }
    }

    private record UpdateDocumentAggregate(String value) {
        @Apply
        DocumentAggregate apply(DocumentAggregate current) {
            return new DocumentAggregate(
                    current.id(), value);
        }
    }

    @Model(eventSourced = false, searchable = true)
    private record DocumentModel(
            @EntityId DocumentModelId id, String value) {
    }

    private static final class DocumentModelId
            extends Id<DocumentModel> {
        private DocumentModelId(String id) {
            super(id, "document-model-");
        }
    }

    private record CreateDocumentModel(
            DocumentModelId id, String value) {
        @Apply
        DocumentModel apply() {
            return new DocumentModel(id, value);
        }
    }

    private record UpdateDocumentModel(
            DocumentModelId id, String value) {
        @Apply
        DocumentModel apply(DocumentModel current) {
            return new DocumentModel(id, value);
        }
    }

    @Aggregate(
            eventSourced = false, searchable = true,
            eventPublication = EventPublication.NEVER)
    private record SilentAggregate(
            @EntityId String id, String value) {
    }

    private record CreateSilentAggregate(
            String id, String value) {
        @Apply
        SilentAggregate apply() {
            return new SilentAggregate(id, value);
        }
    }

    @Model(
            eventSourced = false, searchable = true,
            eventPublication = EventPublication.NEVER)
    private record SilentModel(
            @EntityId SilentModelId id, String value) {
    }

    private static final class SilentModelId
            extends Id<SilentModel> {
        private SilentModelId(String id) {
            super(id, "silent-model-");
        }
    }

    private record CreateSilentModel(
            SilentModelId id, String value) {
        @Apply
        SilentModel apply() {
            return new SilentModel(id, value);
        }
    }
}
