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
 * Defines one durable representation of a {@link Model}.
 * <p>
 * A model may select both representations. When {@link #EVENT_SOURCED} is present, the event stream is authoritative;
 * otherwise {@link #DOCUMENT} is authoritative. This set is independent from event publication and from internal
 * documents used to compose model graphs.
 */
public enum ModelPersistence {

    /** Persist reconstructing events in the model event stream. */
    EVENT_SOURCED,

    /** Persist the current model state as a document. */
    DOCUMENT
}
