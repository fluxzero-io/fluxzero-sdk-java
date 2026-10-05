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

/**
 * Metadata-only local type lookup. This does not execute upcasters, decode data, or qualify application logic.
 * A missing input type may still be transformed into a known type by a revision-specific upcaster.
 *
 * @param status whether the serializer recognizes this identifier, or cannot provide this diagnostic
 * @param resolvedType identifier after aliases, when available
 * @param localRevision declared local revision, or null when it cannot be determined
 */
public record TypeInspection(Status status, String resolvedType, Integer localRevision) {
    /** Result of an identifier lookup, independent of payload/replay compatibility. */
    public enum Status {
        /** The identifier is recognized locally. */ KNOWN,
        /** The identifier is not recognized locally before structural upcasting. */ UNKNOWN,
        /** The serializer does not expose this information. */ UNAVAILABLE
    }
}
