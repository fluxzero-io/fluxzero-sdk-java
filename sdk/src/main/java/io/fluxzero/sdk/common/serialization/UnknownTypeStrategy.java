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

package io.fluxzero.sdk.common.serialization;

import java.util.stream.Stream;

/**
 * Defines the strategy for handling unknown or unresolvable types during deserialization.
 * <p>
 * This is used by {@link Serializer} implementations to determine how to proceed when a type identifier is not
 * recognized (e.g., missing class, renamed type).
 * </p>
 *
 * <ul>
 *   <li>{@link #AS_INTERMEDIATE} – Treat unknown types as intermediate representations.
 *   Deserialization may still proceed later based on structural conversion or inspection.</li>
 *
 *   <li>{@link #IGNORE} – Silently skip unknown types. The deserialized stream will exclude them. Default for deserialization of tracked messages.</li>
 *
 *   <li>{@link #FAIL} – Immediately throw a {@link DeserializationException} upon encountering an unknown type. Default for event-sourcing.</li>
 * </ul>
 *
 * @see Serializer#deserialize(Stream, UnknownTypeStrategy)
 * @see DeserializationException
 */
public enum UnknownTypeStrategy {
    /**
     * Represents the strategy to treat unknown or unresolvable types as intermediate representations during the
     * deserialization process. This approach allows for deserialization to potentially proceed later based on
     * structural conversion or further inspection, instead of immediately failing or skipping the unknown type.
     */
    AS_INTERMEDIATE,
    /**
     * Silently skips unknown or unresolvable types during the deserialization process. When this strategy is applied,
     * any type identifier that cannot be resolved will be ignored, and the deserialized output will omit the
     * corresponding data without throwing an error.
     * <p>
     * This can be useful when dealing with dynamic schemas or when backward compatibility with modified or incomplete
     * data streams is needed.
     * <p>
     * This is the default strategy for deserializing messages from a global message log.
     */
    IGNORE,
    /**
     * Defines a strategy where deserialization will immediately fail by throwing a {@link DeserializationException}
     * when an unknown or unresolvable type is encountered.
     * <p>
     * This strategy ensures strict validation of incoming data, preventing deserialization from proceeding with
     * unrecognized or unsupported type identifiers.
     * <p>
     * Typically used in scenarios requiring high reliability and accuracy in the deserialization process.
     * <p>
     * This is the default strategy for deserializing events during event sourcing.
     */
    FAIL
}
