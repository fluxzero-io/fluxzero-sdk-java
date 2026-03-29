/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import lombok.Value;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents a single multipart file part resolved through {@link FormParam}.
 * <p>
 * Fluxzero creates this value for {@code multipart/form-data} parts that have a
 * {@code Content-Disposition} {@code filename=} attribute. The part exposes the original field name,
 * submitted filename, declared content type, and raw bytes.
 * <p>
 * This type is intended for handler parameters such as:
 * <pre>{@code
 * @HandlePost("/upload")
 * void upload(@FormParam("document") MultipartFormPart document) { ... }
 *
 * @HandlePost("/upload-many")
 * void upload(@FormParam("document") List<MultipartFormPart> documents) { ... }
 * }</pre>
 * The same multipart field can also be injected as {@code byte[]}, {@code InputStream}, or a collection
 * of those types when more convenient for the handler.
 */
@Value
public class MultipartFormPart {
    /**
     * The multipart form field name, for example {@code document}.
     */
    String name;

    /**
     * The submitted filename from the multipart {@code Content-Disposition} header.
     */
    String filename;

    /**
     * The declared part content type, or {@code application/octet-stream} when absent.
     */
    String contentType;

    /**
     * The full raw bytes of this part.
     */
    byte[] bytes;

    /**
     * Returns a fresh {@link InputStream} over the part bytes.
     */
    public InputStream asInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    /**
     * Decodes the part bytes as UTF-8 text.
     */
    public String asString() {
        return asString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the part bytes using the given charset.
     */
    public String asString(Charset charset) {
        return new String(bytes, charset);
    }
}
