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
 *
 */

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.application.DefaultPropertySource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.newPlatformThreadFactory;

final class SearchClientAsyncSupport {
    private static final String THREADS_PROPERTY = "FLUXZERO_SEARCH_ASYNC_THREADS";
    private static final String QUEUE_SIZE_PROPERTY = "FLUXZERO_SEARCH_ASYNC_QUEUE_SIZE";
    private static final int DEFAULT_THREADS = 32;
    private static final int DEFAULT_QUEUE_SIZE = 2048;

    private static final ThreadPoolExecutor executor = createExecutor();

    private SearchClientAsyncSupport() {
    }

    static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private static ThreadPoolExecutor createExecutor() {
        DefaultPropertySource propertySource = DefaultPropertySource.getInstance();
        int threads = Math.max(1, propertySource.getInteger(THREADS_PROPERTY, DEFAULT_THREADS));
        int queueSize = Math.max(1, propertySource.getInteger(QUEUE_SIZE_PROPERTY, DEFAULT_QUEUE_SIZE));
        ThreadPoolExecutor result = new ThreadPoolExecutor(
                threads, threads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                newPlatformThreadFactory("fluxzero-search-client-async-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        result.allowCoreThreadTimeOut(true);
        return result;
    }
}
