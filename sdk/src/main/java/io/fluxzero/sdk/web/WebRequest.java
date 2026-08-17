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

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.publishing.WebRequestGateway;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

import java.beans.ConstructorProperties;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.fluxzero.common.api.Data.JSON_FORMAT;

/**
 * Represents a web request message within the Fluxzero Runtime.
 * <p>
 * This message is routed to handlers using annotations like {@link HandleWeb}, {@link HandleGet}, or
 * {@link HandleSocketOpen}.
 * </p>
 *
 * <p>
 * {@code WebRequest} extends {@link Message} and includes additional metadata such as:
 * </p>
 * <ul>
 *   <li>{@code path} – the requested URI path including query parameters</li>
 *   <li>{@code method} – HTTP or WebSocket method (e.g. GET, WS_OPEN)</li>
 *   <li>{@code headers} – structured request headers</li>
 *   <li>{@code cookies} – parsed from the Cookie header</li>
 * </ul>
 *
 * <p>
 * It also provides a fluent {@link Builder} API to construct requests programmatically.
 * </p>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * WebRequest request = WebRequest.post("https://api.example.com/projects")
 *     .body(new ProjectDetails("My Project", "My Description"))
 *     .build();
 * }</pre>
 *
 * <p>Outbound requests with an absolute URL that are dispatched using the {@link WebRequestGateway} will be forwarded
 * by the proxy in Fluxzero Runtime.
 *
 * @see WebResponse
 * @see HandleWeb
 */
@Value
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"headers", "cookies"})
public class WebRequest extends Message {

    public static final String urlKey = "url", methodKey = "method", headersKey = "headers", sessionIdKey = "sessionId";

    /**
     * Creates a new {@link Builder} instance for constructing a {@link WebRequest}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new {@link Builder} instance for constructing a {@link WebRequest GET request} to given url.
     */
    public static Builder get(String url) {
        return builder().method(HttpRequestMethod.GET).url(url);
    }

    /**
     * Creates a new {@link Builder} instance for constructing a {@link WebRequest POST request} to given url.
     */
    public static Builder post(String url) {
        return builder().method(HttpRequestMethod.POST).url(url);
    }

    /**
     * Creates a new {@link Builder} instance for constructing a {@link WebRequest PUT request} to given url.
     */
    public static Builder put(String url) {
        return builder().method(HttpRequestMethod.PUT).url(url);
    }

    /**
     * Creates a new {@link Builder} instance for constructing a {@link WebRequest PATCH request} to given url.
     */
    public static Builder patch(String url) {
        return builder().method(HttpRequestMethod.PATCH).url(url);
    }

    /**
     * Creates a new {@link Builder} instance for constructing a {@link WebRequest DELETE request} to given url.
     */
    public static Builder delete(String url) {
        return builder().method(HttpRequestMethod.DELETE).url(url);
    }

    @NonNull
    String path;

    @NonNull
    String method;

    @NonNull
    Map<String, List<String>> headers;

    /**
     * Returns all request cookies from every {@code Cookie} header value in logical wire order.
     * <p>
     * Each header value is parsed as a semicolon-separated sequence. Cookies with the same name are not collapsed.
     * Header names are case-insensitive, while cookie names remain case-sensitive.
     *
     * @return all request cookies, or an empty list when none are present
     */
    @Getter(lazy = true)
    @JsonIgnore
    List<HttpCookie> cookies = parseCookieHeaders(getHeaders("Cookie"));

    private WebRequest(Builder builder) {
        super(builder.payload(), builder.metadata.with(urlKey, builder.url(), methodKey, builder.method(),
                                                       headersKey, builder.headers()));
        this.path = builder.url();
        this.method = builder.method();
        this.headers = builder.headers();
    }

    @ConstructorProperties({"payload", "metadata", "messageId", "timestamp"})
    WebRequest(Object payload, Metadata metadata, String messageId, Instant timestamp) {
        super(payload, metadata, messageId, timestamp);
        this.path = getUrl(metadata);
        this.method = getMethod(metadata);
        this.headers = getHeaders(metadata);
    }

