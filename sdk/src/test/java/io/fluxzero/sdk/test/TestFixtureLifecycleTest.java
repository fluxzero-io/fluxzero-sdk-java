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

import io.fluxzero.sdk.Fluxzero;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestIdentifier;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixtureLifecycleTest {

    private final TestFixtureExecutionListener listener = new TestFixtureExecutionListener();

    @Test
    void finishingNestedExecutionOnlyClosesNestedFixtures() throws InterruptedException {
        TestIdentifier outerExecution = execution("outer", true);
        TestIdentifier innerExecution = execution("inner", false);

        listener.executionStarted(outerExecution);
        TestFixture outer = TestFixture.createAsync();
        listener.executionStarted(innerExecution);
        TestFixture inner = TestFixture.create();

        listener.executionFinished(innerExecution, TestExecutionResult.successful());

        assertTrue(closesWithin(inner, Duration.ofSeconds(1)));
        assertFalse(closesWithin(outer, Duration.ofMillis(200)));
        assertSame(outer.getFluxzero(), Fluxzero.get());

        listener.executionFinished(outerExecution, TestExecutionResult.successful());
        assertTrue(closesWithin(outer, Duration.ofSeconds(1)));
    }

    @Test
    void finishingOnAnotherThreadDoesNotLoseParentScope() throws InterruptedException {
        TestIdentifier outerExecution = execution("cross-thread-outer", true);
        TestIdentifier innerExecution = execution("cross-thread-inner", false);

        listener.executionStarted(outerExecution);
        TestFixture outer = TestFixture.createAsync();
        listener.executionStarted(innerExecution);
        TestFixture inner = TestFixture.create();

        CompletableFuture.runAsync(
                () -> listener.executionFinished(innerExecution, TestExecutionResult.successful())).join();
        TestFixture secondOuter = TestFixture.createAsync();

        assertTrue(closesWithin(inner, Duration.ofSeconds(1)));
        assertFalse(closesWithin(outer, Duration.ofMillis(200)));
        assertFalse(closesWithin(secondOuter, Duration.ofMillis(200)));

        CompletableFuture.runAsync(
                () -> listener.executionFinished(outerExecution, TestExecutionResult.successful())).join();
        assertTrue(closesWithin(outer, Duration.ofSeconds(1)));
        assertTrue(closesWithin(secondOuter, Duration.ofSeconds(1)));

        TestIdentifier nextExecution = execution("cross-thread-next", false);
        listener.executionStarted(nextExecution);
        assertNull(Fluxzero.instance.get());
        listener.executionFinished(nextExecution, TestExecutionResult.successful());
    }

    @Test
    void testExecutionAdoptsFixturesCreatedWhileConstructingTestInstance() throws InterruptedException {
        TestIdentifier containerExecution = execution("container", false);
        TestIdentifier testExecution = execution("test", true);

        listener.executionStarted(containerExecution);
        TestFixture fixture = TestFixture.create();
        listener.executionStarted(testExecution);

        listener.executionFinished(testExecution, TestExecutionResult.successful());

        assertTrue(closesWithin(fixture, Duration.ofSeconds(1)));
        listener.executionFinished(containerExecution, TestExecutionResult.successful());
    }

    private static TestIdentifier execution(String uniqueId, boolean test) {
        TestIdentifier result = mock(TestIdentifier.class);
        when(result.getUniqueId()).thenReturn(uniqueId);
        when(result.isTest()).thenReturn(test);
        return result;
    }

    private static boolean closesWithin(TestFixture fixture, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (closed(fixture)) {
                return true;
            }
            Thread.sleep(10);
        }
        return closed(fixture);
    }

    private static boolean closed(TestFixture fixture) {
        try {
            var closed = fixture.getFluxzero().getClass().getDeclaredField("closed");
            closed.setAccessible(true);
            return ((AtomicBoolean) closed.get(fixture.getFluxzero())).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
