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

package io.fluxzero.sdk.test;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestIdentifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class TestFixtureLifecycleTest {

    @Test
    void finishingNestedExecutionDoesNotShutDownOuterFixtureOnSameWorkerThread() throws InterruptedException {
        TestFixtureExecutionListener listener = new TestFixtureExecutionListener();
        TestIdentifier test = mock(TestIdentifier.class);

        listener.executionStarted(test);
        TestFixture outer = TestFixture.createAsync();

        listener.executionStarted(test);
        TestFixture.create();
        listener.executionFinished(test, TestExecutionResult.successful());

        assertFalse(closesWithin(outer, Duration.ofSeconds(1)));
    }

    private static boolean closesWithin(TestFixture fixture, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (closed(fixture)) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
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
