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

package io.fluxzero.sdk.scheduling.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.scheduling.SerializedSchedule;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;

/**
 * A lower-level client interface for scheduling and cancelling deferred messages (i.e., schedules) in Fluxzero.
 * <p>
 * This interface provides the primitives for scheduling logic used internally by
 * {@link io.fluxzero.sdk.scheduling.MessageScheduler}. It may interface with either:
 * <ul>
 *     <li>The Fluxzero Runtime in runtime scenarios, where schedules are persisted in the {@link io.fluxzero.common.MessageType#SCHEDULE} log, or</li>
 *     <li>An in-memory schedule store for testing scenarios, allowing fast and isolated feedback cycles.</li>
 * </ul>
 *
 * <p>
 * Most application developers will not use this interface directly. Instead, they should rely on higher-level scheduling APIs
 * such as {@link io.fluxzero.sdk.scheduling.MessageScheduler} or static methods like
 * {@code Fluxzero.schedule(...)}.
 *
 * <p>
 * A schedule represents a message that will be dispatched at a future time. The {@link SerializedSchedule} class encapsulates
 * the serialized form of these scheduled messages.
 *
 * @see io.fluxzero.sdk.scheduling.MessageScheduler
 * @see SerializedSchedule
 * @see WebsocketSchedulingClient
 */
public interface SchedulingClient extends AutoCloseable {

    /**
     * Schedule one or more serialized schedules using {@link Guarantee#DEFAULT} as the default delivery guarantee.
     *
     * @param schedules One or more schedules to add.
     * @return A future that completes when the schedules have been sent or persisted (depending on the
     * underlying implementation).
     */
    default CompletableFuture<Void> schedule(SerializedSchedule... schedules) {
        return schedule(Guarantee.DEFAULT, schedules);
    }

    /**
     * Schedule one or more serialized schedules with a specified {@link Guarantee}.
     *
     * @param guarantee Delivery guarantee to apply (e.g., none, sent, stored).
     * @param schedules One or more schedules to register.
     * @return A future that completes when the selected delivery guarantee is reached.
     */
    CompletableFuture<Void> schedule(Guarantee guarantee, SerializedSchedule... schedules);

    /**
     * Schedules messages owned by the current lifetime of each committed Model parent.
     * Any parent deletion triggers asynchronous cancellation. Empty parents use ordinary scheduling.
     * Custom clients must explicitly implement ownership; it is never silently ignored.
     *
     * @param guarantee acknowledgement guarantee
     * @param parentIds canonical Model IDs in this client's namespace
     * @param schedules messages to register
     * @return acknowledgement of scheduling, or an unsupported-operation failure
     */
    default CompletableFuture<Void> scheduleWithParents(Guarantee guarantee, List<String> parentIds,
                                                        SerializedSchedule... schedules) {
        return parentIds.isEmpty() ? schedule(guarantee, schedules)
                : bindScheduleParents(parentIds).thenCompose(io.fluxzero.sdk.common.ThreadLocalContext.capture().wrap(
                        parents -> scheduleBoundToParents(guarantee, parents, schedules)));
    }

    /** Acquires opaque namespace-local lifetime bindings to existing parents. */
    default CompletableFuture<Map<String, Long>> bindScheduleParents(List<String> parentIds) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This scheduling client does not support Model-owned schedules"));
    }

    /** Stores schedules with previously acquired bindings, without rebinding recreated parents. */
    default CompletableFuture<Void> scheduleBoundToParents(Guarantee guarantee, Map<String, Long> parents,
                                                          SerializedSchedule... schedules) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This scheduling client does not support Model-owned schedules"));
    }

    /**
     * Cancel a scheduled message using {@link Guarantee#DEFAULT} as the default guarantee.
     *
     * @param scheduleId The identifier of the schedule to cancel.
     * @return A future that completes when the selected delivery guarantee is reached.
     */
    default CompletableFuture<Void> cancelSchedule(String scheduleId) {
        return cancelSchedule(scheduleId, Guarantee.DEFAULT);
    }

    /**
     * Cancel a scheduled message using the provided delivery guarantee.
     *
     * @param scheduleId The identifier of the schedule to cancel.
     * @param guarantee  Delivery guarantee for the cancellation request.
     * @return A future that completes when the selected delivery guarantee is reached.
     */
    CompletableFuture<Void> cancelSchedule(String scheduleId, Guarantee guarantee);

    /**
     * Checks whether a schedule with the given ID currently exists.
     *
     * @param scheduleId The identifier of the schedule to check.
     * @return {@code true} if a schedule exists for the given ID, {@code false} otherwise.
     */
    default boolean hasSchedule(String scheduleId) {
        return getSchedule(scheduleId) != null;
    }

    /**
     * Retrieves the serialized schedule associated with the given ID.
     *
     * @param scheduleId The ID of the schedule to retrieve.
     * @return The matching {@link SerializedSchedule}, or {@code null} if none is found.
     */
    SerializedSchedule getSchedule(String scheduleId);

    /**
     * Closes this client and releases any underlying resources or tracking registrations.
     */
    @Override
    void close();
}
