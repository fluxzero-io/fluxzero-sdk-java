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

package io.fluxzero.sdk.browser.generator;

import java.util.Objects;

/**
 * Generated browser source or resource.
 *
 */
public final class BrowserGeneratedSource {
    private final String path;
    private final String content;

    public BrowserGeneratedSource(String path, String content) {
        this.path = Objects.requireNonNull(path, "path");
        this.content = Objects.requireNonNull(content, "content");
    }

    public String path() {
        return path;
    }

    public String content() {
        return content;
    }
}
