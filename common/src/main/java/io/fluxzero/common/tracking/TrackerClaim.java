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

package io.fluxzero.common.tracking;

/**
 * An active segment reservation transferred between elected Runtime instances.
 * This contains ownership only: persisted consumer positions remain the progress authority.
 *
 * @param consumer consumer name
 * @param trackerId original tracker identity
 * @param clientId original client identity, used for connection liveness
 * @param start inclusive segment boundary
 * @param end exclusive segment boundary
 * @param startedAtMillis time the original batch became active
 * @param purgeDelayMillis configured processing timeout, or null when unbounded
 * @param singleTracker whether ownership excludes every other tracker of this consumer
 */
public record TrackerClaim(String consumer, String trackerId, String clientId, int start, int end,
                           long startedAtMillis, Long purgeDelayMillis, boolean singleTracker) {
    public TrackerClaim {
        if (consumer == null || trackerId == null || clientId == null || start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid tracker reservation");
        }
    }
}