    /**
     * Constructs a new WebRequest instance using the provided Message.
     *
     * @param m the Message instance containing the payload, metadata, message ID, and timestamp
     */
    public WebRequest(Message m) {
        this(m.getPayload(), m.getMetadata(), m.getMessageId(), m.getTimestamp());
    }

    /**
     * Serializes the request using the content type if applicable.
     */
    @Override
    public SerializedMessage serialize(Serializer serializer) {
        return Optional.ofNullable(getContentType()).map(
                        format -> new SerializedMessage(serializer.serialize(getPayload(), format), getMetadata(),
                                                        getMessageId(), getTimestamp().toEpochMilli()))
                .orElseGet(() -> super.serialize(serializer));
    }

    @Override
    public WebRequest withMetadata(Metadata metadata) {
        return new WebRequest(super.withMetadata(metadata));
    }

    @Override
    public WebRequest addMetadata(Metadata metadata) {
        return (WebRequest) super.addMetadata(metadata);
    }

    @Override
    public WebRequest addMetadata(String key, Object value) {
        return (WebRequest) super.addMetadata(key, value);
    }

    @Override
    public WebRequest addMetadata(Object... keyValues) {
        return (WebRequest) super.addMetadata(keyValues);
    }

    @Override
    public WebRequest addMetadata(Map<String, ?> values) {
        return (WebRequest) super.addMetadata(values);
    }

    @Override
    public WebRequest addUser(User user) {
        return (WebRequest) super.addUser(user);
    }

    @Override
    public WebRequest withPayload(Object payload) {
        if (payload == getPayload()) {
            return this;
        }
        return toBuilder().payload(payload).build();
    }

    @Override
    public WebRequest withMessageId(String messageId) {
        return new WebRequest(super.withMessageId(messageId));
    }

    @Override
    public WebRequest withTimestamp(Instant timestamp) {
        return new WebRequest(super.withTimestamp(timestamp));
    }

    /**
     * Returns a single header value, or {@code null} if not present.
     *
     * @param name the header name
     * @return the first value or {@code null}
     */
    public String getHeader(String name) {
        return getHeaders(name).stream().findFirst().orElse(null);
    }

    /**
     * Returns all values for the specified header.
     *
     * @param name the header name
     * @return list of values, possibly empty
     */
    public List<String> getHeaders(String name) {
        return headers.getOrDefault(name, Collections.emptyList());
    }

    /**
     * Returns the content type of the request (based on {@code Content-Type} header).
     */
    public String getContentType() {
        return getHeader("Content-Type");
    }

    /**
     * Deserializes the payload into a given type, using JSON if content type is {@code application/json}.
     *
     * @param type the target type
     * @return deserialized payload
     */
    @Override
    public <R> R getPayloadAs(Type type) {
        return WebUtils.convertPayload(getPayload(), type, getContentType());
    }

    /**
     * Finds the first cookie with the given name in logical wire order.
     * <p>
     * This method retains its first-match behavior and is equivalent to calling
     * {@link #getCookie(String, CookieValueConflictPolicy)} with {@link CookieValueConflictPolicy#DEFAULT}. Cookies in
     * later {@code Cookie} header values are now included in the lookup.
     *
     * @param name the case-sensitive cookie name
     * @return the first matching cookie, or empty if it is absent
     */
    public Optional<HttpCookie> getCookie(String name) {
        return getCookie(name, CookieValueConflictPolicy.DEFAULT);
    }

