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

package io.fluxzero.sdk.tracking.metrics.host.events;

import io.fluxzero.common.api.JsonType;
import lombok.Builder;
import lombok.Value;

/**
 * JVM class loading metrics.
 */
@Value
@Builder
public class JvmClassMetrics implements JsonType {

    /**
     * The number of classes currently loaded in the JVM.
     */
    int loadedCount;

    /**
     * The total number of classes unloaded since the JVM started.
     */
    long unloadedCount;

    /**
     * The total number of classes loaded since the JVM started.
     */
    long totalLoadedCount;
}
