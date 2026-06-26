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

package io.fluxzero.sdk.tracking.metrics.host.collectors;

import java.util.Optional;

/**
 * Interface for collecting a specific type of metric.
 * <p>
 * Implementations should be stateless and thread-safe. The {@link #collect()} method
 * may be called concurrently from multiple threads.
 *
 * @param <T> the type of metrics this collector produces
 */
public interface MetricCollector<T> {

    /**
     * Collects metrics.
     *
     * @return an Optional containing the collected metrics, or empty if collection failed
     *         or the metrics are not available on this platform
     */
    Optional<T> collect();

    /**
     * Checks if this collector is available on the current platform.
     * <p>
     * This method should return quickly and may cache its result.
     *
     * @return true if this collector can collect metrics on the current platform
     */
    boolean isAvailable();
}
