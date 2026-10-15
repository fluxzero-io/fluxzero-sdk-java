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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.sdk.registry.ConsumerBlueprint;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Orders subscription snapshots and refreshes per consumer, independently of transport session reconnects. */
final class ConsumerFilterUpdates {
    private final Map<Key, State> consumers = new ConcurrentHashMap<>();
    private final BiConsumer<String, ConsumerFilter> beforeUpdate;
    private final boolean references;

    ConsumerFilterUpdates() {
        this(false, (consumer, filter) -> {});
    }

    ConsumerFilterUpdates(boolean references, BiConsumer<String, ConsumerFilter> beforeUpdate) {
        this.references = references;
        this.beforeUpdate = beforeUpdate;
    }

    CompletableFuture<MessageBatch> read(Read read, ConsumerBlueprint blueprint,
                                          Function<Read, CompletableFuture<MessageBatch>> send) {
        if (blueprint == null) {
            return send.apply(read);
        }
        AtomicReference<CompletableFuture<MessageBatch>> result = new AtomicReference<>();
        Key key = new Key(read.getConsumer(), blueprint.getFilter().subscriptionId());
        consumers.compute(key, (consumer, state) -> {
            State effective = state == null ? new State() : state;
            if (state == null) {
                effective.observer = blueprint.observe(filter -> update(key, filter, send).join());
            }
            // Obtain metadata inside the same ordering boundary as refresh, even on a consumer's very first read.
            ConsumerFilter filter = blueprint.getFilter();
            if (effective.filter == null || effective.filter.revision() <= filter.revision()) {
                effective.filter = filter;
            }
            read.setConsumerFilter(references && state != null ? effective.filter.asReference() : effective.filter);
            effective.reads.put(read.getTrackerId(), read);
            result.set(send.apply(read));
            return effective;
        });
        return result.get();
    }

    ConsumerFilter current(Read read) {
        ConsumerFilter filter = read.getConsumerFilter();
        if (filter == null) {
            return null;
        }
        State state = consumers.get(new Key(read.getConsumer(), filter.subscriptionId()));
        return state == null ? filter.referenceOnly() ? null : filter : state.filter;
    }

    CompletableFuture<Void> update(Key consumer, ConsumerFilter filter,
                                    Function<Read, CompletableFuture<MessageBatch>> send) {
        AtomicReference<CompletableFuture<Void>> result = new AtomicReference<>(CompletableFuture.completedFuture(null));
        consumers.computeIfPresent(consumer, (name, state) -> {
            if (state.filter.revision() < filter.revision()) {
                state.filter = filter;
            }
            // A concurrent read may already know this local snapshot without the server having acknowledged it.
            // Every registration notification establishes an explicit remote activation boundary.
            ConsumerFilter current = state.filter;
            beforeUpdate.accept(consumer.consumer(), current);
            state.reads.values().forEach(read -> read.setConsumerFilter(current));
            Read template = state.reads.values().iterator().next();
            Read update = new Read(template.getMessageType(), consumer.consumer(), template.getTrackerId(),
                                   0, 0L, 0L, template.getTypeFilter(), template.isFilterMessageTarget(),
                                   template.isIgnoreSegment(), template.isSingleTracker(),
                                   template.isClientControlledIndex(), template.isIncludeDocumentTombstones(),
                                   template.getLastIndex(), template.getPurgeTimeout());
            update.setConsumerFilter(current);
            result.set(send.apply(update).thenApply(ignored -> null));
            return state;
        });
        return result.get();
    }

    void disconnect(String consumer, String tracker) {
        for (Key key : consumers.keySet()) {
            if (key.consumer().equals(consumer)) {
                consumers.computeIfPresent(key, (name, state) -> {
                    state.reads.remove(tracker);
                    if (state.reads.isEmpty()) {
                        state.observer.cancel();
                        return null;
                    }
                    return state;
                });
            }
        }
    }

    void close() {
        consumers.values().forEach(state -> state.observer.cancel());
        consumers.clear();
    }

    private record Key(String consumer, String subscription) {}

    private static final class State {
        private Registration observer;
        private volatile ConsumerFilter filter;
        private final Map<String, Read> reads = new HashMap<>();
    }
}
