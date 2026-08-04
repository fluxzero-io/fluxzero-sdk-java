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
 *
 */

package io.fluxzero.sdk.tracking.handling.authentication;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.time.LocalDate;
import java.util.Objects;

import static io.fluxzero.sdk.configuration.ApplicationProperties.DEFAULTS_VERSION_PROPERTY;

/**
 * Abstract base class for implementing {@link UserProvider}s that resolve user identities via a metadata key.
 * <p>
 * This implementation provides a reusable foundation for extracting, injecting, and managing {@link User} instances
 * within {@link Metadata}, commonly used in message handling scenarios. Most concrete {@code UserProvider}
 * implementations can extend this class to inherit standard behavior for:
 * <ul>
 *   <li>Retrieving a user or resolving a user ID from message metadata via a configured key</li>
 *   <li>Checking whether a user is present in metadata</li>
 *   <li>Inserting or removing user entries in metadata</li>
 * </ul>
 *
 * <h2>Metadata key</h2>
 * The default metadata key is {@link #DEFAULT_USER_KEY}, which resolves to {@code "$user"}. With compatibility
 * defaults, this key stores a serialized user object. With defaults version {@code 2026.08.04} or newer, or when
 * {@link #USE_USER_ID_METADATA_PROPERTY} is enabled, it stores {@link User#getName()} for regular users and
 * {@link #SYSTEM_USER_ID} for the system user. Regular IDs resolve through {@link #getUserById(Object)} and the system
 * ID resolves through {@link #getSystemUser()}.
 * Custom keys can also be provided via the constructor for flexibility across different application contexts.
 *
 * @see User
 * @see UserProvider
 * @see Metadata
 * @see io.fluxzero.sdk.common.HasMessage
 */
public abstract class AbstractUserProvider implements UserProvider {

    private static final LocalDate USER_ID_METADATA_DEFAULTS_VERSION = LocalDate.of(2026, 8, 4);

    /**
     * Default key used in {@link Metadata} to store user information.
     */
    public static final String DEFAULT_USER_KEY = "$user";

    /**
     * Property that explicitly selects whether user metadata contains user IDs instead of serialized user objects.
     */
    public static final String USE_USER_ID_METADATA_PROPERTY = "fluxzero.auth.useUserIdMetadata";

    /**
     * Reserved user ID that resolves to the system user.
     */
    public static final String SYSTEM_USER_ID = "$system";


    private final String metadataKey;
    private final Class<? extends User> userClass;
    private volatile UserMetadataConfiguration userMetadataConfiguration;

    /**
     * Constructs an {@code AbstractUserProvider} using a custom metadata key.
     *
     * @param metadataKey the key that stores the user or user ID
     * @param userClass   the concrete user class this provider resolves
     */
    public AbstractUserProvider(String metadataKey, Class<? extends User> userClass) {
        this.metadataKey = metadataKey;
        this.userClass = userClass;
    }

    /**
     * Constructs an {@code AbstractUserProvider} using the default metadata key {@link #DEFAULT_USER_KEY}.
     *
     * @param userClass the concrete user class this provider resolves
     */
    public AbstractUserProvider(Class<? extends User> userClass) {
        this(DEFAULT_USER_KEY, userClass);
    }

    /**
     * Extracts a {@link User} from the metadata of a message.
     * <p>
     * Uses the configured {@code metadataKey} to locate the user in the metadata of the given message. Compatibility
     * defaults only accept a serialized user object. New defaults also accept user IDs while continuing to deserialize
     * user objects written before migration. IDs are resolved through {@link #getUserById(Object)}, except for
     * {@link #SYSTEM_USER_ID}, which resolves through {@link #getSystemUser()}.
     *
     * @param message the message containing metadata
     * @return the resolved {@link User}, or {@code null} if not found
     */
    @Override
    public User fromMessage(HasMessage message) {
        Metadata metadata = message.getMetadata();
        if (!useUserIdMetadata()) {
            return metadata.get(metadataKey, userClass);
        }
        String metadataValue = metadata.get(metadataKey);
        if (metadataValue == null || "null".equals(metadataValue)) {
            return null;
        }
        if (SYSTEM_USER_ID.equals(metadataValue)) {
            return getSystemUser();
        }
        String normalizedValue = metadataValue.stripLeading();
        if (normalizedValue.startsWith("{") || normalizedValue.startsWith("[")) {
            return metadata.get(metadataKey, userClass);
        }
        return getUserById(metadataValue);
    }

