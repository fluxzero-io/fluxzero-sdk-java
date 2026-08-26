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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.publishing.WebRequestGateway;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import lombok.experimental.Delegate;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class FixtureWebRequestGateway implements WebRequestGateway {

    @Delegate
    private final WebRequestGateway delegate;
    private final Map<WebRequestGateway, FixtureWebRequestGateway> gateways;

    FixtureWebRequestGateway(WebRequestGateway delegate) {
        this(delegate, new IdentityHashMap<>());
        gateways.put(delegate, this);
    }

    private FixtureWebRequestGateway(WebRequestGateway delegate,
                                     Map<WebRequestGateway, FixtureWebRequestGateway> gateways) {
        this.delegate = delegate;
        this.gateways = gateways;
    }

    @Override
    public CompletableFuture<WebResponse> send(WebRequest request, WebRequestSettings settings) {
        int retries = Math.max(0, settings.getMaxRetries());
        WebRequestSettings fixtureSettings = fixtureSettings(settings);
        CompletableFuture<WebResponse> result = delegate.send(request, fixtureSettings);
        for (int i = 0; i < retries; i++) {
            result = result.thenCompose(response -> settings.getRetryableStatusCodes().contains(response.getStatus())
                    ? delegate.send(request, fixtureSettings)
                    : CompletableFuture.completedFuture(response));
        }
        return result;
    }

    @Override
    public WebResponse sendAndWait(WebRequest request, WebRequestSettings settings) {
        int retries = Math.max(0, settings.getMaxRetries());
        WebRequestSettings fixtureSettings = fixtureSettings(settings);
        WebResponse result = delegate.sendAndWait(request, fixtureSettings);
        while (retries-- > 0 && settings.getRetryableStatusCodes().contains(result.getStatus())) {
            result = delegate.sendAndWait(request, fixtureSettings);
        }
        return result;
    }

    @Override
    public WebRequestGateway forNamespace(String namespace) {
        WebRequestGateway namespaced = delegate.forNamespace(namespace);
        synchronized (gateways) {
            FixtureWebRequestGateway result = gateways.get(namespaced);
            if (result == null) {
                result = new FixtureWebRequestGateway(namespaced, gateways);
                gateways.put(namespaced, result);
            }
            return result;
        }
    }

    private WebRequestSettings fixtureSettings(WebRequestSettings settings) {
        return settings.toBuilder()
                .useNativeHttpClient(false)
                .maxRetries(0)
                .retryDelay(Duration.ZERO)
                .build();
    }
}
