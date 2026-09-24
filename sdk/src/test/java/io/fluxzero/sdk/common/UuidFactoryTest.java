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

package io.fluxzero.sdk.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UuidFactoryTest {

    @Test
    void technicalIdsAreUniqueVersionFourUuids() {
        UuidFactory subject = new UuidFactory(false);
        HashSet<String> ids = new HashSet<>();

        for (int i = 0; i < 100_000; i++) {
            String value = subject.nextTechnicalId();
            UUID uuid = UUID.fromString(value);
            assertEquals(4, uuid.version());
            assertEquals(2, uuid.variant());
            ids.add(value);
        }

        assertEquals(100_000, ids.size());
    }

    @Test
    void compactTechnicalIdsRetainTheExistingShape() {
        String value = new UuidFactory().nextTechnicalId();

        assertEquals(32, value.length());
        UUID.fromString(value.replaceFirst(
                "([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})",
                "$1-$2-$3-$4-$5"));
    }
}
