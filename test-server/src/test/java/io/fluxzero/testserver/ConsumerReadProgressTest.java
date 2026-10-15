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

package io.fluxzero.testserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerReadProgressTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recognizesReadsBeforeAndAfterExpectedIndexPublication(boolean expectedFirst) throws Exception {
        var progress = new ConsumerMetricsBenchmarkTest.ReadProgress("measured-", 2);
        if (expectedFirst) {
            progress.expect(42);
        }
        progress.observe("other-consumer", 42L);
        progress.observe("measured-a", null);
        progress.observe("measured-a", 41L);
        progress.observe("measured-b", 42L);
        progress.observe("measured-b", 42L);
        // An older in-flight observation must not overwrite the latest forward position.
        progress.observe("measured-b", 40L);
        if (!expectedFirst) {
            progress.expect(42);
        }
        assertFalse(progress.await(0, TimeUnit.MILLISECONDS));
        progress.observe("measured-a", 42L);
        assertTrue(progress.await(0, TimeUnit.MILLISECONDS));
    }
}
