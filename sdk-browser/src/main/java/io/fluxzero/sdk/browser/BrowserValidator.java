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

package io.fluxzero.sdk.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal browser-safe validator used by generated request validation code.
 */
public final class BrowserValidator {
    private final List<String> violations = new ArrayList<>();

    public void require(String path, boolean valid, String message) {
        if (!valid) {
            violations.add(path + ":" + message);
        }
    }

    public void length(String path, String value, int min, int max) {
        int length = value == null ? 0 : value.length();
        require(path, value != null && length >= min && length <= max, "length");
    }

    public boolean valid() {
        return violations.isEmpty();
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }
}
