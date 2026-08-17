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

package io.fluxzero.common.api;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.serialization.NullCollectionsAsEmptyModule;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;

/**
 * Represents immutable metadata associated with a Message in the Fluxzero Runtime.
 * <p>
 * {@code Metadata} is a type-safe, JSON-serializable key–value store where all values are encoded as strings. It
 * supports fluent creation, transformation, and querying, and is designed to be passed along with messages to provide
 * context such as routing keys, user identity, correlation IDs, HTTP headers, and custom tracing information.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Immutable, fluent API (e.g. {@code metadata.with("key", value)})</li>
 *   <li>Auto-serializes arbitrary objects to JSON strings using Jackson</li>
 *   <li>Supports optional, lazy deserialization via {@code get(key, Class)}</li>
 *   <li>Includes built-in support for trace propagation via {@code withTrace()}</li>
 *   <li>Provides {@link #entrySet}, {@link #containsKey}}, {@link #getOrDefault} ()}, etc.</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Metadata metadata = Metadata.of("correlationId", "1234")
 *                             .with("userId", currentUser.getId())
 *                             .withTrace("workflow", "CreateOrder");
 * }</pre>
 */
public final class Metadata {
    /**
     * Type stored in the {@link Data} envelope returned by {@link #toData()}.
     */
    public static final String DATA_TYPE = Metadata.class.getName();

    /**
     * Compact binary format used to carry metadata opaquely through the runtime.
     */
    public static final String DATA_FORMAT = "application/vnd.fluxzero.metadata.v1";

