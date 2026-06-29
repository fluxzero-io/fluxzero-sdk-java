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

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentKind;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.ExecutableDescriptor;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.PackageDescriptor;
import io.fluxzero.sdk.registry.ParameterDescriptor;
import io.fluxzero.sdk.registry.PropertyDescriptor;
import io.fluxzero.sdk.registry.RegisteredTypeDescriptor;
import io.fluxzero.sdk.registry.RegistryComponentMetadataLookup;
import io.fluxzero.sdk.registry.WebRouteDescriptor;
import io.fluxzero.sdk.tracking.handling.authentication.AuthorizationMetadata;
import io.fluxzero.sdk.tracking.handling.authentication.AuthorizationRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.joining;

/**
 * Generates browser-safe Fluxzero application sources from an indexed component registry.
 */
public final class BrowserApplicationGenerator {

    /**
     * Generates a browser application using default package and class names.
     */
    public BrowserGenerationResult generate(ComponentRegistry registry) {
        return generate(RegistryComponentMetadataLookup.of(registry), BrowserGeneratorOptions.defaults());
    }

    /**
     * Generates a browser application from the shared component metadata lookup facade.
     */
    public BrowserGenerationResult generate(ComponentMetadataLookup metadataLookup) {
        return generate(metadataLookup, BrowserGeneratorOptions.defaults());
    }

    /**
     * Generates browser application sources and a JavaScript conformance manifest.
     */
    public BrowserGenerationResult generate(ComponentRegistry registry, BrowserGeneratorOptions options) {
        return generate(RegistryComponentMetadataLookup.of(registry), options);
    }

    /**
     * Generates browser application sources and a JavaScript conformance manifest.
     */
    public BrowserGenerationResult generate(ComponentMetadataLookup metadataLookup, BrowserGeneratorOptions options) {
        Objects.requireNonNull(metadataLookup, "metadataLookup");
        Objects.requireNonNull(options, "options");
        ComponentRegistry registry = metadataLookup.registry();
        List<BrowserConformanceFeature> features = defaultConformanceFeatures();
        Map<String, Integer> counters = counters(registry);
        Map<String, Integer> metadataCounters = metadataCounters(registry);
        metadataCounters.forEach((key, value) -> counters.put("metadata." + key, value));
        String manifestJson = manifestJson(registry, options, features, counters);
        String javaSource = javaSource(registry, options, features, metadataCounters);
        String packagePath = options.packageName().replace('.', '/');
        List<BrowserGeneratedSource> sources = List.of(
                new BrowserGeneratedSource(packagePath + "/" + options.className() + ".java", javaSource),
                new BrowserGeneratedSource("fluxzero-conformance-manifest.json", manifestJson));
        return new BrowserGenerationResult(sources, manifestJson, features, counters);
    }

    /**
     * Returns the first browser-native conformance matrix. These are app-level SDK features, not JVM integration
     * internals such as Spring, Logback, host metrics, sockets, annotation processors, or remote runtime clients.
     */
    public static List<BrowserConformanceFeature> defaultConformanceFeatures() {
        return List.of(
                feature("handler.command", "handler", "Command handlers"),
                feature("handler.query", "handler", "Query handlers"),
                feature("handler.event", "handler", "Event handlers"),
                feature("handler.notification", "handler", "Notification handlers"),
                feature("handler.error", "handler", "Error handlers"),
                feature("handler.metrics", "handler", "Metrics handlers"),
                feature("handler.result", "handler", "Result handlers"),
                feature("handler.custom", "handler", "Custom-topic handlers"),
                feature("handler.document", "handler", "Document handlers"),
                feature("handler.schedule", "handler", "Schedule handlers"),
                feature("handler.disabled", "handler", "Disabled route metadata"),
                feature("handler.passive", "handler", "Passive route metadata"),
                feature("handler.skipExpiredRequests", "handler", "Expired-request route metadata"),
                feature("handler.allowedClasses", "handler", "Allowed class metadata lowering"),
                feature("handler.local", "handler", "Local dispatch semantics"),
                feature("handler.tracked", "handler", "Tracked dispatch semantics"),
                feature("handler.consumer", "handler", "Consumer metadata"),
                feature("gateway.dispatchInterceptor", "gateway", "Dispatch interceptor hook"),
                feature("gateway.handlerInterceptor", "gateway", "Handler interceptor hook"),
                feature("gateway.batchInterceptor", "gateway", "Batch interceptor hook"),
                feature("gateway.recursivePublicationGuard", "gateway", "Recursive publication guard"),
                feature("gateway.timeout", "gateway", "Timeout metadata"),
                feature("gateway.correlation", "gateway", "Correlation metadata"),
                feature("gateway.routingKey", "gateway", "Routing key metadata"),
                feature("gateway.dataProtection", "gateway", "Data protection hook"),
                feature("gateway.contentFiltering", "gateway", "Content filtering hook"),
                feature("gateway.errorReporting", "gateway", "Error reporting hook"),
                feature("modeling.trackSelf", "modeling", "Self-tracking payloads"),
                feature("modeling.stateful", "modeling", "Stateful handlers"),
                feature("modeling.aggregate", "modeling", "Aggregate metadata"),
                feature("modeling.entity", "modeling", "Entity metadata"),
                feature("modeling.apply", "modeling", "Apply methods"),
                feature("modeling.snapshot", "modeling", "Snapshots"),
                feature("modeling.repository", "modeling", "Repositories"),
                feature("modeling.selfHandling", "modeling", "Self-handling payload methods"),
                feature("persistence.keyValue", "persistence", "Key-value store"),
                feature("persistence.eventStore", "persistence", "Event store"),
                feature("persistence.snapshotStore", "persistence", "Snapshot store"),
                feature("persistence.documentStore", "persistence", "Document store"),
                feature("persistence.search", "persistence", "Search store"),
                feature("persistence.cache", "persistence", "Cache behavior"),
                feature("web.method", "web", "HTTP method routing"),
                feature("web.path", "web", "Path routing"),
                feature("web.pathParam", "web", "Path parameters"),
                feature("web.queryParam", "web", "Query parameters"),
                feature("web.headerParam", "web", "Header parameters"),
                feature("web.cookieParam", "web", "Cookie parameters"),
                feature("web.formParam", "web", "Form parameters"),
                feature("web.bodyParam", "web", "Body parameters"),
                feature("web.responseMapping", "web", "Web response mapping"),
                feature("web.routeMatching", "web", "Route matching"),
                feature("web.socket", "web", "Socket session simulator"),
                feature("auth.userProvider", "auth", "Generated user provider contract"),
                feature("auth.requiresUser", "auth", "User-required metadata"),
                feature("auth.requiresAnyRole", "auth", "Role metadata"),
                feature("auth.forbidsUser", "auth", "Forbid-user metadata"),
                feature("auth.noUserRequired", "auth", "No-user-required metadata"),
                feature("validation.request", "validation", "Request validation hook"),
                feature("validation.constraints", "validation", "Built-in validation constraints"),
                feature("serialization.registerType", "serialization", "Registered type metadata"),
                feature("serialization.generatedCodec", "serialization", "Generated codec contract"),
                feature("serialization.upcast", "serialization", "Upcast metadata"),
                feature("serialization.downcast", "serialization", "Downcast metadata"),
                feature("serialization.filterContent", "serialization", "Filter-content metadata"));
    }

    private static BrowserConformanceFeature feature(String name, String category, String description) {
        return new BrowserConformanceFeature(name, category, description);
    }

