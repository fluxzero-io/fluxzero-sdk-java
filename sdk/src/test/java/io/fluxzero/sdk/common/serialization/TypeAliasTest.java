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

package io.fluxzero.sdk.common.serialization;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeAliasTest {
    private static final String CURRENT_PACKAGE = "io.fluxzero.sdk.common.serialization";
    private static final String CURRENT_TYPE = CURRENT_PACKAGE + ".TypeAliasTest$CurrentType";
    private static final String LEGACY_PACKAGE = "host.example";
    private static final String LEGACY_TYPE = LEGACY_PACKAGE + ".TypeAliasTest$CurrentType";

    @Test
    void deserializesUsingPackageAlias() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);

        CurrentType result = serializer.deserialize(data(LEGACY_TYPE, 1, "{\"value\":\"test\"}"));

        assertEquals(new CurrentType("test"), result);
    }

    @Test
    void appliesPackageAliasToNestedClassMetadata() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);

        Container result = serializer.deserialize(data(Container.class.getName(), 0, """
                {"value":{"@class":"%s.TypeAliasTest$NestedType","value":"test"}}
                """.formatted(LEGACY_PACKAGE)));

        assertEquals(new NestedType("test"), result.value());
    }

    @Test
    void nestedClassMetadataStillUsesTheOriginalPathWhenAliasesDoNotMatch() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias("other.legacy", "other.current");

        Container result = serializer.deserialize(data(Container.class.getName(), 0, """
                {"value":{"@class":"%s","value":"test"}}
                """.formatted(NestedType.class.getName())));

        assertEquals(new NestedType("test"), result.value());
    }

    @Test
    void nestedClassMetadataUsesRegisteredSimpleNameWithoutAliases() {
        JacksonSerializer serializer = new JacksonSerializer();

        Container result = serializer.deserialize(data(Container.class.getName(), 0, """
                {"value":{"@class":"NestedType","value":"test"}}
                """));

        assertEquals(new NestedType("test"), result.value());
    }

    @Test
    void appliesPackageAliasToClassMetadataInsideMessageMetadata() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);
        Metadata metadata = Metadata.of("typedValue", """
                {"@class":"%s.TypeAliasTest$NestedType","value":"test"}
                """.formatted(LEGACY_PACKAGE));
        SerializedMessage serializedMessage = new SerializedMessage(
                data(CURRENT_TYPE, 1, "{\"value\":\"payload\"}"), metadata, "message-id", 0L);

        Metadata result = serializer.deserializeMessage(serializedMessage, MessageType.EVENT).getMetadata();

        assertEquals(new NestedType("test"), result.get("typedValue", NestedValue.class));
        assertEquals(new NestedType("test"), result.with("other", "value")
                .get("typedValue", NestedValue.class));
        assertEquals(metadata.get("typedValue"), result.get("typedValue"));
    }

    @Test
    void compactMetadataRetainsAliasesAcrossMutationsWithoutChangingWireValues() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);
        String json = """
                {"@class":"%s.TypeAliasTest$NestedType","value":"test"}
                """.formatted(LEGACY_PACKAGE);
        Metadata compact = Metadata.builder(2).put("typedValue", json).put("remove", "value").build();
        for (Metadata original : List.of(compact, Metadata.fromData(compact.toData()))) {
            for (UnaryOperator<Metadata> mutation : List.<UnaryOperator<Metadata>>of(
                    m -> m, m -> m.with("other", "value"), m -> m.with(Map.of("other", "value")),
                    m -> m.with(Metadata.builder(1).put("other", "value").build()),
                    m -> m.with("one", "1", "two", "2"), m -> m.withNull("other"),
                    m -> m.withTrace("other", "value"), m -> m.without("remove"),
                    m -> m.withoutIf("remove"::equals), m -> Metadata.empty().with(m),
                    m -> Metadata.of("other", "value").with(m), m -> m.with(Metadata.empty()))) {
                Metadata mapped = original.withClassNameMapper(serializer::resolveTypeName);
                assertSame(original.toData(), mapped.toData());
                Metadata result = mutation.apply(mapped);
                assertEquals(new NestedType("test"), result.get("typedValue", NestedValue.class));
                assertEquals(json, result.get("typedValue"));
                assertEquals(json, Metadata.fromData(result.toData()).get("typedValue"));
            }
            Metadata receiver = Metadata.empty().withClassNameMapper(
                    type -> NestedType.class.getName());
            Metadata conflicting = original.withClassNameMapper(type -> "nonexistent.Type");
            assertEquals(new NestedType("test"), receiver.with(conflicting)
                    .get("typedValue", NestedValue.class));
            assertEquals(original, original.withClassNameMapper(serializer::resolveTypeName));
        }
    }

    @Test
    void attachingMetadataAliasResolverDoesNotMaterializeSerializedMetadata() {
        AtomicInteger reads = new AtomicInteger();
        byte[] bytes = Metadata.of("key", "value").toData().getValue();
        Data<byte[]> data = new Data<>(() -> {
            reads.incrementAndGet();
            return bytes;
        }, Metadata.DATA_TYPE, 0, Metadata.DATA_FORMAT);

        Metadata mapped = Metadata.fromData(data).withClassNameMapper(type -> type);

        assertSame(data, mapped.toData());
        assertEquals(0, reads.get());
        assertEquals("value", mapped.get("key"));
    }

    @Test
    void resolvesNestedAliasesAndRegisteredNamesInsideByteRangesWithoutMaterialization() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);
        for (String type : List.of(LEGACY_PACKAGE + ".TypeAliasTest$NestedType", "NestedType")) {
            byte[] json = """
                    {"value":{"@class":"%s","value":"test"}}
                    """.formatted(type).getBytes(UTF_8);
            byte[] source = new byte[json.length + 4];
            System.arraycopy(json, 0, source, 2, json.length);
            Data.ByteArrayView view = new Data.ByteArrayView() {
                @Override
                public byte[] array() {
                    return source;
                }

                @Override
                public int offset() {
                    return 2;
                }

                @Override
                public int length() {
                    return json.length;
                }

                @Override
                public byte[] get() {
                    throw new AssertionError("Byte range was materialized");
                }
            };

            Container result = serializer.deserialize(new Data<>(view, Container.class.getName(), 0, Data.JSON_FORMAT));

            assertEquals(new NestedType("test"), result.value());
        }
    }

    @Test
    void registeredNamesWithCanonicalAliasesAgreeAcrossDirectStreamAndReplayPaths() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerTypeAlias(NestedType.class.getName(), CURRENT_TYPE);
        for (String type : List.of("NestedType", "TypeAliasTest$NestedType", NestedType.class.getName())) {
            Data<byte[]> input = data(type, 1, "{\"value\":\"test\"}");
            CurrentType expected = new CurrentType("test");

            assertEquals(expected, serializer.deserialize(input));
            assertEquals(expected, serializer.deserialize(Stream.of(input), UnknownTypeStrategy.FAIL)
                    .findFirst().orElseThrow().getPayload());
            assertEquals(CurrentType.class, serializer.serializedClassWithoutUpcasting(input));
            assertEquals(expected, serializer.deserializeFirstMessageOrNull(
                    new SerializedMessage(input, Metadata.empty(), "message-id", 0L), MessageType.EVENT, null)
                    .getPayload());
        }
    }

    @Test
    void nestedTypeIdentifiersSurviveWordBoundariesAndUnicodeEscapes() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);
        serializer.registerTypeAlias("old", NestedType.class.getName());
        for (int offset = 0; offset < 8; offset++) {
            for (int padding = 0; padding < 16; padding++) {
                for (String property : List.of("@class", "\\u0040class", "@cl\\u0061ss")) {
                    String json = "{\"value\":{" + " ".repeat(padding) + "\"" + property + "\":\""
                            + LEGACY_PACKAGE + ".TypeAliasTest$NestedType\",\"value\":\"test\"}}";

                    Container result = serializer.deserialize(byteRange(Container.class.getName(), json, offset));

                    assertEquals(new NestedType("test"), result.value());

                    String compact = "{" + " ".repeat(padding) + "\"" + property
                            + "\":\"old\",\"value\":\"test\"}";
                    assertEquals(new NestedType("test"), serializer.deserialize(
                            byteRange(NestedType.class.getName(), compact, offset)));
                }
            }
            for (String value : List.of("test", "é漢字", "mail@example.org", "@class", "a\\b", "")) {
                String json = serializer.getObjectMapper().createObjectNode().put("value", value).toString();
                assertEquals(new CurrentType(value.isEmpty() ? null : value),
                             serializer.deserialize(byteRange(CURRENT_TYPE, json, offset)));
            }
        }
    }

    @Test
    void markerFreePayloadsRetainCustomMapperByteArrayOverloads() {
        AtomicInteger wholeReads = new AtomicInteger();
        AtomicInteger rangeReads = new AtomicInteger();
        JsonMapper mapper = new CountingJsonMapper(wholeReads, rangeReads);
        JacksonSerializer serializer = new JacksonSerializer(mapper);
        String value = "x".repeat(256);
        String json = "{\"value\":\"" + value + "\"}";

        assertEquals(new CurrentType(value), serializer.deserialize(data(CURRENT_TYPE, 1, json)));
        assertEquals(new CurrentType(value), serializer.deserialize(byteRange(CURRENT_TYPE, json, 3)));
        assertEquals(1, wholeReads.get());
        assertEquals(1, rangeReads.get());
    }

    private static class CountingJsonMapper extends JsonMapper {
        private final AtomicInteger wholeReads;
        private final AtomicInteger rangeReads;

        private CountingJsonMapper(AtomicInteger wholeReads, AtomicInteger rangeReads) {
            this.wholeReads = wholeReads;
            this.rangeReads = rangeReads;
        }

        private CountingJsonMapper(CountingJsonMapper source) {
            super(source);
            wholeReads = source.wholeReads;
            rangeReads = source.rangeReads;
        }

        @Override
        public JsonMapper copy() {
            return new CountingJsonMapper(this);
        }

        @Override
        public <T> T readValue(byte[] src, JavaType type) throws IOException {
            wholeReads.incrementAndGet();
            return super.readValue(src, type);
        }

        @Override
        public <T> T readValue(byte[] src, int offset, int length, JavaType type) throws IOException {
            rangeReads.incrementAndGet();
            return super.readValue(src, offset, length, type);
        }
    }

    private static Data<byte[]> byteRange(String type, String json, int offset) {
        byte[] bytes = json.getBytes(UTF_8);
        byte[] source = new byte[offset + bytes.length + 8];
        System.arraycopy(bytes, 0, source, offset, bytes.length);
        return new Data<>(new Data.ByteArrayView() {
            @Override
            public byte[] array() {
                return source;
            }

            @Override
            public int offset() {
                return offset;
            }

            @Override
            public int length() {
                return bytes.length;
            }

            @Override
            public byte[] get() {
                throw new AssertionError("Byte range was materialized");
            }
        }, type, 1, Data.JSON_FORMAT);
    }

    @Test
    void supportsMultipleExactAndPackageAliases() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerTypeCaster("legacy.First", "intermediate.First");
        serializer.registerTypeAlias("intermediate.First", CURRENT_TYPE);
        serializer.registerPackageAlias("legacy.one", CURRENT_PACKAGE);
        serializer.registerPackageAlias("legacy.two", CURRENT_PACKAGE);

        assertEquals(CURRENT_TYPE, serializer.upcastType("legacy.First"));
        assertEquals(CURRENT_TYPE + "$Nested", serializer.upcastType("legacy.one.TypeAliasTest$CurrentType$Nested"));
        assertEquals(CURRENT_TYPE, serializer.upcastType("legacy.two.TypeAliasTest$CurrentType"));
    }

    @Test
    void resolvesAliasTargetThroughRegisteredTypeName() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.NestedType", "NestedType");

        assertEquals(NestedType.class.getName(), serializer.resolveTypeName("legacy.NestedType"));
    }

    @Test
    void leavesCanonicalGenericTypeNameUnchanged() {
        JacksonSerializer serializer = new JacksonSerializer();
        String type = "java.util.List<java.lang.String>";

        assertSame(type, serializer.resolveTypeName(type));
    }

    @Test
    void exactAliasTakesPrecedenceOverPackageAlias() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, "wrong.package");
        serializer.registerTypeAlias(LEGACY_TYPE, CURRENT_TYPE);

        assertEquals(CURRENT_TYPE, serializer.upcastType(LEGACY_TYPE));
    }

    @Test
    void longestPackageAliasTakesPrecedence() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias("host", "wrong.package");
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);

        assertEquals(CURRENT_TYPE, serializer.upcastType(LEGACY_TYPE));
    }

    @Test
    void packageAliasUsesPackageBoundaryAndReturnsUnmatchedInstance() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);
        String unmatched = new String("host.examples.SomeType");

        assertSame(unmatched, serializer.upcastType(unmatched));
    }

    @Test
    void exactAndPackageAliasesCanBeChained() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.CurrentType", LEGACY_TYPE);
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);

        assertEquals(CURRENT_TYPE, serializer.upcastType("legacy.CurrentType"));
    }

    @Test
    void aliasesAreAppliedAfterContentUpcasting() {
        JacksonSerializer serializer = new JacksonSerializer(List.of(new LegacyUpcaster()));
        serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);

        CurrentType result = serializer.deserialize(data(LEGACY_TYPE, 0, "{\"oldValue\":\"test\"}"));

        assertEquals(new CurrentType("test"), result);
    }

    @Test
    void resolvesRegisteredSimpleTypeBeforeContentUpcasting() {
        JacksonSerializer serializer = new JacksonSerializer(List.of(new CurrentTypeUpcaster()));

        CurrentType result = serializer.deserialize(data("CurrentType", 0, "{\"oldValue\":\"test\"}"));

        assertEquals(new CurrentType("test"), result);
    }

    @Test
    void cancellingRegistrationRemovesPackageAlias() {
        JacksonSerializer serializer = new JacksonSerializer();
        Registration registration = serializer.registerPackageAlias(LEGACY_PACKAGE, CURRENT_PACKAGE);
        assertEquals(CURRENT_TYPE, serializer.upcastType(LEGACY_TYPE));

        registration.cancel();
        registration.cancel();

        assertEquals(LEGACY_TYPE, serializer.upcastType(LEGACY_TYPE));
    }

    @Test
    void rejectsExactAliasCycleWithoutDamagingExistingAliases() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.One", "legacy.Two");

        assertThrows(IllegalArgumentException.class,
                     () -> serializer.registerTypeAlias("legacy.Two", "legacy.One"));

        assertEquals("legacy.Two", serializer.upcastType("legacy.One"));
    }

    @Test
    void rejectsExpandingPackageAliasCycle() {
        JacksonSerializer serializer = new JacksonSerializer();

        assertThrows(IllegalArgumentException.class,
                     () -> serializer.registerPackageAlias("host.example", "host.example.moved"));
    }

    @Test
    void rejectsPackageAliasCycleWithoutDamagingExistingAliases() {
        JacksonSerializer serializer = new JacksonSerializer();
        serializer.registerPackageAlias("host.one", "host.two");

        assertThrows(IllegalArgumentException.class,
                     () -> serializer.registerPackageAlias("host.two", "host.one"));

        assertEquals("host.two.Type", serializer.upcastType("host.one.Type"));
    }

    private static Data<byte[]> data(String type, int revision, String json) {
        return new Data<>(json.getBytes(UTF_8), type, revision, Data.JSON_FORMAT);
    }

    @RegisterType
    @Revision(1)
    private record CurrentType(String value) {
    }

    private record Container(NestedValue value) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private interface NestedValue {
    }

    @RegisterType
    private record NestedType(String value) implements NestedValue {
    }

    private static class LegacyUpcaster {
        @Upcast(type = LEGACY_TYPE, revision = 0)
        ObjectNode upcast(ObjectNode input) {
            return input.set("value", input.remove("oldValue"));
        }
    }

    private static class CurrentTypeUpcaster {
        @Upcast(type = CURRENT_TYPE, revision = 0)
        ObjectNode upcast(ObjectNode input) {
            return input.set("value", input.remove("oldValue"));
        }
    }
}
