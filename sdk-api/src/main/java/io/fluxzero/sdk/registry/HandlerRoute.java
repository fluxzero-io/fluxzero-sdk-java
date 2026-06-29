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

package io.fluxzero.sdk.registry;

import io.fluxzero.common.MessageType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Indexed handler route metadata discovered without loading the handler class.
 *
 * @param messageType message stream handled by the route
 * @param annotation handler annotation metadata
 * @param executable handler executable metadata
 * @param disabled whether this route is disabled during discovery
 * @param passive whether this route is passive
 * @param skipExpiredRequests whether expired requests may be skipped before invocation
 * @param local whether the route participates in local gateway dispatch
 * @param tracked whether the route participates in Fluxzero tracking
 * @param payloadTypeNames likely payload class names; empty means the concrete runtime handler decides
 * @param allowedClassNames class names declared by the handler annotation's {@code allowedClasses}
 * @param webRoutes web route metadata for web handlers
 */
public record HandlerRoute(
        MessageType messageType,
        AnnotationDescriptor annotation,
        ExecutableDescriptor executable,
        boolean disabled,
        boolean passive,
        boolean skipExpiredRequests,
        boolean local,
        boolean tracked,
        Set<String> payloadTypeNames,
        Set<String> allowedClassNames,
        List<WebRouteDescriptor> webRoutes) {

    public HandlerRoute {
        Objects.requireNonNull(messageType, "messageType");
        payloadTypeNames = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(payloadTypeNames, "payloadTypeNames")));
        allowedClassNames = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(allowedClassNames, "allowedClassNames")));
        webRoutes = RegistryCollections.immutableList(Objects.requireNonNull(webRoutes, "webRoutes"));
    }

    public HandlerRoute(MessageType messageType, boolean local, boolean tracked, Set<String> payloadTypeNames) {
        this(messageType, null, null, false, false, false, local, tracked, payloadTypeNames, Set.of(), List.of());
    }

    /**
     * Returns whether this route can be considered for the supplied message and payload type.
     */
    public boolean canHandle(MessageType messageType, Class<?> payloadType) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(payloadType, "payloadType");
        if (disabled || this.messageType != messageType) {
            return false;
        }
        return payloadTypeNames.isEmpty()
               || payloadTypeNames.stream().anyMatch(typeName -> matchesPayloadType(typeName, payloadType));
    }

    private static boolean matchesPayloadType(String typeName, Class<?> payloadType) {
        String canonicalName = payloadType.getCanonicalName();
        return Objects.equals(typeName, payloadType.getName())
               || canonicalName != null && Objects.equals(typeName, canonicalName)
               || Objects.equals(typeName, payloadType.getSimpleName())
               || Object.class.getName().equals(typeName)
               || Object.class.getSimpleName().equals(typeName);
    }

    /**
     * Returns whether payload matching is intentionally delegated to the concrete runtime handler.
     */
    public boolean hasWildcardPayload() {
        return payloadTypeNames.isEmpty();
    }

    /**
     * Returns whether this route contains source-level web route metadata.
     */
    public boolean hasWebRouteMetadata() {
        return !webRoutes.isEmpty();
    }

    /**
     * Returns the handler annotation when the route came from parsed source metadata.
     */
    public Optional<AnnotationDescriptor> annotationMetadata() {
        return Optional.ofNullable(annotation);
    }

    /**
     * Returns the handler executable when the route came from parsed source metadata.
     */
    public Optional<ExecutableDescriptor> executableMetadata() {
        return Optional.ofNullable(executable);
    }
}
