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

package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpOriginTest {
    @Test
    void normalizesHostAndEffectivePortWithoutIncludingPrivateRequestData() {
        assertEquals(HttpOrigin.of(URI.create("https://EXAMPLE.com:443/a?q=secret#private")),
                     HttpOrigin.of(URI.create("https://user:password@example.com/b")));
        assertEquals(HttpOrigin.of(URI.create("http://[::1]/a")),
                     HttpOrigin.of(URI.create("http://[::1]:80/b")));
        assertNotEquals(HttpOrigin.of(URI.create("https://example.com")),
                        HttpOrigin.of(URI.create("http://example.com")));
        assertNotEquals(HttpOrigin.of(URI.create("https://example.com")),
                        HttpOrigin.of(URI.create("https://example.com:8443")));
        assertThrows(IllegalArgumentException.class, () -> HttpOrigin.of(URI.create("/relative")));
    }
}
