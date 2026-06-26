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

package io.fluxzero.sdk.common.exception;

import lombok.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Human-readable Fluxzero error report with a stable error code.
 * <p>
 * Reports are intended for exception messages and logs. They explain what happened, likely causes, concrete recovery
 * steps, and contextual values that help developers fix the issue without having to inspect the SDK internals first.
 */
@Value
public class FluxzeroErrorReport {
    static final String UNSAFE_VALUE_PLACEHOLDER = "<unavailable: value rendering failed>";
    private static final int FULL_MESSAGE_RENDER_LIMIT = 100;
    private static final ConcurrentMap<FluxzeroErrorCode, AtomicInteger> FULL_MESSAGE_RENDER_COUNTS =
            new ConcurrentHashMap<>();

    FluxzeroErrorCode code;
    String title;
    List<String> whatHappened;
    List<String> whyThisHappened;
    List<String> howToFix;
    Map<String, String> context;
    String documentationUrl;

    /**
     * Creates a builder for a report with the given code and title.
     */
    public static Builder builder(FluxzeroErrorCode code, String title) {
        return new Builder(code, title);
    }

    /**
     * Returns the stable machine-readable error code.
     */
    public String getErrorCode() {
        return code == null ? null : code.getCode();
    }

    /**
     * Formats this report as a multi-line exception message.
     */
    public String format() {
        if (!shouldRenderFullMessage()) {
            return formatSummary();
        }
        StringBuilder result = new StringBuilder();
        result.append("FluxzeroError ").append(code.getCode()).append(": ").append(title);
        appendSection(result, "What happened", whatHappened);
        appendSection(result, "Why this happened", whyThisHappened);
        appendNumberedSection(result, "How to fix", howToFix);
        appendContext(result, context);
        if (documentationUrl != null && !documentationUrl.isBlank()) {
            result.append(System.lineSeparator()).append(System.lineSeparator())
                    .append("Docs:").append(System.lineSeparator())
                    .append("  ").append(documentationUrl);
        }
        return result.toString();
    }

    /**
     * Formats this report as a best-effort exception message.
     * <p>
     * If report rendering itself fails, the fallback keeps the stable error code, title, and documentation URL so error
     * reporting does not hide the original SDK failure.
     */
    public String formatSafely() {
        try {
            return format();
        } catch (RuntimeException e) {
            return formatFallback(e);
        }
    }

    private String formatFallback(RuntimeException renderingFailure) {
        String codeText = code == null ? "unknown" : code.getCode();
        String docsUrl = documentationUrl;
        if ((docsUrl == null || docsUrl.isBlank()) && code != null) {
            docsUrl = code.getDocumentationUrl();
        }
        StringBuilder result = new StringBuilder("FluxzeroError ").append(codeText);
        if (title != null && !title.isBlank()) {
            result.append(": ").append(title);
        }
        result.append(System.lineSeparator())
                .append("Rich error details could not be rendered: ")
                .append(renderingFailure.getClass().getName());
        if (docsUrl != null && !docsUrl.isBlank()) {
            result.append(System.lineSeparator()).append("Docs: ").append(docsUrl);
        }
        return result.toString();
    }

    private boolean shouldRenderFullMessage() {
        return FULL_MESSAGE_RENDER_COUNTS.computeIfAbsent(code, c -> new AtomicInteger()).incrementAndGet()
               <= FULL_MESSAGE_RENDER_LIMIT;
    }

    private String formatSummary() {
        StringBuilder result = new StringBuilder();
        result.append("FluxzeroError ").append(code.getCode()).append(": ").append(title)
                .append(System.lineSeparator());
        appendSummaryContext(result);
        result.append("Full error details suppressed after ").append(FULL_MESSAGE_RENDER_LIMIT)
                .append(" occurrences of ").append(code.getCode()).append(" in this JVM.");
        if (documentationUrl != null && !documentationUrl.isBlank()) {
            result.append(System.lineSeparator()).append("Docs: ").append(documentationUrl);
        }
        return result.toString();
    }

