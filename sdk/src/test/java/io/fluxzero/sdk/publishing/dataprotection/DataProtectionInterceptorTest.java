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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProtectionInterceptorTest {
    private final TestFixture testFixture = TestFixture.createAsync();

    @Test
    void testSerializedMessageDoesNotContainData() {
        testFixture.registerHandlers(new SomeHandler())
                .whenExecuting(fc -> Fluxzero.publishEvent(new SomeEvent("something super secret")))
                .expectEvents(new SomeEvent(null));
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

}
