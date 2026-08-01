/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultRequestHandlerResponseCallbackTest {

    @Test
    void evaluatesLastChunkOnceOnSynchronousFastPath() {
        AtomicInteger evaluations = new AtomicInteger();
        SerializedMessage response = new SerializedMessage(
                new Data<>(new byte[0], "result", 0),
                Metadata.empty(), "message", 1L) {
            @Override
            public boolean lastChunk() {
                evaluations.incrementAndGet();
                return true;
            }
        };
        CompletableFuture<SerializedMessage> result = new CompletableFuture<>();
        DefaultRequestHandler.ResponseCallback callback =
                new DefaultRequestHandler.ResponseCallback(null, result);

        callback.process(response, Runnable::run);

        assertSame(response, result.join());
        assertEquals(1, evaluations.get());
    }
}
