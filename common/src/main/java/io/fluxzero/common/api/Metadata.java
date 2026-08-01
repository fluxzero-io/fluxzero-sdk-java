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
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.serialization.NullCollectionsAsEmptyModule;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
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
@Value
public class Metadata {
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
    public static JsonMapper objectMapper = JsonMapper.builder()
            .findAndAddModules().addModule(new NullCollectionsAsEmptyModule())
            .disable(FAIL_ON_EMPTY_BEANS).disable(FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Metadata EMPTY =
            new Metadata(emptyMap());

    Map<String, String> entries;

    /**
     * Retrieves a map of entries where the keys and values are strings.
     *
     * @return a map containing the entries with string keys and values
     */
    @JsonAnyGetter
    public Map<String, String> getEntries() {
        return entries;
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
            return new Metadata(
                    new SerializedEntries(entries, size));
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
        if (!DATA_TYPE.equals(data.getType()) || !DATA_FORMAT.equals(data.getFormat()) || data.getRevision() != 0) {
            throw new IllegalArgumentException("Unsupported serialized metadata descriptor: " + data);
        }
        return new Metadata(new SerializedEntries(data));
    }

    static boolean containsKey(
            Data.ByteArrayView data, String key) {
        return MetadataBinaryCodec.containsKey(
                data, key);
    }

    static boolean containsKey(
            byte[] data, int offset, int length, String key) {
        return MetadataBinaryCodec.containsKey(data, offset, length, key);
    }

    static String get(
            Data.ByteArrayView data, String key) {
        return MetadataBinaryCodec.get(data, key);
    }

    static String get(
            byte[] data, int offset, int length, String key) {
        return MetadataBinaryCodec.get(data, offset, length, key);
    }

    /**
     * Returns the compact serialized representation of this metadata.
     *
     * <p>Metadata that originated in serialized form returns that same {@link Data} instance until it is changed.
     * Metadata constructed from entries is encoded at most once per metadata instance.</p>
     */
    @JsonIgnore
    public Data<byte[]> toData() {
        if (entries instanceof SerializedEntries serializedEntries) {
            return serializedEntries.data();
        }
        return new Data<>(MetadataBinaryCodec.encode(entries), DATA_TYPE, 0, DATA_FORMAT);
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
        this.entries = entries instanceof SerializedEntries
                ? entries : new SerializedEntries(entries);
    }

    /**
     * Returns the string representation of this object, which is the string representation of the underlying entries
     * map.
     *
     * @return the string representation of the entries map
     */
    @Override
    public String toString() {
        return entries.toString();
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
        if (hasOpaqueEntries() && hasOnlyStringEntries(values)) {
            @SuppressWarnings("unchecked")
            Map<String, String> stringValues = (Map<String, String>) values;
            return withSerializedChanges(stringValues);
        }
        Map<String, String> map = new HashMap<>(entries);
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
        if (metadata.entries.isEmpty()) {
            return this;
        }
        if (entries.isEmpty()) {
            return metadata;
        }
        if (hasOpaqueEntries() && metadata.hasOpaqueEntries()) {
            return fromData(new Data<>(
                    MetadataBinaryCodec.merge(toData(), metadata.toData()),
                    DATA_TYPE, 0, DATA_FORMAT));
        }
        Map<String, String> map = new HashMap<>(entries);
        map.putAll(metadata.entries);
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
        Map<String, String> map = new HashMap<>(entries);
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
        if (entries instanceof SerializedEntries serializedEntries
                && value instanceof String stringValue) {
            return new Metadata(
                    serializedEntries.with(
                            keyString, stringValue));
        }
        if (entries instanceof SerializedEntries serializedEntries
                && value instanceof Enum<?> enumValue) {
            return new Metadata(
                    serializedEntries.with(
                            keyString, enumValue.name()));
        }
        return new Metadata(with(key, value, new HashMap<>(entries)));
    }

    private boolean hasOpaqueEntries() {
        return entries instanceof SerializedEntries serializedEntries
               && serializedEntries.isOpaque();
    }

    /**
     * Returns a new {@code Metadata} instance with an explicit null value associated with the given key.
     *
     * @param key   the key to add or update in the metadata entries
     * @return a new {@code Metadata} instance with the updated entries
     */
    @SneakyThrows
    public Metadata withNull(Object key) {
        var map = new HashMap<>(entries);
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
        return new Metadata(withTrace(key, value, new HashMap<>(entries)));
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
        Map<String, String> map = new HashMap<>(entries);
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
        Map<String, String> map = new HashMap<>(entries);
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
        return entries.get(key.toString());
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
        return entries instanceof SerializedEntries serializedEntries
                ? serializedEntries.traceEntries()
                : traceEntries(entries);
    }

    private void forEachTraceEntry(BiConsumer<String, String> consumer) {
        if (entries instanceof SerializedEntries serializedEntries && serializedEntries.isOpaque()) {
            MetadataBinaryCodec.forEachTraceEntry(serializedEntries.data(), consumer);
            return;
        }
        entries.forEach((key, value) -> {
            if (key.startsWith("$trace.")) {
                consumer.accept(key, value);
            }
        });
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
        return entries.containsKey(key.toString());
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
        return entries.entrySet().containsAll(metadata.entries.entrySet());
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
        return entries.getOrDefault(key.toString(), defaultValue);
    }

    /**
     * Returns a set view of the mappings contained in this metadata object.
     *
     * @return a set of entries, where each entry represents a key-value mapping in the metadata.
     */
    public Set<Map.Entry<String, String>> entrySet() {
        return entries.entrySet();
    }

    private static final class SerializedEntries extends AbstractMap<String, String> {
        private volatile Data<byte[]> data;
        private volatile Map<String, String> decoded;
        private volatile String[] compact;
        private final int compactSize;

        private SerializedEntries(Data<byte[]> data) {
            this.data = data;
            compactSize = -1;
        }

        private SerializedEntries(Map<String, String> decoded) {
            this.decoded = Objects.requireNonNull(decoded, "entries");
            compactSize = -1;
        }

        private SerializedEntries(String[] compact, int compactSize) {
            this.compact = Objects.requireNonNull(compact, "entries");
            this.compactSize = compactSize;
        }

        private Data<byte[]> data() {
            Data<byte[]> current = data;
            if (current == null) {
                synchronized (this) {
                    current = data;
                    if (current == null) {
                        String[] compactEntries = compact;
                        current = new Data<>(
                                compactEntries == null
                                        ? MetadataBinaryCodec.encode(decoded())
                                        : MetadataBinaryCodec.encode(compactEntries, compactSize),
                                DATA_TYPE, 0, DATA_FORMAT);
                        data = current;
                        compact = null;
                    }
                }
            }
            return current;
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return decoded().entrySet();
        }

        @Override
        public String get(Object key) {
            Map<String, String> current = decoded;
            if (current != null) {
                return current.get(key);
            }
            String[] compactEntries = compact;
            if (compactEntries != null) {
                if (!(key instanceof String stringKey)) {
                    return null;
                }
                for (int index = 0; index < compactSize; index++) {
                    int keyIndex = index * 2;
                    if (compactEntries[keyIndex].equals(stringKey)) {
                        return compactEntries[keyIndex + 1];
                    }
                }
                return null;
            }
            return key instanceof String string
                    ? MetadataBinaryCodec.get(data(), string) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            Map<String, String> current = decoded;
            if (current != null) {
                return current.containsKey(key);
            }
            String[] compactEntries = compact;
            if (compactEntries != null) {
                if (!(key instanceof String stringKey)) {
                    return false;
                }
                for (int index = 0; index < compactSize; index++) {
                    if (compactEntries[index * 2].equals(stringKey)) {
                        return true;
                    }
                }
                return false;
            }
            return key instanceof String string
                    && MetadataBinaryCodec.containsKey(data(), string);
        }

        @Override
        public int size() {
            Map<String, String> current = decoded;
            if (current != null) {
                return current.size();
            }
            return compact == null
                    ? MetadataBinaryCodec.size(data())
                    : compactSize;
        }

        private Map<String, String> decoded() {
            Map<String, String> current = decoded;
            if (current == null) {
                synchronized (this) {
                    current = decoded;
                    if (current == null) {
                        String[] compactEntries = compact;
                        if (compactEntries == null) {
                            current = MetadataBinaryCodec.decode(data);
                        } else {
                            Map<String, String> materialized = new HashMap<>(
                                    Math.max(16, (int) (compactSize / 0.75f) + 1));
                            for (int index = 0; index < compactSize; index++) {
                                int keyIndex = index * 2;
                                materialized.put(
                                        compactEntries[keyIndex],
                                        compactEntries[keyIndex + 1]);
                            }
                            current = java.util.Collections.unmodifiableMap(materialized);
                        }
                        decoded = current;
                    }
                }
            }
            return current;
        }

        private boolean isOpaque() {
            return decoded == null;
        }

        private Map<String, String> traceEntries() {
            Map<String, String> current = decoded;
            if (current != null) {
                return Metadata.traceEntries(current);
            }
            String[] compactEntries = compact;
            if (compactEntries == null) {
                return MetadataBinaryCodec.traceEntries(data());
            }
            Map<String, String> result = new HashMap<>();
            for (int index = 0; index < compactSize; index++) {
                int keyIndex = index * 2;
                String key = compactEntries[keyIndex];
                if (key.startsWith("$trace.")) {
                    result.put(key, compactEntries[keyIndex + 1]);
                }
            }
            return result;
        }

        private SerializedEntries with(String key, String value) {
            Map<String, String> currentDecoded = decoded;
            if (currentDecoded != null) {
                if (currentDecoded.isEmpty()) {
                    return new SerializedEntries(
                            new String[]{key, value}, 1);
                }
                Map<String, String> entries =
                        new HashMap<>(currentDecoded);
                entries.put(key, value);
                return new SerializedEntries(entries);
            }
            String[] compactEntries = compact;
            if (compactEntries == null) {
                Data<byte[]> currentData = data;
                if (currentData != null) {
                    return new SerializedEntries(new Data<>(
                            MetadataBinaryCodec.merge(
                                    currentData, key, value),
                            DATA_TYPE, 0, DATA_FORMAT));
                }
                throw new IllegalStateException(
                        "Metadata entries have no representation");
            }
            int matchingIndex = -1;
            for (int index = 0; index < compactSize; index++) {
                if (compactEntries[index * 2].equals(key)) {
                    matchingIndex = index;
                    break;
                }
            }
            int nextSize = matchingIndex < 0
                    ? Math.addExact(compactSize, 1)
                    : compactSize;
            String[] result = new String[
                    Math.multiplyExact(nextSize, 2)];
            int target = 0;
            for (int index = 0; index < compactSize; index++) {
                if (index != matchingIndex) {
                    int source = index * 2;
                    result[target++] = compactEntries[source];
                    result[target++] = compactEntries[source + 1];
                }
            }
            result[target] = key;
            result[target + 1] = value;
            return new SerializedEntries(result, nextSize);
        }
    }

    private static final class MetadataBinaryCodec {
        private MetadataBinaryCodec() {
        }

        private static byte[] encode(Map<String, String> entries) {
            BinaryWriter writer = new BinaryWriter(minimumEncodedSize(entries));
            writer.writeInt(entries.size());
            entries.forEach((key, value) ->
                                    writer.writeEntry(
                                            Objects.requireNonNull(key, "Metadata key"),
                                            Objects.requireNonNull(value, "Metadata value")));
            return writer.toByteArray();
        }

        private static byte[] encode(String[] entries, int size) {
            BinaryWriter writer = new BinaryWriter(minimumEncodedSize(entries, size));
            writer.writeInt(size);
            for (int index = 0; index < size; index++) {
                int keyIndex = index * 2;
                writer.writeEntry(entries[keyIndex], entries[keyIndex + 1]);
            }
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
            int size = Integer.BYTES;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current <= 0x7f) {
                    size = addSize(size, 1);
                } else if (current <= 0x7ff) {
                    size = addSize(size, 2);
                } else if (Character.isHighSurrogate(current)
                        && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    size = addSize(size, 4);
                    index++;
                } else if (Character.isSurrogate(current)) {
                    size = addSize(size, 1); // Standard UTF-8 replacement byte ('?')
                } else {
                    size = addSize(size, 3);
                }
            }
            return size;
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
            BinaryReader reader = new BinaryReader(data);
            int result = reader.readInt();
            if (result < 0 || result > MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Invalid serialized metadata entry count " + result);
            }
            return result;
        }

