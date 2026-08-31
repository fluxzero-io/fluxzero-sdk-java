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

/**
 * Defines the durable representation and authoritative load path of a {@link Model}.
 * <p>
 * This choice is independent from event publication and from internal documents used to compose model graphs.
 */
public enum ModelPersistence {

    /** Reconstruct state from the model event stream and do not maintain a directly searchable current document. */
    EVENT_SOURCED(true, false),

    /** Reconstruct state from the event stream and also maintain a directly searchable current document. */
    EVENT_SOURCED_WITH_DOCUMENT(true, true),

    /**
     * Treat the current document as authoritative and load state directly through the configured document serializer.
     */
    DOCUMENT(false, true);

    private final boolean eventSourced;
    private final boolean storesDocument;

    ModelPersistence(boolean eventSourced, boolean storesDocument) {
        this.eventSourced = eventSourced;
        this.storesDocument = storesDocument;
    }

    /** Whether normal loads reconstruct state from stored model events. */
    public boolean isEventSourced() {
        return eventSourced;
    }

    /** Whether commits maintain a directly searchable current document. */
    public boolean storesDocument() {
        return storesDocument;
    }
}
