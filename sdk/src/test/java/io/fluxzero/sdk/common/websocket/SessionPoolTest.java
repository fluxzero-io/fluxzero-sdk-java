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

package io.fluxzero.sdk.common.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionPoolTest {

    @Test
    void testPoolCyclesSessions() {
        SessionPool sessionPool =
                new SessionPool(3, () -> when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock());
        WebsocketSession first = sessionPool.get();
        WebsocketSession second = sessionPool.get();
        WebsocketSession third = sessionPool.get();
        WebsocketSession fourth = sessionPool.get();

        assertNotSame(first, second);
        assertNotSame(first, third);
        assertNotSame(second, third);
        assertSame(first, fourth);
    }

    @Test
    void singleSessionPoolAlwaysReturnsTheSameSession() {
        SessionPool sessionPool =
                new SessionPool(1, () -> when(mock(WebsocketSession.class).isOpen()).thenReturn(true).getMock());

        WebsocketSession first = sessionPool.get();
        WebsocketSession second = sessionPool.get();
        WebsocketSession third = sessionPool.get();

        assertSame(first, second);
        assertSame(first, third);
    }

    @Test
    void constructorRejectsZeroSizedPool() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPool(0, () -> mock(WebsocketSession.class)));
    }

    @Test
    void constructorRejectsNegativeSizedPool() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPool(-1, () -> mock(WebsocketSession.class)));
    }
}