        private static Map<String, String> decode(Data<byte[]> data) {
            BinaryReader reader = new BinaryReader(data);
            int size = reader.readSize();
            if (size == 0) {
                reader.requireComplete();
                return emptyMap();
            }
            Map<String, String> result = new HashMap<>(Math.max(16, (int) (size / 0.75f) + 1));
            for (int i = 0; i < size; i++) {
                result.put(reader.readString(), reader.readString());
            }
            reader.requireComplete();
            return java.util.Collections.unmodifiableMap(result);
        }

        private static boolean containsKey(Data<byte[]> data, String key) {
            BinaryReader reader = new BinaryReader(data);
            return containsKey(reader, key);
        }

        private static boolean containsKey(
                Data.ByteArrayView data, String key) {
            return findValue(data, key) >= 0;
        }

        private static boolean containsKey(
                byte[] data, int offset, int length, String key) {
            return findValue(data, offset, length, key) >= 0;
        }

        private static boolean containsKey(
                BinaryReader reader, String key) {
            int size = reader.readSize();
            if (size == 0) {
                reader.requireComplete();
                return false;
            }
            boolean found = false;
            for (int i = 0; i < size; i++) {
                found |= reader.readStringEquals(key);
                reader.skipString();
            }
            reader.requireComplete();
            return found;
        }