    /**
     * Finds a cookie with the given name using the requested conflict policy.
     * <p>
     * Identical repeated values are accepted by both policies. {@link CookieValueConflictPolicy#REJECT_CONFLICTING_VALUES}
     * throws a redacted {@link CookieConflictException} when at least one matching value differs.
     *
     * @param name           the case-sensitive cookie name
     * @param conflictPolicy the policy for matching cookies with different values
     * @return the first matching cookie, or empty if it is absent
     * @throws CookieConflictException if conflicting values are present and rejection is requested
     */
    public Optional<HttpCookie> getCookie(String name, CookieValueConflictPolicy conflictPolicy) {
        CookieValueConflictPolicy requestedPolicy = Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        CookieValueConflictPolicy resolvedPolicy = requestedPolicy == CookieValueConflictPolicy.DEFAULT
                ? CookieValueConflictPolicy.ALLOW_CONFLICTING_VALUES : requestedPolicy;
        HttpCookie first = null;
        for (HttpCookie cookie : getCookies()) {
            if (!Objects.equals(name, cookie.getName())) {
                continue;
            }
            if (first == null) {
                first = cookie;
                if (resolvedPolicy == CookieValueConflictPolicy.ALLOW_CONFLICTING_VALUES) {
                    return Optional.of(cookie);
                }
            } else if (!Objects.equals(first.getValue(), cookie.getValue())) {
                throw new CookieConflictException();
            }
        }
        return Optional.ofNullable(first);
    }

    /**
     * Returns all cookies with the given name in logical wire order, without collapsing duplicates.
     *
     * @param name the case-sensitive cookie name
     * @return all matching cookies, possibly empty
     */
    public List<HttpCookie> getCookies(String name) {
        return getCookies().stream().filter(c -> Objects.equals(name, c.getName())).toList();
    }

    /**
     * The request path including query parameters (e.g. {@code "/api/users?id=123"}). May contain the full URL for
     * outbound web requests.
     */
    public @NonNull String getPath() {
        return path;
    }

    /**
     * The HTTP or WebSocket method (e.g. {@code "GET"}, {@code "POST"}, {@code "WS_OPEN"}).
     */
    public @NonNull String getMethod() {
        return method;
    }

    /**
     * The HTTP headers as a case-insensitive map. Header values are lists to support repeated headers.
     */
    public @NonNull Map<String, List<String>> getHeaders() {
        return headers;
    }

    /**
     * Creates a mutable builder for this request.
     */
    public WebRequest.Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Extracts the request path from the provided Metadata object.
     *
     * @param metadata the metadata object containing the request path information
     * @return the request path as a string
     * @throws IllegalStateException if the request path information is missing in the metadata
     */
    public static String getUrl(Metadata metadata) {
        return Optional.ofNullable(metadata.get(urlKey)).map(u -> u.startsWith("/") || u.contains("://") ? u : "/" + u)
                .orElseThrow(() -> new IllegalStateException("WebRequest is malformed: url is missing"));
    }

    /**
     * Extracts the HTTP or WebSocket method (e.g., "GET", "POST", "WS_OPEN") from the provided Metadata object.
     *
     * @param metadata the metadata object containing the method information
     * @return the HTTP or WebSocket method as a string
     * @throws IllegalStateException if the method information is missing in the metadata
     */
    public static String getMethod(Metadata metadata) {
        return Optional.ofNullable(metadata.get(methodKey))
                .orElseThrow(() -> new IllegalStateException("WebRequest is malformed: http method is missing"));
    }

    /**
     * Retrieves a case-insensitive map of headers from the provided Metadata object.
     */
    public static Map<String, List<String>> getHeaders(Metadata metadata) {
        return WebUtils.getHeaders(metadata);
    }

    /**
     * Retrieves the first header value for the given name from the provided Metadata object.
     */
    public static Optional<String> getHeader(Metadata metadata, String name) {
        return WebUtils.getHeader(metadata, name);
    }

    /**
     * Retrieves the first cookie with the given name from the provided Metadata object.
     */
    public static Optional<HttpCookie> getCookie(Metadata metadata, String name) {
        return parseCookieHeaders(getHeaders(metadata).getOrDefault("Cookie", Collections.emptyList()))
                .stream().filter(c -> Objects.equals(c.getName(), name)).findFirst();
    }

