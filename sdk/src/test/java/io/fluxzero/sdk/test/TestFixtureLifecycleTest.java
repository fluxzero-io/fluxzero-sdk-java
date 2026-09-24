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

package io.fluxzero.sdk.test;

import io.fluxzero.common.TestTask;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.DefaultRequestHandler;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixtureLifecycleTest {

    private static final String CLEANUP_FAILURE_PROBE = "fluxzero.test.cleanupFailureProbe";

    private final TestFixtureExecutionListener listener = new TestFixtureExecutionListener();

    @Test
    void cleanupFailureFailsTheOwningJupiterTestAndStillClosesItsOtherFixtures() {
        CleanupFailureProbe.siblingClosed.set(false);
        var launcher = LauncherFactory.create(LauncherConfig.builder()
                .enableTestExecutionListenerAutoRegistration(false).build());
        var summary = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(new TestFixtureExecutionListener() {
            @Override public void testPlanExecutionFinished(TestPlan plan) {
                // This nested launcher must not close fixtures owned by the surrounding suite.
            }
        }, summary);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(CleanupFailureProbe.class))
                .configurationParameter(CLEANUP_FAILURE_PROBE, "true")
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build());
        assertEquals(1, summary.getSummary().getTestsFailedCount());
        assertTrue(summary.getSummary().getFailures().getFirst().getException().getMessage()
                .contains("Test fixture cleanup did not complete successfully"));
        assertTrue(CleanupFailureProbe.siblingClosed.get());
    }

    // Whole-package IDE runs also discover static member classes. This intentionally failing test belongs only to
    // the nested launcher, whose configuration is isolated from concurrent tests and the surrounding test plan.
    @ExtendWith(CleanupFailureProbeCondition.class)
    static class CleanupFailureProbe {
        static final AtomicBoolean siblingClosed = new AtomicBoolean();

        @Test
        void bodySucceedsButCleanupFails() {
            Fluxzero failing = mock(Fluxzero.class, CALLS_REAL_METHODS);
            doAnswer(invocation -> { throw new IllegalStateException("cleanup failure"); }).when(failing).close(true);
            Fluxzero sibling = mock(Fluxzero.class, CALLS_REAL_METHODS);
            doAnswer(invocation -> { siblingClosed.set(true); return null; }).when(sibling).close(true);
            for (Fluxzero fluxzero : new Fluxzero[]{failing, sibling}) {
                TestFixture fixture = mock(TestFixture.class);
                when(fixture.getFluxzero()).thenReturn(fluxzero);
                doAnswer(invocation -> { fluxzero.execute(fc -> fc.close(true)); return null; })
                        .when(fixture).closeAfterTest();
                TestFixtureLifecycle.register(fixture);
            }
        }
    }

    private static class CleanupFailureProbeCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return context.getConfigurationParameter(CLEANUP_FAILURE_PROBE).map(Boolean::parseBoolean).orElse(false)
                    ? ConditionEvaluationResult.enabled("Explicit cleanup failure probe")
                    : ConditionEvaluationResult.disabled("Executed by the cleanup lifecycle test's nested launcher");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupCompletesAbandonedRequestsWithoutAResponseGracePeriod(boolean asynchronous) throws Exception {
        TestFixture fixture = (asynchronous ? TestFixture.createAsync() : TestFixture.create())
                .resultTimeout(Duration.ofMillis(25));
        Fluxzero fc = fixture.getFluxzero();
        List<CompletableFuture<?>> results = List.of(
                fc.commandGateway().send("unhandled command"),
                fc.queryGateway().send("unhandled query"),
                fc.customGateway("cleanup-topic").send("unhandled custom request"),
                fc.webRequestGateway().send(WebRequest.get("/unhandled").build()),
                fc.commandGateway().forNamespace("other").send("unhandled namespaced command"),
                fc.webRequestGateway().forNamespace("other").send(WebRequest.get("/unhandled").build()));
        fixture.whenApplying(ignored -> results.getFirst())
                .expectExceptionalResult(java.util.concurrent.TimeoutException.class);
        results.forEach(result -> assertFalse(result.isDone()));
        try (var closer = new TestTask(fixture::closeAfterTest, () -> {})) {
            // The old gateway + request-handler grace periods take at least four seconds. Resource closure itself
            // takes milliseconds and remains part of this operation; no request timeout or wire metadata is changed.
            closer.awaitCompletion(Duration.ofSeconds(1));
        }
        results.forEach(result -> assertTrue(result.isCompletedExceptionally()));
    }

    @Test
    void fixtureShutdownGraceUsesItsOwnPropertySourceAndPreservesExplicitOverrides() {
        TestFixture configured = TestFixture.create(DefaultFluxzero.builder().replacePropertySource(
                ignored -> name -> DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY.equals(name) ? "150" : null));
        TestFixture defaults = TestFixture.create();
        assertEquals("150", configured.getFluxzero().propertySource().get(DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY));
        assertNull(defaults.getFluxzero().propertySource().get(DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY));
        assertEquals("150", configured.withProperty("unrelated", "value").getFluxzero()
                .propertySource().get(DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY));
        assertThrows(IllegalArgumentException.class, () -> DefaultFluxzero.builder().replacePropertySource(
                ignored -> name -> DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY.equals(name) ? "-1" : null).build(LocalClient.newInstance()));
    }

    @Test
    void lazyCustomGatewayRetainsItsApplicationsShutdownConfigurationWhenBuilderIsReused() throws Exception {
        var builder = DefaultFluxzero.builder().replacePropertySource(
                ignored -> name -> DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY.equals(name) ? "0" : null);
        try (var first = builder.build(LocalClient.newInstance())) {
            builder.replacePropertySource(
                    ignored -> name -> DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY.equals(name) ? "2000" : null);
            try (var second = builder.build(LocalClient.newInstance())) {
                var response = first.customGateway("created-after-second-build").forNamespace("other").send("unhandled");
                try (var closer = new TestTask(first::close, () -> {})) {
                    closer.awaitCompletion(Duration.ofSeconds(1));
                }
                assertTrue(response.isCompletedExceptionally());
                assertEquals("2000", second.propertySource().get(DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY));
            }
        }
    }

    @Test
    void automaticCleanupPreservesAnExplicitResponseGracePeriod() throws Exception {
        var result = new CompletableFuture<String>();
        TestFixture fixture = TestFixture.create().withProperty(DefaultRequestHandler.SHUTDOWN_TIMEOUT_PROPERTY, "2000")
                .registerHandlers(new Object() {
                    @io.fluxzero.sdk.tracking.handling.HandleCommand
                    CompletableFuture<String> handle(String command) { return result; }
                });
        var response = fixture.getFluxzero().commandGateway().send("handled");
        try (var closer = new TestTask(fixture::closeAfterTest, () -> result.complete("completed"))) {
            closer.awaitBlockedIn(DefaultFluxzero.class, "close", Duration.ofSeconds(5));
            result.complete("completed");
            closer.awaitCompletion(Duration.ofSeconds(1));
        }
        assertEquals("completed", response.join());
    }

    @Test
    void finishingScopeWaitsForCompleteCleanupWithoutSerializingIndependentFixtures() throws Exception {
        String scope = "blocked-cleanup";
        var firstEntered = new CountDownLatch(1);
        var secondClosed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstClosed = new AtomicBoolean();
        TestFixtureLifecycle.startScope(scope, true);
        Fluxzero first = mock(Fluxzero.class, CALLS_REAL_METHODS);
        doAnswer(invocation -> {
            assertSame(first, Fluxzero.get());
            firstEntered.countDown();
            assertTrue(release.await(5, SECONDS));
            firstClosed.set(true);
            return null;
        }).when(first).close(true);
        Fluxzero second = mock(Fluxzero.class, CALLS_REAL_METHODS);
        doAnswer(invocation -> {
            secondClosed.countDown();
            return null;
        }).when(second).close(true);
        for (Fluxzero fluxzero : new Fluxzero[]{first, second}) {
            TestFixture fixture = mock(TestFixture.class);
            when(fixture.getFluxzero()).thenReturn(fluxzero);
            doAnswer(invocation -> { fluxzero.execute(fc -> fc.close(true)); return null; })
                    .when(fixture).closeAfterTest();
            TestFixtureLifecycle.register(fixture);
        }
        try (var finisher = new TestTask(() -> TestFixtureLifecycle.finishScope(scope), release::countDown)) {
            assertTrue(firstEntered.await(5, SECONDS));
            assertTrue(secondClosed.await(5, SECONDS), "Independent fixtures must close concurrently");
            finisher.awaitBlockedIn(TestFixtureLifecycle.class, "closeFixtures", Duration.ofSeconds(5));
            release.countDown();
            finisher.awaitCompletion(Duration.ofSeconds(5));
        }
        assertTrue(firstClosed.get());
    }

    @Test
    void finishingNestedExecutionOnlyClosesNestedFixtures() {
        TestIdentifier outerExecution = execution("outer", true);
        TestIdentifier innerExecution = execution("inner", false);

        listener.executionStarted(outerExecution);
        TestFixture outer = TestFixture.createAsync();
        AtomicBoolean outerClosed = observeClose(outer);
        listener.executionStarted(innerExecution);
        TestFixture inner = TestFixture.create();
        AtomicBoolean innerClosed = observeClose(inner);

        listener.executionFinished(innerExecution, TestExecutionResult.successful());

        assertTrue(innerClosed.get());
        assertFalse(outerClosed.get());
        assertSame(outer.getFluxzero(), Fluxzero.get());

        listener.executionFinished(outerExecution, TestExecutionResult.successful());
        assertTrue(outerClosed.get());
    }

    @Test
    void finishingOnAnotherThreadDoesNotLoseParentScope() throws Exception {
        TestIdentifier outerExecution = execution("cross-thread-outer", true);
        TestIdentifier innerExecution = execution("cross-thread-inner", false);

        listener.executionStarted(outerExecution);
        TestFixture outer = TestFixture.createAsync();
        AtomicBoolean outerClosed = observeClose(outer);
        listener.executionStarted(innerExecution);
        TestFixture inner = TestFixture.create();
        AtomicBoolean innerClosed = observeClose(inner);

        finishOnWorker(innerExecution);
        TestFixture secondOuter = TestFixture.createAsync();
        AtomicBoolean secondOuterClosed = observeClose(secondOuter);

        assertTrue(innerClosed.get());
        assertFalse(outerClosed.get());
        assertFalse(secondOuterClosed.get());

        finishOnWorker(outerExecution);
        assertTrue(outerClosed.get());
        assertTrue(secondOuterClosed.get());

        TestIdentifier nextExecution = execution("cross-thread-next", false);
        listener.executionStarted(nextExecution);
        assertNull(Fluxzero.instance.get());
        listener.executionFinished(nextExecution, TestExecutionResult.successful());
    }

    @Test
    void testExecutionAdoptsFixturesCreatedWhileConstructingTestInstance() {
        TestIdentifier containerExecution = execution("container", false);
        TestIdentifier testExecution = execution("test", true);

        listener.executionStarted(containerExecution);
        TestFixture fixture = TestFixture.create();
        AtomicBoolean closed = observeClose(fixture);
        listener.executionStarted(testExecution);

        listener.executionFinished(testExecution, TestExecutionResult.successful());

        assertTrue(closed.get());
        listener.executionFinished(containerExecution, TestExecutionResult.successful());
    }

    private static TestIdentifier execution(String uniqueId, boolean test) {
        TestIdentifier result = mock(TestIdentifier.class);
        when(result.getUniqueId()).thenReturn(uniqueId);
        when(result.isTest()).thenReturn(test);
        return result;
    }

    private void finishOnWorker(TestIdentifier execution) throws Exception {
        try (var executor = newWorkerPool("fixture-lifecycle-test", 1)) {
            executor.submit(() -> listener.executionFinished(execution, TestExecutionResult.successful()))
                    .get(5, SECONDS);
        }
    }

    private static AtomicBoolean observeClose(TestFixture fixture) {
        var closed = new AtomicBoolean();
        fixture.getFluxzero().beforeShutdown(() -> closed.set(true));
        return closed;
    }
}
