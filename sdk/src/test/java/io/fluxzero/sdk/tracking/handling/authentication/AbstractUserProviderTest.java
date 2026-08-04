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
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider.DEFAULT_USER_KEY;
import static io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider.SYSTEM_USER_ID;
import static io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider.USE_USER_ID_METADATA_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractUserProviderTest {

    private static final String LEGACY_DEFAULTS_VERSION = "2026.08.03";
    private static final String USER_ID_DEFAULTS_VERSION = "2026.08.04";

    private final MockUser resolvedUser = new MockUser("resolved");
    private final MockUser systemUser = new MockUser("system");
    private final TestUserProvider provider = new TestUserProvider();

    @Test
    void legacyDefaultsStoreAndDeserializeCompleteUser() {
        withConfiguration(LEGACY_DEFAULTS_VERSION, null, () -> {
            MockUser metadataUser = new MockUser("metadata");
            Metadata metadata = provider.addToMetadata(Metadata.empty(), metadataUser);

            assertEquals(metadataUser, metadata.get(DEFAULT_USER_KEY, MockUser.class));
            assertEquals(metadataUser, provider.fromMessage(message(metadata)));
            assertNull(provider.requestedUserId);
        });
    }

    @Test
    void missingDefaultsVersionUsesCompatibilityBehavior() {
        withConfiguration(null, null, () -> {
            MockUser metadataUser = new MockUser("metadata");
            Metadata metadata = provider.addToMetadata(Metadata.empty(), metadataUser);

            assertEquals(metadataUser, metadata.get(DEFAULT_USER_KEY, MockUser.class));
            assertEquals(metadataUser, provider.fromMessage(message(metadata)));
        });
    }

    @Test
    void legacyDefaultsRejectUserIdMetadata() {
        withConfiguration(LEGACY_DEFAULTS_VERSION, null, () -> {
            assertThrows(IllegalStateException.class,
                         () -> provider.fromMessage(message(Metadata.of(DEFAULT_USER_KEY, "user-123"))));
            assertNull(provider.requestedUserId);
        });
    }

    @Test
    void newDefaultsStoreUserNameAndResolveItById() {
        withConfiguration(USER_ID_DEFAULTS_VERSION, null, () -> {
            MockUser metadataUser = new MockUser("metadata");
            Metadata metadata = provider.addToMetadata(Metadata.empty(), metadataUser);

            assertEquals(metadataUser.getName(), metadata.get(DEFAULT_USER_KEY));
            assertSame(resolvedUser, provider.fromMessage(message(metadata)));
            assertEquals(metadataUser.getName(), provider.requestedUserId);
        });
    }

    @Test
    void newDefaultsResolveSystemUserWithoutRegularLookup() {
        withConfiguration(USER_ID_DEFAULTS_VERSION, null, () -> {
            Metadata metadata = provider.addToMetadata(Metadata.empty(), systemUser);

            assertEquals(SYSTEM_USER_ID, metadata.get(DEFAULT_USER_KEY));
            assertSame(systemUser, provider.fromMessage(message(metadata)));
            assertNull(provider.requestedUserId);
        });
    }

    @Test
    void newDefaultsReserveSystemUserIdForSystemUser() {
        withConfiguration(USER_ID_DEFAULTS_VERSION, null, () -> {
            User regularUser = new User() {
                @Override
                public String getName() {
                    return SYSTEM_USER_ID;
                }

                @Override
                public boolean hasRole(String role) {
                    return false;
                }
            };

            assertThrows(IllegalArgumentException.class,
                         () -> provider.addToMetadata(Metadata.empty(), regularUser));
        });
    }

    @Test
    void newDefaultsRemainCompatibleWithSerializedUsersAndObjectShapedIds() {
        withConfiguration(USER_ID_DEFAULTS_VERSION, null, () -> {
            MockUser metadataUser = new MockUser("metadata");
            Metadata metadata = Metadata.of(DEFAULT_USER_KEY, metadataUser);

            assertEquals(metadataUser, provider.fromMessage(message(metadata)));
            assertNull(provider.requestedUserId);

            assertSame(resolvedUser, provider.fromMessage(message(Metadata.of(DEFAULT_USER_KEY, "{invalid"))));
            assertEquals("{invalid", provider.requestedUserId);
            assertSame(resolvedUser, provider.fromMessage(message(Metadata.of(DEFAULT_USER_KEY, "{invalid}"))));
            assertEquals("{invalid}", provider.requestedUserId);
        });
    }

    @Test
    void explicitPropertyOverridesDefaultsVersion() {
        withConfiguration(LEGACY_DEFAULTS_VERSION, true, () -> {
            Metadata metadata = provider.addToMetadata(Metadata.empty(), new MockUser("metadata"));
            assertEquals("mockUser", metadata.get(DEFAULT_USER_KEY));
        });
        withConfiguration(USER_ID_DEFAULTS_VERSION, false, () -> {
            MockUser metadataUser = new MockUser("metadata");
            Metadata metadata = provider.addToMetadata(Metadata.empty(), metadataUser);
            assertEquals(metadataUser, metadata.get(DEFAULT_USER_KEY, MockUser.class));
        });
    }

    private static Message message(Metadata metadata) {
        return Message.asMessage("payload").withMetadata(metadata);
    }

    private static void withConfiguration(String defaultsVersion, Boolean useUserIdMetadata, Runnable test) {
        TestFixture fixture = TestFixture.create();
        if (defaultsVersion != null) {
            fixture.withProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY, defaultsVersion);
        }
        if (useUserIdMetadata != null) {
            fixture.withProperty(USE_USER_ID_METADATA_PROPERTY, useUserIdMetadata);
        }
        fixture.whenApplying(fc -> {
            test.run();
            return null;
        }).expectNoResult();
    }

    private class TestUserProvider extends AbstractUserProvider {
        private Object requestedUserId;

        private TestUserProvider() {
            super(MockUser.class);
        }

        @Override
        public User getUserById(Object userId) {
            requestedUserId = userId;
            return resolvedUser;
        }

        @Override
        public User getSystemUser() {
            return systemUser;
        }
    }
}