    private void appendSummaryContext(StringBuilder result) {
        if (context.isEmpty()) {
            return;
        }
        result.append("Context: ");
        AtomicInteger index = new AtomicInteger();
        context.forEach((key, value) -> {
            if (index.getAndIncrement() > 0) {
                result.append("; ");
            }
            result.append(key).append("=").append(value);
        });
        result.append(System.lineSeparator());
    }

    @Override
    public String toString() {
        return formatSafely();
    }

    static void resetFormatCountsForTesting() {
        FULL_MESSAGE_RENDER_COUNTS.clear();
    }

    private static void appendSection(StringBuilder result, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        result.append(System.lineSeparator()).append(System.lineSeparator()).append(title).append(":");
        lines.forEach(line -> result.append(System.lineSeparator()).append("  ").append(line));
    }

    private static void appendNumberedSection(StringBuilder result, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        result.append(System.lineSeparator()).append(System.lineSeparator()).append(title).append(":");
        for (int i = 0; i < lines.size(); i++) {
            result.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(lines.get(i));
        }
    }

    private static void appendContext(StringBuilder result, Map<String, String> context) {
        if (context.isEmpty()) {
            return;
        }
        result.append(System.lineSeparator()).append(System.lineSeparator()).append("Context:");
        int width = context.keySet().stream().mapToInt(String::length).max().orElse(0) + 1;
        context.forEach((key, value) -> result.append(System.lineSeparator())
                .append("  ").append(String.format("%-" + width + "s", key + ":"))
                .append(" ").append(value));
    }

    /**
     * Builder for {@link FluxzeroErrorReport}.
     */
    public static final class Builder {
        private final FluxzeroErrorCode code;
        private final String title;
        private final List<String> whatHappened = new ArrayList<>();
        private final List<String> whyThisHappened = new ArrayList<>();
        private final List<String> howToFix = new ArrayList<>();
        private final Map<String, String> context = new LinkedHashMap<>();
        private String documentationUrl;

        private Builder(FluxzeroErrorCode code, String title) {
            this.code = Objects.requireNonNull(code, "code");
            this.title = Objects.requireNonNull(title, "title");
            this.documentationUrl = code.getDocumentationUrl();
        }

        /**
         * Adds lines for the "What happened" section.
         */
        public Builder whatHappened(String... lines) {
            this.whatHappened.addAll(nonBlankLines(lines));
            return this;
        }

        /**
         * Adds lines for the "Why this happened" section.
         */
        public Builder whyThisHappened(String... lines) {
            this.whyThisHappened.addAll(nonBlankLines(lines));
            return this;
        }

        /**
         * Adds recovery steps for the "How to fix" section.
         */
        public Builder howToFix(String... lines) {
            this.howToFix.addAll(nonBlankLines(lines));
            return this;
        }

        /**
         * Adds one contextual key/value pair.
         */
        public Builder context(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.context.put(key, safeString(value));
            }
            return this;
        }

        /**
         * Overrides the default documentation URL. Pass {@code null} to omit the docs section.
         */
        public Builder documentationUrl(String documentationUrl) {
            this.documentationUrl = documentationUrl;
            return this;
        }

        /**
         * Builds the report.
         */
        public FluxzeroErrorReport build() {
            return new FluxzeroErrorReport(
                    code, title, List.copyOf(whatHappened), List.copyOf(whyThisHappened), List.copyOf(howToFix),
                    Collections.unmodifiableMap(new LinkedHashMap<>(context)), documentationUrl);
        }

        private static List<String> nonBlankLines(String... lines) {
            if (lines == null) {
                return List.of();
            }
            return Arrays.stream(lines).filter(line -> line != null && !line.isBlank()).toList();
        }
    }

    static String safeString(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Objects.toString(value);
        } catch (RuntimeException e) {
            return UNSAFE_VALUE_PLACEHOLDER;
        }
    }
}
