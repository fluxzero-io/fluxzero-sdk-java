/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.web;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MemoizingSupplier;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.Namespaced;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.publishing.GatewayException;
import io.fluxzero.sdk.publishing.GenericGateway;
import io.fluxzero.sdk.publishing.MetricsGateway;
import io.fluxzero.sdk.publishing.TimeoutException;
import io.fluxzero.sdk.publishing.WebRequestGateway;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.memoize;
import static java.lang.Thread.currentThread;

/**
 * Default implementation of the {@link WebRequestGateway} interface that delegates requests to a configured
 * {@link GenericGateway}, or executes them through a lifecycle-managed native HTTP client when explicitly requested.
 * <p>
 * It supports sending web requests in both asynchronous (fire-and-forget, future-based) and synchronous (blocking)
 * manners. Proxy execution remains the default and preserves Fluxzero message handling and traceability.
 *
 * @see WebRequestGateway
 * @see GenericGateway
 */
@Slf4j
public class DefaultWebRequestGateway extends AbstractNamespaced<WebRequestGateway>
        implements WebRequestGateway {
    static final String DEFAULT_REDIRECT_POLICY_PROPERTY = "fluxzero.web.defaultRedirectPolicy";
    static final LocalDate SAME_ORIGIN_DEFAULTS_VERSION = LocalDate.of(2026, 8, 26);

    @Delegate(excludes = Namespaced.class)
    private final GenericGateway delegate;
    private final Supplier<NativeWebRequestClient> nativeHttpClientFactory;
    private final MemoizingSupplier<NativeWebRequestClient> nativeHttpClient;
    private final boolean nativeHttpClientOwner;
    private final RedirectPolicy defaultRedirectPolicy;
    private final MetricsGateway metricsGateway;

    /**
     * Creates a gateway using a default Jackson serializer for opt-in native HTTP request bodies.
     *
     * @param delegate gateway used for proxy-routed requests
     */
    public DefaultWebRequestGateway(GenericGateway delegate) {
        this(delegate, () -> new NativeWebRequestClient(new JacksonSerializer()));
    }

    /**
     * Creates a gateway using the configured application serializer for opt-in native HTTP request bodies.
     *
     * @param delegate   gateway used for proxy-routed requests
     * @param serializer serializer used to encode native HTTP request bodies
     */
    public DefaultWebRequestGateway(GenericGateway delegate, Serializer serializer) {
        this(delegate, () -> new NativeWebRequestClient(serializer));
    }

    /**
     * Creates a gateway with an application-scoped versioned redirect default and optional native transport metrics.
     * Passing {@code null} as metrics gateway disables automatic native transport metrics.
     *
     * @param delegate       gateway used for proxy-routed requests
     * @param serializer     serializer used to encode native HTTP request bodies
     * @param propertySource application property source used to resolve the versioned redirect default
     * @param metricsGateway metrics gateway, or {@code null} when automatic metrics are globally disabled
     */
    public DefaultWebRequestGateway(GenericGateway delegate, Serializer serializer, PropertySource propertySource,
                                    MetricsGateway metricsGateway) {
        this(delegate, () -> new NativeWebRequestClient(serializer),
             resolveDefaultRedirectPolicy(propertySource), metricsGateway);
    }

    DefaultWebRequestGateway(GenericGateway delegate, NativeWebRequestClient nativeHttpClient) {
        this(delegate, () -> nativeHttpClient);
    }

    DefaultWebRequestGateway(GenericGateway delegate, NativeWebRequestClient nativeHttpClient,
                             RedirectPolicy defaultRedirectPolicy, MetricsGateway metricsGateway) {
        this(delegate, () -> nativeHttpClient, defaultRedirectPolicy, metricsGateway);
    }

    DefaultWebRequestGateway(GenericGateway delegate, Supplier<NativeWebRequestClient> nativeHttpClientFactory) {
        this(delegate, nativeHttpClientFactory, RedirectPolicy.ALLOW, null);
    }

    private DefaultWebRequestGateway(GenericGateway delegate,
                                     Supplier<NativeWebRequestClient> nativeHttpClientFactory,
                                     RedirectPolicy defaultRedirectPolicy, MetricsGateway metricsGateway) {
        this(delegate, nativeHttpClientFactory, memoize(nativeHttpClientFactory), true,
             defaultRedirectPolicy, metricsGateway);
    }

    private DefaultWebRequestGateway(GenericGateway delegate,
                                     Supplier<NativeWebRequestClient> nativeHttpClientFactory,
                                     MemoizingSupplier<NativeWebRequestClient> nativeHttpClient,
                                     boolean nativeHttpClientOwner, RedirectPolicy defaultRedirectPolicy,
                                     MetricsGateway metricsGateway) {
        this.delegate = delegate;
        this.nativeHttpClientFactory = nativeHttpClientFactory;
        this.nativeHttpClient = nativeHttpClient;
        this.nativeHttpClientOwner = nativeHttpClientOwner;
        this.defaultRedirectPolicy = defaultRedirectPolicy;
        this.metricsGateway = metricsGateway;
    }

    static RedirectPolicy resolveDefaultRedirectPolicy(PropertySource propertySource) {
        String configured = propertySource.get(DEFAULT_REDIRECT_POLICY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            RedirectPolicy result;
            try {
                result = RedirectPolicy.valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Property `%s` must be NEVER, SAME_ORIGIN, or ALLOW, but found `%s`"
                                .formatted(DEFAULT_REDIRECT_POLICY_PROPERTY, configured), e);
            }
            if (result == RedirectPolicy.DEFAULT) {
                throw new IllegalArgumentException(
                        "Property `%s` must be NEVER, SAME_ORIGIN, or ALLOW, but found DEFAULT"
                                .formatted(DEFAULT_REDIRECT_POLICY_PROPERTY));
            }
            return result;
        }
        return ApplicationProperties.defaultsVersionAtLeast(propertySource, SAME_ORIGIN_DEFAULTS_VERSION)
                ? RedirectPolicy.SAME_ORIGIN : RedirectPolicy.ALLOW;
    }

    @Override
    public CompletableFuture<Void> sendAndForget(Guarantee guarantee, WebRequest... requests) {
        return delegate.sendAndForget(guarantee, requests);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public CompletableFuture<WebResponse> send(WebRequest request, WebRequestSettings settings) {
        CompletableFuture<WebResponse> requestFuture = sendRequest(request, settings);
        if (!settings.isUseNativeHttpClient()) {
            return requestFuture.thenApply(response -> stripHeadPayload(request, response));
        }
        CancellableResponseFuture result = new CancellableResponseFuture(requestFuture);
        requestFuture.whenComplete((response, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            try {
                result.complete(stripHeadPayload(request, response));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    @Override
    @SneakyThrows
    @SuppressWarnings({"unchecked", "rawtypes"})
    public WebResponse sendAndWait(WebRequest request, WebRequestSettings settings) {
        try {
            WebResponse response = sendRequest(request, settings).get();
            return stripHeadPayload(request, response);
        } catch (InterruptedException e) {
            currentThread().interrupt();
            throw new GatewayException(FluxzeroErrors.threadInterrupted(
                    "the web response", request.getMessageId(),
                    request.getMethod() + " " + WebRequest.getPathForLogging(request.getMetadata())), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new TimeoutException(FluxzeroErrors.requestTimedOut(
                        "web request", request.getMethod() + " " + WebRequest.getPathForLogging(request.getMetadata()),
                        request.getMessageId(), null, MessageType.WEBRESPONSE.name(),
                        settings.getTimeout().plusMillis(5_000L)));
            }
            throw cause;
        }
    }

    @Override
    protected WebRequestGateway createForNamespace(String namespace) {
        GenericGateway namespacedDelegate = delegate.forNamespace(namespace);
        return namespacedDelegate == delegate ? this
                : new DefaultWebRequestGateway(
                        namespacedDelegate, nativeHttpClientFactory, nativeHttpClient, false,
                        defaultRedirectPolicy, metricsGateway == null ? null : metricsGateway.forNamespace(namespace));
    }

    @Override
    public DefaultWebRequestGateway forNamespace(String namespace) {
        return (DefaultWebRequestGateway) super.forNamespace(namespace);
    }

    /**
     * Returns a gateway backed by the given proxy delegate and an independently owned native HTTP client.
     *
     * @param delegate gateway used for proxy-routed requests
     * @return this gateway if the delegate is unchanged, otherwise a gateway with an independent lifecycle
     */
    public DefaultWebRequestGateway withDelegate(GenericGateway delegate) {
        return this.delegate == delegate ? this
                : new DefaultWebRequestGateway(
                        delegate, nativeHttpClientFactory, defaultRedirectPolicy, metricsGateway);
    }

    private WebResponse stripHeadPayload(WebRequest request, WebResponse response) {
        if (response == null || !HttpRequestMethod.HEAD.equals(request.getMethod())) {
            return response;
        }
        return response.withPayload(null);
    }

    private CompletableFuture<?> sendForMessage(WebRequest request, Duration timeout) {
        return delegate.sendForMessage(request, timeout);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CompletableFuture<WebResponse> sendRequest(WebRequest request, WebRequestSettings settings) {
        if (settings.isUseNativeHttpClient()) {
            return nativeHttpClient.get().send(
                    request, settings, defaultRedirectPolicy,
                    metricsGateway == null ? null : this::publishNativeMetric);
        }
        WebRequest webRequest = addSettings(request, settings);
        return (CompletableFuture) sendForMessage(webRequest, responseTimeout(settings));
    }

    private WebRequest addSettings(WebRequest request, WebRequestSettings settings) {
        WebRequestSettings publishedSettings = settings.getRedirectPolicy() == RedirectPolicy.DEFAULT
                ? settings.toBuilder().redirectPolicy(defaultRedirectPolicy).build() : settings;
        return request.withMetadata(request.getMetadata().with("settings", publishedSettings));
    }

    private Duration responseTimeout(WebRequestSettings settings) {
        return settings.getTimeout().plusMillis(5_000L);
    }

    private void publishNativeMetric(NativeWebRequestMetric metric) {
        try {
            metricsGateway.publish(metric, Metadata.empty(), Guarantee.NONE)
                    .exceptionally(error -> {
                        log.debug("Failed to publish native WebRequest metric", error);
                        return null;
                    });
        } catch (Throwable e) {
            log.debug("Failed to publish native WebRequest metric", e);
        }
    }

    private static final class CancellableResponseFuture extends CompletableFuture<WebResponse> {
        private final CompletableFuture<WebResponse> requestFuture;

        private CancellableResponseFuture(CompletableFuture<WebResponse> requestFuture) {
            this.requestFuture = requestFuture;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            requestFuture.cancel(mayInterruptIfRunning);
            return true;
        }
    }

    @Override
    public void close() {
        delegate.close();
        if (nativeHttpClientOwner && nativeHttpClient.isCached()) {
            nativeHttpClient.get().close();
        }
    }
}
