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

package io.fluxzero.sdk.common.serialization;

import lombok.Getter;

/** A serialized identifier remains unknown after upcasting; preserves the DeserializationException contract. */
@Getter
public class UnknownSerializedTypeException extends DeserializationException {
    /** Identifier that could not be resolved, after any upcasting. */
    private final String serializedType;
    /** Revision of the unresolved representation. */
    private final int revision;

    /** Creates a failure without retaining serialized payload bytes or metadata. */
    public UnknownSerializedTypeException(String serializedType, int revision) {
        super("Could not deserialize object. The serialized type is unknown: %s (rev. %d)"
                      .formatted(serializedType, revision));
        this.serializedType = serializedType;
        this.revision = revision;
    }
}