        private static String get(Data<byte[]> data, String key) {
            BinaryReader reader = new BinaryReader(data);
            return get(reader, key);
        }

        private static String get(
                Data.ByteArrayView data, String key) {
            long value = findValue(data, key);
            return value < 0 ? null : new String(
                    data.array(), (int) (value >>> Integer.SIZE), (int) value,
                    StandardCharsets.UTF_8);
        }

        private static String get(
                byte[] data, int offset, int length, String key) {
            long value = findValue(data, offset, length, key);
            return value < 0 ? null : new String(
                    data, (int) (value >>> Integer.SIZE), (int) value,
                    StandardCharsets.UTF_8);
        }

        /**
         * Scans a byte-array view without allocating a stateful reader. The returned long packs the offset and length
         * of the last value for the requested key, or {@code -1} when the key is absent.
         */
        private static long findValue(Data.ByteArrayView data, String key) {
            byte[] bytes = data == null ? null : data.array();
            int offset = data == null ? 0 : data.offset();
            int length = data == null ? 0 : data.length();
            return findValue(bytes, offset, length, key);
        }

        private static long findValue(byte[] bytes, int offset, int length, String key) {
            Objects.requireNonNull(key, "Metadata key");
            BinaryReader.validate(bytes, offset, length);
            int position = offset;
            int limit = offset + length;
            if (limit - position < Integer.BYTES) {
                throw new IllegalArgumentException("Truncated serialized metadata");
            }
            int size = readInt(bytes, position);
            position += Integer.BYTES;
            if (size < 0 || size > MAX_ENTRY_COUNT) {
                throw new IllegalArgumentException("Invalid serialized metadata entry count " + size);
            }

            long result = -1;
            for (int index = 0; index < size; index++) {
                if (limit - position < Integer.BYTES) {
                    throw new IllegalArgumentException("Truncated serialized metadata");
                }
                int keyLength = readInt(bytes, position);
                position += Integer.BYTES;
                if (keyLength < 0 || keyLength > MAX_DATA_BYTES || keyLength > limit - position) {
                    throw new IllegalArgumentException("Invalid serialized metadata string size " + keyLength);
                }
                boolean matches = utf8Equals(bytes, position, keyLength, key);
                position += keyLength;

                if (limit - position < Integer.BYTES) {
                    throw new IllegalArgumentException("Truncated serialized metadata");
                }
                int valueLength = readInt(bytes, position);
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

        private static boolean utf8Equals(byte[] bytes, int position, int byteLength, String value) {
            int byteIndex = 0;
            for (int charIndex = 0; charIndex < value.length(); charIndex++) {
                char current = value.charAt(charIndex);
                if (current <= 0x7f) {
                    if (!matchesByte(bytes, position, byteIndex++, byteLength, current)) {
                        return false;
                    }
                } else if (current <= 0x7ff) {
                    if (!matchesByte(bytes, position, byteIndex++, byteLength, 0xc0 | current >>> 6)
                            || !matchesByte(bytes, position, byteIndex++, byteLength, 0x80 | current & 0x3f)) {
                        return false;
                    }
                } else if (Character.isHighSurrogate(current)
                        && charIndex + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(charIndex + 1))) {
                    int codePoint = Character.toCodePoint(current, value.charAt(++charIndex));
                    if (!matchesByte(bytes, position, byteIndex++, byteLength, 0xf0 | codePoint >>> 18)
                            || !matchesByte(bytes, position, byteIndex++, byteLength, 0x80 | codePoint >>> 12 & 0x3f)
                            || !matchesByte(bytes, position, byteIndex++, byteLength, 0x80 | codePoint >>> 6 & 0x3f)
                            || !matchesByte(bytes, position, byteIndex++, byteLength, 0x80 | codePoint & 0x3f)) {
                        return false;
                    }
                } else if (Character.isSurrogate(current)) {
                    if (!matchesByte(bytes, position, byteIndex++, byteLength, '?')) {
                        return false;
                    }
                } else if (!matchesByte(bytes, position, byteIndex++, byteLength, 0xe0 | current >>> 12)
                        || !matchesByte(bytes, position, byteIndex++, byteLength, 0x80 | current >>> 6 & 0x3f)
                        || !matchesByte(bytes, position, byteIndex++, byteLength, 0x80 | current & 0x3f)) {
                    return false;
                }
            }
            return byteIndex == byteLength;
        }

        private static boolean matchesByte(
                byte[] bytes, int position, int byteIndex, int byteLength, int expected) {
            return byteIndex < byteLength
                    && (bytes[position + byteIndex] & 0xff) == expected;
        }

        private static String get(
                BinaryReader reader, String key) {
            int size = reader.readSize();
            if (size == 0) {
                reader.requireComplete();
                return null;
            }
            String result = null;
            for (int i = 0; i < size; i++) {
                boolean matches = reader.readStringEquals(key);
                if (matches) {
                    result = reader.readString();
                } else {
                    reader.skipString();
                }
            }
            reader.requireComplete();
            return result;
        }

        private static Map<String, String> traceEntries(Data<byte[]> data) {
            BinaryReader reader = new BinaryReader(data);
            int size = reader.readSize();
            Map<String, String> result = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String key = reader.readStringIfStartsWith("$trace.");
                if (key == null) {
                    reader.skipString();
                } else {
                    result.put(key, reader.readString());
                }
            }
            reader.requireComplete();
            return result;
        }

