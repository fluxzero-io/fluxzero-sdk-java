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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.HasMessage;
import lombok.AllArgsConstructor;

/**
 * An extendable {@link UserProvider} that delegates to another {@link UserProvider}.
 */
@AllArgsConstructor
public class DelegatingUserProvider implements RefreshingUserProvider<User> {
    protected final UserProvider delegate;

    @Override
    public User getActiveUser() {
        return delegate.getActiveUser();
    }

    @Override
    public User getUserById(Object userId) {
        return delegate.getUserById(userId);
    }

    @Override
    public User getSystemUser() {
        return delegate.getSystemUser();
    }

    @Override
    public User fromMessage(HasMessage message) {
        return delegate.fromMessage(message);
    }

    /**
     * Refreshes the supplied user when the delegate supports {@link RefreshingUserProvider}; otherwise returns the
     * original user unchanged.
     *
     * @param user    the previously resolved user identity
     * @param message the message being authorized or handled
     * @return the refreshed user, or the original user if the delegate does not support refresh
     */
    @Override
    public User refreshUser(User user, HasMessage message) {
        return delegate instanceof RefreshingUserProvider<?> refreshingUserProvider
                ? refreshUser(refreshingUserProvider, user, message) : user;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected User refreshUser(RefreshingUserProvider refreshingUserProvider, User user, HasMessage message) {
        return refreshingUserProvider.refreshUser(user, message);
    }

    @Override
    public boolean containsUser(Metadata metadata) {
        return delegate.containsUser(metadata);
    }

    @Override
    public Metadata removeFromMetadata(Metadata metadata) {
        return delegate.removeFromMetadata(metadata);
    }

    @Override
    public Metadata addToMetadata(Metadata metadata, User user, boolean ifAbsent) {
        return delegate.addToMetadata(metadata, user, ifAbsent);
    }

    @Override
    public UserProvider andThen(UserProvider other) {
        return delegate.andThen(other);
    }
}
