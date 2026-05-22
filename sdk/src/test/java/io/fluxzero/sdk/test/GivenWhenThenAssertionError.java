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

package io.fluxzero.sdk.test;

import com.fasterxml.jackson.databind.ObjectWriter;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.Message;
import lombok.SneakyThrows;
import lombok.Value;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.platform.commons.util.ExceptionUtils;
import org.opentest4j.AssertionFailedError;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;

/**
 * Specialized {@link AssertionFailedError} used by the Fluxzero testing framework to signal assertion failures
 * during {@code then} phase validations in a {@code given-when-then} test.
 * <p>
 * This exception provides enhanced formatting for improved diffing between expected and actual values. Specifically,
 * if the compared values include:
 * <ul>
 *   <li>{@link Message} instances – only user metadata is retained (technical metadata is stripped), and payload is shown separately</li>
 *   <li>{@link Throwable} instances – stack traces are extracted and rendered as strings</li>
 *   <li>Other objects – serialized using a pretty-printed JSON formatter</li>
 * </ul>
 * <p>
 * Formatting is handled internally via {@link #formatForComparison(Object)}. Differences are then passed to the parent
 * {@link AssertionFailedError} constructor to enable clear diffs in IDEs and test runners.
 */
public class GivenWhenThenAssertionError extends AssertionFailedError {
    private static final String VERBOSE_ASSERTIONS_PROPERTY = "fluxzero.maven.enabled";
    private static final ThreadLocal<Supplier<String>> traceSupplier = new ThreadLocal<>();
    private static final ThreadLocal<String> javaCommandOverride = new ThreadLocal<>();

    /**
     * Shared JSON object writer used to serialize expected and actual values for comparison.
     * Uses {@link JsonUtils} with default pretty printer enabled.
     */
    public static ObjectWriter formatter = JsonUtils.reader.writerWithDefaultPrettyPrinter();

    /**
     * Constructs an assertion error with only a message.
     *
     * @param message the failure message
     */
    public GivenWhenThenAssertionError(String message) {
        super(appendTrace(message));
    }

    /**
     * Constructs an assertion error with a message and a cause.
     *
     * @param message the failure message
     * @param cause   the exception that caused this failure
     */
    public GivenWhenThenAssertionError(String message, Throwable cause) {
        super(appendTrace(message), cause);
    }

    /**
     * Constructs an assertion error with a message and expected/actual values to compare.
     * <p>
     * The values are preprocessed and serialized to human-readable form using {@link #formatForComparison(Object)}.
     *
     * @param message  the failure message
     * @param expected the expected value
     * @param actual   the actual value
     */
    public GivenWhenThenAssertionError(String message, Object expected, Object actual) {
        this(message, compare(expected, actual));
    }

    /**
     * Constructs an assertion error with a message, expected/actual values, and a cause.
     * <p>
     * The values are preprocessed and serialized to human-readable form using {@link #formatForComparison(Object)}.
     *
     * @param message  the failure message
     * @param expected the expected value
     * @param actual   the actual value
     * @param cause    the exception that caused this failure
     */
    public GivenWhenThenAssertionError(String message, Object expected, Object actual, Throwable cause) {
        this(message, compare(expected, actual), cause);
    }

    private GivenWhenThenAssertionError(String message, FormattedComparison comparison) {
        super(formatComparisonMessage(message, comparison), comparison.expected(), comparison.actual());
        addSuppressedTraceIfNeeded();
    }

    private GivenWhenThenAssertionError(String message, FormattedComparison comparison, Throwable cause) {
        super(formatComparisonMessage(message, comparison), comparison.expected(), comparison.actual(), cause);
        addSuppressedTraceIfNeeded();
    }

    static void useTrace(Supplier<String> supplier) {
        if (supplier == null) {
            clearTrace();
            return;
        }
        traceSupplier.set(supplier);
    }

    static void clearTrace() {
        traceSupplier.remove();
    }

    static void useJavaCommand(String javaCommand) {
        if (javaCommand == null) {
            javaCommandOverride.remove();
            return;
        }
        javaCommandOverride.set(javaCommand);
    }

