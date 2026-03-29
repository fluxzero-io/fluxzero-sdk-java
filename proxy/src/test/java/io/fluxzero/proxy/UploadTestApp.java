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

package io.fluxzero.proxy;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.web.FormParam;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.MultipartFormPart;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.testserver.TestServer;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

/**
 * Small runnable app for manually testing end-to-end proxied web uploads against a local runtime.
 * <p>
 * By default it connects to {@code ws://localhost:8888}, registers a few {@code POST} handlers for different payload
 * shapes, and keeps running until the process is stopped. If no local runtime is reachable on port {@code 8888}, it
 * starts a {@link TestServer}. If no proxy is reachable on port {@code 8080}, it starts a local {@link ProxyServer}
 * wired to the same client connection.
 */
@Slf4j
public class UploadTestApp {
    private static final String DEFAULT_ENDPOINT = "ws://localhost:8888";
    private static final int DEFAULT_RUNTIME_PORT = 8888;
    private static final int DEFAULT_PROXY_PORT = 8080;
    private static final long STREAM_PROGRESS_LOG_INTERVAL_BYTES = 128L << 20;
    private static final String DEFAULT_PROXY_MAX_REQUEST_BODY_SIZE = String.valueOf(16L << 30);
    private static final String DEFAULT_PROXY_MAX_MULTIPART_REQUEST_BODY_SIZE = String.valueOf(16L << 30);

    public static void main(String[] args) throws InterruptedException {
        String endpoint = System.getProperty("endpoint.messaging", DEFAULT_ENDPOINT);
        String clientName = System.getProperty("fluxzero.client.name", "upload-test-app");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

        if (shouldManageLocalRuntime(endpoint) && !isHealthy(httpClient,
                                                             "http://localhost:" + DEFAULT_RUNTIME_PORT + "/health")) {
            log.info("No Fluxzero test server detected on port {}, starting one", DEFAULT_RUNTIME_PORT);
            TestServer.start(DEFAULT_RUNTIME_PORT);
        }

        WebSocketClient client = WebSocketClient.newInstance(
                WebSocketClient.ClientConfig.builder()
                        .name(clientName)
                        .runtimeBaseUrl(endpoint)
                        .build());

        ProxyServer proxyServer = null;
        if (shouldManageLocalRuntime(endpoint)
            && !isHealthy(httpClient, "http://localhost:" + DEFAULT_PROXY_PORT + "/proxy/health")) {
            log.info("No Fluxzero proxy detected on port {}, starting one", DEFAULT_PROXY_PORT);
            configureLocalProxyLimits();
            proxyServer = ProxyServer.start(DEFAULT_PROXY_PORT, new ProxyRequestHandler(client));
        }

        Fluxzero fluxzero = DefaultFluxzero.builder().build(client);
        fluxzero.registerHandlers(new UploadHandler());
        ProxyServer startedProxy = proxyServer;

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("upload-test-app-shutdown").unstarted(() -> {
            log.info("Shutting down upload test app");
            fluxzero.close();
            if (startedProxy != null) {
                startedProxy.cancel();
            }
        }));

