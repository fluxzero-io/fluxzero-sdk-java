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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.web.internal.WebUtilsInternal;
import io.jooby.Body;
import io.jooby.Context;
import io.jooby.Cookie;
import io.jooby.DefaultContext;
import io.jooby.Formdata;
import io.jooby.MediaType;
import io.jooby.ParamSource;
import io.jooby.QueryString;
import io.jooby.Route;
import io.jooby.Router;
import io.jooby.Sender;
import io.jooby.ServerSentEmitter;
import io.jooby.StatusCode;
import io.jooby.output.Output;
import io.jooby.WebSocket;
import io.jooby.value.ConversionHint;
import io.jooby.value.ValueFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;
import lombok.experimental.NonFinal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

/**
 * Default implementation of {@link WebRequestContext} that adapts a {@link DeserializingMessage} of type
 * {@link io.fluxzero.common.MessageType#WEBREQUEST} to a Jooby-compatible {@link Context}.
 * <p>
 * This context serves as the main bridge between Fluxzero’s web messaging infrastructure and
 * the Jooby framework’s routing, parameter resolution, and content access APIs.
 * It enables handler matching and invocation based on path, query, form, header, and cookie parameters.
 *
 * <h2>Purpose</h2>
 * {@code DefaultWebRequestContext} wraps incoming web request metadata into an object that behaves
 * like a live HTTP request. It can be used during handler method resolution to extract parameter values,
 * match URI paths, handle routing with method + origin specificity, and access request body, headers, etc.
 *
 * <h2>Internals</h2>
 * <ul>
 *   <li>Backed by a {@link DeserializingMessage} representing a {@code WEBREQUEST}</li>
 *   <li>Implements Jooby’s {@link DefaultContext} to take advantage of its form/query/header abstractions</li>
 *   <li>Supports delayed body deserialization using a {@code Supplier<byte[]>}</li>
 *   <li>Provides metadata-based implementations of headers, cookies, and remote address resolution</li>
 *   <li>Can be accessed statically from {@link #getCurrentWebRequestContext()}</li>
 * </ul>
 *
 * <h2>Usage in Routing</h2>
 * Used primarily by {@link io.fluxzero.sdk.web.WebHandlerMatcher}, this context provides the
 * foundation for matching {@link io.fluxzero.sdk.web.WebPattern}s and routing to appropriate
 * handler methods.
 *
 * <h2>Extensibility</h2>
 * While this implementation currently delegates most parsing and matching to Jooby, the abstraction is intentionally
 * based on the {@link WebRequestContext} interface to allow future replacement of Jooby with another HTTP framework
 * without affecting the rest of the dispatching system.
 *
 * <p>
 * Many Jooby methods are stubbed or unsupported, as Fluxzero only uses a subset of Jooby's APIs for request
 * introspection (not response handling).
 *
 * @see WebRequestContext
 * @see io.fluxzero.sdk.web.WebHandlerMatcher
 * @see io.fluxzero.sdk.web.WebRequest
 * @see io.fluxzero.sdk.web.WebPattern
 */
@Value
@AllArgsConstructor
public class DefaultWebRequestContext implements DefaultContext, WebRequestContext {

    /**
     * Returns the current {@link DefaultWebRequestContext} from the active {@link DeserializingMessage},
     * or {@code null} if no such message is available or it is not a {@link MessageType#WEBREQUEST}.
     * <p>
     * This method provides a convenient way to access the web request context during handler invocation,
     * filtering, or logging without needing to explicitly pass it around.
     *
     * @return the current {@code DefaultWebRequestContext}, or {@code null} if unavailable
     *
     * @see DeserializingMessage#getCurrent()
     * @see #getWebRequestContext(DeserializingMessage)
     */
    public static DefaultWebRequestContext getCurrentWebRequestContext() {
        return Optional.ofNullable(DeserializingMessage.getCurrent()).map(
                DefaultWebRequestContext::getWebRequestContext).orElse(null);
    }