    private static final int MAX_ENTRY_COUNT = 2_000_000;
    private static final int MAX_DATA_BYTES = 512 * 1024 * 1024;
    static final int UNKNOWN_CHUNK_STATUS = -1;
    static final int CHUNKED_STATUS = 1;
    static final int LAST_CHUNK_STATUS = 1 << 1;
    static final int FIRST_CHUNK_STATUS = 1 << 2;
    public static JsonMapper objectMapper = JsonMapper.builder()
            .findAndAddModules().addModule(new NullCollectionsAsEmptyModule())
            .disable(FAIL_ON_EMPTY_BEANS).disable(FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Metadata EMPTY =
            new Metadata(emptyMap());

    private final Object source;
    private final int compactSize;
    private volatile Map<String, String> entries;
    private volatile Data<byte[]> data;
    private volatile int chunkStatus = UNKNOWN_CHUNK_STATUS;

    /**
     * Retrieves a map of entries where the keys and values are strings.
     *
     * @return a map containing the entries with string keys and values
     */
    @JsonAnyGetter
    public Map<String, String> getEntries() {
        return materialize();
    }

    /**
     * Creates a new {@code Metadata} instance with the provided key-value pairs. This method generates a non-empty
     * metadata object by associating the specified keys with their corresponding values.
     *
     * @param keyValues an alternating sequence of keys and values. The number of elements must be even, where keys are
     *                  instances of {@code Object} and corresponding values follow them.
     * @return a {@code Metadata} instance containing the specified key-value pairs.
     * @throws IllegalArgumentException if the number of key-value arguments is not even.
     */
    public static Metadata of(Object... keyValues) {
        return Metadata.empty().with(keyValues);
    }

    /**
     * Creates an empty instance of the Metadata class with no entries.
     *
     * @return a Metadata instance with no key-value pairs.
     */
    public static Metadata empty() {
        return EMPTY;
    }

    /**
     * Creates a compact metadata builder for values that are already normalized to strings.
     *
     * <p>The builder avoids an intermediate hash table and retains a compact immutable representation until the
     * metadata reaches a serialization boundary. Repeated keys replace their earlier value, matching ordinary
     * {@link Map#put(Object, Object)} semantics.</p>
     *
     * @param expectedEntries expected number of distinct metadata keys
     * @return a new builder
     */
    public static Builder builder(int expectedEntries) {
        return new Builder(expectedEntries);
    }

    /**
     * Builder for compact metadata whose keys and values are already strings.
     */
    public static final class Builder {
        private String[] entries;
        private int size;
        private boolean shared;

        private Builder(int expectedEntries) {
            if (expectedEntries < 0 || expectedEntries > MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Invalid expected metadata entry count " + expectedEntries);
            }
            entries = new String[Math.multiplyExact(expectedEntries, 2)];
        }

        /**
         * Adds or replaces a metadata value. A {@code null} value removes the key, matching
         * {@link Metadata#with(Object, Object)}.
         *
         * @return this builder
         */
        public Builder put(@NonNull String key, String value) {
            for (int index = 0; index < size; index++) {
                int keyIndex = index * 2;
                if (entries[keyIndex].equals(key)) {
                    if (value == null) {
                        ensureMutable();
                        int nextIndex = keyIndex + 2;
                        int remaining = size * 2 - nextIndex;
                        if (remaining > 0) {
                            System.arraycopy(entries, nextIndex, entries, keyIndex, remaining);
                        }
                        int clearedIndex = --size * 2;
                        entries[clearedIndex] = null;
                        entries[clearedIndex + 1] = null;
                    } else {
                        ensureMutable();
                        entries[keyIndex + 1] = value;
                    }
                    return this;
                }
            }
            if (value == null) {
                return this;
            }
            if (size == MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Metadata contains too many entries");
            }
            ensureCapacity(size + 1);
            int keyIndex = size++ * 2;
            entries[keyIndex] = key;
            entries[keyIndex + 1] = value;
            return this;
        }

        /**
         * Adds or replaces normalized metadata values.
         *
         * @return this builder
         */
        public Builder putAll(Map<String, String> values) {
            values.forEach(this::put);
            return this;
        }

        /**
         * Adds or replaces the trace entries from existing metadata without materializing an intermediate map.
         *
         * @return this builder
         */
        public Builder putTraceEntries(@NonNull Metadata metadata) {
            metadata.forEachTraceEntry(this::put);
            return this;
        }

        /**
         * Builds immutable metadata that remains compact until its serialized representation is needed.
         */
        public Metadata build() {
            if (size == 0) {
                return empty();
            }
            shared = true;
            return new Metadata(entries, size);
        }

        private void ensureCapacity(int requiredEntries) {
            int requiredLength = Math.multiplyExact(requiredEntries, 2);
            if (shared || requiredLength > entries.length) {
                int nextLength = entries.length;
                if (requiredLength > nextLength) {
                    int growth = Math.max(8, nextLength >>> 1);
                    nextLength = Math.max(requiredLength, nextLength + growth);
                }
                entries = Arrays.copyOf(entries, nextLength);
                shared = false;
            }
        }

        private void ensureMutable() {
            if (shared) {
                entries = entries.clone();
                shared = false;
            }
        }
    }

    /**
     * Creates a new {@code Metadata} instance with a single key-value pair.
     *
     * @param key   the key to be included in the metadata, not null
     * @param value the value associated with the key, can be null
     * @return a new {@code Metadata} instance containing the provided key-value pair
     */
    public static Metadata of(Object key, Object value) {
        return Metadata.empty().with(key, value);
    }

    /**
     * Creates a new instance of {@code Metadata} populated with the given map.
     *
     * @param map the map containing key-value pairs to populate the metadata. The keys and values are expected to be
     *            convertible to strings.
     * @return a new {@code Metadata} instance containing the key-value pairs from the provided map.
     */
    public static Metadata of(Map<?, ?> map) {
        return Metadata.empty().with(map);
    }

    /**
     * Creates immutable metadata from values that are already normalized to strings.
     *
     * <p>This avoids the conversion copy used by {@link #of(Map)} and is intended for trusted serialization
     * decoders.</p>
     */
    public static Metadata ofStrings(
            Map<String, String> entries) {
        return entries.isEmpty()
                ? empty()
                : new Metadata(Map.copyOf(entries));
    }

    /**
     * Restores metadata from its compact serialized representation without eagerly constructing keys, values or a
     * backing map. The metadata is decoded only when an entry is inspected or changed.
     *
     * <p>The supplied data remains opaque while a message is routed, stored or forwarded. This lets infrastructure
     * preserve application metadata without repeatedly serializing and deserializing it.</p>
     */
    public static Metadata fromData(@NonNull Data<byte[]> data) {
        return fromData(data, UNKNOWN_CHUNK_STATUS);
    }

    static Metadata fromData(@NonNull Data<byte[]> data, int chunkStatus) {
        if (!DATA_TYPE.equals(data.getType()) || !DATA_FORMAT.equals(data.getFormat()) || data.getRevision() != 0) {
            throw new IllegalArgumentException("Unsupported serialized metadata descriptor: " + data);
        }
        if (chunkStatus < UNKNOWN_CHUNK_STATUS
            || chunkStatus > (CHUNKED_STATUS | LAST_CHUNK_STATUS | FIRST_CHUNK_STATUS)) {
            throw new IllegalArgumentException("Invalid metadata chunk status " + chunkStatus);
        }
        return new Metadata(data, chunkStatus);
    }

    static boolean containsKey(
            byte[] data, int offset, int length, String key) {
        return MetadataBinaryCodec.containsKey(data, offset, length, key);
    }

    static String get(
            byte[] data, int offset, int length, String key) {
        return MetadataBinaryCodec.get(data, offset, length, key);
    }

    static long getLong(
            byte[] data, int offset, int length, String key, long defaultValue) {
        return MetadataBinaryCodec.getLong(data, offset, length, key, defaultValue);
    }

    /**
     * Returns the compact serialized representation of this metadata.
     *
     * <p>Metadata that originated in serialized form returns that same {@link Data} instance until it is changed.
     * Metadata constructed from entries is encoded at most once per metadata instance.</p>
     */
    @JsonIgnore
    public Data<byte[]> toData() {
        Data<byte[]> current = data;
        if (current == null) {
            synchronized (this) {
                current = data;
                if (current == null) {
                    byte[] encoded = source instanceof String[] compact
                            ? MetadataBinaryCodec.encode(compact, compactSize, this)
                            : MetadataBinaryCodec.encode(materialize(), this);
                    data = current = new Data<>(encoded, DATA_TYPE, 0, DATA_FORMAT);
                }
            }
        }
        return current;
    }

    int chunkStatus() {
        toData();
        return chunkStatus;
    }

    /**
     * Constructs a new Metadata instance with the specified map of entries. The entries map defines the key-value pairs
     * for this Metadata object.
     *
     * @param entries a map containing key-value pairs representing the metadata. Keys and values must be non-null and
     *                of type String.
     */
    @JsonCreator
    private Metadata(Map<String, String> entries) {
        this.source = Objects.requireNonNull(entries, "entries");
        this.compactSize = -1;
        this.entries = entries;
    }

    private Metadata(String[] entries, int size) {
        this.source = Objects.requireNonNull(entries, "entries");
        this.compactSize = size;
    }

    private Metadata(Data<byte[]> data, int chunkStatus) {
        this.source = Objects.requireNonNull(data, "data");
        this.compactSize = -1;
        this.data = data;
        this.chunkStatus = chunkStatus;
    }

    /**
     * Returns the string representation of this object, which is the string representation of the underlying entries
     * map.
     *
     * @return the string representation of the entries map
     */
    @Override
    public String toString() {
        return materialize().toString();
    }

    /*
        Add
     */

    /**
     * Returns a new Metadata instance that includes all the current entries and the mappings provided in the given map.
     * If a key in the given map already exists in the current entries, its value will be overwritten.
     *
     * @param values a map containing the key-value pairs to be added or updated in the metadata
     * @return a new Metadata instance with the updated entries
     */
    public Metadata with(Map<?, ?> values) {
        if (values.isEmpty()) {
            return this;
        }
        if (hasCompactEntries() && hasOnlyStringEntries(values)) {
            @SuppressWarnings("unchecked")
            Map<String, String> stringValues = (Map<String, String>) values;
            return withSerializedChanges(stringValues);
        }
        Map<String, String> map = new HashMap<>(materialize());
        values.forEach((key, value) -> with(key, value, map));
        return new Metadata(map);
    }

    private static boolean hasOnlyStringEntries(Map<?, ?> values) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private Metadata withSerializedChanges(Map<String, String> values) {
        byte[] merged = MetadataBinaryCodec.merge(toData(), values);
        return fromData(new Data<>(merged, DATA_TYPE, 0, DATA_FORMAT));
    }

    /**
     * Creates a new instance of {@code Metadata} by combining the current metadata with the given metadata.
     *
     * @param metadata the {@code Metadata} containing entries to be added to the current instance
     * @return a new {@code Metadata} instance that includes all entries from the current instance and the provided
     * metadata
     */
    public Metadata with(Metadata metadata) {
        if (metadata.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return metadata;
        }
        if (hasCompactEntries() && metadata.hasCompactEntries()) {
            return fromData(new Data<>(
                    MetadataBinaryCodec.merge(toData(), metadata.toData()),
                    DATA_TYPE, 0, DATA_FORMAT));
        }
        Map<String, String> map = new HashMap<>(materialize());
        map.putAll(metadata.materialize());
        return new Metadata(map);
    }

    /**
     * Creates a new {@code Metadata} instance by adding the specified key-value pairs. For each pair of values
     * provided, the first value is used as the key (converted to a string), and the second value is used as the value.
     * If an odd number of arguments is provided, an {@link IllegalArgumentException} is thrown.
     *
     * @param keyValues an alternating sequence of keys and values. Each key is converted to a string, and each value is
     *                  added as a corresponding value in the metadata. The number of arguments must be even, with each
     *                  key followed by its value.
     * @return a new {@code Metadata} instance containing the updated entries.
     * @throws IllegalArgumentException if the number of provided arguments is not even.
     */
    public Metadata with(Object... keyValues) {
        if (keyValues.length % 2 == 1) {
            throw new IllegalArgumentException("Failed to create metadata for keys " + Arrays.toString(keyValues));
        }
        Map<String, String> map = new HashMap<>(materialize());
        for (int i = 0; i < keyValues.length; i += 2) {
            with(keyValues[i].toString(), keyValues[i + 1], map);
        }
        return new Metadata(map);
    }

    /**
     * Returns a new {@code Metadata} instance with the specified key-value pair added or updated in the current
     * entries.
     * <p>
     * If the value is null, the key is removed. If the value is an {@code Optional} that is empty, no changes are
     * made.
     *
     * @param key   the key to add or update in the metadata entries
     * @param value the value associated with the key; if null, the key will be removed; if an {@code Optional} is
     *              empty, no change occurs
     * @return a new {@code Metadata} instance with the updated entries
     */
    @SneakyThrows
    public Metadata with(Object key, Object value) {
        String keyString = Objects.requireNonNull(key, "Metadata key").toString();
        if (value instanceof String stringValue && hasCompactEntries()) {
            return withCompactString(keyString, stringValue);
        }
        if (value instanceof Enum<?> enumValue && hasCompactEntries()) {
            return withCompactString(keyString, enumValue.name());
        }
        return new Metadata(with(key, value, new HashMap<>(materialize())));
    }

    private boolean hasCompactEntries() {
        return entries == null;
    }

    /**
     * Returns a new {@code Metadata} instance with an explicit null value associated with the given key.
     *
     * @param key   the key to add or update in the metadata entries
     * @return a new {@code Metadata} instance with the updated entries
     */
    @SneakyThrows
    public Metadata withNull(Object key) {
        var map = new HashMap<>(materialize());
        map.put(key.toString(), objectMapper.writeValueAsString(null));
        return new Metadata(map);
    }

    /**
     * Adds the specified key-value pair to the metadata if the key is not already present.
     *
     * @param key   the key to check and potentially add to the metadata
     * @param value the value to associate with the key if the key is absent
     * @return the current metadata instance if the key is already present, or a new metadata instance with the
     * key-value pair added if the key was absent
     */
    public Metadata addIfAbsent(Object key, Object value) {
        return containsKey(key) ? this : with(key, value);
    }

    /**
     * Adds all entries from the specified map to the current {@code Metadata}, ignoring keys that already exist. If a
     * key in the provided map already exists in this {@code Metadata}, it will be excluded from the operation.
     *
     * @param map the map containing entries to be added, unless the keys already exist in this {@code Metadata}
     * @return a new {@code Metadata} instance with the combined entries from the original and the provided map,
     * excluding entries with duplicate keys
     */
    public Metadata addIfAbsent(Map<?, ?> map) {
        map = new HashMap<>(map);
        map.keySet().removeIf(this::containsKey);
        return with(map);
    }

    /**
     * Updates a map with a given key-value pair. If the value is null, the key is removed from the map. If the value is
     * an Optional and empty, it does not modify the map. If the value is non-null, it is added to the map. Non-String
     * values are serialized into a JSON string representation.
     *
     * @param key     the key to be added or updated in the map; must not be null
     * @param value   the value to associate with the given key; can be null or an Optional
     * @param entries the map in which the key-value pair is added or updated
     * @return the updated map with the provided key-value modifications applied
     */
    @SneakyThrows
    private static Map<String, String> with(@NonNull Object key, Object value, Map<String, String> entries) {
        String keyString = key.toString();
        if (value == null) {
            entries.remove(keyString);
            return entries;
        }
        if (value instanceof Optional<?> optional) {
            if (optional.isEmpty()) {
                return entries;
            }
            value = optional.get();
        }
        if (value instanceof Enum<?> e) {
            value = e.name();
        }
        entries.put(keyString, value instanceof String ? (String) value : objectMapper.writeValueAsString(value));
        return entries;
    }


    /**
     * Adds a trace entry to the provided map of entries. The trace key is prefixed with "$trace." followed by the
     * string representation of the provided key. If the value is non-null, it is added to the entries map. If the value
     * is null, the entry with the trace key is removed from the map.
     *
     * @param key     the key to be prefixed for the trace entry
     * @param value   the value to be associated with the trace key; if null, the key is removed
     * @param entries the map of entries to be updated with the trace key-value pair
     * @return the updated map of entries
     */
    @SneakyThrows
    private static Map<String, String> withTrace(Object key, Object value, Map<String, String> entries) {
        return with("$trace." + key, value, entries);
    }

    /**
     * Adds a trace entry with the specified key and value to the metadata. The trace key is prefixed with "$trace.".
     *
     * @param key   the key for the trace entry, which will be prefixed with "$trace."
     * @param value the value associated with the specified key
     * @return a new Metadata instance containing the updated trace entries
     */
    @SneakyThrows
    public Metadata withTrace(Object key, Object value) {
        return new Metadata(withTrace(key, value, new HashMap<>(materialize())));
    }

    /*
        Remove
     */

    /**
     * Returns a new Metadata instance without the specified key. If the given key exists in the original entries, it
     * will be removed in the resulting Metadata instance. The original Metadata object remains unmodified.
     *
     * @param key the key to be removed from the Metadata entries
     * @return a new Metadata instance with the specified key removed
     */
    public Metadata without(Object key) {
        Map<String, String> map = new HashMap<>(materialize());
        map.remove(key.toString());
        return new Metadata(map);
    }

    /**
     * Returns a new instance of Metadata, excluding all entries where the provided predicate evaluates to true for the
     * entry keys.
     *
     * @param check a predicate that determines which keys should be excluded. If the predicate returns true for a key,
     *              the key-value pair will be removed.
     * @return a new Metadata object with the specified entries removed.
     */
    public Metadata withoutIf(Predicate<String> check) {
        Map<String, String> map = new HashMap<>(materialize());
        Iterator<String> iterator = map.keySet().iterator();
        iterator.forEachRemaining(key -> {
            if (check.test(key)) {
                iterator.remove();
            }
        });
        return new Metadata(map);
    }

    /*
        Query
     */

    /**
     * Retrieves the value associated with the given key from the entries map.
     *
     * @param key the key whose associated value is to be returned. It should be an object, and its string
     *            representation will be used to query the map.
     * @return the value associated with the specified key, or null if the key is not present in the map.
     */
    public String get(Object key) {
        String stringKey = key.toString();
        Map<String, String> current = entries;
        if (current != null) {
            return current.get(stringKey);
        }
        if (source instanceof String[] compact) {
            for (int index = 0; index < compactSize; index++) {
                if (compact[index * 2].equals(stringKey)) {
                    return compact[index * 2 + 1];
                }
            }
            return null;
        }
        return MetadataBinaryCodec.get(toData(), stringKey);
    }

    /**
     * Retrieves the value associated with the provided key, if it exists, wrapped in an Optional.
     *
     * @param key the key whose associated value is to be returned, must not be null
     * @return an Optional containing the value associated with the key, or an empty Optional if no value is found
     */
    public Optional<String> getOptionally(Object key) {
        return Optional.ofNullable(get(key));
    }

    private static boolean hasObjectEncoding(String value) {
        int start = skipWhitespace(value, 0);
        if (start == value.length()) {
            return false;
        }
        int end = value.length() - 1;
        while (end > start && Character.isWhitespace(value.charAt(end))) {
            end--;
        }
        return value.charAt(start) == '{' && value.charAt(end) == '}'
               || value.charAt(start) == '[' && value.charAt(end) == ']';
    }

    private static int skipWhitespace(String value, int offset) {
        while (offset < value.length() && Character.isWhitespace(value.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    /**
     * Retrieves the value associated with the given key and attempts to deserialize it into the specified type.
     *
     * @param <T>  the type into which the value should be deserialized
     * @param key  the key used to look up the value
     * @param type the class object representing the type to which the value will be converted
     * @return the deserialized value if the key exists and the conversion is successful; null if the key does not exist
     * or the value is null
     * @throws IllegalStateException if deserialization fails
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @SneakyThrows
    public <T> T get(Object key, Class<T> type) {
        String value = get(key);
        if (value == null) {
            return null;
        }
        if (String.class.isAssignableFrom(type)) {
            return (T) value;
        }
        if (type.isEnum()) {
            return (T) Enum.valueOf((Class<Enum>) type, value);
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (IOException e) {
            throw new IllegalStateException(format("Failed to deserialize value %s to a %s for key %s",
                                                   value, type.getSimpleName(), key), e);
        }
    }

    /**
     * Retrieves an object value or maps a raw string value to the requested type.
     * <p>
     * Values enclosed by matching object or array delimiters are first deserialized using this metadata instance's
     * object mapper. If such a value is not syntactically valid JSON, or if it is not enclosed by those delimiters, it
     * is passed to {@code stringMapper}. A mapping failure for valid JSON remains a deserialization error. Missing and
     * explicitly null values return {@code null}.
     *
     * @param key          the key whose value should be retrieved
     * @param type         the object type to deserialize
     * @param stringMapper maps a raw string value to the requested type
     * @param <T>          the requested type
     * @return the deserialized or mapped value, or {@code null} if the value is missing or explicitly null
     * @throws IllegalStateException if a valid JSON value cannot be mapped to the requested type
     */
    public <T> T get(Object key, Class<? extends T> type, Function<String, ? extends T> stringMapper) {
        String value = get(key);
        if (value == null || "null".equals(value)) {
            return null;
        }
        if (!hasObjectEncoding(value)) {
            return stringMapper.apply(value);
        }
        JsonNode tree;
        try {
            tree = objectMapper.reader().with(FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (IOException e) {
            return stringMapper.apply(value);
        }
        try {
            return objectMapper.treeToValue(tree, type);
        } catch (IOException e) {
            throw new IllegalStateException(format("Failed to deserialize value %s to a %s for key %s",
                                                   value, type.getSimpleName(), key), e);
        }
    }

    /**
     * Retrieves an object associated with the given key, attempts to deserialize it to the specified type, and returns
     * it wrapped in an {@code Optional}. If the object is not present, an empty {@code Optional} is returned.
     *
     * @param <T>  the type of the object to be retrieved
     * @param key  the key used to identify the object
     * @param type the class of the type to cast the retrieved object to
     * @return an {@code Optional} containing the object if present and of the specified type, or an empty
     * {@code Optional} otherwise
     */
    public <T> Optional<T> getOptionally(Object key, Class<T> type) {
        return Optional.ofNullable(get(key, type));
    }

    /**
     * Retrieves a value associated with the given key, deserializes it to the specified type, and returns it.
     *
     * @param key  The key whose associated value is to be retrieved.
     * @param type The type reference indicating the type to which the retrieved value should be deserialized.
     * @param <T>  The type of the returned value.
     * @return The deserialized value of the specified type associated with the given key, or null if no value is found.
     * @throws IllegalStateException if an error occurs during deserialization.
     */
    @SneakyThrows
    public <T> T get(Object key, TypeReference<T> type) {
        String value = get(key);
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (IOException e) {
            throw new IllegalStateException(format("Failed to deserialize value %s to a %s for key %s",
                                                   value, type, key), e);
        }
    }

    /**
     * Retrieves an object associated with the given key, deserializes it to the specified type, and returns it wrapped
     * in an {@code Optional}. If the object is not present, an empty {@code Optional} is returned.
     *
     * @param <T>  the type of the object to be retrieved
     * @param key  the key associated with the object to retrieve
     * @param type the type reference indicating the expected type of the object
     * @return an {@link Optional} containing the object if found, or an empty {@link Optional} if not found
     */
    public <T> Optional<T> getOptionally(Object key, TypeReference<T> type) {
        return Optional.ofNullable(get(key, type));
    }

    /**
     * Retrieves the value associated with the specified key or throws an exception provided by the given error supplier
     * if the key does not exist or has a null value.
     *
     * @param key           the key whose associated value is to be returned
     * @param errorSupplier a supplier that provides the exception to be thrown if the key is not found or the value is
     *                      null
     * @param <X>           the type of exception that the error supplier provides
     * @return the value associated with the specified key
     * @throws X if the key does not exist or the associated value is null
     */
    public <X extends Throwable> String getOrThrow(Object key, Supplier<? extends X> errorSupplier) throws X {
        return Optional.ofNullable(get(key)).orElseThrow(errorSupplier);
    }

    /**
     * Retrieves a map containing only the entries from the metadata whose keys start with the prefix "$trace.".
     *
     * @return a map of trace-specific entries where keys start with "$trace."
     */
    @JsonIgnore
    public Map<String, String> getTraceEntries() {
        Map<String, String> current = entries;
        if (current != null) {
            return traceEntries(current);
        }
        if (source instanceof String[] compact) {
            Map<String, String> result = new HashMap<>();
            for (int index = 0; index < compactSize; index++) {
                String key = compact[index * 2];
                if (key.startsWith("$trace.")) {
                    result.put(key, compact[index * 2 + 1]);
                }
            }
            return result;
        }
        return MetadataBinaryCodec.traceEntries(toData());
    }

    private void forEachTraceEntry(BiConsumer<String, String> consumer) {
        Map<String, String> current = entries;
        if (current != null) {
            current.forEach((key, value) -> {
                if (key.startsWith("$trace.")) {
                    consumer.accept(key, value);
                }
            });
            return;
        }
        if (source instanceof String[] compact) {
            for (int index = 0; index < compactSize; index++) {
                String key = compact[index * 2];
                if (key.startsWith("$trace.")) {
                    consumer.accept(key, compact[index * 2 + 1]);
                }
            }
            return;
        }
        MetadataBinaryCodec.forEachTraceEntry(toData(), consumer);
    }

    private static Map<String, String> traceEntries(Map<String, String> source) {
        Map<String, String> result = new HashMap<>();
        source.forEach((key, value) -> {
            if (key.startsWith("$trace.")) {
                result.put(key, value);
            }
        });
        return result;
    }

    /**
     * Checks if the specified key is present in the entries map. The key is first converted to a string and then
     * checked against the map for existence.
     *
     * @param key the key to check for presence in the entries map; must not be null
     * @return true if the entries map contains the specified key, false otherwise
     */
    public boolean containsKey(Object key) {
        String stringKey = key.toString();
        Map<String, String> current = entries;
        if (current != null) {
            return current.containsKey(stringKey);
        }
        if (source instanceof String[] compact) {
            for (int index = 0; index < compactSize; index++) {
                if (compact[index * 2].equals(stringKey)) {
                    return true;
                }
            }
            return false;
        }
        return MetadataBinaryCodec.containsKey(toData(), stringKey);
    }

    /**
     * Checks if the given keys are present in the internal entries.
     *
     * @param keys the keys to check for presence
     * @return {@code true} if at least one of the provided keys exists, otherwise {@code false}
     */
    public boolean containsAnyKey(Object... keys) {
        return Arrays.stream(keys).anyMatch(this::containsKey);
    }

    /**
     * Determines if the specified key-value pair exists within the data structure.
     *
     * @param key   the key to check, must not be null
     * @param value the value to check, must not be null
     * @return true if the key-value pair exists, false otherwise
     */
    public boolean contains(@NonNull Object key, @NonNull Object value) {
        Object result = value instanceof String ? get(key) : get(key, value.getClass());
        return Objects.equals(result, value);
    }

    /**
     * Checks whether the current metadata contains all entries of the specified metadata.
     *
     * @param metadata the Metadata object to compare, ensuring to check if all its entries exist within the current
     *                 metadata.
     * @return true if the current metadata contains all entries from the specified metadata, false otherwise.
     */
    public boolean contains(@NonNull Metadata metadata) {
        return entrySet().containsAll(metadata.entrySet());
    }

    /**
     * Retrieves the value mapped to the specified key in the entries map. If the key is not found, returns the provided
     * default value.
     *
     * @param key          the key whose associated value is to be returned
     * @param defaultValue the value to return if the key is not found in the map
     * @return the value mapped to the specified key, or the default value if the key is not found
     */
    public String getOrDefault(Object key, String defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * Returns a set view of the mappings contained in this metadata object.
     *
     * @return a set of entries, where each entry represents a key-value mapping in the metadata.
     */
    public Set<Map.Entry<String, String>> entrySet() {
        return materialize().entrySet();
    }

    private boolean isEmpty() {
        Map<String, String> current = entries;
        if (current != null) {
            return current.isEmpty();
        }
        return source instanceof String[] ? compactSize == 0 : MetadataBinaryCodec.size(toData()) == 0;
    }

    private Metadata withCompactString(String key, String value) {
        if (!(source instanceof String[] compact)) {
            return fromData(new Data<>(MetadataBinaryCodec.merge(toData(), key, value), DATA_TYPE, 0, DATA_FORMAT));
        }
        int matchingIndex = -1;
        for (int index = 0; index < compactSize; index++) {
            if (compact[index * 2].equals(key)) {
                matchingIndex = index;
                break;
            }
        }
        int nextSize = matchingIndex < 0 ? Math.addExact(compactSize, 1) : compactSize;
        String[] result = new String[Math.multiplyExact(nextSize, 2)];
        int target = 0;
        for (int index = 0; index < compactSize; index++) {
            if (index != matchingIndex) {
                int sourceIndex = index * 2;
                result[target++] = compact[sourceIndex];
                result[target++] = compact[sourceIndex + 1];
            }
        }
        result[target] = key;
        result[target + 1] = value;
        return new Metadata(result, nextSize);
    }

    private Map<String, String> materialize() {
        Map<String, String> current = entries;
        if (current == null) {
            synchronized (this) {
                current = entries;
                if (current == null) {
                    if (source instanceof String[] compact) {
                        Map<String, String> result = new HashMap<>(
                                Math.max(16, (int) (compactSize / 0.75f) + 1));
                        for (int index = 0; index < compactSize; index++) {
                            result.put(compact[index * 2], compact[index * 2 + 1]);
                        }
                        current = Collections.unmodifiableMap(result);
                    } else {
                        current = MetadataBinaryCodec.decode(toData());
                    }
                    entries = current;
                }
            }
        }
        return current;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Metadata metadata
                && materialize().equals(metadata.materialize());
    }

    @Override
    public int hashCode() {
        return materialize().hashCode();
    }

    private static final class MetadataBinaryCodec {
        private static final int ENCODED_KEY_CACHE_SETS = 32;
        private static final int MAX_CACHED_KEY_CHARS = 128;
        private static final ThreadLocal<EncodedKeyCache> ENCODED_KEY_CACHE =
                ThreadLocal.withInitial(EncodedKeyCache::new);

        private MetadataBinaryCodec() {
        }

        private static byte[] encode(Map<String, String> entries, Metadata target) {
            BinaryWriter writer = new BinaryWriter(minimumEncodedSize(entries));
            writer.writeInt(entries.size());
            entries.forEach((key, value) ->
                                    writer.writeEntry(
                                            Objects.requireNonNull(key, "Metadata key"),
                                            Objects.requireNonNull(value, "Metadata value")));
            if (target != null) {
                target.chunkStatus = writer.chunkStatus();
            }
            return writer.toByteArray();
        }

        private static byte[] encode(String[] entries, int size, Metadata target) {
            BinaryWriter writer = new BinaryWriter(minimumEncodedSize(entries, size));
            writer.writeInt(size);
            for (int index = 0; index < size; index++) {
                int keyIndex = index * 2;
                writer.writeEntry(entries[keyIndex], entries[keyIndex + 1]);
            }
            target.chunkStatus = writer.chunkStatus();
            return writer.toByteArray();
        }

        private static int minimumEncodedSize(Map<String, String> entries) {
            int size = Integer.BYTES;
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "Metadata key");
                String value = Objects.requireNonNull(entry.getValue(), "Metadata value");
                size = addSize(size, 2 * Integer.BYTES);
                size = addSize(size, key.length());
                size = addSize(size, value.length());
            }
            return size;
        }

        private static int minimumEncodedSize(String[] entries, int entryCount) {
            int size = Integer.BYTES;
            for (int index = 0; index < entryCount; index++) {
                int keyIndex = index * 2;
                size = addSize(size, 2 * Integer.BYTES);
                size = addSize(size, entries[keyIndex].length());
                size = addSize(size, entries[keyIndex + 1].length());
            }
            return size;
        }

        private static int stringSize(String value) {
            return addSize(Integer.BYTES, BinaryWire.utf8Length(value));
        }

        private static int addSize(int current, int addition) {
            int result;
            try {
                result = Math.addExact(current, addition);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Serialized metadata exceeds maximum size", e);
            }
            if (result > MAX_DATA_BYTES) {
                throw new IllegalArgumentException("Serialized metadata exceeds maximum size");
            }
            return result;
        }

        private static int size(Data<byte[]> data) {
            BinaryWire.Cursor reader = cursor(data);
            int result = reader.readInt();
            if (result < 0 || result > MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Invalid serialized metadata entry count " + result);
            }
            return result;
        }

        private static Map<String, String> decode(Data<byte[]> data) {
            BinaryWire.Cursor reader = cursor(data);
            int size = readSize(reader);
            if (size == 0) {
                reader.requireComplete();
                return emptyMap();
            }
            Map<String, String> result = new HashMap<>(Math.max(16, (int) (size / 0.75f) + 1));
            for (int i = 0; i < size; i++) {
                result.put(reader.readString(MAX_DATA_BYTES), reader.readString(MAX_DATA_BYTES));
            }
            reader.requireComplete();
            return java.util.Collections.unmodifiableMap(result);
        }

        private static boolean containsKey(Data<byte[]> data, String key) {
            return findValue(data, key) >= 0;
        }

        private static boolean containsKey(
                byte[] data, int offset, int length, String key) {
            return findValue(data, offset, length, key) >= 0;
        }

        private static String get(Data<byte[]> data, String key) {
            long value = findValue(data, key);
            if (value < 0) {
                return null;
            }
            Data.ByteArrayView view = data.byteArrayView();
            byte[] bytes = view == null ? data.getValue() : view.array();
            return new String(bytes, (int) (value >>> Integer.SIZE), (int) value, StandardCharsets.UTF_8);
        }

        private static String get(
                byte[] data, int offset, int length, String key) {
            long value = findValue(data, offset, length, key);
            return value < 0 ? null : new String(
                    data, (int) (value >>> Integer.SIZE), (int) value,
                    StandardCharsets.UTF_8);
        }

        private static long getLong(
                byte[] data, int offset, int length, String key, long defaultValue) {
            long value = findValue(data, offset, length, key);
            if (value < 0) {
                return defaultValue;
            }
            return parseLong(
                    data, (int) (value >>> Integer.SIZE), (int) value,
                    defaultValue);
        }

        private static long parseLong(
                byte[] bytes, int offset, int length, long defaultValue) {
            if (length == 0) {
                return defaultValue;
            }
            int position = offset;
            int limitPosition = offset + length;
            boolean negative = false;
            byte first = bytes[position];
            if (first == '-' || first == '+') {
                negative = first == '-';
                if (++position == limitPosition) {
                    return defaultValue;
                }
            }
            long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
            long multiplyLimit = limit / 10;
            long result = 0;
            while (position < limitPosition) {
                int digit = bytes[position++] - '0';
                if (digit < 0 || digit > 9
                    || result < multiplyLimit) {
                    return defaultValue;
                }
                result *= 10;
                if (result < limit + digit) {
                    return defaultValue;
                }
                result -= digit;
            }
            return negative ? result : -result;
        }

        /**
         * Scans a byte-array view without allocating a stateful reader. The returned long packs the offset and length
         * of the last value for the requested key, or {@code -1} when the key is absent.
         */
        private static long findValue(Data<byte[]> data, String key) {
            Data.ByteArrayView view = data.byteArrayView();
            byte[] bytes = view == null ? data.getValue() : view.array();
            int offset = view == null ? 0 : view.offset();
            int length = view == null ? (bytes == null ? 0 : bytes.length) : view.length();
            return findValue(bytes, offset, length, key);
        }

        private static long findValue(byte[] bytes, int offset, int length, String key) {
            Objects.requireNonNull(key, "Metadata key");
            validate(bytes, offset, length);
            int position = offset;
            int limit = offset + length;
            if (limit - position < Integer.BYTES) {
                throw new IllegalArgumentException("Truncated serialized metadata");
            }
            int size = BinaryWire.peekInt(bytes, position);
            position += Integer.BYTES;
            if (size < 0 || size > MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Invalid serialized metadata entry count " + size);
            }

            long result = -1;
            for (int index = 0; index < size; index++) {
                if (limit - position < Integer.BYTES) {
                    throw new IllegalArgumentException("Truncated serialized metadata");
                }
                int keyLength = BinaryWire.peekInt(bytes, position);
                position += Integer.BYTES;
                if (keyLength < 0 || keyLength > MAX_DATA_BYTES || keyLength > limit - position) {
                    throw new IllegalArgumentException("Invalid serialized metadata string size " + keyLength);
                }
                boolean matches = BinaryWire.utf8Equals(bytes, position, keyLength, key);
                position += keyLength;

                if (limit - position < Integer.BYTES) {
                    throw new IllegalArgumentException("Truncated serialized metadata");
                }
                int valueLength = BinaryWire.peekInt(bytes, position);
                position += Integer.BYTES;
                if (valueLength < 0 || valueLength > MAX_DATA_BYTES || valueLength > limit - position) {
                    throw new IllegalArgumentException("Invalid serialized metadata string size " + valueLength);
                }
                if (matches) {
                    result = ((long) position << Integer.SIZE) | (valueLength & 0xffffffffL);
                }
                position += valueLength;
            }
            if (position != limit) {
                throw new IllegalArgumentException("Unexpected trailing serialized metadata bytes");
            }
            return result;
        }

        private static Map<String, String> traceEntries(Data<byte[]> data) {
            BinaryWire.Cursor reader = cursor(data);
            int size = readSize(reader);
            Map<String, String> result = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String key = readStringIfStartsWith(reader, "$trace.");
                if (key == null) {
                    reader.skipString(MAX_DATA_BYTES);
                } else {
                    result.put(key, reader.readString(MAX_DATA_BYTES));
                }
            }
            reader.requireComplete();
            return result;
        }

        private static void forEachTraceEntry(Data<byte[]> data, BiConsumer<String, String> consumer) {
            BinaryWire.Cursor reader = cursor(data);
            int size = readSize(reader);
            for (int i = 0; i < size; i++) {
                String key = readStringIfStartsWith(reader, "$trace.");
                if (key == null) {
                    reader.skipString(MAX_DATA_BYTES);
                } else {
                    consumer.accept(key, reader.readString(MAX_DATA_BYTES));
                }
            }
            reader.requireComplete();
        }

        private static byte[] merge(Data<byte[]> data, Map<String, String> changes) {
            String[] keys = new String[changes.size()];
            String[] values = new String[changes.size()];
            int changeBytes = 0;
            int changeIndex = 0;
            for (Map.Entry<String, String> entry : changes.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "Metadata key");
                String value = Objects.requireNonNull(entry.getValue(), "Metadata value");
                keys[changeIndex] = key;
                values[changeIndex++] = value;
                changeBytes = addSize(changeBytes, stringSize(key));
                changeBytes = addSize(changeBytes, stringSize(value));
            }

            BinaryWire.Cursor reader = cursor(data);
            int baseSize = readSize(reader);
            int retainedBytes = 0;
            int replaced = 0;
            for (int index = 0; index < baseSize; index++) {
                int entryOffset = reader.position();
                int keyLength = reader.readStringLength(MAX_DATA_BYTES);
                int keyOffset = reader.position();
                reader.skip(keyLength);
                reader.skipString(MAX_DATA_BYTES);
                if (containsKey(reader.bytes(), keyOffset, keyLength, keys)) {
                    replaced++;
                } else {
                    retainedBytes = addSize(retainedBytes, reader.position() - entryOffset);
                }
            }
            reader.requireComplete();

            int encodedSize = addSize(Integer.BYTES, retainedBytes);
            encodedSize = addSize(encodedSize, changeBytes);
            BinaryWriter writer = new BinaryWriter(encodedSize);
            writer.writeInt(Math.addExact(baseSize - replaced, changes.size()));
            reader = cursor(data);
            readSize(reader);
            for (int index = 0; index < baseSize; index++) {
                int entryOffset = reader.position();
                int keyLength = reader.readStringLength(MAX_DATA_BYTES);
                int keyOffset = reader.position();
                reader.skip(keyLength);
                reader.skipString(MAX_DATA_BYTES);
                if (!containsKey(reader.bytes(), keyOffset, keyLength, keys)) {
                    writer.write(reader.bytes(), entryOffset, reader.position() - entryOffset);
                }
            }
            reader.requireComplete();
            for (int index = 0; index < keys.length; index++) {
                writer.writeString(keys[index]);
                writer.writeString(values[index]);
            }
            return writer.toByteArray();
        }

        private static byte[] merge(Data<byte[]> data, String key, String value) {
            Objects.requireNonNull(key, "Metadata key");
            Objects.requireNonNull(value, "Metadata value");
            BinaryWire.Cursor reader = cursor(data);
            int baseSize = readSize(reader);
            int retainedBytes = 0;
            int replaced = 0;
            for (int index = 0; index < baseSize; index++) {
                int start = reader.position();
                boolean matches = reader.readStringEquals(key, MAX_DATA_BYTES);
                reader.skipString(MAX_DATA_BYTES);
                if (matches) {
                    replaced++;
                } else {
                    retainedBytes = addSize(retainedBytes, reader.position() - start);
                }
            }
            reader.requireComplete();

            int encodedSize = addSize(Integer.BYTES, retainedBytes);
            encodedSize = addSize(encodedSize, stringSize(key));
            encodedSize = addSize(encodedSize, stringSize(value));
            BinaryWriter writer = new BinaryWriter(encodedSize);
            writer.writeInt(Math.addExact(baseSize - replaced, 1));
            reader = cursor(data);
            readSize(reader);
            for (int index = 0; index < baseSize; index++) {
                int start = reader.position();
                boolean matches = reader.readStringEquals(key, MAX_DATA_BYTES);
                reader.skipString(MAX_DATA_BYTES);
                if (!matches) {
                    writer.write(reader.bytes(), start, reader.position() - start);
                }
            }
            reader.requireComplete();
            writer.writeString(key);
            writer.writeString(value);
            return writer.toByteArray();
        }

        private static byte[] merge(Data<byte[]> base, Data<byte[]> changes) {
            BinaryWire.Cursor changeReader = cursor(changes);
            int changeSize = readSize(changeReader);
            int changeEntriesOffset = changeReader.position();
            int[] changeKeyOffsets = new int[changeSize];
            int[] changeKeyLengths = new int[changeSize];
            for (int index = 0; index < changeSize; index++) {
                int keyLength = changeReader.readStringLength(MAX_DATA_BYTES);
                changeKeyOffsets[index] = changeReader.position();
                changeKeyLengths[index] = keyLength;
                changeReader.skip(keyLength);
                changeReader.skipString(MAX_DATA_BYTES);
            }
            changeReader.requireComplete();

            BinaryWire.Cursor baseReader = cursor(base);
            int baseSize = readSize(baseReader);
            int retainedBytes = 0;
            int replaced = 0;
            for (int index = 0; index < baseSize; index++) {
                int entryOffset = baseReader.position();
                int keyLength = baseReader.readStringLength(MAX_DATA_BYTES);
                int keyOffset = baseReader.position();
                baseReader.skip(keyLength);
                baseReader.skipString(MAX_DATA_BYTES);
                if (containsRawKey(baseReader.bytes(), keyOffset, keyLength,
                                   changeReader.bytes(), changeKeyOffsets, changeKeyLengths)) {
                    replaced++;
                } else {
                    retainedBytes = addSize(retainedBytes, baseReader.position() - entryOffset);
                }
            }
            baseReader.requireComplete();

            int changeBytes = changeReader.position() - changeEntriesOffset;
            int encodedSize = addSize(Integer.BYTES, retainedBytes);
            encodedSize = addSize(encodedSize, changeBytes);
            BinaryWriter writer = new BinaryWriter(encodedSize);
            writer.writeInt(Math.addExact(baseSize - replaced, changeSize));
            baseReader = cursor(base);
            readSize(baseReader);
            for (int index = 0; index < baseSize; index++) {
                int entryOffset = baseReader.position();
                int keyLength = baseReader.readStringLength(MAX_DATA_BYTES);
                int keyOffset = baseReader.position();
                baseReader.skip(keyLength);
                baseReader.skipString(MAX_DATA_BYTES);
                if (!containsRawKey(baseReader.bytes(), keyOffset, keyLength,
                                    changeReader.bytes(), changeKeyOffsets, changeKeyLengths)) {
                    writer.write(baseReader.bytes(), entryOffset, baseReader.position() - entryOffset);
                }
            }
            baseReader.requireComplete();
            writer.write(changeReader.bytes(), changeEntriesOffset, changeBytes);
            return writer.toByteArray();
        }

        private static boolean containsRawKey(
                byte[] keyBytes, int keyOffset, int keyLength,
                byte[] candidateBytes, int[] candidateOffsets, int[] candidateLengths) {
            for (int candidateIndex = 0; candidateIndex < candidateOffsets.length; candidateIndex++) {
                if (candidateLengths[candidateIndex] != keyLength) {
                    continue;
                }
                int candidateOffset = candidateOffsets[candidateIndex];
                int index = 0;
                while (index < keyLength
                        && keyBytes[keyOffset + index] == candidateBytes[candidateOffset + index]) {
                    index++;
                }
                if (index == keyLength) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsKey(byte[] bytes, int offset, int length, String[] candidates) {
            for (String candidate : candidates) {
                if (BinaryWire.utf8Equals(bytes, offset, length, candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static BinaryWire.Cursor cursor(Data<byte[]> data) {
            Data.ByteArrayView view = data.byteArrayView();
            byte[] bytes = view == null ? data.getValue() : view.array();
            int offset = view == null ? 0 : view.offset();
            int length = view == null ? (bytes == null ? 0 : bytes.length) : view.length();
            return new BinaryWire.Cursor(bytes, offset, length, MAX_DATA_BYTES);
        }

        private static int readSize(BinaryWire.Cursor reader) {
            int result = reader.readInt();
            if (result < 0 || result > MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Invalid serialized metadata entry count " + result);
            }
            return result;
        }

        private static void validate(byte[] bytes, int offset, int length) {
            if (bytes == null || offset < 0 || length < 0
                    || offset > bytes.length - length || length > MAX_DATA_BYTES) {
                throw new IllegalArgumentException("Invalid serialized metadata size");
            }
        }

        private static String readStringIfStartsWith(BinaryWire.Cursor reader, String prefix) {
            int length = reader.readStringLength(MAX_DATA_BYTES);
            int position = reader.position();
            boolean matches = length >= prefix.length();
            for (int index = 0; matches && index < prefix.length(); index++) {
                matches = (reader.bytes()[position + index] & 0xff) == prefix.charAt(index);
            }
            String result = matches
                    ? new String(reader.bytes(), position, length, StandardCharsets.UTF_8) : null;
            reader.skip(length);
            return result;
        }

        private static final class BinaryWriter {
            private final BinaryWire.Writer writer;
            private int chunkStatus = LAST_CHUNK_STATUS | FIRST_CHUNK_STATUS;
            private EncodedKeyCache encodedKeyCache;

            private BinaryWriter(int initialSize) {
                writer = new BinaryWire.Writer(initialSize, MAX_DATA_BYTES);
            }

            private void writeInt(int value) {
                writer.writeInt(value);
            }

            private void writeEntry(String key, String value) {
                if (HasMetadata.FINAL_CHUNK.equals(key)) {
                    chunkStatus |= CHUNKED_STATUS;
                    chunkStatus = "true".equalsIgnoreCase(value)
                            ? chunkStatus | LAST_CHUNK_STATUS
                            : chunkStatus & ~LAST_CHUNK_STATUS;
                } else if (HasMetadata.FIRST_CHUNK.equals(key)) {
                    chunkStatus = "true".equalsIgnoreCase(value)
                            ? chunkStatus | FIRST_CHUNK_STATUS
                            : chunkStatus & ~FIRST_CHUNK_STATUS;
                }
                writeKey(key);
                writeString(value);
            }

            private int chunkStatus() {
                return chunkStatus;
            }

            private void writeKey(String key) {
                EncodedKeyCache cache = encodedKeyCache;
                if (cache == null) {
                    encodedKeyCache = cache = ENCODED_KEY_CACHE.get();
                }
                byte[] encoded = cache.encoded(key);
                if (encoded == null) {
                    writeString(key);
                    return;
                }
                writer.writeBytes(encoded);
            }

            private void writeString(String value) {
                writer.writeString(value);
            }

            private void write(byte[] value, int offset, int length) {
                writer.writeRaw(value, offset, length);
            }

            private byte[] toByteArray() {
                return writer.toByteArray();
            }
        }

        private static final class EncodedKeyCache {
            private final String[] keys = new String[ENCODED_KEY_CACHE_SETS * 2];
            private final byte[][] encoded = new byte[ENCODED_KEY_CACHE_SETS * 2][];
            private final String[] candidates = new String[ENCODED_KEY_CACHE_SETS];
            private final boolean[] replaceSecond = new boolean[ENCODED_KEY_CACHE_SETS];

            private byte[] encoded(String key) {
                if (key.length() > MAX_CACHED_KEY_CHARS) {
                    return null;
                }
                int hash = System.identityHashCode(key);
                int set = (hash ^ hash >>> 16) & (ENCODED_KEY_CACHE_SETS - 1);
                int first = set * 2;
                if (keys[first] == key) {
                    return encoded[first];
                }
                if (keys[first + 1] == key) {
                    return encoded[first + 1];
                }
                if (candidates[set] != key) {
                    candidates[set] = key;
                    return null;
                }
                int target = replaceSecond[set] ? first + 1 : first;
                replaceSecond[set] = !replaceSecond[set];
                byte[] result = key.getBytes(StandardCharsets.UTF_8);
                keys[target] = key;
                encoded[target] = result;
                candidates[set] = null;
                return result;
            }
        }

    }
}
