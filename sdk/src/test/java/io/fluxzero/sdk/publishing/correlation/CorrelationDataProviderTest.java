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

package io.fluxzero.sdk.publishing.correlation;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static io.fluxzero.sdk.configuration.ApplicationProperties.APPLICATION_VERSION_PROPERTY;
import static io.fluxzero.sdk.configuration.ApplicationProperties.TASK_ID_PROPERTY;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.METADATA_KEY;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.NAMESPACE_METADATA_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrelationDataProviderTest {
    private final CorrelationDataProvider testProvider = new TestCorrelationDataProvider();
    private final DefaultCorrelationDataProvider defaultProvider = DefaultCorrelationDataProvider.INSTANCE;

    @Test
    void provideCommandAndEventMetadata() {
        var command = new Message("bla");
        TestFixture.create(DefaultFluxzero.builder().replaceCorrelationDataProvider(
                defaultProvider -> testProvider), new CommandHandler())
                .whenExecuting(fc -> fc.commandGateway().sendAndForget(command))
                .expectCommands(command.addMetadata("foo", "bar"))
                .expectEvents(command.addMetadata("foo", "bar", "msgId", command.getMessageId()));
    }

    @Test
    void preservesCustomNoArgProviderAndNullRemovalSemantics() {
        var command = new Message("bla", Metadata.of("remove", "old"));
        CorrelationDataProvider provider = new CorrelationDataProvider() {
            @Override
            public Map<String, String> getCorrelationData() {
                Map<String, String> result = new HashMap<>();
                result.put("custom", "value");
                result.put("remove", null);
                return result;
            }

            @Override
            public Map<String, String> getCorrelationData(@Nullable DeserializingMessage currentMessage) {
                throw new AssertionError("The no-arg correlation provider contract should be used");
            }
        };

        TestFixture.create(DefaultFluxzero.builder().replaceCorrelationDataProvider(ignored -> provider))
                .whenExecuting(fc -> fc.commandGateway().sendAndForget(command))
                .expectCommands(command.withMetadata(Metadata.of("custom", "value")));
    }

    @Test
    void extendDefaultProvider() {
        var command = new Message("bla");
        TestFixture.create(DefaultFluxzero.builder().replaceCorrelationDataProvider(
                defaultProvider -> defaultProvider.andThen(testProvider)), new CommandHandler())
                .whenExecuting(fc -> fc.commandGateway().sendAndForget(command))
                .expectCommands(command.addMetadata("foo", "bar"))
                .expectCommands((Predicate<Message>) c -> c.getMetadata().containsKey(defaultProvider.getClientIdKey()))
                .expectEvents(command.addMetadata("foo", "bar", "msgId", command.getMessageId(),
                                                  defaultProvider.getCorrelationIdKey(), command.getMessageId()))
                .<Message>expectEvent(m -> m.getMetadata().containsKey(defaultProvider.getDelayKey()));
    }

    @Test
    void configuredApplicationVersionIsAvailableThroughEveryCorrelationDataPath() {
        var builder = DefaultFluxzero.builder()
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                        APPLICATION_VERSION_PROPERTY, " 1.2.3 ")))
                .replaceCorrelationDataProvider(ignored -> testProvider);

        try (Fluxzero fluxzero = builder.build(LocalClient.newInstance())) {
            CorrelationDataProvider provider = fluxzero.correlationDataProvider();
            for (MessageType messageType : MessageType.values()) {
                Map<String, String> correlationData = provider.getCorrelationData(
                        fluxzero.client(), null, messageType);
                assertEquals("1.2.3", correlationData.get(provider.getApplicationVersionKey()));
                assertEquals("bar", correlationData.get("foo"));
            }
            assertEquals("1.2.3", provider.getCorrelationData((DeserializingMessage) null)
                    .get(provider.getApplicationVersionKey()));
            assertEquals("1.2.3", fluxzero.configuration().correlationDataProvider()
                    .getCorrelationData((DeserializingMessage) null).get(provider.getApplicationVersionKey()));
        }
    }

    @Test
    void configuredApplicationVersionIsAuthoritativeOnPublishedMessages() {
        String metadataKey = defaultProvider.getApplicationVersionKey();
        var command = new Message("bla").addMetadata(metadataKey, "caller-supplied");
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                APPLICATION_VERSION_PROPERTY, "1.2.3")));

        TestFixture.create(builder, new CommandHandler())
                .whenExecuting(fc -> fc.commandGateway().sendAndForget(command))
                .expectCommands((Predicate<Message>) message ->
                        "1.2.3".equals(message.getMetadata().get(metadataKey)))
                .<Message>expectEvent(message -> "1.2.3".equals(message.getMetadata().get(metadataKey)));
    }

    @Test
    void configuredTaskIdIsAvailableThroughEveryCorrelationDataPath() {
        var builder = DefaultFluxzero.builder()
                .replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                        TASK_ID_PROPERTY, " task-123 ")))
                .replaceCorrelationDataProvider(ignored -> testProvider);

        try (Fluxzero fluxzero = builder.build(LocalClient.newInstance())) {
            CorrelationDataProvider provider = fluxzero.correlationDataProvider();
            for (MessageType messageType : MessageType.values()) {
                Map<String, String> correlationData = provider.getCorrelationData(
                        fluxzero.client(), null, messageType);
                assertEquals("task-123", correlationData.get(provider.getTaskIdKey()));
                assertEquals("bar", correlationData.get("foo"));
            }
            assertEquals("task-123", provider.getCorrelationData((DeserializingMessage) null)
                    .get(provider.getTaskIdKey()));
            assertEquals("task-123", fluxzero.configuration().correlationDataProvider()
                    .getCorrelationData((DeserializingMessage) null).get(provider.getTaskIdKey()));
        }
    }

    @Test
    void configuredTaskIdIsAuthoritativeOnPublishedMessages() {
        String metadataKey = defaultProvider.getTaskIdKey();
        String versionKey = defaultProvider.getApplicationVersionKey();
        var command = new Message("bla").addMetadata(
                metadataKey, "caller-supplied", versionKey, "caller-supplied");
        var builder = DefaultFluxzero.builder().replacePropertySource(ignored -> new SimplePropertySource(Map.of(
                TASK_ID_PROPERTY, "task-123", APPLICATION_VERSION_PROPERTY, "1.2.3")));

        TestFixture.create(builder, new CommandHandler())
                .whenExecuting(fc -> fc.commandGateway().sendAndForget(command))
                .expectCommands((Predicate<Message>) message ->
                        "task-123".equals(message.getMetadata().get(metadataKey))
                        && "1.2.3".equals(message.getMetadata().get(versionKey)))
                .<Message>expectEvent(message -> "task-123".equals(message.getMetadata().get(metadataKey))
                                                && "1.2.3".equals(message.getMetadata().get(versionKey)));
    }

    @Test
    void absentOrBlankApplicationVersionDoesNotDecorateCorrelationData() {
        assertSame(testProvider, ApplicationVersionCorrelationDataProvider.decorate(
                testProvider, new SimplePropertySource(Map.of())));
        assertSame(testProvider, ApplicationVersionCorrelationDataProvider.decorate(
                testProvider, new SimplePropertySource(Map.of(APPLICATION_VERSION_PROPERTY, " \t "))));
    }

    @Test
    void absentOrBlankTaskIdDoesNotDecorateCorrelationData() {
        assertSame(testProvider, TaskIdCorrelationDataProvider.decorate(
                testProvider, new SimplePropertySource(Map.of())));
        assertSame(testProvider, TaskIdCorrelationDataProvider.decorate(
                testProvider, new SimplePropertySource(Map.of(TASK_ID_PROPERTY, " \t "))));
    }

    @Test
    void includesNamespaceOfTriggeringMessage() {
        JacksonSerializer serializer = new JacksonSerializer();
        DeserializingMessage tenantMessage = new DeserializingMessage(
                new Message("trigger"), MessageType.EVENT, serializer)
                .putContext(ConsumerConfiguration.class, ConsumerConfiguration.builder()
                        .name("tenant-events").namespace("tenant").build());

        Map<String, String> tenantCorrelation = defaultProvider.getCorrelationData(tenantMessage);
        Map<String, String> applicationCorrelation = defaultProvider.getCorrelationData(
                new DeserializingMessage(new Message("trigger"), MessageType.EVENT,
                                         serializer));

        assertEquals("tenant", tenantCorrelation.get(defaultProvider.getTriggerNamespaceKey()));
        assertFalse(applicationCorrelation.containsKey(defaultProvider.getTriggerNamespaceKey()));
    }

    @Test
    void compactDefaultCorrelationMetadataMatchesTheMapContract() {
        DeserializingMessage current = new DeserializingMessage(
                new Message("trigger", Metadata.of("$trace.workflow", "test")),
                MessageType.COMMAND, new JacksonSerializer());
        Map<String, String> expected = new HashMap<>(defaultProvider.getCorrelationData(current));
        Map<String, String> actual = new HashMap<>(
                defaultProvider.getCorrelationMetadata(current).getEntries());

        expected.remove(defaultProvider.getDelayKey());
        actual.remove(defaultProvider.getDelayKey());

        assertEquals(expected, actual);
    }

    @Test
    void compactDefaultCorrelationMetadataPreservesSubMillisecondDelaySemantics() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(11), ZoneOffset.UTC);
        Fluxzero fluxzero = mock(Fluxzero.class, Answers.CALLS_REAL_METHODS);
        when(fluxzero.clock()).thenReturn(clock);
        DeserializingMessage current = new DeserializingMessage(
                new Message("trigger", Metadata.empty(), "message-id",
                            Instant.ofEpochSecond(10, 999_999_999)),
                MessageType.COMMAND, new JacksonSerializer());

        Metadata correlation = fluxzero.apply(
                ignored -> defaultProvider.getCorrelationMetadata(current));

        assertEquals("0", correlation.get(defaultProvider.getDelayKey()));
    }

    @Test
    void compactDefaultCorrelationMetadataPreservesNegativeSubMillisecondDelaySemantics() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(10, 999_999_999), ZoneOffset.UTC);
        Fluxzero fluxzero = mock(Fluxzero.class, Answers.CALLS_REAL_METHODS);
        when(fluxzero.clock()).thenReturn(clock);
        DeserializingMessage current = new DeserializingMessage(
                new Message("trigger", Metadata.empty(), "message-id", Instant.ofEpochSecond(11)),
                MessageType.COMMAND, new JacksonSerializer());

        Metadata correlation = fluxzero.apply(
                ignored -> defaultProvider.getCorrelationMetadata(current));

        assertEquals("0", correlation.get(defaultProvider.getDelayKey()));
    }

    @Test
    void replacesInheritedTriggerNamespaceForEveryMessagingHop() {
        DeserializingMessage current = new DeserializingMessage(
                new Message("current", Metadata.of(defaultProvider.getTriggerNamespaceKey(), "previous")),
                MessageType.COMMAND, new JacksonSerializer());

        Message outgoing = current.apply(ignored -> new CorrelatingInterceptor().interceptDispatch(
                new Message("outgoing"), MessageType.EVENT, null));

        assertFalse(outgoing.getMetadata().containsKey(defaultProvider.getTriggerNamespaceKey()));
    }

    @Test
    void doesNotInheritProtectedDataReferencesFromTriggeringRequest() {
        DeserializingMessage current = new DeserializingMessage(
                new Message("current", Metadata.of(METADATA_KEY, Map.of("secret", "old-key"),
                                                   NAMESPACE_METADATA_KEY, "tenant")),
                MessageType.COMMAND, new JacksonSerializer());

        Message outgoing = current.apply(ignored -> new CorrelatingInterceptor().interceptDispatch(
                new Message("outgoing"), MessageType.EVENT, null));

        assertFalse(outgoing.getMetadata().containsKey(METADATA_KEY));
        assertFalse(outgoing.getMetadata().containsKey(NAMESPACE_METADATA_KEY));
    }

    private static class CommandHandler {
        @HandleCommand
        void handle(Object command) {
            Fluxzero.publishEvent(command);
        }
    }

    private static class TestCorrelationDataProvider implements CorrelationDataProvider {

        @Override
        public Map<String, String> getCorrelationData(@Nullable DeserializingMessage currentMessage) {
            Client client = Fluxzero.getOptionally().map(Fluxzero::client).orElse(null);
            if (currentMessage == null) {
                return getCorrelationData(client, null, null);
            }
            return getCorrelationData(client, currentMessage.getSerializedObject(), currentMessage.getMessageType());
        }

        @Override
        public Map<String, String> getCorrelationData(Client client, @Nullable SerializedMessage msg,
                                                      @Nullable MessageType messageType) {
            Map<String, String> result = new HashMap<>(Map.of("foo", "bar"));
            if (msg != null) {
                result.put("msgId", msg.getMessageId());
            }
            return result;
        }
    }

}
