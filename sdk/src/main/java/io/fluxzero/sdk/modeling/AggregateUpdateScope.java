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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.tracking.handling.Invocation;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Groups the checks and automatic commits of a direct Aggregate update without creating a message handler. */
final class AggregateUpdateScope {
    private static final ThreadLocal<AggregateUpdateScope> current = new ThreadLocal<>();

    private final List<Runnable> checks = new ArrayList<>();
    private final List<ModifiableAggregateRoot<?>> roots = new ArrayList<>();

    private AggregateUpdateScope() {
    }

    static <T> T run(Supplier<T> update) {
        if (current.get() != null) {
            return update.get();
        }
        var scope = new AggregateUpdateScope();
        T result;
        try {
            current.set(scope);
            try {
                result = update.get();
            } finally {
                current.remove();
            }
            scope.checks.forEach(Runnable::run);
        } catch (Throwable failure) {
            scope.complete(failure);
            throw failure;
        }
        scope.complete(null);
        return result;
    }

    static void validate(Runnable check) {
        AggregateUpdateScope scope = current.get();
        if (scope != null && Invocation.getCurrent() == null) {
            scope.checks.add(check);
        } else {
            Invocation.validateOnHandlerCompletion(check);
        }
    }

    static boolean deferCommit(ModifiableAggregateRoot<?> root) {
        AggregateUpdateScope scope = current.get();
        if (scope == null || Invocation.getCurrent() != null) {
            return false;
        }
        scope.roots.add(root);
        return true;
    }

    private void complete(Throwable error) {
        AsyncCompletionScope.runAndAwait(() -> completeRoots(error));
    }

    @SneakyThrows
    private void completeRoots(Throwable failure) {
        for (var root : roots) {
            try {
                root.whenHandlerCompletes(failure);
            } catch (Throwable e) {
                // Later roots must discard their staged updates if an earlier commit fails.
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
