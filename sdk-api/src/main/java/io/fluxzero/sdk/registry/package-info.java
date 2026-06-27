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

/**
 * Incubating source-indexed Fluxzero application model.
 * <p>
 * Types in this package are public so tools and execution modes can consume the same metadata, but the model is still
 * expected to evolve while registry semantics settle. {@link io.fluxzero.sdk.registry.ComponentRegistryBlueprint}
 * can render the model as Markdown for local diagnostics, documentation, and agent-readable application summaries.
 * {@link io.fluxzero.sdk.registry.ComponentRegistryProcessor} writes the model from normal javac compilation, while
 * {@link io.fluxzero.sdk.registry.ComponentRegistryGenerator} writes it from source-only tooling for directories such
 * as {@code src/main/fluxzero}. Fluxzero applications load generated
 * {@value io.fluxzero.sdk.registry.ComponentRegistryJson#DEFAULT_RESOURCE} resources automatically at startup, and
 * on-demand execution can consume those artifacts instead of scanning sources at runtime.
 * {@link io.fluxzero.sdk.registry.ComponentMetadataLookup} is the runtime-facing facade for consuming the model without
 * coupling Fluxzero semantics to a specific metadata backend.
 */
package io.fluxzero.sdk.registry;