    private static String appendTrace(String message) {
        if (message.contains("Test trace:")) {
            return message;
        }
        String trace = currentTrace();
        return trace == null ? message : message + System.lineSeparator() + System.lineSeparator() + trace;
    }

    private static String currentTrace() {
        Supplier<String> supplier = traceSupplier.get();
        if (supplier == null) {
            return null;
        }
        String trace = supplier.get();
        return trace == null || trace.isBlank() ? null : trace;
    }

    private static String formatComparisonMessage(String message, FormattedComparison comparison) {
        String formatted = formatMessage(message, comparison);
        return shouldSuppressTraceInComparisonMessage() ? formatted : appendTrace(formatted);
    }

    private void addSuppressedTraceIfNeeded() {
        if (!shouldSuppressTraceInComparisonMessage()) {
            return;
        }
        String trace = currentTrace();
        if (trace != null) {
            addSuppressed(new TraceDetails(traceWithoutHeader(trace)));
        }
    }

    private static String traceWithoutHeader(String trace) {
        return trace.replaceFirst("^Test trace:", "");
    }

    private static String formatMessage(String message, FormattedComparison comparison) {
        if (!shouldRenderComparisonInMessage()) {
            return message;
        }
        return message + System.lineSeparator() + System.lineSeparator()
               + "expected:" + System.lineSeparator() + renderInMessage(comparison.expected())
               + System.lineSeparator() + System.lineSeparator()
               + "actual:" + System.lineSeparator() + renderInMessage(comparison.actual());
    }

    private static boolean shouldRenderComparisonInMessage() {
        return Boolean.getBoolean(VERBOSE_ASSERTIONS_PROPERTY) && isMavenSurefireRun();
    }

    private static boolean shouldSuppressTraceInComparisonMessage() {
        return isDirectIntellijTestRun();
    }

    private static boolean isMavenSurefireRun() {
        if (isDirectIntellijTestRun()) {
            return false;
        }
        String command = javaCommand();
        return command.contains("org.apache.maven.surefire.booter.")
               || command.contains("surefirebooter")
               || System.getProperty("surefire.real.class.path") != null
               || System.getProperty("surefire.test.class.path") != null;
    }

    private static boolean isDirectIntellijTestRun() {
        return javaCommand().contains("com.intellij.rt.junit.JUnitStarter");
    }

    private static String javaCommand() {
        return javaCommandOverride.get() == null
                ? System.getProperty("sun.java.command", "")
                : javaCommandOverride.get();
    }

