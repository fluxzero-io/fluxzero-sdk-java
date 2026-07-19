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
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.METADATA_KEY;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.NAMESPACE_METADATA_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
