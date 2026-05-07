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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsocketConnectionOptionsTest {

    @Test
    void defaultsNullCollectionsToEmptyCollections() {
        WebsocketConnectionOptions options = new WebsocketConnectionOptions(null, null, null, null);

        assertTrue(options.headers().isEmpty());
        assertTrue(options.userProperties().isEmpty());
        assertTrue(options.subprotocols().isEmpty());
    }

    @Test
    void defensivelyCopiesCollectionsAndNormalizesNullHeaderValues() {
        List<String> headerValues = new ArrayList<>(List.of("value"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Fluxzero-Test", headerValues);
        headers.put("Fluxzero-Empty", null);
        Map<String, Object> userProperties = new HashMap<>(Map.of("client", "test"));
        List<String> subprotocols = new ArrayList<>(List.of("fluxzero"));

        WebsocketConnectionOptions options = new WebsocketConnectionOptions(
                headers, userProperties, Duration.ofSeconds(2), subprotocols);
        headerValues.add("mutated");
        headers.put("Fluxzero-Mutated", List.of("mutated"));
        userProperties.put("mutated", true);
        subprotocols.add("mutated");

        assertEquals(List.of("value"), options.headers().get("Fluxzero-Test"));
        assertEquals(List.of(), options.headers().get("Fluxzero-Empty"));
        assertEquals(Map.of("client", "test"), options.userProperties());
        assertEquals(List.of("fluxzero"), options.subprotocols());
        assertThrows(UnsupportedOperationException.class, () -> options.headers().put("new", List.of("value")));
        assertThrows(UnsupportedOperationException.class, () -> options.headers().get("Fluxzero-Test").add("new"));
        assertThrows(UnsupportedOperationException.class, () -> options.subprotocols().add("new"));
    }
}
