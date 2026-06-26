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

import io.fluxzero.sdk.common.HasMessage;

/**
 * Optional extension for refreshing an already resolved {@link User}.
 * <p>
 * This hook is useful for contexts that keep using a previously established user identity, but still need a fresh
 * view of its roles, claims, or active/disabled status before authorization. For example, a websocket session can pin
 * the user that was accepted when the session opened, then ask this provider to refresh that user for later socket
 * messages without re-authenticating every frame from request headers or payload data.
 * <p>
 * Implementations should treat the supplied user as the trusted identity and return the current authoritative
 * representation of that same identity. Returning {@code null} is interpreted as no authenticated user.
 *
 * @param <U> the concrete user type managed by this provider
 */
public interface RefreshingUserProvider<U extends User> extends UserProvider {

    /**
     * Refreshes an already resolved user for the current message context.
     *
     * @param user    the previously resolved user identity
     * @param message the message being authorized or handled
     * @return the refreshed user, or {@code null} if the user is no longer available
     */
    U refreshUser(U user, HasMessage message);
}
