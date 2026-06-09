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

package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeboxedExecutorTest {

    @Test
    void callAndWaitManagedBlocksWhenCalledFromForkJoinPool() throws Exception {
        ForkJoinPool forkJoinPool = new ForkJoinPool(1);
        TimeboxedExecutor timeboxedExecutor =
                new TimeboxedExecutor(Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory()));
        try {
            CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return timeboxedExecutor.callAndWait(() -> {
                        CompletableFuture<String> nested = new CompletableFuture<>();
                        forkJoinPool.execute(() -> nested.complete("ok"));
                        return nested.get(2, TimeUnit.SECONDS);
                    }, Duration.ofSeconds(2));
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, forkJoinPool);

            assertEquals("ok", result.get(5, TimeUnit.SECONDS));
        } finally {
            timeboxedExecutor.close();
            forkJoinPool.shutdownNow();
        }
    }
}
