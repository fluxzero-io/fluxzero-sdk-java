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
package io.fluxzero.sdk.publishing.dataprotection;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.util.NameTransformer;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.keyvalue.KeyValueStore;
import io.fluxzero.sdk.publishing.AdhocDispatchInterceptor;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.METADATA_KEY;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.NAMESPACE_METADATA_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Isolated
class ModelEventProtectionTest {
    private final JacksonSerializer serializer = new JacksonSerializer();
    private final Map<String, Map<String, Object>> values = new HashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final KeyValueStore store = mock(KeyValueStore.class);
    private final DataProtectionInterceptor protection = new DataProtectionInterceptor(store, serializer);

    ModelEventProtectionTest() {
        when(store.forNamespace(nullable(String.class))).thenAnswer(invocation -> {
            String namespace = invocation.getArgument(0);
            Map<String, Object> contents = values.computeIfAbsent(namespace, ignored -> new HashMap<>());
            KeyValueStore view = mock(KeyValueStore.class);
            doAnswer(call -> {
                contents.put(call.getArgument(0), call.getArgument(1));
                writes.incrementAndGet();
                return null;
            }).when(view).store(anyString(), any(), any());
            when(view.get(anyString())).thenAnswer(call -> contents.get(call.getArgument(0)));
            doAnswer(call -> contents.remove(call.getArgument(0))).when(view).delete(anyString());
            return view;
        });
    }

    @Test
    void unchangedCopiesReuseReferencesAndKeepNonProtectedChanges() {
        for (boolean tracked : List.of(false, true)) {
            DeserializingMessage source = restore(new Secret("secret", 1), false, tracked, "tenant");
            DeserializingMessage copy = source.withPayload(new Secret("secret", 2))
                    .withMetadata(source.getMetadata().with("other", "metadata"));
            int before = writes.get();
            SerializedMessage result = prepare(copy);
            assertEquals(new Secret(null, 2), serializer.deserialize(result.getData()));
            assertEquals(source.getMetadata().get(METADATA_KEY), result.getMetadata().get(METADATA_KEY));
            assertEquals("metadata", result.getMetadata().get("other"));
            assertEquals("secret", storedValue(result));
            assertEquals(before, writes.get());
        }
    }

    @Test
    void changedAndClearedValuesHaveReplayCorrectReferences() {
        DeserializingMessage source = restore(new Secret("original", 1), false, false, null);
        DeserializingMessage changed = source.withPayload(new Secret("changed", 2));
        SerializedMessage result = prepare(changed);
        assertEquals(new Secret(null, 2), serializer.deserialize(result.getData()));
        assertEquals("changed", storedValue(result));
        assertNotEquals(source.getMetadata().get(METADATA_KEY), result.getMetadata().get(METADATA_KEY));
        assertEquals("original", values.get(null).get(reference(source.getMetadata())));
        SerializedMessage cleared = prepare(source.withPayload(new Secret(null, 3)));
        assertFalse(cleared.getMetadata().containsKey(METADATA_KEY));
        assertEquals(new Secret(null, 3), serializer.deserialize(cleared.getData()));
    }

    @Test
    void droppedAndMissingValuesAreNotRecreatedByCopies() {
        DeserializingMessage dropped = restore(new Secret("secret", 1), true, false, null);
        assertTrue(values.get(null).isEmpty());
        int before = writes.get();
        SerializedMessage result = prepare(dropped.withPayload(new Secret("secret", 2)));
        assertEquals(dropped.getMetadata().get(METADATA_KEY), result.getMetadata().get(METADATA_KEY));
        assertNull(storedValue(result));
        assertEquals(before, writes.get());
        DeserializingMessage missing = restored(new DeserializingMessage(
                new Message(new Secret(null, 1), dropped.getMetadata()), COMMAND, serializer), false);
        SerializedMessage absent = prepare(missing.withPayload(new Secret(null, 2)));
        assertEquals(dropped.getMetadata().get(METADATA_KEY), absent.getMetadata().get(METADATA_KEY));
        assertEquals(before, writes.get());
    }