    private static String renderInMessage(Object value) {
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return "<empty>";
            }
            StringBuilder builder = new StringBuilder();
            int i = 0;
            for (Object item : collection) {
                if (i > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(renderInMessage(item));
                i++;
            }
            return builder.toString();
        }
        return Objects.toString(value);
    }

    /**
     * Prepares an object for textual comparison by transforming it into a clean and readable form:
     * <ul>
     *   <li>For {@link Message} instances: renders only the payload unless expected metadata is part of a mismatch</li>
     *   <li>For {@link Collection} instances: formats each element individually</li>
     *   <li>For {@link Throwable}: extracts and returns stack trace</li>
     *   <li>For other values: serializes to pretty-printed JSON if possible</li>
     * </ul>
     *
     * @param expectedOrActual the object to format
     * @return a readable representation of the value for use in assertion output
     */
    @SneakyThrows
    private static Object formatForComparison(Object expectedOrActual) {
        return formatForComparison(expectedOrActual, null, true);
    }

    @SneakyThrows
    private static Object formatForComparison(Object expectedOrActual, Object counterpart, boolean expectedSide) {
        if (expectedOrActual instanceof Message message) {
            return formatMessageForComparison(message, counterpart, expectedSide);
        }
        if (expectedOrActual instanceof Collection<?> collection) {
            List<?> counterparts = counterpart instanceof Collection<?> c ? new ArrayList<>(c) : List.of();
            int[] index = new int[1];
            return collection.stream()
                    .map(item -> formatForComparison(item, counterpartAt(counterparts, index), expectedSide))
                    .collect(toList());
        }
        if (expectedOrActual instanceof Throwable) {
            return ExceptionUtils.readStackTrace((Throwable) expectedOrActual);
        }
        if (ResultValidator.matchersSupported) {
            if (expectedOrActual instanceof Matcher<?>) {
                return expectedOrActual;
            }
            if (expectedOrActual instanceof Description) {
                return Objects.toString(expectedOrActual);
            }
        }
        try {
            return expectedOrActual instanceof CharSequence
                    ? expectedOrActual
                    : formatter.writeValueAsString(expectedOrActual).replaceAll("\\\\n", "\n");
        } catch (Exception e) {
            return expectedOrActual;
        }
    }

    private static Object counterpartAt(List<?> counterparts, int[] index) {
        int current = index[0]++;
        return current < counterparts.size() ? counterparts.get(current) : null;
    }

    private static Object formatMessageForComparison(Message message, Object counterpart, boolean expectedSide) {
        Message expectedMessage = expectedSide ? message : counterpart instanceof Message m ? m : null;
        Message actualMessage = expectedSide ? counterpart instanceof Message m ? m : null : message;
        if (shouldRenderEnvelope(expectedMessage, actualMessage)) {
            return new PayloadAndMetadata(message.getPayload(), userMetadata(message));
        }
        Object counterpartPayload = counterpart instanceof Message m ? m.getPayload() : null;
        return formatPayloadForComparison(message.getPayload(), counterpartPayload, expectedSide);
    }

    private static Object formatPayloadForComparison(Object payload, Object counterpart, boolean expectedSide) {
        Object formatted = formatForComparison(payload, counterpart, expectedSide);
        if (payload == null || payload instanceof CharSequence || payload instanceof Number
            || payload instanceof Boolean || payload instanceof Enum<?> || payload instanceof Class<?>) {
            return formatted;
        }
        if (formatted instanceof String text && text.stripLeading().startsWith("{")
            && !text.contains("\"@class\"")) {
            return addPayloadType(text, payload.getClass());
        }
        return formatted;
    }

    private static String addPayloadType(String json, Class<?> payloadType) {
        String trimmed = json.strip();
        if (trimmed.equals("{ }") || trimmed.equals("{}")) {
            return "{%n  \"@class\" : \"%s\"%n}".formatted(payloadType.getName());
        }
        int insertAt = json.indexOf('{') + 1;
        return json.substring(0, insertAt)
               + System.lineSeparator() + "  \"@class\" : \"" + payloadType.getName() + "\","
               + json.substring(insertAt);
    }

    private static boolean shouldRenderEnvelope(Message expected, Message actual) {
        if (expected == null) {
            return false;
        }
        Metadata expectedMetadata = userMetadata(expected);
        if (expectedMetadata.getEntries().isEmpty()) {
            return false;
        }
        return actual == null || !userMetadata(actual).contains(expectedMetadata);
    }

    private static Metadata userMetadata(Message message) {
        return message.getMetadata().withoutIf(key -> key.startsWith("$"));
    }

    private static FormattedComparison compare(Object expected, Object actual) {
        return new FormattedComparison(
                formatForComparison(expected, actual, true),
                formatForComparison(actual, expected, false));
    }

    /**
     * Helper class used to render a message's payload and stripped-down metadata cleanly in assertion output.
     */
    @Value
    private static class PayloadAndMetadata {
        Object payload;
        Metadata metadata;

        @Override
        public String toString() {
            try {
                return formatter.writeValueAsString(this).replaceAll("\\\\n", "\n");
            } catch (Exception e) {
                return "Message{" +
                        "payload=" + payload +
                        ", metadata=" + metadata +
                        '}';
            }
        }
    }

    private record FormattedComparison(Object expected, Object actual) {
    }

    private static class TraceDetails extends AssertionError {
        private TraceDetails(String trace) {
            super(trace);
            setStackTrace(new StackTraceElement[0]);
        }
    }
}
