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

package io.fluxzero.javaclient.publishing.correlation;

import io.fluxzero.javaclient.Fluxzero;
import io.fluxzero.javaclient.common.serialization.DeserializingMessage;
import io.fluxzero.javaclient.configuration.client.Client;
import io.fluxzero.javaclient.tracking.Tracker;
import io.fluxzero.javaclient.tracking.handling.Invocation;
import lombok.Getter;

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
}
