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

package io.fluxzero.sdk.tracking.metrics;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.AdhocDispatchInterceptor;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.BatchInterceptor;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;

import java.util.function.Consumer;
import java.util.function.Function;

import static io.fluxzero.sdk.publishing.AdhocDispatchInterceptor.runWithAdhocInterceptor;

/**
 * Interceptor that disables the dispatch of outbound {@link MessageType#METRICS} messages.
 *
 * <p>This class implements {@link HandlerInterceptor}, {@link BatchInterceptor}, and {@link DispatchInterceptor},
 * allowing it to wrap individual message handlers, batch execution by message trackers, or consumer-scoped dispatch
 * interception directly. When applied as a handler or batch interceptor, it uses an
 * {@link AdhocDispatchInterceptor} to suppress the publication of metrics within the scope of the handler
 * or batch execution.
 *
 * <p>Typical usage includes applying this interceptor to consumers that should not emit metrics, such as
 * utility consumers that operate in high-frequency or low-signal environments.
 *
 * <h2>Example Usage</h2>
 * To apply this interceptor, annotate your handler class using
 * {@code @Consumer(batchInterceptors = DisableMetrics.class)} or
 * {@code @Consumer(handlerInterceptors = DisableMetrics.class)} or
 * {@code @Consumer(dispatchInterceptors = DisableMetrics.class)}:
 *
 * <pre>{@code
 * @Consumer(handlerInterceptors = DisableMetrics.class)
 * public class OrderHandler {
 *
 *     @HandleEvent
 *     public void on(OrderPlaced event) {
 *         // Any outbound metrics from this handler will be suppressed
 *     }
 * }
 * }</pre>
 *
 * @see AdhocDispatchInterceptor
 * @see MessageType#METRICS
 * @see HandlerInterceptor
 * @see BatchInterceptor
 * @see DispatchInterceptor
 */
public class DisableMetrics implements HandlerInterceptor, BatchInterceptor, DispatchInterceptor {
    @Override
    public Message interceptDispatch(Message message, MessageType messageType, String topic) {
        return messageType == MessageType.METRICS ? null : message;
    }

    @Override
    public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
        return batch -> AdhocDispatchInterceptor.runWithAdhocInterceptor(() -> consumer.accept(batch), this,
                                                                         MessageType.METRICS);
    }

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        return m -> runWithAdhocInterceptor(() -> function.apply(m), this, MessageType.METRICS);
    }
}
