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
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.keyvalue.KeyValueStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getTypeAnnotation;
import static io.fluxzero.common.reflection.ReflectionUtils.getValue;
import static io.fluxzero.common.reflection.ReflectionUtils.isLeafValue;
import static io.fluxzero.common.reflection.ReflectionUtils.writeProperty;
import static java.util.Optional.ofNullable;

/**
 * A {@link DispatchInterceptor} and {@link HandlerInterceptor} that supports secure transmission of sensitive data
 * fields by removing them from the payload before dispatch and restoring them during handling.
 *
 * <p>This interceptor works in two phases:
 * <ul>
 *   <li><strong>Dispatch phase:</strong>
 *     <ul>
 *       <li>Scans the payload for fields annotated with {@link ProtectData}.</li>
 *       <li>Each such field is serialized and stored securely in a designated store, indexed by a generated key.</li>
 *       <li>The payload is cloned and sensitive fields are removed (set to {@code null}).</li>
 *       <li>The generated key references are stored in the message metadata under {@link #METADATA_KEY}.</li>
 *     </ul>
 *   </li>
 *   <li><strong>Handling phase:</strong>
 *     <ul>
 *       <li>Looks for protected data references in the message metadata.</li>
 *       <li>If present, retrieves the original values from the store using the stored keys.</li>
 *       <li>Injects the retrieved values back into the message payload before invocation.</li>
 *       <li>If the target method is annotated with {@link DropProtectedData}, the stored entries are deleted after injection.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>This strategy is useful for preventing sensitive or private data from being persisted to the message log,
 * while still allowing handlers to receive full context during execution.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class MyHandler {
 *   @HandleCommand
 *   public void handle(@ProtectData String ssn, ...) {
 *     ...
 *   }
 *
 *   @HandleCommand
 *   @DropProtectedData
 *   public void auditSensitive(@ProtectData String secretField, ...) {
 *     ...
 *     // After invocation, the secret is permanently removed
 *   }
 * }
 * }
 * </pre>
 *
 * <p>Note: The payload is cloned via (de)serialization to ensure the original object remains unmodified.
 *
 * @see ProtectData
 * @see DropProtectedData
 * @see DispatchInterceptor
 * @see HandlerInterceptor
 */
@AllArgsConstructor
@Slf4j
public class DataProtectionInterceptor implements DispatchInterceptor, HandlerInterceptor {

    public static String METADATA_KEY = "$protectedData";

    private final KeyValueStore keyValueStore;
    private final Serializer serializer;

    @Override
    @SuppressWarnings("unchecked")
    public Message interceptDispatch(Message m, MessageType messageType, String topic) {
        Map<String, String> protectedFields = new LinkedHashMap<>();
        if (m.getMetadata().containsKey(METADATA_KEY)) {
            protectedFields.putAll(m.getMetadata().get(METADATA_KEY, Map.class));
        } else {
            protectedFields.putAll(getProtectedFields(m.getPayload()));
            if (!protectedFields.isEmpty()) {
                m = m.withMetadata(m.getMetadata().with(METADATA_KEY, protectedFields));
            }
        }
        if (!protectedFields.isEmpty()) {
            Object payloadCopy = sanitizePayload(m.getPayload(), protectedFields);
            m = m.withPayload(payloadCopy);
        }
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        return m -> {
            if (m.getMetadata().containsKey(METADATA_KEY)) {
                Object payload = m.getPayload();
                Map<String, String> protectedFields = m.getMetadata().get(METADATA_KEY, Map.class);
                boolean dropProtectedData = invoker.getMethod().isAnnotationPresent(DropProtectedData.class);
                if (payload != null && payload.getClass().isRecord()) {
                    JsonNode payloadTree = serializer.convert(payload, JsonNode.class);
                    protectedFields.forEach((fieldName, key) -> {
                        try {
                            writeProperty(fieldName, payloadTree, keyValueStore.get(key));
                        } catch (Exception e) {
                            log.warn("Failed to set field {}", fieldName, e);
                        }
                        if (dropProtectedData) {
                            keyValueStore.delete(key);
                        }
                    });
                    m = m.withPayload(serializer.convert(payloadTree, payload.getClass()));
                    return function.apply(m);
                }
                protectedFields.forEach((fieldName, key) -> {
                    try {
                        writeProperty(fieldName, payload, keyValueStore.get(key));
                    } catch (Exception e) {
                        log.warn("Failed to set field {}", fieldName, e);
                    }
                    if (dropProtectedData) {
                        keyValueStore.delete(key);
                    }
                });
            }
            return function.apply(m);
        };
    }

    private Object sanitizePayload(Object payload, Map<String, String> protectedFields) {
        if (payload != null && payload.getClass().isRecord()) {
            JsonNode payloadTree = serializer.convert(payload, JsonNode.class);
            protectedFields.forEach((name, key) -> writeProperty(name, payloadTree, null));
            return serializer.convert(payloadTree, payload.getClass());
        }
        Object payloadCopy = serializer.deserialize(serializer.serialize(payload));
        protectedFields.forEach((name, key) -> writeProperty(name, payloadCopy, null));
        return payloadCopy;
    }

    private Map<String, String> getProtectedFields(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, String> protectedFields = new LinkedHashMap<>();
        getAnnotatedProperties(value.getClass(), ProtectData.class).stream()
                .flatMap(property -> ofNullable(getValue(property, value)).stream()
                        .flatMap(propertyValue -> getProtectedFields(property, propertyValue)))
                .forEach(e -> protectedFields.put(e.getKey(), e.getValue()));
        return protectedFields;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    private Stream<Map.Entry<String, String>> getProtectedFields(AccessibleObject holder, Object propertyValue) {
        if (propertyValue == null) {
            return Stream.empty();
        }
        String name = getPropertyName(holder);
        if (isLeafValue(propertyValue)
            || propertyValue instanceof JsonNode
            || propertyValue instanceof Data<?>
            || propertyValue instanceof Iterable<?>
            || propertyValue instanceof Map<?, ?>
            || getTypeAnnotation(propertyValue.getClass(), ProtectData.class) != null) {
            return Stream.of(Map.entry(name, storeProtectedValue(propertyValue)));
        }
        return getProtectedFields(propertyValue).entrySet().stream()
                .map(e -> Map.entry("%s/%s".formatted(name, e.getKey()), e.getValue()));
    }

    private String storeProtectedValue(Object value) {
        String key = Fluxzero.currentIdentityProvider().nextTechnicalId();
        keyValueStore.store(key, value, Guarantee.STORED);
        return key;
    }
}