        private static void forEachTraceEntry(Data<byte[]> data, BiConsumer<String, String> consumer) {
            BinaryReader reader = new BinaryReader(data);
            int size = reader.readSize();
            for (int i = 0; i < size; i++) {
                String key = reader.readStringIfStartsWith("$trace.");
                if (key == null) {
                    reader.skipString();
                } else {
                    consumer.accept(key, reader.readString());
                }
            }
            reader.requireComplete();
        }

        private static byte[] merge(Data<byte[]> data, Map<String, String> changes) {
            String[] keys = new String[changes.size()];
            String[] values = new String[changes.size()];
            byte[][] encodedKeys = new byte[changes.size()][];
            int changeSize = 0;
            int changeIndex = 0;
            for (Map.Entry<String, String> entry : changes.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "Metadata key");
                String value = Objects.requireNonNull(entry.getValue(), "Metadata value");
                keys[changeIndex] = key;
                values[changeIndex] = value;
                encodedKeys[changeIndex] = encodedLookupKey(key);
                changeSize = addSize(changeSize, stringSize(key));
                changeSize = addSize(changeSize, stringSize(value));
                changeIndex++;
            }

            BinaryReader reader = new BinaryReader(data);
            int baseSize = reader.readSize();
            boolean[] retained = new boolean[baseSize];
            int retainedBytes = 0;
            int replaced = 0;
            for (int index = 0; index < baseSize; index++) {
                int start = reader.position;
                boolean keep = reader.readStringIndex(keys, encodedKeys) < 0;
                reader.skipString();
                retained[index] = keep;
                if (keep) {
                    retainedBytes = addSize(retainedBytes, reader.position - start);
                } else {
                    replaced++;
                }
            }
            reader.requireComplete();

