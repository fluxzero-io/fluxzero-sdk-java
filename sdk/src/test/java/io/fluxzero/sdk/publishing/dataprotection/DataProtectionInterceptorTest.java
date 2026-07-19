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

package io.fluxzero.sdk.publishing.dataprotection;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.persisting.keyvalue.KeyValueStore;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import io.fluxzero.sdk.tracking.metrics.HandleMessageEvent;
import io.fluxzero.sdk.tracking.metrics.IgnoreMessageEvent;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProtectionInterceptorTest {
    private final TestFixture testFixture = TestFixture.createAsync();

    @Test
    void testSerializedMessageDoesNotContainData() {
        testFixture.registerHandlers(new SomeHandler())
                .whenExecuting(fc -> Fluxzero.publishEvent(new SomeEvent("something super secret")))
                .expectEvents(new SomeEvent(null));
    }

    @Test
    void storesProtectedDataInDispatchNamespace() {
        KeyValueStore defaultStore = mock(KeyValueStore.class);
        KeyValueStore namespacedStore = mock(KeyValueStore.class);
        when(defaultStore.forNamespace("tenant")).thenReturn(namespacedStore);

        new DataProtectionInterceptor(defaultStore, new JacksonSerializer()).interceptDispatch(
                new Message(new SomeEvent("secret")), MessageType.EVENT, null, "tenant");

        verify(namespacedStore).store(anyString(), eq("secret"), eq(io.fluxzero.common.Guarantee.STORED));
        verify(defaultStore, never()).store(anyString(), eq("secret"), eq(io.fluxzero.common.Guarantee.STORED));
    }

    @Test
    void replacesInheritedProtectedDataReferencesInDispatchNamespace() {
        KeyValueStore defaultStore = mock(KeyValueStore.class);
        KeyValueStore namespacedStore = mock(KeyValueStore.class);
        when(defaultStore.forNamespace("tenant")).thenReturn(namespacedStore);
        Message message = new Message(new SomeEvent("secret"), Metadata.of(
                DataProtectionInterceptor.METADATA_KEY, Map.of("sensitiveData", "old-key"),
                DataProtectionInterceptor.NAMESPACE_METADATA_KEY, "application"));

        Message result = new DataProtectionInterceptor(defaultStore, new JacksonSerializer())
                .interceptDispatch(message, MessageType.EVENT, null, "tenant");

        Map<String, String> references = result.getMetadata().get(
                DataProtectionInterceptor.METADATA_KEY, Map.class);
        assertFalse(references.containsValue("old-key"));
        assertEquals("tenant", result.getMetadata().get(DataProtectionInterceptor.NAMESPACE_METADATA_KEY));
        verify(namespacedStore).store(anyString(), eq("secret"), eq(io.fluxzero.common.Guarantee.STORED));
    }

    @Test
    void removesInheritedReferencesWhenProtectedValueIsUnavailableInNewNamespace() {
        Message message = new Message(new SomeEvent(null), Metadata.of(
                DataProtectionInterceptor.METADATA_KEY, Map.of("sensitiveData", "old-key"),
                DataProtectionInterceptor.NAMESPACE_METADATA_KEY, "application"));

        Message result = new DataProtectionInterceptor(mock(KeyValueStore.class), new JacksonSerializer())
                .interceptDispatch(message, MessageType.EVENT, null, "tenant");

        assertFalse(result.getMetadata().containsKey(DataProtectionInterceptor.METADATA_KEY));
        assertFalse(result.getMetadata().containsKey(DataProtectionInterceptor.NAMESPACE_METADATA_KEY));
    }

    @Test
    void removesProtectedDataMetadataWhenPayloadHasNothingToProtect() {
        Message result = new DataProtectionInterceptor(mock(KeyValueStore.class), new JacksonSerializer())
                .interceptDispatch(new Message("plain", Metadata.of(
                        DataProtectionInterceptor.METADATA_KEY, Map.of("secret", "old-key"))),
                                   MessageType.EVENT, null, "tenant");

        assertFalse(result.getMetadata().containsKey(DataProtectionInterceptor.METADATA_KEY));
        assertFalse(result.getMetadata().containsKey(DataProtectionInterceptor.NAMESPACE_METADATA_KEY));
    }

    @Test
    void restoresProtectedDataFromConsumerNamespace() {
        KeyValueStore defaultStore = mock(KeyValueStore.class);
        KeyValueStore namespacedStore = mock(KeyValueStore.class);
        when(defaultStore.forNamespace("tenant")).thenReturn(namespacedStore);
        when(namespacedStore.get("protected-key")).thenReturn("secret");
        SomeHandler target = new SomeHandler();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                target, HandleEvent.class, List.of(new PayloadParameterResolver(), new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new DataProtectionInterceptor(
                defaultStore, new JacksonSerializer()).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new SomeEvent(null), Metadata.of(
                        DataProtectionInterceptor.METADATA_KEY, Map.of("sensitiveData", "protected-key"))),
                MessageType.EVENT, new JacksonSerializer());
        message.putContext(ConsumerConfiguration.class,
                           ConsumerConfiguration.builder().name("tenant-consumer").namespace("tenant").build());

        wrapped.getInvokerOrNull(message).invoke();

        assertEquals("secret", target.getLastEvent().getSensitiveData());
        verify(namespacedStore).get("protected-key");
    }

    @Test
    void localDispatchRestoresProtectedDataFromApplicationNamespaceInsideCustomConsumer() {
        KeyValueStore defaultStore = mock(KeyValueStore.class);
        KeyValueStore namespacedStore = mock(KeyValueStore.class);
        when(defaultStore.forNamespace(null)).thenReturn(defaultStore);
        when(defaultStore.forNamespace("tenant")).thenReturn(namespacedStore);
        when(defaultStore.get("protected-key")).thenReturn("secret");
        SomeHandler target = new SomeHandler();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                target, HandleEvent.class, List.of(new PayloadParameterResolver(), new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new DataProtectionInterceptor(
                defaultStore, new JacksonSerializer()).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new SomeEvent(null), Metadata.of(
                        DataProtectionInterceptor.METADATA_KEY, Map.of("sensitiveData", "protected-key"),
                        DataProtectionInterceptor.NAMESPACE_METADATA_KEY, "")),
                MessageType.EVENT, new JacksonSerializer());
        message.putContext(ConsumerConfiguration.class,
                           ConsumerConfiguration.builder().name("tenant-consumer").namespace("tenant").build());

        wrapped.getInvokerOrNull(message).invoke();

        assertEquals("secret", target.getLastEvent().getSensitiveData());
        verify(defaultStore).get("protected-key");
        verify(namespacedStore, never()).get("protected-key");
    }

    @Test
    void testHandlerDoesGetData() {
        String payload = "something super secret";
        SomeHandler handler = new SomeHandler();
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new SomeEvent(payload)))
                .expectThat(fc -> {
                    assertEquals(payload, handler.getLastEvent().getSensitiveData());
                    assertTrue(handler.getLastMetadata().containsKey(DataProtectionInterceptor.METADATA_KEY));
                });
    }

    @Test
    void testDroppingDataPermanently() {
        String payload = "something super secret";
        DroppingHandler droppingHandler = new DroppingHandler();
        SomeHandler secondHandler = new SomeHandler();
        testFixture.registerHandlers(droppingHandler, secondHandler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new SomeEvent(payload)))
                .expectThat(fc -> {
                    assertEquals(payload, droppingHandler.getLastEvent().getSensitiveData());
                    assertNull(secondHandler.getLastEvent().getSensitiveData());
                });
    }

    @Test
    void testCommandValidationAfterFieldIsSet() {
        String payload = "something super secret";
        var handler = new ValidatingHandler();
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.sendCommandAndWait(new ConstrainedCommand(payload)))
                .expectThat(fc -> assertEquals(payload, handler.getLastCommand().getSensitiveData()));
    }

    @Test
    void testNullDataIsIgnored() {
        SomeHandler handler = new SomeHandler();
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new SomeEvent(null)))
                .expectEvents(new SomeEvent(null))
                .expectThat(fc -> {
                    assertNull(handler.getLastEvent().getSensitiveData());
                    assertFalse(handler.getLastMetadata().containsKey(DataProtectionInterceptor.METADATA_KEY));
                });
    }

    @Test
    void testNestedProtectedDataRequiresAnnotatedPath() {
        String payload = "something nested and secret";
        NestedHandler handler = new NestedHandler();
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new NestedEvent(new SensitiveDetails(payload, "visible"))))
                .expectEvents(new NestedEvent(new SensitiveDetails(null, "visible")))
                .expectThat(fc -> {
                    assertEquals(payload, handler.getLastEvent().getDetails().getSensitiveData());
                    assertEquals("visible", handler.getLastEvent().getDetails().getPublicData());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("details/sensitiveData"));
                });
    }

    @Test
    void testTypeAnnotationProtectsWholeAnnotatedField() {
        WholeObjectHandler handler = new WholeObjectHandler();
        WholeProtectedDetails payload = new WholeProtectedDetails("secret", "visible");
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new WholeObjectEvent(payload)))
                .expectEvents(new WholeObjectEvent(null))
                .expectThat(fc -> {
                    assertEquals(payload, handler.getLastEvent().getDetails());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("details"));
                });
    }

    @Test
    void testIdTypeAnnotationProtectsWholeAnnotatedField() {
        IdHandler handler = new IdHandler();
        ProtectedId payload = new ProtectedId("id-123");
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new ProtectedIdEvent(payload)))
                .expectThat(fc -> {
                    assertEquals(payload, handler.getLastEvent().getProtectedId());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("protectedId"));
                    assertFalse(new String(handler.getData().getValue()).contains("id-123"));
                });
    }

    @Test
    void testDataValueIsProtectedAsWholeField() {
        DataHandler handler = new DataHandler();
        Data<String> payload = new Data<>("top-secret", String.class.getName(), 0, Data.JSON_FORMAT);
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new ProtectedDataEvent(payload)))
                .expectEvents(new ProtectedDataEvent(null))
                .expectThat(fc -> {
                    assertEquals(payload.getValue(), handler.getLastEvent().getProtectedData().getValue());
                    assertEquals(payload.getType(), handler.getLastEvent().getProtectedData().getType());
                    assertEquals(payload.getRevision(), handler.getLastEvent().getProtectedData().getRevision());
                    assertEquals(payload.getFormat(), handler.getLastEvent().getProtectedData().getFormat());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("protectedData"));
                });
    }

    @Test
    void testJsonNodeValueIsProtectedAsWholeField() {
        JsonNodeHandler handler = new JsonNodeHandler();
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("secret", "top-secret").put("visible", "ok");
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new ProtectedJsonNodeEvent(payload)))
                .expectEvents(new ProtectedJsonNodeEvent(null))
                .expectThat(fc -> {
                    assertEquals(payload, handler.getLastEvent().getProtectedJson());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("protectedJson"));
                });
    }

    @Test
    void testProtectDataOnRecordComponent() {
        RecordHandler handler = new RecordHandler();
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(new ProtectedRecordEvent("top-secret", "visible")))
                .expectEvents(new ProtectedRecordEvent(null, "visible"))
                .expectThat(fc -> {
                    assertEquals("top-secret", handler.getLastEvent().secret());
                    assertEquals("visible", handler.getLastEvent().visible());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("secret"));
                });
    }

    @Test
    void testNestedProtectDataOnRecordComponents() {
        NestedRecordHandler handler = new NestedRecordHandler();
        testFixture.registerHandlers(handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(
                        new ProtectedNestedRecordEvent(new ProtectedSensitiveDetails("top-secret", "visible"))))
                .expectEvents(new ProtectedNestedRecordEvent(new ProtectedSensitiveDetails(null, "visible")))
                .expectThat(fc -> {
                    assertEquals("top-secret", handler.getLastEvent().details().socialSecurityNumber());
                    assertEquals("visible", handler.getLastEvent().details().displayName());
                    assertTrue(handler.getLastMetadata().get(DataProtectionInterceptor.METADATA_KEY, Map.class)
                            .containsKey("details/socialSecurityNumber"));
                });
    }

    @Test
    void protectedMessagesDoNotExposeReusableHandlerMethodBeforeDataIsRestored() {
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new SomeHandler(), HandleEvent.class,
                java.util.List.of(new MessageParameterResolver(), new PayloadParameterResolver()));
        Handler<DeserializingMessage> wrapped = new DataProtectionInterceptor(null, null).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new SomeEvent(null), Metadata.of(DataProtectionInterceptor.METADATA_KEY,
                                                             Map.of("sensitiveData", "key"))),
                MessageType.EVENT, null);

        assertNull(wrapped.getHandlerMethodOrNull(message));
        assertNotNull(wrapped.getInvokerOrNull(message));
    }

    @Test
    void handlePolicyInvokesHandlerSilentlyWhenProtectedDataIsMissing() {
        DefaultPolicyHandler handler = new DefaultPolicyHandler();

        invokeWithMissingProtectedData(handler, MissingProtectedDataPolicy.HANDLE, null);

        assertTrue(handler.isInvoked());
        assertNull(handler.getLastEvent().getSensitiveData());
    }

    @Test
    void warnPolicyLogsAndInvokesHandlerWhenProtectedDataIsMissing() {
        Logger logger = (Logger) LoggerFactory.getLogger(DataProtectionInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        DefaultPolicyHandler handler = new DefaultPolicyHandler();
        try {
            invokeWithMissingProtectedData(handler, MissingProtectedDataPolicy.WARN, null);
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(handler.isInvoked());
        assertTrue(appender.list.stream().anyMatch(
                event -> event.getLevel() == Level.WARN
                         && event.getFormattedMessage().contains("sensitiveData")));
    }

    @Test
    void skipPolicyDoesNotInvokeHandlerWhenProtectedDataIsMissing() {
        DefaultPolicyHandler handler = new DefaultPolicyHandler();

        HandlerInvoker invoker = invokeWithMissingProtectedData(handler, MissingProtectedDataPolicy.SKIP, null);

        assertFalse(handler.isInvoked());
        assertTrue(invoker.wasSkipped());
        assertFalse(invoker.isPassive());
    }

    @Test
    void failPolicyRejectsMessageWhenProtectedDataIsMissing() {
        DefaultPolicyHandler handler = new DefaultPolicyHandler();

        MissingProtectedDataException exception = assertThrows(
                MissingProtectedDataException.class,
                () -> invokeWithMissingProtectedData(handler, MissingProtectedDataPolicy.FAIL, null));

        assertEquals(java.util.Set.of("sensitiveData"), exception.getMissingFields());
        assertFalse(handler.isInvoked());
    }

    @Test
    void consumerPolicyOverridesApplicationPolicy() {
        DefaultPolicyHandler handler = new DefaultPolicyHandler();
        ConsumerConfiguration consumer = ConsumerConfiguration.builder().name("test")
                .onMissingProtectedData(MissingProtectedDataPolicy.SKIP).build();

        invokeWithMissingProtectedData(handler, MissingProtectedDataPolicy.FAIL, consumer);

        assertFalse(handler.isInvoked());
    }

    @Test
    void handlerPolicyOverridesConsumerPolicy() {
        HandlePolicyHandler handler = new HandlePolicyHandler();
        ConsumerConfiguration consumer = ConsumerConfiguration.builder().name("test")
                .onMissingProtectedData(MissingProtectedDataPolicy.SKIP).build();

        invokeWithMissingProtectedData(handler, MissingProtectedDataPolicy.FAIL, consumer);

        assertTrue(handler.isInvoked());
    }

    @Test
    void applicationPolicyCanBeConfiguredByProperty() {
        DefaultPolicyConsumer handler = new DefaultPolicyConsumer();
        TestFixture.createAsync(DefaultFluxzero.builder().replacePropertySource(existing ->
                                new SimplePropertySource(Map.of(
                                        MissingProtectedDataPolicy.PROPERTY, "SKIP")).andThen(existing)),
                        handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(
                        new SomeEvent(null), missingProtectedDataMetadata()))
                .expectThat(fc -> assertFalse(handler.isInvoked()))
                .<IgnoreMessageEvent>expectMetric(event ->
                        event.getReason().equals(IgnoreMessageEvent.MISSING_PROTECTED_DATA))
                .expectNoMetricsLike(HandleMessageEvent.class);
    }

    @Test
    void consumerAnnotationOverridesApplicationPolicyDuringTracking() {
        SkipPolicyConsumer handler = new SkipPolicyConsumer();
        TestFixture.createAsync(DefaultFluxzero.builder()
                                        .onMissingProtectedData(MissingProtectedDataPolicy.FAIL),
                                handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(
                        new SomeEvent(null), missingProtectedDataMetadata()))
                .expectThat(fc -> assertFalse(handler.isInvoked()));
    }

    @Test
    void builderPolicyOverridesConfiguredProperty() {
        DefaultPolicyConsumer handler = new DefaultPolicyConsumer();
        TestFixture.createAsync(DefaultFluxzero.builder().replacePropertySource(existing ->
                                        new SimplePropertySource(Map.of(
                                                MissingProtectedDataPolicy.PROPERTY, "SKIP")).andThen(existing))
                                .onMissingProtectedData(MissingProtectedDataPolicy.HANDLE),
                        handler)
                .whenExecuting(fc -> Fluxzero.publishEvent(
                        new SomeEvent(null), missingProtectedDataMetadata()))
                .expectThat(fc -> assertTrue(handler.isInvoked()));
    }

    private HandlerInvoker invokeWithMissingProtectedData(Object target, MissingProtectedDataPolicy applicationPolicy,
                                                          ConsumerConfiguration consumerConfiguration) {
        KeyValueStore keyValueStore = mock(KeyValueStore.class);
        when(keyValueStore.forNamespace(nullable(String.class))).thenReturn(keyValueStore);
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                target, HandleEvent.class, List.of(new PayloadParameterResolver()));
        Handler<DeserializingMessage> wrapped = new DataProtectionInterceptor(
                keyValueStore, null, applicationPolicy).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new SomeEvent(null), missingProtectedDataMetadata()),
                MessageType.EVENT, null);
        if (consumerConfiguration != null) {
            message.putContext(ConsumerConfiguration.class, consumerConfiguration);
        }
        HandlerInvoker invoker = wrapped.getInvokerOrNull(message);
        invoker.invoke();
        return invoker;
    }

    private Metadata missingProtectedDataMetadata() {
        return Metadata.of(DataProtectionInterceptor.METADATA_KEY,
                           Map.of("sensitiveData", "missing-key"),
                           DataProtectionInterceptor.NAMESPACE_METADATA_KEY, "public");
    }

    @Value
    @Builder(toBuilder = true)
    private static class SomeEvent {
        @ProtectData
        String sensitiveData;
    }

    @Value
    @Builder(toBuilder = true)
    private static class ConstrainedCommand {
        @ProtectData
        @NotNull
        String sensitiveData;
    }

    @Value
    @Builder(toBuilder = true)
    private static class NestedEvent {
        @ProtectData
        SensitiveDetails details;
    }

    @Value
    @Builder(toBuilder = true)
    private static class SensitiveDetails {
        @ProtectData
        String sensitiveData;
        String publicData;
    }

    @Value
    @Builder(toBuilder = true)
    private static class WholeObjectEvent {
        @ProtectData
        WholeProtectedDetails details;
    }

    @Value
    @Builder(toBuilder = true)
    @ProtectData
    private static class WholeProtectedDetails {
        String sensitiveData;
        String publicData;
    }

    @Value
    @Builder(toBuilder = true)
    private static class ProtectedIdEvent {
        @ProtectData
        ProtectedId protectedId;
    }

    private static class ProtectedId extends Id<Object> {
        private ProtectedId(String functionalId) {
            super(functionalId, Object.class);
        }
    }

    @Value
    @Builder(toBuilder = true)
    private static class ProtectedDataEvent {
        @ProtectData
        Data<String> protectedData;
    }

    @Value
    @Builder(toBuilder = true)
    private static class ProtectedJsonNodeEvent {
        @ProtectData
        JsonNode protectedJson;
    }

    private record ProtectedRecordEvent(@ProtectData String secret, String visible) {
    }

    private record ProtectedNestedRecordEvent(@ProtectData ProtectedSensitiveDetails details) {
    }

    private record ProtectedSensitiveDetails(@ProtectData String socialSecurityNumber, String displayName) {
    }

    @Getter
    @Consumer(name = "data-protection")
    private static class SomeHandler {
        private SomeEvent lastEvent;
        private Metadata lastMetadata;
        private Data<byte[]> data;

        @HandleEvent
        private void handler(SomeEvent event, DeserializingMessage message) {
            lastEvent = event.toBuilder().build();
            lastMetadata = message.getMetadata();
            data = message.getSerializedObject().getData();
        }
    }

    @Getter
    private static class ValidatingHandler {
        private ConstrainedCommand lastCommand;

        @HandleCommand
        private void handler(ConstrainedCommand command) {
            lastCommand = command;
        }
    }

    @Getter
    @Consumer(name = "data-protection")
    private static class DroppingHandler {
        private SomeEvent lastEvent;

        @HandleEvent
        @DropProtectedData
        private void handler(SomeEvent event) {
            lastEvent = event.toBuilder().build();
        }
    }

    @Getter
    private static class NestedHandler {
        private NestedEvent lastEvent;
        private Metadata lastMetadata;

        @HandleEvent
        private void handler(NestedEvent event, DeserializingMessage message) {
            lastEvent = event.toBuilder().build();
            lastMetadata = message.getMetadata();
        }
    }

    @Getter
    private static class WholeObjectHandler {
        private WholeObjectEvent lastEvent;
        private Metadata lastMetadata;

        @HandleEvent
        private void handler(WholeObjectEvent event, DeserializingMessage message) {
            lastEvent = event.toBuilder().build();
            lastMetadata = message.getMetadata();
        }
    }

    @Getter
    private static class IdHandler {
        private ProtectedIdEvent lastEvent;
        private Metadata lastMetadata;
        private Data<byte[]> data;

        @HandleEvent
        private void handler(ProtectedIdEvent event, DeserializingMessage message) {
            lastEvent = event.toBuilder().build();
            lastMetadata = message.getMetadata();
            data = message.getSerializedObject().getData();
        }
    }

    @Getter
    private static class DataHandler {
        private ProtectedDataEvent lastEvent;
        private Metadata lastMetadata;

        @HandleEvent
        private void handler(ProtectedDataEvent event, DeserializingMessage message) {
            lastEvent = event.toBuilder().build();
            lastMetadata = message.getMetadata();
        }
    }

    @Getter
    private static class JsonNodeHandler {
        private ProtectedJsonNodeEvent lastEvent;
        private Metadata lastMetadata;

        @HandleEvent
        private void handler(ProtectedJsonNodeEvent event, DeserializingMessage message) {
            lastEvent = event.toBuilder().build();
            lastMetadata = message.getMetadata();
        }
    }

    @Getter
    private static class RecordHandler {
        private ProtectedRecordEvent lastEvent;
        private Metadata lastMetadata;

        @HandleEvent
        private void handler(ProtectedRecordEvent event, DeserializingMessage message) {
            lastEvent = event;
            lastMetadata = message.getMetadata();
        }
    }

    @Getter
    private static class NestedRecordHandler {
        private ProtectedNestedRecordEvent lastEvent;
        private Metadata lastMetadata;

        @HandleEvent
        private void handler(ProtectedNestedRecordEvent event, DeserializingMessage message) {
            lastEvent = event;
            lastMetadata = message.getMetadata();
        }
    }

    @Getter
    private static class DefaultPolicyHandler {
        private boolean invoked;
        private SomeEvent lastEvent;

        @HandleEvent
        private void handler(SomeEvent event) {
            invoked = true;
            lastEvent = event;
        }
    }

    @Getter
    private static class HandlePolicyHandler {
        private boolean invoked;

        @HandleEvent(onMissingProtectedData = MissingProtectedDataPolicy.HANDLE)
        private void handler(SomeEvent event) {
            invoked = true;
        }
    }

    @Getter
    @Consumer(name = "default-missing-protected-data-policy")
    private static class DefaultPolicyConsumer {
        private boolean invoked;

        @HandleEvent
        private void handler(SomeEvent event) {
            invoked = true;
        }
    }

    @Getter
    @Consumer(name = "skip-missing-protected-data",
            onMissingProtectedData = MissingProtectedDataPolicy.SKIP)
    private static class SkipPolicyConsumer {
        private boolean invoked;

        @HandleEvent
        private void handler(SomeEvent event) {
            invoked = true;
        }
    }

}