    @Test
    void explicitMessageOutputRetainsDropProvenance() {
        DeserializingMessage source = restore(new Secret("secret", 1), true, false, null);
        DeserializingMessage emitted = new DeserializingMessage(
                new Message(new Secret("secret", 2), source.getMetadata()), COMMAND, serializer);
        DataProtectionInterceptor.preserveRestoredDataContext(source, emitted);
        assertNull(storedValue(prepare(emitted)));
        assertEquals(1, writes.get());
    }

    @Test
    void mutableProtectedValuesAreSnapshottedBeforeHandling() {
        DeserializingMessage source = restore(new MutableSecret(new ArrayList<>(List.of("old"))), false, false, null);
        source.<MutableSecret>getPayload().value().add("new");
        SerializedMessage result = prepare(source);
        assertEquals(List.of("old", "new"), storedValue(result));
        assertTrue(serializer.deserialize(result.getData(), JsonNode.class).get("value").isNull());
        assertEquals(2, writes.get());
    }

    @Test
    void rawUpdatesPrepareOnceAndSplitUpdatesDoNotSharePreparedPayloads() {
        DeserializingMessage raw = new DeserializingMessage(new Message(new Secret("one", 1)), COMMAND, serializer);
        SerializedMessage first = prepare(raw);
        assertEquals("one", storedValue(first));
        assertEquals(first, prepare(raw));
        assertEquals(1, writes.get());
        DeserializingMessage split = raw.withPayload(new Secret("two", 2));
        SerializedMessage second = prepare(split);
        assertEquals("two", storedValue(second));
        assertNotEquals(first.getMetadata().get(METADATA_KEY), second.getMetadata().get(METADATA_KEY));
        assertEquals(first, prepare(raw));
        assertEquals(2, writes.get());
    }

    @Test
    void unprotectedOutputsLoseInheritedProtectionMetadata() {
        DeserializingMessage source = restore(new Secret("secret", 1), false, false, null);
        SerializedMessage result = prepare(source.withPayload(new PublicValue("public")));
        assertFalse(result.getMetadata().containsKey(METADATA_KEY));
        assertFalse(result.getMetadata().containsKey(NAMESPACE_METADATA_KEY));
        assertEquals(new PublicValue("public"), serializer.deserialize(result.getData()));
    }

    @Test
    void newProtectedTypesKeepUnprotectedFieldsWithoutInheritingOldReferences() {
        DeserializingMessage source = restore(new Secret("old-secret", 1), false, false, null);
        SerializedMessage result = prepare(source.withPayload(new DifferentSecret("public", "new-secret")));
        assertEquals(new DifferentSecret("public", null), serializer.deserialize(result.getData()));
        Map<?, ?> references = result.getMetadata().get(METADATA_KEY, Map.class);
        assertFalse(references.containsKey("value"));
        assertEquals("new-secret", values.get(null).get(references.get("other")));
    }

    @Test
    void laterRestorationCannotReplaceAnEarlierViewsDropProvenance() {
        Message sanitized = protection.interceptDispatch(new Message(new Secret("secret", 1)), COMMAND, null);
        DeserializingMessage original = new DeserializingMessage(sanitized, COMMAND, serializer);
        DeserializingMessage dropped = restored(original, true);
        assertNull(restored(original, false).<Secret>getPayload().value());
        assertNull(storedValue(prepare(dropped)));
        assertEquals(1, writes.get());
    }

    @Test
    void polymorphicNestedProtectionRetainsDroppedReferences() {
        DeserializingMessage source = restore(new NestedSecret(new SensitiveDetail("secret")), true, false, null);
        SerializedMessage result = prepare(source.withPayload(new NestedSecret(new SensitiveDetail("secret"))));
        Map<?, ?> references = result.getMetadata().get(METADATA_KEY, Map.class);
        assertTrue(references.containsKey("details/value"));
        assertTrue(values.get(null).isEmpty());
        assertEquals(1, writes.get());
        assertNull(serializer.<NestedSecret>deserialize(result.getData()).details().value());
    }

