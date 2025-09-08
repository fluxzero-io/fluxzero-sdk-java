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

package io.fluxzero.javaclient.tracking;

import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.javaclient.Fluxzero;
import lombok.AllArgsConstructor;

import java.util.function.Consumer;

/**
 * A {@link BatchInterceptor} that ensures the correct {@link Fluxzero} instance is bound to the current thread for
 * the duration of a {@link MessageBatch}.
 * <p>
 * This interceptor enables the use of {@link Fluxzero}'s static convenience methods during message processing. It
 * ensures that operations like {@link Fluxzero#sendCommand(Object)} or {@link Fluxzero#publishEvent(Object)}
 * resolve the correct runtime context even in multithreaded applications.
 *
 * <h2>Thread-Local Binding</h2>
 * <ul>
 *   <li>Sets {@link Fluxzero#instance} before batch processing begins.</li>
 *   <li>Restores the previous thread-local instance (if any) after processing completes.</li>
 *   <li>Ensures thread isolation across different trackers and test threads.</li>
 * </ul>
 *
 * <h2>Runtime Expectations</h2>
 * <ul>
 *   <li>In a typical production application, there is a single {@code Fluxzero} instance used throughout the JVM.</li>
 *   <li>This instance is often registered via {@link Fluxzero#applicationInstance} and used as a fallback
 *       when no thread-local binding is available.</li>
 *   <li>Multiple instances may occur in rare cases—such as connecting to multiple Fluxzero Runtimes—or during
 *       unit/integration testing scenarios involving multiple test clients or parallel test execution.</li>
 *   <li>This interceptor ensures that each batch is processed with the appropriate context, even in such edge cases.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>This interceptor is registered automatically in most Fluxzero configurations.</li>
 *   <li>In custom or manual configurations (e.g., test setups), it may need to be included explicitly.</li>
 * </ul>
 *
 * @see Fluxzero#instance
 * @see Fluxzero#applicationInstance
 * @see MessageBatch
 */
@AllArgsConstructor
public class FluxzeroInterceptor implements BatchInterceptor {
    private final Fluxzero fluxzero;

    @Override
    public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
        return batch -> {
            Fluxzero.instance.set(fluxzero);
            try {
                consumer.accept(batch);
            } finally {
                Fluxzero.instance.remove();
            }
        };
    }
}
