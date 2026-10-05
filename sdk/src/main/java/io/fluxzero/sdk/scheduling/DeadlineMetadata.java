/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.ModelRoot;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Internal codec for SDK-owned deadline metadata in events, Model documents and snapshots. */
public final class DeadlineMetadata {
    /** Reserved SDK namespace; application/dispatch metadata cannot supply values in this namespace. */
    public static final String PREFIX = io.fluxzero.common.api.modeling.ModelDeadlineUpdate.METADATA_PREFIX;

    private DeadlineMetadata() {}

    private static String key(String modelId) {
        return PREFIX + "model." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(modelId.getBytes(StandardCharsets.UTF_8));
    }

    /** Removes untrusted deadline metadata before preparing a live Model write. */
    public static Metadata strip(Metadata metadata) {
        return metadata.entrySet().stream().noneMatch(e -> e.getKey().startsWith(PREFIX)) ? metadata
                : metadata.withoutIf(k -> k.startsWith(PREFIX));
    }

    /** Adds one complete authoritative category map, including an explicit empty map when canceled. */
    public static Metadata with(Metadata metadata, String modelId, Map<String, DeadlineInfo> deadlines) {
        return metadata.with(key(modelId), JsonUtils.asJson(deadlines));
    }

    /** Restores a Model's recorded categories; old records without metadata retain their prior value. */
    public static Map<String, DeadlineInfo> read(Metadata metadata, String modelId,
                                                Map<String, DeadlineInfo> previous) {
        String value = metadata.get(key(modelId));
        return value == null ? previous : Map.copyOf(JsonUtils.fromJson(value,
                f -> f.constructMapType(Map.class, String.class, DeadlineInfo.class)));
    }

    /** Obtains the immutable deadline map from a loaded Model revision. */
    public static Map<String, DeadlineInfo> get(Entity<?> entity) {
        return entity instanceof ModelRoot<?> root ? root.deadlines() : Map.of();
    }
}