    @Test
    void replayRestoresOnlyRetainedDataAndDoesNotHideReadFailures() {
        DeserializingMessage restored = restore(new Secret("secret", 1), false, true, "tenant");
        SerializedMessage safe = prepare(restored);
        safe.setIndex(42L);
        DeserializingMessage event = serializer.deserializeMessage(safe, EVENT);
        DeserializingMessage replay = protection.restoreForReplay(event);
        assertEquals("secret", replay.<Secret>getPayload().value());
        assertSame(safe.getData(), replay.getSerializedObject().getData());
        assertEquals(42L, replay.getIndex());
        assertEquals(1, writes.get());
        values.get("tenant").clear();
        assertNull(protection.restoreForReplay(event).<Secret>getPayload().value());
        KeyValueStore failing = mock(KeyValueStore.class);
        when(failing.forNamespace("tenant")).thenReturn(failing);
        when(failing.get(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,
                     () -> new DataProtectionInterceptor(failing, serializer).restoreForReplay(event));
    }

    @Test
    void introducedProtectedPayloadAndUnknownSerializedPropertiesRemainSafe() {
        DeserializingMessage source = new DeserializingMessage(new Message(new PublicValue("public")), COMMAND, serializer);
        var data = serializer.serialize(new Secret("secret", 1));
        ObjectNode raw = serializer.deserialize(data, ObjectNode.class);
        raw.put("auditMarker", 42);
        var candidate = source.getSerializedObject().withData(data.map(ignored -> serializer.serialize(raw).getValue()));
        SerializedMessage result = protection.modifySerializedMessage(candidate, source, EVENT, null, null);
        JsonNode safe = serializer.deserialize(result.getData(), JsonNode.class);
        assertTrue(safe.get("value").isNull());
        assertEquals(42, safe.get("auditMarker").intValue());
        assertEquals(data.getType(), result.getData().getType());
        assertEquals("secret", storedValue(result));
    }

    @Test
    void recordAndPojoPropertyNamesAreRedactedAndRestoredUsingSerializerConfiguration() {
        for (Object payload : List.of(new AliasedSecret("secret"), new AliasedPojo("secret"),
                                     new SnakeSecret("secret"), new EscapedSecret("secret"))) {
            DeserializingMessage source = restore(payload, false, false, null);
            assertEquals(payload, source.getPayload());
            SerializedMessage result = prepare(source);
            JsonNode raw = serializer.deserialize(result.getData(), JsonNode.class);
            assertFalse(raw.toString().contains("\"secret\""));
            assertEquals(payload, protection.restoreForReplay(serializer.deserializeMessage(result, EVENT)).getPayload());
        }
    }

    @Test
    void serializedAliasesAreRedactedWithoutIntroducingDuplicateRecordProperties() {
        DeserializingMessage source = restore(new AliasedSecret("secret"), false, false, null);
        ObjectNode raw = serializer.convert(source.getPayload(), ObjectNode.class);
        raw.remove("wireValue");
        raw.put("oldValue", "secret");
        var data = source.getSerializedObject().getData();
        SerializedMessage candidate = source.getSerializedObject().withData(data.map(ignored -> serializer.serialize(raw).getValue()));
        SerializedMessage result = protection.modifySerializedMessage(candidate, source, EVENT, null, null);
        JsonNode safe = serializer.deserialize(result.getData(), JsonNode.class);
        assertFalse(safe.has("wireValue"));
        assertTrue(safe.get("oldValue").isNull());
        assertEquals(new AliasedSecret(null), serializer.deserialize(result.getData()));
    }

    @Test
    void precedingSerializedEditsAndLegacyOverridesArePreserved() {
        AtomicInteger legacyCalls = new AtomicInteger();
        DataProtectionInterceptor customized = new DataProtectionInterceptor(store, serializer) {
            @Override
            public SerializedMessage modifySerializedMessage(SerializedMessage candidate, Message message,
                                                             MessageType type, String topic) {
                legacyCalls.incrementAndGet();
                return super.modifySerializedMessage(candidate, message, type, topic)
                        .withMetadata(candidate.getMetadata().with("legacy", "called"));
            }
        };
        DispatchInterceptor preceding = new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, MessageType type, String topic) {
                return message;
            }

            @Override
            public SerializedMessage modifySerializedMessage(SerializedMessage candidate, Message message,
                                                             MessageType type, String topic) {
                Secret payload = serializer.deserialize(candidate.getData());
                return candidate.withData(serializer.serialize(new Secret(payload.value(), 42))).withSegment(123);
            }
        };
        DeserializingMessage source = restore(new Secret("secret", 1), false, false, null);
        SerializedMessage result = preceding.andThen(customized).withNamespace("tenant")
                .modifySerializedMessage(source.getSerializedObject(), source, EVENT, null, null);
        assertEquals(new Secret(null, 42), serializer.deserialize(result.getData()));
        assertEquals(123, result.getSegment());
        assertEquals("called", result.getMetadata().get("legacy"));
        assertEquals(1, legacyCalls.get());
        assertEquals(1, writes.get());
    }

    @Test
    void adhocSourceAwareInterceptorsReceiveTheSource() {
        DeserializingMessage source = new DeserializingMessage(new Message(new Secret("secret", 1)), COMMAND, serializer);
        SerializedMessage result = AdhocDispatchInterceptor.runWithAdhocInterceptor(
                () -> new AdhocDispatchInterceptor().modifySerializedMessage(
                        source.getSerializedObject(), source, EVENT, null, "tenant"), protection, EVENT);
        assertEquals("secret", storedValue(result));
        assertEquals("tenant", result.getMetadata().get(NAMESPACE_METADATA_KEY));
    }

    @Test
    void newProtectedOutputsUseTheConsumerNamespace() {
        DeserializingMessage source = ClientUtils.setConsumerNamespace(new DeserializingMessage(
                new Message(new Secret("secret", 1)), COMMAND, serializer), "tenant");
        SerializedMessage result = prepare(source);
        assertEquals("tenant", result.getMetadata().get(NAMESPACE_METADATA_KEY));
        assertEquals("secret", storedValue(result));
        assertFalse(values.containsKey(null));
    }

    @Test
    void customCodecsAndModifiedWritersCannotPublishUnmappedPrivateValues() {
        SimpleModule codec = new SimpleModule().addSerializer(CodecSecret.class, new JsonSerializer<>() {
            @Override
            public void serialize(CodecSecret value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeStartObject();
                generator.writeStringField("encodedvalue", value.value);
                generator.writeEndObject();
            }
        });
        SimpleModule modifier = new SimpleModule().setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(
                    com.fasterxml.jackson.databind.SerializationConfig config,
                    com.fasterxml.jackson.databind.BeanDescription description, List<BeanPropertyWriter> properties) {
                return properties.stream().map(property -> property.rename(
                        NameTransformer.simpleTransformer("encoded", ""))).toList();
            }
        });
        SimpleModule duplicate = new SimpleModule().setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(
                    com.fasterxml.jackson.databind.SerializationConfig config,
                    com.fasterxml.jackson.databind.BeanDescription description, List<BeanPropertyWriter> properties) {
                List<BeanPropertyWriter> result = new ArrayList<>(properties);
                properties.stream().map(property -> property.rename(NameTransformer.simpleTransformer("copy", "")))
                        .forEach(result::add);
                return result;
            }
        });
        for (SimpleModule module : List.of(codec, modifier, duplicate)) {
            var mapper = JacksonSerializer.defaultObjectMapper.copy();
            mapper.registerModule(module);
            JacksonSerializer customized = new JacksonSerializer(mapper);
            DataProtectionInterceptor interceptor = new DataProtectionInterceptor(store, customized);
            CodecSecret payload = new CodecSecret("secret");
            // Existing POJO dispatch remains safe: it clears the logical property before invoking the codec.
            Message sanitized = interceptor.interceptDispatch(new Message(payload), COMMAND, null);
            assertNull(sanitized.<CodecSecret>getPayload().value);
            int before = writes.get();
            DeserializingMessage source = new DeserializingMessage(new Message(payload), COMMAND, customized);
            assertThrows(UnsupportedOperationException.class, () -> interceptor.modifySerializedMessage(
                    source.getSerializedObject(), source, EVENT, null, null));
            assertEquals(before, writes.get());
        }
    }

    @Test
    void typeWrappersRootWrappingAndDynamicPropertiesRequireExplicitSafeMappings() {
        for (Object payload : List.of(new WrappedSecret("secret"), new NestedWrapper(new WrappedSecret("secret")),
                                     new DynamicSecret(Map.of("private", "secret")), new FilteredSecret("secret"))) {
            String path = payload instanceof NestedWrapper ? "details/value"
                    : payload instanceof DynamicSecret ? "secrets" : "value";
            assertThrows(UnsupportedOperationException.class, () -> serializer.serializedPropertyPaths(payload, path));
        }
        var mapper = JacksonSerializer.defaultObjectMapper.copy();
        mapper.enable(SerializationFeature.WRAP_ROOT_VALUE);
        JacksonSerializer wrapped = new JacksonSerializer(mapper);
        assertThrows(UnsupportedOperationException.class,
                     () -> wrapped.serializedPropertyPaths(new RootSecret("secret"), "value"));
    }

    @Test
    void standardNestedAndUnwrappedBeanMappingsRemainSupported() {
        assertEquals(List.of("details/value"),
                     serializer.serializedPropertyPaths(new FinalNestedSecret(new Secret("secret", 1)), "details/value"));
        assertEquals(List.of("private_value"), serializer.serializedPropertyPaths(
                new UnwrappedSecret(new Secret("secret", 1)), "details/value"));
    }

    @Test
    void derivedBeanPropertiesCannotCopySecretsIntoStoredEvents() {
        for (Object payload : List.of(new DerivedPojo("secret"), new DerivedRecord("secret"))) {
            assertEquals("secret", serializer.<JsonNode>convert(payload, JsonNode.class).path("copy").textValue());
            DeserializingMessage source = restore(payload, false, false, null);
            SerializedMessage result = prepare(source);
            JsonNode raw = serializer.deserialize(result.getData(), JsonNode.class);
            assertTrue(raw.get("value").isNull());
            assertTrue(raw.path("copy").isMissingNode() || raw.path("copy").isNull());
            assertEquals("secret", storedValue(result));
            assertEquals(payload, protection.restoreForReplay(serializer.deserializeMessage(result, EVENT)).getPayload());
        }
        assertThrows(UnsupportedOperationException.class,
                     () -> serializer.serializedPropertyPaths(new IndirectPojo("secret"), "secret"));
    }

    @Test
    void unstableSerializationCannotSilentlyRewriteUnrelatedFields() {
        DeserializingMessage source = new DeserializingMessage(new Message(new UnstableSecret("secret")),
                                                               COMMAND, serializer);
        assertThrows(IllegalStateException.class, () -> prepare(source));
        assertEquals(0, writes.get());
    }

    @Test
    void alreadyRedactedBackingFieldsCannotRetainStaleDerivedSecrets() {
        for (String mode : List.of("retained", "cleared", "raw")) {
            int before = writes.get();
            DeserializingMessage source = mode.equals("raw")
                    ? new DeserializingMessage(new Message(new DerivedRecord("secret")), COMMAND, serializer)
                    : restore(new DerivedRecord("secret"), false, false, null);
            var data = source.getSerializedObject().getData();
            ObjectNode raw = serializer.deserialize(data, ObjectNode.class);
            raw.putNull("value");
            raw.put("auditMarker", "preserve");
            assertEquals("secret", raw.path("copy").textValue());
            if (mode.equals("cleared")) {
                source = source.withPayload(new DerivedRecord(null));
            }
            SerializedMessage candidate = source.getSerializedObject().withData(
                    data.map(ignored -> serializer.serialize(raw).getValue()));
            SerializedMessage result = protection.modifySerializedMessage(candidate, source, EVENT, null, null);
            JsonNode safe = serializer.deserialize(result.getData(), JsonNode.class);
            assertTrue(safe.path("copy").isMissingNode() || safe.path("copy").isNull());
            assertEquals("preserve", safe.path("auditMarker").textValue());
            assertEquals(mode.equals("retained"), result.getMetadata().containsKey(METADATA_KEY));
            assertEquals(before + (mode.equals("raw") ? 0 : 1), writes.get());
        }
    }

    private DeserializingMessage restore(Object payload, boolean drop, boolean tracked, String namespace) {
        Message sanitized = protection.interceptDispatch(new Message(payload), COMMAND, null, namespace);
        SerializedMessage serialized = sanitized.serialize(serializer);
        serialized.setIndex(123L);
        DeserializingMessage message = tracked
                ? serializer.deserializeMessage(serialized, COMMAND)
                : new DeserializingMessage(sanitized, COMMAND, serializer);
        return restored(message, drop);
    }

    private DeserializingMessage restored(DeserializingMessage message, boolean drop) {
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(drop ? new DroppingHandler() : new CapturingHandler(),
                HandleCommand.class, List.of(new MessageParameterResolver(), new PayloadParameterResolver()));
        return (DeserializingMessage) protection.wrap(handler).getInvokerOrNull(message).invoke();
    }

    private SerializedMessage prepare(DeserializingMessage source) {
        return protection.modifySerializedMessage(source.getSerializedObject(), source, EVENT, null, null);
    }

    private Object storedValue(SerializedMessage message) {
        String namespace = message.getMetadata().get(NAMESPACE_METADATA_KEY);
        return values.get(namespace == null || namespace.isEmpty() ? null : namespace)
                .get(reference(message.getMetadata()));
    }

    @SuppressWarnings("unchecked")
    private String reference(Metadata metadata) {
        return ((Map<String, String>) metadata.get(METADATA_KEY, Map.class)).get("value");
    }

    private record Secret(@ProtectData String value, int visible) {
    }

    private record MutableSecret(@ProtectData List<String> value) {
    }

    private record PublicValue(String value) {
    }

    private record DifferentSecret(String value, @ProtectData String other) {
    }

    private record NestedSecret(@ProtectData Detail details) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private interface Detail {
        String value();
    }

    private record SensitiveDetail(@ProtectData String value) implements Detail {
    }

    private record AliasedSecret(@ProtectData @JsonProperty("wireValue") @JsonAlias("oldValue") String value) {
    }

    @Value
    private static class AliasedPojo {
        @ProtectData
        @JsonProperty("wireValue")
        String value;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record SnakeSecret(@ProtectData String secretValue) {
    }

    private record EscapedSecret(@ProtectData @JsonProperty("private.value/~") String value) {
    }

    @Value
    private static class CodecSecret {
        @ProtectData
        @JsonAlias("encodedvalue")
        String value;
    }

    @Value
    private static class DerivedPojo {
        @ProtectData
        String value;

        @JsonProperty("copy")
        public String getCopy() {
            return value;
        }
    }

    private record DerivedRecord(@ProtectData String value) {
        @JsonProperty("copy")
        public String getCopy() {
            return value;
        }
    }

    private static class IndirectPojo {
        @ProtectData
        @JsonIgnore
        private final String secret;

        private IndirectPojo(String secret) {
            this.secret = secret;
        }

        @JsonProperty("value")
        public String getValue() {
            return secret;
        }
    }

    private record UnstableSecret(@ProtectData String value) {
        private static final AtomicInteger sequence = new AtomicInteger();

        @JsonProperty("nonce")
        public int getNonce() {
            return sequence.incrementAndGet();
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    private record WrappedSecret(@ProtectData String value) {
    }

    private record NestedWrapper(@ProtectData WrappedSecret details) {
    }

    private record FinalNestedSecret(@ProtectData Secret details) {
    }

    private record UnwrappedSecret(@ProtectData @JsonUnwrapped(prefix = "private_") Secret details) {
    }

    private record DynamicSecret(@ProtectData @JsonAnyGetter Map<String, String> secrets) {
    }

    @JsonRootName("protected")
    private record RootSecret(@ProtectData String value) {
    }

    @JsonFilter("custom")
    private record FilteredSecret(@ProtectData String value) {
    }

    private static class CapturingHandler {
        @HandleCommand
        DeserializingMessage handle(Object payload, DeserializingMessage message) {
            return message;
        }
    }

    private static class DroppingHandler {
        @HandleCommand
        @DropProtectedData
        DeserializingMessage handle(Object payload, DeserializingMessage message) {
            return message;
        }
    }
}
