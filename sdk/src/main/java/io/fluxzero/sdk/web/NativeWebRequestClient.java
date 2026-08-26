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

package io.fluxzero.sdk.web;

import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.Serializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

final class NativeWebRequestClient implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final Serializer serializer;
    private final Function<Duration, CompletableFuture<Void>> retryDelay;

    NativeWebRequestClient(Serializer serializer) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                     .connectTimeout(CONNECT_TIMEOUT).build(), serializer);
    }

    NativeWebRequestClient(HttpClient httpClient, Serializer serializer) {
        this(httpClient, serializer, NativeWebRequestClient::delay);
    }

    NativeWebRequestClient(HttpClient httpClient, Serializer serializer,
                           Function<Duration, CompletableFuture<Void>> retryDelay) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.serializer = Objects.requireNonNull(serializer);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    CompletableFuture<WebResponse> send(WebRequest request, WebRequestSettings settings) {
        SerializedMessage serializedRequest = request.serialize(serializer);
        Instant deadline = Instant.now().plus(settings.getTimeout());
        CancellableRequestFuture result = new CancellableRequestFuture();
        send(request, serializedRequest, settings, Math.max(0, settings.getMaxRetries()), deadline, result)
                .whenComplete((response, error) -> {
                    if (error == null) {
                        result.complete(response);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
        return result;
    }

    private CompletableFuture<WebResponse> send(WebRequest request, SerializedMessage serializedRequest,
                                                WebRequestSettings settings, int retriesRemaining, Instant deadline,
                                                CancellableRequestFuture requestFuture) {
        if (requestFuture.isCancelled()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return CompletableFuture.completedFuture(asWebResponse(
                    new HttpTimeoutException("Timeout in native HTTP client")));
        }

        HttpRequest httpRequest;
        try {
            httpRequest = asHttpRequest(request, serializedRequest, settings, remaining);
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(asWebResponse(e));
        }

        CompletableFuture<HttpResponse<byte[]>> attempt;
        try {
            attempt = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(asWebResponse(e));
        }
        requestFuture.track(attempt);

        CompletableFuture<CompletableFuture<WebResponse>> result = attempt.handle((response, error) -> {
            if (error == null) {
                if (shouldRetry(response.statusCode(), settings, retriesRemaining, deadline)) {
                    return retry(request, serializedRequest, settings, retriesRemaining, deadline,
                                 asWebResponse(response), requestFuture);
                }
                return CompletableFuture.completedFuture(asWebResponse(response));
            }
            Throwable failure = unwrap(error);
            if (retriesRemaining > 0 && failure instanceof IOException && Instant.now().isBefore(deadline)) {
                return retry(request, serializedRequest, settings, retriesRemaining, deadline,
                             asWebResponse(failure), requestFuture);
            }
            return CompletableFuture.completedFuture(asWebResponse(failure));
        });
        return result.thenCompose(Function.identity());
    }

    private boolean shouldRetry(int status, WebRequestSettings settings, int retriesRemaining, Instant deadline) {
        return retriesRemaining > 0 && settings.getRetryableStatusCodes().contains(status)
                && Instant.now().isBefore(deadline);
    }

    private CompletableFuture<WebResponse> retry(WebRequest request, SerializedMessage serializedRequest,
                                                 WebRequestSettings settings, int retriesRemaining, Instant deadline,
                                                 WebResponse exhaustedResult,
                                                 CancellableRequestFuture requestFuture) {
        Duration delay = normalizedRetryDelay(settings);
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero() || delay.compareTo(remaining) >= 0) {
            return CompletableFuture.completedFuture(exhaustedResult);
        }
        CompletableFuture<Void> delayFuture = retryDelay.apply(delay);
        requestFuture.track(delayFuture);
        return delayFuture.thenCompose(ignored -> Instant.now().isBefore(deadline)
                ? send(request, serializedRequest, settings, retriesRemaining - 1, deadline, requestFuture)
                : CompletableFuture.completedFuture(exhaustedResult));
    }

    private Duration normalizedRetryDelay(WebRequestSettings settings) {
        Duration delay = settings.getRetryDelay();
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    private static CompletableFuture<Void> delay(Duration duration) {
        if (duration.isZero()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> {}, CompletableFuture.delayedExecutor(duration.toNanos(), NANOSECONDS));
    }

    private HttpRequest asHttpRequest(WebRequest request, SerializedMessage serializedRequest,
                                      WebRequestSettings settings, Duration timeout) {
        URI uri = URI.create(request.getPath());
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Native HTTP requests require an absolute HTTP(S) URL");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.valueOf(settings.getHttpVersion().name()))
                .timeout(timeout);
        request.getHeaders().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.method(request.getMethod(), bodyPublisher(serializedRequest)).build();
    }

    private HttpRequest.BodyPublisher bodyPublisher(SerializedMessage request) {
        byte[] value = request.data().getValue();
        String type = request.data().getType();
        return type == null || Void.class.getName().equals(type) || value.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(value);
    }

    private WebResponse asWebResponse(HttpResponse<byte[]> response) {
        WebResponse.Builder builder = WebResponse.builder();
        response.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.status(response.statusCode()).payload(response.body()).build();
    }

    private WebResponse asWebResponse(Throwable error) {
        String message = error.getMessage() == null ? "Exception while handling native HTTP request"
                : error.getMessage();
        return WebResponse.builder().status(502).payload(message.getBytes(UTF_8)).build();
    }

    private Throwable unwrap(Throwable error) {
        Throwable result = error;
        while (result instanceof CompletionException && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static final class CancellableRequestFuture extends CompletableFuture<WebResponse> {
        private final AtomicReference<CompletableFuture<?>> activeOperation = new AtomicReference<>();

        private void track(CompletableFuture<?> operation) {
            activeOperation.set(operation);
            if (isCancelled()) {
                operation.cancel(true);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            CompletableFuture<?> operation = activeOperation.get();
            if (operation != null) {
                operation.cancel(mayInterruptIfRunning);
            }
            return true;
        }
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
