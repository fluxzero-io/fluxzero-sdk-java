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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Intercepts and decorates batch-level message handling for a {@link Tracker}.
 * <p>
 * A {@code BatchInterceptor} wraps the execution of a {@code Consumer<MessageBatch>}—typically invoked by a tracker to
 * process a group of messages polled from the message log. Interceptors can be used to inject common behavior such as
 * logging, metrics, retries, transaction boundaries, or diagnostics at the batch level.
 * </p>
 *
 * <p>
 * Implementations can also be registered via Java's {@link ServiceLoader}. Service-loaded interceptors are picked up
 * automatically by Fluxzero, including when using the {@code TestFixture}, and are ordered using {@link
 * io.fluxzero.sdk.common.Order @Order}.
 * </p>
 *
 * <h2>Usage</h2>
 * <p>
 * Interceptors are applied during consumer configuration via the
 * {@link io.fluxzero.sdk.tracking.Consumer#batchInterceptors()} attribute, or programmatically. They are
 * composed in a chain using {@link #andThen(BatchInterceptor)} or {@link #join(List)}.
 * </p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class LoggingBatchInterceptor implements BatchInterceptor {
 *     @Override
 *     public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
 *         return batch -> {
 *             log.info("Processing batch of {} messages", batch.size());
 *             consumer.accept(batch);
 *             log.info("Finished processing batch");
 *         };
 *     }
 * }
 * }</pre>
 *
 * @see HandlerInterceptor
 * @see io.fluxzero.sdk.tracking.Consumer#batchInterceptors()
 */
@FunctionalInterface
public interface BatchInterceptor {

    /**
     * Default batch interceptors discovered via Java's service loader, sorted by
     * {@link io.fluxzero.sdk.common.Order}. These interceptors are applied automatically by Fluxzero.
     */
    List<BatchInterceptor> defaultInterceptors = ClientUtils.loadServices(BatchInterceptor.class);

    /**
     * Returns a no-op interceptor that does not alter the consumer behavior.
     */
    static BatchInterceptor noOp() {
        return preservingConsumerFilter((c, t) -> c);
    }

    /**
     * Describes serialized inputs needed by this interceptor and its downstream consumer. The default is
     * unrestricted because interceptors can transform messages or require inputs that no handler accepts.
     * Implementations may return {@code downstream} only when excluded inputs are irrelevant to their behavior and
     * they never transform an excluded input into one the downstream filter admits. Custom inverse projections
     * describe the input side of a transformation; this method runs after serializer/upcaster projection.
     */
    default ConsumerFilter describeConsumerFilter(ConsumerFilter downstream) {
        return ConsumerFilter.unrestricted();
    }

    /**
     * Marks an interceptor as preserving downstream selection. Use for observers that only need handler-relevant
     * messages and do not expand the accepted inputs. This is an explicit promise, not an analysis of the lambda.
     * Invocation, shutdown and the wrapped interceptor's {@link io.fluxzero.sdk.common.Order} are preserved.
     */
    static BatchInterceptor preservingConsumerFilter(BatchInterceptor interceptor) {
        return new FilterPreserving(Objects.requireNonNull(interceptor));
    }

    /** Ordered application and consumer interceptors, shared by execution and input selection. */
    static List<BatchInterceptor> effectiveInterceptors(ConsumerConfiguration configuration, MessageType type,
                                                       Fluxzero fluxzero) {
        List<BatchInterceptor> result = new ArrayList<>();
        result.add(new FluxzeroInterceptor(fluxzero));
        result.addAll(configuration.getBatchInterceptors().stream()
                .sorted(Comparator.comparingInt(ClientUtils::orderOf)).toList());
        result.addAll(fluxzero.configuration().batchInterceptors().getOrDefault(type, List.of()));
        return List.copyOf(result);
    }

    /** Explicit selection-preserving wrapper; use {@link #preservingConsumerFilter(BatchInterceptor)}. */
    record FilterPreserving(BatchInterceptor delegate) implements BatchInterceptor {
        public FilterPreserving {
            Objects.requireNonNull(delegate);
        }

        @Override
        public ConsumerFilter describeConsumerFilter(ConsumerFilter downstream) {
            return downstream;
        }

        @Override
        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            return delegate.intercept(consumer, tracker);
        }

        @Override
        public void shutdown(Tracker tracker) {
            delegate.shutdown(tracker);
        }
    }

    /**
     * Intercepts the given batch message consumer and returns a decorated version to be invoked by the tracker.
     *
     * @param consumer the original consumer that processes the {@link MessageBatch}
     * @param tracker  the tracker invoking this interceptor
     * @return a wrapped consumer with additional behavior
     */
    Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker);

    /**
     * Optional lifecycle callback for cleanup when the tracker shuts down. Default is a no-op.
     *
     * @param tracker the tracker being shut down
     */
    default void shutdown(Tracker tracker) {
        // no-op
    }

    /**
     * Composes this interceptor with another, returning a new interceptor that applies both in sequence. The
     * {@code nextInterceptor} is applied first, followed by this interceptor.
     *
     * @param nextInterceptor the interceptor to apply before this one
     * @return a combined interceptor
     */
    default BatchInterceptor andThen(BatchInterceptor nextInterceptor) {
        return new BatchInterceptor() {
            @Override
            public ConsumerFilter describeConsumerFilter(ConsumerFilter downstream) {
                ConsumerFilter next = nextInterceptor.describeConsumerFilter(downstream);
                ConsumerFilter result = BatchInterceptor.this.describeConsumerFilter(
                        next == null ? ConsumerFilter.unrestricted() : next);
                return result == null ? ConsumerFilter.unrestricted() : result;
            }

            @Override
            public Consumer<MessageBatch> intercept(Consumer<MessageBatch> c, Tracker t) {
                return BatchInterceptor.this.intercept(nextInterceptor.intercept(c, t), t);
            }

            @Override
            public void shutdown(Tracker tracker) {
                nextInterceptor.shutdown(tracker);
                BatchInterceptor.this.shutdown(tracker);
            }
        };
    }

    /**
     * Joins a list of interceptors into a single composite interceptor, applying them in sequence. If the list is
     * empty, a no-op interceptor is returned.
     *
     * @param interceptors the list of interceptors to join
     * @return a composite interceptor
     */
    static BatchInterceptor join(List<BatchInterceptor> interceptors) {
        return interceptors.stream().reduce(BatchInterceptor::andThen).orElseGet(BatchInterceptor::noOp);
    }
}
