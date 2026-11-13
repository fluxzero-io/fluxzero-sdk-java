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

package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.Registration;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Allocated only for watched pools. Slot opens use the pool's normal lock, handshake and retry policy. */
@Slf4j
final class SessionObservations {
    private static final int MAX_PENDING_CHANGES = 64;
    private final IntConsumer connect;
    private final IntPredicate open;
    private final Thread[] connecting;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private boolean closed;

    SessionObservations(int size, IntConsumer connect, IntPredicate open) {
        this.connect = connect;
        this.open = open;
        this.connecting = new Thread[size];
    }

    synchronized Registration subscribe(int first, int count, Consumer<Boolean> listener) {
        var subscription = new Subscription(first, count, listener);
        subscriptions.add(subscription);
        subscription.refresh();
        for (int i = first; i < first + count; i++) { ensureConnected(i); }
        return subscription;
    }

    synchronized void changed(int slot) {
        if (closed) { return; }
        boolean watched = false;
        for (Subscription subscription : subscriptions) {
            if (subscription.includes(slot)) {
                watched = true;
                subscription.refresh();
            }
        }
        if (watched) { ensureConnected(slot); }
    }

    synchronized boolean ownsConnectionAttempt(int slot) {
        return !closed && connecting[slot] == Thread.currentThread();
    }

    private void ensureConnected(int slot) {
        if (connecting[slot] != null || open.test(slot)) { return; }
        Thread worker = Thread.ofVirtual().name("fluxzero-maintain-websocket-" + slot).unstarted(() -> {
            boolean established = false;
            try {
                connect.accept(slot);
                established = true;
            } catch (SessionPool.ClientClosedException ignored) {
                // Cancellation or pool shutdown won the race to the slot.
            } catch (Exception failure) {
                if (ownsConnectionAttempt(slot) && !Thread.currentThread().isInterrupted()) {
                    log.warn("Could not maintain WebSocket slot {}", slot, failure);
                }
            } finally {
                synchronized (this) {
                    if (connecting[slot] == Thread.currentThread()) {
                        connecting[slot] = null;
                        if (established) { changed(slot); }
                    }
                }
            }
        });
        connecting[slot] = worker;
        worker.start();
    }

    private void stopConnecting(int slot) {
        Thread worker = connecting[slot];
        connecting[slot] = null;
        if (worker != null) { worker.interrupt(); }
    }

    synchronized void close() {
        closed = true;
        for (int i = 0; i < connecting.length; i++) { stopConnecting(i); }
        subscriptions.forEach(s -> s.offer(false));
        subscriptions.clear();
    }

    /** Serial delivery per subscriber. No application callback runs under a pool or observer lock. */
    private final class Subscription implements Registration {
        private final int first, count;
        private final Consumer<Boolean> listener;
        private final ArrayDeque<Boolean> pending = new ArrayDeque<>();
        private Boolean last;
        private boolean delivering, cancelled;

        Subscription(int first, int count, Consumer<Boolean> listener) {
            this.first = first;
            this.count = count;
            this.listener = listener;
        }

        boolean includes(int slot) {
            return slot >= first && slot < first + count;
        }

        void refresh() {
            for (int i = first; i < first + count; i++) {
                if (!open.test(i)) { offer(false); return; }
            }
            offer(true);
        }

        synchronized void offer(boolean value) {
            if (cancelled || Boolean.valueOf(value).equals(last)) { return; }
            last = value;
            // Bound retained work for a slow listener. It will converge to the latest transport state.
            if (pending.size() == MAX_PENDING_CHANGES) { pending.clear(); }
            pending.add(value);
            if (!delivering) {
                delivering = true;
                Thread.startVirtualThread(this::deliver);
            }
        }

        private void deliver() {
            while (true) {
                Boolean value;
                synchronized (this) {
                    value = pending.poll();
                    if (value == null) { delivering = false; return; }
                }
                try {
                    listener.accept(value);
                } catch (RuntimeException e) {
                    log.warn("WebSocket availability listener failed", e);
                } catch (Error e) {
                    cancel();
                    throw e;
                }
            }
        }

        @Override
        public void cancel() {
            synchronized (SessionObservations.this) {
                synchronized (this) {
                    cancelled = true;
                    pending.clear();
                }
                if (!subscriptions.remove(this)) { return; }
                for (int i = first; i < first + count; i++) {
                    int slot = i;
                    if (subscriptions.stream().noneMatch(s -> s.includes(slot))) { stopConnecting(slot); }
                }
            }
        }
    }
}
