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

/**
 * Internal adapter that marks SDK runtime endpoints and exposes runtime-dispatch timing while delegating the public
 * low-level endpoint contract.
 */
final class SdkRuntimeWebsocketEndpoint implements WebsocketEndpoint {
    private final WebsocketEndpoint delegate;
    private final ThreadLocal<RuntimeDispatchTiming> runtimeDispatchTiming = new ThreadLocal<>();

    SdkRuntimeWebsocketEndpoint(WebsocketEndpoint delegate) {
        this.delegate = delegate;
    }

    void onRuntimeMessage(byte[] bytes, WebsocketSession session, ReceiveTiming receiveTiming,
                          RuntimeDispatchTiming dispatchTiming) {
        RuntimeDispatchTiming previousTiming = runtimeDispatchTiming.get();
        runtimeDispatchTiming.set(dispatchTiming);
        try {
            delegate.onMessage(bytes, session, receiveTiming);
        } finally {
            if (previousTiming == null) {
                runtimeDispatchTiming.remove();
            } else {
                runtimeDispatchTiming.set(previousTiming);
            }
        }
    }

    RuntimeDispatchTiming currentDispatchTiming() {
        return runtimeDispatchTiming.get();
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
