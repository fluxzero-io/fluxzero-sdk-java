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

package io.fluxzero.javaclient.tracking;

import io.fluxzero.common.Registration;
import io.fluxzero.javaclient.Fluxzero;

import java.util.Arrays;
import java.util.List;

/**
 * The Tracking interface provides a mechanism to start and manage the tracking of messages by a given set of handlers.
 */
public interface Tracking extends AutoCloseable {

    /**
     * Starts the tracking process using the specified Fluxzero instance and the provided handlers.
     *
     * @param fluxzero the Fluxzero instance to be used for tracking
     * @param handlers      the handlers responsible for processing tracked messages
     * @return a Registration instance that can be used to manage and cancel the tracking process
     */
    default Registration start(Fluxzero fluxzero, Object... handlers) {
        return start(fluxzero, Arrays.asList(handlers));
    }

    /**
     * Starts tracking messages using the provided Fluxzero and a list of handlers.
     *
     * @param fluxzero the Fluxzero used to manage the message tracking process
     * @param handlers      a list of handlers that process the tracked messages
     * @return a Registration instance that can be used to cancel the tracking process
     */
    Registration start(Fluxzero fluxzero, List<?> handlers);

    /**
     * Closes the tracking process, releasing any resources held by it. This method is invoked automatically when
     * {@link Fluxzero} is shut down.
     */
    @Override
    void close();
}
