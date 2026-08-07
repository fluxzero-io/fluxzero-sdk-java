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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.common.ThreadLocalContext;

import java.lang.reflect.Parameter;
import java.util.concurrent.Callable;

/** Message-invocation scope for supplying one graph-change root to an ordinary prepared handler invoker. */
final class GraphChangeInvocation {
    private static final ThreadLocal<Value> current = ThreadLocalContext.create();

    private GraphChangeInvocation() {
    }

    static Graph<?> graph(Parameter parameter) {
        Value value = current.get();
        return value != null && value.parameter().equals(parameter)
                ? value.graph() : null;
    }

    static boolean supplies(Parameter parameter) {
        Value value = current.get();
        return value != null && value.parameter().equals(parameter);
    }

    static <T> T call(Parameter parameter, Graph<?> graph, Callable<T> task) throws Exception {
        Value previous = current.get();
        current.set(new Value(parameter, graph));
        try {
            return task.call();
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    private record Value(Parameter parameter, Graph<?> graph) {
    }
}
