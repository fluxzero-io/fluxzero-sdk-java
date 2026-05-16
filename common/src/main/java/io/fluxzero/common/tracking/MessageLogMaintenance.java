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

import java.util.concurrent.CompletableFuture;

/**
 * Coordinates maintenance operations that span the message log, tracking positions and active trackers.
 */
public class MessageLogMaintenance {

    private final MessageStore messageStore;
    private final PositionStore positionStore;
    private final TrackingStrategy trackingStrategy;

    /**
     * Creates message log maintenance for the given shared log components.
     *
     * @param messageStore     the message store to maintain
     * @param positionStore    the position store associated with the message log
     * @param trackingStrategy the tracking strategy that owns active trackers for the message log
     */
    public MessageLogMaintenance(MessageStore messageStore, PositionStore positionStore,
                                 TrackingStrategy trackingStrategy) {
        this.messageStore = messageStore;
        this.positionStore = positionStore;
        this.trackingStrategy = trackingStrategy;
    }

    /**
     * Returns the message store maintained by this instance.
     *
     * @return the message store
     */
    public MessageStore getMessageStore() {
        return messageStore;
    }

    /**
     * Returns the position store maintained by this instance.
     *
     * @return the position store
     */
    public PositionStore getPositionStore() {
        return positionStore;
    }

    /**
     * Returns the tracking strategy whose active trackers are coordinated by this instance.
     *
     * @return the tracking strategy
     */
    public TrackingStrategy getTrackingStrategy() {
        return trackingStrategy;
    }

    /**
     * Truncates the message log and clears all tracking positions.
     * <p>
     * Active trackers are disconnected first and receive a final empty batch so consumers can stop waiting before the
     * underlying stores are cleared.
     */
    public synchronized CompletableFuture<Void> truncate() {
        trackingStrategy.disconnectTrackers(tracker -> true, true);
        messageStore.truncate();
        positionStore.truncate();
        return CompletableFuture.completedFuture(null);
    }
}
