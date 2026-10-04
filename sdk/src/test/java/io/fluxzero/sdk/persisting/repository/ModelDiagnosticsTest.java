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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.TypeInspection;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelDiagnosticsTest {
    private final EventStoreClient client = mock(EventStoreClient.class);
    private final ModelTypeResolver models = new ModelTypeResolver() {
        public String modelName(Class<?> type) { return "known"; }
        public Class<?> modelType(String name, String id) { throw new AssertionError("Strict resolution not required"); }
        public Optional<Class<?>> knownModelType(String name, String id) {
            return "known".equals(name) ? Optional.of(Known.class) : Optional.empty();
        }
    };
    private final JacksonSerializer serializer = new JacksonSerializer();
    private final ModelDiagnostics diagnostics = new ModelDiagnostics(client, models, serializer);

    @Test
    void inspectsOnlyMetadataAndKeepsUnknownAndPartialStreamsExplicit() {
        serializer.registerTypeAlias("old-event", KnownEvent.class.getName());
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            assertEquals(ModelReadBoundary.at(42L), request.getBoundary());
            assertEquals(1, request.getMaxBytes());
            assertEquals(List.of(2, 2, 2), request.getRequests().stream().map(ModelEventStreamRequest::getMaxSize).toList());
            return new GetModelEventsResult(request.getRequestId(), 42,
                    List.of(payload(10, "old-event", 0), payload(11, "unshared.event", 7)),
                    List.of(stream("a", "known", 0, 10), stream("b", "foreign", 4, 11),
                            new ModelEventStream("absent", null, List.of())));
        });
        var report = diagnostics.inspectStreams(List.of("a", "b", "absent"), ModelReadBoundary.at(42L),
                new ModelDiagnostics.Limits(3, 2, 1, 2));
        assertEquals(42, report.stateIndex());
        assertTrue(report.exactBoundary());
        assertFalse(report.graphSample());
        var known = report.models().getFirst();
        assertEquals(Known.class.getName(), known.localModelType());
        assertTrue(known.eventsComplete());
        var type = known.observedTypes().getFirst();
        assertEquals(0, type.revision());
        assertEquals(2, type.localType().localRevision());
        assertEquals(TypeInspection.Status.KNOWN, type.localType().status());
        var unknown = report.models().get(1);
        assertTrue(unknown.headPresent());
        assertNull(unknown.localModelType());
        assertFalse(unknown.eventsComplete());
        assertEquals(TypeInspection.Status.UNKNOWN, unknown.observedTypes().getFirst().localType().status());
        assertFalse(report.models().getLast().headPresent());
        verify(client, times(1)).getModelEvents(any());
        verifyNoMoreInteractions(client);
        assertFalse(report.toString().contains("payload-secret"));
    }

    @Test
    void graphInspectionUsesOneBoundedReadWithoutResolvingUnknownValues() {
        when(client.getModelGraph(any())).thenAnswer(invocation -> {
            GetModelGraph request = invocation.getArgument(0);
            assertEquals(2, request.getMaxModels());
            assertEquals(3, request.getMaxDepth());
            assertEquals(1, request.getMaxEventsPerModel());
            return new GetModelGraphResult(request.getRequestId(), List.of(),
                    new GetModelEventsResult(request.getRequestId(), 42, false, List.of(payload(10, "foreign.event", 1)),
                            List.of(stream("root", "known", 0, 10), stream("child", "foreign", 9, 10))));
        });
        var report = diagnostics.inspectGraph("root", ModelReadBoundary.current(),
                                              new ModelDiagnostics.Limits(2, 1, 128, 3));
        assertTrue(report.graphSample());
        assertFalse(report.exactBoundary());
        assertEquals(2, report.models().size());
        assertNull(report.models().getLast().localModelType());
        verify(client, never()).getModelEvents(any());
        verify(client, times(1)).getModelGraph(any());
    }

    @Test
    void validatesLimitsBeforeStorageAndRejectsChangedBoundariesOrMissingPayloads() {
        assertThrows(IllegalArgumentException.class, () -> new ModelDiagnostics.Limits(256, 1024, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ModelDiagnostics.Limits(1, 1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> diagnostics.inspectStreams(
                List.of("a", "a"), ModelReadBoundary.current(), ModelDiagnostics.Limits.DEFAULT));
        verifyNoInteractions(client);
        when(client.getModelEvents(any())).thenReturn(new GetModelEventsResult(0, 43, List.of(), List.of()));
        assertEquals(ModelReadException.Kind.INVALID_DATA, assertThrows(ModelReadException.class,
                () -> diagnostics.inspectStreams(List.of("a"), ModelReadBoundary.at(42L),
                                                  ModelDiagnostics.Limits.DEFAULT)).getKind());
        when(client.getModelEvents(any())).thenReturn(new GetModelEventsResult(0, 42, List.of(),
                                                                              List.of(stream("a", "known", 0, 10))));
        assertThrows(ModelReadException.class, () -> diagnostics.inspectStreams(
                List.of("a"), ModelReadBoundary.at(42L), ModelDiagnostics.Limits.DEFAULT));
    }

    @Test
    void customSerializerCanLeaveLookupUnavailableWithoutPretendingTheTypeIsMissing() {
        Serializer custom = mock(Serializer.class, CALLS_REAL_METHODS);
        assertEquals(TypeInspection.Status.UNAVAILABLE, custom.inspectType("custom-event").status());
        assertEquals(TypeInspection.Status.KNOWN, serializer.inspectType("java.util.List<java.lang.String>").status());
        assertEquals(0, serializer.inspectType("java.util.List<java.lang.String>").localRevision());
    }

    @Test
    void packedInspectionNeverRunsStructuralUpcastersOrDecodesPayloads() {
        serializer.registerUpcasters(new UnsafeUpcaster());
        var message = new SerializedMessage(new Data<>(new byte[]{(byte) '{'}, "historical.event", 0,
                                                       "application/json"), Metadata.empty(), "packed", 0L);
        message.setIndex(0L);
        var packed = new ModelEventPayloadBlock(0, 1, false, BinaryWire.encodeEnvelope(message));
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(request.getRequestId(), 42, true, List.of(),
                    List.of(stream("a", "known", 0, 10)), new long[]{10}, List.of(packed), new long[]{0}, List.of());
        });
        var report = diagnostics.inspectStreams(List.of("a"), ModelReadBoundary.at(42L), ModelDiagnostics.Limits.DEFAULT);
        assertTrue(report.models().getFirst().eventsComplete());
        assertEquals(TypeInspection.Status.UNKNOWN,
                     report.models().getFirst().observedTypes().getFirst().localType().status());
        verify(client, times(1)).getModelEvents(any());
    }

    static class UnsafeUpcaster {
        @Upcast(type = "historical.event", revision = 0)
        Data<?> upcast(Data<?> data) { throw new AssertionError("Must not execute application upcasters"); }
    }

    private static ModelEventPayload payload(long state, String type, int revision) {
        Data<byte[]> data = new Data<>((Supplier<byte[]>) () -> { throw new AssertionError("payload-secret must not be read"); },
                                       type, revision, "application/json");
        return new ModelEventPayload(state, new SerializedMessage(data, Metadata.empty(), "id-" + state, 0L));
    }

    private static ModelEventStream stream(String id, String type, long headSequence, long eventState) {
        return new ModelEventStream(id, new ModelHeadState(id, type, headSequence, 42, true, false),
                                    List.of(new ModelEventMembership(0, eventState, -1, "commit", 0)));
    }

    @Model(searchable = false)
    private record Known(@EntityId String id) { }
    @Revision(2)
    private record KnownEvent(String id) { }
}
