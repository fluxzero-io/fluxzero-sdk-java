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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private Map<String, List<Object>> formParameterValues;
    private Map<String, List<String>> formParameters;
    private Map<String, List<WebFormPart>> formParts;
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

    public Map<String, List<WebFormPart>> getFormParts() {
        return formParts();
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
                case FORM -> new ParameterValue(firstOrListObject(formParameterValues().get(name)));
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
        formParameterValues();
        return formParameters;
    }

    private Map<String, List<WebFormPart>> formParts() {
        formParameterValues();
        return formParts;
    }

    private Map<String, List<Object>> formParameterValues() {
        if (!formParametersParsed) {
            ParsedForm parsedForm = parseForm(bodySupplier.get());
            formParameterValues = parsedForm.parameterValues();
            formParameters = parsedForm.parameters();
            formParts = parsedForm.parts();
            formParametersParsed = true;
        }
        return formParameterValues;
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
        return parseForm(body).parameters();
    }

    ParsedForm parseForm(byte[] body) {
        String contentType = WebRequest.getHeader(metadata, "Content-Type").orElse("");
        if (body == null || body.length == 0) {
            return ParsedForm.empty();
        }
        if ("application/x-www-form-urlencoded".equals(mediaType(contentType))) {
            Map<String, List<String>> parameters = parseParameters(new String(body, StandardCharsets.UTF_8));
            return ParsedForm.ofUrlEncoded(parameters);
        }
        if ("multipart/form-data".equals(mediaType(contentType))) {
            return boundary(contentType).map(boundary -> ParsedForm.ofMultipart(parseMultipart(body, boundary)))
                    .orElseGet(ParsedForm::empty);
        }
        return ParsedForm.empty();
    }

    private static Object firstOrList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.size() == 1 ? values.getFirst() : values;
    }

    private static Object firstOrListObject(List<Object> values) {
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

    private static Optional<String> boundary(String contentType) {
        return Optional.ofNullable(headerParameters(contentType).get("boundary")).filter(s -> !s.isBlank());
    }

    static Map<String, String> headerParameters(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : splitHeaderParameters(headerValue)) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = unquote(part.substring(separator + 1).trim());
            if (!key.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static String mediaType(String contentType) {
        int parameterStart = Optional.ofNullable(contentType).orElse("").indexOf(';');
        String result = parameterStart < 0 ? Optional.ofNullable(contentType).orElse("")
                : contentType.substring(0, parameterStart);
        return result.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> splitHeaderParameters(String headerValue) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < headerValue.length(); i++) {
            char c = headerValue.charAt(i);
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
            if (c == ';' && !quoted) {
                result.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        result.add(current.toString().trim());
        return result;
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (escaped) {
                result.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                result.append(c);
            }
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    private static List<WebFormPart> parseMultipart(byte[] body, String boundary) {
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int position = raw.indexOf(delimiter);
        if (position < 0) {
            return List.of();
        }

        List<WebFormPart> parts = new ArrayList<>();
        while (position >= 0) {
            position += delimiter.length();
            if (raw.startsWith("--", position)) {
                break;
            }
            position = skipLineBreak(raw, position);

            Separator headerSeparator = findHeaderSeparator(raw, position);
            if (headerSeparator == null) {
                throw new IllegalArgumentException("Malformed multipart/form-data request: part headers are incomplete");
            }
            Map<String, List<String>> headers = parsePartHeaders(raw.substring(position, headerSeparator.index()));
            int bodyStart = headerSeparator.index() + headerSeparator.length();
            BoundaryPosition nextBoundary = findNextBoundary(raw, delimiter, bodyStart);
            byte[] content = Arrays.copyOfRange(body, bodyStart, nextBoundary.dataEnd());

            Optional<WebFormPart> part = createPart(headers, content);
            part.ifPresent(parts::add);
            position = nextBoundary.delimiterStart();
        }
        return parts;
    }

    private static int skipLineBreak(String raw, int position) {
        if (raw.startsWith("\r\n", position)) {
            return position + 2;
        }
        if (raw.startsWith("\n", position)) {
            return position + 1;
        }
        return position;
    }

    private static Separator findHeaderSeparator(String raw, int start) {
        int crlf = raw.indexOf("\r\n\r\n", start);
        int lf = raw.indexOf("\n\n", start);
        if (crlf < 0 && lf < 0) {
            return null;
        }
        if (lf < 0 || (crlf >= 0 && crlf < lf)) {
            return new Separator(crlf, 4);
        }
        return new Separator(lf, 2);
    }

    private static BoundaryPosition findNextBoundary(String raw, String delimiter, int start) {
        int delimiterStart = raw.indexOf(delimiter, start);
        while (delimiterStart >= 0) {
            if (delimiterStart >= 2 && raw.charAt(delimiterStart - 2) == '\r'
                && raw.charAt(delimiterStart - 1) == '\n') {
                return new BoundaryPosition(delimiterStart - 2, delimiterStart);
            }
            if (delimiterStart >= 1 && raw.charAt(delimiterStart - 1) == '\n') {
                return new BoundaryPosition(delimiterStart - 1, delimiterStart);
            }
            delimiterStart = raw.indexOf(delimiter, delimiterStart + delimiter.length());
        }
        throw new IllegalArgumentException("Malformed multipart/form-data request: closing boundary is missing");
    }

    private static Map<String, List<String>> parsePartHeaders(String headerBlock) {
        Map<String, List<String>> headers = WebUtils.emptyHeaderMap();
        for (String line : headerBlock.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            headers.computeIfAbsent(name, __ -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    private static Optional<WebFormPart> createPart(Map<String, List<String>> headers, byte[] content) {
        String contentDisposition = headers.getOrDefault("Content-Disposition", List.of()).stream()
                .findFirst().orElse("");
        Map<String, String> dispositionParameters = headerParameters(contentDisposition);
        String name = dispositionParameters.get("name");
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String fileName = dispositionParameters.get("filename");
        String contentType = headers.getOrDefault("Content-Type", List.of()).stream().findFirst().orElse(null);
        return Optional.of(new WebFormPart(name, fileName, contentType, headers, content));
    }

    private static <T> Map<String, List<T>> copyLists(Map<String, List<T>> values) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    private static Map<String, List<Object>> objectValues(Map<String, List<String>> values) {
        Map<String, List<Object>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    record ParsedForm(
            Map<String, List<Object>> parameterValues,
            Map<String, List<String>> parameters,
            Map<String, List<WebFormPart>> parts
    ) {
        static ParsedForm empty() {
            return new ParsedForm(Map.of(), Map.of(), Map.of());
        }

        static ParsedForm ofUrlEncoded(Map<String, List<String>> parameters) {
            return new ParsedForm(objectValues(parameters), copyLists(parameters), Map.of());
        }

        static ParsedForm ofMultipart(List<WebFormPart> parts) {
            Map<String, List<Object>> parameterValues = new LinkedHashMap<>();
            Map<String, List<String>> parameters = new LinkedHashMap<>();
            Map<String, List<WebFormPart>> partsByName = new LinkedHashMap<>();
            for (WebFormPart part : parts) {
                parameterValues.computeIfAbsent(part.getName(), __ -> new ArrayList<>()).add(part);
                partsByName.computeIfAbsent(part.getName(), __ -> new ArrayList<>()).add(part);
                if (!part.isFile()) {
                    parameters.computeIfAbsent(part.getName(), __ -> new ArrayList<>()).add(part.asString());
                }
            }
            return new ParsedForm(copyLists(parameterValues), copyLists(parameters), copyLists(partsByName));
        }
    }

    record Separator(int index, int length) {
    }

    record BoundaryPosition(int dataEnd, int delimiterStart) {
    }
}