    /**
     * Creates or retrieves a {@link DefaultWebRequestContext} from the given {@link DeserializingMessage}.
     * <p>
     * Internally caches the context in the message so that repeated lookups are efficient.
     * This method should only be used with messages of type {@link MessageType#WEBREQUEST}; otherwise,
     * an {@link IllegalArgumentException} is thrown.
     *
     * @param message a deserialized {@code WEBREQUEST} message
     * @return a {@code DefaultWebRequestContext} wrapping the request metadata and body
     * @throws IllegalArgumentException if the message type is not {@code WEBREQUEST}
     *
     * @see DeserializingMessage#computeContextIfAbsent(Class, java.util.function.Function)
     * @see #getCurrentWebRequestContext()
     */
    public static DefaultWebRequestContext getWebRequestContext(DeserializingMessage message) {
        return message.computeContextIfAbsent(DefaultWebRequestContext.class, DefaultWebRequestContext::new);
    }

    private static final Pattern IP_PATTERN = Pattern.compile("[0-9a-fA-F.:]+");
    private static final Router ROUTER = new ConvertingRouter();

    Supplier<byte[]> bodySupplier;
    Metadata metadata;

    @Getter(lazy = true)
    URI uri = URI.create(WebRequest.getUrl(metadata));
    @With
    String method;
    @Getter(lazy = true)
    String requestPath = getUri().getRawPath();
    @Getter(lazy = true)
    @jakarta.annotation.Nullable
    String origin = Optional.ofNullable(getUri().getScheme())
            .map(scheme -> scheme + "://" + getUri().getHost() + Optional.ofNullable(getUri().getPort())
                    .filter(p -> p >= 0).map(p -> ":" + p).orElse("")).orElse(null);
    @Getter(lazy = true)
    @Accessors(fluent = true)
    QueryString query = QueryString.create(getValueFactory(), getUri().getQuery());
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Getter(lazy = true)
    @Accessors(fluent = true)
    io.jooby.value.Value header = io.jooby.value.Value.headers(getValueFactory(), (Map) WebRequest.getHeaders(metadata));
    @Getter(lazy = true)
    @Accessors(fluent = true)
    Map<String, String> cookieMap = WebRequest.getHeaders(metadata).getOrDefault("Cookie", Collections.emptyList())
            .stream().findFirst().map(WebUtils::parseRequestCookieHeader).orElseGet(Collections::emptyList)
            .stream().collect(toMap(HttpCookie::getName, HttpCookie::getValue));
    @Getter(lazy = true)
    Map<String, Object> attributes = new LinkedHashMap<>();
    @Getter(lazy = true)
    String remoteAddress = Stream.of("X-Forwarded-For", "Forwarded", "X-Real-IP")
            .flatMap(h -> WebRequest.getHeader(metadata, h).stream())
            .flatMap(s -> {
                Matcher matcher = IP_PATTERN.matcher(s);
                return matcher.find() ? Stream.of(matcher.group()) : Stream.empty();
            })
            .findFirst()
            .orElse("");
    @NonFinal
    @Setter
    @Accessors(chain = true)
    Map<String, String> pathMap = new LinkedHashMap<>();
    @NonFinal
    @Setter
    @Accessors(chain = true)
    Route route;
    @Getter(lazy = true)
    @Accessors(fluent = true)
    Body body = Body.of(this, bodySupplier.get());
    @Getter(lazy = true)
    @Accessors(fluent = true)
    Formdata form = parseForm();
    @Getter(lazy = true)
    @Accessors(fluent = true)
    Map<String, List<Object>> formParameters = parseFormParameters(bodySupplier.get());
    @Getter(lazy = true)
    JsonNode jsonBody = parseJsonBody(bodySupplier.get());

    DefaultWebRequestContext(DeserializingMessage message) {
        if (message.getMessageType() != MessageType.WEBREQUEST) {
            throw new IllegalArgumentException("Invalid message type: " + message.getMessageType());
        }
        bodySupplier = () -> message instanceof ChunkedDeserializingMessage chunked
                ? chunked.getAggregatedPayloadBytes()
                : message.getSerializedObject().getData().getValue();
        metadata = message.getMetadata();
        method = WebRequest.getMethod(metadata);
    }

