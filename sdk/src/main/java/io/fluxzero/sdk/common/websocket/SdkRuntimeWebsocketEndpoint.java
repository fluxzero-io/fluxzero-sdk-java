/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.common.websocket;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Internal adapter that marks SDK runtime endpoints and exposes runtime-dispatch timing while delegating the public
 * low-level endpoint contract.
 */
final class SdkRuntimeWebsocketEndpoint implements WebsocketEndpoint {
    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);
    private final WebsocketEndpoint delegate;
    private final ThreadLocal<RuntimeDispatchTiming> runtimeDispatchTiming = new ThreadLocal<>();

    SdkRuntimeWebsocketEndpoint(WebsocketEndpoint delegate) {
        this.delegate = delegate;
    }

    RuntimeIngressController.MessageDispatch onRuntimeMessage(
            byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming,
            RuntimeDispatchTiming dispatchTiming) {
        if (dispatchTiming == null) {
            return dispatchRuntimeMessage(bytes, session, receiveTiming);
        }
        RuntimeDispatchTiming previousTiming = runtimeDispatchTiming.get();
        runtimeDispatchTiming.set(dispatchTiming);
        try {
            return dispatchRuntimeMessage(bytes, session, receiveTiming);
        } finally {
            if (previousTiming == null) {
                runtimeDispatchTiming.remove();
            } else {
                runtimeDispatchTiming.set(previousTiming);
            }
        }
    }

    private RuntimeIngressController.MessageDispatch dispatchRuntimeMessage(
            byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming) {
        if (delegate instanceof AbstractWebsocketClient client) {
            return client.dispatchStagedRuntimeMessage(bytes, session, receiveTiming);
        }
        delegate.onMessage(bytes, session, receiveTiming);
        return RuntimeIngressController.MessageDispatch.admitted(COMPLETED);
    }

    RuntimeDispatchTiming currentDispatchTiming() {
        return runtimeDispatchTiming.get();
    }

    void onRuntimeIngressBackpressure(WebsocketSession session, boolean backpressured,
                                      RuntimeIngressController.State state) {
        if (delegate instanceof AbstractWebsocketClient client) {
            client.onRuntimeIngressBackpressure(session, backpressured, state);
        }
    }

    void onRuntimeIngressProgress(
            WebsocketSession session, RuntimeIngressController.Progress progress, int retainedMessages,
            long sequence) {
        if (delegate instanceof AbstractWebsocketClient client) {
            client.onRuntimeIngressProgress(session, progress, retainedMessages, sequence);
        }
    }

    @Override
    public void onOpen(WebsocketSession session) {
        delegate.onOpen(session);
    }

    @Override
    public void onMessage(byte[] bytes, WebsocketSession session) {
        delegate.onMessage(bytes, session);
    }

    @Override
    public void onMessage(byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming) {
        delegate.onMessage(bytes, session, receiveTiming);
    }

    @Override
    public boolean captureReceiveTiming() {
        return delegate.captureReceiveTiming();
    }

    @Override
    public void onPong(ByteBuffer data, WebsocketSession session) {
        delegate.onPong(data, session);
    }

    @Override
    public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
        delegate.onClose(session, closeReason);
    }

    @Override
    public void onError(WebsocketSession session, Throwable error) {
        delegate.onError(session, error);
    }

    /**
     * Runtime-message dispatch timestamps and monotonic queue duration in milliseconds.
     */
    record RuntimeDispatchTiming(long queuedTimestamp, long startedTimestamp, long queueDuration) {
    }
}
