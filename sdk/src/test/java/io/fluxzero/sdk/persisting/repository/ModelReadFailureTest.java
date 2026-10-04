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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.TypeInspection;
import io.fluxzero.sdk.common.serialization.UnknownSerializedTypeException;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModelReadFailureTest {
    private static final IllegalStateException APPLICATION_FAILURE = new IllegalStateException("application failure");

    @Test
    void unknownTypeStillHonorsExistingDeserializationExceptionContract() {
        var serializer = new JacksonSerializer();
        var failure = assertThrows(DeserializationException.class,
                () -> serializer.deserialize(new Data<>(new byte[]{1}, "foreign.event", 17, "application/json")));
        assertInstanceOf(UnknownSerializedTypeException.class, failure);
        assertEquals(17, ((UnknownSerializedTypeException) failure).getRevision());
    }

    @Test
    void missingModelAndBrokenCatalogHaveDifferentActionableContext() {
        var cursor = new ModelReplayCursor(org.mockito.Mockito.mock(io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient.class),
                new JacksonSerializer(), null, null, null, null, null, null, ModelReplayCursor.EventBoundaryBarrier.NONE,
                new ModelTypeResolver() {
                    public String modelName(Class<?> type) { return "known"; }
                    public Class<?> modelType(String type, String id) { throw APPLICATION_FAILURE; }
                    public Optional<Class<?>> knownModelType(String type, String id) {
                        if ("broken".equals(type)) { throw APPLICATION_FAILURE; }
                        return Optional.empty();
                    }
                });
        var missing = assertThrows(ModelReadException.class, () -> cursor.modelType("foreign", "model-id"));
        assertEquals(ModelReadException.Kind.MISSING_MODEL_CONTRACT, missing.getKind());
        assertEquals("foreign", missing.getContext().modelType());
        assertEquals("model-id", missing.getContext().modelId());
        assertSame(APPLICATION_FAILURE, missing.getCause());
        assertTrue(missing.getMessage().contains(ModelReadException.DOCUMENTATION));
        var broken = assertThrows(ModelReadException.class, () -> cursor.modelType("broken", "model-id"));
        assertEquals(ModelReadException.Kind.CATALOG_FAILURE, broken.getKind());
    }

    @Test
    void diagnosticCatalogProbePreservesOriginalLinkageFailure() {
        var original = new NoClassDefFoundError("missing contract dependency");
        var resolver = new ModelTypeResolver() {
            public String modelName(Class<?> type) { return "known"; }
            public Class<?> modelType(String type, String id) { throw original; }
        };
        var cursor = new ModelReplayCursor(org.mockito.Mockito.mock(io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient.class),
                new JacksonSerializer(), null, null, null, null, null, null, ModelReplayCursor.EventBoundaryBarrier.NONE,
                resolver);
        var failure = assertThrows(ModelReadException.class, () -> cursor.modelType("known", "model"));
        assertEquals(ModelReadException.Kind.CATALOG_FAILURE, failure.getKind());
        assertSame(original, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fixtureCanQualifyColdStoredReplaySeparatelyFromCatalogInspection(boolean async) {
        var builder = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook();
        TestFixture fixture = async ? TestFixture.createAsync(builder) : TestFixture.create(builder);
        fixture.given(app -> store(app, new Message(new Created("cold", false)).serialize(app.serializer())))
                .whenApplying(app -> app.modelRepository().load("cold", State.class).get())
                .expectResult(new State("cold"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsMissingSerializedContractsAndCorruptSyntaxWithoutPayloadLeak(boolean corrupt) {
        String type = corrupt ? Created.class.getName() : "missing.writer.event";
        Data<byte[]> data = new Data<>((corrupt ? "{ payload-secret" : "{}").getBytes(StandardCharsets.UTF_8),
                                       type, 7, "application/json");
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance(null))) {
            store(app, new SerializedMessage(data, Metadata.of("secret", "metadata-secret"), "event", 0L));
            var failure = assertThrows(ModelReadException.class, () -> app.modelRepository().load("cold", State.class));
            assertEquals(corrupt ? ModelReadException.Kind.INVALID_DATA : ModelReadException.Kind.MISSING_SERIALIZED_TYPE,
                         failure.getKind());
            assertEquals("cold", failure.getContext().modelId());
            assertEquals(type, failure.getContext().serializedType());
            assertEquals(7, failure.getContext().serializedRevision());
            assertEquals(State.class.getName(), failure.getContext().javaModelType());
            assertEquals(corrupt ? TypeInspection.Status.KNOWN : TypeInspection.Status.UNKNOWN,
                         failure.getContext().registration().status());
            assertNotNull(failure.getCause());
            assertNotNull(failure.getContext().stateIndex());
            assertFalse(failure.getMessage().contains("payload-secret"));
            assertFalse(failure.getMessage().contains("metadata-secret"));
        }
    }

    @Test
    void knownTypesDoNotGuaranteeWorkingApplicationReplay() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance(null))) {
            store(app, new Message(new Created("cold", true)).serialize(app.serializer()));
            var report = app.modelRepository().diagnostics().inspectStreams(List.of("cold"), ModelReadBoundary.current(),
                                                                           ModelDiagnostics.Limits.DEFAULT);
            assertEquals(TypeInspection.Status.KNOWN, report.models().getFirst().observedTypes().getFirst().localType().status());
            var failure = assertThrows(ModelReadException.class, () -> app.modelRepository().load("cold", State.class));
            assertEquals(ModelReadException.Kind.APPLICATION_FAILURE, failure.getKind());
            assertEquals(ModelReadException.Operation.APPLY_EVENT, failure.getContext().operation());
            Throwable root = failure;
            while (root.getCause() != null) { root = root.getCause(); }
            assertSame(APPLICATION_FAILURE, root);
        }
    }

    @Test
    void applicationFailureAfterUpcastingRetainsOriginalStoredRepresentation() {
        var serializer = new JacksonSerializer();
        serializer.registerUpcasters(new HistoricalEvents());
        try (Fluxzero app = DefaultFluxzero.builder().replaceSerializer(serializer).disableKeepalive()
                .disableShutdownHook().build(LocalClient.newInstance(null))) {
            store(app, new SerializedMessage(new Data<>("{\"id\":\"cold\",\"fail\":true}".getBytes(StandardCharsets.UTF_8),
                    "historical.created", 0, "application/json"), Metadata.empty(), "event", 0L));
            var failure = assertThrows(ModelReadException.class, () -> app.modelRepository().load("cold", State.class));
            assertEquals(ModelReadException.Kind.APPLICATION_FAILURE, failure.getKind());
            assertEquals("historical.created", failure.getContext().serializedType());
            assertEquals(0, failure.getContext().serializedRevision());
            assertEquals(TypeInspection.Status.UNKNOWN, failure.getContext().registration().status());
            assertEquals(State.class.getSimpleName(), failure.getContext().modelType());
            assertNotNull(failure.getContext().stateIndex());
        }
    }

    @Test
    void contextualFailureRetainsApplicationWrapperAndGraphRoot() {
        var missing = ModelReadException.failure(ModelReadException.Kind.MISSING_MODEL_CONTRACT,
                ModelReadException.Operation.RESOLVE_MODEL_TYPE, "child", "foreign", null, null, null,
                APPLICATION_FAILURE).withRoot("root");
        var wrapper = new IllegalStateException("application wrapper", missing);
        var suppressed = new IllegalArgumentException("additional context");
        wrapper.addSuppressed(suppressed);
        var result = ModelReadException.failure(ModelReadException.Kind.APPLICATION_FAILURE,
                ModelReadException.Operation.APPLY_EVENT, "parent", "parent-type", State.class, null, null, wrapper);
        assertSame(wrapper, result.getCause());
        assertSame(suppressed, result.getCause().getSuppressed()[0]);
        assertEquals("root", result.getContext().rootId());
        assertEquals("child", result.getContext().modelId());
        assertEquals(ModelReadException.Kind.MISSING_MODEL_CONTRACT, result.getKind());
    }

    @Test
    void missingReplayHandlerIncludesItsStoredMembershipBoundary() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance(null))) {
            store(app, new Message("known event without a handler").serialize(app.serializer()));
            var failure = assertThrows(ModelReadException.class, () -> app.modelRepository().load("cold", State.class));
            assertEquals(ModelReadException.Kind.MISSING_REPLAY_HANDLER, failure.getKind());
            assertEquals(String.class.getName(), failure.getContext().serializedType());
            assertNotNull(failure.getContext().stateIndex());
        }
    }

    @Test
    void nestedHistoricalReadOfSameModelKeepsItsOwnEventContext() {
        var historical = ModelReadException.failure(ModelReadException.Kind.DECODING_FAILURE,
                ModelReadException.Operation.READ_EVENT, "model", "known", State.class,
                new Data<>(new byte[0], "historical.event", 0, "application/json"), null, APPLICATION_FAILURE, 0L);
        var current = ModelReadException.failure(ModelReadException.Kind.DECODING_FAILURE,
                ModelReadException.Operation.READ_EVENT, "model", "known", State.class,
                new Data<>(new byte[0], "current.event", 2, "application/json"), null, historical, 10L);
        assertEquals("historical.event", current.getContext().serializedType());
        assertEquals(0, current.getContext().serializedRevision());
        assertEquals(0L, current.getContext().stateIndex());
    }

    @Test
    void parserErrorsInsideApplicationCodeDoNotProveStoredDataIsCorrupt() throws Exception {
        Exception parserFailure;
        try (var parser = new com.fasterxml.jackson.core.JsonFactory().createParser("{ invalid")) {
            parserFailure = assertThrows(com.fasterxml.jackson.core.exc.StreamReadException.class, () -> {
                while (parser.nextToken() != null) { }
            });
        }
        var valid = new Data<>("{}".getBytes(StandardCharsets.UTF_8), "known", 0, "application/json");
        var decoded = ModelReadException.failure(ModelReadException.Kind.DECODING_FAILURE,
                ModelReadException.Operation.READ_EVENT, "model", "known", State.class, valid, null, parserFailure);
        assertEquals(ModelReadException.Kind.DECODING_FAILURE, decoded.getKind());
        var lenient = new JacksonSerializer(com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES).build());
        var extendedJson = new Data<>("{value:1}".getBytes(StandardCharsets.UTF_8), "known", 0, "application/json");
        assertEquals(ModelReadException.Kind.DECODING_FAILURE, ModelReadException.failure(
                ModelReadException.Kind.DECODING_FAILURE, ModelReadException.Operation.READ_EVENT,
                "model", "known", State.class, extendedJson, lenient, parserFailure).getKind());
        var applied = ModelReadException.failure(ModelReadException.Kind.APPLICATION_FAILURE,
                ModelReadException.Operation.APPLY_EVENT, "model", "known", State.class, valid, null, parserFailure);
        assertEquals(ModelReadException.Kind.APPLICATION_FAILURE, applied.getKind());
        assertSame(parserFailure, applied.getCause());
    }

    @Test
    void repositoryDiagnosticsPreserveNamespaceIsolation() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance(null))) {
            store(app, new Message(new Created("cold", false)).serialize(app.serializer()));
            assertTrue(app.modelRepository().diagnostics().inspectStreams(List.of("cold"), ModelReadBoundary.current(),
                    ModelDiagnostics.Limits.DEFAULT).models().getFirst().headPresent());
            assertFalse(app.modelRepository().forNamespace("isolated").diagnostics().inspectStreams(List.of("cold"),
                    ModelReadBoundary.current(), ModelDiagnostics.Limits.DEFAULT).models().getFirst().headPresent());
        }
    }

    @Test
    void graphFailureReportsRequestedRootAndUnknownLogicalContract() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance(null))) {
            store(app, new Message(new Created("cold", false)).serialize(app.serializer()), "foreign-model");
            var failure = assertThrows(ModelReadException.class, () -> app.modelRepository().loadGraph("cold", State.class, io.fluxzero.sdk.modeling.Graph.Options.DEFAULT));
            assertEquals(ModelReadException.Kind.MISSING_MODEL_CONTRACT, failure.getKind());
            assertEquals("cold", failure.getContext().rootId());
            assertEquals("foreign-model", failure.getContext().modelType());
        }
    }

    static class HistoricalEvents {
        @Upcast(type = "historical.created", revision = 0)
        Data<?> rename(Data<?> input) {
            return new Data<>(input.getValue(), Created.class.getName(), 1, input.getFormat());
        }
    }

    private static void store(Fluxzero app, SerializedMessage event) {
        store(app, event, State.class.getSimpleName());
    }

    private static void store(Fluxzero app, SerializedMessage event, String modelType) {
        app.client().getEventStoreClient().commitModels(new CommitModels("seed", -1L, List.of("cold"),
                List.of(ModelCommitStep.builder().event(event).targets(List.of(ModelCommitTarget.builder()
                        .modelId("cold").modelType(modelType).storeEvent(true).updateState(true)
                        .relationships(List.of()).build())).build()), ModelConflictPolicy.ACCEPT, Guarantee.STORED, true)).join();
    }

    private record Created(String id, boolean fail) { }
    @Model(searchable = false)
    private record State(@EntityId String id) {
        @Apply
        static State create(Created event) {
            if (event.fail()) { throw APPLICATION_FAILURE; }
            return new State(event.id());
        }
    }
}
