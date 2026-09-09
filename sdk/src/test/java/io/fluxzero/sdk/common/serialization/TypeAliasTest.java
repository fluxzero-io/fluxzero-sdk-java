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

import java.util.List;

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
