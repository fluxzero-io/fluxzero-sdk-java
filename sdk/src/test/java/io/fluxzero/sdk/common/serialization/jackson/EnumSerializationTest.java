/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.common.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.casting.Downcast;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EnumSerializationTest {
    private static final String LEGACY_TYPE =
            "io.fluxzero.sdk.common.serialization.jackson.EnumSerializationTest$Choice$1";
    private final JacksonSerializer serializer = new JacksonSerializer();

    @Test
    void constantBodyUsesDeclaringEnumAndRoundTrips() throws Exception {
        Data<byte[]> data = serializer.serialize(Choice.SPECIAL);
        assertEquals(Choice.class.getName(), data.getType());
        assertSame(Choice.SPECIAL, serializer.deserialize(data));
        // A previous SDK/Jackson reader already understands the new envelope.
        assertSame(Choice.SPECIAL, serializer.getObjectMapper().readValue(
                data.getValue(), serializer.getObjectMapper().getTypeFactory().constructFromCanonical(data.getType())));
    }

    @Test
    void legacyEnvelopeWorksDirectlyAndLazily() {
        Data<byte[]> legacy = legacy(Choice.SPECIAL, LEGACY_TYPE);
        assertSame(Choice.SPECIAL, serializer.deserialize(legacy));
        assertSame(Choice.SPECIAL, serializer.deserialize(Stream.of(legacy), UnknownTypeStrategy.FAIL)
                .findFirst().orElseThrow().getPayload());
    }

    @Test
    void homogeneousContainersUseDeclaringEnumAndReadLegacyTypes() {
        var list = new ArrayList<>(List.of(Choice.SPECIAL));
        var map = new LinkedHashMap<>(Map.of(Choice.SPECIAL, Choice.SPECIAL));
        for (Object value : List.of(list, map)) {
            Data<byte[]> data = serializer.serialize(value);
            assertFalse(data.getType().contains(LEGACY_TYPE));
            assertEquals(value, serializer.deserialize(data));
            assertEquals(value, serializer.deserialize(new Data<>(data.getValue(),
                    data.getType().replace(Choice.class.getName(), LEGACY_TYPE), 0, Data.JSON_FORMAT)));
        }
    }

    @Test
    void ordinaryEnumsMixedConstantsAndNestedFieldsKeepWorking() {
        for (Object value : List.of(Choice.PLAIN, new Holder(Choice.SPECIAL),
                new ArrayList<>(List.of(Choice.PLAIN, Choice.SPECIAL)))) {
            assertEquals(value, serializer.deserialize(serializer.serialize(value)));
        }
        assertEquals(Choice.class.getName(), serializer.serialize(Choice.PLAIN).getType());
    }

    @Test
    void enumJsonValueAndCreatorArePreserved() {
        assertSame(Coded.SPECIAL, serializer.deserialize(serializer.serialize(Coded.SPECIAL)));
        assertSame(Coded.SPECIAL, serializer.deserialize(legacy(Coded.SPECIAL, Coded.SPECIAL.getClass().getName())));
    }

    @Test
    void legacyTypeUpcasterRunsBeforeEnumResolution() {
        JacksonSerializer subject = new JacksonSerializer(List.of(new LegacyCaster()));
        Data<byte[]> legacy = legacy(Choice.SPECIAL, LEGACY_TYPE);
        assertSame(Choice.PLAIN, subject.deserialize(legacy));
        assertSame(Choice.PLAIN, subject.deserialize(Stream.of(legacy), UnknownTypeStrategy.FAIL)
                .findFirst().orElseThrow().getPayload());
    }

    @Test
    void legacyTypeAliasesStillApply() {
        serializer.registerTypeAlias("old.Choice", LEGACY_TYPE);
        assertSame(Choice.SPECIAL, serializer.deserialize(legacy(Choice.SPECIAL, "old.Choice")));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void existingConstantSpecificDeserializerKeepsItsEnvelopeAndBehavior() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer((Class) Choice.SPECIAL.getClass(), new JsonDeserializer<Choice>() {
            @Override
            public Choice deserialize(JsonParser parser, DeserializationContext context) {
                return Choice.PLAIN;
            }
        });
        JsonMapper mapper = JsonMapper.builder().addModule(module).build();
        assertTrue(mapper.canDeserialize(mapper.constructType(Choice.SPECIAL.getClass())));
        JacksonSerializer subject = new JacksonSerializer(mapper);
        Data<byte[]> data = subject.serialize(Choice.SPECIAL);
        assertEquals(LEGACY_TYPE, data.getType());
        assertEquals(0, data.getRevision());
        assertSame(Choice.PLAIN, subject.deserialize(data));
    }

    @Test
    void customMapKeyDeserializerKeepsItsEnvelopeAndBehavior() {
        SimpleModule module = new SimpleModule();
        module.addKeyDeserializer(Choice.SPECIAL.getClass(), new KeyDeserializer() {
            @Override
            public Object deserializeKey(String key, DeserializationContext context) {
                return Choice.PLAIN;
            }
        });
        JacksonSerializer subject = new JacksonSerializer(JsonMapper.builder().addModule(module).build());
        for (Object value : List.of("value", Choice.SPECIAL)) {
            Data<byte[]> data = subject.serialize(new LinkedHashMap<>(Map.of(Choice.SPECIAL, value)));
            assertTrue(data.getType().contains(LEGACY_TYPE));
            assertEquals(Map.of(Choice.PLAIN, value), subject.deserialize(data));
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void customContainerDeserializerRetainsItsGenericType() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer((Class) ArrayList.class, new CustomListDeserializer());
        JacksonSerializer subject = new JacksonSerializer(JsonMapper.builder().addModule(module).build());
        Data<byte[]> data = subject.serialize(new ArrayList<>(List.of(Choice.SPECIAL)));
        assertTrue(data.getType().contains(LEGACY_TYPE));
        assertEquals(List.of(Choice.PLAIN), subject.deserialize(data));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void parserDependentCustomCodecKeepsItsOriginalRoute() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer((Class) Choice.SPECIAL.getClass(), new ParserDependentDeserializer());
        JacksonSerializer subject = new JacksonSerializer(JsonMapper.builder().addModule(module).build());
        Data<byte[]> data = subject.serialize(Choice.SPECIAL);
        assertEquals(LEGACY_TYPE, data.getType());
        assertEquals(0, data.getRevision());
        assertSame(Choice.PLAIN, subject.deserialize(data));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enumCommandAndResultWorkInSyncAndAsyncFixtures(boolean async) {
        TestFixture fixture = TestFixture.create(new ChoiceHandler());
        (async ? fixture.async() : fixture).whenCommand(Choice.SPECIAL).expectResult(Choice.SPECIAL);
    }

    @Test
    void declaringEnumModuleAndRevisionArePreserved() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Choice.class, new JsonDeserializer<Choice>() {
            @Override
            public Choice deserialize(JsonParser parser, DeserializationContext context) {
                return Choice.PLAIN;
            }
        });
        JacksonSerializer subject = new JacksonSerializer(JsonMapper.builder().addModule(module).build());
        assertSame(Choice.PLAIN, subject.deserialize(subject.serialize(Choice.SPECIAL)));
        assertEquals(serializer.serialize(Choice.PLAIN).getRevision(), serializer.serialize(Choice.SPECIAL).getRevision());
        assertEquals(2, serializer.serialize(Choice.SPECIAL).getRevision());
    }

    @Test
    void currentEnumRevisionSkipsOldUpcasterAndSupportsDowncasting() {
        JacksonSerializer subject = new JacksonSerializer(List.of(new CurrentEnumCaster()));
        assertSame(Choice.SPECIAL, subject.deserialize(subject.serialize(Choice.SPECIAL)));
        assertEquals(TextNode.valueOf("PLAIN"), subject.downcast(Choice.SPECIAL, 1));
    }

    @Test
    void enumSearchDocumentRoundTrips() {
        var document = serializer.toDocument(Choice.SPECIAL, "choice", "choices", null, null);
        assertEquals(2, document.getDocument().getRevision());
        assertSame(Choice.SPECIAL, serializer.fromDocument(document));
    }

    @Test
    void defaultJacksonCannotReadConstantSubclass() {
        assertFalse(serializer.getObjectMapper().canDeserialize(
                serializer.getObjectMapper().constructType(Choice.SPECIAL.getClass())));
    }

    private Data<byte[]> legacy(Object value, String type) {
        return new Data<>(serializer.serialize(value).getValue(), type, 0, Data.JSON_FORMAT);
    }

    @Revision(2)
    enum Choice {
        PLAIN,
        SPECIAL { @Override public String toString() { return "special"; } }
    }

    enum Coded {
        SPECIAL { @Override public String toString() { return "special"; } };
        @JsonValue public String code() { return "code"; }
        @JsonCreator public static Coded fromCode(String value) {
            return "code".equals(value) ? SPECIAL : null;
        }
    }

    record Holder(Choice choice) {}

    static class ChoiceHandler {
        @HandleCommand
        Choice handle(Choice choice) { return choice; }
    }

    static class ParserDependentDeserializer extends JsonDeserializer<Choice> implements ContextualDeserializer {
        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
                throws com.fasterxml.jackson.databind.JsonMappingException {
            if (context.getParser() == null) {
                throw InvalidDefinitionException.from((JsonParser) null, "Requires a parser", context.getContextualType());
            }
            return this;
        }

        @Override
        public Choice deserialize(JsonParser parser, DeserializationContext context) { return Choice.PLAIN; }
    }

    static class CustomListDeserializer extends JsonDeserializer<ArrayList<?>> implements ContextualDeserializer {
        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            assertEquals(Choice.SPECIAL.getClass(), context.getContextualType().getContentType().getRawClass());
            return this;
        }

        @Override
        public ArrayList<?> deserialize(JsonParser parser, DeserializationContext context) throws java.io.IOException {
            parser.skipChildren();
            return new ArrayList<>(List.of(Choice.PLAIN));
        }
    }

    static class CurrentEnumCaster {
        @Upcast(type = "io.fluxzero.sdk.common.serialization.jackson.EnumSerializationTest$Choice", revision = 0)
        TextNode fromOldRevision(TextNode input) { throw new AssertionError("New data reached old upcaster"); }

        @Downcast(type = "io.fluxzero.sdk.common.serialization.jackson.EnumSerializationTest$Choice", revision = 2)
        TextNode toOldRevision(TextNode input) { return TextNode.valueOf("PLAIN"); }
    }

    static class LegacyCaster {
        @Upcast(type = LEGACY_TYPE, revision = 0)
        TextNode upcast(TextNode input) { return TextNode.valueOf("PLAIN"); }
    }
}