            int encodedSize = addSize(Integer.BYTES, retainedBytes);
            encodedSize = addSize(encodedSize, changeSize);
            BinaryWriter writer = new BinaryWriter(encodedSize);
            writer.writeInt(Math.addExact(baseSize - replaced, changes.size()));
            reader = new BinaryReader(data);
            reader.readSize();
            for (int index = 0; index < baseSize; index++) {
                int start = reader.position;
                reader.skipString();
                reader.skipString();
                if (retained[index]) {
                    writer.write(reader.bytes, start, reader.position - start);
                }
            }
            for (int index = 0; index < keys.length; index++) {
                writer.writeString(keys[index]);
                writer.writeString(values[index]);
            }
            return writer.toByteArray();
        }

        private static byte[] merge(Data<byte[]> data, String key, String value) {
            Objects.requireNonNull(key, "Metadata key");
            Objects.requireNonNull(value, "Metadata value");
            BinaryReader reader = new BinaryReader(data);
            int baseSize = reader.readSize();
            int retainedBytes = 0;
            int replaced = 0;
            for (int index = 0; index < baseSize; index++) {
                int start = reader.position;
                boolean matches = reader.readStringEquals(key);
                reader.skipString();
                if (matches) {
                    replaced++;
                } else {
                    retainedBytes = addSize(retainedBytes, reader.position - start);
                }
            }
            reader.requireComplete();

            int encodedSize = addSize(Integer.BYTES, retainedBytes);
            encodedSize = addSize(encodedSize, stringSize(key));
            encodedSize = addSize(encodedSize, stringSize(value));
            BinaryWriter writer = new BinaryWriter(encodedSize);
            writer.writeInt(Math.addExact(baseSize - replaced, 1));
            reader = new BinaryReader(data);
            reader.readSize();
            for (int index = 0; index < baseSize; index++) {
                int start = reader.position;
                boolean matches = reader.readStringEquals(key);
                reader.skipString();
                if (!matches) {
                    writer.write(reader.bytes, start, reader.position - start);
                }
            }
            reader.requireComplete();
            writer.writeString(key);
            writer.writeString(value);
            return writer.toByteArray();
        }

        private static byte[] merge(Data<byte[]> base, Data<byte[]> changes) {
            BinaryReader changeReader = new BinaryReader(changes);
            int changeSize = changeReader.readSize();
            int changeEntriesOffset = changeReader.position;
            int[] changeKeyOffsets = new int[changeSize];
            int[] changeKeyLengths = new int[changeSize];
            for (int index = 0; index < changeSize; index++) {
                int keyLength = changeReader.readStringLength();
                changeKeyOffsets[index] = changeReader.position;
                changeKeyLengths[index] = keyLength;
                changeReader.position += keyLength;
                changeReader.skipString();
            }
            changeReader.requireComplete();

            BinaryReader baseReader = new BinaryReader(base);
            int baseSize = baseReader.readSize();
            int retainedBytes = 0;
            int replaced = 0;
            for (int index = 0; index < baseSize; index++) {
                int entryOffset = baseReader.position;
                int keyLength = baseReader.readStringLength();
                int keyOffset = baseReader.position;
                baseReader.position += keyLength;
                baseReader.skipString();
                if (containsRawKey(baseReader.bytes, keyOffset, keyLength,
                                   changeReader.bytes, changeKeyOffsets, changeKeyLengths)) {
                    replaced++;
                } else {
                    retainedBytes = addSize(retainedBytes, baseReader.position - entryOffset);
                }
            }
            baseReader.requireComplete();

            int changeBytes = changeReader.limit - changeEntriesOffset;
            int encodedSize = addSize(Integer.BYTES, retainedBytes);
            encodedSize = addSize(encodedSize, changeBytes);
            BinaryWriter writer = new BinaryWriter(encodedSize);
            writer.writeInt(Math.addExact(baseSize - replaced, changeSize));
            baseReader = new BinaryReader(base);
            baseReader.readSize();
            for (int index = 0; index < baseSize; index++) {
                int entryOffset = baseReader.position;
                int keyLength = baseReader.readStringLength();
                int keyOffset = baseReader.position;
                baseReader.position += keyLength;
                baseReader.skipString();
                if (!containsRawKey(baseReader.bytes, keyOffset, keyLength,
                                    changeReader.bytes, changeKeyOffsets, changeKeyLengths)) {
                    writer.write(baseReader.bytes, entryOffset, baseReader.position - entryOffset);
                }
            }
            baseReader.requireComplete();
            writer.write(changeReader.bytes, changeEntriesOffset, changeBytes);
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

        private static byte[] encodedLookupKey(String key) {
            for (int index = 0; index < key.length(); index++) {
                if (key.charAt(index) > 0x7f) {
                    return key.getBytes(StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        private static int readInt(byte[] bytes, int offset) {
            return (bytes[offset] & 0xff) << 24
                   | (bytes[offset + 1] & 0xff) << 16
                   | (bytes[offset + 2] & 0xff) << 8
                   | bytes[offset + 3] & 0xff;
        }

        private static final class BinaryWriter {
            private byte[] bytes;
            private int position;

            private BinaryWriter(int initialSize) {
                bytes = new byte[initialSize];
            }

            private void writeInt(int value) {
                ensure(Integer.BYTES);
                writeInt(position, value);
                position += Integer.BYTES;
            }

            private void writeEntry(String key, String value) {
                writeString(key);
                writeString(value);
            }

            private void writeString(String value) {
                int lengthOffset = position;
                int length = value.length();
                writeInt(length);
                ensure(length);
                for (int index = 0; index < value.length(); index++) {
                    char current = value.charAt(index);
                    if (current <= 0x7f) {
                        bytes[position++] = (byte) current;
                    } else {
                        position = lengthOffset;
                        writeUtf8(value);
                        return;
                    }
                }
            }

            private void writeUtf8(String value) {
                int byteLength = stringSize(value) - Integer.BYTES;
                writeInt(byteLength);
                ensure(byteLength);
                for (int index = 0; index < value.length(); index++) {
                    char current = value.charAt(index);
                    if (current <= 0x7f) {
                        bytes[position++] = (byte) current;
                    } else if (current <= 0x7ff) {
                        bytes[position++] = (byte) (0xc0 | current >>> 6);
                        bytes[position++] = (byte) (0x80 | current & 0x3f);
                    } else if (Character.isHighSurrogate(current)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        int codePoint = Character.toCodePoint(current, value.charAt(++index));
                        bytes[position++] = (byte) (0xf0 | codePoint >>> 18);
                        bytes[position++] = (byte) (0x80 | codePoint >>> 12 & 0x3f);
                        bytes[position++] = (byte) (0x80 | codePoint >>> 6 & 0x3f);
                        bytes[position++] = (byte) (0x80 | codePoint & 0x3f);
                    } else if (Character.isSurrogate(current)) {
                        bytes[position++] = '?';
                    } else {
                        bytes[position++] = (byte) (0xe0 | current >>> 12);
                        bytes[position++] = (byte) (0x80 | current >>> 6 & 0x3f);
                        bytes[position++] = (byte) (0x80 | current & 0x3f);
                    }
                }
            }

            private void writeInt(int offset, int value) {
                bytes[offset] = (byte) (value >>> 24);
                bytes[offset + 1] = (byte) (value >>> 16);
                bytes[offset + 2] = (byte) (value >>> 8);
                bytes[offset + 3] = (byte) value;
            }

            private void write(byte[] value, int offset, int length) {
                ensure(length);
                System.arraycopy(value, offset, bytes, position, length);
                position += length;
            }

            private void ensure(int additional) {
                int required = Math.addExact(position, additional);
                if (required > MAX_DATA_BYTES) {
                    throw new IllegalArgumentException("Serialized metadata exceeds maximum size");
                }
                if (required > bytes.length) {
                    int growth = Math.max(16, bytes.length >>> 1);
                    int newLength = Math.min(MAX_DATA_BYTES, Math.max(required, bytes.length + growth));
                    bytes = Arrays.copyOf(bytes, newLength);
                }
            }

            private byte[] toByteArray() {
                return position == bytes.length ? bytes : Arrays.copyOf(bytes, position);
            }
        }

        private static final class BinaryReader {
            private final byte[] bytes;
            private final int limit;
            private int position;

            private BinaryReader(Data<byte[]> data) {
                Data.ByteArrayView view = data.byteArrayView();
                byte[] bytes = view == null ? data.getValue() : view.array();
                int offset = view == null ? 0 : view.offset();
                int length = view == null ? (bytes == null ? 0 : bytes.length) : view.length();
                validate(bytes, offset, length);
                this.bytes = bytes;
                this.position = offset;
                this.limit = offset + length;
            }

            private BinaryReader(Data.ByteArrayView view) {
                byte[] bytes = view == null ? null : view.array();
                int offset = view == null ? 0 : view.offset();
                int length = view == null ? 0 : view.length();
                validate(bytes, offset, length);
                this.bytes = bytes;
                this.position = offset;
                this.limit = offset + length;
            }

            private static void validate(
                    byte[] bytes, int offset,
                    int length) {
                if (bytes == null || offset < 0 || length < 0
                        || offset > bytes.length - length || length > MAX_DATA_BYTES) {
                    throw new IllegalArgumentException("Invalid serialized metadata size");
                }
            }

            private int readSize() {
                int result = readInt();
                if (result < 0 || result > MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("Invalid serialized metadata entry count " + result);
                }
                return result;
            }

            private String readString() {
                int length = readInt();
                if (length < 0 || length > MAX_DATA_BYTES || length > limit - position) {
                    throw new IllegalArgumentException("Invalid serialized metadata string size " + length);
                }
                String result = new String(bytes, position, length, StandardCharsets.UTF_8);
                position += length;
                return result;
            }

            private boolean readStringEquals(String value) {
                int length = readStringLength();
                boolean result = utf8Equals(value, length);
                position += length;
                return result;
            }

            private boolean utf8Equals(String value, int byteLength) {
                return MetadataBinaryCodec.utf8Equals(bytes, position, byteLength, value);
            }

            private String readStringIfStartsWith(String prefix) {
                int length = readStringLength();
                boolean matches = length >= prefix.length();
                for (int index = 0; matches && index < prefix.length(); index++) {
                    matches = (bytes[position + index] & 0xff) == prefix.charAt(index);
                }
                String result = matches
                        ? new String(bytes, position, length, StandardCharsets.UTF_8) : null;
                position += length;
                return result;
            }

            private int readStringIndex(String[] values, byte[][] encodedValues) {
                int length = readStringLength();
                int result = -1;
                for (int valueIndex = 0; valueIndex < values.length; valueIndex++) {
                    String value = values[valueIndex];
                    byte[] encodedValue = encodedValues[valueIndex];
                    int expectedLength = encodedValue == null ? value.length() : encodedValue.length;
                    if (length != expectedLength) {
                        continue;
                    }
                    boolean matches = true;
                    for (int index = 0; matches && index < length; index++) {
                        int expected = encodedValue == null
                                ? value.charAt(index) : encodedValue[index] & 0xff;
                        matches = (bytes[position + index] & 0xff) == expected;
                    }
                    if (matches) {
                        result = valueIndex;
                        break;
                    }
                }
                position += length;
                return result;
            }

            private void skipString() {
                int length = readStringLength();
                position += length;
            }

            private int readStringLength() {
                int length = readInt();
                if (length < 0 || length > MAX_DATA_BYTES || length > limit - position) {
                    throw new IllegalArgumentException("Invalid serialized metadata string size " + length);
                }
                return length;
            }

            private int readInt() {
                if (limit - position < Integer.BYTES) {
                    throw new IllegalArgumentException("Truncated serialized metadata");
                }
                int result = MetadataBinaryCodec.readInt(bytes, position);
                position += Integer.BYTES;
                return result;
            }

            private void requireComplete() {
                if (position != limit) {
                    throw new IllegalArgumentException("Unexpected trailing serialized metadata bytes");
                }
            }
        }
    }
}
