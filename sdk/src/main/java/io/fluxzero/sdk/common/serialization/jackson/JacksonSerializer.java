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

package io.fluxzero.sdk.common.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializer;
import com.fasterxml.jackson.databind.ser.impl.UnwrappingBeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.UnwrappingBeanSerializer;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedObject;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.search.Inverter;
import io.fluxzero.common.search.JacksonInverter;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.AbstractSerializer;
import io.fluxzero.sdk.common.serialization.ContentFilter;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.DeserializingObject;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Type;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.sdk.common.ClientUtils.getRevisionNumber;
import static java.lang.String.format;

/**
 * A concrete {@link io.fluxzero.sdk.common.serialization.Serializer} implementation based on Jackson.
 * <p>
 * This is the default serializer used in Fluxzero, supporting:
 * <ul>
 *     <li>Serialization and deserialization using Jackson's {@link ObjectMapper}</li>
 *     <li>Integration with upcasters/downcasters for versioned data evolution</li>
 *     <li>Intermediate representation based on {@link JsonNode} for revision tracking</li>
 *     <li>{@link DocumentSerializer} support for document store interoperability</li>
 *     <li>Type caching and memoization for performance</li>
 * </ul>
 *
 * <p>
 * You can customize or replace this serializer entirely by subclassing or injecting your own implementation of
 * {@code AbstractSerializer}.
 */
@Slf4j
public class JacksonSerializer extends AbstractSerializer<JsonNode> implements DocumentSerializer {
    private static final byte[] NULL_BYTES = new byte[]{'n', 'u', 'l', 'l'};
    private static final String CLASS_PROPERTY = "@class";
    private static final VarHandle BYTE_WORDS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    /**
     * Default {@link JsonMapper} instance used for JSON serialization and deserialization.
     * <p>
     * In advanced scenarios, users may replace this field with a custom {@link JsonMapper}. However, this is generally
     * discouraged unless strictly necessary.
     * <p>
     * A better approach for customizing Jackson behavior is to provide your own modules via the Jackson
     * {@link com.fasterxml.jackson.databind.Module} SPI (ServiceLoader mechanism), which avoids overriding global
     * configuration and ensures compatibility.
     * <p>
     * <strong>Warning:</strong> This mapper is also used to construct and parse search documents.
     * Misconfiguration may result in inconsistencies in search indexing or data loss.
     */
    public static JsonMapper defaultObjectMapper = JsonUtils.writer;

    @Getter
    private final ObjectMapper objectMapper;
    private final boolean singlePassLargePayloads;
    @Delegate
    private final ContentFilter contentFilter;
    private final Function<String, JavaType> typeCache = memoize(this::getJavaType);
    private final Function<Type, String> typeStringCache = memoize(this::getCanonicalType);
    private final Inverter<JsonNode> inverter;

    /**
     * Constructs a default JacksonSerializer with no up/downcasters.
     */
    public JacksonSerializer() {
        this(Collections.emptyList());
    }

    /**
     * Constructs a JacksonSerializer with the given up/downcaster candidates.
     */
    public JacksonSerializer(Collection<?> casterCandidates) {
        this(defaultObjectMapper, casterCandidates);
    }

    /**
     * Constructs a JacksonSerializer with a specific {@link JsonMapper} instance.
     */
    public JacksonSerializer(JsonMapper objectMapper) {
        this(objectMapper, Collections.emptyList());
    }

    /**
     * Constructs a JacksonSerializer with an object mapper and up/downcaster candidates.
     */
    public JacksonSerializer(JsonMapper objectMapper, Collection<?> casterCandidates) {
        this(objectMapper, casterCandidates, new JacksonInverter());
    }

    /**
     * Full constructor with object mapper, caster candidates and custom document inverter.
     */
    public JacksonSerializer(JsonMapper objectMapper, Collection<?> casterCandidates, JacksonInverter inverter) {
        super(casterCandidates, inverter, Data.JSON_FORMAT);
        this.objectMapper = objectMapper;
        this.singlePassLargePayloads = objectMapper.getClass() == JsonMapper.class;
        this.contentFilter = new JacksonContentFilter(objectMapper);
        this.inverter = inverter;
    }

    /**
     * Returns a canonical string name for the given type.
     */
    @Override
    protected String asString(Type type) {
        return typeStringCache.apply(type);
    }