    @NotNull
    @Override
    public Map<String, String> pathMap() {
        return pathMap;
    }

    @Override
    public ParameterValue getParameter(String name, WebParameterSource... sources) {
        for (WebParameterSource source : sources) {
            ParameterValue value = switch (source) {
                case PATH -> new ParameterValue(lookup(name, ParamSource.PATH));
                case HEADER -> new ParameterValue(lookup(name, ParamSource.HEADER));
                case COOKIE -> new ParameterValue(lookup(name, ParamSource.COOKIE));
                case FORM -> new ParameterValue(Optional.ofNullable(formParameters().get(name))
                        .map(values -> values.size() == 1 ? values.getFirst() : values)
                        .orElse(null));
                case QUERY -> new ParameterValue(lookup(name, ParamSource.QUERY));
                case BODY -> ReflectionUtils.readProperty(name, getJsonBody())
                        .map(ParameterValue::new).orElseGet(() -> new ParameterValue(null));
            };
            if (value.hasValue()) {
                return value;
            }
        }
        return new ParameterValue(null);
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

    Formdata parseForm() {
        Formdata result = Formdata.create(getValueFactory());
        formParameters().forEach((key, formValues) -> {
            List<String> stringValues = formValues.stream().filter(String.class::isInstance).map(String.class::cast)
                    .toList();
            if (stringValues.isEmpty()) {
                return;
            }
            if (stringValues.size() == 1) {
                result.put(key, stringValues.getFirst());
            } else {
                result.put(key, stringValues);
            }
        });
        return result;
    }

    Map<String, Object> formObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        formParameters().forEach((key, values) -> {
            Object value = values.size() == 1 ? values.getFirst() : values;
            result.put(key, value);
            String camelCaseKey = toCamelCase(key);
            if (!camelCaseKey.equals(key)) {
                result.putIfAbsent(camelCaseKey, value);
            }
        });
        return result;
    }

