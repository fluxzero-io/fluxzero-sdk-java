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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.scheduling.Schedule;
import lombok.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of a {@link FixtureResult} captured at one point in time.
 */
@Value
class ImmutableFixtureResult {
    boolean whenPhaseStarted;
    boolean collectingResults;
    Message tracedMessage;
    Object result;
    ImmutableFixtureResult previousResult;
    Map<String, String> knownWebParams;
    List<Message> commands;
    List<Message> queries;
    List<Message> events;
    List<Message> webRequests;
    List<Message> webResponses;
    List<Message> metrics;
    List<Schedule> schedules;
    List<Throwable> errors;
    Map<String, List<Message>> customMessages;

    static ImmutableFixtureResult from(FixtureResult result) {
        return new ImmutableFixtureResult(
                result.isWhenPhaseStarted(),
                result.isCollectingResults(),
                result.getTracedMessage(),
                result.getResult(),
                result.getPreviousResult() == null ? null : from(result.getPreviousResult()),
                Map.copyOf(result.getKnownWebParams()),
                List.copyOf(result.getCommands()),
                List.copyOf(result.getQueries()),
                List.copyOf(result.getEvents()),
                List.copyOf(result.getWebRequests()),
                List.copyOf(result.getWebResponses()),
                List.copyOf(result.getMetrics()),
                List.copyOf(result.getSchedules()),
                List.copyOf(result.getErrors()),
                copyCustomMessages(result));
    }

    private static Map<String, List<Message>> copyCustomMessages(FixtureResult result) {
        Map<String, List<Message>> customMessages = new HashMap<>();
        result.getCustomMessages().forEach((topic, messages) -> customMessages.put(topic, List.copyOf(messages)));
        return Map.copyOf(customMessages);
    }
}
