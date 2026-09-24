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

package io.fluxzero.common.api.search;

import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetModelMigrationsTest {

    @Test
    void requestAndResultRoundTripThroughThePolymorphicWireContract() {
        GetModelMigrations request = new GetModelMigrations(128);
        GetModelMigrationsResult result = new GetModelMigrationsResult(
                request.getRequestId(),
                List.of(new ModelHeadState(
                        "model-1", "example.Model", 3L, 7L,
                        true, false)));

        GetModelMigrations decodedRequest = assertInstanceOf(
                GetModelMigrations.class,
                JsonUtils.fromJson(JsonUtils.asJson(request), JsonType.class));
        GetModelMigrationsResult decodedResult = assertInstanceOf(
                GetModelMigrationsResult.class,
                JsonUtils.fromJson(JsonUtils.asJson(result), JsonType.class));

        assertEquals(128, decodedRequest.getMaxSize());
        assertEquals(result.getRequestId(), decodedResult.getRequestId());
        assertEquals(result.getMigrations(), decodedResult.getMigrations());
    }

    @Test
    void rejectsUnboundedEnumeration() {
        assertThrows(IllegalArgumentException.class,
                     () -> new GetModelMigrations(0));
        assertThrows(IllegalArgumentException.class,
                     () -> new GetModelMigrations(1_001));
    }
}
