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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.fluxzero.common.Registration;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records opt-in {@link TestFixtureObservation} data and writes a dev-server test impact index.
 */
@Slf4j
public final class TestFixtureObservationRecorder {

    public static final String ENABLED_PROPERTY = "fluxzero.testImpact.enabled";
    public static final String DIRECTORY_PROPERTY = "fluxzero.testImpact.directory";
    public static final String TEST_IMPACT_FILE = "test-impact.json";

    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ThreadLocal<String> currentTestId = new ThreadLocal<>();
    private static final Map<String, List<TestFixtureObservation>> observationsByTest = new ConcurrentHashMap<>();
    private static final List<TestFixtureObservationSink> sinks = new CopyOnWriteArrayList<>();

    private TestFixtureObservationRecorder() {
    }

    public static Registration registerSink(TestFixtureObservationSink sink) {
        sinks.add(Objects.requireNonNull(sink, "sink may not be null"));
        return () -> sinks.remove(sink);
    }

    public static void testStarted(String testId) {
        currentTestId.set(testId);
        if (isEnabled()) {
            observationsByTest.computeIfAbsent(testId, ignored -> new CopyOnWriteArrayList<>());
        }
    }

    public static void testFinished(String testId) {
        try {
            if (isEnabled() && observationsByTest.containsKey(testId)) {
                writeImpactIndex(testId);
            }
        } finally {
            currentTestId.remove();
        }
    }

    static String currentTestId() {
        return currentTestId.get();
    }

    static void record(String fallbackTestId, TestFixtureObservation observation) {
        String testId = currentTestId.get();
        if (testId == null) {
            testId = fallbackTestId;
        }
        if (testId == null || (!isEnabled() && sinks.isEmpty())) {
            return;
        }
        TestFixtureObservation observed = observation.withTestId(testId);
        sinks.forEach(sink -> sink.observe(observed));
        if (isEnabled()) {
            observationsByTest.computeIfAbsent(testId, ignored -> new CopyOnWriteArrayList<>()).add(observed);
        }
    }

    static void resetForTests() {
        currentTestId.remove();
        observationsByTest.clear();
        sinks.clear();
    }

    private static boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    private static synchronized void writeImpactIndex(String testId) {
        Path directory = impactDirectory();
        Path file = directory.resolve(TEST_IMPACT_FILE);
        try {
            Files.createDirectories(directory);
            ImpactIndex index = readImpactIndex(file);
            Map<String, TestImpact> tests = new TreeMap<>(index.tests());
            tests.put(testId, TestImpact.from(observationsByTest.getOrDefault(testId, List.of())));
            ImpactIndex next = new ImpactIndex(Instant.now().toEpochMilli(), tests);
            Path temp = Files.createTempFile(directory, TEST_IMPACT_FILE, ".tmp");
            objectMapper.writeValue(temp.toFile(), next);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to write Fluxzero test impact index", e);
        }
    }

    private static ImpactIndex readImpactIndex(Path file) {
        if (!Files.isRegularFile(file)) {
            return ImpactIndex.empty();
        }
        try {
            return objectMapper.readValue(file.toFile(), ImpactIndex.class);
        } catch (IOException e) {
            log.warn("Failed to read existing Fluxzero test impact index; replacing it", e);
            return ImpactIndex.empty();
        }
    }

    private static Path impactDirectory() {
        String directory = System.getProperty(DIRECTORY_PROPERTY);
        return directory == null || directory.isBlank()
                ? Path.of(".fluxzero", "dev").toAbsolutePath()
                : Path.of(directory).toAbsolutePath();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImpactIndex(long updatedAt, Map<String, TestImpact> tests) {
        static ImpactIndex empty() {
            return new ImpactIndex(Instant.now().toEpochMilli(), Map.of());
        }

        public ImpactIndex {
            tests = tests == null ? Map.of() : Map.copyOf(tests);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TestImpact(
            Set<String> handlers,
            Set<String> payloads,
            Set<MessageUsage> messages,
            Set<WebUsage> web,
            Set<String> documentCollections,
            Set<String> schedulePayloads
    ) {
        static TestImpact from(List<TestFixtureObservation> observations) {
            TreeSet<String> handlers = new TreeSet<>();
            TreeSet<String> payloads = new TreeSet<>();
            TreeSet<MessageUsage> messages = new TreeSet<>();
            TreeSet<WebUsage> web = new TreeSet<>();
            TreeSet<String> documentCollections = new TreeSet<>();
            TreeSet<String> schedulePayloads = new TreeSet<>();
            for (TestFixtureObservation observation : observations) {
                addIfPresent(payloads, observation.payloadClass());
                addIfPresent(documentCollections, observation.documentCollection());
                addIfPresent(schedulePayloads, observation.schedulePayloadClass());
                if (observation.handlerClass() != null) {
                    handlers.add(observation.handlerClass() + "#" + observation.handlerMethod());
                }
                if (observation.messageType() != null) {
                    messages.add(new MessageUsage(observation.messageType(), observation.topic(),
                                                  observation.payloadClass()));
                }
                if (observation.webMethod() != null || observation.webPath() != null) {
                    web.add(new WebUsage(observation.webMethod(), observation.webPath(),
                                         observation.payloadClass()));
                }
            }
            return new TestImpact(handlers, payloads, messages, web, documentCollections, schedulePayloads);
        }

        public TestImpact {
            handlers = copy(handlers);
            payloads = copy(payloads);
            messages = copy(messages);
            web = copy(web);
            documentCollections = copy(documentCollections);
            schedulePayloads = copy(schedulePayloads);
        }

        private static <T> Set<T> copy(Set<T> input) {
            return input == null ? Set.of() : Set.copyOf(input);
        }

        private static void addIfPresent(Set<String> values, String value) {
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageUsage(String type, String topic, String payloadClass) implements Comparable<MessageUsage> {
        @Override
        public int compareTo(MessageUsage other) {
            return List.of(nullToEmpty(type), nullToEmpty(topic), nullToEmpty(payloadClass))
                    .toString().compareTo(List.of(nullToEmpty(other.type), nullToEmpty(other.topic),
                                                  nullToEmpty(other.payloadClass)).toString());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WebUsage(String method, String path, String payloadClass) implements Comparable<WebUsage> {
        @Override
        public int compareTo(WebUsage other) {
            return List.of(nullToEmpty(method), nullToEmpty(path), nullToEmpty(payloadClass))
                    .toString().compareTo(List.of(nullToEmpty(other.method), nullToEmpty(other.path),
                                                  nullToEmpty(other.payloadClass)).toString());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
