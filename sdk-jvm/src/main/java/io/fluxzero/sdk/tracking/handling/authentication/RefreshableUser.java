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

package io.fluxzero.sdk.tracking.handling.authentication;

/**
 * Mutable holder for a previously resolved user that may be refreshed while handling a message.
 * <p>
 * The holder itself is kept stable in message context while the contained user can be replaced by a refresh-capable
 * {@link UserProvider}. This lets authorization, {@link User#getCurrent()}, and {@link User} parameter resolution see
 * the same refreshed user during a single handling flow.
 */
public class RefreshableUser {
    private volatile User user;

    /**
     * Creates a holder for a previously resolved user.
     *
     * @param user the initial user
     */
    public RefreshableUser(User user) {
        this.user = user;
    }

    /**
     * Returns the current user.
     *
     * @return the current user, or {@code null} if no authenticated user is available
     */
    public User user() {
        return user;
    }

    /**
     * Replaces the current user.
     *
     * @param user the refreshed user
     * @return the refreshed user
     */
    public User update(User user) {
        this.user = user;
        return user;
    }
}
