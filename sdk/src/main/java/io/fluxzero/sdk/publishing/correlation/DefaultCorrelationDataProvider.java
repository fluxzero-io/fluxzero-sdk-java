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

package io.fluxzero.sdk.publishing.correlation;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.Invocation;
import jakarta.annotation.Nullable;
import lombok.Getter;

import java.time.Duration;

import static io.fluxzero.sdk.Fluxzero.currentTime;
import static io.fluxzero.sdk.common.ClientUtils.getConsumerNamespace;

/**
 * Default implementation of the {@link CorrelationDataProvider} interface.
 * <p>
 * This provider automatically assembles standard correlation metadata that is attached to outgoing messages
 * in a Fluxzero application. This correlation data enables tracing, auditing, monitoring, and debugging
 * across asynchronous message flows.
 *
 * <p>It gathers correlation context from multiple sources, including:
 * <ul>
 *   <li>The current {@link Client}</li>
 *   <li>The current {@link Tracker} if one is active</li>
 *   <li>The current {@link DeserializingMessage} being handled</li>
 *   <li>The current {@link Invocation} context</li>
 * </ul>
 *
 * <p>In addition to these fields, trace-level metadata from the current message
 * (e.g. custom entries marked as traceable) is also included.
 *
 * <p>This correlation metadata is typically added to outgoing messages automatically via
 * the {@link CorrelatingInterceptor}.
 *
 * @see CorrelationDataProvider
 * @see CorrelatingInterceptor
 * @see Fluxzero#currentCorrelationData()
 */
@Getter
public enum DefaultCorrelationDataProvider implements CorrelationDataProvider {
    INSTANCE;

    /**
     * Returns the default correlation data directly in its compact metadata representation.
     *
     * @param currentMessage the message currently being handled, or {@code null}
     * @return compact correlation metadata equivalent to {@link #getCorrelationData(DeserializingMessage)}
     */
    public Metadata getCorrelationMetadata(@Nullable DeserializingMessage currentMessage) {
        Metadata.Builder result = Metadata.builder(16);
        Client applicationClient = Fluxzero.getOptionally().map(Fluxzero::client).orElse(null);
        if (applicationClient != null) {
            String applicationId = applicationClient.applicationId();
            if (applicationId != null) {
                result.put(getApplicationIdKey(), applicationId);
            }
            result.put(getClientIdKey(), applicationClient.id());
            result.put(getClientNameKey(), applicationClient.name());
        }
        Tracker tracker = Tracker.current.get();
        if (tracker != null) {
            result.put(getConsumerKey(), tracker.getName());
            result.put(getTrackerKey(), tracker.getTrackerId());
        }
        Invocation invocation = Invocation.getCurrent();
        if (invocation != null) {
            result.put(getInvocationKey(), invocation.getId());
            String handler = invocation.getHandler();
            if (handler != null) {
                result.put(getHandlerKey(), handler);
            }
        }
        if (currentMessage != null) {
            Long index = currentMessage.getIndex();
            String correlationId = index == null ? currentMessage.getMessageId() : index.toString();
            Metadata currentMetadata = currentMessage.getMetadata();
            result.put(getCorrelationIdKey(), correlationId);
            result.put(getTraceIdKey(), currentMetadata.getOrDefault(getTraceIdKey(), correlationId));
            result.put(getTriggerKey(), currentMessage.getType());
            result.put(getTriggerTypeKey(), currentMessage.getMessageType().name());
            String consumerNamespace = getConsumerNamespace(currentMessage);
            String triggerNamespace = consumerNamespace == null && applicationClient != null
                    ? applicationClient.namespace() : consumerNamespace;
            if (triggerNamespace != null) {
                result.put(getTriggerNamespaceKey(), triggerNamespace);
            }
            result.putAll(currentMetadata.getTraceEntries());
            result.put(getDelayKey(), Long.toString(
                    Duration.between(currentMessage.getTimestamp(), currentTime()).toMillis()));
        }
        return result.build();
    }
}
