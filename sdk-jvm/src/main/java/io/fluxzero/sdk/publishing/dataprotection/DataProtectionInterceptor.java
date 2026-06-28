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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Leaf;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerInvoker.DelegatingHandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.keyvalue.KeyValueStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.GeneratedPropertyAccesses;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.PropertyAccess;
import io.fluxzero.sdk.registry.PropertyDescriptor;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

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
    public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        return new Handler<>() {
            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                if (!message.getMetadata().containsKey(METADATA_KEY)) {
                    return handler.getInvokerOrNull(message);
                }
                HandlerInvoker invoker = handler.getInvokerOrNull(message);
                if (invoker == null) {
                    return null;
                }
                return new DelegatingHandlerInvoker(invoker) {
                    @Override
                    public Object invoke(BiFunction<Object, Object, Object> combiner) {
                        DeserializingMessage handledMessage = restoreProtectedData(message, invoker);
                        if (handledMessage != message) {
                            HandlerInvoker restoredInvoker = handler.getInvokerOrNull(handledMessage);
                            if (restoredInvoker == null) {
                                throw new UnsupportedOperationException(
                                        "Restoring protected data changed the payload type in an unsupported way.");
                            }
                            return handledMessage.apply(m -> restoredInvoker.invoke(combiner));
                        }
                        return invoker.invoke(combiner);
                    }
                };
            }

            @Override
            public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
                if (!message.getMetadata().containsKey(METADATA_KEY)) {
                    return handler.getHandlerMethodOrNull(message);
                }
                return null;
            }

            @Override
            public Class<?> getTargetClass() {
                return handler.getTargetClass();
            }

            @Override
            public String toString() {
                return handler.toString();
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        return m -> {
            DeserializingMessage handledMessage = restoreProtectedData(m, invoker);
            if (handledMessage != m) {
                return handledMessage.apply(function::apply);
            }
            return function.apply(m);
        };
    }

    @SuppressWarnings("unchecked")
    private DeserializingMessage restoreProtectedData(DeserializingMessage m, HandlerDescriptor invoker) {
        if (!m.getMetadata().containsKey(METADATA_KEY)) {
            return m;
        }
        Object payload = m.getPayload();
        Map<String, String> protectedFields = m.getMetadata().get(METADATA_KEY, Map.class);
        boolean dropProtectedData = shouldDropProtectedData(invoker);
        if (payload != null && (payload.getClass().isRecord() || ComponentMetadataLookups.generatedOnlyMode())) {
            JsonNode payloadTree = serializer.convert(payload, JsonNode.class);
            protectedFields.forEach((fieldName, key) -> restoreProtectedField(payloadTree, fieldName, key,
                                                                              dropProtectedData));
            return m.withPayloadPreservingSerializedObject(serializer.convert(payloadTree, payload.getClass()));
        }
        protectedFields.forEach((fieldName, key) -> restoreProtectedField(payload, fieldName, key, dropProtectedData));
        return m;
    }

    private boolean shouldDropProtectedData(HandlerDescriptor invoker) {
        Optional<Boolean> metadata = ComponentMetadataLookups.lookup(invoker.getTargetClass())
                .map(lookup -> hasAnnotation(
                        ComponentMetadataLookups.executableAnnotations(lookup, invoker.getExecutableView()),
                        DropProtectedData.class));
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.orElse(false);
        }
        return invoker.getExecutableView().annotation(DropProtectedData.class).isPresent();
    }

    private void restoreProtectedField(Object payload, String fieldName, String key, boolean dropProtectedData) {
        try {
            writeProperty(fieldName, payload, keyValueStore.get(key));
        } catch (Exception e) {
            log.warn("Failed to set field {}", fieldName, e);
        }
        if (dropProtectedData) {
            keyValueStore.delete(key);
        }
    }

    private Object sanitizePayload(Object payload, Map<String, String> protectedFields) {
        if (payload != null && (payload.getClass().isRecord() || ComponentMetadataLookups.generatedOnlyMode())) {
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
        Optional<Stream<Map.Entry<String, String>>> metadata = ComponentMetadataLookups.lookup(value.getClass())
                .map(lookup -> typeNames(value.getClass()).stream()
                        .flatMap(typeName -> lookup.properties(typeName).stream())
                        .filter(property -> hasAnnotation(property, ProtectData.class))
                        .flatMap(property -> readProperty(property.name(), value).stream()
                                .flatMap(propertyValue -> getProtectedFields(property.name(), propertyValue))));
        metadata.orElseGet(() -> ComponentMetadataLookups.generatedOnlyMode() ? Stream.empty()
                : properties().annotatedProperties(value.getClass(), ProtectData.class).stream()
                .flatMap(property -> Optional.ofNullable(properties().propertyValue(property, value, true)).stream()
                        .flatMap(propertyValue -> getProtectedFields(properties().propertyName(property), propertyValue))))
                .forEach(e -> protectedFields.put(e.getKey(), e.getValue()));
        return protectedFields;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    private Stream<Map.Entry<String, String>> getProtectedFields(String name, Object propertyValue) {
        if (propertyValue == null) {
            return Stream.empty();
        }
        if (isLeafValue(propertyValue)
            || propertyValue instanceof JsonNode
            || propertyValue instanceof Data<?>
            || propertyValue instanceof Iterable<?>
            || propertyValue instanceof Map<?, ?>
            || isProtectedType(propertyValue.getClass())) {
            return Stream.of(Map.entry(name, storeProtectedValue(propertyValue)));
        }
        return getProtectedFields(propertyValue).entrySet().stream()
                .map(e -> Map.entry("%s/%s".formatted(name, e.getKey()), e.getValue()));
    }

    private boolean isProtectedType(Class<?> type) {
        Optional<Boolean> metadata = ComponentMetadataLookups.lookup(type)
                .map(lookup -> typeNames(type).stream()
                        .anyMatch(typeName -> hasAnnotation(lookup.typeAnnotations(typeName), ProtectData.class)));
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.orElse(false);
        }
        return JvmComponentIntrospector.getInstance().getTypeAnnotation(type, ProtectData.class) != null;
    }

    private String storeProtectedValue(Object value) {
        String key = Fluxzero.currentIdentityProvider().nextTechnicalId();
        keyValueStore.store(key, value, Guarantee.STORED);
        return key;
    }

    private static PropertyAccess<Class<?>, AccessibleObject> properties() {
        return JvmComponentIntrospector.getInstance();
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> readProperty(String propertyPath, Object target) {
        Optional<T> generatedValue = readGeneratedProperty(propertyPath, target);
        if (generatedValue.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return generatedValue;
        }
        return properties().readProperty(propertyPath, target);
    }

    private boolean writeProperty(String propertyPath, Object target, Object value) {
        if (writeGeneratedProperty(propertyPath, target, value) || writeJsonProperty(propertyPath, target, value)) {
            return true;
        }
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            properties().writeProperty(propertyPath, target, value);
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> readGeneratedProperty(String propertyPath, Object target) {
        if (target == null || propertyPath == null || propertyPath.isBlank()) {
            return Optional.empty();
        }
        int separator = propertyPath.indexOf('.');
        String propertyName = separator < 0 ? propertyPath : propertyPath.substring(0, separator);
        Optional<Object> value = GeneratedPropertyAccesses.findReader(target.getClass(), propertyName)
                .map(reader -> reader.read(target));
        if (value.isEmpty() || separator < 0) {
            return (Optional<T>) value;
        }
        return readGeneratedProperty(propertyPath.substring(separator + 1), value.get());
    }

    private static boolean writeGeneratedProperty(String propertyPath, Object target, Object value) {
        if (target == null || propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        int separator = propertyPath.indexOf('.');
        String propertyName = separator < 0 ? propertyPath : propertyPath.substring(0, separator);
        if (separator < 0) {
            return GeneratedPropertyAccesses.findWriter(target.getClass(), propertyName)
                    .map(writer -> {
                        writer.write(target, value);
                        return true;
                    })
                    .orElse(false);
        }
        return readGeneratedProperty(propertyName, target)
                .map(nestedTarget -> writeGeneratedProperty(propertyPath.substring(separator + 1), nestedTarget, value))
                .orElse(false);
    }

    private boolean writeJsonProperty(String propertyPath, Object target, Object value) {
        if (!(target instanceof ObjectNode objectNode) || propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        String normalized = propertyPath.replace('.', '/');
        int separator = normalized.indexOf('/');
        if (separator < 0) {
            if (value == null) {
                objectNode.remove(normalized);
            } else {
                objectNode.set(normalized, serializer.convert(value, JsonNode.class));
            }
            return true;
        }
        JsonNode child = objectNode.get(normalized.substring(0, separator));
        return writeJsonProperty(normalized.substring(separator + 1), child, value);
    }

    private static boolean isLeafValue(Object value) {
        if (value == null) {
            return true;
        }
        Class<?> type = value.getClass();
        for (Class<?> leafValueType : leafValueTypes) {
            if (leafValueType.isAssignableFrom(type)) {
                return true;
            }
        }
        return type.isEnum() || Leaf.class.isAssignableFrom(type);
    }

    private static final Set<Class<?>> leafValueTypes = Set.of(
            String.class, Number.class, Boolean.class, Character.class,
            java.util.UUID.class, java.net.URI.class,
            java.net.URL.class, java.util.Locale.class,
            java.util.Currency.class, java.time.temporal.Temporal.class,
            java.time.temporal.TemporalAmount.class
    );

    private static boolean hasAnnotation(PropertyDescriptor property, Class<?> annotationType) {
        return hasAnnotation(property.annotations(), annotationType);
    }

    private static boolean hasAnnotation(Iterable<AnnotationDescriptor> annotations, Class<?> annotationType) {
        for (AnnotationDescriptor annotation : annotations) {
            if (annotation.qualifiedName().equals(annotationType.getName())
                || annotation.name().equals(annotationType.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> typeNames(Class<?> type) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(type.getName());
        String canonicalName = type.getCanonicalName();
        if (canonicalName != null) {
            result.add(canonicalName);
        }
        return List.copyOf(result);
    }
}