    /**
     * Maps standard bean properties using this mapper's naming, alias and unwrapping configuration. Custom codecs,
     * dynamic output and type/root wrappers require an explicit mapping override; unsupported mappings fail closed.
     */
    @Override
    public List<String> serializedPropertyPaths(Object payload, String propertyPath) {
        if (objectMapper.isEnabled(SerializationFeature.WRAP_ROOT_VALUE)) {
            throw unsupportedPropertyMapping(payload.getClass(), propertyPath);
        }
        return serializedPropertyPaths(payload, payload.getClass(), propertyPath);
    }

    @SneakyThrows
    private List<String> serializedPropertyPaths(Object payload, Class<?> type, String path) {
        int separator = path.indexOf('/');
        String name = separator < 0 ? path : path.substring(0, separator);
        var description = objectMapper.getSerializationConfig().introspect(objectMapper.constructType(type));
        // Bean introspection alone cannot prove the wire shape of a custom codec or a type wrapper.
        var codec = objectMapper.getSerializerProviderInstance().findValueSerializer(type);
        var typeSerializer = objectMapper.getSerializerFactory().createTypeSerializer(
                objectMapper.getSerializationConfig(), objectMapper.constructType(type));
        if (description.findAnyGetter() != null
            || objectMapper.getSerializationConfig().getAnnotationIntrospector()
                       .findFilterId(description.getClassInfo()) != null
            || codec.getClass() != BeanSerializer.class
            || typeSerializer != null && !usesPropertyTypeId(typeSerializer.getTypeInclusion())) {
            throw unsupportedPropertyMapping(type, path);
        }
        var writers = new ArrayList<BeanPropertyWriter>();
        var writerNames = new HashSet<String>();
        codec.properties().forEachRemaining(writer -> {
            if (writer.getClass() != BeanPropertyWriter.class
                && writer.getClass() != UnwrappingBeanPropertyWriter.class
                || !writerNames.add(writer.getName())
                || description.findProperties().stream().noneMatch(property ->
                    property.getName().equals(writer.getName())
                    && property.getPrimaryMember() != null
                    && property.getPrimaryMember().equals(writer.getMember()))) {
                throw unsupportedPropertyMapping(type, path);
            }
            writers.add((BeanPropertyWriter) writer);
        });
        for (var property : description.findProperties()) {
            if (!property.getInternalName().equals(name) || !property.couldSerialize()) {
                continue;
            }
            var member = property.getPrimaryMember();
            var writer = writers.stream().filter(candidate -> candidate.getName().equals(property.getName()))
                    .findFirst().orElseThrow(() -> unsupportedPropertyMapping(type, path));
            if (separator >= 0 && (member != null && member.getAnnotation(JsonFilter.class) != null
                                   || writer.getSerializer() != null
                                      && writer.getSerializer().getClass() != BeanSerializer.class
                                      && writer.getSerializer().getClass() != UnwrappingBeanSerializer.class
                                   || writer.getTypeSerializer() != null
                                      && !usesPropertyTypeId(writer.getTypeSerializer().getTypeInclusion()))) {
                throw unsupportedPropertyMapping(type, path);
            }
            JsonUnwrapped unwrapped = member == null ? null : member.getAnnotation(JsonUnwrapped.class);
            List<String> names = new ArrayList<>(List.of(escapeProperty(property.getName())));
            JsonAlias aliases = member == null ? null : member.getAnnotation(JsonAlias.class);
            if (aliases != null) {
                Arrays.stream(aliases.value()).map(JacksonSerializer::escapeProperty).forEach(names::add);
            }
            if (separator < 0) {
                if (unwrapped != null && unwrapped.enabled()) {
                    throw new IllegalArgumentException("An unwrapped value cannot be protected as a single property: " + path);
                }
                return names;
            }
            Object nested = payload == null ? null : ReflectionUtils.readProperty(name, payload).orElse(null);
            List<String> children = serializedPropertyPaths(nested,
                    nested == null ? property.getPrimaryType().getRawClass() : nested.getClass(),
                    path.substring(separator + 1));
            if (unwrapped != null && unwrapped.enabled()) {
                return children.stream().map(child -> {
                    int slash = child.indexOf('/');
                    return escapeProperty(unwrapped.prefix()) + (slash < 0 ? child : child.substring(0, slash))
                           + escapeProperty(unwrapped.suffix()) + (slash < 0 ? "" : child.substring(slash));
                }).toList();
            }
            return names.stream().flatMap(parent -> children.stream().map(child -> parent + "/" + child)).toList();
        }
        throw unsupportedPropertyMapping(type, path);
    }

