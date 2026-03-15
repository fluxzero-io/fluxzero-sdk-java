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

/**
 * Marker interface for value objects that should be treated as leaves during reflective traversal.
 * <p>
 * Types implementing this interface are handled as terminal values by Fluxzero's reflection-based infrastructure.
 * This means they are not recursively inspected for nested annotated properties when features such as
 * {@code @Facet}, {@code @Sortable}, or {@code @ProtectData} traverse an object graph.
 * <p>
 * Use this for domain-specific value objects that should behave like primitives or other scalar values from the
 * framework's point of view, even if they are implemented as regular classes with multiple fields.
 * <p>
 * Typical examples are identifier wrappers, strongly typed value objects, or custom scalar abstractions where the
 * framework should rely on the value as a whole rather than introspecting its internal structure.
 */
public interface Leaf {
}
