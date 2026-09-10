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
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerInvoker.DelegatingHandlerInvoker;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.common.handling.HandlerMethodApplicability;
import io.fluxzero.common.handling.HandlerMethodPlan;
import io.fluxzero.common.handling.HandlerMethodPreparation;
import io.fluxzero.common.handling.HandlerMethodPlanner;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.persisting.keyvalue.KeyValueStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.LocalDispatchDescriptor;
import io.fluxzero.sdk.publishing.PreparedLocalDispatch;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.metrics.IgnoreMessageEvent;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyType;
import static io.fluxzero.common.reflection.ReflectionUtils.getTypeAnnotation;
import static io.fluxzero.common.reflection.ReflectionUtils.getValue;
import static io.fluxzero.common.reflection.ReflectionUtils.isLeafValue;
import static io.fluxzero.common.reflection.ReflectionUtils.readProperty;
import static io.fluxzero.common.reflection.ReflectionUtils.writeProperty;
import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toCollection;

/**
 * A {@link DispatchInterceptor} and {@link HandlerInterceptor} that supports secure transmission of sensitive data
 * fields by removing them from the payload before dispatch and restoring them during handling.
 *
 * <p>This interceptor works in two phases:
 * <ul>
 *   <li><strong>Dispatch phase:</strong>
 *     <ul>
 *       <li>Scans the payload for fields annotated with {@link ProtectData}.</li>
 *       <li>Each such field receives a generated key. Values are stored securely when the message is externally
 *       published; values used only by a local handler remain in memory.</li>
 *       <li>The payload is cloned and sensitive fields are removed (set to {@code null}).</li>
 *       <li>The generated key references and their dispatch namespace are stored in the message metadata under
 *       {@link #METADATA_KEY} and {@link #NAMESPACE_METADATA_KEY}.</li>
 *     </ul>
 *   </li>
 *   <li><strong>Handling phase:</strong>
 *     <ul>
 *       <li>Looks for protected data references in the message metadata.</li>
 *       <li>If present, retrieves the original values from memory or the store using the generated keys.</li>
 *       <li>Injects the retrieved values back into the message payload before invocation.</li>
 *       <li>If the target method is annotated with {@link DropProtectedData}, the retained values are removed after
 *       injection.</li>
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
@Slf4j
public class DataProtectionInterceptor implements DispatchInterceptor, HandlerInterceptor {

    public static String METADATA_KEY = "$protectedData";
    public static final String NAMESPACE_METADATA_KEY = "$protectedDataNamespace";

    private final KeyValueStore keyValueStore;
    private final Serializer serializer;
    private final MissingProtectedDataPolicy onMissingProtectedData;
    private final boolean trackingMetricsEnabled;
    private final ConcurrentMap<String, PendingProtectedData> pendingValues = new ConcurrentHashMap<>();

    /**
     * Creates an interceptor that silently invokes handlers when protected values are unavailable.
     *
     * @param keyValueStore store containing protected values
     * @param serializer serializer used to sanitize and restore payloads
     */
    public DataProtectionInterceptor(KeyValueStore keyValueStore, Serializer serializer) {
        this(keyValueStore, serializer, MissingProtectedDataPolicy.HANDLE, true);
    }

    /**
     * Creates an interceptor with an application-wide missing protected data policy.
     *
     * @param keyValueStore store containing protected values
     * @param serializer serializer used to sanitize and restore payloads
     * @param onMissingProtectedData application-wide fallback policy
     */
    public DataProtectionInterceptor(KeyValueStore keyValueStore, Serializer serializer,
                                     MissingProtectedDataPolicy onMissingProtectedData) {
        this(keyValueStore, serializer, onMissingProtectedData, true);
    }

    /**
     * Creates an interceptor with an application-wide missing protected data policy.
     *
     * @param keyValueStore store containing protected values
     * @param serializer serializer used to sanitize and restore payloads
     * @param onMissingProtectedData application-wide fallback policy
     * @param trackingMetricsEnabled whether skipped handlers should publish ignore-message metrics
     */
    public DataProtectionInterceptor(KeyValueStore keyValueStore, Serializer serializer,
                                     MissingProtectedDataPolicy onMissingProtectedData,
                                     boolean trackingMetricsEnabled) {
        this.keyValueStore = keyValueStore;
        this.serializer = serializer;
        this.onMissingProtectedData = onMissingProtectedData == MissingProtectedDataPolicy.DEFAULT
                ? MissingProtectedDataPolicy.HANDLE : onMissingProtectedData;
        this.trackingMetricsEnabled = trackingMetricsEnabled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Message interceptDispatch(Message m, MessageType messageType, String topic) {
        return interceptDispatch(m, messageType, topic, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Message interceptDispatch(Message m, MessageType messageType, String topic, String namespace) {
        return protectData(m, namespace, false);
    }

    @Override
    public Message interceptLocalDispatch(Message message, MessageType messageType, String topic, String namespace) {
        return protectData(message, namespace, true);
    }

    @Override
    public void beforeExternalDispatch(Message message, MessageType messageType, String topic) {
        Set<String> references = getProtectedDataReferences(message);
        PendingProtectedData pending = references.stream().map(pendingValues::get)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (pending != null) {
            pending.externalize(keyValueStore, references);
        }
    }

    @Override
    public void preserveLocalDispatchState(Message previousMessage, Message replacement) {
        Set<String> retained = replacement == null ? Set.of() : getProtectedDataReferences(replacement);
        getProtectedDataReferences(previousMessage).stream().filter(key -> !retained.contains(key))
                .forEach(pendingValues::remove);
    }

    @Override
    public boolean storesLocalDispatchState() {
        return true;
    }

    @Override
    public void completeLocalDispatch(Message message) {
        getProtectedDataReferences(message).forEach(pendingValues::remove);
    }

    @Override
    public SerializedMessage modifySerializedMessage(SerializedMessage serializedMessage, Message message,
                                                     MessageType messageType, String topic) {
        try {
            beforeExternalDispatch(message, messageType, topic);
            return serializedMessage;
        } finally {
            completeLocalDispatch(message);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public SerializedMessage modifySerializedMessage(SerializedMessage serialized, DeserializingMessage source,
                                                     MessageType messageType, String topic, String namespace) {
        // Invoke the existing extension point once, including overrides supplied by applications.
        serialized = modifySerializedMessage(serialized, source.toMessage(), messageType, topic);
        if (serialized == null) {
            return null;
        }
        if (getAnnotatedProperties(source.getPayloadClass(), ProtectData.class).isEmpty()
            && serialized.getData() == source.getSerializedObject(serializer).getData()) {
            Metadata metadata = serialized.getMetadata();
            return metadata.containsKey(METADATA_KEY) || metadata.containsKey(NAMESPACE_METADATA_KEY)
                    ? serialized.withMetadata(withoutProtectedDataMetadata(metadata)) : serialized;
        }
        ProtectionContext context = source.computeContextIfAbsent(ProtectionContext.class, ignored -> new ProtectionContext());
        synchronized (context) {
            List<PreparedProtection> previous = context.prepared.computeIfAbsent(source, ignored -> new ArrayList<>());
            for (PreparedProtection prepared : previous) {
                if (prepared.matches(source, serialized, namespace)) {
                    return prepared.apply(serialized);
                }
            }
            // Decode the current candidate, not the logical source: preceding serialized interceptors own its edits.
            Object payload = serializer.deserialize(serialized.getData());
            if (payload == null || getAnnotatedProperties(payload.getClass(), ProtectData.class).isEmpty()) {
                return serialized.withMetadata(withoutProtectedDataMetadata(serialized.getMetadata()));
            }
            Metadata metadata = serialized.getMetadata();
            Map<String, String> existing = metadata.containsKey(METADATA_KEY)
                    ? metadata.get(METADATA_KEY, Map.class) : Map.of();
            String storedNamespace = metadata.get(NAMESPACE_METADATA_KEY);
            String targetNamespace = storedNamespace == null
                    ? namespace == null ? getConsumerNamespace(source) : namespace
                    : storedNamespace.isEmpty() ? null : storedNamespace;
            Map<String, String> references = new LinkedHashMap<>(existing);
            references.keySet().removeIf(path -> !isProtectedPath(payload, payload.getClass(), path));
            Map<String, Object> values = new LinkedHashMap<>();
            collectProtectedValues(payload, "", values);
            for (var entry : new LinkedHashMap<>(references).entrySet()) {
                String field = entry.getKey();
                RestoredValue restored = context.restored.get(entry.getValue());
                Object candidateValue = readProperty(field, payload).orElse(null);
                if (candidateValue == null && restored != null) {
                    Object logicalValue = readProperty(field, source.getPayload()).orElse(null);
                    if (!restored.matches(targetNamespace, snapshot(logicalValue))) {
                        references.remove(field);
                        if (logicalValue != null) {
                            values.put(field, logicalValue);
                        }
                    }
                }
            }
            PendingProtectedData additions = new PendingProtectedData(targetNamespace);
            for (var entry : values.entrySet()) {
                String field = entry.getKey();
                Object value = entry.getValue();
                String reference = references.get(field);
                RestoredValue restored = reference == null ? null : context.restored.get(reference);
                if (restored == null || !restored.matches(targetNamespace, snapshot(value))) {
                    references.put(field, protectValue(value, targetNamespace, additions));
                }
            }
            // Work on the current wire tree so unknown extension fields, type aliases and revisions survive redaction.
            Data<byte[]> input = serialized.getData();
            JsonNode sanitized = serializer.deserialize(
                    input.withType(JsonNode.class.getName()).withRevision(0), JsonNode.class);
            // A regular bean getter can also derive output from a protected backing field. Apply the serialization
            // delta of logical sanitization, while retaining fields added by earlier serialized interceptors.
            Map<String, String> redactionFields = new LinkedHashMap<>(references);
            existing.forEach((path, reference) -> {
                if (isProtectedPath(payload, payload.getClass(), path)) {
                    redactionFields.putIfAbsent(path, reference);
                }
            });
            if (payload.getClass() == source.getPayloadClass()) {
                Map<String, Object> sourceFields = new LinkedHashMap<>();
                collectProtectedValues(source.getPayload(), "", sourceFields);
                sourceFields.keySet().forEach(path -> redactionFields.putIfAbsent(path, ""));
            }
            Object redactionBasis = redactionBasis(payload, sanitized, source, redactionFields, context);
            JsonNode before = serializedTree(redactionBasis, input.getFormat());
            JsonNode after = serializedTree(sanitizePayload(redactionBasis, redactionFields), input.getFormat());
            sanitized = redactDerivedProperties(sanitized, before, after);
            for (String path : references.keySet()) {
                writeProtectedProperty(payload, sanitized, path, null);
            }
            byte[] safeBytes = serializer.serialize(sanitized, input.getFormat()).getValue();
            Data<byte[]> safeData = input.map(ignored -> safeBytes);
            Metadata safeMetadata = withoutProtectedDataMetadata(metadata);
            if (!references.isEmpty()) {
                safeMetadata = safeMetadata.with(METADATA_KEY, references)
                        .with(NAMESPACE_METADATA_KEY, targetNamespace == null ? "" : targetNamespace);
            }
            // No durable references are published until every new value is stored successfully.
            additions.externalize(keyValueStore, Set.copyOf(references.values()));
            PreparedProtection prepared = new PreparedProtection(source, input.map(bytes -> bytes.clone()), metadata, namespace,
                                                                  safeData, safeMetadata);
            previous.add(prepared);
            return prepared.apply(serialized);
        }
    }

    /**
     * Carries private restoration provenance to an explicitly emitted message in the Model pipeline. It is never
     * serialized, and an emitted message's own restoration context takes precedence.
     */
    public static void preserveRestoredDataContext(DeserializingMessage source, DeserializingMessage emitted) {
        source.getContext(ProtectionContext.class).ifPresent(context ->
                emitted.computeContextIfAbsent(ProtectionContext.class, ignored -> context));
    }

    /**
     * Restores retained private values before reconstructing a Model from its stored events. Reconstruction does not
     * invoke command handlers, apply missing-handler policies, drop values, or write new references. Missing values
     * remain {@code null}, as for replay after erasure. The stored envelope and event index stay unchanged.
     */
    @SuppressWarnings("unchecked")
    public DeserializingMessage restoreForReplay(DeserializingMessage message) {
        if (!message.containsMetadata(METADATA_KEY) || message.getPayload() == null) {
            return message;
        }
        Object payload = message.getPayload();
        Object restored = payload.getClass().isRecord() ? serializer.convert(payload, JsonNode.class)
                : serializer.deserialize(serializer.serialize(payload));
        KeyValueStore store = keyValueStore.forNamespace(getProtectedDataNamespace(message));
        Map<String, String> fields = message.getMetadata().get(METADATA_KEY, Map.class);
        // A missing key is erased data; a failed read must not become a cacheable, incomplete Model revision.
        fields.forEach((field, reference) -> writeProtectedProperty(payload, restored, field, store.get(reference)));
        Object logical = payload.getClass().isRecord() ? serializer.convert(restored, payload.getClass()) : restored;
        return message.withRestoredPayload(logical);
    }

    private Data<byte[]> snapshot(Object value) {
        if (value == null) {
            return null;
        }
        Data<byte[]> serialized = serializer.serialize(value);
        return serialized.map(bytes -> bytes.clone());
    }

    private Object redactionBasis(Object payload, JsonNode raw, DeserializingMessage source, Map<String, String> fields,
                                  ProtectionContext context) {
        Object copy = null;
        for (var field : fields.entrySet()) {
            if (readProperty(field.getKey(), payload).orElse(null) != null
                && serializer.serializedPropertyPaths(payload, field.getKey()).stream()
                        .noneMatch(path -> raw.at("/" + path).isNull())) {
                continue;
            }
            Object previous = payload.getClass() == source.getPayloadClass()
                    ? readProperty(field.getKey(), source.getPayload()).orElse(null) : null;
            RestoredValue restored = context.restored.get(field.getValue());
            if (previous == null && restored != null && restored.value() != null) {
                previous = serializer.deserialize(restored.value());
            }
            if (previous != null) {
                if (copy == null) {
                    copy = payload.getClass().isRecord() ? serializer.convert(payload, JsonNode.class)
                            : serializer.deserialize(serializer.serialize(payload));
                }
                writeProtectedProperty(payload, copy, field.getKey(), previous);
            }
        }
        // This is a private comparison view, never a replay input or a value to retain in the vault.
        return copy == null ? payload : payload.getClass().isRecord()
                ? serializer.convert(copy, payload.getClass()) : copy;
    }

    private static JsonNode redactDerivedProperties(JsonNode raw, JsonNode before, JsonNode after) {
        if (raw.isNull() || Objects.equals(before, after)) {
            return raw;
        }
        if (raw instanceof ObjectNode object && before.isObject() && after.isObject()) {
            before.properties().forEach(entry -> {
                String name = entry.getKey();
                if (object.has(name)) {
                    JsonNode replacement = after.get(name);
                    if (replacement == null) {
                        if (!object.get(name).isNull()) {
                            requireMatchingProtectedRepresentation(object.get(name), entry.getValue());
                        }
                        object.remove(name);
                    } else {
                        object.set(name, redactDerivedProperties(object.get(name), entry.getValue(), replacement));
                    }
                }
            });
            return object;
        }
        if (!raw.equals(after)) {
            requireMatchingProtectedRepresentation(raw, before);
        }
        return after.deepCopy();
    }

    private static void requireMatchingProtectedRepresentation(JsonNode actual, JsonNode expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException("Protected payload serialization is not stable enough for safe redaction");
        }
    }

    private JsonNode serializedTree(Object payload, String format) {
        return serializer.deserialize(serializer.serialize(payload, format)
                                              .withType(JsonNode.class.getName()).withRevision(0), JsonNode.class);
    }

    private void writeProtectedProperty(Object logicalPayload, Object target, String path, Object value) {
        if (target instanceof JsonNode tree) {
            List<String> paths = serializer.serializedPropertyPaths(logicalPayload, path);
            JsonNode replacement = value == null ? null : serializer.convert(value, JsonNode.class);
            boolean present = paths.stream().anyMatch(candidate -> !tree.at("/" + candidate).isMissingNode());
            for (int i = 0; i < paths.size(); i++) {
                String serializedPath = paths.get(i);
                if ((present || i > 0) && tree.at("/" + serializedPath).isMissingNode()) {
                    continue;
                }
                String[] parts = serializedPath.split("/", -1);
                ObjectNode parent = (ObjectNode) tree;
                for (int part = 0; part < parts.length - 1; part++) {
                    String name = parts[part].replace("~1", "/").replace("~0", "~");
                    JsonNode nested = parent.get(name);
                    parent = nested == null || nested.isNull() ? parent.putObject(name) : (ObjectNode) nested;
                }
                String name = parts[parts.length - 1].replace("~1", "/").replace("~0", "~");
                parent.set(name, replacement);
            }
        } else {
            writeProperty(path, target, value);
        }
    }

    private static void collectProtectedValues(Object payload, String prefix, Map<String, Object> values) {
        if (payload == null) {
            return;
        }
        for (AccessibleObject property : getAnnotatedProperties(payload.getClass(), ProtectData.class)) {
            Object value = getValue(property, payload);
            if (value == null) {
                continue;
            }
            String path = prefix + getPropertyName(property);
            if (isLeafValue(value) || value instanceof JsonNode || value instanceof Data<?>
                || value instanceof Iterable<?> || value instanceof Map<?, ?>
                || getTypeAnnotation(value.getClass(), ProtectData.class) != null) {
                values.put(path, value);
            } else {
                collectProtectedValues(value, path + "/", values);
            }
        }
    }

    private static boolean isProtectedPath(Object value, Class<?> type, String path) {
        int separator = path.indexOf('/');
        String propertyName = separator < 0 ? path : path.substring(0, separator);
        for (AccessibleObject property : getAnnotatedProperties(type, ProtectData.class)) {
            if (getPropertyName(property).equals(propertyName)) {
                if (separator < 0) {
                    return true;
                }
                Object nested = value == null ? null : getValue(property, value);
                return isProtectedPath(nested, nested == null ? getPropertyType(property) : nested.getClass(),
                                       path.substring(separator + 1));
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Message protectData(Message m, String namespace, boolean deferStorage) {
        Object payload = m.getPayload();
        if (payload == null || getAnnotatedProperties(payload.getClass(), ProtectData.class).isEmpty()) {
            if (!m.getMetadata().containsKey(METADATA_KEY)
                && !m.getMetadata().containsKey(NAMESPACE_METADATA_KEY)) {
                return m;
            }
            Metadata metadata = withoutProtectedDataMetadata(m.getMetadata());
            return m.withMetadata(metadata);
        }
        Map<String, String> existingFields = m.getMetadata().containsKey(METADATA_KEY)
                ? m.getMetadata().get(METADATA_KEY, Map.class) : Map.of();
        String existingNamespace = m.getMetadata().get(NAMESPACE_METADATA_KEY);
        String targetNamespace = namespace == null ? "" : namespace;
        PendingProtectedData pending = deferStorage ? new PendingProtectedData(namespace) : null;
        Map<String, String> protectedFields = getProtectedFields(payload, namespace, pending);
        if (protectedFields.isEmpty() && !existingFields.isEmpty()
            && Objects.equals(existingNamespace, targetNamespace)) {
            protectedFields = existingFields;
        }
        Metadata metadata = withoutProtectedDataMetadata(m.getMetadata());
        if (!protectedFields.isEmpty()) {
            metadata = metadata.with(METADATA_KEY, protectedFields)
                    .with(NAMESPACE_METADATA_KEY, targetNamespace);
        }
        m = m.withMetadata(metadata);
        if (!protectedFields.isEmpty()) {
            Object payloadCopy = sanitizePayload(m.getPayload(), protectedFields);
            m = m.withPayload(payloadCopy);
        }
        if (pending != null && !pending.isEmpty()) {
            protectedFields.values().forEach(key -> pendingValues.put(key, pending));
        }
        return m;
    }

    private static Metadata withoutProtectedDataMetadata(Metadata metadata) {
        return metadata.without(METADATA_KEY).without(NAMESPACE_METADATA_KEY);
    }

    @Override
    public PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
        return getAnnotatedProperties(descriptor.payloadClass(), ProtectData.class).isEmpty()
                ? PreparedLocalDispatch.noOp : null;
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
                if (!message.containsMetadata(METADATA_KEY)) {
                    return handler.getInvokerOrNull(message);
                }
                HandlerInvoker invoker = handler.getInvokerOrNull(message);
                if (invoker == null) {
                    return null;
                }
                return new DelegatingHandlerInvoker(invoker) {
                    private boolean skipped;

                    @Override
                    public Object invoke(BiFunction<Object, Object, Object> combiner) {
                        RestoredMessage restored = restoreProtectedData(message, invoker);
                        if (restored.skip()) {
                            skipped = true;
                            return null;
                        }
                        DeserializingMessage handledMessage = restored.message();
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

                    @Override
                    public boolean wasSkipped() {
                        return skipped || delegate.wasSkipped();
                    }
                };
            }

            @Override
            public HandlerMethod<DeserializingMessage> getHandlerMethodOrNull(DeserializingMessage message) {
                if (!message.containsMetadata(METADATA_KEY)) {
                    return handler.getHandlerMethodOrNull(message);
                }
                return null;
            }

            @Override
            public HandlerMethodPlan<DeserializingMessage> getHandlerMethodPlanOrNull(
                    DeserializingMessage message) {
                if (!message.containsMetadata(METADATA_KEY)) {
                    return handler.getHandlerMethodPlanOrNull(message);
                }
                return null;
            }

            @Override
            public HandlerMethodPlanner<DeserializingMessage> getHandlerMethodPlanner() {
                HandlerMethodPlanner<DeserializingMessage> planner = handler.getHandlerMethodPlanner();
                if (planner == null) {
                    return null;
                }
                return new HandlerMethodPlanner<>() {
                    @Override
                    public Object getCacheKey(DeserializingMessage message) {
                        Object key = planner.getCacheKey(message);
                        return key == null ? null : new DataProtectionPlanKey(
                                key, message.containsMetadata(METADATA_KEY));
                    }

                    @Override
                    public Object getCacheKey(HandlerInput<DeserializingMessage> input) {
                        Object key = planner.getCacheKey(input);
                        boolean protectedData = input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local
                                ? local.containsMetadata(METADATA_KEY)
                                : input.getMessage().containsMetadata(METADATA_KEY);
                        return key == null ? null : new DataProtectionPlanKey(key, protectedData);
                    }

                    @Override
                    public HandlerMethodPreparation<DeserializingMessage> prepare(DeserializingMessage message) {
                        return message.containsMetadata(METADATA_KEY)
                                ? HandlerMethodPreparation.unsupported() : planner.prepare(message);
                    }

                    @Override
                    public HandlerMethodPreparation<DeserializingMessage> prepare(
                            HandlerInput<DeserializingMessage> input) {
                        boolean protectedData = input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local
                                ? local.containsMetadata(METADATA_KEY)
                                : input.getMessage().containsMetadata(METADATA_KEY);
                        return protectedData ? HandlerMethodPreparation.unsupported() : planner.prepare(input);
                    }

                    @Override
                    public HandlerMethodApplicability<DeserializingMessage> prepareApplicability(
                            HandlerInput<DeserializingMessage> input) {
                        if (!(input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local)
                            || input.getMessageIfAvailable() != null || local.containsMetadata(METADATA_KEY)) {
                            return HandlerMethodApplicability.unsupported();
                        }
                        return planner.prepareApplicability(input);
                    }

                    @Override
                    public boolean isPayloadClassKey(HandlerInput<DeserializingMessage> input) {
                        return input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local
                               && input.getMessageIfAvailable() == null
                               && !local.containsMetadata(METADATA_KEY)
                               && planner.isPayloadClassKey(input);
                    }

                    @Override
                    public boolean isNoMatchPayloadClassKey(HandlerInput<DeserializingMessage> input) {
                        return input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local
                               && input.getMessageIfAvailable() == null
                               && !local.containsMetadata(METADATA_KEY)
                               && planner.isNoMatchPayloadClassKey(input);
                    }
                };
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
            RestoredMessage restored = restoreProtectedData(m, invoker);
            if (restored.skip()) {
                return null;
            }
            DeserializingMessage handledMessage = restored.message();
            if (handledMessage != m) {
                return handledMessage.apply(function::apply);
            }
            return function.apply(m);
        };
    }

    @SuppressWarnings("unchecked")
    private RestoredMessage restoreProtectedData(DeserializingMessage m, HandlerDescriptor invoker) {
        if (!m.containsMetadata(METADATA_KEY)) {
            return new RestoredMessage(m, false);
        }
        Object payload = m.getPayload();
        PendingProtectedData pending = getPendingProtectedData(m);
        boolean usePendingData = pending != null && !pending.isExternalized();
        KeyValueStore store = usePendingData ? null
                : keyValueStore.forNamespace(getProtectedDataNamespace(m));
        Map<String, String> protectedFields = m.getMetadata().get(METADATA_KEY, Map.class);
        Map<String, Object> protectedValues = new LinkedHashMap<>();
        Set<String> failedFields = new LinkedHashSet<>();
        protectedFields.forEach((fieldName, key) -> {
            try {
                protectedValues.put(fieldName, usePendingData ? pending.get(key) : store.get(key));
            } catch (Exception e) {
                failedFields.add(fieldName);
                log.warn("Failed to obtain protected field {}", fieldName, e);
            }
        });
        Set<String> missingFields = protectedValues.entrySet().stream()
                .filter(e -> e.getValue() == null && !failedFields.contains(e.getKey()))
                .map(Map.Entry::getKey).collect(toCollection(LinkedHashSet::new));
        if (!missingFields.isEmpty()) {
            MissingProtectedDataPolicy policy = resolveMissingProtectedDataPolicy(m, invoker);
            switch (policy) {
                case WARN -> log.warn(
                        "Protected data is no longer available for fields {} in message {} handled by {}; invoking the handler with null values",
                        missingFields, m.getMessageId(), invoker.getMethod());
                case SKIP -> {
                    publishIgnoreMessageMetric(m, invoker);
                    return new RestoredMessage(m, true);
                }
                case FAIL -> throw new MissingProtectedDataException(missingFields);
                case DEFAULT, HANDLE -> {
                    // HANDLE is intentionally silent. DEFAULT is resolved before reaching this branch.
                }
            }
        }
        ProtectionContext protectionContext = new ProtectionContext();
        protectedFields.forEach((field, reference) -> protectionContext.restored.put(reference,
                new RestoredValue(getProtectedDataNamespace(m), snapshot(failedFields.contains(field)
                        ? readProperty(field, payload).orElse(null) : protectedValues.get(field)))));
        m.withoutContext(ProtectionContext.class).putContext(ProtectionContext.class, protectionContext);
        boolean dropProtectedData = invoker.getMethod().isAnnotationPresent(DropProtectedData.class);
        if (payload != null && payload.getClass().isRecord()) {
            JsonNode payloadTree = serializer.convert(payload, JsonNode.class);
            protectedFields.forEach((fieldName, key) -> restoreProtectedField(
                    payload, payloadTree, fieldName, key, protectedValues, failedFields, dropProtectedData, store, pending));
            return new RestoredMessage(m.withPayload(serializer.convert(payloadTree, payload.getClass())), false);
        }
        if (payload != null && pending != null) {
            Object restoredPayload = pending.getOrCreateRestoredPayload(
                    () -> serializer.deserialize(serializer.serialize(payload)));
            protectedFields.forEach((fieldName, key) -> restoreProtectedField(
                    payload, restoredPayload, fieldName, key, protectedValues, failedFields, dropProtectedData, store, pending));
            return new RestoredMessage(m.withPayload(restoredPayload), false);
        }
        protectedFields.forEach((fieldName, key) -> restoreProtectedField(
                payload, payload, fieldName, key, protectedValues, failedFields, dropProtectedData, store, pending));
        return new RestoredMessage(m, false);
    }

    private PendingProtectedData getPendingProtectedData(DeserializingMessage message) {
        return getProtectedDataReferences(message.toMessage()).stream().map(pendingValues::get)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getProtectedDataReferences(Message message) {
        if (!message.getMetadata().containsKey(METADATA_KEY)) {
            return Set.of();
        }
        Map<String, String> references = message.getMetadata().get(METADATA_KEY, Map.class);
        return references.values().stream().filter(Objects::nonNull).collect(toCollection(LinkedHashSet::new));
    }

    private String getProtectedDataNamespace(DeserializingMessage message) {
        String namespace = message.getMetadataValue(NAMESPACE_METADATA_KEY);
        return namespace == null ? getConsumerNamespace(message) : namespace.isEmpty() ? null : namespace;
    }

    private MissingProtectedDataPolicy resolveMissingProtectedDataPolicy(DeserializingMessage message,
                                                                         HandlerDescriptor invoker) {
        MissingProtectedDataPolicy handlerPolicy = ReflectionUtils.getAnnotationAs(
                        invoker.getMethod(), HandleMessage.class, HandleAnnotation.class)
                .map(HandleAnnotation::getOnMissingProtectedData).orElse(MissingProtectedDataPolicy.DEFAULT);
        if (handlerPolicy != MissingProtectedDataPolicy.DEFAULT) {
            return handlerPolicy;
        }
        MissingProtectedDataPolicy consumerPolicy = message.getContext(ConsumerConfiguration.class)
                .map(ConsumerConfiguration::getOnMissingProtectedData).orElse(MissingProtectedDataPolicy.DEFAULT);
        return consumerPolicy == MissingProtectedDataPolicy.DEFAULT ? onMissingProtectedData : consumerPolicy;
    }

    private void restoreProtectedField(Object logicalPayload, Object payload, String fieldName, String key,
                                        Map<String, Object> protectedValues, Set<String> failedFields,
                                        boolean dropProtectedData, KeyValueStore store,
                                        PendingProtectedData pending) {
        if (!failedFields.contains(fieldName)) {
            try {
                writeProtectedProperty(logicalPayload, payload, fieldName, protectedValues.get(fieldName));
            } catch (Exception e) {
                log.warn("Failed to set protected field {}", fieldName, e);
            }
        }
        if (dropProtectedData) {
            if (store == null) {
                pending.remove(key);
            } else {
                store.delete(key);
            }
        }
    }

    private void publishIgnoreMessageMetric(DeserializingMessage message, HandlerDescriptor invoker) {
        if (!trackingMetricsEnabled) {
            return;
        }
        try {
            String consumer = Tracker.current().map(Tracker::getName)
                    .or(() -> message.getContext(ConsumerConfiguration.class).map(ConsumerConfiguration::getName))
                    .orElseGet(() -> "local-" + message.getMessageType());
            Fluxzero.getOptionally().ifPresent(fc -> fc.metricsGateway().publish(new IgnoreMessageEvent(
                    consumer, invoker.getTargetClass().getSimpleName(), message.getIndex(), message.getMessageType(),
                    message.getTopic(), message.getType(), IgnoreMessageEvent.MISSING_PROTECTED_DATA)));
        } catch (Exception e) {
            log.error("Failed to publish ignore message metrics", e);
        }
    }

    private Object sanitizePayload(Object payload, Map<String, String> protectedFields) {
        if (payload != null && payload.getClass().isRecord()) {
            JsonNode payloadTree = serializer.convert(payload, JsonNode.class);
            protectedFields.forEach((name, key) -> writeProtectedProperty(payload, payloadTree, name, null));
            return serializer.convert(payloadTree, payload.getClass());
        }
        Object payloadCopy = serializer.deserialize(serializer.serialize(payload));
        protectedFields.forEach((name, key) -> writeProperty(name, payloadCopy, null));
        return payloadCopy;
    }

    private Map<String, String> getProtectedFields(Object value, String namespace, PendingProtectedData pending) {
        if (value == null) {
            return Map.of();
        }
        Map<String, String> protectedFields = new LinkedHashMap<>();
        getAnnotatedProperties(value.getClass(), ProtectData.class).stream()
                .flatMap(property -> ofNullable(getValue(property, value)).stream()
                        .flatMap(propertyValue -> getProtectedFields(property, propertyValue, namespace, pending)))
                .forEach(e -> protectedFields.put(e.getKey(), e.getValue()));
        return protectedFields;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    private Stream<Map.Entry<String, String>> getProtectedFields(AccessibleObject holder, Object propertyValue,
                                                                  String namespace, PendingProtectedData pending) {
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
            return Stream.of(Map.entry(name, protectValue(propertyValue, namespace, pending)));
        }
        return getProtectedFields(propertyValue, namespace, pending).entrySet().stream()
                .map(e -> Map.entry("%s/%s".formatted(name, e.getKey()), e.getValue()));
    }

    private String protectValue(Object value, String namespace, PendingProtectedData pending) {
        String key = Fluxzero.currentIdentityProvider().nextTechnicalId();
        if (pending == null) {
            keyValueStore.forNamespace(namespace).store(key, value, Guarantee.STORED);
        } else {
            pending.put(key, value);
        }
        return key;
    }

    private static final class PendingProtectedData {
        private final String namespace;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private boolean externalized;
        private Object restoredPayload;

        private PendingProtectedData(String namespace) {
            this.namespace = namespace;
        }

        private synchronized void put(String key, Object value) {
            values.put(key, value);
        }

        private synchronized Object get(String key) {
            return values.get(key);
        }

        private synchronized void remove(String key) {
            values.remove(key);
        }

        private synchronized boolean isEmpty() {
            return values.isEmpty();
        }

        private synchronized boolean isExternalized() {
            return externalized;
        }

        private synchronized Object getOrCreateRestoredPayload(Supplier<Object> supplier) {
            if (restoredPayload == null) {
                restoredPayload = supplier.get();
            }
            return restoredPayload;
        }

        private synchronized void externalize(KeyValueStore keyValueStore, Set<String> referencedKeys) {
            if (externalized) {
                return;
            }
            KeyValueStore store = keyValueStore.forNamespace(namespace);
            values.forEach((key, value) -> {
                if (referencedKeys.contains(key)) {
                    store.store(key, value, Guarantee.STORED);
                }
            });
            values.clear();
            externalized = true;
        }
    }

    private record RestoredMessage(DeserializingMessage message, boolean skip) {
    }

    private static final class ProtectionContext {
        private final Map<String, RestoredValue> restored = new LinkedHashMap<>();
        private final Map<DeserializingMessage, List<PreparedProtection>> prepared = new IdentityHashMap<>();
    }

    private record RestoredValue(String namespace, Data<byte[]> value) {
        private boolean matches(String namespace, Data<byte[]> value) {
            return Objects.equals(this.namespace, namespace) && Objects.equals(this.value, value);
        }
    }

    private record PreparedProtection(DeserializingMessage source, Data<byte[]> input, Metadata metadata,
                                      String namespace, Data<byte[]> output, Metadata outputMetadata) {
        private boolean matches(DeserializingMessage source, SerializedMessage candidate, String namespace) {
            return this.source == source && Objects.equals(this.namespace, namespace)
                   && input.equals(candidate.getData()) && metadata.equals(candidate.getMetadata());
        }

        private SerializedMessage apply(SerializedMessage candidate) {
            return candidate.withData(output).withMetadata(outputMetadata);
        }
    }

    private record DataProtectionPlanKey(Object delegate, boolean protectedData) {
    }

    @Value
    private static class HandleAnnotation {
        MissingProtectedDataPolicy onMissingProtectedData;
    }
}