    private static boolean usesPropertyTypeId(JsonTypeInfo.As inclusion) {
        return inclusion == JsonTypeInfo.As.PROPERTY || inclusion == JsonTypeInfo.As.EXISTING_PROPERTY;
    }

    private static UnsupportedOperationException unsupportedPropertyMapping(Class<?> type, String path) {
        return new UnsupportedOperationException(
                "Cannot safely map protected property %s on %s with this Jackson configuration. "
                .formatted(path, type.getName())
                + "Provide an explicit serializedPropertyPaths implementation for the custom wire format.");
    }

    private static String escapeProperty(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Serializes the object to a JSON byte array.
     */
    @Override
    protected byte[] doSerialize(Object object) throws Exception {
        return object == null ? NULL_BYTES.clone() : objectMapper.writeValueAsBytes(object);
    }

    /**
     * Deserializes a {@link Data} instance into an object of the given type using the Jackson object mapper. Supports
     * {@link JsonNode}, byte arrays and strings as serialized input.
     */
    @Override
    protected Object doDeserialize(Data<?> data, String type) throws Exception {
        Data.ByteArrayView byteArrayView = data.byteArrayView();
        if (byteArrayView != null) {
            byte[] bytes = byteArrayView.array();
            int offset = byteArrayView.offset();
            int length = byteArrayView.length();
            if (Void.class.getName().equals(type)
                    && length == NULL_BYTES.length
                    && Arrays.equals(bytes, offset, offset + length,
                                     NULL_BYTES, 0, NULL_BYTES.length)) {
                return null;
            }
            if (mayContainClassProperty(bytes, offset, length)) {
                return deserializeWithTypeResolution(objectMapper.createParser(bytes, offset, length),
                                                     typeCache.apply(type));
            }
            return objectMapper.readValue(bytes, offset, length, typeCache.apply(type));
        }
        Object value = data.getValue();
        if (mayContainClassProperty(value)) {
            JavaType javaType = typeCache.apply(type);
            return switch (value) {
                case JsonNode v -> deserializeWithTypeResolution(objectMapper.treeAsTokens(v), javaType);
                case byte[] v -> deserializeWithTypeResolution(objectMapper.createParser(v), javaType);
                case String v -> deserializeWithTypeResolution(objectMapper.createParser(v), javaType);
                case null -> null;
                default ->
                        throw new IllegalArgumentException("Incompatible data value type: " + value.getClass());
            };
        }
        return switch (value) {
            case JsonNode v -> objectMapper.convertValue(v, typeCache.apply(type));
            case byte[] v when Void.class.getName().equals(type) && Arrays.equals(v, NULL_BYTES) -> null;
            case byte[] v -> objectMapper.readValue(v, typeCache.apply(type));
            case String v -> objectMapper.readValue(v, typeCache.apply(type));
            case null -> null;
            default ->
                    throw new IllegalArgumentException("Incompatible data value type: " + value.getClass());
        };
    }

    private boolean mayContainClassProperty(Object value) {
        return switch (value) {
            case JsonNode ignored -> true;
            case byte[] bytes -> mayContainClassProperty(bytes, 0, bytes.length);
            case String string -> string.contains(CLASS_PROPERTY) || string.contains("\\u");
            case null -> false;
            default -> true;
        };
    }

    private boolean mayContainClassProperty(byte[] input, int offset, int length) {
        // Large payloads are cheaper to parse once with type resolution than to scan before parsing.
        // Keep custom JsonMapper subclasses on their existing byte-array readValue overload for marker-free input.
        if (length >= 64 && singlePassLargePayloads) {
            return true;
        }
        int end = offset + length;
        int i = offset;
        // Skip eight ordinary bytes at once. Endianness is irrelevant: the masks test every byte equally.
        // A possible '@' or escape falls back to the exact scan, including candidates spanning two words.
        for (; i <= end - Long.BYTES; i += Long.BYTES) {
            long word = (long) BYTE_WORDS.get(input, i);
            if (hasZeroByte(word ^ 0x4040404040404040L) || hasZeroByte(word ^ 0x5c5c5c5c5c5c5c5cL)) {
                break;
            }
        }
        for (; i < end; i++) {
            byte current = input[i];
            if (current == '\\' && i + 1 < end && input[i + 1] == 'u') {
                return true;
            }
            if (current == '@' && i <= end - CLASS_PROPERTY.length()) {
                int j = 1;
                while (j < CLASS_PROPERTY.length() && input[i + j] == CLASS_PROPERTY.charAt(j)) {
                    j++;
                }
                if (j == CLASS_PROPERTY.length()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasZeroByte(long value) {
        return ((value - 0x0101010101010101L) & ~value & 0x8080808080808080L) != 0;
    }

    private Object deserializeWithTypeResolution(JsonParser parser, JavaType type) throws IOException {
        try (JsonParser resolvingParser = new TypeResolvingJsonParser(parser)) {
            return objectMapper.readValue(resolvingParser, type);
        }
    }

    private class TypeResolvingJsonParser extends JsonParserDelegate {
        private TypeResolvingJsonParser(JsonParser delegate) {
            super(delegate);
        }

        @Override
        public String getText() throws IOException {
            return resolveTypeIdentifier(super.getText());
        }

        @Override
        public String getValueAsString() throws IOException {
            return resolveTypeIdentifier(super.getValueAsString());
        }

        @Override
        public String getValueAsString(String defaultValue) throws IOException {
            return resolveTypeIdentifier(super.getValueAsString(defaultValue));
        }

        private String resolveTypeIdentifier(String value) throws IOException {
            return currentToken() == JsonToken.VALUE_STRING && CLASS_PROPERTY.equals(currentName())
                    ? resolveTypeName(value) : value;
        }
    }

    /**
     * Converts the given object into a {@link JsonNode} for use in revision downcasting.
     */
    @SneakyThrows
    @Override
    protected JsonNode asIntermediateValue(Object input) {
        return input instanceof byte[]
                ? objectMapper.readTree((byte[]) input)
                : objectMapper.convertValue(input, JsonNode.class);
    }

    /**
     * Determines whether the given type is known to the Jackson type system.
     */
    @Override
    protected boolean isKnownType(String type) {
        try {
            typeCache.apply(type);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fallback handler for deserialization of unknown types. Attempts best-effort conversion using Jackson.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    protected Stream<DeserializingObject<byte[], ?>> deserializeUnknownType(SerializedObject<?> s) {
        SerializedObject<?> jsonNode =
                s.withData(new Data(s.data().getValue(), JsonNode.class.getName(), 0, getFormat()));
        return Stream.of(new DeserializingObject(jsonNode, (Function<Type, Object>) type -> {
            try {
                Object serializedValue = jsonNode.data().getValue();
                return switch (serializedValue) {
                    case null -> convert(null, type);
                    case JsonNode json -> convert(json, type);
                    case byte[] bytes -> convert(objectMapper.readTree(bytes), type);
                    default -> throw new UnsupportedOperationException(
                            "Unsupported data type: " + serializedValue.getClass());
                };
            } catch (Exception e) {
                throw new DeserializationException(format("Could not deserialize a %s to a %s. Invalid json?",
                                                          type, s.data().getType()), e);
            }
        }));
    }

    /**
     * Resolves a canonical or registered simple/partial {@link JavaType} for the given string-based type name.
     */
    protected JavaType getJavaType(String type) {
        return objectMapper.getTypeFactory().constructFromCanonical(resolveTypeName(type));
    }

    /**
     * Computes the canonical string representation of a {@link Type}.
     */
    protected String getCanonicalType(Type type) {
        return objectMapper.constructType(type).toCanonical();
    }

    @Override
    public SerializedDocument toDocument(Object value, String id, String collection, Instant timestamp, Instant end,
                                         Metadata metadata) {
        return inverter.toDocument(value, getTypeString(value), getRevisionNumber(value), id, collection, timestamp,
                                   end, metadata);
    }

    @Override
    public <T> T fromDocument(SerializedDocument document) {
        return deserialize(document.getDocument());
    }

    @Override
    public <T> T fromDocument(SerializedDocument document, Class<T> type) {
        return deserialize(document.getDocument(), type);
    }

    /**
     * Converts an object into another type using Jackson’s {@link ObjectMapper}.
     */
    @Override
    public <V> V doConvert(Object value, Type type) {
        return objectMapper.convertValue(value, objectMapper.constructType(type));
    }

    /**
     * Performs a field-level clone by copying values from the original object into a new instance of the same type.
     * This first converts the value to an {@link com.fasterxml.jackson.databind.node.ObjectNode}.
     */
    @Override
    public Object doClone(Object value) {
        return ReflectionUtils.copyFields(value, doConvert(objectMapper.createObjectNode(), value.getClass()));
    }
}
