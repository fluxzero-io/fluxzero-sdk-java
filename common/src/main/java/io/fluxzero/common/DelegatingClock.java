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

package io.fluxzero.common;

import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.ObjectUtils.tryRun;

/**
 * A concrete implementation of a {@link Clock} that delegates its method calls to another {@link Clock}
 * instance, allowing runtime manipulation of the delegated clock.
 *
 * <p>This class is useful when you need to dynamically switch the clock's implementation during runtime,
 * for example, testing scenarios requiring adjustable or controlled time flows.
 *
 * <p>The delegated clock instance is stored atomically to ensure thread-safe updates.
 */
@AllArgsConstructor
public class DelegatingClock extends Clock {
    private final AtomicReference<Clock> delegate = new AtomicReference<>(Clock.systemUTC());
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Replaces the backing clock and notifies registered change listeners after the new clock is visible.
     */
    public void setDelegate(@NonNull Clock delegate) {
        this.delegate.set(delegate);
        changeListeners.forEach(listener -> tryRun(listener::run));
    }

    /**
     * Registers a listener that is invoked whenever the backing clock changes.
     *
     * @param listener callback to invoke after {@link #setDelegate(Clock)}
     * @return registration that removes the listener
     */
    public Registration onChange(@NonNull Runnable listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    @Override
    public ZoneId getZone() {
        return delegate.get().getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.get().withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.get().instant();
    }
}
