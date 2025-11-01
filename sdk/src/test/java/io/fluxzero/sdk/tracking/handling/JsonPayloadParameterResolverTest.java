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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

class JsonPayloadParameterResolverTest {

    TestFixture testFixture = TestFixture.create();

    @Test
    void payloadToJsonNode() {
        testFixture.registerHandlers(new Object() {
                    @HandleCommand(allowedClasses = TestPayload.class)
                    void handle(ObjectNode payload) {
                        Fluxzero.publishEvent(payload.get("foo").textValue());
                    }
                })
                .whenCommand(new TestPayload("bar"))
                .expectEvents("bar");
    }

    record TestPayload(String foo) {
    }
}