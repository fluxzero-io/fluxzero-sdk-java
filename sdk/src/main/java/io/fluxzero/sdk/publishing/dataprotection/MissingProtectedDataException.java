/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.publishing.dataprotection;

import lombok.Getter;

import java.util.Set;

/**
 * Indicates that a handler configured with {@link MissingProtectedDataPolicy#FAIL} received a message for which one or
 * more protected values are no longer available.
 */
@Getter
public class MissingProtectedDataException extends RuntimeException {
    private final Set<String> missingFields;

    /**
     * Creates an exception for the unavailable protected fields.
     *
     * @param missingFields protected field paths whose values are unavailable
     */
    public MissingProtectedDataException(Set<String> missingFields) {
        super("Protected data is no longer available for fields %s".formatted(missingFields));
        this.missingFields = Set.copyOf(missingFields);
    }
}