    /**
     * Returns {@code true} if the metadata contains a user entry under the configured key.
     *
     * @param metadata the metadata to inspect
     * @return {@code true} if a user is present, otherwise {@code false}
     */
    @Override
    public boolean containsUser(Metadata metadata) {
        return metadata.containsKey(metadataKey);
    }

    /**
     * Removes the user entry from the metadata.
     *
     * @param metadata the original metadata
     * @return a new {@link Metadata} instance without the user entry
     */
    @Override
    public Metadata removeFromMetadata(Metadata metadata) {
        return metadata.without(metadataKey);
    }

    /**
     * Adds a {@link User} to the metadata using the configured key. Compatibility defaults serialize the complete user;
     * new defaults store {@link User#getName()} so the receiving provider can resolve a regular user by ID, or
     * {@link #SYSTEM_USER_ID} when {@link #isSystemUser(User)} identifies the user as the system user.
     *
     * @param metadata the original metadata
     * @param user     the user to add
     * @param ifAbsent whether to only add the user if it is not already present
     * @return updated metadata including the user
     * @throws IllegalArgumentException if a regular user has the reserved name {@link #SYSTEM_USER_ID}
     */
    @Override
    public Metadata addToMetadata(Metadata metadata, User user, boolean ifAbsent) {
        if (ifAbsent && metadata.containsKey(metadataKey)) {
            return metadata;
        }
        Object metadataUser = user;
        if (user != null && useUserIdMetadata()) {
            if (isSystemUser(user)) {
                metadataUser = SYSTEM_USER_ID;
            } else {
                metadataUser = user.getName();
                if (SYSTEM_USER_ID.equals(metadataUser)) {
                    throw new IllegalArgumentException("User ID `%s` is reserved for the system user"
                                                               .formatted(SYSTEM_USER_ID));
                }
            }
        }
        return ifAbsent ? metadata.addIfAbsent(metadataKey, metadataUser)
                : user == null ? metadata.withNull(metadataKey) : metadata.with(metadataKey, metadataUser);
    }

    /**
     * Determines whether a user should be represented by {@link #SYSTEM_USER_ID} in metadata.
     * <p>
     * The default implementation checks whether the supplied user is the same instance as {@link #getSystemUser()}.
     * Providers whose system user is not a stable instance may override this method with their own identity check.
     *
     * @param user the non-null user being added to metadata
     * @return {@code true} if the user represents the system identity
     */
    protected boolean isSystemUser(User user) {
        return user == getSystemUser();
    }

    private boolean useUserIdMetadata() {
        String configured = ApplicationProperties.getProperty(USE_USER_ID_METADATA_PROPERTY);
        String defaultsVersion = configured == null
                ? ApplicationProperties.getProperty(DEFAULTS_VERSION_PROPERTY) : null;
        UserMetadataConfiguration cached = userMetadataConfiguration;
        if (cached != null && Objects.equals(cached.configured(), configured)
            && Objects.equals(cached.defaultsVersion(), defaultsVersion)) {
            return cached.useUserIdMetadata();
        }
        boolean result = configured == null
                ? ApplicationProperties.defaultsVersionAtLeast(USER_ID_METADATA_DEFAULTS_VERSION)
                : Boolean.parseBoolean(configured.trim());
        userMetadataConfiguration = new UserMetadataConfiguration(configured, defaultsVersion, result);
        return result;
    }

    private record UserMetadataConfiguration(String configured, String defaultsVersion, boolean useUserIdMetadata) {
    }
}
