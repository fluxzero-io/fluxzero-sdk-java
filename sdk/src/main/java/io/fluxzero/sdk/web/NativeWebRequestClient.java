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

import io.fluxzero.common.MemoizingSupplier;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.Serializer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.CANCELLED;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.CONNECTION;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.INVALID_REQUEST;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.IO;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.OTHER;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.SECURITY;
import static io.fluxzero.sdk.web.NativeWebRequestMetric.ErrorCategory.TIMEOUT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

final class NativeWebRequestClient implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_REDIRECTS = 5;

    private final MemoizingSupplier<HttpClient> redirectingHttpClient;
    private final MemoizingSupplier<HttpClient> nonRedirectingHttpClient;
    private final Serializer serializer;
    private final Function<Duration, CompletableFuture<Void>> retryDelay;

    NativeWebRequestClient(Serializer serializer) {
        this(() -> newHttpClient(HttpClient.Redirect.NORMAL), () -> newHttpClient(HttpClient.Redirect.NEVER),
             serializer, NativeWebRequestClient::delay);
    }

    NativeWebRequestClient(HttpClient httpClient, Serializer serializer) {
        this(() -> httpClient, () -> httpClient, serializer, NativeWebRequestClient::delay);
    }

    NativeWebRequestClient(HttpClient httpClient, Serializer serializer,
                           Function<Duration, CompletableFuture<Void>> retryDelay) {
        this(() -> httpClient, () -> httpClient, serializer, retryDelay);
    }

    NativeWebRequestClient(HttpClient redirectingHttpClient, HttpClient nonRedirectingHttpClient,
                           Serializer serializer, Function<Duration, CompletableFuture<Void>> retryDelay) {
        this(() -> redirectingHttpClient, () -> nonRedirectingHttpClient, serializer, retryDelay);
        this.redirectingHttpClient.get();
        this.nonRedirectingHttpClient.get();
    }

    private NativeWebRequestClient(Supplier<HttpClient> redirectingHttpClient,
                                   Supplier<HttpClient> nonRedirectingHttpClient,
                                   Serializer serializer, Function<Duration, CompletableFuture<Void>> retryDelay) {
        this.redirectingHttpClient = memoize(() -> Objects.requireNonNull(redirectingHttpClient.get()));
        this.nonRedirectingHttpClient = memoize(() -> Objects.requireNonNull(nonRedirectingHttpClient.get()));
        this.serializer = Objects.requireNonNull(serializer);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    private static HttpClient newHttpClient(HttpClient.Redirect redirectPolicy) {
        return HttpClient.newBuilder().followRedirects(redirectPolicy).connectTimeout(CONNECT_TIMEOUT).build();
    }

    CompletableFuture<WebResponse> send(WebRequest request, WebRequestSettings settings) {
        return send(request, settings, RedirectPolicy.ALLOW, null);
    }

    CompletableFuture<WebResponse> send(WebRequest request, WebRequestSettings settings,
                                        RedirectPolicy defaultRedirectPolicy,
                                        BiConsumer<NativeWebRequestMetric, String> metricConsumer) {
        SerializedMessage serializedRequest = request.serialize(serializer);
        Instant deadline = Instant.now().plus(settings.getTimeout());
        RedirectPolicy redirectPolicy = settings.getRedirectPolicy() == RedirectPolicy.DEFAULT
                ? defaultRedirectPolicy : settings.getRedirectPolicy();
        if (redirectPolicy == RedirectPolicy.DEFAULT) {
            throw new IllegalArgumentException("Default redirect policy must be resolved before execution");
        }
        MetricState metricState = metricConsumer == null ? null
                : new MetricState(request.getMethod(), normalizedTarget(request.getPath()), metricConsumer);
        CancellableRequestFuture result = new CancellableRequestFuture(metricState);
        send(request, serializedRequest, settings, redirectPolicy, Math.max(0, settings.getMaxRetries()),
             deadline, result, metricState).whenComplete((outcome, error) -> {
            long completedNanos = System.nanoTime();
            if (error == null) {
                if (metricState != null) {
                    metricState.terminalOutcome.set(outcome);
                }
                if (result.complete(outcome.response())) {
                    if (metricState != null) {
                        metricState.completedNanos.set(completedNanos);
                        metricState.publish(null);
                    }
                }
            } else {
                if (result.completeExceptionally(error)) {
                    if (metricState != null) {
                        metricState.completedNanos.set(completedNanos);
                        metricState.publish(error);
                    }
                }
            }
        });
        return result;
    }

    private CompletableFuture<RequestOutcome> send(
            WebRequest request, SerializedMessage serializedRequest, WebRequestSettings settings,
            RedirectPolicy redirectPolicy, int retriesRemaining, Instant deadline,
            CancellableRequestFuture requestFuture, MetricState metricState) {
        if (requestFuture.isCancelled()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return CompletableFuture.completedFuture(failureOutcome(
                    new HttpTimeoutException("Timeout in native HTTP client")));
        }

        HttpRequest httpRequest;
        try {
            httpRequest = asHttpRequest(request, serializedRequest, settings, remaining);
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(failureOutcome(e));
        }

        if (metricState != null) {
            metricState.attempts.incrementAndGet();
        }
        return executeAttempt(httpRequest, redirectPolicy, deadline, requestFuture, metricState)
                .thenCompose(outcome -> {
                    if (outcome.failure() == null) {
                        if (shouldRetry(outcome.status(), settings, retriesRemaining, deadline)) {
                            return retry(request, serializedRequest, settings, redirectPolicy, retriesRemaining,
                                         deadline, outcome, requestFuture, metricState);
                        }
                        return CompletableFuture.completedFuture(outcome);
                    }
                    if (retriesRemaining > 0 && outcome.failure() instanceof IOException
                            && Instant.now().isBefore(deadline)) {
                        return retry(request, serializedRequest, settings, redirectPolicy, retriesRemaining,
                                     deadline, outcome, requestFuture, metricState);
                    }
                    return CompletableFuture.completedFuture(outcome);
                });
    }

    private CompletableFuture<RequestOutcome> executeAttempt(
            HttpRequest request, RedirectPolicy redirectPolicy, Instant deadline,
            CancellableRequestFuture requestFuture, MetricState metricState) {
        return switch (redirectPolicy) {
            case ALLOW -> sendOnce(redirectingHttpClient.get(), request, requestFuture).thenApply(raw -> {
                if (metricState != null && raw.response() != null && hasRedirectLocation(raw.response())) {
                    metricState.redirectRejected.set(true);
                }
                return asOutcome(raw);
            });
            case NEVER -> sendOnce(nonRedirectingHttpClient.get(), request, requestFuture).thenApply(raw -> {
                if (metricState != null && raw.response() != null && hasRedirectLocation(raw.response())) {
                    metricState.redirectRejected.set(true);
                }
                return asOutcome(raw);
            });
            case SAME_ORIGIN -> followSameOrigin(
                    request, request.uri(), deadline, requestFuture, metricState, 0).thenApply(this::asOutcome);
            case DEFAULT -> throw new IllegalStateException("Redirect policy must be resolved before execution");
        };
    }

    private CompletableFuture<RawOutcome> followSameOrigin(
            HttpRequest request, URI origin, Instant deadline, CancellableRequestFuture requestFuture,
            MetricState metricState, int redirects) {
        return sendOnce(nonRedirectingHttpClient.get(), request, requestFuture).thenCompose(outcome -> {
            if (outcome.response() == null || !hasRedirectLocation(outcome.response())) {
                return CompletableFuture.completedFuture(outcome);
            }
            Optional<URI> target = redirectTarget(outcome.response(), request.uri());
            if (target.isEmpty() || redirects >= MAX_REDIRECTS || !sameOrigin(origin, target.get())) {
                if (metricState != null) {
                    metricState.redirectRejected.set(true);
                }
                return CompletableFuture.completedFuture(outcome);
            }
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                return CompletableFuture.completedFuture(new RawOutcome(
                        null, new HttpTimeoutException("Timeout in native HTTP client")));
            }
            HttpRequest redirected;
            try {
                redirected = redirectedRequest(request, target.get(), outcome.response().statusCode(), remaining);
            } catch (Throwable e) {
                return CompletableFuture.completedFuture(new RawOutcome(null, e));
            }
            return followSameOrigin(
                    redirected, origin, deadline, requestFuture, metricState, redirects + 1);
        });
    }

    private CompletableFuture<RawOutcome> sendOnce(
            HttpClient httpClient, HttpRequest request, CancellableRequestFuture requestFuture) {
        CompletableFuture<HttpResponse<byte[]>> attempt;
        try {
            attempt = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(new RawOutcome(null, e));
        }
        requestFuture.track(attempt);
        return attempt.handle((response, error) -> new RawOutcome(
                response, error == null ? null : unwrap(error)));
    }

    private boolean shouldRetry(Integer status, WebRequestSettings settings,
                                int retriesRemaining, Instant deadline) {
        return status != null && retriesRemaining > 0 && settings.getRetryableStatusCodes().contains(status)
               && Instant.now().isBefore(deadline);
    }

    private CompletableFuture<RequestOutcome> retry(
            WebRequest request, SerializedMessage serializedRequest, WebRequestSettings settings,
            RedirectPolicy redirectPolicy, int retriesRemaining, Instant deadline, RequestOutcome exhaustedResult,
            CancellableRequestFuture requestFuture, MetricState metricState) {
        Duration delay = normalizedRetryDelay(settings);
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero() || delay.compareTo(remaining) >= 0) {
            return CompletableFuture.completedFuture(exhaustedResult);
        }
        CompletableFuture<Void> delayFuture = retryDelay.apply(delay);
        requestFuture.track(delayFuture);
        return delayFuture.thenCompose(ignored -> Instant.now().isBefore(deadline)
                ? send(request, serializedRequest, settings, redirectPolicy, retriesRemaining - 1,
                       deadline, requestFuture, metricState)
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
        if (!isHttpUri(uri)) {
            throw new IllegalArgumentException("Native HTTP requests require an absolute HTTP(S) URL");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.valueOf(settings.getHttpVersion().name()))
                .timeout(timeout);
        request.getHeaders().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.method(request.getMethod(), bodyPublisher(serializedRequest)).build();
    }

    private HttpRequest redirectedRequest(HttpRequest request, URI target, int status, Duration timeout) {
        boolean switchToGet = status == 303 && !"HEAD".equalsIgnoreCase(request.method())
                              || (status == 301 || status == 302) && "POST".equalsIgnoreCase(request.method());
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .version(request.version().orElse(HttpClient.Version.HTTP_2)).timeout(timeout);
        request.headers().map().forEach(
                (name, values) -> values.forEach(value -> builder.header(name, value)));
        if (switchToGet) {
            return builder.GET().build();
        }
        return builder.method(
                request.method(), request.bodyPublisher().orElseGet(HttpRequest.BodyPublishers::noBody)).build();
    }

    private HttpRequest.BodyPublisher bodyPublisher(SerializedMessage request) {
        byte[] value = request.data().getValue();
        String type = request.data().getType();
        return type == null || Void.class.getName().equals(type) || value.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(value);
    }

    private boolean hasRedirectLocation(HttpResponse<?> response) {
        return isRedirect(response.statusCode()) && response.headers().firstValue("Location").isPresent();
    }

    private Optional<URI> redirectTarget(HttpResponse<?> response, URI source) {
        try {
            URI target = source.resolve(response.headers().firstValue("Location").orElseThrow());
            return isHttpUri(target) ? Optional.of(target) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isHttpUri(URI uri) {
        return uri.isAbsolute() && uri.getHost() != null
               && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    static boolean sameOrigin(URI first, URI second) {
        return first.getScheme() != null && second.getScheme() != null
               && first.getHost() != null && second.getHost() != null
               && first.getScheme().equalsIgnoreCase(second.getScheme())
               && first.getHost().equalsIgnoreCase(second.getHost())
               && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static RequestTarget normalizedTarget(String url) {
        try {
            URI uri = URI.create(url);
            return isHttpUri(uri)
                    ? new RequestTarget(uri.getScheme().toLowerCase(Locale.ROOT),
                                        uri.getHost().toLowerCase(Locale.ROOT), effectivePort(uri), rawPath(uri),
                                        uri.getRawQuery())
                    : RequestTarget.unknown();
        } catch (RuntimeException ignored) {
            return RequestTarget.unknown();
        }
    }

    private static String rawPath(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private RequestOutcome asOutcome(RawOutcome outcome) {
        return outcome.failure() == null
                ? new RequestOutcome(asWebResponse(outcome.response()), outcome.response().statusCode(), null)
                : failureOutcome(outcome.failure());
    }

    private RequestOutcome failureOutcome(Throwable failure) {
        return new RequestOutcome(asWebResponse(failure), null, failure);
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

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while (result instanceof CompletionException && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static NativeWebRequestMetric.ErrorCategory errorCategory(Throwable error) {
        Throwable failure = unwrap(error);
        if (failure instanceof CancellationException) {
            return CANCELLED;
        }
        if (failure instanceof HttpTimeoutException || failure instanceof java.net.SocketTimeoutException) {
            return TIMEOUT;
        }
        if (failure instanceof ConnectException) {
            return CONNECTION;
        }
        if (failure instanceof IOException) {
            return IO;
        }
        if (failure instanceof IllegalArgumentException) {
            return INVALID_REQUEST;
        }
        if (failure instanceof SecurityException) {
            return SECURITY;
        }
        return OTHER;
    }

    @Override
    public void close() {
        try {
            if (redirectingHttpClient.isCached()) {
                redirectingHttpClient.get().close();
            }
        } finally {
            if (nonRedirectingHttpClient.isCached()) {
                HttpClient client = nonRedirectingHttpClient.get();
                if (!redirectingHttpClient.isCached() || client != redirectingHttpClient.get()) {
                    client.close();
                }
            }
        }
    }

    private record RawOutcome(HttpResponse<byte[]> response, Throwable failure) {
    }

    private record RequestOutcome(WebResponse response, Integer status, Throwable failure) {
    }

    private record RequestTarget(String scheme, String hostname, Integer port, String path, String query) {
        private static RequestTarget unknown() {
            return new RequestTarget(null, null, null, null, null);
        }
    }

    private static final class MetricState {
        private final String method;
        private final RequestTarget target;
        private final BiConsumer<NativeWebRequestMetric, String> consumer;
        private final long startNanos = System.nanoTime();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicBoolean redirectRejected = new AtomicBoolean();
        private final AtomicLong completedNanos = new AtomicLong();
        private final AtomicReference<RequestOutcome> terminalOutcome = new AtomicReference<>();

        private MetricState(String method, RequestTarget target,
                            BiConsumer<NativeWebRequestMetric, String> consumer) {
            this.method = method;
            this.target = target;
            this.consumer = consumer;
        }

        private void publish(Throwable error) {
            if (consumer == null) {
                return;
            }
            RequestOutcome outcome = terminalOutcome.get();
            boolean cancelled = error instanceof CancellationException;
            Throwable failure = cancelled ? error : outcome == null ? error : outcome.failure();
            NativeWebRequestMetric.ErrorCategory category = failure == null ? null : errorCategory(failure);
            try {
                long endNanos = completedNanos.get();
                consumer.accept(new NativeWebRequestMetric(
                        method, target.scheme(), target.hostname(), target.port(), target.path(),
                        category == null && outcome != null ? outcome.status() : null, category,
                        (endNanos == 0L ? System.nanoTime() : endNanos) - startNanos, attempts.get(), cancelled,
                        redirectRejected.get()), target.query());
            } catch (Throwable ignored) {
                //Metrics must never affect request completion.
            }
        }
    }

    private static final class CancellableRequestFuture extends CompletableFuture<WebResponse> {
        private final AtomicReference<CompletableFuture<?>> activeOperation = new AtomicReference<>();
        private final MetricState metricState;

        private CancellableRequestFuture(MetricState metricState) {
            this.metricState = metricState;
        }

        private void track(CompletableFuture<?> operation) {
            activeOperation.set(operation);
            if (isCancelled()) {
                operation.cancel(true);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            long completedNanos = System.nanoTime();
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            CompletableFuture<?> operation = activeOperation.get();
            if (operation != null) {
                operation.cancel(mayInterruptIfRunning);
            }
            if (metricState != null) {
                metricState.completedNanos.set(completedNanos);
                metricState.publish(new CancellationException());
            }
            return true;
        }
    }
}
