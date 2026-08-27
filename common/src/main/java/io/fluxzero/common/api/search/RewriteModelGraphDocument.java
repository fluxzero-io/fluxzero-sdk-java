/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api.search;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Command;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

/**
 * Conditionally replaces one materialized model-graph document after application-level schema migration.
 * <p>
 * The Runtime applies the replacement only while the stored document still carries {@link #expectedManifest}. This
 * prevents a delayed document handler from overwriting a newer graph projection boundary or a migration performed by
 * another application instance. Direct model documents, relationships and projection progress are not changed.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class RewriteModelGraphDocument extends Command {
    @NonNull SerializedDocument document;
    @NonNull String expectedManifest;
    @NonNull Guarantee guarantee;

    @Override
    public String routingKey() {
        return document.getId();
    }
}
