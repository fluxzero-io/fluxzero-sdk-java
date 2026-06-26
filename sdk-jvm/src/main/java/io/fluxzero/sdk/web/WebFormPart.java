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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single {@code multipart/form-data} part.
 * <p>
 * Text fields and file uploads are exposed through the same type. Use {@link #isFile()} to distinguish file parts,
 * {@link #asString()} for text content, and {@link #getContent()} or {@link #getInputStream()} for raw bytes.
 */
public final class WebFormPart {
    private final String name;
    private final String fileName;
    private final String contentType;
    private final Map<String, List<String>> headers;
    private final byte[] content;

    public WebFormPart(String name, String fileName, String contentType,
                       Map<String, List<String>> headers, byte[] content) {
        this.name = name;
        this.fileName = fileName;
        this.contentType = contentType;
        Map<String, List<String>> headerCopy = WebUtils.emptyHeaderMap();
        Optional.ofNullable(headers).orElseGet(Map::of)
                .forEach((key, values) -> headerCopy.put(key, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(headerCopy);
        this.content = Optional.ofNullable(content).orElseGet(() -> new byte[0]).clone();
    }

    public String getName() {
        return name;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public List<String> getHeaders(String name) {
        return headers.getOrDefault(name, List.of());
    }

    public Optional<String> getHeader(String name) {
        return getHeaders(name).stream().findFirst();
    }

    public byte[] getContent() {
        return content.clone();
    }

    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    public boolean isFile() {
        return fileName != null;
    }

    public String asString() {
        return new String(content, charset());
    }

    private Charset charset() {
        return getHeader("Content-Type")
                .flatMap(WebFormPart::charsetParameter)
                .orElse(StandardCharsets.UTF_8);
    }

    private static Optional<Charset> charsetParameter(String contentType) {
        return DefaultWebRequestContext.headerParameters(contentType).entrySet().stream()
                .filter(e -> "charset".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .flatMap(WebFormPart::charset);
    }

    private static Optional<Charset> charset(String charset) {
        try {
            return Optional.of(Charset.forName(charset));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebFormPart that)) {
            return false;
        }
        return Objects.equals(name, that.name)
               && Objects.equals(fileName, that.fileName)
               && Objects.equals(contentType, that.contentType)
               && Objects.equals(headers, that.headers)
               && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(name, fileName, contentType, headers);
        result = 31 * result + Arrays.hashCode(content);
        return result;
    }

    @Override
    public String toString() {
        return "WebFormPart[name=%s, fileName=%s, contentType=%s, size=%d]".formatted(
                name, fileName, contentType, content.length);
    }
}
