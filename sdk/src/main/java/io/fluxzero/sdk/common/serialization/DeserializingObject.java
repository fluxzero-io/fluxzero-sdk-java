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

import io.fluxzero.common.MemoizingFunction;
import io.fluxzero.common.api.SerializedObject;
import io.fluxzero.common.reflection.ReflectionUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;

import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A wrapper around a {@link SerializedObject} that supports lazy deserialization of its payload.
 * <p>
 * This class defers the deserialization of the underlying object until it is first accessed via {@link #getPayload()}
 * or {@link #getPayloadAs(Type)}. The result is then memoized to avoid repeated deserialization.
 *
 * <p>
 * This is typically used for messages that may not require full deserialization (e.g. for routing or filtering). It is
 * extended by {@link DeserializingMessage} which adds additional context and metadata.
 *
 * @param <T> The raw data type of the serialized object (e.g., String or byte[])
 * @param <S> The specific {@link SerializedObject} type used to represent the serialized payload
 */
@ToString(exclude = {"payload", "objectFunction"})
public class DeserializingObject<T, S extends SerializedObject<T>> {
    /**
     * Returns the underlying {@link SerializedObject}.
     */
    @Getter
    private final S serializedObject;
    private final Function<Type, Object> payload;
    private volatile MemoizingFunction<Type, Object> objectFunction;

    /**
     * Returns the memoized deserialization function used to deserialize the payload. This method is protected for use
     * in subclasses.
     */
    protected MemoizingFunction<Type, Object> getObjectFunction() {
        MemoizingFunction<Type, Object> result = objectFunction;
        if (result == null) {
            synchronized (this) {
                result = objectFunction;
                if (result == null) {
                    result = new PayloadMemoizingFunction(payload);
                    objectFunction = result;
                }
            }
        }
        return result;
    }

    /**
     * Creates a new {@code DeserializingObject} with the given serialized representation and deserializer function.
     *
     * @param serializedObject The raw serialized object
     * @param payload          A function to deserialize the object when needed
     */
    public DeserializingObject(S serializedObject, Function<Type, Object> payload) {
        this.serializedObject = serializedObject;
        this.payload = payload;
        if (payload instanceof MemoizingFunction<?, ?> memoizingFunction) {
            @SuppressWarnings("unchecked")
            MemoizingFunction<Type, Object> typed = (MemoizingFunction<Type, Object>) memoizingFunction;
            this.objectFunction = typed;
        }
    }

    /**
     * Returns the deserialized payload using the default target type ({@code Object.class}). The result is cached and
     * reused on subsequent calls.
     */
    @SuppressWarnings("unchecked")
    public <V> V getPayload() {
        return (V) getObjectFunction().apply(Object.class);
    }

    /**
     * Returns the deserialized payload using the specified target {@link Type}. Results are cached per type.
     *
     * @param type the desired target type
     * @param <V>  the expected type of the deserialized object
     */
    @SuppressWarnings("unchecked")
    public <V> V getPayloadAs(Type type) {
        return (V) getObjectFunction().apply(type);
    }

    /**
     * Returns {@code true} if the payload has already been deserialized using the default type.
     */
    public boolean isDeserialized() {
        MemoizingFunction<Type, Object> result = objectFunction;
        return result != null && result.isCached(Object.class);
    }

    /**
     * Returns the declared type of the payload (e.g., fully qualified class name), or {@code null} if unknown.
     */
    public String getType() {
        return serializedObject.data().getType();
    }

    /**
     * Returns the revision number of the serialized payload, if available.
     */
    public int getRevision() {
        return serializedObject.data().getRevision();
    }

    /**
     * Attempts to resolve the declared payload class using {@link ReflectionUtils#classForName(String)} and throws an
     * exception if the class cannot be found.
     *
     * @return the {@link Class} corresponding to the declared type, or {@code null} if not resolvable
     */
    @SneakyThrows
    @SuppressWarnings("unused")
    public Class<?> getPayloadClass() {
        String type = getType();
        return type == null ? null : ReflectionUtils.classForName(type);
    }

    private static class PayloadMemoizingFunction implements MemoizingFunction<Type, Object> {
        private static final Object UNCACHED = new Object();
        private static final Object NULL_VALUE = new Object();
        private static final Object NULL_KEY = new Object();

        private final Function<Type, Object> delegate;
        private volatile Object objectPayload = UNCACHED;
        private volatile ConcurrentHashMap<Object, Object> typedPayloads;

        private PayloadMemoizingFunction(Function<Type, Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object apply(Type type) {
            if (Object.class.equals(type)) {
                Object result = objectPayload;
                if (result == UNCACHED) {
                    synchronized (this) {
                        result = objectPayload;
                        if (result == UNCACHED) {
                            result = wrap(delegate.apply(type));
                            objectPayload = result;
                        }
                    }
                }
                return unwrap(result);
            }
            Object normalizedType = normalizeKey(type);
            return unwrap(typedPayloads().computeIfAbsent(normalizedType, ignored -> wrap(delegate.apply(type))));
        }

        @Override
        public void clear() {
            objectPayload = UNCACHED;
            ConcurrentHashMap<Object, Object> currentTypedPayloads = typedPayloads;
            if (currentTypedPayloads != null) {
                currentTypedPayloads.clear();
            }
        }

        @Override
        public Object remove(Type type) {
            if (Object.class.equals(type)) {
                synchronized (this) {
                    Object result = objectPayload;
                    objectPayload = UNCACHED;
                    return result == UNCACHED ? null : unwrap(result);
                }
            }
            ConcurrentHashMap<Object, Object> currentTypedPayloads = typedPayloads;
            return currentTypedPayloads == null ? null : unwrap(currentTypedPayloads.remove(normalizeKey(type)));
        }

        @Override
        public boolean isCached(Type type) {
            if (Object.class.equals(type)) {
                return objectPayload != UNCACHED;
            }
            ConcurrentHashMap<Object, Object> currentTypedPayloads = typedPayloads;
            return currentTypedPayloads != null && currentTypedPayloads.containsKey(normalizeKey(type));
        }

        @Override
        public void forEach(Consumer<? super Object> consumer) {
            Object cachedObjectPayload = objectPayload;
            if (cachedObjectPayload != UNCACHED) {
                consumer.accept(unwrap(cachedObjectPayload));
            }
            ConcurrentHashMap<Object, Object> currentTypedPayloads = typedPayloads;
            if (currentTypedPayloads != null) {
                currentTypedPayloads.values().forEach(value -> consumer.accept(unwrap(value)));
            }
        }

        private ConcurrentHashMap<Object, Object> typedPayloads() {
            ConcurrentHashMap<Object, Object> result = typedPayloads;
            if (result == null) {
                synchronized (this) {
                    result = typedPayloads;
                    if (result == null) {
                        result = new ConcurrentHashMap<>();
                        typedPayloads = result;
                    }
                }
            }
            return result;
        }

        private Object normalizeKey(Type type) {
            return type == null ? NULL_KEY : type;
        }

        private Object wrap(Object value) {
            return value == null ? NULL_VALUE : value;
        }

        private Object unwrap(Object value) {
            return value == NULL_VALUE ? null : value;
        }
    }
}
