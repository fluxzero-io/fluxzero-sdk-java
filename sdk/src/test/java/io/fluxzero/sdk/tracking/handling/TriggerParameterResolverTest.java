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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.correlation.DefaultCorrelationDataProvider;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriggerParameterResolverTest {

    private final TestFixture testFixture = TestFixture.createAsync(new MainHandler(), new ResultHandler());

    @Test
    void triggerAsPayload() {
        testFixture.whenCommand(new TriggerAsPayload("some result"))
                .expectEvents(new ResultReceived("some result", TriggerAsPayload.class));
    }

    @Test
    void triggerAsPayload_multipleTriggers() {
        testFixture.whenCommand(new TriggerAsPayload3("some result"))
                .expectEvents(new ResultReceived("some result", TriggerAsPayload3.class));
    }

    @Test
    void triggerAsMessage() {
        testFixture.whenCommand(new TriggerAsMessage("some result"))
                .expectEvents(new ResultReceived("some result", TriggerAsMessage.class));
    }

    @Test
    void triggerAsMessage_multipleTriggers() {
        testFixture.whenCommand(new TriggerAsMessage2("some result"))
                .expectOnlyEvents(new ResultReceived("some result", TriggerAsMessage2.class));
    }

    @Test
    void triggerAsMessageNotHandled() {
        testFixture.whenCommand(new TriggerAsMessageNotHandled("some result")).expectNoEvents();
    }

    @Test
    void triggerError() {
        testFixture.whenCommand(new Throws("some error"))
                .expectEvents(new ResultReceived("some error", Throws.class));
    }

    @Test
    void triggerNotInjectedIfMessageTypeIsWrong() {
        testFixture.whenQuery(new TriggerAsPayload("some result")).expectNoEvents();
    }

    @Test
    void consumerIsChecked() {
        testFixture.whenCommand(new ThrowsOther("some error")).expectNoEvents();
    }

    @Test
    void triggerAsMethodAnnotation() {
        testFixture.whenCommand(new TriggerOnMethod("some result"))
                .expectOnlyEvents(new ResultReceived("some result", TriggerOnMethod.class));
    }

    @Test
    void loadsTriggerFromNamespaceStoredInCorrelationData() {
        Client applicationClient = mock(Client.class);
        Client sourceClient = mock(Client.class);
        TrackingClient trackingClient = mock(TrackingClient.class);
        JacksonSerializer serializer = new JacksonSerializer();
        SerializedMessage serializedTrigger = new Message("original").serialize(serializer);
        serializedTrigger.setIndex(42L);
        when(applicationClient.forNamespace("source")).thenReturn(sourceClient);
        when(sourceClient.getTrackingClient(MessageType.COMMAND)).thenReturn(trackingClient);
        when(trackingClient.readFromIndex(42L, 1)).thenReturn(List.of(serializedTrigger));
        var provider = DefaultCorrelationDataProvider.INSTANCE;
        DeserializingMessage resultMessage = new DeserializingMessage(
                new Message("result", Metadata.of(
                        provider.getCorrelationIdKey(), "42",
                        provider.getTriggerKey(), String.class.getName(),
                        provider.getTriggerTypeKey(), MessageType.COMMAND.name(),
                        provider.getTriggerNamespaceKey(), "source")),
                MessageType.RESULT, serializer);
        Tracker.current.set(new Tracker("tracker", MessageType.RESULT, null,
                                        ConsumerConfiguration.builder().name("result-handler")
                                                .namespace("destination").build(), null));
        try {
            assertEquals("original", new TriggerParameterResolver(applicationClient, serializer)
                    .getTriggerMessage(resultMessage).orElseThrow().getPayload());
        } finally {
            Tracker.current.remove();
        }

        verify(applicationClient).forNamespace("source");
    }

    @Consumer(name = "main")
    static class MainHandler {
        @HandleCommand
        String handle(HasResult command) {
            return command.getResult();
        }

        @HandleQuery
        String handleQuery(HasResult query) {
            return query.getResult();
        }

        @HandleCommand
        void handle(Throws command) {
            throw new MockException(command.getErrorMessage());
        }

        @HandleCommand
        void handle(ThrowsOther command) {
            throw new IllegalCommandException(command.getErrorMessage());
        }
    }

    static class ResultHandler {
        @HandleResult
        void handle(String result, @Trigger(messageType = MessageType.COMMAND) TriggerAsPayload trigger) {
            Fluxzero.publishEvent(new ResultReceived(result, trigger.getClass()));
        }

        @HandleResult
        void handle(String result, @Trigger({TriggerAsPayload2.class, TriggerAsPayload3.class}) HasResult trigger) {
            Fluxzero.publishEvent(new ResultReceived(result, trigger.getClass()));
        }

        @HandleResult
        void handle(String result, @Trigger({TriggerAsMessage.class, TriggerAsMessage2.class}) Message trigger) {
            Fluxzero.publishEvent(new ResultReceived(result, trigger.getPayloadClass()));
        }

        @HandleResult
        void handle(String result, @Trigger(TriggerAsDeserializingMessage.class) DeserializingMessage trigger) {
            Fluxzero.publishEvent(new ResultReceived(result, trigger.getPayloadClass()));
        }

        @HandleError
        void handle(MockException error, @Trigger(consumer = "main") Throws trigger) {
            Fluxzero.publishEvent(new ResultReceived(error.getMessage(), trigger.getClass()));
        }

        @HandleError
        void handle(IllegalCommandException error, @Trigger(consumer = "wrongConsumer") Throws trigger) {
            Fluxzero.publishEvent(new ResultReceived(error.getMessage(), trigger.getClass()));
        }

        @HandleResult
        @Trigger(value = TriggerOnMethod.class, consumer = "main")
        void handle(String result) {
            Fluxzero.publishEvent(new ResultReceived(result, TriggerOnMethod.class));
        }
    }



    interface HasResult {
        String getResult();
    }

    @Value
    static class TriggerAsPayload implements HasResult {
        String result;
    }

    @Value
    static class TriggerAsPayload2 implements HasResult {
        String result;
    }

    @Value
    static class TriggerAsPayload3 implements HasResult {
        String result;
    }

    @Value
    static class TriggerAsMessage implements HasResult {
        String result;
    }

    @Value
    static class TriggerAsMessage2 implements HasResult {
        String result;
    }

    @Value
    static class TriggerAsMessageNotHandled implements HasResult {
        String result;
    }

    @Value
    static class TriggerAsDeserializingMessage implements HasResult {
        String result;
    }

    @Value
    static class TriggerOnMethod implements HasResult {
        String result;
    }

    @Value
    static class Throws {
        String errorMessage;
    }

    @Value
    static class ThrowsOther {
        String errorMessage;
    }

    @Value
    static class ResultReceived {
        String result;
        Class<?> triggerClass;
    }
}
