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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManualGraphReplayTest {
    private static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    @Test
    void manualReadOfDynamicWriteTargetReplaysItsPreviousValue() {
        verifyColdRead(new ManualIncrement("counter"));
    }

    @Test
    void injectedReadOfDynamicWriteTargetReplaysItsPreviousValue() {
        verifyColdRead(new InjectedIncrement("counter"));
    }

    @Test
    void manualReadOfSiblingChangedBySameEventUsesPreviousValue() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new IncrementAndObserve("counter", "receipt"))).join());
            assertEquals(1, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
            app.cache().clear();
            assertEquals(1, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
        }
    }

    @Model(searchable = false)
    record Receipt(@EntityId String receiptId, int observed) {}

    record IncrementAndObserve(String counterId, String receiptId) {
        @Apply Counter increment(Counter before) { return new Counter(counterId, before.value() + 1); }
        @Apply Receipt observe() {
            return new Receipt(receiptId, Fluxzero.loadGraph(counterId, Counter.class).get().value());
        }
    }

    private void verifyColdRead(Object increment) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(LocalClient.newInstance())) {
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(new CreateCounter("counter"))).join());
            app.apply(fc -> fc.executeModelCommit(Message.asMessage(increment)).join());
            assertEquals(2, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
            app.cache().clear();
            assertEquals(2, app.apply(fc -> Fluxzero.loadModel("counter", Counter.class).get().value()).intValue());
        }
    }

    @Model(searchable = false)
    record Counter(@EntityId String counterId, int value) {}

    record CreateCounter(String counterId) {
        @Apply Counter apply() { return new Counter(counterId, 1); }
    }

    record ManualIncrement(String selectedId) {
        @Apply List<Counter> apply() {
            int previous = depth.get();
            // Fail deterministically at re-entry rather than exhausting the stack or flooding storage reads.
            if (previous != 0) { throw new AssertionError("Replay recursively re-entered the same transition"); }
            depth.set(previous + 1);
            try {
                Counter before = Fluxzero.loadGraph(selectedId, Counter.class).get();
                return List.of(new Counter(before.counterId(), before.value() + 1));
            } finally { depth.remove(); }
        }
    }

    record InjectedIncrement(String counterId) {
        @Apply List<Counter> apply(Counter before) {
            return List.of(new Counter(before.counterId(), before.value() + 1));
        }
    }
}
