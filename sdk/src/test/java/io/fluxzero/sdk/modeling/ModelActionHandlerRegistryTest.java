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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelActionHandlerRegistryTest {

    @Test
    void receiverApplyTracksTheCommandPayloadRatherThanTheModel() {
        ModelActionHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                List.of(ReceiverCommand.class),
                subject.trackingTargets(
                        ReceiverModel.class,
                        HandlerFilter.ALWAYS_HANDLE));
        assertTrue(subject.createHandler(
                ReceiverCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isPresent());
        assertTrue(subject.createHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isEmpty());
    }

    @Test
    void staticModelApplyTracksTheCommandPayloadRatherThanTheModel() {
        ModelActionHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                StaticApplyModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                List.of(StaticCreateCommand.class),
                subject.trackingTargets(
                        StaticApplyModel.class,
                        HandlerFilter.ALWAYS_HANDLE));
        assertTrue(subject.createHandler(
                StaticCreateCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isPresent());
        assertTrue(subject.canHandle(
                message(new StaticCreateCommand("created"))));
    }

    @Test
    void automaticHandlingUsesApplyThenModelThenApplicationPrecedence() {
        ModelActionHandlerRegistry disabledApplication =
                subject(AutomaticModelHandling.DISABLED);
        disabledApplication.registerHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.registerHandler(
                ExplicitlyEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.registerHandler(
                ApplyEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertFalse(disabledApplication.canHandle(
                message(new ReceiverCommand("default"))));
        assertTrue(disabledApplication.canHandle(
                message(new ExplicitlyEnabledCommand("model"))));
        assertTrue(disabledApplication.canHandle(
                message(new ApplyEnabledCommand("apply"))));

        ModelActionHandlerRegistry enabledApplication =
                subject(AutomaticModelHandling.ENABLED);
        enabledApplication.registerHandler(
                ApplyDisabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        assertFalse(enabledApplication.canHandle(
                message(new ApplyDisabledCommand("disabled"))));
    }

    @Test
    void oneDisabledApplyDeclinesTheCompleteMultiModelCommand() {
        ModelActionHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                MixedEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                MixedDisabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertFalse(subject.canHandle(
                message(new MixedCommand("enabled", "disabled"))));
    }

    @Test
    void graphProjectionCompletionUsesApplyThenRootThenApplicationPrecedence()
            throws Exception {
        ModelActionEngine.Transition inherited =
                transition(
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "inherit"));
        ModelActionEngine.Transition asynchronous =
                transition(
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "asynchronous"));

        assertEquals(
                java.util.Set.of("awaited-graphs"),
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC)
                        .awaitedGraphProjections(
                                evaluation(inherited)));
        assertTrue(
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.AWAIT)
                        .awaitedGraphProjections(
                                evaluation(asynchronous))
                        .isEmpty());
        assertEquals(
                java.util.Set.of("default-graphs"),
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.AWAIT)
                        .awaitedGraphProjections(
                                evaluation(
                                        transition(
                                                DefaultProjectionRoot.class,
                                                ProjectionApplies.class
                                                        .getDeclaredMethod(
                                                                "inherit")))));
    }

    @Test
    void awaitDominatesAsyncForTheSameAffectedRoot()
            throws Exception {
        ModelActionHandlerRegistry subject =
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC);
        ModelActionEngine.Transition asynchronous =
                transition(
                        "shared-child",
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "asynchronous"));
        ModelActionEngine.Transition awaiting =
                transition(
                        "shared-child",
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "awaiting"));

        assertEquals(
                Map.of(
                        "awaited-graphs",
                        java.util.Set.of(
                                "shared-child")),
                subject.awaitedGraphProjectionTargets(
                        evaluation(
                                asynchronous,
                                awaiting)));
    }

    @Test
    void activeConsumerPrecedesRootAndApplicationCompletion()
            throws Exception {
        Tracker.current.set(
                new Tracker(
                        "tracker",
                        MessageType.COMMAND,
                        null,
                        ConsumerConfiguration.builder()
                                .name("consumer")
                                .graphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT)
                                .build(),
                        null));
        try {
            ModelActionHandlerRegistry subject =
                    subject(
                            AutomaticModelHandling.ENABLED,
                            GraphProjectionCompletion.ASYNC);
            assertEquals(
                    java.util.Set.of("async-graphs"),
                    subject.awaitedGraphProjections(
                            evaluation(
                                    transition(
                                            AsyncProjectionRoot.class,
                                            ProjectionApplies.class
                                                    .getDeclaredMethod(
                                                            "inherit")))));
            assertTrue(
                    subject.awaitedGraphProjections(
                            evaluation(
                                    transition(
                                            AsyncProjectionRoot.class,
                                            ProjectionApplies.class
                                                    .getDeclaredMethod(
                                                            "asynchronous"))))
                            .isEmpty());
        } finally {
            Tracker.current.remove();
        }
    }

    @Test
    void retriesAutomaticGraphProjectionRegistrationAfterTransientFailure() {
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        when(eventStoreClient.registerModelGraphProjection(
                any())).thenReturn(
                CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "runtime temporarily unavailable")),
                CompletableFuture.completedFuture(
                        new ModelGraphProjectionStatus(
                                0L, "retryRoots",
                                -1L, -1L,
                                0L, 0L, false)));
        JacksonSerializer serializer =
                new JacksonSerializer();
        ModelActionHandlerRegistry subject =
                new ModelActionHandlerRegistry(
                        mock(DefaultModelRepository.class),
                        eventStoreClient,
                        serializer,
                        serializer,
                        mock(DocumentSerializer.class),
                        DispatchInterceptor.noOp,
                        "test",
                        List.of(),
                        HandlerDecorator.noOp,
                        io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT,
                        ModelConflictResolver.fail(),
                        0,
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC);

        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);

        verify(eventStoreClient, times(2))
                .registerModelGraphProjection(
                        any());
    }

    private static ModelActionHandlerRegistry subject(
            AutomaticModelHandling automaticHandling) {
        return subject(
                automaticHandling,
                GraphProjectionCompletion.ASYNC);
    }

    private static ModelActionHandlerRegistry subject(
            AutomaticModelHandling automaticHandling,
            GraphProjectionCompletion graphProjectionCompletion) {
        JacksonSerializer serializer =
                new JacksonSerializer();
        return new ModelActionHandlerRegistry(
                mock(DefaultModelRepository.class),
                mock(EventStoreClient.class),
                serializer,
                serializer,
                mock(DocumentSerializer.class),
                DispatchInterceptor.noOp,
                "test",
                List.of(),
                HandlerDecorator.noOp,
                io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT,
                ModelConflictResolver.fail(),
                0,
                automaticHandling,
                graphProjectionCompletion);
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(
                new Message(payload),
                MessageType.COMMAND,
                new JacksonSerializer());
    }

    private static ModelActionEngine.ActionEvaluation evaluation(
            ModelActionEngine.Transition... transitions) {
        return new ModelActionEngine.ActionEvaluation(
                1L,
                java.util.Arrays.stream(transitions)
                        .map(ModelActionEngine.Transition::modelId)
                        .toList(),
                java.util.Arrays.stream(transitions)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ModelActionEngine.Transition::modelId,
                                        ModelActionEngine.Transition::modelType,
                                        (first, second) ->
                                                first)),
                List.of(
                        new ModelActionEngine.AppliedSubstep(
                                null,
                                List.of(transitions))),
                Map.of());
    }

    private static ModelActionEngine.Transition transition(
            Class<?> modelType,
            java.lang.reflect.Executable handler) {
        return transition(
                modelType.getName()
                + "#"
                + handler.getName(),
                modelType, handler);
    }

    private static ModelActionEngine.Transition transition(
            String modelId,
            Class<?> modelType,
            java.lang.reflect.Executable handler) {
        return new ModelActionEngine.Transition(
                modelId,
                modelType, 0L, null,
                new Object(), handler);
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "retryRoots"))
    private record RetryRoot(
            @EntityId String id) {
    }

    @Model
    private record ReceiverModel(
            @EntityId String id) {
        @Apply
        ReceiverModel apply(ReceiverCommand command) {
            return new ReceiverModel(command.id());
        }
    }

    private record ReceiverCommand(String id) {
    }

    @Model
    private record StaticApplyModel(
            @EntityId String id) {
        @Apply
        static StaticApplyModel create(
                StaticCreateCommand command) {
            return new StaticApplyModel(
                    command.id());
        }
    }

    private record StaticCreateCommand(
            String id) {
    }

    @Model(automaticHandling = AutomaticModelHandling.ENABLED)
    private record ExplicitlyEnabledModel(
            @EntityId String id) {
        @Apply
        ExplicitlyEnabledModel apply(
                ExplicitlyEnabledCommand command) {
            return new ExplicitlyEnabledModel(command.id());
        }
    }

    private record ExplicitlyEnabledCommand(String id) {
    }

    @Model(automaticHandling = AutomaticModelHandling.DISABLED)
    private record ApplyEnabledModel(
            @EntityId String id) {
        @Apply(automaticHandling = AutomaticModelHandling.ENABLED)
        ApplyEnabledModel apply(
                ApplyEnabledCommand command) {
            return new ApplyEnabledModel(command.id());
        }
    }

    private record ApplyEnabledCommand(String id) {
    }

    @Model(automaticHandling = AutomaticModelHandling.ENABLED)
    private record ApplyDisabledModel(
            @EntityId String id) {
        @Apply(automaticHandling = AutomaticModelHandling.DISABLED)
        ApplyDisabledModel apply(
                ApplyDisabledCommand command) {
            return new ApplyDisabledModel(command.id());
        }
    }

    private record ApplyDisabledCommand(String id) {
    }

    @Model
    private record MixedEnabledModel(
            @EntityId String id) {
        @Apply
        MixedEnabledModel apply(MixedCommand command) {
            return new MixedEnabledModel(command.enabledId());
        }
    }

    @Model
    private record MixedDisabledModel(
            @EntityId String id) {
        @Apply(automaticHandling = AutomaticModelHandling.DISABLED)
        MixedDisabledModel apply(MixedCommand command) {
            return new MixedDisabledModel(command.disabledId());
        }
    }

    private record MixedCommand(
            String enabledId,
            String disabledId) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "awaited-graphs",
                    completion = GraphProjectionCompletion.AWAIT))
    private record ProjectionRoot(
            @EntityId ProjectionRootId id) {
    }

    private static final class ProjectionRootId
            extends Id<ProjectionRoot> {
        private ProjectionRootId(String id) {
            super(id, "projection-root-");
        }
    }

    @Model
    private record ProjectionChild(
            @EntityId String id,
            @ParentId(path = "children")
            ProjectionRootId rootId) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "default-graphs"))
    private record DefaultProjectionRoot(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "async-graphs",
                    completion = GraphProjectionCompletion.ASYNC))
    private record AsyncProjectionRoot(
            @EntityId String id) {
    }

    private static class ProjectionApplies {
        @Apply
        Object inherit() {
            return null;
        }

        @Apply(
                graphProjectionCompletion =
                        GraphProjectionCompletion.ASYNC)
        Object asynchronous() {
            return null;
        }

        @Apply(
                graphProjectionCompletion =
                        GraphProjectionCompletion.AWAIT)
        Object awaiting() {
            return null;
        }
    }
}
