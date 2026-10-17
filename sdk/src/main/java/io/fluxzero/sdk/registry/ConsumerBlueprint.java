/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.registry;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.sdk.common.serialization.Serializer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.fluxzero.common.ObjectUtils.forceThrow;

/**
 * Lifecycle-bound projection of the effective handlers assigned to one consumer. The same contract can later be
 * supplied by generated metadata; it contains no application objects or executable code on the wire.
 */
public final class ConsumerBlueprint implements AutoCloseable {
    private final String subscriptionId = java.util.UUID.randomUUID().toString();
    private final Serializer serializer;
    private final Supplier<ConsumerFilter> handlers;
    private final LongSupplier revisions;
    private final UnaryOperator<ConsumerFilter> inputProjection;
    private final List<Consumer<ConsumerFilter>> listeners = new CopyOnWriteArrayList<>();
    private final Registration serializerRegistration;
    private volatile Projection projection;

    public ConsumerBlueprint(Serializer serializer, Supplier<ConsumerFilter> handlers, LongSupplier revisions) {
        this(serializer, handlers, revisions, UnaryOperator.identity());
    }

    /**
     * Projects the serializer's required inputs through an upstream serialized-message transformation, such as a
     * batch interceptor chain. A null projection result means unknown selection and retains broad delivery.
     */
    public ConsumerBlueprint(Serializer serializer, Supplier<ConsumerFilter> handlers, LongSupplier revisions,
                             UnaryOperator<ConsumerFilter> inputProjection) {
        this.serializer = serializer;
        this.handlers = handlers;
        this.revisions = revisions;
        this.inputProjection = java.util.Objects.requireNonNull(inputProjection);
        this.serializerRegistration = serializer.observeConsumerFilters(this::refresh);
        refresh();
    }

    /** Current immutable projection, including its monotonic application-local revision. */
    public ConsumerFilter getFilter() {
        return projection.filter();
    }

    /** Whether the current conservative projection admits this serialized input, without executing application code. */
    public boolean mayHandle(SerializedMessage message) {
        return projection.predicate().test(message);
    }

    private record Projection(ConsumerFilter filter, Predicate<SerializedMessage> predicate) {}

    /**
     * Publishes a new projection after registration changes; listeners establish the remote activation boundary.
     * Every listener is notified even when another fails. Activation failures remain visible to the caller.
     */
    public void refresh() {
        ConsumerFilter current;
        synchronized (this) {
            current = inputProjection.apply(serializer.describeConsumerFilter(handlers.get()));
            if (current == null) {
                current = ConsumerFilter.unrestricted();
            }
            current = current.forRevision(subscriptionId, revisions.getAsLong(), current.format(), current.upcastInputs());
            projection = new Projection(current, current.compile());
        }
        Throwable failure = null;
        for (Consumer<ConsumerFilter> listener : listeners) {
            try {
                listener.accept(current);
            } catch (Throwable e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            forceThrow(failure);
        }
    }

    /** Observes future projections. Newly opened reads always carry {@link #getFilter()} themselves. */
    public Registration observe(Consumer<ConsumerFilter> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void close() {
        serializerRegistration.cancel();
        listeners.clear();
    }
}
