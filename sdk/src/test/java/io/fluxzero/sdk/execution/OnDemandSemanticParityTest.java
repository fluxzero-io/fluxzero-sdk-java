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

package io.fluxzero.sdk.execution;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OnDemandSemanticParityTest {

    @Test
    void coreFlowsAndInterceptorsMatchNormalHandlers(@TempDir Path tempDir) throws Exception {
        assertNormalAndOnDemand(tempDir, (fixture, commandInterceptions) -> {
            fixture.whenCommand(new ParityCommand("one"))
                    .expectResult(new ParityResult("command:one"))
                    .expectNoErrors();
            assertEquals(1, commandInterceptions.get());

            fixture.whenQuery(new ParityQuery("two"))
                    .expectResult(new ParityResult("query:two"))
                    .expectNoErrors();

            fixture.whenEvent(new ParityEvent("three"))
                    .expectEvents(new ParityFollowUpEvent("event:three"))
                    .expectNoResult()
                    .expectNoErrors();

            fixture.whenGet("/parity/42")
                    .expectResult("web:42")
                    .expectNoErrors();

            assertEquals(1, commandInterceptions.get());
        });
    }

    @Test
    void validationAndErrorHandlingMatchNormalHandlers(@TempDir Path tempDir) throws Exception {
        assertNormalAndOnDemand(tempDir, (fixture, ignored) -> {
            fixture.whenCommand(new ParityValidatedCommand(null))
                    .expectExceptionalResult(ValidationException.class);

            fixture.whenCommand(new ParityFailingCommand("boom"))
                    .expectExceptionalResult(IllegalStateException.class);
        });
    }

    private static void assertNormalAndOnDemand(Path tempDir, FixtureAssertion assertion) throws Exception {
        runNormal(assertion);
        runOnDemand(tempDir, assertion);
    }

    private static void runNormal(FixtureAssertion assertion) throws Exception {
        AtomicInteger commandInterceptions = new AtomicInteger();
        TestFixture fixture = TestFixture.create(builder(commandInterceptions), new NormalParityHandler());
        try {
            assertion.accept(fixture, commandInterceptions);
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static void runOnDemand(Path tempDir, FixtureAssertion assertion) throws Exception {
        writeParitySource(tempDir);
        AtomicInteger commandInterceptions = new AtomicInteger();
        try (OnDemandExecution execution = OnDemandExecution.builder()
                .sourceRoot(tempDir)
                .cacheRoot(tempDir.resolve("cache"))
                .cacheTtl(Duration.ofMinutes(10))
                .startTracking(false)
                .build()) {
            TestFixture fixture = TestFixture.create(builder(commandInterceptions), fluxzero -> {
                execution.registerWith(fluxzero);
                return List.of();
            });
            try {
                assertion.accept(fixture, commandInterceptions);
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }
    }

    private static FluxzeroBuilder builder(AtomicInteger commandInterceptions) {
        return DefaultFluxzero.builder()
                .addHandlerInterceptor((next, invoker) -> message -> {
                    commandInterceptions.incrementAndGet();
                    return next.apply(message);
                }, MessageType.COMMAND);
    }

    private static void writeParitySource(Path sourceRoot) throws Exception {
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("SourceParityHandler.java"), """
                package io.fluxzero.sdk.execution.generated;

                import io.fluxzero.sdk.Fluxzero;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityCommand;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityEvent;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityFailingCommand;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityFollowUpEvent;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityQuery;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityResult;
                import io.fluxzero.sdk.execution.OnDemandSemanticParityTest.ParityValidatedCommand;
                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleEvent;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;
                import io.fluxzero.sdk.tracking.handling.LocalHandler;
                import io.fluxzero.sdk.web.HandleGet;
                import io.fluxzero.sdk.web.PathParam;

                @LocalHandler
                public class SourceParityHandler {
                    @HandleCommand
                    public ParityResult handle(ParityCommand command) {
                        return new ParityResult("command:" + command.value());
                    }

                    @HandleCommand
                    public ParityResult handle(ParityValidatedCommand command) {
                        return new ParityResult("validated:" + command.value());
                    }

                    @HandleCommand
                    public String handle(ParityFailingCommand command) {
                        throw new IllegalStateException(command.value());
                    }

                    @HandleQuery
                    public ParityResult handle(ParityQuery query) {
                        return new ParityResult("query:" + query.value());
                    }

                    @HandleEvent
                    public void on(ParityEvent event) {
                        Fluxzero.publishEvent(new ParityFollowUpEvent("event:" + event.value()));
                    }

                    @HandleGet("/parity/{id}")
                    public String get(@PathParam("id") String id) {
                        return "web:" + id;
                    }
                }
                """);
    }

    public record ParityCommand(String value) {
    }

    public record ParityValidatedCommand(@NotNull String value) {
    }

    public record ParityFailingCommand(String value) {
    }

    public record ParityQuery(String value) {
    }

    public record ParityEvent(String value) {
    }

    public record ParityFollowUpEvent(String value) {
    }

    public record ParityResult(String value) {
    }

    @FunctionalInterface
    private interface FixtureAssertion {
        void accept(TestFixture fixture, AtomicInteger commandInterceptions) throws Exception;
    }
}
