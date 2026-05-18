/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.RuntimeLifecycleEvent;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import static io.fluxzero.common.api.RuntimeLifecycleEvent.Phase.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeLifecycleEventTest {

    @Test
    void eventRoundTripsThroughDefaultSerializer() {
        JacksonSerializer serializer = new JacksonSerializer();
        RuntimeLifecycleEvent event = new RuntimeLifecycleEvent(STARTED, "FluxzeroTestServer", "1.2.3", 8888, 123L);

        var serialized = serializer.serialize(event);
        RuntimeLifecycleEvent result = serializer.deserialize(serialized);

        assertEquals(RuntimeLifecycleEvent.class.getName(), serialized.getType());
        assertEquals(event, result);
    }

    @Test
    void eventDeserializesThroughJsonTypeDiscriminator() {
        JsonType result = JsonUtils.fromJson(
                """
                        {
                          "@type": "runtimeLifecycleEvent",
                          "phase": "STARTED",
                          "runtime": "FluxzeroTestServer",
                          "runtimeVersion": "1.2.3",
                          "port": 8888,
                          "timestamp": 123
                        }
                        """, JsonType.class);

        assertEquals(new RuntimeLifecycleEvent(STARTED, "FluxzeroTestServer", "1.2.3", 8888, 123L), result);
    }
}
