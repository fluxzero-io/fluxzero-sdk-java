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

package io.fluxzero.common.application;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Property source decorator that resolves placeholders in values returned by its delegate.
 */
class SubstitutingPropertySource implements PropertySource {
    private final PropertySource delegate;
    private final PropertySource resolver;
    private final ThreadLocal<Set<String>> resolving = ThreadLocal.withInitial(HashSet::new);

    SubstitutingPropertySource(PropertySource delegate, PropertySource resolver) {
        this.delegate = delegate;
        this.resolver = resolver;
    }

    @Override
    public String get(String name) {
        String value = delegate.get(name);
        if (value == null || !substitutionPattern.matcher(value).find()) {
            return value;
        }

        Set<String> names = resolving.get();
        if (!names.add(name)) {
            throw new IllegalStateException("Recursive substitution detected for property \"%s\"".formatted(name));
        }
        try {
            return substituteOrOriginal(value);
        } finally {
            names.remove(name);
            if (names.isEmpty()) {
                resolving.remove();
            }
        }
    }

    private String substituteOrOriginal(String template) {
        String current = template;
        while (true) {
            Matcher matcher = substitutionPattern.matcher(current);
            StringBuilder result = new StringBuilder();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String value = resolvePlaceholder(matcher.group(1));
                if (value == null) {
                    return template;
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            if (!found) {
                return current;
            }
            matcher.appendTail(result);
            String next = result.toString();
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
    }

    private String resolvePlaceholder(String key) {
        var keyWithDefault = key.split(":", 2);
        if (keyWithDefault.length == 1) {
            return resolver.get(key);
        }
        String value = resolver.get(keyWithDefault[0]);
        return value == null ? keyWithDefault[1] : value;
    }
}
