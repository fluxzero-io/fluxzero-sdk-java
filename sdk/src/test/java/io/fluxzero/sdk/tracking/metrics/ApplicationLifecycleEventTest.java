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

package io.fluxzero.sdk.tracking.metrics;

import io.fluxzero.common.api.ApplicationLifecycleEvent;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import static io.fluxzero.common.api.ApplicationLifecycleEvent.Phase.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationLifecycleEventTest {

    @Test
    void serializesWithoutSdkTypeCoupling() {
        ApplicationLifecycleEvent event = new ApplicationLifecycleEvent(
                STARTED, "startup", "application", "client", "client-id", "namespace", "1.2.3", 123L, 456L);
        JacksonSerializer serializer = new JacksonSerializer();

        var serialized = serializer.serialize(event);
        ApplicationLifecycleEvent result = serializer.deserialize(serialized);

        assertEquals(ApplicationLifecycleEvent.class.getName(), serialized.getType());
        assertEquals(event, result);
        assertFalse(JsonType.class.isAssignableFrom(ApplicationLifecycleEvent.class));
    }
}
