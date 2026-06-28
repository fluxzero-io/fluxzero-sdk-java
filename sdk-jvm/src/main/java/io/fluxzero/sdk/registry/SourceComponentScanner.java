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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;

/**
 * Incubating scanner for local Java source files.
 * <p>
 * The scanner builds a Fluxzero component registry from source text only. It does not invoke javac, load classes, or
 * run annotation processors.
 */
public class SourceComponentScanner {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("\\bimport\\s+(?:static\\s+)?([\\w.]+)(\\.\\*)?\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b(class|record|interface|enum)\\s+(\\w+)\\b");
    private static final Pattern ANNOTATION_TYPE_PATTERN = Pattern.compile("@\\s*interface\\s+(\\w+)\\b");
    private static final Set<String> STANDARD_META_ANNOTATIONS = Set.of(
            "Documented", "Inherited", "Repeatable", "Retention", "Target");
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer", "Long",
            "Object", "Short", "String", "Throwable", "Void");
    private static final Set<String> JAVA_LANG_ANNOTATIONS = Set.of(
            "Deprecated", "FunctionalInterface", "Override", "SafeVarargs", "SuppressWarnings");
    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void");
    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "abstract", "static", "final", "strictfp", "synchronized",
            "native", "default", "transient", "volatile", "sealed", "non-sealed");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("[-+]?\\d[\\d_]*[lL]?");
    private static final Pattern QUALIFIED_CONSTANT = Pattern.compile(
            "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern SOURCE_CONSTANT_DECLARATION = Pattern.compile(
            "\\b(?=[^;{}]*\\bfinal\\b)(?:public|protected|private|static|final|\\s)+"
            + "(String|boolean|byte|short|int|long)\\s+([^;]+);");

    private static final Map<String, String> KNOWN_ANNOTATIONS = Map.ofEntries(
            entry("RegisterType", "io.fluxzero.common.serialization.RegisterType"),
            entry("Consumer", "io.fluxzero.sdk.tracking.Consumer"),
            entry("TrackSelf", "io.fluxzero.sdk.tracking.TrackSelf"),
            entry("LocalHandler", "io.fluxzero.sdk.tracking.handling.LocalHandler"),
            entry("Stateful", "io.fluxzero.sdk.tracking.handling.Stateful"),
            entry("Association", "io.fluxzero.sdk.tracking.handling.Association"),
            entry("Trigger", "io.fluxzero.sdk.tracking.handling.Trigger"),
            entry("HandleMessage", "io.fluxzero.sdk.tracking.handling.HandleMessage"),
            entry("HandleCommand", "io.fluxzero.sdk.tracking.handling.HandleCommand"),
            entry("HandleQuery", "io.fluxzero.sdk.tracking.handling.HandleQuery"),
            entry("HandleEvent", "io.fluxzero.sdk.tracking.handling.HandleEvent"),
            entry("HandleNotification", "io.fluxzero.sdk.tracking.handling.HandleNotification"),
            entry("HandleError", "io.fluxzero.sdk.tracking.handling.HandleError"),
            entry("HandleMetrics", "io.fluxzero.sdk.tracking.handling.HandleMetrics"),
            entry("HandleResult", "io.fluxzero.sdk.tracking.handling.HandleResult"),
            entry("HandleCustom", "io.fluxzero.sdk.tracking.handling.HandleCustom"),
            entry("HandleDocument", "io.fluxzero.sdk.tracking.handling.HandleDocument"),
            entry("HandleSchedule", "io.fluxzero.sdk.tracking.handling.HandleSchedule"),
            entry("HandleWeb", "io.fluxzero.sdk.web.HandleWeb"),
            entry("HandleWebResponse", "io.fluxzero.sdk.web.HandleWebResponse"),
            entry("HandleGet", "io.fluxzero.sdk.web.HandleGet"),
            entry("HandlePost", "io.fluxzero.sdk.web.HandlePost"),
            entry("HandlePut", "io.fluxzero.sdk.web.HandlePut"),
            entry("HandlePatch", "io.fluxzero.sdk.web.HandlePatch"),
            entry("HandleDelete", "io.fluxzero.sdk.web.HandleDelete"),
            entry("HandleHead", "io.fluxzero.sdk.web.HandleHead"),
            entry("HandleOptions", "io.fluxzero.sdk.web.HandleOptions"),
            entry("HandleTrace", "io.fluxzero.sdk.web.HandleTrace"),
            entry("HandleSocketHandshake", "io.fluxzero.sdk.web.HandleSocketHandshake"),
            entry("HandleSocketOpen", "io.fluxzero.sdk.web.HandleSocketOpen"),
            entry("HandleSocketMessage", "io.fluxzero.sdk.web.HandleSocketMessage"),
            entry("HandleSocketPong", "io.fluxzero.sdk.web.HandleSocketPong"),
            entry("HandleSocketClose", "io.fluxzero.sdk.web.HandleSocketClose"),
            entry("Path", "io.fluxzero.sdk.web.Path"),
            entry("PathParam", "io.fluxzero.sdk.web.PathParam"),
            entry("QueryParam", "io.fluxzero.sdk.web.QueryParam"),
            entry("HeaderParam", "io.fluxzero.sdk.web.HeaderParam"),
            entry("CookieParam", "io.fluxzero.sdk.web.CookieParam"),
            entry("FormParam", "io.fluxzero.sdk.web.FormParam"),
            entry("BodyParam", "io.fluxzero.sdk.web.BodyParam"),
            entry("WebParam", "io.fluxzero.sdk.web.WebParam"),
            entry("ApiDoc", "io.fluxzero.sdk.web.ApiDoc"),
            entry("ApiDocInfo", "io.fluxzero.sdk.web.ApiDocInfo"),
            entry("ApiDocServer", "io.fluxzero.sdk.web.ApiDocServer"),
            entry("ApiDocComponent", "io.fluxzero.sdk.web.ApiDocComponent"),
            entry("ApiDocResponse", "io.fluxzero.sdk.web.ApiDocResponse"),
            entry("ApiDocResponses", "io.fluxzero.sdk.web.ApiDocResponses"),
            entry("ApiDocExclude", "io.fluxzero.sdk.web.ApiDocExclude"),
            entry("ServeStatic", "io.fluxzero.sdk.web.ServeStatic"),
            entry("SocketEndpoint", "io.fluxzero.sdk.web.SocketEndpoint"),
            entry("Aggregate", "io.fluxzero.sdk.modeling.Aggregate"),
            entry("EntityId", "io.fluxzero.sdk.modeling.EntityId"),
            entry("Alias", "io.fluxzero.sdk.modeling.Alias"),
            entry("Member", "io.fluxzero.sdk.modeling.Member"),
            entry("AssertLegal", "io.fluxzero.sdk.modeling.AssertLegal"),
            entry("Apply", "io.fluxzero.sdk.persisting.eventsourcing.Apply"),
            entry("InterceptApply", "io.fluxzero.sdk.persisting.eventsourcing.InterceptApply"),
            entry("Searchable", "io.fluxzero.sdk.persisting.search.Searchable"),
            entry("SearchInclude", "io.fluxzero.common.search.SearchInclude"),
            entry("SearchExclude", "io.fluxzero.common.search.SearchExclude"),
            entry("Facet", "io.fluxzero.common.search.Facet"),
            entry("Sortable", "io.fluxzero.common.search.Sortable"),
            entry("Revision", "io.fluxzero.common.serialization.Revision"),
            entry("RoutingKey", "io.fluxzero.sdk.publishing.routing.RoutingKey"),
            entry("ProtectData", "io.fluxzero.sdk.publishing.dataprotection.ProtectData"),
            entry("DropProtectedData", "io.fluxzero.sdk.publishing.dataprotection.DropProtectedData"),
            entry("FilterContent", "io.fluxzero.sdk.common.serialization.FilterContent"),
            entry("Cast", "io.fluxzero.sdk.common.serialization.casting.Cast"),
            entry("Upcast", "io.fluxzero.sdk.common.serialization.casting.Upcast"),
            entry("UpcastRepeatable", "io.fluxzero.sdk.common.serialization.casting.UpcastRepeatable"),
            entry("Downcast", "io.fluxzero.sdk.common.serialization.casting.Downcast"),
            entry("DowncastRepeatable", "io.fluxzero.sdk.common.serialization.casting.DowncastRepeatable"),
            entry("Timeout", "io.fluxzero.sdk.publishing.Timeout"),
            entry("Periodic", "io.fluxzero.sdk.scheduling.Periodic"),
            entry("ValidateWith", "io.fluxzero.sdk.tracking.handling.validation.ValidateWith"),
            entry("Constraint", "jakarta.validation.Constraint"),
            entry("ConvertGroup", "jakarta.validation.groups.ConvertGroup"),
            entry("GroupSequence", "jakarta.validation.GroupSequence"),
            entry("Valid", "jakarta.validation.Valid"),
            entry("AssertFalse", "jakarta.validation.constraints.AssertFalse"),
            entry("AssertTrue", "jakarta.validation.constraints.AssertTrue"),
            entry("DecimalMax", "jakarta.validation.constraints.DecimalMax"),
            entry("DecimalMin", "jakarta.validation.constraints.DecimalMin"),
            entry("Digits", "jakarta.validation.constraints.Digits"),
            entry("Email", "jakarta.validation.constraints.Email"),
            entry("Future", "jakarta.validation.constraints.Future"),
            entry("FutureOrPresent", "jakarta.validation.constraints.FutureOrPresent"),
            entry("Max", "jakarta.validation.constraints.Max"),
            entry("Min", "jakarta.validation.constraints.Min"),
            entry("Negative", "jakarta.validation.constraints.Negative"),
            entry("NegativeOrZero", "jakarta.validation.constraints.NegativeOrZero"),
            entry("NotBlank", "jakarta.validation.constraints.NotBlank"),
            entry("NotEmpty", "jakarta.validation.constraints.NotEmpty"),
            entry("NotNull", "jakarta.validation.constraints.NotNull"),
            entry("Null", "jakarta.validation.constraints.Null"),
            entry("Past", "jakarta.validation.constraints.Past"),
            entry("PastOrPresent", "jakarta.validation.constraints.PastOrPresent"),
            entry("Pattern", "jakarta.validation.constraints.Pattern"),
            entry("Positive", "jakarta.validation.constraints.Positive"),
            entry("PositiveOrZero", "jakarta.validation.constraints.PositiveOrZero"),
            entry("Size", "jakarta.validation.constraints.Size"),
            entry("Length", "io.fluxzero.sdk.tracking.handling.validation.constraints.Length"),
            entry("Range", "io.fluxzero.sdk.tracking.handling.validation.constraints.Range"),
            entry("URL", "io.fluxzero.sdk.tracking.handling.validation.constraints.URL"),
            entry("CreditCardNumber", "io.fluxzero.sdk.tracking.handling.validation.constraints.CreditCardNumber"),
            entry("UUID", "io.fluxzero.sdk.tracking.handling.validation.constraints.UUID"),
            entry("UniqueElements", "io.fluxzero.sdk.tracking.handling.validation.constraints.UniqueElements"),
            entry("RequiresUser", "io.fluxzero.sdk.tracking.handling.authentication.RequiresUser"),
            entry("RequiresAnyRole", "io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole"),
            entry("NoUserRequired", "io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired"),
            entry("ForbidsUser", "io.fluxzero.sdk.tracking.handling.authentication.ForbidsUser"),
            entry("ForbidsAnyRole", "io.fluxzero.sdk.tracking.handling.authentication.ForbidsAnyRole"));
    private static final Map<String, AnnotationDescriptor> BUILT_IN_META_ANNOTATIONS = builtInMetaAnnotations();
    private static final Set<String> SOURCE_ONLY_ANNOTATIONS = Set.of(
            Override.class.getName(),
            SuppressWarnings.class.getName(),
            SafeVarargs.class.getName());

    private static final Map<String, String> KNOWN_TYPES = Map.ofEntries(
            entry("Cache", "io.fluxzero.common.caching.Cache"),
            entry("PropertySource", "io.fluxzero.common.application.PropertySource"),
            entry("TaskScheduler", "io.fluxzero.common.TaskScheduler"),
            entry("DispatchInterceptor", "io.fluxzero.sdk.publishing.DispatchInterceptor"),
            entry("HandlerDecorator", "io.fluxzero.sdk.tracking.handling.HandlerDecorator"),
            entry("HandlerInterceptor", "io.fluxzero.sdk.tracking.handling.HandlerInterceptor"),
            entry("BatchInterceptor", "io.fluxzero.sdk.tracking.BatchInterceptor"),
            entry("ResponseMapper", "io.fluxzero.sdk.tracking.handling.ResponseMapper"),
            entry("WebResponseMapper", "io.fluxzero.sdk.web.WebResponseMapper"),
            entry("Validator", "io.fluxzero.sdk.tracking.handling.validation.Validator"),
            entry("ParameterResolver", "io.fluxzero.common.handling.ParameterResolver"),
            entry("Serializer", "io.fluxzero.sdk.common.serialization.Serializer"),
            entry("JacksonSerializer", "io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer"),
            entry("DocumentSerializer", "io.fluxzero.sdk.persisting.search.DocumentSerializer"),
            entry("CorrelationDataProvider", "io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider"),
            entry("IdentityProvider", "io.fluxzero.sdk.common.IdentityProvider"),
            entry("UserProvider", "io.fluxzero.sdk.tracking.handling.authentication.UserProvider"),
            entry("AbstractUserProvider", "io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider"),
            entry("DefaultResponseMapper", "io.fluxzero.sdk.tracking.handling.DefaultResponseMapper"),
            entry("DefaultWebResponseMapper", "io.fluxzero.sdk.web.DefaultWebResponseMapper"),
            entry("SoftReferenceCache", "io.fluxzero.sdk.persisting.caching.SoftReferenceCache"),
            entry("InMemoryTaskScheduler", "io.fluxzero.common.InMemoryTaskScheduler"));

    private static final Map<String, ComponentCapability> INFRASTRUCTURE_CAPABILITIES = Map.ofEntries(
            entry("io.fluxzero.sdk.publishing.DispatchInterceptor", ComponentCapability.DISPATCH_INTERCEPTOR),
            entry("io.fluxzero.sdk.tracking.handling.HandlerDecorator", ComponentCapability.HANDLER_DECORATOR),
            entry("io.fluxzero.sdk.tracking.handling.HandlerInterceptor", ComponentCapability.HANDLER_INTERCEPTOR),
            entry("io.fluxzero.sdk.tracking.BatchInterceptor", ComponentCapability.BATCH_INTERCEPTOR),
            entry("io.fluxzero.sdk.tracking.handling.ResponseMapper", ComponentCapability.RESPONSE_MAPPER),
            entry("io.fluxzero.sdk.web.WebResponseMapper", ComponentCapability.WEB_RESPONSE_MAPPER),
            entry("io.fluxzero.sdk.tracking.handling.validation.Validator", ComponentCapability.VALIDATOR),
            entry("io.fluxzero.common.handling.ParameterResolver", ComponentCapability.PARAMETER_RESOLVER),
            entry("io.fluxzero.sdk.common.serialization.Serializer", ComponentCapability.SERIALIZER),
            entry("io.fluxzero.sdk.persisting.search.DocumentSerializer", ComponentCapability.DOCUMENT_SERIALIZER),
            entry("io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider",
                  ComponentCapability.CORRELATION_DATA_PROVIDER),
            entry("io.fluxzero.sdk.common.IdentityProvider", ComponentCapability.IDENTITY_PROVIDER),
            entry("io.fluxzero.sdk.tracking.handling.authentication.UserProvider", ComponentCapability.USER_PROVIDER),
            entry("io.fluxzero.common.caching.Cache", ComponentCapability.CACHE),
            entry("io.fluxzero.common.TaskScheduler", ComponentCapability.TASK_SCHEDULER),
            entry("io.fluxzero.common.application.PropertySource", ComponentCapability.PROPERTY_SOURCE));
    private static final Map<String, String> KNOWN_CONSTANT_VALUES = Map.ofEntries(
            entry("Order.HIGHEST_PRECEDENCE", String.valueOf(Integer.MIN_VALUE)),
            entry("io.fluxzero.sdk.common.Order.HIGHEST_PRECEDENCE", String.valueOf(Integer.MIN_VALUE)),
            entry("Ordered.HIGHEST_PRECEDENCE", String.valueOf(Integer.MIN_VALUE)),
            entry("org.springframework.core.Ordered.HIGHEST_PRECEDENCE", String.valueOf(Integer.MIN_VALUE)),
            entry("HIGHEST_PRECEDENCE", String.valueOf(Integer.MIN_VALUE)),
            entry("Integer.MIN_VALUE", String.valueOf(Integer.MIN_VALUE)),
            entry("java.lang.Integer.MIN_VALUE", String.valueOf(Integer.MIN_VALUE)),
            entry("Order.LOWEST_PRECEDENCE", String.valueOf(Integer.MAX_VALUE)),
            entry("io.fluxzero.sdk.common.Order.LOWEST_PRECEDENCE", String.valueOf(Integer.MAX_VALUE)),
            entry("Ordered.LOWEST_PRECEDENCE", String.valueOf(Integer.MAX_VALUE)),
            entry("org.springframework.core.Ordered.LOWEST_PRECEDENCE", String.valueOf(Integer.MAX_VALUE)),
            entry("LOWEST_PRECEDENCE", String.valueOf(Integer.MAX_VALUE)),
            entry("Integer.MAX_VALUE", String.valueOf(Integer.MAX_VALUE)),
            entry("java.lang.Integer.MAX_VALUE", String.valueOf(Integer.MAX_VALUE)),
            entry("ConsumerConfiguration.DEFAULT_MAX_FETCH_BYTES", String.valueOf(100L * 1024L * 1024L)),
            entry("io.fluxzero.sdk.tracking.ConsumerConfiguration.DEFAULT_MAX_FETCH_BYTES",
                  String.valueOf(100L * 1024L * 1024L)),
            entry("DEFAULT_MAX_FETCH_BYTES", String.valueOf(100L * 1024L * 1024L)),
            entry("ConsumerConfiguration.USE_DEFAULT_MAX_FETCH_BYTES", "-1"),
            entry("io.fluxzero.sdk.tracking.ConsumerConfiguration.USE_DEFAULT_MAX_FETCH_BYTES", "-1"),
            entry("USE_DEFAULT_MAX_FETCH_BYTES", "-1"),
            entry("Long.MAX_VALUE", String.valueOf(Long.MAX_VALUE)),
            entry("java.lang.Long.MAX_VALUE", String.valueOf(Long.MAX_VALUE)),
            entry("Long.MIN_VALUE", String.valueOf(Long.MIN_VALUE)),
            entry("java.lang.Long.MIN_VALUE", String.valueOf(Long.MIN_VALUE)));

    private static final Map<String, HandlerSpec> HANDLERS = Map.ofEntries(
            entry("HandleCommand", new HandlerSpec(MessageType.COMMAND, false, false, false, List.of(), false, false)),
            entry("HandleQuery", new HandlerSpec(MessageType.QUERY, false, true, false, List.of(), false, false)),
            entry("HandleEvent", new HandlerSpec(MessageType.EVENT, false, false, false, List.of(), false, false)),
            entry("HandleNotification", new HandlerSpec(MessageType.NOTIFICATION, false, false, false, List.of(), false, false)),
            entry("HandleError", new HandlerSpec(MessageType.ERROR, false, false, false, List.of(), false, false)),
            entry("HandleMetrics", new HandlerSpec(MessageType.METRICS, false, false, false, List.of(), false, false)),
            entry("HandleResult", new HandlerSpec(MessageType.RESULT, false, false, false, List.of(), false, false)),
            entry("HandleCustom", new HandlerSpec(MessageType.CUSTOM, false, false, false, List.of(), false, false)),
            entry("HandleDocument", new HandlerSpec(MessageType.DOCUMENT, false, false, false, List.of(), false, false)),
            entry("HandleSchedule", new HandlerSpec(MessageType.SCHEDULE, false, false, false, List.of(), false, false)),
            entry("HandleWebResponse", new HandlerSpec(MessageType.WEBRESPONSE, false, false, false, List.of(), false, false)),
            entry("HandleWeb", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("ANY"), true, true)),
            entry("HandleGet", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("GET"), true, true)),
            entry("HandlePost", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("POST"), false, true)),
            entry("HandlePut", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PUT"), false, true)),
            entry("HandlePatch", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PATCH"), false, true)),
            entry("HandleDelete", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("DELETE"), false, true)),
            entry("HandleHead", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("HEAD"), false, true)),
            entry("HandleOptions", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("OPTIONS"), false, false)),
            entry("HandleTrace", new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("TRACE"), false, true)),
            entry("HandleSocketHandshake", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_HANDSHAKE"), false, false)),
            entry("HandleSocketOpen", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_OPEN"), false, false)),
            entry("HandleSocketMessage", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_MESSAGE"), false, false)),
            entry("HandleSocketPong", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_PONG"), false, false)),
            entry("HandleSocketClose", new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_CLOSE"), false, false)));
    private static final List<String> HANDLER_NAMES = List.of(
            "HandleCommand", "HandleQuery", "HandleEvent", "HandleNotification", "HandleError", "HandleMetrics",
            "HandleResult", "HandleCustom", "HandleDocument", "HandleSchedule", "HandleWebResponse",
            "HandleGet", "HandlePost", "HandlePut", "HandlePatch", "HandleDelete", "HandleHead", "HandleOptions",
            "HandleTrace", "HandleSocketHandshake", "HandleSocketOpen", "HandleSocketMessage", "HandleSocketPong",
            "HandleSocketClose", "HandleWeb");

    /**
     * Scans the source root and returns indexed component metadata.
     */
    public ComponentRegistry scan(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new ComponentRegistryException(
                    "Component source root does not exist or is not a directory: " + sourceRoot);
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<ParsedSource> sources = files.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::parse)
                    .toList();
            sources = enrichMetaAnnotations(sources);
            List<String> allTypeNames = sources.stream()
                    .flatMap(source -> source.types().stream())
                    .map(TypeInfo::canonicalFullClassName)
                    .sorted()
                    .toList();
            Map<String, PackageInfo> packages = packageInfos(sources);
            List<PackageDescriptor> packageDescriptors = packages.values().stream()
                    .map(packageInfo -> packageDescriptor(packageInfo, allTypeNames))
                    .toList();
            List<ComponentDescriptor> components = sources.stream()
                    .flatMap(source -> source.types().stream()
                            .map(type -> componentDescriptor(source, type, packages, allTypeNames)))
                    .toList();
            return new ComponentRegistry(sourceRoot, packageDescriptors, components);
        } catch (Exception e) {
            if (e instanceof ComponentRegistryException componentRegistryException) {
                throw componentRegistryException;
            }
            throw new ComponentRegistryException("Failed to index component source root: " + sourceRoot, e);
        }
    }

    /**
     * Scans the source root, writes the registry JSON artifact, and returns the indexed registry.
     */
    public ComponentRegistry writeJson(Path sourceRoot, Path output) {
        ComponentRegistry registry = scan(sourceRoot);
        ComponentRegistryJson.write(registry, output);
        return registry;
    }

    private ParsedSource parse(Path sourceFile) {
        try {
            return new SourceParser(sourceFile, Files.readString(sourceFile)).parse();
        } catch (IOException e) {
            throw new ComponentRegistryException("Failed to read component source: " + sourceFile, e);
        }
    }

    private List<ParsedSource> enrichMetaAnnotations(List<ParsedSource> sources) {
        Map<String, List<AnnotationDescriptor>> metaAnnotations = new LinkedHashMap<>();
        for (ParsedSource source : sources) {
            for (AnnotationTypeInfo annotationType : source.annotationTypes()) {
                List<AnnotationDescriptor> annotations = annotationType.annotations().stream()
                        .filter(SourceComponentScanner::includeSourceMetaAnnotation)
                        .toList();
                metaAnnotations.put(annotationType.qualifiedName(), annotations);
                metaAnnotations.putIfAbsent(annotationType.simpleName(), annotations);
            }
        }
        return sources.stream()
                .map(source -> enrichSource(source, metaAnnotations))
                .toList();
    }

    private static boolean includeSourceMetaAnnotation(AnnotationDescriptor annotation) {
        return !annotation.qualifiedName().startsWith("java.lang.annotation.")
               && !STANDARD_META_ANNOTATIONS.contains(annotation.name());
    }

    private ParsedSource enrichSource(
            ParsedSource source, Map<String, List<AnnotationDescriptor>> metaAnnotations) {
        return new ParsedSource(
                source.sourceFile(), source.packageName(), source.imports(), source.wildcardImports(),
                enrichAnnotations(source.packageAnnotations(), metaAnnotations, new LinkedHashSet<>()),
                source.types().stream().map(type -> enrichType(type, metaAnnotations)).toList(),
                source.annotationTypes().stream()
                        .map(type -> new AnnotationTypeInfo(
                                type.simpleName(), type.qualifiedName(),
                                enrichAnnotations(type.annotations(), metaAnnotations, new LinkedHashSet<>())))
                        .toList());
    }

    private TypeInfo enrichType(TypeInfo type, Map<String, List<AnnotationDescriptor>> metaAnnotations) {
        return new TypeInfo(
                type.kind(), type.packageName(), type.className(), type.canonicalName(), type.superTypeNames(),
                enrichAnnotations(type.annotations(), metaAnnotations, new LinkedHashSet<>()),
                type.enclosingAnnotations().stream()
                        .map(annotations -> enrichAnnotations(annotations, metaAnnotations, new LinkedHashSet<>()))
                        .toList(),
                type.properties().stream().map(property -> new PropertyDescriptor(
                        property.name(), property.typeName(), property.genericTypeName(),
                        enrichAnnotations(property.annotations(), metaAnnotations, new LinkedHashSet<>()),
                        enrichTypeUse(property.typeUse(), metaAnnotations))).toList(),
                type.executables().stream().map(executable -> new ExecutableDescriptor(
                        executable.kind(), executable.name(), executable.returnTypeName(),
                        enrichTypeUse(executable.returnTypeUse(), metaAnnotations),
                        executable.parameters().stream().map(parameter -> new ParameterDescriptor(
                                parameter.name(), parameter.typeName(),
                                enrichAnnotations(parameter.annotations(), metaAnnotations, new LinkedHashSet<>()),
                                enrichTypeUse(parameter.typeUse(), metaAnnotations)))
                                .toList(),
                        enrichAnnotations(executable.annotations(), metaAnnotations, new LinkedHashSet<>()),
                        executable.isStatic())).toList());
    }

    private TypeUseDescriptor enrichTypeUse(
            TypeUseDescriptor typeUse, Map<String, List<AnnotationDescriptor>> metaAnnotations) {
        if (typeUse == null || typeUse.equals(TypeUseDescriptor.EMPTY)) {
            return typeUse;
        }
        return new TypeUseDescriptor(
                typeUse.typeName(),
                enrichAnnotations(typeUse.annotations(), metaAnnotations, new LinkedHashSet<>()),
                typeUse.typeArguments().stream().map(argument -> enrichTypeUse(argument, metaAnnotations)).toList(),
                enrichTypeUse(typeUse.componentType(), metaAnnotations));
    }

    private static List<AnnotationDescriptor> enrichAnnotations(
            List<AnnotationDescriptor> annotations, Map<String, List<AnnotationDescriptor>> metaAnnotations,
            Set<String> visiting) {
        return annotations.stream()
                .map(annotation -> enrichAnnotation(annotation, metaAnnotations, visiting))
                .toList();
    }

    private static AnnotationDescriptor enrichAnnotation(
            AnnotationDescriptor annotation, Map<String, List<AnnotationDescriptor>> metaAnnotations,
            Set<String> visiting) {
        String key = metaAnnotations.containsKey(annotation.qualifiedName())
                ? annotation.qualifiedName() : annotation.name();
        if (!visiting.add(key)) {
            return annotation;
        }
        List<AnnotationDescriptor> meta = Stream.concat(
                        metaAnnotations.getOrDefault(key, List.of()).stream(),
                        Optional.ofNullable(BUILT_IN_META_ANNOTATIONS.get(key)).stream())
                .map(metaAnnotation -> enrichAnnotation(metaAnnotation, metaAnnotations, new LinkedHashSet<>(visiting)))
                .toList();
        return new AnnotationDescriptor(
                annotation.name(), annotation.qualifiedName(), annotation.attributes(),
                annotation.nestedAnnotations(), meta);
    }

    private static AnnotationDescriptor webParam(String source) {
        return new AnnotationDescriptor(
                "WebParam", KNOWN_ANNOTATIONS.get("WebParam"),
                Map.of("type", List.of(source), "value", List.of("")));
    }

    private static Map<String, AnnotationDescriptor> builtInMetaAnnotations() {
        Map<String, AnnotationDescriptor> result = new LinkedHashMap<>();
        addMetaAnnotation(result, "PathParam", webParam("PATH"));
        addMetaAnnotation(result, "QueryParam", webParam("QUERY"));
        addMetaAnnotation(result, "HeaderParam", webParam("HEADER"));
        addMetaAnnotation(result, "CookieParam", webParam("COOKIE"));
        addMetaAnnotation(result, "FormParam", webParam("FORM"));
        addMetaAnnotation(result, "BodyParam", webParam("BODY"));
        addMetaAnnotation(result, "Upcast", castMeta(1));
        addMetaAnnotation(result, "Downcast", castMeta(-1));
        addMetaAnnotation(result, "HandleCommand", handleMessageMeta(MessageType.COMMAND));
        addMetaAnnotation(result, "HandleQuery", handleMessageMeta(MessageType.QUERY));
        addMetaAnnotation(result, "HandleEvent", handleMessageMeta(MessageType.EVENT));
        addMetaAnnotation(result, "HandleNotification", handleMessageMeta(MessageType.NOTIFICATION));
        addMetaAnnotation(result, "HandleError", handleMessageMeta(MessageType.ERROR));
        addMetaAnnotation(result, "HandleMetrics", handleMessageMeta(MessageType.METRICS));
        addMetaAnnotation(result, "HandleResult", handleMessageMeta(MessageType.RESULT));
        addMetaAnnotation(result, "HandleCustom", handleMessageMeta(MessageType.CUSTOM));
        addMetaAnnotation(result, "HandleDocument", handleMessageMeta(MessageType.DOCUMENT));
        addMetaAnnotation(result, "HandleSchedule", handleMessageMeta(MessageType.SCHEDULE));
        addMetaAnnotation(result, "HandleWebResponse", handleMessageMeta(MessageType.WEBRESPONSE));
        addMetaAnnotation(result, "HandleWeb", handleMessageMeta(MessageType.WEBREQUEST));
        addMetaAnnotation(result, "HandleGet", handleWebMeta("GET"));
        addMetaAnnotation(result, "HandlePost", handleWebMeta("POST"));
        addMetaAnnotation(result, "HandlePut", handleWebMeta("PUT"));
        addMetaAnnotation(result, "HandlePatch", handleWebMeta("PATCH"));
        addMetaAnnotation(result, "HandleDelete", handleWebMeta("DELETE"));
        addMetaAnnotation(result, "HandleHead", handleWebMeta("HEAD"));
        addMetaAnnotation(result, "HandleOptions", handleWebMeta("OPTIONS"));
        addMetaAnnotation(result, "HandleTrace", handleWebMeta("TRACE"));
        addMetaAnnotation(result, "HandleSocketHandshake", handleWebMeta("WS_HANDSHAKE"));
        addMetaAnnotation(result, "HandleSocketOpen", handleWebMeta("WS_OPEN"));
        addMetaAnnotation(result, "HandleSocketMessage", handleWebMeta("WS_MESSAGE"));
        addMetaAnnotation(result, "HandleSocketPong", handleWebMeta("WS_PONG"));
        addMetaAnnotation(result, "HandleSocketClose", handleWebMeta("WS_CLOSE"));
        return Map.copyOf(result);
    }

    private static void addMetaAnnotation(
            Map<String, AnnotationDescriptor> result, String annotationName, AnnotationDescriptor metaAnnotation) {
        result.put(annotationName, metaAnnotation);
        result.put(KNOWN_ANNOTATIONS.get(annotationName), metaAnnotation);
    }

    private static AnnotationDescriptor handleMessageMeta(MessageType messageType) {
        return new AnnotationDescriptor(
                "HandleMessage", KNOWN_ANNOTATIONS.get("HandleMessage"),
                Map.of("value", List.of(messageType.name())));
    }

    private static AnnotationDescriptor handleWebMeta(String method) {
        return new AnnotationDescriptor(
                "HandleWeb", KNOWN_ANNOTATIONS.get("HandleWeb"),
                Map.of("value", List.of(""), "method", List.of(method)));
    }

    private static AnnotationDescriptor castMeta(int revisionDelta) {
        return new AnnotationDescriptor(
                "Cast", KNOWN_ANNOTATIONS.get("Cast"),
                Map.of("revisionDelta", List.of(Integer.toString(revisionDelta))));
    }

    private Map<String, PackageInfo> packageInfos(List<ParsedSource> sources) {
        Map<String, PackageInfo> result = new LinkedHashMap<>();
        for (ParsedSource source : sources) {
            if ("package-info.java".equals(source.sourceFile().getFileName().toString())) {
                LocalHandlerConfig localHandler = localHandlerConfig(source.packageAnnotations()).orElse(null);
                ConsumerDescriptor consumer = consumerDescriptor(source.packageAnnotations()).orElse(null);
                result.put(source.packageName(), new PackageInfo(
                        source.packageName(), source.sourceFile(), source.packageAnnotations(),
                        localHandler, consumer));
            }
        }
        return result;
    }

    private PackageDescriptor packageDescriptor(PackageInfo packageInfo, List<String> allTypeNames) {
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(
                packageInfo.annotations(), packageInfo.packageName(), allTypeNames);
        Set<ComponentCapability> capabilities = EnumSet.noneOf(ComponentCapability.class);
        if (packageInfo.localHandler() != null) {
            capabilities.add(ComponentCapability.PACKAGE_LOCAL_HANDLER);
            if (packageInfo.localHandler().enabled()) {
                capabilities.add(ComponentCapability.LOCAL_HANDLER);
            }
            if (!packageInfo.localHandler().enabled() || packageInfo.localHandler().allowExternalMessages()) {
                capabilities.add(ComponentCapability.TRACKING_HANDLER);
            }
        }
        if (!registeredTypes.isEmpty()) {
            capabilities.add(ComponentCapability.REGISTERED_TYPE);
        }
        if (packageInfo.consumer() != null) {
            capabilities.add(ComponentCapability.CONSUMER);
        }
        return new PackageDescriptor(
                packageInfo.packageName(), packageInfo.sourceFile(), packageInfo.annotations(),
                registeredTypes, packageInfo.consumer(), Set.copyOf(capabilities));
    }

    private ComponentDescriptor componentDescriptor(ParsedSource source, TypeInfo type,
                                                   Map<String, PackageInfo> packageInfos, List<String> allTypeNames) {
        PackageInfo packageInfo = effectivePackageInfo(source.packageName(), packageInfos);
        List<RegisteredTypeDescriptor> registeredTypes =
                registeredTypes(type.annotations(), type.canonicalFullClassName(), allTypeNames);
        ConsumerDescriptor consumer = consumerDescriptor(type.annotations())
                .orElse(packageInfo == null ? null : packageInfo.consumer());
        LocalHandlerConfig typeLocalHandler = localHandlerConfig(type.annotations()).orElse(null);
        Set<HandlerRoute> routes = new LinkedHashSet<>();
        for (ExecutableDescriptor executable : type.executables()) {
            for (AnnotationDescriptor declaredAnnotation : executable.annotations()) {
                HandlerMatch match = handlerMatch(declaredAnnotation).orElse(null);
                if (match == null) {
                    continue;
                }
                AnnotationDescriptor annotation = match.annotation();
                HandlerSpec spec = match.spec();
                LocalHandlerConfig localHandler = localHandlerConfig(executable.annotations())
                        .orElse(typeLocalHandler != null ? typeLocalHandler
                                : packageInfo == null ? null : packageInfo.localHandler());
                boolean selfHandler = isSelfHandler(spec, type, executable);
                boolean selfTracking = hasTrackSelf(executable.annotations()) || hasTrackSelf(type.annotations())
                                       || packageInfo != null && hasTrackSelf(packageInfo.annotations());
                boolean local = localHandler != null ? localHandler.enabled() : selfHandler && !selfTracking;
                boolean tracked = localHandler != null ? !local || localHandler.allowExternalMessages() : !local;
                boolean disabled = annotation.booleanValue("disabled", false);
                boolean passive = annotation.booleanValue("passive", spec.defaultPassive());
                boolean skipExpiredRequests =
                        annotation.booleanValue("skipExpiredRequests", spec.defaultSkipExpiredRequests());
                Set<String> allowedClasses = new LinkedHashSet<>(annotation.values("allowedClasses"));
                Set<String> payloadTypes = !allowedClasses.isEmpty() ? allowedClasses
                        : spec.web() ? Set.of()
                        : firstPayloadType(executable).map(Set::of)
                                .orElseGet(() -> selfHandler ? Set.of(type.canonicalFullClassName()) : Set.of());
                routes.add(new HandlerRoute(
                        spec.messageType(), annotation, executable, disabled, passive, skipExpiredRequests,
                        local, tracked, payloadTypes, allowedClasses,
                        spec.web() ? webRoutes(annotation, spec, packageInfos, type, executable) : List.of()));
            }
        }
        Set<ComponentCapability> capabilities = componentCapabilities(routes, registeredTypes, consumer, type.superTypeNames());
        return new ComponentDescriptor(
                source.sourceFile(), packageInfo == null ? null : packageInfo.sourceFile(), type.kind(),
                source.packageName(), type.className(), type.superTypeNames(), type.annotations(), type.properties(),
                type.executables(), Set.copyOf(routes), registeredTypes, consumer, capabilities);
    }

    private static Optional<String> firstPayloadType(ExecutableDescriptor executable) {
        return executable.parameters().stream().map(ParameterDescriptor::typeName).findFirst();
    }

    private static boolean isSelfHandler(HandlerSpec spec, TypeInfo type, ExecutableDescriptor executable) {
        return (spec.messageType().isRequest() || spec.messageType() == MessageType.SCHEDULE)
               && !spec.web()
               && executable.kind() == ExecutableKind.METHOD
               && executable.parameters().isEmpty()
               && requiresPayloadInstance(type);
    }

    private static boolean requiresPayloadInstance(TypeInfo type) {
        if (type.kind() == ComponentKind.RECORD) {
            return true;
        }
        if (type.kind() != ComponentKind.CLASS) {
            return false;
        }
        List<ExecutableDescriptor> constructors = type.executables().stream()
                .filter(executable -> executable.kind() == ExecutableKind.CONSTRUCTOR)
                .toList();
        return !constructors.isEmpty()
               && constructors.stream().noneMatch(constructor -> constructor.parameters().isEmpty());
    }

    private static Optional<HandlerMatch> handlerMatch(AnnotationDescriptor annotation) {
        for (String name : HANDLER_NAMES) {
            Optional<AnnotationDescriptor> match = annotation.find(name, KNOWN_ANNOTATIONS.get(name));
            if (match.isPresent()) {
                return Optional.of(new HandlerMatch(match.get(), HANDLERS.get(name)));
            }
        }
        return Optional.empty();
    }

    private static boolean hasTrackSelf(List<AnnotationDescriptor> annotations) {
        return findAnnotation(annotations, "TrackSelf").isPresent();
    }

    private static Optional<AnnotationDescriptor> findAnnotation(
            List<AnnotationDescriptor> annotations, String annotationName) {
        return annotations.stream()
                .map(annotation -> annotation.find(annotationName, KNOWN_ANNOTATIONS.get(annotationName)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private List<WebRouteDescriptor> webRoutes(AnnotationDescriptor annotation, HandlerSpec spec,
                                               Map<String, PackageInfo> packageInfos, TypeInfo type,
                                               ExecutableDescriptor executable) {
        List<String> packagePaths = packagePaths(type.packageName(), packageInfos);
        List<String> rootPaths = new ArrayList<>(packagePaths);
        for (List<AnnotationDescriptor> enclosingAnnotations : type.enclosingAnnotations()) {
            WebRoutePaths.pathValue(enclosingAnnotations, simplePackageName(type.packageName()))
                    .ifPresent(rootPaths::add);
        }
        Optional<String> typePath = WebRoutePaths.pathValue(type.annotations(), simplePackageName(type.packageName()));
        Optional<String> methodPath = WebRoutePaths.pathValue(executable.annotations(), type.className());
        List<String> handlerPaths = annotation.values("value");
        List<String> methods = annotation.isOrHas("HandleWeb", KNOWN_ANNOTATIONS.get("HandleWeb"))
                               && !annotation.values("method").isEmpty()
                ? annotation.values("method") : spec.webMethods();
        boolean autoHead = annotation.booleanValue("autoHead", spec.defaultAutoHead());
        boolean autoOptions = annotation.booleanValue("autoOptions", spec.defaultAutoOptions());
        List<String> paths = WebRoutePaths.paths(rootPaths, typePath, methodPath, handlerPaths);
        return List.of(new WebRouteDescriptor(paths, methods, autoHead, autoOptions));
    }

    private static Set<ComponentCapability> componentCapabilities(Set<HandlerRoute> routes,
                                                                  List<RegisteredTypeDescriptor> registeredTypes,
                                                                  ConsumerDescriptor consumer,
                                                                  List<String> superTypeNames) {
        Set<ComponentCapability> result = EnumSet.noneOf(ComponentCapability.class);
        result.add(ComponentCapability.SOURCE_COMPONENT);
        if (!routes.isEmpty()) {
            result.add(ComponentCapability.HANDLER);
        }
        if (routes.stream().anyMatch(HandlerRoute::local)) {
            result.add(ComponentCapability.LOCAL_HANDLER);
        }
        if (routes.stream().anyMatch(HandlerRoute::tracked)) {
            result.add(ComponentCapability.TRACKING_HANDLER);
        }
        if (routes.stream().anyMatch(route -> route.messageType() == MessageType.WEBREQUEST)) {
            result.add(ComponentCapability.WEB_REQUEST_HANDLER);
        }
        if (!registeredTypes.isEmpty()) {
            result.add(ComponentCapability.REGISTERED_TYPE);
        }
        if (consumer != null) {
            result.add(ComponentCapability.CONSUMER);
        }
        superTypeNames.stream()
                .flatMap(typeName -> infrastructureCapabilities(typeName).stream())
                .forEach(result::add);
        if (result.contains(ComponentCapability.HANDLER_INTERCEPTOR)) {
            result.add(ComponentCapability.HANDLER_DECORATOR);
        }
        if (result.contains(ComponentCapability.WEB_RESPONSE_MAPPER)) {
            result.add(ComponentCapability.RESPONSE_MAPPER);
        }
        return Set.copyOf(result);
    }

    private static Set<ComponentCapability> infrastructureCapabilities(String typeName) {
        typeName = eraseGeneric(typeName);
        if ("io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer".equals(typeName)) {
            return Set.of(ComponentCapability.SERIALIZER, ComponentCapability.DOCUMENT_SERIALIZER);
        }
        if ("io.fluxzero.sdk.tracking.handling.DefaultResponseMapper".equals(typeName)) {
            return Set.of(ComponentCapability.RESPONSE_MAPPER);
        }
        if ("io.fluxzero.sdk.web.DefaultWebResponseMapper".equals(typeName)) {
            return Set.of(ComponentCapability.WEB_RESPONSE_MAPPER, ComponentCapability.RESPONSE_MAPPER);
        }
        if ("io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider".equals(typeName)) {
            return Set.of(ComponentCapability.USER_PROVIDER);
        }
        if ("io.fluxzero.sdk.persisting.caching.SoftReferenceCache".equals(typeName)) {
            return Set.of(ComponentCapability.CACHE);
        }
        if ("io.fluxzero.common.InMemoryTaskScheduler".equals(typeName)) {
            return Set.of(ComponentCapability.TASK_SCHEDULER);
        }
        return Optional.ofNullable(INFRASTRUCTURE_CAPABILITIES.get(typeName)).map(Set::of).orElseGet(Set::of);
    }

    private static List<RegisteredTypeDescriptor> registeredTypes(
            List<AnnotationDescriptor> annotations, String defaultRoot, List<String> allTypeNames) {
        return annotations.stream()
                .map(annotation -> annotation.find("RegisterType", KNOWN_ANNOTATIONS.get("RegisterType")))
                .flatMap(Optional::stream)
                .map(annotation -> {
                    String root = annotationRoot(annotation, defaultRoot);
                    List<String> contains = annotation.values("contains");
                    List<String> candidates = allTypeNames.stream()
                            .filter(typeName -> typeName.replace("$", ".").startsWith(root))
                            .filter(typeName -> contains.isEmpty() || contains.stream()
                                    .map(Pattern::compile)
                                    .anyMatch(pattern -> pattern.matcher(typeName).find()))
                            .toList();
                    return new RegisteredTypeDescriptor(root, contains, candidates, annotation);
                })
                .toList();
    }

    private static String annotationRoot(AnnotationDescriptor annotation, String defaultRoot) {
        return annotation.firstValue("root")
                .filter(value -> !value.isBlank())
                .or(() -> annotation.firstValue("rootClass").filter(value -> !value.isBlank())
                        .filter(value -> !value.equals(Void.class.getName())))
                .orElse(defaultRoot);
    }

    private static Optional<ConsumerDescriptor> consumerDescriptor(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.find("Consumer", KNOWN_ANNOTATIONS.get("Consumer")))
                .flatMap(Optional::stream)
                .findFirst()
                .map(annotation -> new ConsumerDescriptor(
                        annotation.firstValue("name").or(() -> annotation.firstValue("value")).orElse(""),
                        annotation.attributes(), annotation));
    }

    private static Optional<LocalHandlerConfig> localHandlerConfig(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.find("LocalHandler", KNOWN_ANNOTATIONS.get("LocalHandler")))
                .flatMap(Optional::stream)
                .reduce((first, second) -> second)
                .map(annotation -> {
                    boolean enabled = annotation.booleanValue("value", true);
                    boolean allowExternalMessages = enabled && annotation.booleanValue("allowExternalMessages", false);
                    return new LocalHandlerConfig(enabled, allowExternalMessages);
                });
    }

    private static List<String> packagePaths(String packageName, Map<String, PackageInfo> packageInfos) {
        List<String> result = new ArrayList<>();
        for (String current = packageName; current != null; current = parentPackage(current)) {
            PackageInfo packageInfo = packageInfos.get(current);
            if (packageInfo == null) {
                continue;
            }
            WebRoutePaths.pathValue(packageInfo.annotations(), simplePackageName(current))
                    .ifPresent(path -> result.add(0, path));
        }
        return List.copyOf(result);
    }

    private static String simplePackageName(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? Objects.requireNonNullElse(packageName, "") : packageName.substring(lastDot + 1);
    }

    private static PackageInfo effectivePackageInfo(String packageName, Map<String, PackageInfo> packageInfos) {
        List<PackageInfo> lineage = new ArrayList<>();
        for (String current = packageName; current != null; current = parentPackage(current)) {
            PackageInfo packageInfo = packageInfos.get(current);
            if (packageInfo != null) {
                lineage.add(0, packageInfo);
            }
        }
        if (lineage.isEmpty()) {
            return null;
        }
        List<AnnotationDescriptor> annotations = lineage.stream()
                .flatMap(packageInfo -> packageInfo.annotations().stream())
                .toList();
        LocalHandlerConfig localHandler = null;
        ConsumerDescriptor consumer = null;
        for (PackageInfo packageInfo : lineage) {
            if (packageInfo.localHandler() != null) {
                localHandler = packageInfo.localHandler();
            }
            if (packageInfo.consumer() != null) {
                consumer = packageInfo.consumer();
            }
        }
        PackageInfo nearest = lineage.getLast();
        return new PackageInfo(nearest.packageName(), nearest.sourceFile(), annotations, localHandler, consumer);
    }

    private static String parentPackage(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    private record HandlerSpec(MessageType messageType, boolean defaultPassive, boolean defaultSkipExpiredRequests,
                               boolean web, List<String> webMethods, boolean defaultAutoHead,
                               boolean defaultAutoOptions) {
    }

    private record LocalHandlerConfig(boolean enabled, boolean allowExternalMessages) {
    }

    private record PackageInfo(String packageName, Path sourceFile, List<AnnotationDescriptor> annotations,
                               LocalHandlerConfig localHandler, ConsumerDescriptor consumer) {
    }

    private record ParsedSource(Path sourceFile, String packageName, Map<String, String> imports,
                                List<String> wildcardImports, List<AnnotationDescriptor> packageAnnotations,
                                List<TypeInfo> types, List<AnnotationTypeInfo> annotationTypes) {
    }

    private record AnnotationTypeInfo(String simpleName, String qualifiedName, List<AnnotationDescriptor> annotations) {
    }

    private record TypeInfo(ComponentKind kind, String packageName, String className, String canonicalName,
                            List<String> superTypeNames, List<AnnotationDescriptor> annotations,
                            List<List<AnnotationDescriptor>> enclosingAnnotations,
                            List<PropertyDescriptor> properties, List<ExecutableDescriptor> executables) {
        String fullClassName() {
            return packageName.isBlank() ? className : packageName + "." + className;
        }

        String canonicalFullClassName() {
            return packageName.isBlank() ? canonicalName : packageName + "." + canonicalName;
        }
    }

    private static class SourceParser {
        private final Path sourceFile;
        private final String source;
        private final String packageName;
        private final Map<String, String> imports;
        private final List<String> wildcardImports;
        private final Map<String, String> localTypes;
        private final Map<String, String> sourceConstants;

        SourceParser(Path sourceFile, String source) {
            this.sourceFile = sourceFile;
            this.source = stripComments(source);
            this.packageName = first(PACKAGE_PATTERN, this.source).orElse("");
            this.imports = imports(this.source);
            this.wildcardImports = wildcardImports(this.source);
            this.localTypes = localTypes();
            this.sourceConstants = sourceConstants();
        }

        ParsedSource parse() {
            List<AnnotationDescriptor> packageAnnotations = parsePackageAnnotations();
            return new ParsedSource(sourceFile, packageName, imports, wildcardImports,
                                    packageAnnotations, parseTypes(), parseAnnotationTypes());
        }

        private List<AnnotationDescriptor> parsePackageAnnotations() {
            Matcher matcher = PACKAGE_PATTERN.matcher(source);
            if (!matcher.find()) {
                return List.of();
            }
            return parseAnnotations(source.substring(0, matcher.start()));
        }

        private List<TypeInfo> parseTypes() {
            return parseTypes(0, source.length(), null, null, List.of());
        }

        private List<TypeInfo> parseTypes(
                int rangeStart, int rangeEnd, String enclosingBinaryName, String enclosingCanonicalName,
                List<List<AnnotationDescriptor>> enclosingAnnotations) {
            List<TypeInfo> types = new ArrayList<>();
            Matcher matcher = TYPE_PATTERN.matcher(source);
            matcher.region(rangeStart, rangeEnd);
            while (matcher.find()) {
                if (matcher.start() > 0 && source.charAt(matcher.start() - 1) == '@'
                    || insideAnnotation(matcher.start()) || memberDepth(rangeStart, matcher.start()) != 0) {
                    continue;
                }
                ComponentKind kind = ComponentKind.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
                String simpleName = matcher.group(2);
                String className = enclosingBinaryName == null ? simpleName : enclosingBinaryName + "$" + simpleName;
                String canonicalName = enclosingCanonicalName == null
                        ? simpleName : enclosingCanonicalName + "." + simpleName;
                int bodyStart = findTypeBodyStart(matcher.end(), rangeEnd);
                if (bodyStart < 0 || bodyStart >= rangeEnd) {
                    continue;
                }
                int bodyEnd = matching(source, bodyStart, '{', '}');
                if (bodyEnd < 0 || bodyEnd > rangeEnd) {
                    bodyEnd = rangeEnd;
                }
                List<AnnotationDescriptor> annotations = parseAnnotations(annotationsBefore(matcher.start()));
                String declarationTail = source.substring(matcher.end(), bodyStart);
                List<String> superTypeNames = parseSuperTypeNames(declarationTail);
                List<ExecutableDescriptor> executables = parseExecutables(simpleName, kind, bodyStart, bodyEnd);
                List<PropertyDescriptor> properties = mergeExecutableProperties(
                        parseProperties(kind, declarationTail, bodyStart, bodyEnd), executables);
                types.add(new TypeInfo(
                        kind, packageName, className, canonicalName, superTypeNames, annotations,
                        List.copyOf(enclosingAnnotations), properties, executables));
                List<List<AnnotationDescriptor>> childEnclosingAnnotations = new ArrayList<>(enclosingAnnotations);
                childEnclosingAnnotations.add(annotations);
                types.addAll(parseTypes(
                        bodyStart + 1, bodyEnd, className, canonicalName,
                        List.copyOf(childEnclosingAnnotations)));
            }
            return types;
        }

        private List<AnnotationTypeInfo> parseAnnotationTypes() {
            List<AnnotationTypeInfo> result = new ArrayList<>(parseAnnotationTypes(0, source.length(), null));
            collectAnnotationTypesInTypeBodies(0, source.length(), null, result);
            return result;
        }

        private List<AnnotationTypeInfo> parseAnnotationTypes(
                int rangeStart, int rangeEnd, String enclosingCanonicalName) {
            List<AnnotationTypeInfo> types = new ArrayList<>();
            Matcher matcher = ANNOTATION_TYPE_PATTERN.matcher(source);
            matcher.region(rangeStart, rangeEnd);
            while (matcher.find()) {
                if (insideAnnotation(matcher.start()) || memberDepth(rangeStart, matcher.start()) != 0) {
                    continue;
                }
                String simpleName = matcher.group(1);
                String canonicalName = enclosingCanonicalName == null
                        ? simpleName : enclosingCanonicalName + "." + simpleName;
                List<AnnotationDescriptor> annotations = parseAnnotations(annotationsBefore(matcher.start()));
                String qualifiedName = packageName.isBlank() ? canonicalName : packageName + "." + canonicalName;
                types.add(new AnnotationTypeInfo(simpleName, qualifiedName, annotations));
                int bodyStart = findTypeBodyStart(matcher.end(), rangeEnd);
                if (bodyStart < 0 || bodyStart >= rangeEnd) {
                    continue;
                }
                int bodyEnd = matching(source, bodyStart, '{', '}');
                if (bodyEnd < 0 || bodyEnd > rangeEnd) {
                    bodyEnd = rangeEnd;
                }
                types.addAll(parseAnnotationTypes(bodyStart + 1, bodyEnd, canonicalName));
            }
            return types;
        }

        private void collectAnnotationTypesInTypeBodies(
                int rangeStart, int rangeEnd, String enclosingCanonicalName, List<AnnotationTypeInfo> result) {
            Matcher matcher = TYPE_PATTERN.matcher(source);
            matcher.region(rangeStart, rangeEnd);
            while (matcher.find()) {
                if (matcher.start() > 0 && source.charAt(matcher.start() - 1) == '@'
                    || insideAnnotation(matcher.start()) || memberDepth(rangeStart, matcher.start()) != 0) {
                    continue;
                }
                String simpleName = matcher.group(2);
                String canonicalName = enclosingCanonicalName == null
                        ? simpleName : enclosingCanonicalName + "." + simpleName;
                int bodyStart = findTypeBodyStart(matcher.end(), rangeEnd);
                if (bodyStart < 0 || bodyStart >= rangeEnd) {
                    continue;
                }
                int bodyEnd = matching(source, bodyStart, '{', '}');
                if (bodyEnd < 0 || bodyEnd > rangeEnd) {
                    bodyEnd = rangeEnd;
                }
                result.addAll(parseAnnotationTypes(bodyStart + 1, bodyEnd, canonicalName));
                collectAnnotationTypesInTypeBodies(bodyStart + 1, bodyEnd, canonicalName, result);
            }
        }

        private boolean isTopLevel(int offset) {
            return memberDepth(0, offset) == 0;
        }

        private int memberDepth(int rangeStart, int offset) {
            int depth = 0;
            for (int i = rangeStart; i < offset; i++) {
                char c = source.charAt(i);
                if (c == '"' || c == '\'') {
                    i = skipQuoted(source, i);
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }
            return depth;
        }

        private List<String> parseSuperTypeNames(String declarationTail) {
            String tail = declarationTail.trim();
            if (tail.startsWith("(")) {
                int end = matching(tail, 0, '(', ')');
                tail = end < 0 || end + 1 >= tail.length() ? "" : tail.substring(end + 1).trim();
            }
            List<String> result = new ArrayList<>();
            collectSuperTypes(tail, "extends", result);
            collectSuperTypes(tail, "implements", result);
            return result.stream().distinct().toList();
        }

        private void collectSuperTypes(String declarationTail, String keyword, List<String> sink) {
            int index = wordIndexOf(declarationTail, keyword);
            if (index < 0) {
                return;
            }
            int start = index + keyword.length();
            int end = nextKeywordIndex(declarationTail, start);
            String segment = declarationTail.substring(start, end < 0 ? declarationTail.length() : end);
            for (String type : splitTopLevel(segment, ',')) {
                String genericType = resolveGenericType(type.trim());
                if (!genericType.isBlank()) {
                    sink.add(genericType);
                }
            }
        }

        private boolean insideAnnotation(int offset) {
            int at = source.lastIndexOf('@', offset);
            int semicolon = source.lastIndexOf(';', offset);
            int brace = Math.max(source.lastIndexOf('{', offset), source.lastIndexOf('}', offset));
            return at > semicolon && at > brace && source.substring(at, offset).contains("@interface");
        }

        private String annotationsBefore(int offset) {
            int start = offset;
            int searchFrom = start;
            while (true) {
                int at = source.lastIndexOf('@', searchFrom - 1);
                if (at < 0) {
                    break;
                }
                if (insideAnnotationValue(at)) {
                    searchFrom = at;
                    continue;
                }
                int annotationEnd = annotationEnd(at);
                String between = stripModifiers(source.substring(annotationEnd, start));
                if (!between.isBlank()) {
                    break;
                }
                start = at;
                searchFrom = at;
            }
            return source.substring(start, offset);
        }

        private boolean insideAnnotationValue(int at) {
            for (int previous = source.lastIndexOf('@', at - 1); previous >= 0;
                 previous = source.lastIndexOf('@', previous - 1)) {
                int end = annotationEnd(previous);
                if (end > at) {
                    return true;
                }
            }
            return false;
        }

        private int annotationEnd(int at) {
            int nameEnd = at + 1;
            while (nameEnd < source.length()
                   && (Character.isJavaIdentifierPart(source.charAt(nameEnd)) || source.charAt(nameEnd) == '.')) {
                nameEnd++;
            }
            int next = skipWhitespace(source, nameEnd);
            if (next < source.length() && source.charAt(next) == '(') {
                int end = matching(source, next, '(', ')');
                return end < 0 ? next + 1 : end + 1;
            }
            return nameEnd;
        }

        private static String stripModifiers(String text) {
            String result = text;
            for (String modifier : MODIFIERS) {
                result = result.replaceAll("\\b" + Pattern.quote(modifier) + "\\b", " ");
            }
            return result.trim();
        }

        private List<ExecutableDescriptor> parseExecutables(
                String className, ComponentKind kind, int bodyStart, int bodyEnd) {
            List<ExecutableDescriptor> result = new ArrayList<>();
            int memberStart = bodyStart + 1;
            int i = memberStart;
            int paren = 0;
            while (i < bodyEnd) {
                char c = source.charAt(i);
                if (isStringStart(i)) {
                    i = skipString(i);
                    continue;
                }
                if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '{' && paren == 0) {
                    String header = source.substring(memberStart, i).trim();
                    parseExecutable(header, className).ifPresent(result::add);
                    int blockEnd = matching(source, i, '{', '}');
                    if (blockEnd < 0 || blockEnd >= bodyEnd) {
                        break;
                    }
                    i = blockEnd + 1;
                    memberStart = i;
                    paren = 0;
                    continue;
                }
                if (c == ';' && paren == 0) {
                    parseExecutable(source.substring(memberStart, i).trim(), className).ifPresent(result::add);
                    memberStart = i + 1;
                }
                i++;
            }
            if (kind == ComponentKind.CLASS && result.stream()
                    .noneMatch(executable -> executable.kind() == ExecutableKind.CONSTRUCTOR)) {
                result.add(new ExecutableDescriptor(
                        ExecutableKind.CONSTRUCTOR, "<init>", "void", List.of(), List.of(), false));
            }
            return result;
        }

        private List<PropertyDescriptor> mergeExecutableProperties(
                List<PropertyDescriptor> properties, List<ExecutableDescriptor> executables) {
            Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
            properties.forEach(property -> result.put(property.name(), property));
            executables.stream()
                    .filter(executable -> executable.kind() == ExecutableKind.METHOD)
                    .filter(executable -> executable.parameters().isEmpty())
                    .filter(executable -> !"void".equals(executable.returnTypeName()))
                    .filter(executable -> !executable.annotations().isEmpty())
                    .forEach(executable -> result.putIfAbsent(executable.name(), new PropertyDescriptor(
                            executable.name(), executable.returnTypeName(), executable.returnTypeName(),
                            executable.annotations(), executable.returnTypeUse())));
            return List.copyOf(result.values());
        }

        private List<PropertyDescriptor> parseProperties(ComponentKind kind, String declarationTail,
                                                         int bodyStart, int bodyEnd) {
            Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
            String tail = declarationTail.trim();
            if (kind == ComponentKind.RECORD && tail.startsWith("(")) {
                int end = matching(tail, 0, '(', ')');
                if (end > 0) {
                    parseParameters(tail.substring(1, end)).stream()
                            .map(parameter -> new PropertyDescriptor(
                                    parameter.name(), parameter.typeName(), parameter.typeName(),
                                    parameter.annotations(), parameter.typeUse()))
                            .forEach(property -> result.put(property.name(), property));
                }
            }
            int memberStart = bodyStart + 1;
            int i = memberStart;
            int paren = 0;
            while (i < bodyEnd) {
                char c = source.charAt(i);
                if (isStringStart(i)) {
                    i = skipString(i);
                    continue;
                }
                if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '{' && paren == 0) {
                    int blockEnd = matching(source, i, '{', '}');
                    if (blockEnd < 0 || blockEnd >= bodyEnd) {
                        break;
                    }
                    i = blockEnd + 1;
                    memberStart = i;
                    paren = 0;
                    continue;
                }
                if (c == ';' && paren == 0) {
                    parseProperty(source.substring(memberStart, i).trim()).ifPresent(
                            property -> result.putIfAbsent(property.name(), property));
                    memberStart = i + 1;
                }
                i++;
            }
            return List.copyOf(result.values());
        }

        private Optional<PropertyDescriptor> parseProperty(String header) {
            if (header.isBlank()) {
                return Optional.empty();
            }
            int equals = topLevelIndexOf(header, '=');
            String declaration = equals < 0 ? header : header.substring(0, equals);
            List<AnnotationDescriptor> annotations = parseAnnotations(leadingAnnotationText(declaration));
            String cleaned = removeAnnotations(declaration).trim();
            if (cleaned.contains("(")) {
                return Optional.empty();
            }
            List<String> tokens = words(cleaned);
            tokens.removeIf(MODIFIERS::contains);
            if (tokens.size() < 2) {
                return Optional.empty();
            }
            String name = tokens.get(tokens.size() - 1);
            if (name == null || name.isBlank() || controlKeyword(name)) {
                return Optional.empty();
            }
            String type = cleaned.substring(0, cleaned.lastIndexOf(name)).trim();
            for (String modifier : MODIFIERS) {
                type = type.replaceAll("\\b" + Pattern.quote(modifier) + "\\b", " ");
            }
            type = type.trim();
            if (type.isBlank() || type.contains(",")) {
                return Optional.empty();
            }
            String genericTypeName = resolveGenericType(type);
            TypeUseDescriptor typeUse = parseTypeUse(declaration.substring(0, declaration.lastIndexOf(name)).trim());
            return Optional.of(new PropertyDescriptor(
                    name, resolveErasedType(type), genericTypeName, annotations, typeUse));
        }

        private String leadingAnnotationText(String declaration) {
            int index = 0;
            while (index < declaration.length()) {
                index = skipWhitespace(declaration, index);
                if (index >= declaration.length() || declaration.charAt(index) != '@') {
                    break;
                }
                int nameStart = index + 1;
                int nameEnd = nameStart;
                while (nameEnd < declaration.length()
                       && (Character.isJavaIdentifierPart(declaration.charAt(nameEnd))
                           || declaration.charAt(nameEnd) == '.')) {
                    nameEnd++;
                }
                if (nameEnd == nameStart) {
                    break;
                }
                int next = skipWhitespace(declaration, nameEnd);
                if (next < declaration.length() && declaration.charAt(next) == '(') {
                    int end = matching(declaration, next, '(', ')');
                    if (end < 0) {
                        break;
                    }
                    index = end + 1;
                } else {
                    index = nameEnd;
                }
            }
            return declaration.substring(0, index);
        }

        private TypeUseDescriptor parseTypeUse(String typeText) {
            typeText = stripLeadingAnnotationsAndModifiers(typeText);
            if (typeText.isBlank()) {
                return TypeUseDescriptor.EMPTY;
            }
            String typeName = resolveErasedType(removeAnnotations(typeText));
            int genericStart = typeText.indexOf('<');
            if (genericStart < 0) {
                return new TypeUseDescriptor(typeName, List.of());
            }
            int genericEnd = matching(typeText, genericStart, '<', '>');
            if (genericEnd < 0) {
                return new TypeUseDescriptor(typeName, List.of());
            }
            List<TypeUseDescriptor> arguments = splitTopLevel(typeText.substring(genericStart + 1, genericEnd), ',')
                    .stream()
                    .map(this::parseTypeArgumentUse)
                    .toList();
            return new TypeUseDescriptor(typeName, List.of(), arguments, null);
        }

        private TypeUseDescriptor parseTypeArgumentUse(String typeText) {
            typeText = typeText.trim();
            List<AnnotationDescriptor> annotations = parseAnnotations(leadingAnnotationText(typeText));
            String cleaned = stripLeadingAnnotationsAndModifiers(typeText)
                    .replaceFirst("^\\?\\s+extends\\s+", "")
                    .replaceFirst("^\\?\\s+super\\s+", "")
                    .replace("?", "java.lang.Object")
                    .trim();
            String typeName = resolveErasedType(removeAnnotations(cleaned));
            int genericStart = cleaned.indexOf('<');
            if (genericStart < 0) {
                return new TypeUseDescriptor(typeName, annotations);
            }
            int genericEnd = matching(cleaned, genericStart, '<', '>');
            List<TypeUseDescriptor> arguments = genericEnd < 0 ? List.of()
                    : splitTopLevel(cleaned.substring(genericStart + 1, genericEnd), ',').stream()
                            .map(this::parseTypeArgumentUse)
                            .toList();
            return new TypeUseDescriptor(typeName, annotations, arguments, null);
        }

        private String stripLeadingAnnotationsAndModifiers(String value) {
            String result = removeLeadingAnnotations(value).trim();
            boolean changed;
            do {
                changed = false;
                for (String modifier : MODIFIERS) {
                    if (result.equals(modifier) || result.startsWith(modifier + " ")) {
                        result = result.substring(modifier.length()).trim();
                        changed = true;
                    }
                }
                result = removeLeadingAnnotations(result).trim();
            } while (changed);
            return result;
        }

        private String removeLeadingAnnotations(String value) {
            int annotationEnd = leadingAnnotationText(value).length();
            return value.substring(annotationEnd);
        }

        private Optional<ExecutableDescriptor> parseExecutable(String header, String className) {
            String structuralHeader = removeAnnotations(header).trim();
            if (header.isBlank() || !structuralHeader.contains("(") || topLevelIndexOf(structuralHeader, '=') >= 0
                || structuralTypeDeclaration(structuralHeader)) {
                return Optional.empty();
            }
            int paren = lastTopLevel(header, '(');
            if (paren < 0) {
                return Optional.empty();
            }
            int paramEnd = matching(header, paren, '(', ')');
            if (paramEnd < 0) {
                return Optional.empty();
            }
            String name = previousIdentifier(header, paren);
            if (name == null || name.isBlank() || controlKeyword(name)) {
                return Optional.empty();
            }
            int nameStart = previousIdentifierStart(header, paren);
            String prefix = header.substring(0, nameStart);
            List<AnnotationDescriptor> annotations = parseAnnotations(leadingAnnotationText(prefix));
            String signaturePrefix = removeAnnotations(prefix).trim();
            List<String> prefixTokens = words(signaturePrefix);
            boolean isStatic = prefixTokens.contains("static");
            ExecutableKind kind = name.equals(className) ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
            String returnTypeSource = executableReturnTypeSource(prefix);
            String returnType = kind == ExecutableKind.CONSTRUCTOR || prefixTokens.isEmpty()
                    ? "void" : resolveErasedType(removeAnnotations(returnTypeSource));
            TypeUseDescriptor returnTypeUse = kind == ExecutableKind.CONSTRUCTOR
                    ? TypeUseDescriptor.EMPTY : parseTypeUse(returnTypeSource);
            List<ParameterDescriptor> parameters = parseParameters(header.substring(paren + 1, paramEnd));
            return Optional.of(new ExecutableDescriptor(
                    kind, name, returnType, returnTypeUse, parameters, annotations, isStatic));
        }

        private String executableReturnTypeSource(String prefix) {
            String result = removeLeadingAnnotations(prefix).trim();
            boolean changed;
            do {
                changed = false;
                for (String modifier : MODIFIERS) {
                    if (result.equals(modifier) || result.startsWith(modifier + " ")) {
                        result = result.substring(modifier.length()).trim();
                        changed = true;
                    }
                }
                if (result.startsWith("<")) {
                    int end = matching(result, 0, '<', '>');
                    if (end >= 0) {
                        result = result.substring(end + 1).trim();
                        changed = true;
                    }
                }
                result = removeLeadingAnnotations(result).trim();
            } while (changed);
            return result;
        }

        private List<ParameterDescriptor> parseParameters(String parameters) {
            List<ParameterDescriptor> result = new ArrayList<>();
            for (String parameter : splitTopLevel(parameters, ',')) {
                String trimmed = parameter.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                List<AnnotationDescriptor> annotations = parseAnnotations(leadingAnnotationText(trimmed));
                String cleaned = removeAnnotations(trimmed).replace("final ", "").trim();
                String name = lastIdentifier(cleaned);
                if (name == null) {
                    continue;
                }
                int nameStart = cleaned.lastIndexOf(name);
                String typeText = cleaned.substring(0, nameStart).trim();
                String type = typeText.replace("...", "[]");
                if (type.isBlank()) {
                    continue;
                }
                int originalNameStart = trimmed.lastIndexOf(name);
                TypeUseDescriptor typeUse = parseTypeUse(
                        originalNameStart < 0 ? typeText : trimmed.substring(0, originalNameStart).trim());
                result.add(new ParameterDescriptor(
                        name, resolveErasedType(type), annotations, typeUse));
            }
            return result;
        }

        private String resolveErasedType(String typeText) {
            String normalized = stripLeadingAnnotationsAndModifiers(
                    typeText == null ? "" : typeText.replace("...", "[]").trim());
            if (normalized.isBlank()) {
                return "java.lang.Object";
            }
            int dimensions = 0;
            while (normalized.endsWith("[]")) {
                dimensions++;
                normalized = normalized.substring(0, normalized.length() - 2).trim();
            }
            String erased = eraseGeneric(normalized).trim();
            String resolved = erased.isBlank() ? "java.lang.Object" : resolveType(erased);
            return resolved + "[]".repeat(dimensions);
        }

        private int findTypeBodyStart(int start, int rangeEnd) {
            int generic = 0;
            int paren = 0;
            for (int i = start; i < rangeEnd; i++) {
                char c = source.charAt(i);
                if (c == '"' || c == '\'') {
                    i = skipQuoted(source, i);
                } else if (c == '<') {
                    generic++;
                } else if (c == '>') {
                    generic = Math.max(0, generic - 1);
                } else if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '{' && generic == 0 && paren == 0) {
                    return i;
                }
            }
            return -1;
        }

        private List<AnnotationDescriptor> parseAnnotations(String text) {
            List<AnnotationDescriptor> result = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                int at = text.indexOf('@', i);
                if (at < 0) {
                    break;
                }
                int nameStart = at + 1;
                int nameEnd = nameStart;
                while (nameEnd < text.length()
                       && (Character.isJavaIdentifierPart(text.charAt(nameEnd)) || text.charAt(nameEnd) == '.')) {
                    nameEnd++;
                }
                if (nameEnd == nameStart) {
                    i = at + 1;
                    continue;
                }
                String rawName = text.substring(nameStart, nameEnd);
                String simpleName = simpleName(rawName);
                if (rawName.endsWith(".") || simpleName.isBlank()) {
                    i = nameEnd;
                    continue;
                }
                String qualifiedName = resolveAnnotationName(rawName);
                AnnotationAttributes attributes = AnnotationAttributes.empty();
                int next = skipWhitespace(text, nameEnd);
                if (next < text.length() && text.charAt(next) == '(') {
                    int end = matching(text, next, '(', ')');
                    if (end > next) {
                        attributes = parseAttributes(text.substring(next + 1, end));
                        i = end + 1;
                    } else {
                        i = next + 1;
                    }
                } else {
                    i = nameEnd;
                }
                if (SOURCE_ONLY_ANNOTATIONS.contains(qualifiedName)) {
                    continue;
                }
                result.add(new AnnotationDescriptor(
                        simpleName, qualifiedName, attributes.values(), attributes.nestedAnnotations(), List.of()));
            }
            return result;
        }

        private AnnotationAttributes parseAttributes(String attributes) {
            if (attributes == null || attributes.isBlank()) {
                return AnnotationAttributes.empty();
            }
            Map<String, List<String>> values = new LinkedHashMap<>();
            Map<String, List<AnnotationDescriptor>> nestedAnnotations = new LinkedHashMap<>();
            List<String> parts = splitTopLevel(attributes, ',');
            boolean singleValue = parts.size() == 1 && topLevelIndexOf(parts.getFirst(), '=') < 0;
            if (singleValue) {
                values.put("value", parseValues(parts.getFirst()));
                List<AnnotationDescriptor> nested = parseNestedAnnotations(parts.getFirst());
                if (!nested.isEmpty()) {
                    nestedAnnotations.put("value", nested);
                }
                return new AnnotationAttributes(values, nestedAnnotations);
            }
            for (String part : parts) {
                int equals = topLevelIndexOf(part, '=');
                String name = equals < 0 ? "value" : part.substring(0, equals).trim();
                String value = equals < 0 ? part : part.substring(equals + 1);
                values.put(name, parseValues(value));
                List<AnnotationDescriptor> nested = parseNestedAnnotations(value);
                if (!nested.isEmpty()) {
                    nestedAnnotations.put(name, nested);
                }
            }
            return new AnnotationAttributes(values, nestedAnnotations);
        }

        private List<String> parseValues(String value) {
            value = value.trim();
            if (value.startsWith("{") && value.endsWith("}")) {
                return splitTopLevel(value.substring(1, value.length() - 1), ',').stream()
                        .map(this::normalizeValue)
                        .filter(v -> !v.isBlank())
                        .toList();
            }
            return List.of(normalizeValue(value));
        }

        private List<AnnotationDescriptor> parseNestedAnnotations(String value) {
            value = value.trim();
            if (value.startsWith("{") && value.endsWith("}")) {
                return splitTopLevel(value.substring(1, value.length() - 1), ',').stream()
                        .flatMap(part -> parseNestedAnnotations(part).stream())
                        .toList();
            }
            if (!value.startsWith("@")) {
                return List.of();
            }
            int nameStart = 1;
            int nameEnd = nameStart;
            while (nameEnd < value.length()
                   && (Character.isJavaIdentifierPart(value.charAt(nameEnd)) || value.charAt(nameEnd) == '.')) {
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                return List.of();
            }
            String rawName = value.substring(nameStart, nameEnd);
            AnnotationAttributes attributes = AnnotationAttributes.empty();
            int next = skipWhitespace(value, nameEnd);
            if (next < value.length() && value.charAt(next) == '(') {
                int end = matching(value, next, '(', ')');
                if (end > next) {
                    attributes = parseAttributes(value.substring(next + 1, end));
                }
            }
            return List.of(new AnnotationDescriptor(
                    simpleName(rawName), resolveAnnotationName(rawName),
                    attributes.values(), attributes.nestedAnnotations(), List.of()));
        }

        private String normalizeValue(String value) {
            value = value.trim();
            if (value.startsWith("\"\"\"") && value.endsWith("\"\"\"") && value.length() >= 6) {
                String text = value.substring(3, value.length() - 3).stripIndent();
                if (text.startsWith("\r\n")) {
                    return text.substring(2);
                }
                if (text.startsWith("\n")) {
                    return text.substring(1);
                }
                return text;
            }
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                return value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            if (value.endsWith(".class")) {
                return resolveType(value.substring(0, value.length() - ".class".length()));
            }
            String constant = constantValue(value).orElse(null);
            if (constant != null) {
                return constant;
            }
            if (NUMERIC_LITERAL.matcher(value).matches()) {
                return value.replace("_", "").replaceFirst("[lL]$", "");
            }
            if (QUALIFIED_CONSTANT.matcher(value).matches()) {
                return value.substring(value.lastIndexOf('.') + 1);
            }
            return value;
        }

        private Optional<String> constantValue(String value) {
            String sourceConstant = sourceConstants.get(value);
            if (sourceConstant != null) {
                return Optional.of(sourceConstant);
            }
            if (QUALIFIED_CONSTANT.matcher(value).matches()) {
                sourceConstant = sourceConstants.get(value.substring(value.lastIndexOf('.') + 1));
                if (sourceConstant != null) {
                    return Optional.of(sourceConstant);
                }
            }
            String direct = KNOWN_CONSTANT_VALUES.get(value);
            if (direct != null) {
                return Optional.of(direct);
            }
            String normalized = value.replace("_", "");
            direct = KNOWN_CONSTANT_VALUES.get(normalized);
            if (direct != null) {
                return Optional.of(direct);
            }
            String imported = imports.get(value);
            if (imported != null) {
                return Optional.ofNullable(KNOWN_CONSTANT_VALUES.get(imported));
            }
            return Optional.empty();
        }

        private Map<String, String> sourceConstants() {
            Map<String, String> result = new LinkedHashMap<>();
            Set<String> ambiguous = new LinkedHashSet<>();
            Matcher matcher = SOURCE_CONSTANT_DECLARATION.matcher(source);
            while (matcher.find()) {
                String type = matcher.group(1);
                for (String declaration : splitTopLevel(matcher.group(2), ',')) {
                    int assignment = declaration.indexOf('=');
                    if (assignment <= 0) {
                        continue;
                    }
                    String name = declaredConstantName(declaration.substring(0, assignment));
                    if (name == null) {
                        continue;
                    }
                    Optional<String> value = sourceConstantValue(type, declaration.substring(assignment + 1));
                    if (value.isEmpty()) {
                        continue;
                    }
                    String previous = result.putIfAbsent(name, value.orElseThrow());
                    if (previous != null && !previous.equals(value.orElseThrow())) {
                        ambiguous.add(name);
                    }
                }
            }
            ambiguous.forEach(result::remove);
            return Map.copyOf(result);
        }

        private String declaredConstantName(String declarationPrefix) {
            Matcher matcher = IDENTIFIER.matcher(declarationPrefix);
            String name = null;
            while (matcher.find()) {
                name = matcher.group();
            }
            return name;
        }

        private Optional<String> sourceConstantValue(String type, String expression) {
            String value = expression.trim();
            if ("String".equals(type)) {
                return sourceStringConstantValue(value);
            }
            if ("boolean".equals(type)) {
                return "true".equals(value) || "false".equals(value) ? Optional.of(value) : Optional.empty();
            }
            if (NUMERIC_LITERAL.matcher(value).matches()) {
                return Optional.of(value.replace("_", "").replaceFirst("[lL]$", ""));
            }
            return Optional.empty();
        }

        private Optional<String> sourceStringConstantValue(String expression) {
            StringBuilder result = new StringBuilder();
            for (String part : splitTopLevel(expression, '+')) {
                String value = part.trim();
                Optional<String> literal = stringLiteralValue(value);
                if (literal.isPresent()) {
                    result.append(literal.orElseThrow());
                    continue;
                }
                return Optional.empty();
            }
            return Optional.of(result.toString());
        }

        private Optional<String> stringLiteralValue(String value) {
            if (value.startsWith("\"\"\"") && value.endsWith("\"\"\"") && value.length() >= 6) {
                String text = value.substring(3, value.length() - 3).stripIndent();
                if (text.startsWith("\r\n")) {
                    return Optional.of(text.substring(2));
                }
                if (text.startsWith("\n")) {
                    return Optional.of(text.substring(1));
                }
                return Optional.of(text);
            }
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                return Optional.of(value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\"));
            }
            return Optional.empty();
        }

        private String resolveAnnotationName(String name) {
            if (name.contains(".")) {
                return name;
            }
            if (localTypes.containsKey(name)) {
                return localTypes.get(name);
            }
            if (imports.containsKey(name)) {
                return imports.get(name);
            }
            if (KNOWN_ANNOTATIONS.containsKey(name)) {
                return KNOWN_ANNOTATIONS.get(name);
            }
            if (JAVA_LANG_ANNOTATIONS.contains(name)) {
                return "java.lang." + name;
            }
            return packageName.isBlank() ? name : packageName + "." + name;
        }

        private String resolveType(String type) {
            type = type.trim();
            if (localTypes.containsKey(type)) {
                return localTypes.get(type);
            }
            if (type.contains(".")) {
                return type;
            }
            if (PRIMITIVE_TYPES.contains(type)) {
                return type;
            }
            if (imports.containsKey(type)) {
                return imports.get(type);
            }
            if (KNOWN_TYPES.containsKey(type)) {
                return KNOWN_TYPES.get(type);
            }
            if (JAVA_LANG_TYPES.contains(type)) {
                return "java.lang." + type;
            }
            if (!wildcardImports.isEmpty()) {
                return type;
            }
            return packageName.isBlank() ? type : packageName + "." + type;
        }

        private Map<String, String> localTypes() {
            Map<String, String> result = new LinkedHashMap<>();
            collectLocalTypes(0, source.length(), null, result);
            collectLocalAnnotationTypes(0, source.length(), null, result);
            return result;
        }

        private void collectLocalTypes(
                int rangeStart, int rangeEnd, String enclosingCanonicalName, Map<String, String> result) {
            Matcher matcher = TYPE_PATTERN.matcher(source);
            matcher.region(rangeStart, rangeEnd);
            while (matcher.find()) {
                if (matcher.start() > 0 && source.charAt(matcher.start() - 1) == '@'
                    || insideAnnotation(matcher.start()) || memberDepth(rangeStart, matcher.start()) != 0) {
                    continue;
                }
                String simpleName = matcher.group(2);
                String canonicalName = enclosingCanonicalName == null
                        ? simpleName : enclosingCanonicalName + "." + simpleName;
                String fullName = packageName.isBlank() ? canonicalName : packageName + "." + canonicalName;
                result.putIfAbsent(simpleName, fullName);
                result.putIfAbsent(canonicalName, fullName);
                int bodyStart = findTypeBodyStart(matcher.end(), rangeEnd);
                if (bodyStart < 0 || bodyStart >= rangeEnd) {
                    continue;
                }
                int bodyEnd = matching(source, bodyStart, '{', '}');
                if (bodyEnd < 0 || bodyEnd > rangeEnd) {
                    bodyEnd = rangeEnd;
                }
                collectLocalAnnotationTypes(bodyStart + 1, bodyEnd, canonicalName, result);
                collectLocalTypes(bodyStart + 1, bodyEnd, canonicalName, result);
            }
        }

        private void collectLocalAnnotationTypes(
                int rangeStart, int rangeEnd, String enclosingCanonicalName, Map<String, String> result) {
            Matcher matcher = ANNOTATION_TYPE_PATTERN.matcher(source);
            matcher.region(rangeStart, rangeEnd);
            while (matcher.find()) {
                if (insideAnnotation(matcher.start()) || memberDepth(rangeStart, matcher.start()) != 0) {
                    continue;
                }
                String simpleName = matcher.group(1);
                String canonicalName = enclosingCanonicalName == null
                        ? simpleName : enclosingCanonicalName + "." + simpleName;
                String fullName = packageName.isBlank() ? canonicalName : packageName + "." + canonicalName;
                result.putIfAbsent(simpleName, fullName);
                result.putIfAbsent(canonicalName, fullName);
                int bodyStart = findTypeBodyStart(matcher.end(), rangeEnd);
                if (bodyStart < 0 || bodyStart >= rangeEnd) {
                    continue;
                }
                int bodyEnd = matching(source, bodyStart, '{', '}');
                if (bodyEnd < 0 || bodyEnd > rangeEnd) {
                    bodyEnd = rangeEnd;
                }
                collectLocalAnnotationTypes(bodyStart + 1, bodyEnd, canonicalName, result);
            }
        }

        private record AnnotationAttributes(
                Map<String, List<String>> values,
                Map<String, List<AnnotationDescriptor>> nestedAnnotations) {
            private static AnnotationAttributes empty() {
                return new AnnotationAttributes(Map.of(), Map.of());
            }
        }

        private String resolveGenericType(String type) {
            String normalized = type.replace("...", "[]").trim();
            Matcher matcher = Pattern.compile("\\b[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\b")
                    .matcher(normalized);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String token = matcher.group();
                String replacement = switch (token) {
                    case "extends", "super" -> token;
                    default -> resolveType(token);
                };
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private static int nextKeywordIndex(String text, int start) {
            int extendsIndex = wordIndexOf(text, "extends", start);
            int implementsIndex = wordIndexOf(text, "implements", start);
            if (extendsIndex < 0) {
                return implementsIndex;
            }
            if (implementsIndex < 0) {
                return extendsIndex;
            }
            return Math.min(extendsIndex, implementsIndex);
        }

        private static int wordIndexOf(String text, String word) {
            return wordIndexOf(text, word, 0);
        }

        private static int wordIndexOf(String text, String word, int start) {
            for (int index = text.indexOf(word, start); index >= 0; index = text.indexOf(word, index + 1)) {
                boolean before = index == 0 || !Character.isJavaIdentifierPart(text.charAt(index - 1));
                int afterIndex = index + word.length();
                boolean after = afterIndex >= text.length()
                                || !Character.isJavaIdentifierPart(text.charAt(afterIndex));
                if (before && after) {
                    return index;
                }
            }
            return -1;
        }

        private boolean isStringStart(int offset) {
            char c = source.charAt(offset);
            return c == '"' || c == '\'';
        }

        private int skipString(int offset) {
            char quote = source.charAt(offset);
            int i = offset + 1;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    return i + 1;
                }
                i++;
            }
            return i;
        }

        private static Optional<String> first(Pattern pattern, String source) {
            Matcher matcher = pattern.matcher(source);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }

        private static Map<String, String> imports(String source) {
            Map<String, String> result = new HashMap<>();
            Matcher matcher = IMPORT_PATTERN.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2) == null) {
                    String name = matcher.group(1);
                    result.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
            }
            return result;
        }

        private static List<String> wildcardImports(String source) {
            List<String> result = new ArrayList<>();
            Matcher matcher = IMPORT_PATTERN.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2) != null) {
                    result.add(matcher.group(1));
                }
            }
            return result;
        }
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean string = false;
        boolean character = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (!character && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    result.append(source.substring(i));
                    break;
                }
                result.append(source, i, end + 3);
                i = end + 2;
                continue;
            }
            if (!string && !character && c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                if (i < source.length()) {
                    result.append('\n');
                }
                continue;
            }
            if (!string && !character && c == '/' && next == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    result.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i++;
                continue;
            }
            result.append(c);
            if (c == '\\') {
                if (i + 1 < source.length()) {
                    result.append(source.charAt(++i));
                }
                continue;
            }
            if (!character && c == '"') {
                string = !string;
            } else if (!string && c == '\'') {
                character = !character;
            }
        }
        return result.toString();
    }

    private static int matching(String source, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipQuoted(String source, int start) {
        if (source.startsWith("\"\"\"", start)) {
            int i = start + 3;
            while (i < source.length()) {
                if (source.startsWith("\"\"\"", i)) {
                    return i + 2;
                }
                i++;
            }
            return source.length() - 1;
        }
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i;
            }
            i++;
        }
        return source.length() - 1;
    }

    private static int lastTopLevel(String source, char target) {
        int result = -1;
        int generic = 0;
        int paren = 0;
        int brace = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
            } else if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == '(') {
                if (generic == 0 && paren == 0 && brace == 0 && c == target) {
                    result = i;
                }
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace = Math.max(0, brace - 1);
            }
        }
        return result;
    }

    private record HandlerMatch(AnnotationDescriptor annotation, HandlerSpec spec) {
    }

    private static int topLevelIndexOf(String source, char target) {
        int generic = 0;
        int paren = 0;
        int brace = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
            } else if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace = Math.max(0, brace - 1);
            } else if (c == target && generic == 0 && paren == 0 && brace == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String source, char separator) {
        List<String> result = new ArrayList<>();
        int generic = 0;
        int paren = 0;
        int brace = 0;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(source, i);
            } else if (c == '<') {
                generic++;
            } else if (c == '>') {
                generic = Math.max(0, generic - 1);
            } else if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace = Math.max(0, brace - 1);
            } else if (c == separator && generic == 0 && paren == 0 && brace == 0) {
                result.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = source.substring(start).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private static String removeAnnotations(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != '@') {
                result.append(c);
                i++;
                continue;
            }
            i++;
            while (i < source.length() && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) {
                i++;
            }
            i = skipWhitespace(source, i);
            if (i < source.length() && source.charAt(i) == '(') {
                int end = matching(source, i, '(', ')');
                i = end < 0 ? source.length() : end + 1;
            }
            result.append(' ');
        }
        return result.toString();
    }

    private static List<String> words(String source) {
        List<String> result = new ArrayList<>();
        for (String word : source.replace('\n', ' ').replace('\r', ' ').split("\\s+")) {
            if (!word.isBlank()) {
                result.add(word);
            }
        }
        return result;
    }

    private static String eraseGeneric(String type) {
        int genericStart = type.indexOf('<');
        return genericStart < 0 ? type : type.substring(0, genericStart);
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String previousIdentifier(String source, int offset) {
        int start = previousIdentifierStart(source, offset);
        return start < 0 ? null : source.substring(start, previousIdentifierEnd(source, offset));
    }

    private static int previousIdentifierStart(String source, int offset) {
        int end = previousIdentifierEnd(source, offset);
        if (end < 0) {
            return -1;
        }
        int start = end - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(source.charAt(start))) {
            start--;
        }
        return start + 1;
    }

    private static int previousIdentifierEnd(String source, int offset) {
        int end = offset - 1;
        while (end >= 0 && Character.isWhitespace(source.charAt(end))) {
            end--;
        }
        return end < 0 ? -1 : end + 1;
    }

    private static String lastIdentifier(String source) {
        Matcher matcher = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int skipWhitespace(String source, int offset) {
        while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static boolean controlKeyword(String name) {
        return Set.of("if", "for", "while", "switch", "catch", "try", "new", "return").contains(name);
    }

    private static boolean structuralTypeDeclaration(String header) {
        return words(header).stream().anyMatch(Set.of("class", "record", "interface", "enum")::contains);
    }
}
