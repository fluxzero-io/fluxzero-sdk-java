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

package io.fluxzero.sdk.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDecisionMatrixTest {
    private static final Set<String> REQUIRED_DECISIONS = Set.of(
            "handler.registration",
            "handler.discovery",
            "handler.annotation-kind",
            "handler.disabled-passive-expiry",
            "handler.payload-filter",
            "handler.local-tracked",
            "handler.consumer",
            "handler.parameter-binding",
            "handler.invocation",
            "tracking.gateway-locality",
            "routing.message-key",
            "timeout.request",
            "validation.validate-with",
            "validation.auth-policy",
            "validation.jakarta-elements",
            "validation.jakarta-provider",
            "policy.data-protection",
            "policy.content-filter",
            "modeling.stateful",
            "modeling.association-member",
            "modeling.apply-assert",
            "modeling.property-access",
            "search.document-indexing",
            "casting.route-discovery",
            "casting.invocation",
            "serialization.registered-types",
            "serialization.payload-type",
            "web.route",
            "web.parameter-binding",
            "web.response-mapping",
            "schedule.periodic",
            "schedule.payload-instantiation",
            "extension.builder-components",
            "source.lifecycle"
    );

    private static final Set<String> ALLOWED_SOURCE_VALUES = Set.of(
            "Registry metadata",
            "Generated invocation plan",
            "Allowed JVM backend",
            "Hybrid"
    );

    @Test
    void runtimeDecisionMatrixCoversAllRequiredSemanticAreas() throws Exception {
        Map<String, MatrixRow> matrix = matrix();

        assertTrue(matrix.keySet().containsAll(REQUIRED_DECISIONS),
                   () -> "Missing runtime decision matrix rows: " + missing(matrix));
    }

    @Test
    void runtimeDecisionMatrixUsesDeclaredSourceValues() throws Exception {
        Map<String, MatrixRow> matrix = matrix();

        var invalidRows = matrix.values().stream()
                .filter(row -> !ALLOWED_SOURCE_VALUES.contains(row.currentSource())
                               || !ALLOWED_SOURCE_VALUES.contains(row.finalSource()))
                .toList();
        assertTrue(invalidRows.isEmpty(), () -> "Invalid runtime decision source values: " + invalidRows);
    }

    @Test
    void runtimeDecisionMatrixDoesNotLeaveEmptyBoundaries() throws Exception {
        var emptyBoundaries = matrix().values().stream()
                .filter(row -> row.boundary().isBlank())
                .toList();

        assertTrue(emptyBoundaries.isEmpty(), () -> "Runtime decision rows without boundary: " + emptyBoundaries);
    }

    private static Set<String> missing(Map<String, MatrixRow> matrix) {
        Set<String> missing = new java.util.TreeSet<>(REQUIRED_DECISIONS);
        missing.removeAll(matrix.keySet());
        return missing;
    }

    private static Map<String, MatrixRow> matrix() throws Exception {
        Path matrix = matrixPath();
        assertTrue(Files.exists(matrix), () -> "Missing runtime decision matrix: " + matrix.toAbsolutePath());
        Map<String, MatrixRow> rows = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(matrix)) {
            lines.filter(line -> line.startsWith("| "))
                    .filter(line -> !line.startsWith("| ID "))
                    .filter(line -> !line.startsWith("| ---"))
                    .map(RuntimeDecisionMatrixTest::row)
                    .forEach(row -> rows.put(row.id(), row));
        }
        assertFalse(rows.isEmpty(), "Runtime decision matrix has no rows");
        return rows;
    }

    private static Path matrixPath() {
        return Stream.of(
                        Path.of("..", "docs", "metadata-runtime-decision-matrix.md"),
                        Path.of("docs", "metadata-runtime-decision-matrix.md"))
                .filter(Files::exists)
                .findFirst()
                .orElse(Path.of("..", "docs", "metadata-runtime-decision-matrix.md"));
    }

    private static MatrixRow row(String line) {
        String[] columns = line.split("\\|", -1);
        if (columns.length < 6) {
            throw new IllegalArgumentException("Invalid runtime decision matrix row: " + line);
        }
        return new MatrixRow(
                columns[1].trim(),
                columns[2].trim(),
                columns[3].trim(),
                columns[4].trim(),
                columns[5].trim());
    }

    private record MatrixRow(
            String id,
            String runtimeDecision,
            String currentSource,
            String finalSource,
            String boundary) {
    }
}
