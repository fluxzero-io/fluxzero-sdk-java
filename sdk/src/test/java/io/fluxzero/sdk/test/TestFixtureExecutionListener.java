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

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * A JUnit 5 {@link TestExecutionListener} that shuts down {@link TestFixture} instances when their owning execution
 * scope finishes.
 * <p>
 * This ensures that Fluxzero resources such as schedulers, threads, or stateful components are properly
 * released between test executions.
 * <p>
 * This listener is useful in test environments that rely on side-effectful or stateful behaviors (e.g. schedules,
 * in-memory gateways, spies), especially when running multiple tests within the same JVM.
 *
 * <p><strong>Usage:</strong> Register this listener in your {@code junit-platform.properties} file:
 * <pre>{@code
 * junit.platform.listeners.default = io.fluxzero.sdk.test.TestFixtureExecutionListener
 * }</pre>
 *
 * @see TestFixture
 */
public class TestFixtureExecutionListener implements TestExecutionListener {

    /**
     * Opens a fixture scope for a test or container execution.
     */
    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        TestFixtureLifecycle.startScope(testIdentifier.getUniqueId(), testIdentifier.isTest());
    }

    /**
     * Closes only fixtures created by the completed test or container execution.
     */
    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        TestFixtureLifecycle.finishScope(testIdentifier.getUniqueId());
    }

    /**
     * Defensively closes fixtures left behind by incomplete or non-standard engine executions.
     */
    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        TestFixtureLifecycle.shutDownAllActiveFixtures();
    }
}
