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
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static java.util.concurrent.TimeUnit.SECONDS;
import static io.fluxzero.common.ObjectUtils.newWorkerPool;

class TestFixtureLifecycleTest {

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
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build());
        assertEquals(1, summary.getSummary().getTestsFailedCount());
        assertTrue(summary.getSummary().getFailures().getFirst().getException().getMessage()
                .contains("Test fixture cleanup did not complete successfully"));
        assertTrue(CleanupFailureProbe.siblingClosed.get());
    }

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
                TestFixtureLifecycle.register(fixture);
            }
        }
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