    String toCamelCase(String key) {
        StringBuilder result = new StringBuilder(key.length());
        boolean upperNext = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_' || c == '-' || c == ' ') {
                upperNext = true;
                continue;
            }
            result.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return result.toString();
    }

    Map<String, List<Object>> parseFormParameters(byte[] body) {
        String contentType = WebRequest.getHeader(metadata, "Content-Type").orElse("");
        String normalizedContentType = contentType.toLowerCase();
        if (body == null || body.length == 0) {
            return Map.of();
        }
        if (normalizedContentType.startsWith("application/x-www-form-urlencoded")) {
            return parseUrlEncodedFormParameters(body);
        }
        if (normalizedContentType.startsWith("multipart/form-data")) {
            return parseMultipartFormParameters(body, contentType);
        }
        return Map.of();
    }

    Map<String, List<Object>> parseUrlEncodedFormParameters(byte[] body) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            values.computeIfAbsent(key, __ -> new java.util.ArrayList<>()).add(value);
        }
        return values;
    }

    Map<String, List<Object>> parseMultipartFormParameters(byte[] body, String contentType) {
        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null || boundary.isBlank()) {
            return Map.of();
        }
        String payload = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        Map<String, List<Object>> values = new LinkedHashMap<>();
        int boundaryStart = findMultipartBoundary(payload, delimiter, 0);
        while (boundaryStart >= 0) {
            int afterDelimiter = boundaryStart + delimiter.length();
            if (payload.startsWith("--", afterDelimiter)) {
                break;
            }
            if (!payload.startsWith("\r\n", afterDelimiter)) {
                boundaryStart = findMultipartBoundary(payload, delimiter, afterDelimiter);
                continue;
            }
            int partStart = afterDelimiter + 2;
            int nextBoundary = findMultipartBoundary(payload, delimiter, partStart);
            if (nextBoundary < 0) {
                break;
            }
            String part = payload.substring(partStart, nextBoundary >= 2 ? nextBoundary - 2 : nextBoundary);
            int separator = part.indexOf("\r\n\r\n");
            if (separator < 0) {
                boundaryStart = nextBoundary;
                continue;
            }
            String headers = part.substring(0, separator);
            String bodyPart = part.substring(separator + 4);
            String name = extractMultipartFieldName(headers);
            if (name == null) {
                boundaryStart = nextBoundary;
                continue;
            }
            String filename = extractMultipartFilename(headers);
            byte[] bytes = bodyPart.getBytes(StandardCharsets.ISO_8859_1);
            Object value = filename == null
                    ? new String(bytes, extractMultipartCharset(headers))
                    : new MultipartFormPart(name, filename, extractMultipartContentType(headers), bytes);
            values.computeIfAbsent(name, __ -> new java.util.ArrayList<>()).add(value);
            boundaryStart = nextBoundary;
        }
        return values;
    }

    int findMultipartBoundary(String payload, String delimiter, int fromIndex) {
        int index = Math.max(0, fromIndex);
        while (index < payload.length()) {
            int candidate = payload.indexOf(delimiter, index);
            if (candidate < 0) {
                return -1;
            }
            boolean validStart = candidate == 0
                                 || (candidate >= 2 && payload.charAt(candidate - 2) == '\r'
                                     && payload.charAt(candidate - 1) == '\n');
            int afterDelimiter = candidate + delimiter.length();
            boolean validEnd = afterDelimiter == payload.length()
                               || payload.startsWith("\r\n", afterDelimiter)
                               || payload.startsWith("--", afterDelimiter);
            if (validStart && validEnd) {
                return candidate;
            }
            index = candidate + delimiter.length();
        }
        return -1;
    }

    String extractMultipartBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    return boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    String extractMultipartFieldName(String headers) {
        return extractMultipartContentDispositionParameter(headers, "name");
    }

    String extractMultipartFilename(String headers) {
        return extractMultipartContentDispositionParameter(headers, "filename");
    }

    String extractMultipartContentType(String headers) {
        Matcher matcher = Pattern.compile("(?i)content-type:\\s*([^\\r\\n;]+(?:;[^\\r\\n]+)?)").matcher(headers);
        return matcher.find() ? matcher.group(1).trim() : "application/octet-stream";
    }

    String extractMultipartContentDispositionParameter(String headers, String parameterName) {
        return headers.lines()
                .filter(line -> line.regionMatches(true, 0, "Content-Disposition:", 0, "Content-Disposition:".length()))
                .findFirst()
                .flatMap(line -> Stream.of(line.substring("Content-Disposition:".length()).split(";"))
                        .map(String::trim)
                        .filter(part -> part.regionMatches(true, 0, parameterName + "=", 0, parameterName.length() + 1))
                        .map(part -> part.substring(parameterName.length() + 1).trim())
                        .findFirst())
                .map(value -> value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2
                        ? value.substring(1, value.length() - 1)
                        : value)
                .orElse(null);
    }

    Charset extractMultipartCharset(String headers) {
        Matcher matcher = Pattern.compile("(?i)content-type:.*charset=([A-Za-z0-9_\\-]+)").matcher(headers);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    /*
        Below methods are not *yet* supported but should in the future.
     */

    @NotNull
    @Override
    public String getProtocol() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public List<Certificate> getClientCertificates() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public String getScheme() {
        throw new UnsupportedOperationException();
    }

    /*
        Below methods should never be invoked as they won't be exposed in Fluxzero apps
     */

    @NotNull
    @Override
    public Context setRemoteAddress(@NotNull String remoteAddress) {
        return this;
    }

    @NotNull
    @Override
    public Context setHost(@NotNull String host) {
        return this;
    }

    @NotNull
    @Override
    public Context setPort(int port) {
        return this;
    }

    @NotNull
    @Override
    public Context setScheme(@NotNull String scheme) {
        return this;
    }

    @Override
    public boolean isInIoThread() {
        return false;
    }

    @NotNull
    @Override
    public Context dispatch(@NotNull Runnable action) {
        return this;
    }

    @NotNull
    @Override
    public Context dispatch(@NotNull Executor executor, @NotNull Runnable action) {
        return this;
    }

    @NotNull
    @Override
    public Context upgrade(@NotNull WebSocket.Initializer handler) {
        return this;
    }

    @NotNull
    @Override
    public Context upgrade(@NotNull ServerSentEmitter.Handler handler) {
        return this;
    }

    @NotNull
    @Override
    public Context setResponseHeader(@NotNull String name, @NotNull String value) {
        return this;
    }

    @NotNull
    @Override
    public Context removeResponseHeader(@NotNull String name) {
        return this;
    }

    @NotNull
    @Override
    public Context removeResponseHeaders() {
        return this;
    }

    @NotNull
    @Override
    public Context setResponseLength(long length) {
        return this;
    }

    @Nullable
    @Override
    public String getResponseHeader(@NotNull String name) {
        return null;
    }

    @Override
    public long getResponseLength() {
        return -1L;
    }

    @Override
    public boolean isResponseStarted() {
        return false;
    }

    @Override
    public boolean getResetHeadersOnError() {
        return false;
    }

    @NotNull
    @Override
    public MediaType getResponseType() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Context setResponseCode(int statusCode) {
        return this;
    }

    @NotNull
    @Override
    public StatusCode getResponseCode() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public OutputStream responseStream() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Sender responseSender() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public PrintWriter responseWriter(@NotNull MediaType contentType) {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Router getRouter() {
        return ROUTER;
    }

    @NotNull
    @Override
    public Context setMethod(@NotNull String method) {
        return this;
    }

    @NotNull
    @Override
    public Context setRequestPath(@NotNull String path) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull String data, @NotNull Charset charset) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull byte[] data) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull ByteBuffer data) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull Output output) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull ByteBuffer[] data) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull ReadableByteChannel channel) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull InputStream input) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull FileChannel file) {
        return this;
    }

    @NotNull
    @Override
    public Context send(@NotNull StatusCode statusCode) {
        return this;
    }

    @NotNull
    @Override
    public Context setResetHeadersOnError(boolean value) {
        return this;
    }

    @NotNull
    @Override
    public Context setResponseCookie(@NotNull Cookie cookie) {
        return this;
    }

    @NotNull
    @Override
    public Context setResponseType(@NotNull String contentType) {
        return this;
    }

    @NotNull
    @Override
    public Context setResponseType(@NotNull MediaType contentType) {
        return this;
    }

    @NotNull
    @Override
    public Context setDefaultResponseType(@NotNull MediaType contentType) {
        return this;
    }

    @NotNull
    @Override
    public Context onComplete(@NotNull Route.Complete task) {
        return this;
    }

    public boolean matchesAny(Collection<String> urlPatterns) {
        return urlPatterns.stream().anyMatch(this::matches);
    }

    protected static class ConvertingRouter implements Router {
        @Delegate
        private final Router delegate = WebUtilsInternal.router();

        public ConvertingRouter() {
            delegate.setValueFactory(new DefaultValueFactory());
        }
    }

    protected static class DefaultValueFactory extends ValueFactory {
        @SuppressWarnings("unchecked")
        @Override
        public Object convert(@NotNull Type type, @NotNull io.jooby.value.Value value, @NotNull ConversionHint ignored) {
            return new Wrapper<>(value.valueOrNull()).get(type);
        }
    }

    @lombok.Value
    protected static class Wrapper<T> {
        T value;

        @SuppressWarnings({"rawtypes", "unchecked"})
        <V> V get(Type type) {
            if (value == null) {
                return null;
            }
            if ((type instanceof Class c)) {
                if (c.isInstance(value)) {
                    return (V) c.cast(value);
                }
                Wrapper<V> result = JsonUtils.convertValue(this, tf -> tf.constructParametricType(Wrapper.class, c));
                return result.value;
            }
            throw new IllegalArgumentException(String.format("Unexpected type: %s", type));
        }
    }
}
