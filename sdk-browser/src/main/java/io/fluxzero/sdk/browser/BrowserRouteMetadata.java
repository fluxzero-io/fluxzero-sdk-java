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

import io.fluxzero.common.MessageType;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Browser-safe handler route metadata lowered from the shared component registry.
 */
public final class BrowserRouteMetadata {
    private final String componentName;
    private final MessageType messageType;
    private final boolean disabled;
    private final boolean passive;
    private final boolean skipExpiredRequests;
    private final boolean local;
    private final boolean tracked;
    private final Set<String> payloadTypeNames;
    private final Set<String> allowedClassNames;
    private final int webRoutes;

    public BrowserRouteMetadata(String componentName, MessageType messageType, boolean disabled, boolean passive,
                                boolean skipExpiredRequests, boolean local, boolean tracked,
                                Set<String> payloadTypeNames, Set<String> allowedClassNames, int webRoutes) {
        this.componentName = Objects.requireNonNull(componentName, "componentName");
        this.messageType = Objects.requireNonNull(messageType, "messageType");
        this.disabled = disabled;
        this.passive = passive;
        this.skipExpiredRequests = skipExpiredRequests;
        this.local = local;
        this.tracked = tracked;
        this.payloadTypeNames = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(
                payloadTypeNames, "payloadTypeNames")));
        this.allowedClassNames = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(
                allowedClassNames, "allowedClassNames")));
        this.webRoutes = webRoutes;
    }

    public String componentName() {
        return componentName;
    }

    public MessageType messageType() {
        return messageType;
    }

    public boolean disabled() {
        return disabled;
    }

    public boolean passive() {
        return passive;
    }

    public boolean skipExpiredRequests() {
        return skipExpiredRequests;
    }

    public boolean local() {
        return local;
    }

    public boolean tracked() {
        return tracked;
    }

    public Set<String> payloadTypeNames() {
        return payloadTypeNames;
    }

    public Set<String> allowedClassNames() {
        return allowedClassNames;
    }

    public int webRoutes() {
        return webRoutes;
    }

    public String primaryPayloadTypeName() {
        for (String allowedClassName : allowedClassNames) {
            return allowedClassName;
        }
        for (String payloadTypeName : payloadTypeNames) {
            return payloadTypeName;
        }
        return "";
    }

    boolean matches(MessageType messageType, String payloadTypeName) {
        if (disabled || this.messageType != messageType) {
            return false;
        }
        Set<String> candidateTypeNames = allowedClassNames.isEmpty() ? payloadTypeNames : allowedClassNames;
        if (candidateTypeNames.isEmpty()) {
            return true;
        }
        for (String candidate : candidateTypeNames) {
            if (matchesPayloadType(candidate, payloadTypeName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPayloadType(String candidate, String payloadTypeName) {
        return candidate.equals(payloadTypeName)
               || candidate.equals(simpleName(payloadTypeName))
               || Object.class.getName().equals(candidate)
               || Object.class.getSimpleName().equals(candidate);
    }

    private static String simpleName(String typeName) {
        if (typeName == null) {
            return "";
        }
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }
}
