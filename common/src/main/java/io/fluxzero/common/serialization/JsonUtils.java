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

package io.fluxzero.common.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTypeResolverBuilder;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.fluxzero.common.FileUtils;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.reflection.ReflectionUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY;
import static com.fasterxml.jackson.databind.DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE;
import static com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS;
import static com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_WITH_ZONE_ID;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS;
import static com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES;

/**
 * Utility class for JSON serialization, deserialization, and file-based JSON resource loading.
 * <p>
 * {@code JsonUtils} wraps around Jackson's {@link JsonMapper} to provide enhanced handling of JSON processing. It
 * supports dynamic type handling, tree merging, custom file loading with `@extends` inheritance, and robust conversion
 * between types and structures.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Configurable {@link JsonMapper} for serialization ({@code writer}) and deserialization ({@code reader})</li>
 *   <li>Convenience methods for reading from resource files, merging JSON trees, and converting between types</li>
 *   <li>Support for deserializing JSON using a {@code @class} field containing fully qualified class or simple class name</li>
 *   <li>Support for extending JSON files using a {@code @extends} field</li>
 *   <li>Handles file and URI-based input</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * MyClass object = JsonUtils.fromFile("config.json", MyClass.class);
 * String json = JsonUtils.asJson(object);
 * }</pre>
 *
 * <h2>Type Resolution via @class</h2>
 * When deserializing JSON using generic methods like {@link #fromFile(String)} or {@link #fromJson(String)} that
 * don't specify a target type, a {@code @class} attribute is required in the JSON to indicate the target Java type.
 * This type can be either:
 * <ul>
 *     <li>A fully qualified class name (e.g. {@code com.myapp.model.DepositMoney})</li>
 *     <li>A short or partially qualified name (e.g. {@code DepositMoney} or {@code myapp.DepositMoney})</li>
 * </ul>
 * When a short or partial name is used, it must be registered using {@link RegisterType @RegisterType}
 * so that it can be resolved via the {@link TypeRegistry}.
 *
 * <p>
 * The Fluxzero Runtime uses this resolution mechanism internally to support extensibility and polymorphic message
 * deserialization.
 *
 * <h2>Revisioned payloads</h2>
 * Untyped methods such as {@link #fromFile(String)} and {@link #fromJson(String)} recognize an {@code @revision}
 * property next to {@code @class}. Instead of deserializing the JSON directly, they return a {@link Data} containing
 * the JSON payload, with {@code @class} as its type and {@code @revision} as its revision. Both control properties are
 * removed from the payload. A regular {@code revision} property remains part of the payload.
 * <pre>{@code
 * {
 *   "@class": "com.example.UserCreated",
 *   "@revision": 0,
 *   "name": "Alice"
 * }
 * }</pre>
 * This representation can be passed to a serializer so that normal upcasting takes place before deserialization.
 *
 * <h2>File Inheritance via @extends</h2>
 * JSON files can extend other JSON files using the {@code @extends} attribute. This allows the reuse of base
 * configurations or data structures with overridden values in the extending file.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * // deposit-to-userA.json
 * {
 *   "@class": "DepositMoney",
 *   "amount": 23,
 *   "recipient": "userA"
 * }
 *
 * // deposit-to-userB.json
 * {
 *   "@extends": "deposit-to-userA.json",
 *   "recipient": "userB"
 * }
 * }</pre>
 * When {@code deposit-to-userB.json} is loaded, it will inherit all fields from {@code deposit-to-userA.json}
 * and override the {@code recipient} field with "userB".
 * <p>
 * The inheritance is recursive and is applied before deserialization.
 *
 * @see ObjectMapper
 * @see JsonNode
 * @see FileUtils
 * @see RegisterType
 * @see TypeRegistry
 */
@Slf4j
public class JsonUtils {
    /**
     * Preconfigured JsonMapper for writing/serializing objects to JSON. By default, it is created using
     * {@link #defaultWriterBuilder()}.
     * <p>
     * In advanced scenarios, users may replace this field with a custom {@link JsonMapper}. However, this is generally
     * discouraged unless strictly necessary.
     */
    public static JsonMapper writer = defaultWriterBuilder().build();

