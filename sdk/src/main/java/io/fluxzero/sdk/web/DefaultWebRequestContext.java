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

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import lombok.Getter;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

/**
 * Default implementation of {@link WebRequestContext} for Fluxzero web request messages.
 * <p>
 * This context exposes the request path, origin, and parameter sources needed by web handler resolution without
 * depending on an external HTTP router implementation.
 */
public class DefaultWebRequestContext implements WebRequestContext {

    /**
     * Returns the current {@code DefaultWebRequestContext} from the active {@link DeserializingMessage},
     * or {@code null} if no such message is available.
     */
    public static DefaultWebRequestContext getCurrentWebRequestContext() {
        return Optional.ofNullable(DeserializingMessage.getCurrent()).map(
                DefaultWebRequestContext::getWebRequestContext).orElse(null);
    }

    /**
     * Creates or retrieves a {@code DefaultWebRequestContext} from the given {@link DeserializingMessage}.
     */
    public static DefaultWebRequestContext getWebRequestContext(DeserializingMessage message) {
        return message.computeContextIfAbsent(DefaultWebRequestContext.class, DefaultWebRequestContext::new);
    }

    private static final Pattern IP_PATTERN = Pattern.compile("[0-9a-fA-F.:]+");

    private final Supplier<byte[]> bodySupplier;
    private final Metadata metadata;
    private final URI uri;
    @Getter
    private final String method;
    @Getter
    private final String requestPath;
    @Getter
    private final String origin;
    @Getter
    private final String remoteAddress;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, String> cookieMap;
    private Map<String, String> pathMap = new LinkedHashMap<>();
    private Map<String, List<String>> formParameters;
    private boolean formParametersParsed;
    private JsonNode jsonBody;
    private boolean jsonBodyParsed;

    DefaultWebRequestContext(DeserializingMessage message) {
        if (message.getMessageType() != MessageType.WEBREQUEST) {
            throw new IllegalArgumentException("Invalid message type: " + message.getMessageType());
        }
        bodySupplier = () -> message.getSerializedObject().getData().getValue();
        metadata = message.getMetadata();
        uri = URI.create(Optional.ofNullable(WebRequest.getUrl(metadata)).orElse(""));
        method = WebRequest.getMethod(metadata);
        requestPath = WebRouteMatcher.normalizePath(uri.getRawPath());
        origin = Optional.ofNullable(uri.getScheme())
                .filter(__ -> uri.getHost() != null)
                .map(scheme -> scheme + "://" + uri.getHost() + Optional.of(uri.getPort())
                        .filter(p -> p >= 0).map(p -> ":" + p).orElse("")).orElse(null);
        queryParameters = parseParameters(uri.getRawQuery());
        cookieMap = WebRequest.getHeaders(metadata).getOrDefault("Cookie", Collections.emptyList())
                .stream().findFirst().map(WebUtils::parseRequestCookieHeader).orElseGet(Collections::emptyList)
                .stream().collect(toMap(HttpCookie::getName, HttpCookie::getValue, (first, ignored) -> first,
                                        LinkedHashMap::new));
        remoteAddress = Stream.of("X-Forwarded-For", "Forwarded", "X-Real-IP")
                .flatMap(h -> WebRequest.getHeader(metadata, h).stream())
                .flatMap(s -> {
                    Matcher matcher = IP_PATTERN.matcher(s);
                    return matcher.find() ? Stream.of(matcher.group()) : Stream.empty();
                })
                .findFirst()
                .orElse("");
    }

    public Map<String, String> pathMap() {
        return pathMap;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public URI getUri() {
        return uri;
    }

    public Map<String, String> getPathMap() {
        return pathMap();
    }

    public Map<String, List<String>> getQueryParameters() {
        return queryParameters;
    }

    public Map<String, String> getCookieMap() {
        return cookieMap;
    }

    public Map<String, List<String>> getFormParameters() {
        return formParameters();
    }

    public JsonNode getJsonBody() {
        return jsonBody();
    }

    public DefaultWebRequestContext setPathMap(Map<String, String> pathMap) {
        this.pathMap = new LinkedHashMap<>(pathMap);
        return this;
    }

    @Override
    public ParameterValue getParameter(String name, WebParameterSource... sources) {
        for (WebParameterSource source : sources) {
            ParameterValue value = switch (source) {
                case PATH -> new ParameterValue(pathMap.get(name));
                case HEADER -> new ParameterValue(firstOrList(WebRequest.getHeaders(metadata).get(name)));
                case COOKIE -> new ParameterValue(cookieMap.get(name));
                case FORM -> new ParameterValue(firstOrList(formParameters().get(name)));
                case QUERY -> new ParameterValue(firstOrList(queryParameters.get(name)));
                case BODY -> ReflectionUtils.readProperty(name, jsonBody())
                        .map(ParameterValue::new).orElseGet(() -> new ParameterValue(null));
            };
            if (value.hasValue()) {
                return value;
            }
        }
        return new ParameterValue(null);
    }

    public boolean matches(String urlPattern) {
        return WebRouteMatcher.matchesPath(urlPattern, requestPath);
    }

    public boolean matchesAny(Collection<String> urlPatterns) {
        return urlPatterns.stream().anyMatch(this::matches);
    }

    private Map<String, List<String>> formParameters() {
        if (!formParametersParsed) {
            formParameters = parseFormParameters(bodySupplier.get());
            formParametersParsed = true;
        }
        return formParameters;
    }

    private JsonNode jsonBody() {
        if (!jsonBodyParsed) {
            jsonBody = parseJsonBody(bodySupplier.get());
            jsonBodyParsed = true;
        }
        return jsonBody;
    }

    JsonNode parseJsonBody(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return JsonUtils.readTree(body);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    Map<String, List<String>> parseFormParameters(byte[] body) {
        String contentType = WebRequest.getHeader(metadata, "Content-Type").map(String::toLowerCase).orElse("");
        if (!contentType.startsWith("application/x-www-form-urlencoded")) {
            return Map.of();
        }
        if (body == null || body.length == 0) {
            return Map.of();
        }
        return parseParameters(new String(body, StandardCharsets.UTF_8));
    }

    private static Object firstOrList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.size() == 1 ? values.getFirst() : values;
    }

    private static Map<String, List<String>> parseParameters(String encodedParameters) {
        if (encodedParameters == null || encodedParameters.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String pair : encodedParameters.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            values.computeIfAbsent(key, __ -> new ArrayList<>()).add(value);
        }
        return values;
    }
}