        log.info("Upload test app connected to {}", endpoint);
        log.info("Ready to handle:");
        log.info("  GET  /");
        log.info("  POST /upload            (byte[])");
        log.info("  POST /upload/stream     (InputStream)");
        log.info("  POST /upload/text       (String)");
        log.info("  POST /upload/form       (object-bound form body)");
        log.info("  POST /upload/form-field (@FormParam)");
        log.info("  POST /upload/multipart  (MultipartFormPart)");
        new CountDownLatch(1).await();
    }

    public static class UploadHandler {
        @HandleGet("/")
        String index() {
            return "upload-test-app ready";
        }

        @HandlePost("/upload")
        String upload(byte[] payload) {
            log.info("Received upload of {} bytes", payload.length);
            return "received " + payload.length + " bytes";
        }

        @HandlePost("/upload/stream")
        String uploadStream(InputStream payload, WebRequest request) throws Exception {
            Long expectedBytes = parseContentLength(request.getHeader("Content-Length"));
            Instant startedAt = Instant.now();
            log.info("Started receiving stream upload{}", expectedBytes == null ? "" :
                    " (" + formatMiB(expectedBytes) + " MiB expected)");
            byte[] buffer = new byte[64 * 1024];
            long totalBytes = 0;
            long nextLogAt = STREAM_PROGRESS_LOG_INTERVAL_BYTES;
            while (true) {
                int read = payload.read(buffer);
                if (read < 0) {
                    break;
                }
                totalBytes += read;
                if (totalBytes >= nextLogAt) {
                    logProgress(totalBytes, expectedBytes, startedAt);
                    nextLogAt += STREAM_PROGRESS_LOG_INTERVAL_BYTES;
                }
            }
            logCompleted(totalBytes, expectedBytes, startedAt);
            return "received stream of " + totalBytes + " bytes";
        }

        @HandlePost("/upload/text")
        String uploadText(String payload) {
            log.info("Received text upload of {} chars", payload.length());
            return "received text: " + payload;
        }

        @HandlePost("/upload/form")
        String uploadForm(UploadForm form) {
            log.info("Received form upload for {} ({})", form.getName(), form.getDescription());
            return form.getName() + "|" + form.getDescription();
        }

        @HandlePost("/upload/form-field")
        String uploadFormField(@FormParam("name") String name, @FormParam("description") String description) {
            log.info("Received form fields for {} ({})", name, description);
            return name + "|" + description;
        }

        @HandlePost("/upload/multipart")
        String uploadMultipart(@FormParam("document") MultipartFormPart document) {
            String contents = document.asString(StandardCharsets.ISO_8859_1);
            log.info("Received multipart file {} with {} bytes", document.getFilename(), document.getBytes().length);
            return document.getFilename() + "|" + document.getContentType() + "|" + contents;
        }
    }

    @Value
    public static class UploadForm {
        String name;
        String description;
    }

    private static boolean shouldManageLocalRuntime(String endpoint) {
        return DEFAULT_ENDPOINT.equals(endpoint);
    }

    private static boolean isHealthy(HttpClient httpClient, String url) {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void configureLocalProxyLimits() {
        setPropertyIfMissing("FLUXZERO_PROXY_LOG_CHUNK_DISPATCHES", "false");
        setPropertyIfMissing("FLUXZERO_PROXY_MAX_REQUEST_BODY_SIZE", DEFAULT_PROXY_MAX_REQUEST_BODY_SIZE);
        setPropertyIfMissing("FLUXZERO_PROXY_MAX_MULTIPART_REQUEST_BODY_SIZE",
                             DEFAULT_PROXY_MAX_MULTIPART_REQUEST_BODY_SIZE);
    }

    private static void setPropertyIfMissing(String property, String value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, value);
            log.info("Using {}={} for local upload testing", property, value);
        }
    }

    private static void logProgress(long totalBytes, Long expectedBytes, Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, Instant.now());
        String throughput = formatThroughput(totalBytes, elapsed);
        if (expectedBytes == null || expectedBytes <= 0) {
            log.info("Receiving stream upload progress: {} MiB after {}, avg {}",
                     formatMiB(totalBytes), formatDuration(elapsed), throughput);
            return;
        }
        double progress = Math.min(1d, totalBytes / (double) expectedBytes);
        Duration remaining = estimateRemaining(elapsed, progress);
        log.info("Receiving stream upload progress: {} MiB / {} MiB ({}%) after {}, avg {}, remaining {}",
                 formatMiB(totalBytes), formatMiB(expectedBytes), Math.round(progress * 100),
                 formatDuration(elapsed), throughput, formatDuration(remaining));
    }

    private static void logCompleted(long totalBytes, Long expectedBytes, Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, Instant.now());
        String throughput = formatThroughput(totalBytes, elapsed);
        if (expectedBytes == null || expectedBytes <= 0) {
            log.info("Received stream upload of {} bytes after {}, avg {}", totalBytes, formatDuration(elapsed),
                     throughput);
            return;
        }
        log.info("Received stream upload of {} bytes ({} MiB / {} MiB, {}%) after {}, avg {}",
                 totalBytes, formatMiB(totalBytes), formatMiB(expectedBytes),
                 Math.round(Math.min(1d, totalBytes / (double) expectedBytes) * 100),
                 formatDuration(elapsed), throughput);
    }

    private static Duration estimateRemaining(Duration elapsed, double progress) {
        if (progress <= 0d || progress >= 1d) {
            return Duration.ZERO;
        }
        long estimatedTotalMillis = Math.round(elapsed.toMillis() / progress);
        return Duration.ofMillis(Math.max(0L, estimatedTotalMillis - elapsed.toMillis()));
    }

    private static Long parseContentLength(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long formatMiB(long bytes) {
        return bytes >> 20;
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes > 0 ? minutes + "m" + remainingSeconds + "s" : remainingSeconds + "s";
    }

    private static String formatThroughput(long totalBytes, Duration elapsed) {
        long millis = Math.max(1L, elapsed.toMillis());
        double mibPerSecond = (totalBytes / 1024d / 1024d) / (millis / 1000d);
        return String.format("%.1f MiB/s", mibPerSecond);
    }
}
