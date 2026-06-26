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
 *
 */

package io.fluxzero.sdk.tracking.handling.validation;

import io.fluxzero.sdk.common.exception.FunctionalException;
import lombok.Getter;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Indicates that a request (typically a command or query) contains one or more field-level violations that prevent
 * further processing.
 */
@Getter
public class ValidationException extends FunctionalException {
    /**
     * Sorted legacy violation strings used by older Fluxzero clients.
     */
    private final SortedSet<String> violations;

    /**
     * Lightweight string-only violation summaries with full property paths.
     */
    private final List<ViolationSummary> violationSummaries;

    public ValidationException(String message, Set<String> violations) {
        this(message, violations, List.of());
    }

    @ConstructorProperties({"message", "violations", "violationSummaries"})
    public ValidationException(String message, Set<String> violations, List<ViolationSummary> violationSummaries) {
        super(message, null, false, false);
        this.violations = new TreeSet<>(violations);
        this.violationSummaries = violationSummaries;
    }

    /**
     * String-only validation failure summary for serialized validation responses.
     *
     * @param path    full property path, or an empty string when no path is known
     * @param message interpolated validation message
     */
    public record ViolationSummary(String path, String message) {
    }
}