    static List<HttpCookie> parseCookieHeaders(List<String> cookieHeaders) {
        return switch (cookieHeaders.size()) {
            case 0 -> List.of();
            case 1 -> WebUtils.parseRequestCookieHeader(cookieHeaders.getFirst());
            default -> cookieHeaders.stream()
                    .flatMap(header -> WebUtils.parseRequestCookieHeader(header).stream()).toList();
        };
    }

    /**
     * Retrieves the WebSocket session ID from the provided metadata, or {@code null} if it is missing.
     */
    public static String getSocketSessionId(Metadata metadata) {
        return metadata.get(sessionIdKey);
    }

    /**
     * Retrieves the WebSocket session ID from the provided metadata or throws an exception if it is missing.
     */
    public static String requireSocketSessionId(Metadata metadata) {
        return metadata.getOrThrow(sessionIdKey, () -> new IllegalStateException(
                "`sessionId` is missing in the metadata of the WebRequest"));
    }

    /**
     * Fluent builder for {@link WebRequest}. Use {@link #builder()} to start building.
     */
    @Data
    @ToString(exclude = {"headers", "cookies", "metadata"})
    @NoArgsConstructor
    @Accessors(fluent = true, chain = true)
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class Builder {
        String url;
        String method;
        final Map<String, List<String>> headers = WebUtils.emptyHeaderMap();
        boolean acceptGzipEncoding = true;

        @Setter(AccessLevel.NONE)
        List<HttpCookie> cookies = new ArrayList<>();

        Object payload;

        Metadata metadata = Metadata.empty();

        protected Builder(Metadata metadata) {
            method(WebRequest.getMethod(metadata));
            url(WebRequest.getUrl(metadata));
            headers(WebRequest.getHeaders(metadata));
        }

        protected Builder(WebRequest request) {
            method(request.getMethod());
            url(request.getPath());
            payload(request.getPayload());
            headers(request.getHeaders());
            metadata = request.getMetadata();
        }

        public Builder headerIfAbsent(String key, String value) {
            List<String> values = headers.computeIfAbsent(key, k -> new ArrayList<>());
            if (values.isEmpty()) {
                values.add(value);
            }
            return this;
        }

        public Builder header(String key, String value) {
            return header(key, value, false);
        }

        public Builder header(String key, String value, boolean ifAbsent) {
            List<String> values = headers.computeIfAbsent(key, k -> new ArrayList<>());
            if (values.isEmpty() || !ifAbsent) {
                values.add(value);
            }
            return this;
        }

        public Builder headers(Map<String, List<String>> headers) {
            headers.forEach((key, values) -> this.headers.put(key, new ArrayList<>(values)));
            return this;
        }

        public Builder clearHeader(String key) {
            headers.computeIfPresent(key, (k, v) -> null);
            return this;
        }

        public Builder cookie(HttpCookie cookie) {
            cookies().add(cookie);
            return this;
        }

        public List<HttpCookie> cookies() {
            List<String> cookieHeaders = headers.remove("Cookie");
            if (cookieHeaders != null) {
                cookies.addAll(parseCookieHeaders(cookieHeaders));
            }
            return cookies;
        }

        public Builder contentType(String contentType) {
            return header("Content-Type", contentType);
        }

        public Builder body(Object payload) {
            return payload(payload);
        }

        public Map<String, List<String>> headers() {
            var result = WebUtils.asHeaderMap(headers);
            if (acceptGzipEncoding) {
                result.computeIfAbsent("Accept-Encoding", k -> List.of("gzip"));
            }
            if (!result.containsKey("Content-Type")) {
                if (payload instanceof String) {
                    result.put("Content-Type", List.of("text/plain"));
                } else if (payload instanceof byte[]) {
                    result.put("Content-Type", List.of("application/octet-stream"));
                }
            }
            if (!cookies.isEmpty()) {
                result.put("Cookie", List.of(cookies.stream().map(WebUtils::toRequestHeaderString)
                                                     .collect(Collectors.joining("; "))));
            }
            return result;
        }

        public WebRequest build() {
            if (method == null) {
                throw new IllegalStateException("HTTP request method not set");
            }
            return new WebRequest(this);
        }
    }
}