    /**
     * Creates a preconfigured builder for a JsonMapper for writing/serializing objects to JSON.
     * <p>
     * A better approach for customizing Jackson behavior is to provide your own modules via the Jackson
     * {@link com.fasterxml.jackson.databind.Module} SPI (ServiceLoader mechanism), which avoids overriding global
     * configuration and ensures compatibility.
     * <p>
     * <strong>Warning:</strong> This mapper is also used by the default serializer in Fluxzero applications,
     * {@code JacksonSerializer}. Misconfiguration may result in inconsistencies in search indexing or data loss.
     */
    public static JsonMapper.Builder defaultWriterBuilder() {
        JsonMapper.Builder builder = JsonMapper.builder()
                .addModule(new StripStringsModule()).addModule(new NullCollectionsAsEmptyModule())
                .addModule(new Jdk8Module());
        builder = tryAddKotlinModule(builder);
        return builder
                .enable(JsonParser.Feature.ALLOW_COMMENTS)
                .disable(FAIL_ON_EMPTY_BEANS).disable(WRITE_DATES_AS_TIMESTAMPS).disable(WRITE_DURATIONS_AS_TIMESTAMPS)
                .enable(WRITE_DATES_WITH_ZONE_ID)
                .disable(ADJUST_DATES_TO_CONTEXT_TIME_ZONE).disable(FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .enable(ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .enable(USE_BIG_DECIMAL_FOR_FLOATS)
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .findAndAddModules();
    }

    static JsonMapper.Builder tryAddKotlinModule(JsonMapper.Builder builder) {
        try {
            Class<?> moduleClass = ReflectionUtils.classForName("com.fasterxml.jackson.module.kotlin.KotlinModule$Builder");
            Object builderInstance = ReflectionUtils.asInstance(moduleClass);
            Object kotlinModule = moduleClass.getMethod("build").invoke(builderInstance);
            return builder.addModule((Module) kotlinModule);
        } catch (Throwable ignored) {
            return builder;
        }
    }

    /**
     * Preconfigured JsonMapper for reading/deserializing objects from JSON, including type handling.
     *
     * <p>
     * In advanced scenarios, users may replace this field with a custom {@link JsonMapper}. However, this is generally
     * discouraged unless strictly necessary.
     * <p>
     * A better approach for customizing Jackson behavior is to provide your own modules via the Jackson
     * {@link com.fasterxml.jackson.databind.Module} SPI (ServiceLoader mechanism), which avoids overriding global
     * configuration and ensures compatibility.
     */
    public static JsonMapper reader = writer.rebuild()
            .setDefaultTyping(new DefaultTypeResolverBuilder(JAVA_LANG_OBJECT, AllowlistTypeValidator.instance)
                                      .init(Id.CLASS, new GlobalTypeIdResolver()).inclusion(JsonTypeInfo.As.PROPERTY))
            .build();

    private static final String classProperty = "@class";
    private static final String revisionProperty = "@revision";
    private static final String revisionMarker = '"' + revisionProperty + '"';
    private static final String escapedAsciiMarker = "\\u00";
    private static final Pattern extendsPattern = Pattern.compile("(\"@extends?\"\\s*:\\s*\"([^\"]+)\"\\s*,?)");

    /**
     * Loads and deserializes a JSON file located relative to the calling class. Automatically supports {@code @extends}
     * inheritance for configuration reuse.
     */
    @SneakyThrows
    public static Object fromFile(String fileName) {
        return fromFile(ReflectionUtils.getCallerClass(), fileName);
    }

    /**
     * Loads and deserializes JSON files located relative to the calling class. Automatically supports {@code @extends}
     * inheritance for configuration reuse.
     */
    public static List<?> fromFile(String... fileNames) {
        Class<?> callerClass = ReflectionUtils.getCallerClass();
        return Arrays.stream(fileNames).map(f -> JsonUtils.fromFile(callerClass, f)).collect(Collectors.toList());
    }

    /**
     * Loads and deserializes a JSON file located relative to the reference point into an object. Automatically supports
     * {@code @extends} inheritance for configuration reuse.
     */
    @SuppressWarnings({"unchecked"})
    @SneakyThrows
    public static <T> T fromFile(Class<?> referencePoint, String fileName) {
        String content = getContent(referencePoint, fileName);
        return fromJson(content);
    }

    @SneakyThrows
    protected static String getContent(Class<?> referencePoint, String fileName) {
        URL resource = referencePoint.getResource(fileName);
        if (resource == null) {
            log.error("Resource {} not found in package {}", fileName, referencePoint.getPackageName());
            throw new IllegalArgumentException("File not found: " + fileName);
        }
        return getContent(resource.toURI());
    }

    @SneakyThrows
    protected static String getContent(URI fileUri) {
        String content = FileUtils.loadFile(fileUri);
        AtomicReference<String> extendsReference = new AtomicReference<>();
        content = extendsPattern.matcher(content).replaceFirst(m -> {
            extendsReference.set(m.group(2));
            return "";
        });
        String extendsFile = extendsReference.get();
        if (extendsFile != null) {
            String extendsContent;
            if (extendsFile.startsWith("/")) {
                URL extendsResource = JsonUtils.class.getResource(extendsFile);
                if (extendsResource == null) {
                    log.error("Resource {} not found", extendsFile);
                    throw new IllegalArgumentException("File not found: " + extendsFile);
                }
                extendsContent = getContent(extendsResource.toURI());
            } else {
                extendsContent = getContent(FileUtils.safeResolve(fileUri, extendsFile));
            }
            var baseNode = reader.readTree(extendsContent);
            reader.readerForUpdating(baseNode).readValue(reader.readTree(content));
            content = reader.writeValueAsString(baseNode);
        }
        return content;
    }

    /**
     * Loads and deserializes a JSON file located relative to the calling class to an object of the specified type.
     * Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    @SneakyThrows
    public static <T> T fromFile(String fileName, Class<T> type) {
        return fromFile(ReflectionUtils.getCallerClass(), fileName, type);
    }

    /**
     * Loads and deserializes a JSON file located relative to the calling class to an object of the specified type.
     * Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    @SneakyThrows
    public static <T> T fromFile(String fileName, JavaType javaType) {
        return writer.readValue(FileUtils.loadFile(ReflectionUtils.getCallerClass(), fileName), javaType);
    }

    /**
     * Loads and deserializes a JSON file located relative to the calling class into an object of the type specified by
     * the provided type function. Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    @SneakyThrows
    public static <T> T fromFile(String fileName, Function<TypeFactory, JavaType> typeFunction) {
        return writer.readValue(FileUtils.loadFile(ReflectionUtils.getCallerClass(), fileName),
                                typeFunction.apply(typeFactory()));
    }

    /**
     * Loads and deserializes a JSON file located relative to the reference point into an object of the provided type.
     * Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    @SneakyThrows
    public static <T> T fromFile(Class<?> referencePoint, String fileName, Class<T> type) {
        return fromJson(getContent(referencePoint, fileName), type);
    }

    /**
     * Loads and deserializes a JSON file located relative to the reference point into an object of the provided type.
     * Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    @SneakyThrows
    public static <T> T fromFile(Class<?> referencePoint, String fileName, JavaType javaType) {
        return writer.readValue(FileUtils.loadFile(referencePoint, fileName), javaType);
    }

    /**
     * Loads and deserializes a JSON file located relative to the referencePoint into an object of the type specified by
     * the provided type function. Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    @SneakyThrows
    public static <T> T fromFile(Class<?> referencePoint, String fileName,
                                 Function<TypeFactory, JavaType> typeFunction) {
        return writer.readValue(FileUtils.loadFile(referencePoint, fileName), typeFunction.apply(typeFactory()));
    }

    /**
     * Loads and deserializes a JSON file located relative to the calling class and casts it to type {@link T}.
     * Automatically supports {@code @extends} inheritance for configuration reuse.
     */
    public static <T> T fromFileAs(String fileName) {
        return fromFile(ReflectionUtils.getCallerClass(), fileName);
    }

    /**
     * Loads and deserializes a JSON file located relative to the calling class, applies property replacements, and
     * returns the resulting object. The method supports updating specific properties of the deserialized object using
     * the provided replacement values.
     */
    public static Object fromFileWith(String fileName, Map<String, Object> replaceValues) {
        Object o = JsonUtils.fromFile(ReflectionUtils.getCallerClass(), fileName);
        replaceValues.forEach((path, replacement) -> ReflectionUtils.writeProperty(path, o, replacement));
        return o;
    }

    /**
     * Deserializes JSON without a caller-provided target type. If the root object contains {@code @revision}, this
     * returns revisioned {@link Data} instead of immediately deserializing the declared {@code @class}.
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    public static <T> T fromJson(String json) {
        if (json != null && (json.contains(revisionMarker) || json.contains(escapedAsciiMarker))) {
            JsonNode tree = reader.readTree(json);
            if (tree instanceof ObjectNode objectNode && objectNode.has(revisionProperty)) {
                return (T) deserializeRevisioned(objectNode);
            }
        }
        return (T) reader.readValue(json, Object.class);
    }

    /**
     * Converts a JSON string to an object of the given class type.
     */
    @SneakyThrows
    public static <T> T fromJson(String json, Class<T> type) {
        return selectMapper(type).readValue(json, type);
    }

    /**
     * Converts a JSON string to an object of the given type, which may be a {@link java.lang.reflect.ParameterizedType}
     * or {@link Class}.
     */
    @SneakyThrows
    public static <T> T fromJson(String json, Type type) {
        return fromJson(json, writer.constructType(type));
    }

    /**
     * Converts a JSON string to an object of the given type.
     */
    @SneakyThrows
    public static <T> T fromJson(String json, JavaType type) {
        return writer.readValue(json, type);
    }

    /**
     * Deserializes a JSON string into an object of the type specified by the provided type function.
     */
    @SneakyThrows
    public static <T> T fromJson(String json, Function<TypeFactory, JavaType> typeFunction) {
        return writer.readValue(json, typeFunction.apply(typeFactory()));
    }

    /**
     * Deserializes a JSON byte array into an instance of the specified class type.
     */
    @SneakyThrows
    public static <T> T fromJson(byte[] json, Class<T> type) {
        return selectMapper(type).readValue(json, type);
    }

    /**
     * Deserializes a JSON byte array without a caller-provided target type. If the root object contains
     * {@code @revision}, this returns revisioned {@link Data} instead of immediately deserializing the declared
     * {@code @class}.
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    public static <T> T fromJson(byte[] json) {
        if (containsMarker(json, revisionMarker) || containsMarker(json, escapedAsciiMarker)) {
            JsonNode tree = reader.readTree(json);
            if (tree instanceof ObjectNode objectNode && objectNode.has(revisionProperty)) {
                return (T) deserializeRevisioned(objectNode);
            }
        }
        return (T) reader.readValue(json, Object.class);
    }

    private static boolean containsMarker(byte[] json, String marker) {
        if (json == null || json.length < marker.length()) {
            return false;
        }
        for (int i = 0; i <= json.length - marker.length(); i++) {
            int inputIndex = i;
            int markerIndex = 0;
            while (markerIndex < marker.length()) {
                while (inputIndex < json.length && json[inputIndex] == 0) {
                    inputIndex++;
                }
                if (inputIndex == json.length || json[inputIndex] != marker.charAt(markerIndex)) {
                    break;
                }
                inputIndex++;
                markerIndex++;
            }
            if (markerIndex == marker.length()) {
                return true;
            }
        }
        return false;
    }

    private static Object deserializeRevisioned(ObjectNode objectNode) {
        JsonNode revisionNode = objectNode.get(revisionProperty);
        JsonNode typeNode = objectNode.get(classProperty);
        if (typeNode == null || !typeNode.isTextual() || typeNode.textValue().isBlank()) {
            throw new IllegalArgumentException("JSON must contain a textual @class when using @revision");
        }
        if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToInt()) {
            throw new IllegalArgumentException("JSON must contain an integer @revision");
        }
        String type = typeNode.textValue();
        Class<?> resolvedType = ReflectionUtils.classForName(type, null);
        ObjectNode payload = objectNode.deepCopy();
        payload.remove(List.of(classProperty, revisionProperty));
        return new Data<>(payload, resolvedType == null ? type : resolvedType.getName(), revisionNode.intValue(),
                          Data.JSON_FORMAT);
    }

    /**
     * Converts a JSON string to an object of the given type, which may be a {@link java.lang.reflect.ParameterizedType}
     * or {@link Class}.
     */
    @SneakyThrows
    public static <T> T fromJson(byte[] json, Type type) {
        return fromJson(json, writer.constructType(type));
    }

    /**
     * Deserializes a JSON byte array into an instance of the specified type.
     */
    @SneakyThrows
    public static <T> T fromJson(byte[] json, JavaType type) {
        return writer.readValue(json, type);
    }

    /**
     * Deserializes a JSON byte array into an object of the type specified by the provided type function.
     */
    @SneakyThrows
    public static <T> T fromJson(byte[] json, Function<TypeFactory, JavaType> typeFunction) {
        return writer.readValue(json, typeFunction.apply(typeFactory()));
    }

    static JsonMapper selectMapper(Class<?> type) {
        return Object.class.equals(type)
               || Collection.class.isAssignableFrom(type) ? reader : writer;
    }

    /**
     * Converts an object to a JSON string.
     */
    @SneakyThrows
    public static String asJson(Object object) {
        return writer.writeValueAsString(object);
    }

    /**
     * Converts an object to a formatted JSON string.
     */
    @SneakyThrows
    public static String asPrettyJson(Object object) {
        return writer.writerWithDefaultPrettyPrinter().writeValueAsString(object);
    }

    /**
     * Converts an object to a JSON byte array.
     */
    @SneakyThrows
    public static byte[] asBytes(Object object) {
        return writer.writeValueAsBytes(object);
    }

    /**
     * Converts an object to an object of the given class.
     */
    @SneakyThrows
    public static <T> T convertValue(Object fromValue, Class<? extends T> toValueType) {
        return convertValue(fromValue, (Type) toValueType);
    }

    /**
     * Converts an object to an object of the given type which may be a {@link java.lang.reflect.ParameterizedType} or
     * {@link Class}.
     */
    @SneakyThrows
    public static <T> T convertValue(Object fromValue, Type toValueType) {
        if (fromValue instanceof byte[] input && !byte[].class.equals(toValueType)) {
            fromValue = readTree(input);
        } else if (fromValue instanceof String input && !String.class.equals(toValueType)) {
            fromValue = readTree(input);
        }
        return writer.convertValue(fromValue, writer.constructType(toValueType));
    }

    /**
     * Converts an object to an object of the given type.
     */
    @SneakyThrows
    public static <T> T convertValue(Object fromValue, TypeReference<T> typeRef) {
        if (fromValue instanceof byte[] input && !byte[].class.equals(typeRef.getType())) {
            fromValue = readTree(input);
        } else if (fromValue instanceof String input && !String.class.equals(typeRef.getType())) {
            fromValue = readTree(input);
        }
        return writer.convertValue(fromValue, typeRef);
    }

    /**
     * Converts an object to an object of the given type.
     */
    public static <T> T convertValue(Object fromValue, Function<TypeFactory, JavaType> typeFunction) {
        return writer.convertValue(fromValue, typeFunction.apply(typeFactory()));
    }

    /**
     * Reads a JSON structure as a {@link JsonNode} tree from a JSON byte array.
     */
    @SneakyThrows
    public static JsonNode readTree(byte[] jsonContent) {
        return writer.readTree(jsonContent);
    }

    /**
     * Reads a JSON structure as a {@link JsonNode} tree from a JSON string.
     */
    @SneakyThrows
    public static JsonNode readTree(String jsonContent) {
        return writer.readTree(jsonContent);
    }

    /**
     * Reads a JSON structure as a {@link JsonNode} tree from a JSON input stream.
     */
    @SneakyThrows
    public static JsonNode readTree(InputStream jsonContent) {
        return writer.readTree(jsonContent);
    }

    /**
     * Converts an object to a {@link JsonNode} of type {@link T}.
     */
    @SneakyThrows
    public static <T extends JsonNode> T valueToTree(Object object) {
        return writer.valueToTree(object);
    }

    /**
     * Constructs a new {@link ObjectNode} from alternating key-value arguments.
     */
    public static ObjectNode newObjectNode(Object... keyValues) {
        if (keyValues.length % 2 == 1) {
            throw new IllegalArgumentException("Expected even number of keys + values but got: " + keyValues.length);
        }
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return convertValue(map, ObjectNode.class);
    }

    /**
     * Merges a base object with a patch/update object, omitting null fields. Returns a new object of the same type with
     * updated fields.
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    public static <T> T merge(T base, Object update) {
        if (update == null) {
            return base;
        }
        if (base == null) {
            return (T) update;
        }
        if (Objects.equals(base, update)) {
            return base;
        }
        ObjectNode result = valueToTree(base);
        ObjectNode toAdd = valueToTree(update);
        Iterator<JsonNode> iterator = toAdd.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isNull()) {
                iterator.remove();
            }
        }
        result.setAll(toAdd);
        return (T) convertValue(result, base.getClass());
    }

    /**
     * Provides access to the TypeFactory instance used by the writer.
     */
    public static TypeFactory typeFactory() {
        return writer.getTypeFactory();
    }

    /**
     * Disables any Jackson @JsonIgnore behavior on the specified ObjectMapper.
     */
    public static void disableJsonIgnore(ObjectMapper mapper) {
        mapper.setAnnotationIntrospectors(
                ignoreDisablingIntrospector(mapper.getSerializationConfig().getAnnotationIntrospector()),
                ignoreDisablingIntrospector(mapper.getDeserializationConfig().getAnnotationIntrospector()));
    }

    private static AnnotationIntrospector ignoreDisablingIntrospector(AnnotationIntrospector delegate) {
        return new IgnoreDisablingAnnotationIntrospector(
                delegate == null ? new NopAnnotationIntrospector() {} : delegate);
    }

    private static final class IgnoreDisablingAnnotationIntrospector extends AnnotationIntrospectorPair {
        private IgnoreDisablingAnnotationIntrospector(AnnotationIntrospector delegate) {
            super(delegate, new NopAnnotationIntrospector() {});
        }

        @Override
        public boolean hasIgnoreMarker(AnnotatedMember m) {
            return false;
        }
    }
}
