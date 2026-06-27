/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser;

import io.fluxzero.common.MessageType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-clock driven browser scheduler.
 */
public final class BrowserScheduler {
    private final Clock clock;
    private final BrowserMessageBus messageBus;
    private final List<ScheduledMessage> scheduled = new ArrayList<>();
    private final List<Object> expired = new ArrayList<>();

    BrowserScheduler(Clock clock, BrowserMessageBus messageBus) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
    }

    public void schedule(String id, Instant deadline, Object payload) {
        scheduled.add(new ScheduledMessage(id, deadline, payload));
        scheduled.sort(Comparator.comparing(ScheduledMessage::deadline));
    }

    public int runDue() {
        Instant now = clock.instant();
        List<ScheduledMessage> due = scheduled.stream().filter(message -> !message.deadline().isAfter(now)).toList();
        scheduled.removeAll(due);
        due.forEach(message -> {
            expired.add(message.payload());
            messageBus.dispatch(MessageType.SCHEDULE, message.payload());
        });
        return due.size();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scheduled", scheduled.size());
        state.put("expired", expired.size());
        return state;
    }

    private static final class ScheduledMessage {
        private final String id;
        private final Instant deadline;
        private final Object payload;

        private ScheduledMessage(String id, Instant deadline, Object payload) {
            this.id = id;
            this.deadline = deadline;
            this.payload = payload;
        }

        String id() {
            return id;
        }

        Instant deadline() {
            return deadline;
        }

        Object payload() {
            return payload;
        }
    }
}
