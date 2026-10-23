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

import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.*;

class LocalDispatchFilterTest {
    @Test
    void restrictionIsScopedAndSurvivesNestedRestrictedCallsAndFailures() {
        var message = new DeserializingMessage(new Message("value"), COMMAND, new JacksonSerializer());
        var filter = new LocalDispatchFilter(HandlerFilter.ALWAYS_HANDLE, (m, e, a, t) -> false)
                .restrict(MessageFilter.allowAll());
        assertTrue(filter.test(message, null, null, String.class));
        assertThrows(IllegalStateException.class, () -> LocalDispatchFilter.inDispatch(message, false, () -> {
            assertFalse(filter.test(message, null, null, String.class));
            LocalDispatchFilter.inDispatch(message, false, () -> {
                assertFalse(filter.test(message, null, null, String.class));
                return null;
            });
            assertFalse(filter.test(message, null, null, String.class));
            throw new IllegalStateException("handler failed");
        }));
        assertTrue(filter.test(message, null, null, String.class));
        LocalDispatchFilter.inDispatch(message, true, () -> {
            assertTrue(filter.test(message, null, null, String.class));
            return null;
        });
    }
}
