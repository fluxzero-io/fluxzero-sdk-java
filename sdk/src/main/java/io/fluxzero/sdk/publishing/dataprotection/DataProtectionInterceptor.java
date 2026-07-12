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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperties;
import static io.fluxzero.common.reflection.ReflectionUtils.getPropertyName;
import static io.fluxzero.common.reflection.ReflectionUtils.getTypeAnnotation;
import static io.fluxzero.common.reflection.ReflectionUtils.getValue;
import static io.fluxzero.common.reflection.ReflectionUtils.isLeafValue;
import static io.fluxzero.common.reflection.ReflectionUtils.writeProperty;
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
@Slf4j
public class DataProtectionInterceptor implements DispatchInterceptor, HandlerInterceptor {

    public static String METADATA_KEY = "$protectedData";

    private final KeyValueStore keyValueStore;
    private final Serializer serializer;
    private final MissingProtectedDataPolicy onMissingProtectedData;
    private final boolean trackingMetricsEnabled;

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
                if (!message.getMetadata().containsKey(METADATA_KEY)) {
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
                if (!message.getMetadata().containsKey(METADATA_KEY)) {
                    return handler.getHandlerMethodOrNull(message);
                }
                return null;
            }

            @Override
            public HandlerMethodPlan<DeserializingMessage> getHandlerMethodPlanOrNull(
                    DeserializingMessage message) {
                if (!message.getMetadata().containsKey(METADATA_KEY)) {
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
                                key, message.getMetadata().containsKey(METADATA_KEY));
                    }

                    @Override
                    public Object getCacheKey(HandlerInput<DeserializingMessage> input) {
                        Object key = planner.getCacheKey(input);
                        boolean protectedData = input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local
                                ? local.containsMetadata(METADATA_KEY)
                                : input.getMessage().getMetadata().containsKey(METADATA_KEY);
                        return key == null ? null : new DataProtectionPlanKey(key, protectedData);
                    }

                    @Override
                    public HandlerMethodPreparation<DeserializingMessage> prepare(DeserializingMessage message) {
                        return message.getMetadata().containsKey(METADATA_KEY)
                                ? HandlerMethodPreparation.unsupported() : planner.prepare(message);
                    }

                    @Override
                    public HandlerMethodPreparation<DeserializingMessage> prepare(
                            HandlerInput<DeserializingMessage> input) {
                        boolean protectedData = input instanceof io.fluxzero.sdk.tracking.handling.LocalHandlerInput local
                                ? local.containsMetadata(METADATA_KEY)
                                : input.getMessage().getMetadata().containsKey(METADATA_KEY);
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
        if (!m.getMetadata().containsKey(METADATA_KEY)) {
            return new RestoredMessage(m, false);
        }
        Object payload = m.getPayload();
        Map<String, String> protectedFields = m.getMetadata().get(METADATA_KEY, Map.class);
        Map<String, Object> protectedValues = new LinkedHashMap<>();
        Set<String> failedFields = new LinkedHashSet<>();
        protectedFields.forEach((fieldName, key) -> {
            try {
                protectedValues.put(fieldName, keyValueStore.get(key));
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
        boolean dropProtectedData = invoker.getMethod().isAnnotationPresent(DropProtectedData.class);
        if (payload != null && payload.getClass().isRecord()) {
            JsonNode payloadTree = serializer.convert(payload, JsonNode.class);
            protectedFields.forEach((fieldName, key) -> restoreProtectedField(
                    payloadTree, fieldName, key, protectedValues, failedFields, dropProtectedData));
            return new RestoredMessage(m.withPayload(serializer.convert(payloadTree, payload.getClass())), false);
        }
        protectedFields.forEach((fieldName, key) -> restoreProtectedField(
                payload, fieldName, key, protectedValues, failedFields, dropProtectedData));
        return new RestoredMessage(m, false);
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

    private void restoreProtectedField(Object payload, String fieldName, String key,
                                        Map<String, Object> protectedValues, Set<String> failedFields,
                                        boolean dropProtectedData) {
        if (!failedFields.contains(fieldName)) {
            try {
                writeProperty(fieldName, payload, protectedValues.get(fieldName));
            } catch (Exception e) {
                log.warn("Failed to set protected field {}", fieldName, e);
            }
        }
        if (dropProtectedData) {
            keyValueStore.delete(key);
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

    private record RestoredMessage(DeserializingMessage message, boolean skip) {
    }

    private record DataProtectionPlanKey(Object delegate, boolean protectedData) {
    }

    @Value
    private static class HandleAnnotation {
        MissingProtectedDataPolicy onMissingProtectedData;
    }
}
