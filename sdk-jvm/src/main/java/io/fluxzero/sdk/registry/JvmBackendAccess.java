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
    private static final Map<String, String> ALLOWED_PREFIXES = new TreeMap<>(Map.of(
            "io.fluxzero.sdk.registry.", "metadata producer or generated metadata bridge",
            "io.fluxzero.sdk.common.serialization.", "JVM serialization/type resolution backend",
            "io.fluxzero.sdk.configuration.spring.", "Spring integration backend",
            "io.fluxzero.sdk.tracking.handling.validation.jakarta.", "Jakarta validation provider backend"
    ));

    private static final Map<String, String> ALLOWED_CLASSES = new TreeMap<>(Map.ofEntries(
            Map.entry("io.fluxzero.sdk.Fluxzero", "JVM caller-scope memoization"),
            Map.entry("io.fluxzero.common.handling.HandlerInspector", "current JVM executable invocation backend"),
            Map.entry("io.fluxzero.sdk.common.ClientUtils", "runtime metadata compatibility bridge"),
            Map.entry("io.fluxzero.sdk.common.HasMessage", "message payload type backend"),
            Map.entry("io.fluxzero.sdk.modeling.AnnotatedEntityHolder", "JVM entity mutation/property backend"),
            Map.entry("io.fluxzero.sdk.modeling.DefaultEntityHelper", "JVM entity invocation backend"),
            Map.entry("io.fluxzero.sdk.modeling.Entity", "JVM entity type backend"),
            Map.entry("io.fluxzero.sdk.modeling.EntityParameterResolver", "JVM parameter compatibility backend"),
            Map.entry("io.fluxzero.sdk.modeling.Id", "JVM generic id type backend"),
            Map.entry("io.fluxzero.sdk.modeling.ModelMetadata", "JVM property compatibility backend"),
            Map.entry("io.fluxzero.sdk.persisting.repository.DefaultAggregateRepository",
                      "JVM aggregate invocation/property backend"),
            Map.entry("io.fluxzero.sdk.persisting.search.DefaultIndexOperation", "JVM property access backend"),
            Map.entry("io.fluxzero.sdk.persisting.search.DocumentStore", "JVM property access backend"),
            Map.entry("io.fluxzero.sdk.publishing.DefaultResultGateway", "JVM payload type backend"),
            Map.entry("io.fluxzero.sdk.publishing.dataprotection.DataProtectionInterceptor",
                      "JVM property access backend"),
            Map.entry("io.fluxzero.sdk.scheduling.PeriodicMetadata", "JVM schedule compatibility backend"),
            Map.entry("io.fluxzero.sdk.scheduling.SchedulingInterceptor", "JVM schedule instantiation backend"),
            Map.entry("io.fluxzero.sdk.tracking.ConsumerConfiguration", "JVM consumer compatibility bridge"),
            Map.entry("io.fluxzero.sdk.tracking.DefaultTracking", "JVM handler type backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory",
                      "JVM handler construction/invocation backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.ExpiredRequestDecorator",
                      "JVM handler annotation compatibility bridge"),
            Map.entry("io.fluxzero.sdk.tracking.handling.HandleCustomFilter", "JVM handler filter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.HandleDocumentFilter", "JVM handler filter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.HandlerAssociations", "JVM handler association backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.MutableHandler", "JVM handler type backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.PayloadFilter", "JVM payload filter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.PayloadParameterResolver", "JVM parameter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.RequestTypeResolver", "JVM request type backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.SegmentFilter", "JVM segment filter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.StatefulHandler", "JVM stateful handler backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.TimestampParameterResolver", "JVM parameter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.TriggerParameterResolver", "JVM parameter backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.contentfiltering.ContentFilterInterceptor",
                      "JVM property access backend"),
            Map.entry("io.fluxzero.sdk.tracking.handling.validation.ValidationUtils",
                      "JVM validation compatibility bridge"),
            Map.entry("io.fluxzero.sdk.web.ApiDocExtractor", "JVM API-doc compatibility bridge"),
            Map.entry("io.fluxzero.sdk.web.ApiReferenceEndpoint", "JVM API-doc endpoint backend"),
            Map.entry("io.fluxzero.sdk.web.DefaultWebRequestContext", "JVM web body property backend"),
            Map.entry("io.fluxzero.sdk.web.OpenApiDocumentEndpoint", "JVM API-doc endpoint backend"),
            Map.entry("io.fluxzero.sdk.web.OpenApiRenderer", "JVM OpenAPI compatibility bridge"),
            Map.entry("io.fluxzero.sdk.web.WebHandlerMatcher", "JVM web handler matcher backend"),
            Map.entry("io.fluxzero.sdk.web.WebParamParameterResolver", "JVM web parameter backend"),
            Map.entry("io.fluxzero.sdk.web.WebPayloadParameterResolver", "JVM web payload backend"),
            Map.entry("io.fluxzero.sdk.web.WebUtils", "JVM web route compatibility bridge"),
            Map.entry("io.fluxzero.sdk.web.WebsocketHandlerDecorator", "JVM websocket handler backend")
    ));

    private JvmBackendAccess() {
    }

    static void assertAllowed() {
        if (!ComponentMetadataLookups.generatedOnlyMode()) {
            return;
        }
        String caller = callerClassName();
        if (category(caller).isPresent()) {
            return;
        }
        throw new ComponentRegistryException("""
                Generated-only metadata mode forbids unclassified JVM introspection from %s.
                Move Fluxzero app semantics to ComponentRegistry/generated invocation metadata, or classify this call as
                an explicit JVM-only backend category in JvmBackendAccess.
                """.formatted(caller));
    }

    static Optional<String> category(String callerClassName) {
        String normalized = enclosingClassName(callerClassName);
        String category = ALLOWED_CLASSES.get(normalized);
        if (category != null) {
            return Optional.of(category);
        }
        return ALLOWED_PREFIXES.entrySet().stream()
                .filter(entry -> normalized.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
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
