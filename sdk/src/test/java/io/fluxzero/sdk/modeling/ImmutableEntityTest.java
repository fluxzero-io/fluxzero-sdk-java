/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutableEntityTest {

    @Test
    void computesLazyEntityPropertiesExactlyOnceAcrossThreads()
            throws Exception {
        CountDownLatch computationStarted = new CountDownLatch(1);
        CountDownLatch releaseComputation = new CountDownLatch(1);
        AtomicInteger computations = new AtomicInteger();
        List<ImmutableEntity<?>> expected = List.of();
        ImmutableEntity<String> entity = new ImmutableEntity<>(
                "id", String.class, "value", "id",
                null, null, null, null) {
            @Override
            protected Collection<? extends ImmutableEntity<?>>
                    computeEntities() {
                computations.incrementAndGet();
                computationStarted.countDown();
                try {
                    assertTrue(releaseComputation.await(
                            5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return expected;
            }
        };

        try (var executor = Executors.newFixedThreadPool(8)) {
            var reads = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(entity::entities))
                    .toList();
            assertTrue(computationStarted.await(
                    5, TimeUnit.SECONDS));
            releaseComputation.countDown();
            for (var read : reads) {
                assertSame(expected, read.get());
            }
        }

        assertEquals(1, computations.get());
    }
}
