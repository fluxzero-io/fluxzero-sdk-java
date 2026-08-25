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

package io.fluxzero.sdk.publishing.correlation;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.client.Client;
import jakarta.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.fluxzero.sdk.configuration.ApplicationProperties.APPLICATION_VERSION_PROPERTY;

/**
 * Adds the configured application version to another correlation data provider.
 */
public final class ApplicationVersionCorrelationDataProvider implements CorrelationDataProvider {
    private final CorrelationDataProvider delegate;
    private final String applicationVersion;

    /**
     * Decorates the given provider when {@code fluxzero.application.version} is configured. Returns the original
     * provider unchanged when the property is absent or blank.
     *
     * @param delegate provider that supplies the existing correlation data
     * @param propertySource source that may contain the application version
     * @return the decorated provider, or {@code delegate} when no version is configured
     */
    public static CorrelationDataProvider decorate(CorrelationDataProvider delegate, PropertySource propertySource) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(propertySource, "propertySource");
        String configuredVersion = propertySource.get(APPLICATION_VERSION_PROPERTY);
        return configuredVersion == null || configuredVersion.isBlank()
                ? delegate : new ApplicationVersionCorrelationDataProvider(delegate, configuredVersion.strip());
    }

    private ApplicationVersionCorrelationDataProvider(CorrelationDataProvider delegate, String applicationVersion) {
        this.delegate = delegate;
        this.applicationVersion = applicationVersion;
    }

    @Override
    public Map<String, String> getCorrelationData(@Nullable DeserializingMessage currentMessage) {
        return addApplicationVersion(delegate.getCorrelationData(currentMessage));
    }

    @Override
    public Map<String, String> getCorrelationData(@Nullable Client client, @Nullable SerializedMessage currentMessage,
                                                  @Nullable MessageType messageType) {
        return addApplicationVersion(delegate.getCorrelationData(client, currentMessage, messageType));
    }

    boolean decoratesDefaultProvider() {
        return delegate == DefaultCorrelationDataProvider.INSTANCE;
    }

    private Map<String, String> addApplicationVersion(Map<String, String> correlationData) {
        Map<String, String> result = decoratesDefaultProvider()
                ? correlationData : new HashMap<>(correlationData);
        result.put(getApplicationVersionKey(), applicationVersion);
        return result;
    }

    @Override
    public String getApplicationVersionKey() {
        return delegate.getApplicationVersionKey();
    }

    @Override
    public String getApplicationIdKey() {
        return delegate.getApplicationIdKey();
    }

    @Override
    public String getClientIdKey() {
        return delegate.getClientIdKey();
    }

    @Override
    public String getClientNameKey() {
        return delegate.getClientNameKey();
    }

    @Override
    public String getConsumerKey() {
        return delegate.getConsumerKey();
    }

    @Override
    public String getHandlerKey() {
        return delegate.getHandlerKey();
    }

    @Override
    public String getTrackerKey() {
        return delegate.getTrackerKey();
    }

    @Override
    public String getCorrelationIdKey() {
        return delegate.getCorrelationIdKey();
    }

    @Override
    public String getTraceIdKey() {
        return delegate.getTraceIdKey();
    }

    @Override
    public String getTriggerKey() {
        return delegate.getTriggerKey();
    }

    @Override
    public String getTriggerTypeKey() {
        return delegate.getTriggerTypeKey();
    }

    @Override
    public String getTriggerNamespaceKey() {
        return delegate.getTriggerNamespaceKey();
    }

    @Override
    public String getInvocationKey() {
        return delegate.getInvocationKey();
    }

    @Override
    public String getDelayKey() {
        return delegate.getDelayKey();
    }
}
