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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacklogTest {

    @Test
    void capsInitialBatchCapacity() {
        assertEquals(8, Backlog.initialBatchCapacity(8));
        assertEquals(16, Backlog.initialBatchCapacity(512));
        assertEquals(16, Backlog.initialBatchCapacity(1024));
        assertEquals(16, Backlog.initialBatchCapacity(Integer.MAX_VALUE));
    }

    @Test
    void boundsOrderedBatchesByCountAndWeight() {
        List<List<String>> batches = new ArrayList<>();
        Backlog<String> subject = Backlog.forOrderedAsyncConsumer(batch -> {
            batches.add(List.copyOf(batch));
            return CompletableFuture.completedFuture(null);
        }, 3, String::length, 5L);

        subject.add(List.of("aa", "bbb", "123456", "c", "dd", "e")).join();
        subject.shutDown();

        assertEquals(
                List.of(List.of("aa", "bbb"), List.of("123456"), List.of("c", "dd", "e")),
                batches);
    }

    @Test
    void invalidWeightFailsTheItemWithoutStoppingTheBacklog() {
        Backlog<String> subject = Backlog.forOrderedAsyncConsumer(
                batch -> CompletableFuture.completedFuture(null),
                3, value -> value.equals("invalid") ? -1L : value.length(), 5L);

        assertThrows(Exception.class, () -> subject.add("invalid").join());
        subject.add("valid").join();
        subject.shutDown();
    }
}
