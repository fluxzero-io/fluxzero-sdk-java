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

package io.fluxzero.sdk.tracking.handling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvocationTest {

    @Test
    void performInvocationClearsCurrentBeforeCompletingCallbacks() {
        AtomicReference<Invocation> activeInvocation = new AtomicReference<>();
        AtomicReference<Invocation> callbackInvocation = new AtomicReference<>();
        AtomicReference<Object> callbackResult = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();

        Object result = Invocation.performInvocation(() -> {
            activeInvocation.set(Invocation.getCurrent());
            Invocation.whenHandlerCompletes((r, e) -> {
                callbackInvocation.set(Invocation.getCurrent());
                callbackResult.set(r);
                completed.set(true);
            });
            return "result";
        });

        assertEquals("result", result);
        assertTrue(completed.get());
        assertEquals("result", callbackResult.get());
        assertNull(callbackInvocation.get());
        assertNull(Invocation.getCurrent());
        assertNotNull(activeInvocation.get());
    }
}
