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

package io.fluxzero.common.caching;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NoOpCacheTest {

    @Test
    void bulkMergeDoesNotEvaluateEntries() {
        AtomicBoolean mergeFunctionInvoked = new AtomicBoolean();

        NoOpCache.INSTANCE.mergeAll(
                Map.of("modelId", "model"),
                (previous, update) -> {
                    mergeFunctionInvoked.set(true);
                    return update;
                });

        assertFalse(mergeFunctionInvoked.get());
    }
}
