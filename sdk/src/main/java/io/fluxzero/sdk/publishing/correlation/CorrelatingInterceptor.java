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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.LocalDispatchDescriptor;
import io.fluxzero.sdk.publishing.PreparedLocalDispatch;
import lombok.AllArgsConstructor;

import static io.fluxzero.sdk.Fluxzero.currentCorrelationData;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.METADATA_KEY;
import static io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor.NAMESPACE_METADATA_KEY;

/**
 * A {@link DispatchInterceptor} that enriches outgoing messages with correlation metadata,
 * enabling full traceability across message flows within Fluxzero.
 *
 * <p>This interceptor ensures that dispatched messages inherit and extend the correlation context
 * from the currently handled message and runtime environment.
 * It collects metadata such as:
 * <ul>
 *   <li>Application ID and client identifiers</li>
 *   <li>Consumer/tracker information (if running inside a {@link io.fluxzero.sdk.tracking.Tracker})</li>
 *   <li>Invocation ID (if within a tracked {@link io.fluxzero.sdk.tracking.handling.Invocation})</li>
 *   <li>Correlation ID and trace ID</li>
 *   <li>Trigger type and message type</li>
 * </ul>
 *
 * <p>Specifically, if the current message being handled is a request (e.g., a command or query)
 * and the outgoing message is an event, the outgoing event will inherit the metadata of the incoming request.
 * This is important for tying emitted events to the initiating request in logs, audits, or UIs.
 *
 * <p>In all cases, the interceptor adds the current correlation data retrieved from:
 * {@link Fluxzero#currentCorrelationData()}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * Event event = new MyEvent(...);
 * eventGateway.publish(event);
 * }</pre>
 * The published event will automatically contain metadata such as:
 * <ul>
 *   <li>{@code $correlationId}</li>
 *   <li>{@code $traceId}</li>
 *   <li>{@code $trigger}</li>
 *   <li>{@code $clientId}, {@code $clientName}</li>
 *   <li>{@code $tracker}, {@code $consumer}</li>
 * </ul>
 *
 * @see Fluxzero#currentCorrelationData()
 * @see DefaultCorrelationDataProvider
 * @see DispatchInterceptor
 */
@AllArgsConstructor
public class CorrelatingInterceptor implements DispatchInterceptor {
    @Override
    public Message interceptDispatch(Message message, MessageType messageType, String topic) {
        Metadata metadata = message.getMetadata();
        DeserializingMessage currentMessage = DeserializingMessage.getCurrent();
        if (messageType == MessageType.EVENT) {
            if (currentMessage != null && currentMessage.getMessageType().isRequest()) {
                metadata = currentMessage.getMetadata().without(METADATA_KEY).without(NAMESPACE_METADATA_KEY)
                        .with(metadata);
            }
        }
        if (currentMessage != null) {
            metadata = metadata.without(DefaultCorrelationDataProvider.INSTANCE.getTriggerNamespaceKey());
        }
        return message.withMetadata(metadata.with(currentCorrelationData()));
    }

    @Override
    public PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
        if (descriptor.messageType() == MessageType.EVENT) {
            return null;
        }
        return new PreparedLocalDispatch() {
            private volatile Metadata correlationMetadata;

            @Override
            public boolean prepare(io.fluxzero.sdk.tracking.handling.LocalExecution execution) {
                if (Fluxzero.getOptionally().map(Fluxzero::correlationDataProvider)
                        .orElse(DefaultCorrelationDataProvider.INSTANCE) != DefaultCorrelationDataProvider.INSTANCE) {
                    return false;
                }
                if (correlationMetadata == null) {
                    synchronized (this) {
                        if (correlationMetadata == null) {
                            correlationMetadata = Metadata.of(
                                    DefaultCorrelationDataProvider.INSTANCE.getCorrelationData(
                                            (DeserializingMessage) null));
                        }
                    }
                }
                return true;
            }

            @Override
            public Metadata interceptMetadata(Metadata metadata,
                                              io.fluxzero.sdk.tracking.handling.LocalExecution execution) {
                return metadata.with(correlationMetadata);
            }
        };
    }
}