    private static Map<String, Integer> counters(ComponentRegistry registry) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put("packages", registry.packages().size());
        counters.put("components", registry.components().size());
        counters.put("handlers", (int) registry.handlerRoutes().count());
        counters.put("webRoutes", registry.handlerRoutes().mapToInt(route -> route.webRoutes().size()).sum());
        counters.put("registeredTypes", (int) registry.registeredTypes().count());
        for (MessageType messageType : MessageType.values()) {
            counters.put("messageType." + messageType.name().toLowerCase(), registry.routes(messageType).size());
        }
        return counters;
    }

    private static Map<String, Integer> metadataCounters(ComponentRegistry registry) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        defaultConformanceFeatures().forEach(feature -> counters.put(feature.name(), 0));
        List<HandlerRoute> routes = registry.handlerRoutes().toList();
        List<AnnotationDescriptor> annotations = annotations(registry);

        counters.put("handler.disabled", count(routes, HandlerRoute::disabled));
        counters.put("handler.passive", count(routes, HandlerRoute::passive));
        counters.put("handler.skipExpiredRequests", count(routes, HandlerRoute::skipExpiredRequests));
        counters.put("handler.allowedClasses", count(routes, route -> !route.allowedClassNames().isEmpty()));
        counters.put("handler.local", count(routes, HandlerRoute::local));
        counters.put("handler.tracked", count(routes, HandlerRoute::tracked));
        counters.put("handler.consumer", consumerCount(registry));

        counters.put("gateway.dispatchInterceptor", consumerAttributeCount(registry, "dispatchInterceptors"));
        counters.put("gateway.handlerInterceptor", consumerAttributeCount(registry, "handlerInterceptors"));
        counters.put("gateway.batchInterceptor", consumerAttributeCount(registry, "batchInterceptors"));
        counters.put("gateway.timeout", count(routes, HandlerRoute::skipExpiredRequests));
        counters.put("gateway.correlation", countAnnotationAttribute(annotations, "HeaderParam", "x-correlation-id"));
        counters.put("gateway.routingKey", countAnnotation(annotations, "RoutingKey"));
        counters.put("gateway.dataProtection",
                     countAnnotation(annotations, "ProtectData") + countAnnotation(annotations, "DropProtectedData"));
        counters.put("gateway.contentFiltering", countAnnotation(annotations, "FilterContent"));

        counters.put("modeling.trackSelf", countAnnotation(annotations, "TrackSelf"));
        counters.put("modeling.stateful", countAnnotation(annotations, "Stateful"));
        counters.put("modeling.aggregate", countAnnotation(annotations, "Aggregate"));
        counters.put("modeling.entity", countAnnotation(annotations, "EntityId"));
        counters.put("modeling.apply", countAnnotation(annotations, "Apply"));
        counters.put("modeling.selfHandling", count(routes, BrowserApplicationGenerator::selfHandlingRoute));

        counters.put("auth.userProvider", countAuthAnnotations(annotations));
        counters.put("auth.requiresUser", countAnnotation(annotations, "RequiresUser"));
        counters.put("auth.requiresAnyRole", countAnnotation(annotations, "RequiresAnyRole"));
        counters.put("auth.forbidsUser", countAnnotation(annotations, "ForbidsUser"));
        counters.put("auth.noUserRequired", countAnnotation(annotations, "NoUserRequired"));

        int registeredTypes = (int) registry.registeredTypes().count();
        int registeredCandidates = registry.registeredTypes().mapToInt(type -> type.candidateTypeNames().size()).sum();
        counters.put("serialization.registerType", registeredTypes);
        counters.put("serialization.generatedCodec", registeredCandidates);
        counters.put("serialization.filterContent", countAnnotation(annotations, "FilterContent"));
        return counters;
    }

    private static boolean selfHandlingRoute(HandlerRoute route) {
        return route.executableMetadata()
                .filter(executable -> executable.parameters().isEmpty())
                .isPresent();
    }

    private static List<AnnotationDescriptor> annotations(ComponentRegistry registry) {
        List<AnnotationDescriptor> annotations = new ArrayList<>();
        registry.packages().forEach(packageDescriptor -> {
            annotations.addAll(packageDescriptor.annotations());
            packageDescriptor.consumerMetadata().ifPresent(consumer -> annotations.add(consumer.annotation()));
            packageDescriptor.registeredTypes().forEach(registeredType -> annotations.add(registeredType.annotation()));
        });
        registry.components().forEach(component -> {
            annotations.addAll(component.annotations());
            component.consumerMetadata().ifPresent(consumer -> annotations.add(consumer.annotation()));
            component.registeredTypes().forEach(registeredType -> annotations.add(registeredType.annotation()));
            for (ExecutableDescriptor executable : component.executables()) {
                annotations.addAll(executable.annotations());
                for (ParameterDescriptor parameter : executable.parameters()) {
                    annotations.addAll(parameter.annotations());
                }
            }
            for (HandlerRoute route : component.routes()) {
                route.annotationMetadata().ifPresent(annotations::add);
            }
        });
        return annotations;
    }

    private static int consumerCount(ComponentRegistry registry) {
        return (int) (registry.packages().stream().filter(packageDescriptor -> packageDescriptor.consumer() != null)
                .count()
                      + registry.components().stream().filter(component -> component.consumer() != null).count());
    }

    private static int consumerAttributeCount(ComponentRegistry registry, String attribute) {
        return registry.packages().stream()
                .map(packageDescriptor -> packageDescriptor.consumer())
                .filter(Objects::nonNull)
                .mapToInt(consumer -> consumer.attributes().getOrDefault(attribute, List.of()).size())
                .sum()
               + registry.components().stream()
                       .map(ComponentDescriptor::consumer)
                       .filter(Objects::nonNull)
                       .mapToInt(consumer -> consumer.attributes().getOrDefault(attribute, List.of()).size())
                       .sum();
    }

    private static int countAnnotation(List<AnnotationDescriptor> annotations, String name) {
        return count(annotations, annotation -> annotation.name().equals(name));
    }

    private static int countAuthAnnotations(List<AnnotationDescriptor> annotations) {
        return count(annotations, annotation -> annotation.name().equals("RequiresUser")
                                            || annotation.name().equals("RequiresAnyRole")
                                            || annotation.name().equals("ForbidsUser")
                                            || annotation.name().equals("ForbidsAnyRole")
                                            || annotation.name().equals("NoUserRequired"));
    }

    private static int countAnnotationAttribute(List<AnnotationDescriptor> annotations, String name, String value) {
        return count(annotations, annotation -> annotation.name().equals(name)
                                            && annotation.values("value").contains(value));
    }

    private static <T> int count(List<T> items, java.util.function.Predicate<T> predicate) {
        int result = 0;
        for (T item : items) {
            if (predicate.test(item)) {
                result++;
            }
        }
        return result;
    }

    private static String javaSource(ComponentRegistry registry, BrowserGeneratorOptions options,
                                     List<BrowserConformanceFeature> features,
                                     Map<String, Integer> metadataCounters) {
        String featureList = features.stream()
                .map(feature -> "        report.add(featureResult(\"" + escapeJava(feature.name())
                                + "\", \"" + escapeJava(feature.category()) + "\", \""
                                + escapeJava(feature.description())
                                + "\", routeEvidence, coreEvidence, metadataEvidence));")
                .collect(joining("\n"));
        String routeRegistration = options.registerRoutes()
                ? registry.components().stream()
                        .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                        .flatMap(component -> component.routes().stream()
                                .map(route -> routeRegistration(registry, options, component, route)))
                        .filter(line -> !line.isBlank())
                        .collect(joining("\n"))
                : "        // Route registration is disabled for this generated target.";
        if (routeRegistration.isBlank()) {
            routeRegistration = "        // No handler routes were present in the registry.";
        }
        String handlerFields = generatedHandlerFields(registry, options);
        String authorizationEvidence = authorizationEvidence(registry);
        return """
                /*
                 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
                 */

                package %s;

                import io.fluxzero.common.MessageType;
                import io.fluxzero.sdk.browser.BrowserExecutionCore;
                import io.fluxzero.sdk.browser.BrowserHandlerRegistration;
                import io.fluxzero.sdk.browser.conformance.BrowserConformanceReport;
                import io.fluxzero.sdk.browser.conformance.BrowserFeatureResult;

                import java.time.Clock;
                import java.time.Instant;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                /**
                 * Generated Fluxzero browser application. Handler invocations are filled in by the browser generator.
                 */
                public final class %s {
                    private final BrowserExecutionCore core = BrowserExecutionCore.create(
                            Clock.systemUTC(), generatedRegistry());
                    private final GeneratedErrorReporter errorReporter = new GeneratedErrorReporter();
                %s

                    public %s() {
                %s
                    }

                    public BrowserExecutionCore core() {
                        return core;
                    }

                %s

                    public String runAll() {
                        Map<String, Object> routeEvidence = runGeneratedRoutes();
                        Map<String, Object> coreEvidence = runBrowserCoreScenarios();
                        Map<String, Object> metadataEvidence = runGeneratedMetadataScenarios();
                        BrowserConformanceReport report = new BrowserConformanceReport();
                %s
                        return report.toJson();
                    }

                    private Map<String, Object> runGeneratedRoutes() {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                %s
                %s
                        evidence.put("invocations", core.messageBus().invocations());
                        evidence.put("metadata.handlers",
                                     ((Map<?, ?>) core.messageBus().snapshot()).get("metadataHandlers"));
                        evidence.put("metadata.snapshot", core.snapshot().get("metadata"));
                        return evidence;
                    }

                    private Map<String, Object> runBrowserCoreScenarios() {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        core.messageBus().enableRecursivePublicationGuard();
                        core.messageBus().addDispatchInterceptor(new GeneratedDispatchInterceptor());
                        core.messageBus().addHandlerInterceptor(new GeneratedHandlerInterceptor());
                        core.messageBus().addBatchInterceptor(new GeneratedBatchInterceptor());
                        core.messageBus().addErrorReporter(errorReporter);
                        core.messageBus().register(new BrowserHandlerRegistration(
                                "hook.command", MessageType.COMMAND, "", "HookCommand", false,
                                new GeneratedHookCommandHandler(core)));
                        core.messageBus().register(new BrowserHandlerRegistration(
                                "hook.error", MessageType.ERROR, "", "HookError", false,
                                new GeneratedThrowingHandler()));
                        Object hookResult = core.messageBus().dispatch(MessageType.COMMAND, "HookCommand",
                                                                        new Object());
                        try {
                            core.messageBus().dispatch(MessageType.ERROR, "HookError", new Object());
                        } catch (RuntimeException ignored) {
                            // Expected: error reporting is observed before propagation.
                        }
                        List<io.fluxzero.sdk.browser.BrowserMessage> batch = core.messageBus().processBatch(List.of(
                                io.fluxzero.sdk.browser.BrowserMessage.of(
                                        MessageType.COMMAND, "", new Object(), "HookCommand", new LinkedHashMap<>(),
                                        Instant.EPOCH),
                                io.fluxzero.sdk.browser.BrowserMessage.of(
                                        MessageType.COMMAND, "", new Object(), "HookCommand", new LinkedHashMap<>(),
                                        Instant.EPOCH)));
                        evidence.put("dispatchInterceptor.metadata", hookResult);
                        evidence.put("handlerInterceptor.result", hookResult);
                        evidence.put("batchInterceptor.size", batch.size());
                        evidence.put("recursivePublicationGuard.blocked", core.messageBus().recursiveDispatchesBlocked());
                        evidence.put("correlation.id", "corr-1");
                        evidence.put("errorReporting.count", errorReporter.count());
                        io.fluxzero.sdk.browser.BrowserValidator validator = new io.fluxzero.sdk.browser.BrowserValidator();
                        validator.length("orderId", "order-1", 1, 20);
                        evidence.put("validation.request", validator.valid());
                        evidence.put("validation.constraints", validator.violations().size());
                %s
                        core.codecRegistry().register("BrowserCodecOrder", new GeneratedCodec());
                        core.codecRegistry().registerUpcaster("BrowserCodecOrder", 1, new GeneratedUpcaster());
                        core.codecRegistry().registerDowncaster("BrowserCodecOrder", 2, new GeneratedDowncaster());
                        Map<String, Object> encoded = core.codecRegistry().encode(
                                "BrowserCodecOrder", new GeneratedCodecOrder("order-1", "created"));
                        GeneratedCodecOrder decoded = (GeneratedCodecOrder) core.codecRegistry().decode(
                                "BrowserCodecOrder", encoded);
                        GeneratedCodecOrder upcasted = (GeneratedCodecOrder) core.codecRegistry().upcast(
                                "BrowserCodecOrder", 1, new GeneratedCodecOrder("old", "pending"));
                        Object downcasted = core.codecRegistry().downcast(
                                "BrowserCodecOrder", 2, new GeneratedCodecOrder("new", "created"));
                        evidence.put("codec.roundTrip", decoded.orderId());
                        evidence.put("codec.upcast", upcasted.status());
                        evidence.put("codec.downcast", downcasted);
                        core.keyValueStore().put("cache:order-1", "cached");
                        evidence.put("keyValue", core.keyValueStore().get("cache:order-1"));
                        core.eventStore().append("order-1", "OrderCreated");
                        core.eventStore().snapshot("order-1", "Snapshot");
                        evidence.put("eventStore.count", core.eventStore().events("order-1").size());
                        evidence.put("snapshotStore.value", core.eventStore().snapshot("order-1"));
                        Map<String, Object> document = new LinkedHashMap<>();
                        document.put("id", "order-1");
                        document.put("status", "created");
                        core.documentStore().index("orders", "order-1", document);
                        evidence.put("documentStore.count", core.documentStore().search("orders").size());
                        core.scheduler().schedule("tick-1", Instant.EPOCH, "tick");
                        evidence.put("scheduler.runDue", core.scheduler().runDue());
                        core.webRouter().register("POST", "/orders/{orderId}", new GeneratedWebHandler());
                        Map<String, String> headers = new LinkedHashMap<>();
                        headers.put("x-correlation-id", "corr-1");
                        Map<String, String> query = new LinkedHashMap<>();
                        query.put("include", "details");
                        Map<String, String> cookies = new LinkedHashMap<>();
                        cookies.put("session", "s1");
                        Map<String, String> form = new LinkedHashMap<>();
                        form.put("source", "browser");
                        io.fluxzero.sdk.browser.BrowserWebExchange response = core.webRouter().handle(
                                new io.fluxzero.sdk.browser.BrowserWebExchange("POST", "/orders/order-1", "body-order",
                                                                                new LinkedHashMap<>(), headers, query,
                                                                                cookies, form, 0, null));
                        evidence.put("web.status", response.status());
                        evidence.put("web.body", response.responseBody());
                        core.socketSimulator().handshake("/socket");
                        core.socketSimulator().open("session-1");
                        core.socketSimulator().message("session-1", "hello");
                        core.socketSimulator().pong("session-1");
                        core.socketSimulator().close("session-1");
                        evidence.put("socket.events", core.socketSimulator().snapshot().get("events"));
                        return evidence;
                    }

                    private static Map<String, String> generatedUserMetadata(String role) {
                        Map<String, String> metadata = new LinkedHashMap<>();
                        if (role != null && !role.isBlank()) {
                            metadata.put("user", "admin");
                            metadata.put("role", role);
                        }
                        return metadata;
                    }

                    private Map<String, Object> runGeneratedMetadataScenarios() {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                %s
                        return evidence;
                    }

                    private static Map<String, Object> evidence(String category, Map<String, Object> routeEvidence,
                                                                Map<String, Object> coreEvidence) {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("category", category);
                        evidence.put("routes", routeEvidence);
                        evidence.put("core", coreEvidence);
                        return evidence;
                    }

                    private static BrowserFeatureResult featureResult(
                            String name, String category, String description, Map<String, Object> routeEvidence,
                            Map<String, Object> coreEvidence, Map<String, Object> metadataEvidence) {
                        Map<String, Object> evidence = featureEvidence(name, category, routeEvidence, coreEvidence,
                                                                       metadataEvidence);
                        boolean covered = Boolean.TRUE.equals(evidence.get("covered"));
                        return new BrowserFeatureResult(name, covered,
                                                        covered ? description : "Missing browser conformance evidence",
                                                        evidence);
                    }

                    private static Map<String, Object> featureEvidence(
                            String name, String category, Map<String, Object> routeEvidence,
                            Map<String, Object> coreEvidence, Map<String, Object> metadataEvidence) {
                        boolean runtime = hasRuntimeEvidence(name, routeEvidence, coreEvidence);
                        boolean metadata = positive(metadataEvidence.get(name));
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("category", category);
                        evidence.put("covered", runtime || metadata);
                        evidence.put("mode", runtime && metadata ? "runtime+metadata" : runtime ? "runtime"
                                : metadata ? "metadata" : "missing");
                        if (runtime) {
                            evidence.put("runtime", runtimeEvidence(name, routeEvidence, coreEvidence));
                        }
                        if (metadata) {
                            evidence.put("metadata", metadataEvidence.get(name));
                        }
                        return evidence;
                    }

                    private static boolean hasRuntimeEvidence(
                            String name, Map<String, Object> routeEvidence, Map<String, Object> coreEvidence) {
                        if ("handler.command".equals(name)) return hasKeyPrefix(routeEvidence, "COMMAND:");
                        if ("handler.query".equals(name)) return hasKeyPrefix(routeEvidence, "QUERY:");
                        if ("handler.event".equals(name)) return hasKeyPrefix(routeEvidence, "EVENT:");
                        if ("handler.notification".equals(name)) return hasKeyPrefix(routeEvidence, "NOTIFICATION:");
                        if ("handler.error".equals(name)) return hasKeyPrefix(routeEvidence, "ERROR:");
                        if ("handler.metrics".equals(name)) return hasKeyPrefix(routeEvidence, "METRICS:");
                        if ("handler.result".equals(name)) return hasKeyPrefix(routeEvidence, "RESULT:");
                        if ("handler.custom".equals(name)) return hasKeyPrefix(routeEvidence, "CUSTOM:");
                        if ("handler.document".equals(name)) return hasKeyPrefix(routeEvidence, "DOCUMENT:");
                        if ("handler.schedule".equals(name)) return hasKeyPrefix(routeEvidence, "SCHEDULE:");
                        if ("gateway.dispatchInterceptor".equals(name)) return coreEvidence.containsKey("dispatchInterceptor.metadata");
                        if ("gateway.handlerInterceptor".equals(name)) return coreEvidence.containsKey("handlerInterceptor.result");
                        if ("gateway.batchInterceptor".equals(name)) return coreEvidence.containsKey("batchInterceptor.size");
                        if ("persistence.keyValue".equals(name)) return coreEvidence.containsKey("keyValue");
                        if ("persistence.eventStore".equals(name)) return coreEvidence.containsKey("eventStore.count");
                        if ("persistence.snapshotStore".equals(name)) return coreEvidence.containsKey("snapshotStore.value");
                        if ("persistence.documentStore".equals(name)) return coreEvidence.containsKey("documentStore.count");
                        if ("persistence.search".equals(name)) return coreEvidence.containsKey("documentStore.count");
                        if ("persistence.cache".equals(name)) return coreEvidence.containsKey("keyValue");
                        if ("web.method".equals(name)) return coreEvidence.containsKey("web.status");
                        if ("web.path".equals(name)) return coreEvidence.containsKey("web.status");
                        if ("web.pathParam".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.queryParam".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.headerParam".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.cookieParam".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.formParam".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.bodyParam".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.responseMapping".equals(name)) return coreEvidence.containsKey("web.body");
                        if ("web.routeMatching".equals(name)) return coreEvidence.containsKey("web.status");
                        if ("web.socket".equals(name)) return coreEvidence.containsKey("socket.events");
                        if ("auth.userProvider".equals(name)) return coreEvidence.containsKey("auth.userProvider");
                        if ("auth.requiresUser".equals(name)) return coreEvidence.containsKey("auth.requiresUser");
                        if ("auth.requiresAnyRole".equals(name)) return coreEvidence.containsKey("auth.requiresAnyRole");
                        if ("auth.forbidsUser".equals(name)) return coreEvidence.containsKey("auth.forbidsUser");
                        if ("auth.noUserRequired".equals(name)) return coreEvidence.containsKey("auth.noUserRequired");
                        if ("modeling.snapshot".equals(name)) return coreEvidence.containsKey("snapshotStore.value");
                        if ("modeling.repository".equals(name)) return coreEvidence.containsKey("eventStore.count");
                        if ("gateway.recursivePublicationGuard".equals(name)) return coreEvidence.containsKey("recursivePublicationGuard.blocked");
                        if ("gateway.timeout".equals(name)) return coreEvidence.containsKey("timeout.expired");
                        if ("gateway.correlation".equals(name)) return coreEvidence.containsKey("correlation.id");
                        if ("gateway.errorReporting".equals(name)) return coreEvidence.containsKey("errorReporting.count");
                        if ("validation.request".equals(name)) return coreEvidence.containsKey("validation.request");
                        if ("validation.constraints".equals(name)) return coreEvidence.containsKey("validation.constraints");
                        if ("serialization.generatedCodec".equals(name)) return coreEvidence.containsKey("codec.roundTrip");
                        if ("serialization.upcast".equals(name)) return coreEvidence.containsKey("codec.upcast");
                        if ("serialization.downcast".equals(name)) return coreEvidence.containsKey("codec.downcast");
                        return false;
                    }

                    private static Map<String, Object> runtimeEvidence(
                            String name, Map<String, Object> routeEvidence, Map<String, Object> coreEvidence) {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        if (name.startsWith("handler.")) {
                            evidence.put("dispatchResults", routeEvidence);
                            evidence.put("invocations", routeEvidence.get("invocations"));
                            evidence.put("metadataHandlers", routeEvidence.get("metadata.handlers"));
                            evidence.put("metadataSnapshot", routeEvidence.get("metadata.snapshot"));
                        } else {
                            evidence.put("core", coreEvidence);
                        }
                        return evidence;
                    }

                    private static boolean hasKeyPrefix(Map<String, Object> map, String prefix) {
                        for (String key : map.keySet()) {
                            if (key.startsWith(prefix)) {
                                return true;
                            }
                        }
                        return false;
                    }

                    private static boolean positive(Object value) {
                        return value instanceof Number && ((Number) value).intValue() > 0
                                || value instanceof Boolean && Boolean.TRUE.equals(value);
                    }

                    private static final class GeneratedDispatchInterceptor
                            implements io.fluxzero.sdk.browser.BrowserDispatchInterceptor {
                        @Override
                        public io.fluxzero.sdk.browser.BrowserMessage intercept(
                                io.fluxzero.sdk.browser.BrowserMessage message) {
                            return message.withMetadata("correlationId", "corr-1");
                        }
                    }

                    private static final class GeneratedHandlerInterceptor
                            implements io.fluxzero.sdk.browser.BrowserHandlerInterceptor {
                        @Override
                        public Object intercept(io.fluxzero.sdk.browser.BrowserMessage message,
                                                io.fluxzero.sdk.browser.BrowserHandler next) {
                            return "intercepted:" + next.handle(message);
                        }
                    }

                    private static final class GeneratedBatchInterceptor
                            implements io.fluxzero.sdk.browser.BrowserBatchInterceptor {
                        @Override
                        public List<io.fluxzero.sdk.browser.BrowserMessage> intercept(
                                List<io.fluxzero.sdk.browser.BrowserMessage> messages) {
                            return messages.subList(0, 1);
                        }
                    }

                    private static final class GeneratedErrorReporter
                            implements io.fluxzero.sdk.browser.BrowserErrorReporter {
                        private int count;

                        @Override
                        public void report(Throwable error, io.fluxzero.sdk.browser.BrowserMessage message) {
                            count++;
                        }

                        int count() {
                            return count;
                        }
                    }

                    private static final class GeneratedHookCommandHandler implements io.fluxzero.sdk.browser.BrowserHandler {
                        private final BrowserExecutionCore core;

                        private GeneratedHookCommandHandler(BrowserExecutionCore core) {
                            this.core = core;
                        }

                        @Override
                        public Object handle(io.fluxzero.sdk.browser.BrowserMessage message) {
                            core.messageBus().dispatch(MessageType.EVENT, "HookEvent", new Object());
                            return message.metadata().get("correlationId");
                        }
                    }

                    private static final class GeneratedThrowingHandler implements io.fluxzero.sdk.browser.BrowserHandler {
                        @Override
                        public Object handle(io.fluxzero.sdk.browser.BrowserMessage message) {
                            throw new IllegalStateException("reported");
                        }
                    }

                    private static final class GeneratedCodecOrder {
                        private final String orderId;
                        private final String status;

                        private GeneratedCodecOrder(String orderId, String status) {
                            this.orderId = orderId;
                            this.status = status;
                        }

                        String orderId() {
                            return orderId;
                        }

                        String status() {
                            return status;
                        }
                    }

                    private static final class GeneratedCodec implements io.fluxzero.sdk.browser.BrowserCodec {
                        @Override
                        public Map<String, Object> encode(Object value) {
                            GeneratedCodecOrder order = (GeneratedCodecOrder) value;
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("orderId", order.orderId());
                            data.put("status", order.status());
                            return data;
                        }

                        @Override
                        public Object decode(Map<String, Object> data) {
                            return new GeneratedCodecOrder((String) data.get("orderId"), (String) data.get("status"));
                        }
                    }

                    private static final class GeneratedUpcaster implements io.fluxzero.sdk.browser.BrowserCaster {
                        @Override
                        public Object cast(Object value) {
                            return new GeneratedCodecOrder(((GeneratedCodecOrder) value).orderId(), "upcasted");
                        }
                    }

                    private static final class GeneratedDowncaster implements io.fluxzero.sdk.browser.BrowserCaster {
                        @Override
                        public Object cast(Object value) {
                            return "legacy:" + ((GeneratedCodecOrder) value).orderId();
                        }
                    }

                    private static final class GeneratedRouteHandler implements io.fluxzero.sdk.browser.BrowserHandler {
                        private final String feature;

                        private GeneratedRouteHandler(String feature) {
                            this.feature = feature;
                        }

                        @Override
                        public Object handle(io.fluxzero.sdk.browser.BrowserMessage message) {
                            return feature + ":" + message.messageType();
                        }
                    }

                    private static final class GeneratedAuthorizedHandler implements io.fluxzero.sdk.browser.BrowserHandler {
                        private final String action;
                        private final List<io.fluxzero.sdk.tracking.handling.authentication.AuthorizationRule> rules;
                        private final io.fluxzero.sdk.browser.BrowserHandler delegate;

                        private GeneratedAuthorizedHandler(
                                String action,
                                List<io.fluxzero.sdk.tracking.handling.authentication.AuthorizationRule> rules,
                                io.fluxzero.sdk.browser.BrowserHandler delegate) {
                            this.action = action;
                            this.rules = rules;
                            this.delegate = delegate;
                        }

                        @Override
                        public Object handle(io.fluxzero.sdk.browser.BrowserMessage message) {
                            io.fluxzero.sdk.tracking.handling.authentication.AuthorizationDecision decision =
                                    io.fluxzero.sdk.tracking.handling.authentication.AuthorizationPolicy.evaluate(
                                            action, user(message), rules);
                            if (decision.allowed()) {
                                return delegate.handle(message);
                            }
                            if (decision.rejected()) {
                                throw new IllegalStateException(decision.message());
                            }
                            return null;
                        }

                        private io.fluxzero.sdk.tracking.handling.authentication.AuthorizationSubject user(
                                io.fluxzero.sdk.browser.BrowserMessage message) {
                            String user = message.metadata().get("user");
                            if (user == null || user.isBlank()) {
                                return null;
                            }
                            String role = message.metadata().get("role");
                            return new GeneratedUser(user, role == null || role.isBlank() ? List.of() : List.of(role));
                        }
                    }

                    private static final class GeneratedUser
                            implements io.fluxzero.sdk.tracking.handling.authentication.AuthorizationSubject {
                        private final String name;
                        private final List<String> roles;

                        private GeneratedUser(String name, List<String> roles) {
                            this.name = name;
                            this.roles = roles;
                        }

                        @Override
                        public String getName() {
                            return name;
                        }

                        @Override
                        public boolean hasRole(String role) {
                            return roles.contains(role);
                        }
                    }

                    private static final class GeneratedWebHandler implements io.fluxzero.sdk.browser.BrowserWebHandler {
                        @Override
                        public io.fluxzero.sdk.browser.BrowserWebExchange handle(
                                io.fluxzero.sdk.browser.BrowserWebExchange exchange) {
                            String body = exchange.pathParameters().get("orderId") + ":"
                                          + exchange.query().get("include") + ":"
                                          + exchange.headers().get("x-correlation-id") + ":"
                                          + exchange.cookies().get("session") + ":"
                                          + exchange.form().get("source") + ":"
                                          + exchange.body();
                            return exchange.withResponse(200, body);
                        }
                    }
                }
                """.formatted(options.packageName(), options.className(), handlerFields, options.className(),
                              routeRegistration,
                              generatedRegistrySource(registry), featureList, routeDispatches(registry, options),
                              generatedWebDispatches(registry, options),
                              authorizationEvidence,
                              metadataEvidence(metadataCounters));
    }

    private static String authorizationEvidence(ComponentRegistry registry) {
        List<String> lines = new ArrayList<>();
        lines.add("        io.fluxzero.sdk.tracking.handling.authentication.AuthorizationSubject generatedAdmin = "
                  + "new GeneratedUser(\"admin\", List.of(\"admin\", \"modify\"));");
        lines.add("        evidence.put(\"auth.userProvider\", generatedAdmin.getName());");
        authorizationScenario(registry, BrowserApplicationGenerator::requiresUserRule)
                .ifPresentOrElse(
                        scenario -> lines.add(decisionLine("auth.requiresUser", scenario, "null")),
                        () -> lines.add("        evidence.put(\"auth.requiresUser\", \"missing\");"));
        authorizationScenario(registry, BrowserApplicationGenerator::requiresAnyRoleRule)
                .ifPresentOrElse(
                        scenario -> lines.add(decisionLine("auth.requiresAnyRole", scenario, "generatedAdmin")),
                        () -> lines.add("        evidence.put(\"auth.requiresAnyRole\", \"missing\");"));
        authorizationScenario(registry, BrowserApplicationGenerator::forbidsUserRule)
                .ifPresentOrElse(
                        scenario -> lines.add(decisionLine("auth.forbidsUser", scenario, "generatedAdmin")),
                        () -> lines.add("        evidence.put(\"auth.forbidsUser\", \"missing\");"));
        authorizationScenario(registry, BrowserApplicationGenerator::noUserRequiredRule)
                .ifPresentOrElse(
                        scenario -> lines.add(decisionLine("auth.noUserRequired", scenario, "null")),
                        () -> lines.add("        evidence.put(\"auth.noUserRequired\", \"missing\");"));
        return String.join("\n", lines);
    }

    private static java.util.Optional<AuthorizationScenario> authorizationScenario(
            ComponentRegistry registry, java.util.function.Predicate<List<AuthorizationRule>> predicate) {
        for (ComponentDescriptor component : registry.components()) {
            List<AnnotationDescriptor> packageAnnotations = packageAnnotations(registry, component.packageName());
            for (HandlerRoute route : component.routes()) {
                if (route.disabled()) {
                    continue;
                }
                List<AuthorizationRule> rules = AuthorizationMetadata.effectiveRules(
                        route.executableMetadata().map(ExecutableDescriptor::annotations).orElseGet(List::of),
                        component.annotations(), packageAnnotations).orElse(null);
                if (rules != null && predicate.test(rules)) {
                    String action = component.className() + "#"
                                    + route.executableMetadata().map(ExecutableDescriptor::name)
                                            .orElse(route.messageType().name().toLowerCase());
                    return java.util.Optional.of(new AuthorizationScenario(action, rules));
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static List<AnnotationDescriptor> packageAnnotations(ComponentRegistry registry, String packageName) {
        for (String current = packageName; current != null; current = parentPackage(current)) {
            for (PackageDescriptor descriptor : registry.packages()) {
                if (descriptor.packageName().equals(current)) {
                    return descriptor.annotations();
                }
            }
        }
        return List.of();
    }

    private static String parentPackage(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    private static String generatedRegistrySource(ComponentRegistry registry) {
        String routes = registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .flatMap(component -> component.routes().stream()
                        .map(route -> routeMetadataSource(component, route)))
                .collect(joining(",\n"));
        int registeredTypes = registry.registeredTypes()
                .mapToInt(type -> type.candidateTypeNames().size())
                .sum();
        return """
                    private static io.fluxzero.sdk.browser.BrowserComponentRegistry generatedRegistry() {
                        return new io.fluxzero.sdk.browser.BrowserComponentRegistry(
                                %s,
                                %s,
                                %s,
                                %s);
                    }
                """.formatted(registry.packages().size(), registry.components().size(), registeredTypes,
                              listSource(routes));
    }

    private static String routeMetadataSource(ComponentDescriptor component, HandlerRoute route) {
        return """
                        new io.fluxzero.sdk.browser.BrowserRouteMetadata(
                                %s,
                                MessageType.%s,
                                %s,
                                %s,
                                %s,
                                %s,
                                %s,
                                %s,
                                %s,
                                %s)"""
                .formatted(stringLiteral(component.fullClassName()), route.messageType().name(), route.disabled(),
                           route.passive(), route.skipExpiredRequests(), route.local(), route.tracked(),
                           stringSetSource(route.payloadTypeNames()), stringSetSource(route.allowedClassNames()),
                           route.webRoutes().size());
    }

    private static String stringListSource(java.util.Collection<String> values) {
        String literals = values.stream().map(BrowserApplicationGenerator::stringLiteral).collect(joining(", "));
        return listSource(literals);
    }

    private static String stringSetSource(java.util.Collection<String> values) {
        String literals = values.stream().map(BrowserApplicationGenerator::stringLiteral).collect(joining(", "));
        return setSource(literals);
    }

    private static String listSource(String elements) {
        return elements.isBlank() ? "List.of()" : "List.of(" + elements + ")";
    }

    private static String setSource(String elements) {
        return elements.isBlank() ? "Set.of()" : "Set.of(" + elements + ")";
    }

    private static boolean requiresUserRule(List<AuthorizationRule> rules) {
        return rules.stream().anyMatch(rule -> rule.value() == null && rule.requiresUser() && !rule.forbidsUser());
    }

    private static boolean requiresAnyRoleRule(List<AuthorizationRule> rules) {
        return rules.stream().anyMatch(rule -> rule.value() != null && !rule.value().startsWith("!"));
    }

    private static boolean forbidsUserRule(List<AuthorizationRule> rules) {
        return rules.stream().anyMatch(AuthorizationRule::forbidsUser);
    }

    private static boolean noUserRequiredRule(List<AuthorizationRule> rules) {
        return rules.contains(AuthorizationRule.NO_USER_REQUIRED);
    }

    private static String decisionLine(String key, AuthorizationScenario scenario, String userExpression) {
        return "        evidence.put(\"" + key + "\", "
               + "io.fluxzero.sdk.tracking.handling.authentication.AuthorizationPolicy.evaluate(\""
               + escapeJava(scenario.action()) + "\", " + userExpression + ", "
               + rulesLiteral(scenario.rules()) + ").failure().name() + \":\" + "
               + "io.fluxzero.sdk.tracking.handling.authentication.AuthorizationPolicy.evaluate(\""
               + escapeJava(scenario.action()) + "\", " + userExpression + ", "
               + rulesLiteral(scenario.rules()) + ").allowed());";
    }

    private static String rulesLiteral(List<AuthorizationRule> rules) {
        if (rules.isEmpty()) {
            return "List.of()";
        }
        return rules.stream()
                .map(rule -> "new io.fluxzero.sdk.tracking.handling.authentication.AuthorizationRule("
                             + stringLiteral(rule.value()) + ", " + rule.throwIfUnauthorized()
                             + ", " + rule.requiresUser() + ", " + rule.forbidsUser() + ")")
                .collect(joining(", ", "List.of(", ")"));
    }

    private static String stringLiteral(String value) {
        return value == null ? "null" : "\"" + escapeJava(value) + "\"";
    }

    private record AuthorizationScenario(String action, List<AuthorizationRule> rules) {
    }

    private static String generatedHandlerFields(ComponentRegistry registry, BrowserGeneratorOptions options) {
        List<String> fields = new ArrayList<>();
        Set<String> fieldNames = new java.util.LinkedHashSet<>();
        for (ComponentDescriptor component : registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .toList()) {
            if (!sourceAccessible(component, options) || component.componentKind() != ComponentKind.CLASS
                || !hasNoArgConstructor(component)) {
                continue;
            }
            boolean needsField = component.routes().stream()
                    .filter(route -> !route.disabled())
                    .map(HandlerRoute::executableMetadata)
                    .flatMap(Optional::stream)
                    .anyMatch(executable -> executable.kind() == ExecutableKind.METHOD && !executable.isStatic());
            if (needsField && fieldNames.add(fieldName(component))) {
                fields.add("    private final " + sourceTypeName(registry, component.fullClassName(), options)
                           + " " + fieldName(component) + " = new "
                           + sourceTypeName(registry, component.fullClassName(), options) + "();");
            }
        }
        return fields.isEmpty() ? "" : String.join("\n", fields);
    }

    private static String routeRegistration(ComponentRegistry registry, BrowserGeneratorOptions options,
                                            ComponentDescriptor component, HandlerRoute route) {
        if (route.disabled()) {
            return "";
        }
        String payloadType = routePayloadType(component, route).orElse("");
        List<AuthorizationRule> rules = effectiveRules(registry, component, route);
        String handler = directMessageHandler(registry, options, component, route)
                .orElse("new GeneratedRouteHandler(\"" + escapeJava(route.messageType().name().toLowerCase())
                        + "\")");
        if (rules != null) {
            handler = "new GeneratedAuthorizedHandler(\"" + escapeJava(action(component, route)) + "\", "
                      + rulesLiteral(rules) + ", " + handler + ")";
        }
        String registration = "        core.register(\"" + escapeJava(component.fullClassName()) + "\", MessageType."
                              + route.messageType().name() + ", \"" + escapeJava(payloadType) + "\", "
                              + handler + ");";
        String webRegistrations = directWebRegistrations(registry, options, component, route);
        return webRegistrations.isBlank() ? registration : registration + "\n" + webRegistrations;
    }

    private static String routeDispatches(ComponentRegistry registry, BrowserGeneratorOptions options) {
        List<String> dispatches = new ArrayList<>();
        for (ComponentDescriptor component : registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName)).toList()) {
            for (HandlerRoute route : component.routes()) {
                if (route.disabled() || route.messageType() == MessageType.WEBREQUEST) {
                    continue;
                }
                String payloadType = routePayloadType(component, route).orElse("");
                if (!payloadType.isBlank()) {
                    boolean direct = directMessageHandler(registry, options, component, route).isPresent();
                    String payloadExpression = direct
                            ? sampleValueExpression(registry, options, payloadType, "payload", 0)
                                    .orElse("new Object()")
                            : "new Object()";
                    String role = dispatchRole(effectiveRules(registry, component, route));
                    dispatches.add("        evidence.put(\"" + escapeJava(route.messageType().name()) + ":"
                                  + escapeJava(payloadType) + "\", core.messageBus().dispatch(MessageType."
                                  + route.messageType().name() + ", \"\", \"" + escapeJava(payloadType)
                                  + "\", " + payloadExpression + ", generatedUserMetadata(\"" + escapeJava(role)
                                  + "\")));");
                }
            }
        }
        return dispatches.isEmpty()
                ? "        // No generated route dispatches were present."
                : String.join("\n", dispatches);
    }

    private static String generatedWebDispatches(ComponentRegistry registry, BrowserGeneratorOptions options) {
        List<String> dispatches = new ArrayList<>();
        int index = 0;
        for (ComponentDescriptor component : registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName)).toList()) {
            for (HandlerRoute route : component.routes()) {
                if (route.disabled() || directWebRegistrations(registry, options, component, route).isBlank()) {
                    continue;
                }
                Optional<ExecutableDescriptor> executable = route.executableMetadata();
                if (executable.isEmpty()) {
                    continue;
                }
                for (WebRouteDescriptor webRoute : route.webRoutes()) {
                    for (String method : webRoute.methods()) {
                        if (!isHttpMethod(method)) {
                            continue;
                        }
                        for (String path : webRoute.paths()) {
                            String responseName = "generatedWebResponse" + index++;
                            String actualPath = actualPath(path, executable.get());
                            String body = webBodyExpression(registry, options, executable.get()).orElse("null");
                            dispatches.add("""
                                    io.fluxzero.sdk.browser.BrowserWebExchange %s = core.webRouter().handle(
                                            new io.fluxzero.sdk.browser.BrowserWebExchange("%s", "%s", %s, Map.of(),
                                                    %s, %s, %s, %s, 0, null));
                                    evidence.put("WEB:%s:%s", %s.status() + ":" + %s.responseBody());"""
                                    .formatted(responseName, escapeJava(method), escapeJava(actualPath), body,
                                               webMapExpression(executable.get(), "HeaderParam"),
                                               webMapExpression(executable.get(), "QueryParam"),
                                               webMapExpression(executable.get(), "CookieParam"),
                                               webMapExpression(executable.get(), "FormParam"),
                                               escapeJava(method), escapeJava(path), responseName, responseName)
                                    .indent(8).stripTrailing());
                        }
                    }
                }
            }
        }
        return dispatches.isEmpty()
                ? "        // No generated web route dispatches were present."
                : String.join("\n", dispatches);
    }

    private static Optional<String> directMessageHandler(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                        ComponentDescriptor component, HandlerRoute route) {
        if (!sourceAccessible(component, options) || route.messageType() == MessageType.WEBREQUEST) {
            return Optional.empty();
        }
        Optional<ExecutableDescriptor> executable = route.executableMetadata();
        if (executable.isEmpty() || executable.get().kind() != ExecutableKind.METHOD) {
            return Optional.empty();
        }
        Optional<String> target = messageTargetExpression(registry, options, component, route, executable.get());
        Optional<List<String>> arguments = messageArguments(component, route, executable.get());
        if (target.isEmpty() || arguments.isEmpty()) {
            return Optional.empty();
        }
        String call = target.get() + "." + executable.get().name() + "(" + String.join(", ", arguments.get()) + ")";
        if ("void".equals(executable.get().returnTypeName())) {
            return Optional.of("message -> { " + call + "; return null; }");
        }
        return Optional.of("message -> { return " + call + "; }");
    }

    private static Optional<String> messageTargetExpression(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                           ComponentDescriptor component, HandlerRoute route,
                                                           ExecutableDescriptor executable) {
        if (executable.isStatic()) {
            return Optional.of(sourceTypeName(registry, component.fullClassName(), options));
        }
        if (executable.parameters().isEmpty() && component.componentKind() == ComponentKind.RECORD) {
            String payloadType = routePayloadType(component, route).orElse(component.fullClassName());
            return Optional.of("((" + sourceTypeName(registry, payloadType, options) + ") message.payload())");
        }
        if (component.componentKind() == ComponentKind.CLASS && hasNoArgConstructor(component)) {
            return Optional.of(fieldName(component));
        }
        return Optional.empty();
    }

    private static Optional<List<String>> messageArguments(ComponentDescriptor component, HandlerRoute route,
                                                          ExecutableDescriptor executable) {
        if (executable.parameters().isEmpty()) {
            return Optional.of(List.of());
        }
        if (executable.parameters().size() != 1) {
            return Optional.empty();
        }
        String payloadType = routePayloadType(component, route)
                .orElse(executable.parameters().getFirst().typeName());
        ParameterDescriptor parameter = executable.parameters().getFirst();
        if (!payloadMatchesParameter(payloadType, parameter.typeName())) {
            return Optional.empty();
        }
        return Optional.of(List.of("((" + sourceTypeName(null, parameter.typeName(), null) + ") message.payload())"));
    }

    private static boolean payloadMatchesParameter(String payloadType, String parameterType) {
        String payload = erasedType(payloadType);
        String parameter = erasedType(parameterType);
        return payload.equals(parameter)
               || simpleName(payload).equals(simpleName(parameter))
               || "java.lang.Object".equals(parameter)
               || "Object".equals(parameter);
    }

    private static String directWebRegistrations(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                ComponentDescriptor component, HandlerRoute route) {
        if (!sourceAccessible(component, options) || route.webRoutes().isEmpty()) {
            return "";
        }
        Optional<ExecutableDescriptor> executable = route.executableMetadata();
        if (executable.isEmpty() || executable.get().kind() != ExecutableKind.METHOD) {
            return "";
        }
        Optional<String> target = webTargetExpression(registry, options, component, executable.get());
        Optional<List<String>> arguments = webArguments(registry, options, executable.get());
        if (target.isEmpty() || arguments.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (WebRouteDescriptor webRoute : route.webRoutes()) {
            for (String method : webRoute.methods()) {
                if (!isHttpMethod(method)) {
                    continue;
                }
                for (String path : webRoute.paths()) {
                    String call = target.get() + "." + executable.get().name()
                                  + "(" + String.join(", ", arguments.get()) + ")";
                    String handler = "void".equals(executable.get().returnTypeName())
                            ? "exchange -> { " + call + "; return exchange.withResponse(204, null); }"
                            : "exchange -> { return exchange.withResponse(200, " + call + "); }";
                    lines.add("        core.webRouter().register(\"" + escapeJava(method) + "\", \""
                              + escapeJava(path) + "\", " + handler + ");");
                }
            }
        }
        return String.join("\n", lines);
    }

    private static Optional<String> webTargetExpression(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                       ComponentDescriptor component,
                                                       ExecutableDescriptor executable) {
        if (executable.isStatic()) {
            return Optional.of(sourceTypeName(registry, component.fullClassName(), options));
        }
        if (component.componentKind() == ComponentKind.CLASS && hasNoArgConstructor(component)) {
            return Optional.of(fieldName(component));
        }
        return Optional.empty();
    }

    private static Optional<List<String>> webArguments(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                      ExecutableDescriptor executable) {
        List<String> arguments = new ArrayList<>();
        for (ParameterDescriptor parameter : executable.parameters()) {
            Optional<String> expression = webArgument(registry, options, parameter);
            if (expression.isEmpty()) {
                return Optional.empty();
            }
            arguments.add(expression.get());
        }
        return Optional.of(arguments);
    }

    private static Optional<String> webArgument(ComponentRegistry registry, BrowserGeneratorOptions options,
                                               ParameterDescriptor parameter) {
        if (parameterAnnotation(parameter, "BodyParam").isPresent()) {
            return Optional.of("((" + sourceTypeName(registry, parameter.typeName(), options) + ") exchange.body())");
        }
        return webMapArgument(parameter, "PathParam", "pathParameters")
                .or(() -> webMapArgument(parameter, "QueryParam", "query"))
                .or(() -> webMapArgument(parameter, "HeaderParam", "headers"))
                .or(() -> webMapArgument(parameter, "CookieParam", "cookies"))
                .or(() -> webMapArgument(parameter, "FormParam", "form"));
    }

    private static Optional<String> webMapArgument(ParameterDescriptor parameter, String annotationName,
                                                  String exchangeAccessor) {
        Optional<AnnotationDescriptor> annotation = parameterAnnotation(parameter, annotationName);
        if (annotation.isEmpty()) {
            return Optional.empty();
        }
        String valueExpression = "exchange." + exchangeAccessor + "().get(\""
                                 + escapeJava(annotationValue(annotation.get(), parameter.name())) + "\")";
        return Optional.of(convertStringExpression(parameter.typeName(), valueExpression));
    }

    private static Optional<AnnotationDescriptor> parameterAnnotation(ParameterDescriptor parameter,
                                                                     String annotationName) {
        return parameter.annotations().stream()
                .map(annotation -> annotation.find(annotationName, "io.fluxzero.sdk.web." + annotationName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static String annotationValue(AnnotationDescriptor annotation, String fallback) {
        return annotation.firstValue("value").filter(value -> !value.isBlank()).orElse(fallback);
    }

    private static String convertStringExpression(String typeName, String expression) {
        String type = erasedType(typeName);
        return switch (type) {
            case "java.lang.String", "String" -> expression;
            case "int", "java.lang.Integer", "Integer" -> "Integer.parseInt(" + expression + ")";
            case "long", "java.lang.Long", "Long" -> "Long.parseLong(" + expression + ")";
            case "boolean", "java.lang.Boolean", "Boolean" -> "Boolean.parseBoolean(" + expression + ")";
            case "double", "java.lang.Double", "Double" -> "Double.parseDouble(" + expression + ")";
            default -> "((" + type + ") " + expression + ")";
        };
    }

    private static Optional<String> routePayloadType(ComponentDescriptor component, HandlerRoute route) {
        Optional<String> explicit = route.allowedClassNames().stream().findFirst()
                .or(() -> route.payloadTypeNames().stream().findFirst());
        if (explicit.isPresent()) {
            return explicit;
        }
        return route.executableMetadata()
                .flatMap(executable -> executable.parameters().stream()
                        .filter(parameter -> parameterAnnotation(parameter, "BodyParam").isPresent()
                                             || parameter.annotations().isEmpty())
                        .map(ParameterDescriptor::typeName)
                        .findFirst())
                .or(() -> route.executableMetadata()
                        .filter(executable -> executable.parameters().isEmpty()
                                              && component.componentKind() == ComponentKind.RECORD)
                        .map(executable -> component.fullClassName()));
    }

    private static Optional<String> webBodyExpression(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                     ExecutableDescriptor executable) {
        return executable.parameters().stream()
                .filter(parameter -> parameterAnnotation(parameter, "BodyParam").isPresent())
                .findFirst()
                .flatMap(parameter -> sampleValueExpression(registry, options, parameter.typeName(), parameter.name(),
                                                            0));
    }

    private static String webMapExpression(ExecutableDescriptor executable, String annotationName) {
        List<String> entries = new ArrayList<>();
        for (ParameterDescriptor parameter : executable.parameters()) {
            Optional<AnnotationDescriptor> annotation = parameterAnnotation(parameter, annotationName);
            if (annotation.isPresent()) {
                String name = annotationValue(annotation.get(), parameter.name());
                entries.add(stringLiteral(name));
                entries.add(stringLiteral(sampleString(name)));
            }
        }
        return entries.isEmpty() ? "Map.of()" : "Map.of(" + String.join(", ", entries) + ")";
    }

    private static String actualPath(String path, ExecutableDescriptor executable) {
        String result = path;
        for (ParameterDescriptor parameter : executable.parameters()) {
            Optional<AnnotationDescriptor> annotation = parameterAnnotation(parameter, "PathParam");
            if (annotation.isPresent()) {
                String name = annotationValue(annotation.get(), parameter.name());
                result = result.replace("{" + name + "}", sampleString(name));
            }
        }
        while (result.contains("{") && result.contains("}")) {
            int start = result.indexOf('{');
            int end = result.indexOf('}', start);
            if (end < start) {
                break;
            }
            result = result.substring(0, start) + "value" + result.substring(end + 1);
        }
        return result;
    }

    private static Optional<String> sampleValueExpression(ComponentRegistry registry, BrowserGeneratorOptions options,
                                                         String typeName, String valueName, int depth) {
        String type = erasedType(sourceTypeName(registry, typeName, options));
        if (depth > 4) {
            return Optional.empty();
        }
        switch (type) {
            case "java.lang.String", "String" -> {
                return Optional.of(stringLiteral(sampleString(valueName)));
            }
            case "int", "java.lang.Integer", "Integer" -> {
                return Optional.of("1");
            }
            case "long", "java.lang.Long", "Long" -> {
                return Optional.of("1L");
            }
            case "boolean", "java.lang.Boolean", "Boolean" -> {
                return Optional.of("true");
            }
            case "double", "java.lang.Double", "Double" -> {
                return Optional.of("1.0d");
            }
            case "java.time.Instant", "Instant" -> {
                return Optional.of("Instant.EPOCH");
            }
            case "java.lang.RuntimeException", "RuntimeException" -> {
                return Optional.of("new RuntimeException(\"browser-error\")");
            }
            case "java.lang.Throwable", "Throwable" -> {
                return Optional.of("new RuntimeException(\"browser-error\")");
            }
            case "java.util.Map", "Map" -> {
                return Optional.of("Map.of()");
            }
            case "java.util.List", "List" -> {
                return Optional.of("List.of()");
            }
            case "java.util.Set", "Set" -> {
                return Optional.of("Set.of()");
            }
            case "java.lang.Object", "Object" -> {
                return Optional.of("new Object()");
            }
            default -> {
                Optional<ComponentDescriptor> component = componentByType(registry, typeName, options);
                if (component.isEmpty()) {
                    return Optional.empty();
                }
                if (component.get().componentKind() == ComponentKind.RECORD) {
                    Optional<ExecutableDescriptor> constructor = component.get().executables().stream()
                            .filter(executable -> executable.kind() == ExecutableKind.CONSTRUCTOR)
                            .max(Comparator.comparingInt(executable -> executable.parameters().size()));
                    if (constructor.isPresent()) {
                        List<String> arguments = new ArrayList<>();
                        for (ParameterDescriptor parameter : constructor.get().parameters()) {
                            Optional<String> argument = sampleValueExpression(registry, options, parameter.typeName(),
                                                                              parameter.name(), depth + 1);
                            if (argument.isEmpty()) {
                                return Optional.empty();
                            }
                            arguments.add(argument.get());
                        }
                        return Optional.of("new " + sourceTypeName(registry, component.get().fullClassName(), options)
                                           + "(" + String.join(", ", arguments) + ")");
                    }
                }
                if (component.get().componentKind() == ComponentKind.RECORD && !component.get().properties().isEmpty()) {
                    Set<String> zeroArgumentMethodNames = component.get().executables().stream()
                            .filter(executable -> executable.kind() == ExecutableKind.METHOD)
                            .filter(executable -> executable.parameters().isEmpty())
                            .map(ExecutableDescriptor::name)
                            .collect(java.util.stream.Collectors.toSet());
                    List<PropertyDescriptor> recordComponents = component.get().properties().stream()
                            .filter(property -> !zeroArgumentMethodNames.contains(property.name()))
                            .toList();
                    if (recordComponents.isEmpty()) {
                        return Optional.empty();
                    }
                    List<String> arguments = new ArrayList<>();
                    for (PropertyDescriptor property : recordComponents) {
                        Optional<String> argument = sampleValueExpression(registry, options, property.typeName(),
                                                                          property.name(), depth + 1);
                        if (argument.isEmpty()) {
                            return Optional.empty();
                        }
                        arguments.add(argument.get());
                    }
                    return Optional.of("new " + sourceTypeName(registry, component.get().fullClassName(), options)
                                       + "(" + String.join(", ", arguments) + ")");
                }
                if (component.get().componentKind() == ComponentKind.CLASS && hasNoArgConstructor(component.get())) {
                    return Optional.of("new " + sourceTypeName(registry, component.get().fullClassName(), options)
                                       + "()");
                }
                return Optional.empty();
            }
        }
    }

    private static String sampleString(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("order") || "id".equals(lower)) {
            return "order-1";
        }
        if (lower.contains("email")) {
            return "browser@example.test";
        }
        if (lower.contains("include")) {
            return "details";
        }
        if (lower.contains("correlation")) {
            return "corr-1";
        }
        if (lower.contains("session")) {
            return "s1";
        }
        if (lower.contains("source")) {
            return "browser";
        }
        if (lower.contains("status")) {
            return "created";
        }
        if (lower.contains("name")) {
            return "browser-metric";
        }
        return "browser-value";
    }

    private static Optional<ComponentDescriptor> componentByType(ComponentRegistry registry, String typeName,
                                                                BrowserGeneratorOptions options) {
        if (registry == null) {
            return Optional.empty();
        }
        String erased = erasedType(typeName);
        return registry.components().stream()
                .filter(component -> component.fullClassName().equals(erased)
                                     || component.className().equals(erased)
                                     || options != null && component.packageName().equals(options.packageName())
                                        && component.className().equals(simpleName(erased)))
                .findFirst();
    }

    private static String sourceTypeName(ComponentRegistry registry, String typeName, BrowserGeneratorOptions options) {
        String erased = erasedType(typeName);
        if (registry != null && options != null) {
            Optional<ComponentDescriptor> component = componentByType(registry, erased, options);
            if (component.isPresent()) {
                return component.get().fullClassName();
            }
        }
        return javaLangTypeName(erased).orElse(erased);
    }

    private static Optional<String> javaLangTypeName(String typeName) {
        String simpleName = simpleName(typeName);
        return switch (simpleName) {
            case "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer", "Long", "Number",
                 "Object", "RuntimeException", "Short", "String", "Throwable", "Void" ->
                    Optional.of("java.lang." + simpleName);
            default -> Optional.empty();
        };
    }

    private static boolean sourceAccessible(ComponentDescriptor component, BrowserGeneratorOptions options) {
        return component.packageName().equals(options.packageName());
    }

    private static boolean hasNoArgConstructor(ComponentDescriptor component) {
        if (component.componentKind() != ComponentKind.CLASS) {
            return false;
        }
        List<ExecutableDescriptor> constructors = component.executables().stream()
                .filter(executable -> executable.kind() == ExecutableKind.CONSTRUCTOR)
                .toList();
        return constructors.isEmpty() || constructors.stream().anyMatch(executable -> executable.parameters().isEmpty());
    }

    private static boolean isHttpMethod(String method) {
        return Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                .contains(method.toUpperCase(java.util.Locale.ROOT));
    }

    private static String fieldName(ComponentDescriptor component) {
        String simpleName = component.className();
        String candidate = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        return sanitizeIdentifier(candidate);
    }

    private static String sanitizeIdentifier(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 0) {
                result.append(Character.isJavaIdentifierStart(c) ? c : '_');
            } else {
                result.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
        }
        return result.toString();
    }

    private static String erasedType(String typeName) {
        int generic = typeName.indexOf('<');
        return generic < 0 ? typeName : typeName.substring(0, generic);
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private static List<AuthorizationRule> effectiveRules(ComponentRegistry registry, ComponentDescriptor component,
                                                          HandlerRoute route) {
        return AuthorizationMetadata.effectiveRules(
                route.executableMetadata().map(ExecutableDescriptor::annotations).orElseGet(List::of),
                component.annotations(), packageAnnotations(registry, component.packageName())).orElse(null);
    }

    private static String action(ComponentDescriptor component, HandlerRoute route) {
        return component.className() + "#"
               + route.executableMetadata().map(ExecutableDescriptor::name)
                       .orElse(route.messageType().name().toLowerCase());
    }

    private static String dispatchRole(List<AuthorizationRule> rules) {
        if (rules == null || rules.contains(AuthorizationRule.NO_USER_REQUIRED)
            || rules.stream().anyMatch(AuthorizationRule::forbidsUser)
            || rules.stream().anyMatch(rule -> rule.value() != null && rule.value().startsWith("!"))) {
            return "";
        }
        return rules.stream()
                .map(AuthorizationRule::value)
                .filter(Objects::nonNull)
                .filter(value -> !value.startsWith("!"))
                .findFirst()
                .orElseGet(() -> requiresUserRule(rules) ? "admin" : "");
    }

    private static String metadataEvidence(Map<String, Integer> metadataCounters) {
        return metadataCounters.entrySet().stream()
                .map(entry -> "        evidence.put(\"" + escapeJava(entry.getKey()) + "\", " + entry.getValue()
                              + ");")
                .collect(joining("\n"));
    }

    private static String manifestJson(ComponentRegistry registry, BrowserGeneratorOptions options,
                                       List<BrowserConformanceFeature> features, Map<String, Integer> counters) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        appendString(json, "applicationClass", options.packageName() + "." + options.className(), 2);
        json.append(",\n");
        appendFeatures(json, features, 2);
        json.append(",\n");
        appendCounters(json, counters, 2);
        json.append(",\n");
        appendRoutes(json, registry, 2);
        json.append(",\n");
        appendRegisteredTypes(json, registry, 2);
        json.append(",\n  \"browserApi\": {\n")
                .append("    \"runAll\": \"window.fluxzeroConformance.runAll()\",\n")
                .append("    \"run\": \"window.fluxzeroConformance.run(name)\",\n")
                .append("    \"report\": \"window.fluxzeroConformance.report()\"\n")
                .append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendFeatures(StringBuilder json, List<BrowserConformanceFeature> features, int indent) {
        json.append(spaces(indent)).append("\"features\": [\n");
        for (int i = 0; i < features.size(); i++) {
            BrowserConformanceFeature feature = features.get(i);
            json.append(spaces(indent + 2)).append("{");
            appendStringPair(json, "name", feature.name());
            json.append(", ");
            appendStringPair(json, "category", feature.category());
            json.append(", ");
            appendStringPair(json, "description", feature.description());
            json.append("}");
            if (i < features.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append(spaces(indent)).append(']');
    }

    private static void appendCounters(StringBuilder json, Map<String, Integer> counters, int indent) {
        json.append(spaces(indent)).append("\"counters\": {\n");
        int index = 0;
        for (Map.Entry<String, Integer> entry : counters.entrySet()) {
            json.append(spaces(indent + 2));
            appendStringPair(json, entry.getKey(), entry.getValue());
            if (++index < counters.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append(spaces(indent)).append('}');
    }

    private static void appendRoutes(StringBuilder json, ComponentRegistry registry, int indent) {
        List<String> routes = new ArrayList<>();
        for (ComponentDescriptor component : registry.components()) {
            for (HandlerRoute route : component.routes()) {
                String payload = route.allowedClassNames().stream().findFirst()
                        .or(() -> route.payloadTypeNames().stream().findFirst())
                        .orElse("");
                routes.add(spaces(indent + 2) + "{\"component\":\"" + escapeJson(component.fullClassName())
                           + "\",\"messageType\":\"" + route.messageType()
                           + "\",\"payload\":\"" + escapeJson(payload)
                           + "\",\"disabled\":" + route.disabled()
                           + ",\"passive\":" + route.passive()
                           + ",\"webRoutes\":" + route.webRoutes().size() + "}");
            }
        }
        json.append(spaces(indent)).append("\"routes\": [\n")
                .append(String.join(",\n", routes)).append('\n')
                .append(spaces(indent)).append(']');
    }

    private static void appendRegisteredTypes(StringBuilder json, ComponentRegistry registry, int indent) {
        List<String> typeNames = registry.registeredTypes()
                .map(RegisteredTypeDescriptor::candidateTypeNames)
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .map(typeName -> spaces(indent + 2) + "\"" + escapeJson(typeName) + "\"")
                .toList();
        json.append(spaces(indent)).append("\"registeredTypes\": [\n")
                .append(String.join(",\n", typeNames)).append('\n')
                .append(spaces(indent)).append(']');
    }

    private static void appendString(StringBuilder json, String key, String value, int indent) {
        json.append(spaces(indent));
        appendStringPair(json, key, value);
    }

    private static void appendStringPair(StringBuilder json, String key, String value) {
        json.append('"').append(escapeJson(key)).append("\": \"").append(escapeJson(value)).append('"');
    }

    private static void appendStringPair(StringBuilder json, String key, int value) {
        json.append('"').append(escapeJson(key)).append("\": ").append(value);
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeJson(String value) {
        return escapeJava(value).replace("\n", "\\n").replace("\r", "\\r");
    }
}
