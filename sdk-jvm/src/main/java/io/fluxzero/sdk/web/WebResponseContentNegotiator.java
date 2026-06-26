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

package io.fluxzero.sdk.web;

import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies best-effort response content negotiation for automatically mapped web handler results.
 */
final class WebResponseContentNegotiator {
    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    private static final String TEXT_PLAIN = "text/plain";
    private static final Object NEGOTIABLE_RESPONSES_LOCK = new Object();
    private static final ReferenceQueue<WebResponse> NEGOTIABLE_RESPONSE_QUEUE = new ReferenceQueue<>();
    private static final Set<IdentityWeakReference> NEGOTIABLE_RESPONSES = new HashSet<>();

    private WebResponseContentNegotiator() {
    }

    static WebResponse markNegotiable(WebResponse response) {
        synchronized (NEGOTIABLE_RESPONSES_LOCK) {
            pruneNegotiableResponses();
            NEGOTIABLE_RESPONSES.add(new IdentityWeakReference(response, NEGOTIABLE_RESPONSE_QUEUE));
        }
        return response;
    }

    static WebResponse negotiate(WebResponse response) {
        if (!removeNegotiable(response)) {
            return response;
        }
        Object payload = response.getEncodedPayload();
        if (payload == null) {
            return response;
        }
        if (hasNonNegotiableContentType(response, payload)) {
            return response;
        }
        return acceptHeaders()
                .flatMap(headers -> selectContentType(payload, headers))
                .map(contentType -> withContentType(response, contentType))
                .orElse(response);
    }

    private static boolean hasNonNegotiableContentType(WebResponse response, Object payload) {
        return Optional.ofNullable(response.getContentType())
                .map(WebResponseContentNegotiator::mediaType)
                .filter(contentType -> !supportedContentTypes(payload).contains(contentType))
                .isPresent();
    }

    private static Optional<List<String>> acceptHeaders() {
        DefaultWebRequestContext context = DefaultWebRequestContext.getCurrentWebRequestContext();
        if (context == null) {
            return Optional.empty();
        }
        List<String> headers = WebRequest.getHeaders(context.getMetadata()).getOrDefault("Accept", List.of());
        return headers.isEmpty() ? Optional.empty() : Optional.of(headers);
    }

    private static Optional<String> selectContentType(Object payload, List<String> acceptHeaders) {
        List<String> supported = supportedContentTypes(payload);
        return parseAccept(acceptHeaders).stream()
                .sorted(Comparator.<AcceptRange>comparingDouble(AcceptRange::quality).reversed()
                                .thenComparing(Comparator.comparingInt(AcceptRange::specificity).reversed())
                                .thenComparingInt(AcceptRange::order))
                .flatMap(accepted -> supported.stream().filter(accepted::matches))
                .findFirst();
    }

    private static List<String> supportedContentTypes(Object payload) {
        if (payload instanceof byte[] || payload instanceof InputStream) {
            return List.of(APPLICATION_OCTET_STREAM);
        }
        if (payload instanceof String) {
            return List.of(TEXT_PLAIN, APPLICATION_JSON);
        }
        return List.of(APPLICATION_JSON);
    }

    private static WebResponse withContentType(WebResponse response, String contentType) {
        Map<String, List<String>> headers = WebUtils.asHeaderMap(response.getHeaders());
        headers.put("Content-Type", List.of(contentType));
        addVaryAccept(headers);
        return response.withMetadata(response.getMetadata().with(WebResponse.headersKey, headers));
    }

    private static void addVaryAccept(Map<String, List<String>> headers) {
        List<String> vary = new ArrayList<>(headers.getOrDefault("Vary", List.of()));
        boolean alreadyPresent = vary.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch(value -> "Accept".equalsIgnoreCase(value));
        if (!alreadyPresent) {
            vary.add("Accept");
            headers.put("Vary", List.copyOf(vary));
        }
    }

    private static List<AcceptRange> parseAccept(List<String> headers) {
        List<AcceptRange> result = new ArrayList<>();
        int order = 0;
        for (String value : splitAcceptValues(headers)) {
            AcceptRange range = parseAcceptRange(value, order++);
            if (range.quality() > 0) {
                result.add(range);
            }
        }
        return result;
    }

    private static List<String> splitAcceptValues(List<String> headers) {
        List<String> result = new ArrayList<>();
        for (String header : headers) {
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            boolean escaped = false;
            for (int i = 0; i < header.length(); i++) {
                char c = header.charAt(i);
                if (escaped) {
                    current.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\' && quoted) {
                    current.append(c);
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    quoted = !quoted;
                    current.append(c);
                    continue;
                }
                if (c == ',' && !quoted) {
                    addAcceptValue(result, current);
                    continue;
                }
                current.append(c);
            }
            addAcceptValue(result, current);
        }
        return result;
    }

    private static void addAcceptValue(List<String> result, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            result.add(value);
        }
        current.setLength(0);
    }

    private static AcceptRange parseAcceptRange(String value, int order) {
        String[] segments = value.split(";");
        String mediaRange = segments[0].trim().toLowerCase(Locale.ROOT);
        int separator = mediaRange.indexOf('/');
        if (separator <= 0 || separator == mediaRange.length() - 1) {
            return new AcceptRange("*", "*", 0, order);
        }
        double quality = 1.0;
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i].trim();
            int equals = segment.indexOf('=');
            if (equals > 0 && "q".equalsIgnoreCase(segment.substring(0, equals).trim())) {
                quality = quality(segment.substring(equals + 1).trim());
            }
        }
        return new AcceptRange(
                mediaRange.substring(0, separator),
                mediaRange.substring(separator + 1),
                quality,
                order);
    }

    private static double quality(String value) {
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record AcceptRange(String type, String subtype, double quality, int order) {
        boolean matches(String contentType) {
            String normalized = mediaType(contentType);
            int separator = normalized.indexOf('/');
            if (separator <= 0 || separator == normalized.length() - 1) {
                return false;
            }
            String contentTypeType = normalized.substring(0, separator);
            String contentTypeSubtype = normalized.substring(separator + 1);
            return ("*".equals(type) || type.equals(contentTypeType))
                   && ("*".equals(subtype) || subtype.equals(contentTypeSubtype));
        }

        int specificity() {
            if ("*".equals(type)) {
                return 0;
            }
            return "*".equals(subtype) ? 1 : 2;
        }
    }

    private static String mediaType(String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        int parameterStart = normalized.indexOf(';');
        if (parameterStart >= 0) {
            normalized = normalized.substring(0, parameterStart);
        }
        return normalized.trim();
    }

    private static boolean removeNegotiable(WebResponse response) {
        synchronized (NEGOTIABLE_RESPONSES_LOCK) {
            pruneNegotiableResponses();
            return NEGOTIABLE_RESPONSES.remove(new IdentityWeakReference(response));
        }
    }

    private static void pruneNegotiableResponses() {
        Reference<? extends WebResponse> reference;
        while ((reference = NEGOTIABLE_RESPONSE_QUEUE.poll()) != null) {
            NEGOTIABLE_RESPONSES.remove(reference);
        }
    }

    private static final class IdentityWeakReference extends WeakReference<WebResponse> {
        private final int hashCode;

        private IdentityWeakReference(WebResponse referent) {
            super(referent);
            this.hashCode = System.identityHashCode(referent);
        }

        private IdentityWeakReference(WebResponse referent, ReferenceQueue<WebResponse> queue) {
            super(referent, queue);
            this.hashCode = System.identityHashCode(referent);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityWeakReference reference && get() == reference.get();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
