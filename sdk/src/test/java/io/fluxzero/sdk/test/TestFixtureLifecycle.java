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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.tryCatch;

/**
 * Owns {@link TestFixture} instances per JUnit execution scope and closes them without affecting parent or sibling
 * executions.
 */
final class TestFixtureLifecycle {
    private static final ThreadLocal<Deque<FixtureScope>> currentScopes =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<String, FixtureScope> activeScopes = new ConcurrentHashMap<>();
    private static final Map<Thread, Set<TestFixture>> unscopedFixtures = new ConcurrentHashMap<>();
    private static final Set<Fluxzero> closedFixtureInstances =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Executor shutdownExecutor = newWorkerPool("TestFixture-shutdown", 64);

    private TestFixtureLifecycle() {
    }

    static void startScope(String executionId, boolean test) {
        Deque<FixtureScope> scopes = currentScopes.get();
        scopes.removeIf(FixtureScope::isClosed);
        reconcileCurrentFluxzero(scopes);
        FixtureScope newScope = new FixtureScope(test);
        FixtureScope scope = activeScopes.putIfAbsent(executionId, newScope);
        scope = scope == null ? newScope : scope;
        if (test) {
            // Jupiter constructs test instances before reporting the leaf test as started. Transfer fixtures created by
            // field initializers from their temporary container scopes, while preserving fixtures owned by outer tests.
            FixtureScope testScope = scope;
            scopes.stream().filter(candidate -> !candidate.isTest())
                    .forEach(candidate -> testScope.registerAll(candidate.drain()));
        }
        scopes.push(scope);
    }

    static void finishScope(String executionId) {
        FixtureScope scope = activeScopes.remove(executionId);
        if (scope == null) {
            return;
        }
        Deque<FixtureScope> scopes = currentScopes.get();
        scopes.remove(scope);
        List<TestFixture> fixtures = scope.close();
        scopes.removeIf(FixtureScope::isClosed);
        closeFixtures(fixtures);
        reconcileCurrentFluxzero(scopes);
        if (scopes.isEmpty()) {
            currentScopes.remove();
        }
    }

    static void register(TestFixture fixture) {
        Deque<FixtureScope> scopes = currentScopes.get();
        scopes.removeIf(FixtureScope::isClosed);
        for (FixtureScope scope : scopes) {
            if (scope.register(fixture)) {
                return;
            }
        }
        if (scopes.isEmpty()) {
            currentScopes.remove();
        }
        unscopedFixtures.computeIfAbsent(Thread.currentThread(), ignored -> ConcurrentHashMap.newKeySet()).add(fixture);
    }

    static void shutDownActiveFixtures() {
        Set<TestFixture> fixtures = new LinkedHashSet<>();
        Deque<FixtureScope> scopes = currentScopes.get();
        scopes.removeIf(FixtureScope::isClosed);
        scopes.forEach(scope -> fixtures.addAll(scope.drain()));
        fixtures.addAll(Optional.ofNullable(unscopedFixtures.remove(Thread.currentThread())).orElseGet(Set::of));
        closeFixtures(fixtures);
        if (scopes.isEmpty()) {
            currentScopes.remove();
        }
    }

    static void shutDownAllActiveFixtures() {
        Set<TestFixture> fixtures = new LinkedHashSet<>();
        activeScopes.values().forEach(scope -> fixtures.addAll(scope.close()));
        activeScopes.clear();
        unscopedFixtures.values().forEach(fixtures::addAll);
        unscopedFixtures.clear();
        closeFixtures(fixtures);
        currentScopes.remove();
    }

    private static void closeFixtures(Collection<TestFixture> fixtures) {
        if (fixtures.isEmpty()) {
            return;
        }
        fixtures.forEach(fixture -> closedFixtureInstances.add(fixture.getFluxzero()));
        fixtures.forEach(fixture -> shutdownExecutor.execute(
                tryCatch(() -> fixture.getFluxzero().execute(fc -> fixture.getFluxzero().close(true)))));
        reconcileCurrentFluxzero(currentScopes.get());
        GivenWhenThenAssertionError.clearTrace();
    }

    private static void reconcileCurrentFluxzero(Deque<FixtureScope> scopes) {
        Fluxzero current = Fluxzero.instance.get();
        if (current == null || !closedFixtureInstances.contains(current)) {
            return;
        }
        scopes.stream().map(FixtureScope::lastFixture).flatMap(Optional::stream)
                .findFirst().ifPresentOrElse(fixture -> Fluxzero.instance.set(fixture.getFluxzero()),
                                             Fluxzero.instance::remove);
    }

    private static final class FixtureScope {
        private final boolean test;
        private final List<TestFixture> fixtures = new ArrayList<>();
        private volatile boolean closed;

        private FixtureScope(boolean test) {
            this.test = test;
        }

        synchronized boolean register(TestFixture fixture) {
            if (closed) {
                return false;
            }
            fixtures.add(fixture);
            return true;
        }

        synchronized void registerAll(Collection<TestFixture> fixtures) {
            if (!closed) {
                this.fixtures.addAll(fixtures);
            }
        }

        synchronized List<TestFixture> drain() {
            List<TestFixture> result = List.copyOf(fixtures);
            fixtures.clear();
            return result;
        }

        synchronized List<TestFixture> close() {
            closed = true;
            return drain();
        }

        synchronized Optional<TestFixture> lastFixture() {
            return closed || fixtures.isEmpty() ? Optional.empty() : Optional.of(fixtures.getLast());
        }

        boolean isClosed() {
            return closed;
        }

        boolean isTest() {
            return test;
        }
    }
}
