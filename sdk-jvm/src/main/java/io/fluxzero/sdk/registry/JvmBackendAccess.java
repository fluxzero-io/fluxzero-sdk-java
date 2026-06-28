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

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Guard for JVM reflection/introspection use while Fluxzero runs in generated-only metadata mode.
 * <p>
 * Generated-only mode may still use JVM mechanics for object construction, executable invocation, Jakarta validation
 * internals, serialization, and build/classpath metadata production. What it must not do is silently answer Fluxzero
 * application semantics through an unclassified reflection path.
 */
final class JvmBackendAccess {
    enum BackendStatus {
        PLATFORM_BACKEND,
        MIGRATION_DEBT
    }

    record BackendCategory(String description, BackendStatus status) {
        boolean migrationDebt() {
            return status == BackendStatus.MIGRATION_DEBT;
        }
    }

    private static final Map<String, BackendCategory> ALLOWED_PREFIXES = new TreeMap<>(Map.of(
            "io.fluxzero.sdk.registry.", platform("metadata producer or generated metadata bridge"),
            "io.fluxzero.sdk.common.serialization.", platform("JVM serialization/type resolution backend"),
            "io.fluxzero.sdk.configuration.spring.", platform("Spring integration backend"),
            "io.fluxzero.sdk.tracking.handling.validation.jakarta.", platform("Jakarta validation provider backend")
    ));

    private static final Map<String, BackendCategory> ALLOWED_CLASSES = new TreeMap<>(Map.ofEntries(
            Map.entry("io.fluxzero.sdk.Fluxzero", platform("JVM caller-scope memoization")),
            Map.entry("io.fluxzero.common.handling.HandlerInspector",
                      debt("current JVM executable invocation backend")),
            Map.entry("io.fluxzero.sdk.common.ClientUtils", debt("runtime metadata compatibility bridge")),
            Map.entry("io.fluxzero.sdk.common.HasMessage", debt("message payload type backend")),
            Map.entry("io.fluxzero.sdk.modeling.AnnotatedEntityHolder",
                      debt("JVM entity mutation/property backend")),
            Map.entry("io.fluxzero.sdk.modeling.DefaultEntityHelper", debt("JVM entity invocation backend")),
            Map.entry("io.fluxzero.sdk.modeling.Entity", debt("JVM entity type backend")),
            Map.entry("io.fluxzero.sdk.modeling.EntityParameterResolver", debt("JVM parameter compatibility backend")),
            Map.entry("io.fluxzero.sdk.modeling.Id", debt("JVM generic id type backend")),
            Map.entry("io.fluxzero.sdk.persisting.repository.DefaultAggregateRepository",
                      debt("JVM aggregate invocation/property backend")),
            Map.entry("io.fluxzero.sdk.publishing.DefaultResultGateway", debt("JVM payload type backend")),
            Map.entry("io.fluxzero.sdk.tracking.ConsumerConfiguration", debt("JVM consumer compatibility bridge")),
            Map.entry("io.fluxzero.sdk.tracking.DefaultTracking", debt("JVM handler type backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory",
                      debt("JVM handler construction/invocation backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.ExpiredRequestDecorator",
                      debt("JVM handler annotation compatibility bridge")),
            Map.entry("io.fluxzero.sdk.tracking.handling.HandleCustomFilter", debt("JVM handler filter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.HandleDocumentFilter", debt("JVM handler filter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.HandlerAssociations",
                      debt("JVM handler association backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.MutableHandler", debt("JVM handler type backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.PayloadFilter", debt("JVM payload filter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.PayloadParameterResolver", debt("JVM parameter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.RequestTypeResolver", debt("JVM request type backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.SegmentFilter", debt("JVM segment filter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.StatefulHandler", debt("JVM stateful handler backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.TimestampParameterResolver", debt("JVM parameter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.TriggerParameterResolver", debt("JVM parameter backend")),
            Map.entry("io.fluxzero.sdk.tracking.handling.validation.ValidationUtils",
                      debt("JVM validation compatibility bridge")),
            Map.entry("io.fluxzero.sdk.web.ApiDocExtractor", debt("JVM API-doc compatibility bridge")),
            Map.entry("io.fluxzero.sdk.web.ApiReferenceEndpoint", debt("JVM API-doc endpoint backend")),
            Map.entry("io.fluxzero.sdk.web.DefaultWebRequestContext", debt("JVM web body property backend")),
            Map.entry("io.fluxzero.sdk.web.OpenApiDocumentEndpoint", debt("JVM API-doc endpoint backend")),
            Map.entry("io.fluxzero.sdk.web.OpenApiRenderer", debt("JVM OpenAPI compatibility bridge")),
            Map.entry("io.fluxzero.sdk.web.WebHandlerMatcher", debt("JVM web handler matcher backend")),
            Map.entry("io.fluxzero.sdk.web.WebParamParameterResolver", debt("JVM web parameter backend")),
            Map.entry("io.fluxzero.sdk.web.WebPayloadParameterResolver", debt("JVM web payload backend")),
            Map.entry("io.fluxzero.sdk.web.WebUtils", debt("JVM web route compatibility bridge")),
            Map.entry("io.fluxzero.sdk.web.WebsocketHandlerDecorator", debt("JVM websocket handler backend"))
    ));

    private JvmBackendAccess() {
    }

    static void assertAllowed() {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            return;
        }
        assertAllowed(callerClassName());
    }

    static void assertAllowed(String callerClassName) {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            return;
        }
        Optional<BackendCategory> category = classification(callerClassName);
        if (category.isPresent()) {
            if (ComponentMetadataLookups.strictGeneratedOnlyMode() && category.orElseThrow().migrationDebt()) {
                throw new ComponentRegistryException("""
                        Strict generated-only metadata mode forbids migration-debt JVM backend access from %s.
                        Replace this app-semantic fallback with generated metadata, invocation, or access plans before
                        adding it to strict generated-only acceptance.
                        """.formatted(callerClassName));
            }
            return;
        }
        throw new ComponentRegistryException("""
                Generated-only metadata mode forbids unclassified JVM introspection from %s.
                Move Fluxzero app semantics to ComponentRegistry/generated invocation metadata, or classify this call as
                an explicit JVM-only backend category in JvmBackendAccess.
                """.formatted(callerClassName));
    }

    static Optional<String> category(String callerClassName) {
        return classification(callerClassName).map(BackendCategory::description);
    }

    static Optional<BackendCategory> classification(String callerClassName) {
        String normalized = enclosingClassName(callerClassName);
        BackendCategory category = ALLOWED_CLASSES.get(normalized);
        if (category != null) {
            return Optional.of(category);
        }
        return ALLOWED_PREFIXES.entrySet().stream()
                .filter(entry -> normalized.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    static Map<String, BackendCategory> migrationDebtClasses() {
        Map<String, BackendCategory> result = new TreeMap<>();
        ALLOWED_CLASSES.entrySet().stream()
                .filter(entry -> entry.getValue().migrationDebt())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private static BackendCategory platform(String description) {
        return new BackendCategory(description, BackendStatus.PLATFORM_BACKEND);
    }

    private static BackendCategory debt(String description) {
        return new BackendCategory(description, BackendStatus.MIGRATION_DEBT);
    }

    private static String callerClassName() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : trace) {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName())
                || className.equals(JvmBackendAccess.class.getName())
                || className.equals(JvmComponentIntrospector.class.getName())
                || className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")) {
                continue;
            }
            return className;
        }
        return "<unknown>";
    }

    private static String enclosingClassName(String className) {
        int inner = className.indexOf('$');
        return inner < 0 ? className : className.substring(0, inner);
    }
}
