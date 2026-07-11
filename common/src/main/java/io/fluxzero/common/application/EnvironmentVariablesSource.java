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

import java.util.Locale;

/**
 * A {@link PropertySource} that resolves property values from system environment variables.
 * <p>
 * This source accesses environment variables via {@link System#getenv(String)} and can be used to inject
 * configuration values directly from the host operating system or container runtime. Besides exact environment
 * variable names, it also resolves property-style names to conventional environment variable names; for example
 * {@code fluxzero.api.token} resolves to {@code FLUXZERO_API_TOKEN}. Camel-case property segments support both
 * word-separated names such as {@code FLUXZERO_DATA_PROTECTION} and compact names such as
 * {@code FLUXZERO_DATAPROTECTION}.
 *
 * <p>This is typically used to override configuration in deployment environments without modifying
 * application-specific property files.
 *
 * <h2>Example usage</h2>
 * <pre>
 * export fluxzero TOKEN=secret-token
 * </pre>
 * In your application:
 * <pre>
 * fluxzero.api.token = ApplicationProperties.get("FLUXZERO_API_TOKEN")
 * </pre>
 *
 * <p>This source is usually combined with others (like {@link ApplicationPropertiesSource}) in a layered
 * configuration strategy where environment variables take precedence.
 *
 * @see System#getenv(String)
 * @see ApplicationPropertiesSource
 * @see JavaPropertiesSource
 */
public enum EnvironmentVariablesSource implements PropertySource {
    INSTANCE;

    /**
     * Retrieves the value of the given property name from the system environment. Exact environment variable names have
     * priority over their normalized variants.
     *
     * @param name the name of the environment variable or property-style key
     * @return the value, or {@code null} if the variable is not defined
     */
    @Override
    public String get(String name) {
        String result = System.getenv(name);
        if (result != null) {
            return result;
        }
        String environmentVariableName = toEnvironmentVariableName(name);
        result = environmentVariableName.equals(name) ? null : System.getenv(environmentVariableName);
        if (result != null) {
            return result;
        }
        String compactEnvironmentVariableName = toCompactEnvironmentVariableName(name);
        if (compactEnvironmentVariableName.equals(name)
            || compactEnvironmentVariableName.equals(environmentVariableName)) {
            return null;
        }
        return System.getenv(compactEnvironmentVariableName);
    }

    static String toEnvironmentVariableName(String name) {
        return toEnvironmentVariableName(name, true);
    }

    static String toCompactEnvironmentVariableName(String name) {
        return toEnvironmentVariableName(name, false);
    }

    private static String toEnvironmentVariableName(String name, boolean separateCamelCase) {
        StringBuilder result = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            char next = i + 1 < name.length() ? name.charAt(i + 1) : 0;
            if (!Character.isLetterOrDigit(current)) {
                appendSeparator(result);
            } else {
                if (separateCamelCase && shouldSeparateCamelCase(previous, current, next)) {
                    appendSeparator(result);
                }
                result.append(Character.toUpperCase(current));
            }
            previous = current;
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == '_') {
            result.deleteCharAt(length - 1);
        }
        return result.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean shouldSeparateCamelCase(char previous, char current, char next) {
        return Character.isUpperCase(current)
               && (Character.isLowerCase(previous) || Character.isDigit(previous)
                   || (Character.isUpperCase(previous) && Character.isLowerCase(next)));
    }

    private static void appendSeparator(StringBuilder result) {
        if (!result.isEmpty() && result.charAt(result.length() - 1) != '_') {
            result.append('_');
        }
    }
}
