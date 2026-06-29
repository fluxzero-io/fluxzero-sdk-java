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

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Build-time producer for the incubating Fluxzero component registry.
 * <p>
 * The processor observes javac's source model and writes {@value ComponentRegistryJson#DEFAULT_RESOURCE}. It does not
 * replace existing annotation processors and it does not alter runtime {@code TypeRegistry} behavior; {@code
 * @RegisterType} is captured as component metadata only.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({
        ComponentRegistryProcessor.ENABLED_OPTION,
        ComponentRegistryProcessor.OUTPUT_OPTION,
        "org.gradle.annotation.processing.aggregating"
})
public class ComponentRegistryProcessor extends AbstractProcessor {
    /**
     * Compiler option used to disable registry generation.
     */
    public static final String ENABLED_OPTION = "fluxzero.registry.enabled";

    /**
     * Compiler option for the class-output resource path.
     */
    public static final String OUTPUT_OPTION = "fluxzero.registry.output";

    private static final String REGISTER_TYPE = "io.fluxzero.common.serialization.RegisterType";
    private static final String CONSUMER = "io.fluxzero.sdk.tracking.Consumer";
    private static final String TRACK_SELF = "io.fluxzero.sdk.tracking.TrackSelf";
    private static final String LOCAL_HANDLER = "io.fluxzero.sdk.tracking.handling.LocalHandler";
    private static final String REQUEST = "io.fluxzero.sdk.tracking.handling.Request";
    private static final String ID = "io.fluxzero.sdk.modeling.Id";
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

    private static final Map<String, HandlerSpec> HANDLERS = Map.ofEntries(
            entry("io.fluxzero.sdk.tracking.handling.HandleCommand",
                  new HandlerSpec(MessageType.COMMAND, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleQuery",
                  new HandlerSpec(MessageType.QUERY, false, true, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleEvent",
                  new HandlerSpec(MessageType.EVENT, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleNotification",
                  new HandlerSpec(MessageType.NOTIFICATION, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleError",
                  new HandlerSpec(MessageType.ERROR, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleMetrics",
                  new HandlerSpec(MessageType.METRICS, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleResult",
                  new HandlerSpec(MessageType.RESULT, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleCustom",
                  new HandlerSpec(MessageType.CUSTOM, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleDocument",
                  new HandlerSpec(MessageType.DOCUMENT, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.tracking.handling.HandleSchedule",
                  new HandlerSpec(MessageType.SCHEDULE, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.web.HandleWebResponse",
                  new HandlerSpec(MessageType.WEBRESPONSE, false, false, false, List.of(), false, false)),
            entry("io.fluxzero.sdk.web.HandleWeb",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("ANY"), true, true)),
            entry("io.fluxzero.sdk.web.HandleGet",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("GET"), true, true)),
            entry("io.fluxzero.sdk.web.HandlePost",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("POST"), false, true)),
            entry("io.fluxzero.sdk.web.HandlePut",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PUT"), false, true)),
            entry("io.fluxzero.sdk.web.HandlePatch",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("PATCH"), false, true)),
            entry("io.fluxzero.sdk.web.HandleDelete",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("DELETE"), false, true)),
            entry("io.fluxzero.sdk.web.HandleHead",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("HEAD"), false, true)),
            entry("io.fluxzero.sdk.web.HandleOptions",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("OPTIONS"), false, false)),
            entry("io.fluxzero.sdk.web.HandleTrace",
                  new HandlerSpec(MessageType.WEBREQUEST, false, true, true, List.of("TRACE"), false, true)),
            entry("io.fluxzero.sdk.web.HandleSocketHandshake",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_HANDSHAKE"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketOpen",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_OPEN"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketMessage",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_MESSAGE"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketPong",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_PONG"), false, false)),
            entry("io.fluxzero.sdk.web.HandleSocketClose",
                  new HandlerSpec(MessageType.WEBREQUEST, false, false, true, List.of("WS_CLOSE"), false, false)));
    private static final List<String> HANDLER_NAMES = List.of(
            "io.fluxzero.sdk.tracking.handling.HandleCommand",
            "io.fluxzero.sdk.tracking.handling.HandleQuery",
            "io.fluxzero.sdk.tracking.handling.HandleEvent",
            "io.fluxzero.sdk.tracking.handling.HandleNotification",
            "io.fluxzero.sdk.tracking.handling.HandleError",
            "io.fluxzero.sdk.tracking.handling.HandleMetrics",
            "io.fluxzero.sdk.tracking.handling.HandleResult",
            "io.fluxzero.sdk.tracking.handling.HandleCustom",
            "io.fluxzero.sdk.tracking.handling.HandleDocument",
            "io.fluxzero.sdk.tracking.handling.HandleSchedule",
            "io.fluxzero.sdk.web.HandleWebResponse",
            "io.fluxzero.sdk.web.HandleGet",
            "io.fluxzero.sdk.web.HandlePost",
            "io.fluxzero.sdk.web.HandlePut",
            "io.fluxzero.sdk.web.HandlePatch",
            "io.fluxzero.sdk.web.HandleDelete",
            "io.fluxzero.sdk.web.HandleHead",
            "io.fluxzero.sdk.web.HandleOptions",
            "io.fluxzero.sdk.web.HandleTrace",
            "io.fluxzero.sdk.web.HandleSocketHandshake",
            "io.fluxzero.sdk.web.HandleSocketOpen",
            "io.fluxzero.sdk.web.HandleSocketMessage",
            "io.fluxzero.sdk.web.HandleSocketPong",
            "io.fluxzero.sdk.web.HandleSocketClose",
            "io.fluxzero.sdk.web.HandleWeb");

    private final Map<String, TypeElement> types = new LinkedHashMap<>();
    private final Map<String, PackageElement> packages = new LinkedHashMap<>();
    private boolean invocationsWritten;
    private boolean written;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!enabled()) {
            return false;
        }
        roundEnv.getRootElements().forEach(this::collect);
        if (!roundEnv.processingOver() && !invocationsWritten) {
            invocationsWritten = true;
            writeGeneratedInvocations();
        }
        if (roundEnv.processingOver() && !written) {
            written = true;
            writeRegistry();
        }
        return false;
    }

    private boolean enabled() {
        String configured = processingEnv.getOptions().get(ENABLED_OPTION);
        return configured == null || !"false".equalsIgnoreCase(configured);
    }

    private void collect(Element element) {
        if (element instanceof TypeElement type && componentKind(type).isPresent()) {
            if (!type.getSimpleName().toString().startsWith("FluxzeroGeneratedInvocations_")) {
                types.putIfAbsent(binaryName(type), type);
                collectPackage(type);
            }
        }
        for (Element enclosed : element.getEnclosedElements()) {
            collect(enclosed);
        }
    }

    private void collectPackage(TypeElement type) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(type);
        for (String name = packageElement.getQualifiedName().toString(); name != null; name = parentPackage(name)) {
            PackageElement current = processingEnv.getElementUtils().getPackageElement(name);
            if (current != null) {
                packages.putIfAbsent(name, current);
            }
        }
    }

    private void writeRegistry() {
        ComponentRegistry registry = registry();
        if (registry.packages().isEmpty() && registry.components().isEmpty()) {
            return;
        }
        String output = processingEnv.getOptions().getOrDefault(
                OUTPUT_OPTION, ComponentRegistryJson.DEFAULT_RESOURCE);
        try {
            FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", output);
            try (Writer writer = resource.openWriter()) {
                ComponentRegistryJson.write(registry.normalized(), writer);
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR, "Failed to write Fluxzero component registry: " + e.getMessage());
        }
    }

    private void writeGeneratedInvocations() {
        Map<String, List<TypeElement>> typesByPackage = types.values().stream()
                .collect(Collectors.groupingBy(
                        type -> processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<String> providerNames = new ArrayList<>();
        typesByPackage.forEach((packageName, packageTypes) -> {
            if (packageName.isBlank()) {
                return;
            }
            List<GeneratedInvocationTarget> targets = packageTypes.stream()
                    .sorted(Comparator.comparing(this::binaryName))
                    .map(type -> generatedInvocationTarget(type).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            if (targets.isEmpty()) {
                return;
            }
            String className = generatedInvocationClassName(packageName, targets);
            String providerName = packageName + "." + className;
            try {
                JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(providerName);
                try (Writer writer = sourceFile.openWriter()) {
                    writer.write(generatedInvocationSource(packageName, className, targets));
                }
                providerNames.add(providerName);
            } catch (Exception e) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Failed to write Fluxzero generated invocation registrar " + providerName + ": "
                        + e.getMessage());
            }
        });
        if (providerNames.isEmpty()) {
            return;
        }
        try {
            FileObject service = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/io.fluxzero.common.handling.GeneratedExecutableInvocationRegistrar");
            try (Writer writer = service.openWriter()) {
                writer.write(String.join(System.lineSeparator(), providerNames));
                writer.write(System.lineSeparator());
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to write Fluxzero generated invocation service descriptor: " + e.getMessage());
        }
    }

    private Optional<GeneratedInvocationTarget> generatedInvocationTarget(TypeElement type) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        if (isPrivate(type) || type.getModifiers().contains(Modifier.ABSTRACT)
            || !canInstantiateFromGeneratedSource(type)) {
            return Optional.empty();
        }
        List<GeneratedInvocationExecutable> executables = executableElements(type).stream()
                .filter(executable -> !isPrivate(executable))
                .filter(executable -> !executable.getModifiers().contains(Modifier.ABSTRACT))
                .filter(executable -> !executable.getModifiers().contains(Modifier.NATIVE))
                .map(executable -> generatedInvocationExecutable(packageName, executable))
                .flatMap(Optional::stream)
                .toList();
        return executables.isEmpty() ? Optional.empty()
                : Optional.of(new GeneratedInvocationTarget(binaryName(type), sourceTypeName(type), executables));
    }

    private Optional<GeneratedInvocationExecutable> generatedInvocationExecutable(
            String packageName, ExecutableElement executable) {
        if (!canReferenceFromGeneratedSource(executable.getReturnType(), packageName)
            || executable.getParameters().stream()
                    .anyMatch(parameter -> !canReferenceFromGeneratedSource(parameter.asType(), packageName))) {
            return Optional.empty();
        }
        ExecutableDescriptor descriptor = executableDescriptor(executable);
        String executableId = InvocationPlanDescriptor.executableId(
                descriptor.kind(), descriptor.name(), descriptor.parameters().stream()
                        .map(ParameterDescriptor::typeName)
                        .toList());
        return Optional.of(new GeneratedInvocationExecutable(
                descriptor.kind(), descriptor.name(), executableId,
                executable.getReturnType().getKind() == TypeKind.VOID,
                executable.getModifiers().contains(Modifier.STATIC),
                executable.getParameters().stream()
                        .map(parameter -> parameterExpression(parameter.asType()))
                        .toList()));
    }

    private static boolean isPrivate(Element element) {
        for (Element current = element; current != null; current = current.getEnclosingElement()) {
            if (current.getModifiers().contains(Modifier.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canInstantiateFromGeneratedSource(TypeElement type) {
        if (type.getNestingKind() == NestingKind.TOP_LEVEL) {
            return true;
        }
        return type.getNestingKind() == NestingKind.MEMBER && type.getModifiers().contains(Modifier.STATIC);
    }

    private boolean canReferenceFromGeneratedSource(TypeMirror type, String packageName) {
        if (type.getKind() == TypeKind.ARRAY) {
            return canReferenceFromGeneratedSource(((ArrayType) type).getComponentType(), packageName);
        }
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return true;
        }
        Element element = processingEnv.getTypeUtils().asElement(processingEnv.getTypeUtils().erasure(type));
        if (!(element instanceof TypeElement referencedType)) {
            return true;
        }
        if (referencedType.getNestingKind() == NestingKind.LOCAL
            || referencedType.getNestingKind() == NestingKind.ANONYMOUS
            || isPrivate(referencedType)) {
            return false;
        }
        String referencedPackage = processingEnv.getElementUtils()
                .getPackageOf(referencedType).getQualifiedName().toString();
        if (packageName.equals(referencedPackage)) {
            return true;
        }
        for (Element current = referencedType; current instanceof TypeElement; current = current.getEnclosingElement()) {
            if (!current.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }

    private String generatedInvocationClassName(String packageName, List<GeneratedInvocationTarget> targets) {
        String fingerprint = targets.stream()
                .map(target -> target.componentName() + target.executables().stream()
                        .map(GeneratedInvocationExecutable::executableId)
                        .collect(Collectors.joining()))
                .collect(Collectors.joining("|", packageName, ""));
        return "FluxzeroGeneratedInvocations_" + Integer.toHexString(fingerprint.hashCode()).replace('-', '_');
    }

    private String generatedInvocationSource(
            String packageName, String className, List<GeneratedInvocationTarget> targets) {
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("public final class ").append(className)
                .append(" implements io.fluxzero.common.handling.GeneratedExecutableInvocationRegistrar {\n")
                .append("    @Override\n")
                .append("    public io.fluxzero.common.Registration register(java.util.Collection<String> componentNames) {\n")
                .append("        io.fluxzero.common.Registration registration = io.fluxzero.common.Registration.noOp();\n");
        for (GeneratedInvocationTarget target : targets) {
            source.append("        if (include(componentNames, \"").append(escapeJava(target.componentName()))
                    .append("\")) {\n");
            for (GeneratedInvocationExecutable executable : target.executables()) {
                source.append("            registration = registration.merge(")
                        .append("io.fluxzero.common.handling.GeneratedExecutableInvocations.register(")
                        .append(target.sourceTypeName()).append(".class, \"")
                        .append(escapeJava(executable.executableId())).append("\", ")
                        .append(invocationLambda(target, executable)).append("));\n");
            }
            source.append("        }\n");
        }
        source.append("        return registration;\n")
                .append("    }\n\n")
                .append("    private static boolean include(java.util.Collection<String> componentNames, String componentName) {\n")
                .append("        return componentNames == null || componentNames.isEmpty() || componentNames.contains(componentName);\n")
                .append("    }\n")
                .append("}\n");
        return source.toString();
    }

    private String invocationLambda(GeneratedInvocationTarget target, GeneratedInvocationExecutable executable) {
        String arguments = java.util.stream.IntStream.range(0, executable.parameterExpressions().size())
                .mapToObj(i -> executable.parameterExpressions().get(i).formatted(i))
                .collect(Collectors.joining(", "));
        String body;
        if (executable.kind() == ExecutableKind.CONSTRUCTOR) {
            body = "return new %s(%s);".formatted(target.sourceTypeName(), arguments);
            return invocationBlock(body);
        }
        String owner = executable.isStatic()
                ? target.sourceTypeName()
                : "((%s) target)".formatted(target.sourceTypeName());
        String call = owner + "." + executable.name() + "(" + arguments + ")";
        if (executable.returnsVoid()) {
            body = call + "; return null;";
        } else {
            body = "return " + call + ";";
        }
        return invocationBlock(body);
    }

    private static String invocationBlock(String body) {
        return "(target, parameterCount, parameterProvider) -> { try { " + body
               + " } catch (Throwable e) { throw io.fluxzero.common.ObjectUtils.rethrow(e); } }";
    }

    private String parameterExpression(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return "((" + primitiveWrapper(type.getKind()) + ") parameterProvider.apply(%s))";
        }
        return "((" + sourceTypeName(type) + ") parameterProvider.apply(%s))";
    }

    private static String primitiveWrapper(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> Boolean.class.getName();
            case BYTE -> Byte.class.getName();
            case CHAR -> Character.class.getName();
            case DOUBLE -> Double.class.getName();
            case FLOAT -> Float.class.getName();
            case INT -> Integer.class.getName();
            case LONG -> Long.class.getName();
            case SHORT -> Short.class.getName();
            default -> throw new IllegalArgumentException("Unsupported primitive kind: " + kind);
        };
    }

    private String sourceTypeName(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    private String sourceTypeName(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return sourceTypeName(((ArrayType) type).getComponentType()) + "[]";
        }
        if (type.getKind().isPrimitive()) {
            return type.toString();
        }
        return processingEnv.getTypeUtils().erasure(type).toString();
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ComponentRegistry registry() {
        List<String> allTypeNames = types.values().stream()
                .map(this::typeName)
                .sorted()
                .toList();
        List<PackageDescriptor> packageDescriptors = packages.values().stream()
                .sorted(Comparator.comparing(p -> p.getQualifiedName().toString()))
                .map(packageElement -> packageDescriptor(packageElement, allTypeNames))
                .filter(descriptor -> !descriptor.annotations().isEmpty()
                                      || !descriptor.registeredTypes().isEmpty()
                                      || descriptor.consumer() != null
                                      || !descriptor.capabilities().isEmpty())
                .toList();
        List<ComponentDescriptor> components = types.values().stream()
                .sorted(Comparator.comparing(this::binaryName))
                .map(type -> componentDescriptor(type, allTypeNames))
                .toList();
        return new ComponentRegistry(null, packageDescriptors, components);
    }

    private PackageDescriptor packageDescriptor(PackageElement packageElement, List<String> allTypeNames) {
        String packageName = packageElement.getQualifiedName().toString();
        List<AnnotationDescriptor> annotations = annotationDescriptors(packageElement.getAnnotationMirrors());
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(annotations, packageName, allTypeNames);
        ConsumerDescriptor consumer = consumerDescriptor(annotations).orElse(null);
        Set<ComponentCapability> capabilities = EnumSet.noneOf(ComponentCapability.class);
        localHandlerConfig(annotations).ifPresent(localHandler -> {
            capabilities.add(ComponentCapability.PACKAGE_LOCAL_HANDLER);
            if (localHandler.enabled()) {
                capabilities.add(ComponentCapability.LOCAL_HANDLER);
            }
            if (!localHandler.enabled() || localHandler.allowExternalMessages()) {
                capabilities.add(ComponentCapability.TRACKING_HANDLER);
            }
        });
        if (!registeredTypes.isEmpty()) {
            capabilities.add(ComponentCapability.REGISTERED_TYPE);
        }
        if (consumer != null) {
            capabilities.add(ComponentCapability.CONSUMER);
        }
        return new PackageDescriptor(packageName, null, annotations, registeredTypes, consumer, Set.copyOf(capabilities));
    }

    private ComponentDescriptor componentDescriptor(TypeElement type, List<String> allTypeNames) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        List<AnnotationDescriptor> annotations = annotationDescriptors(type.getAnnotationMirrors());
        List<RegisteredTypeDescriptor> registeredTypes = registeredTypes(annotations, typeName(type), allTypeNames);
        PackageMetadata packageMetadata = packageMetadata(packageName);
        ConsumerDescriptor consumer = consumerDescriptor(annotations).orElse(packageMetadata.consumer());
        LocalHandlerConfig typeLocalHandler = localHandlerConfig(annotations).orElse(null);
        List<PropertyDescriptor> properties = propertyDescriptors(type);
        List<ExecutableElement> elements = executableElements(type);
        List<ExecutableDescriptor> executables = elements.stream().map(this::executableDescriptor).toList();
        Set<HandlerRoute> routes = new LinkedHashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            ExecutableElement executableElement = elements.get(i);
            ExecutableDescriptor executable = executables.get(i);
            for (AnnotationDescriptor declaredAnnotation : executable.annotations()) {
                HandlerMatch match = handlerMatch(declaredAnnotation).orElse(null);
                if (match == null) {
                    continue;
                }
                AnnotationDescriptor annotation = match.annotation();
                HandlerSpec spec = match.spec();
                LocalHandlerConfig localHandler = localHandlerConfig(executable.annotations())
                        .orElse(typeLocalHandler != null ? typeLocalHandler : packageMetadata.localHandler());
                boolean selfHandler = isSelfHandler(spec, type, executableElement);
                boolean selfTracking = hasTrackSelf(executable.annotations()) || hasTrackSelf(annotations)
                                       || packageMetadata.trackSelf();
                boolean local = localHandler != null ? localHandler.enabled() : selfHandler && !selfTracking;
                boolean tracked = localHandler != null ? !local || localHandler.allowExternalMessages() : !local;
                boolean disabled = annotation.booleanValue("disabled", false);
                boolean passive = annotation.booleanValue("passive", spec.defaultPassive());
                boolean skipExpiredRequests =
                        annotation.booleanValue("skipExpiredRequests", spec.defaultSkipExpiredRequests());
                Set<String> allowedClasses = new LinkedHashSet<>(annotation.values("allowedClasses"));
                Set<String> payloadTypes = !allowedClasses.isEmpty() ? allowedClasses
                        : spec.web() ? Set.of() : executable.parameters().stream()
                                .map(ParameterDescriptor::typeName).findFirst()
                                .map(Set::of).orElseGet(() -> selfHandler ? Set.of(typeName(type)) : Set.of());
                routes.add(new HandlerRoute(
                        spec.messageType(), annotation, executable, disabled, passive, skipExpiredRequests,
                        local, tracked, payloadTypes, allowedClasses,
                        spec.web() ? webRoutes(annotation, spec, packageName, type, executable)
                                : List.of()));
            }
        }
        List<String> superTypeNames = superTypeNames(type);
        return new ComponentDescriptor(
                null, null, componentKind(type).orElse(ComponentKind.CLASS), packageName, className(type),
                superTypeNames, annotations, properties, executables, Set.copyOf(routes), registeredTypes, consumer,
                componentCapabilities(type, routes, registeredTypes, consumer, superTypeNames));
    }

    private List<PropertyDescriptor> propertyDescriptors(TypeElement type) {
        Map<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.FIELD && !element.getSimpleName().toString().startsWith("$"))
                .map(VariableElement.class::cast)
                .sorted(Comparator.comparing(field -> field.getSimpleName().toString()))
                .forEach(field -> properties.putIfAbsent(field.getSimpleName().toString(), new PropertyDescriptor(
                        field.getSimpleName().toString(), typeName(field.asType()), field.asType().toString(),
                        annotationDescriptors(field.getAnnotationMirrors()), typeUseDescriptor(field.asType()))));
        type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.RECORD_COMPONENT)
                .map(RecordComponentElement.class::cast)
                .sorted(Comparator.comparing(component -> component.getSimpleName().toString()))
                .forEach(component -> {
                    String name = component.getSimpleName().toString();
                    List<AnnotationDescriptor> annotations = mergeAnnotations(
                            Optional.ofNullable(properties.get(name)).map(PropertyDescriptor::annotations).orElse(List.of()),
                            annotationDescriptors(component.getAnnotationMirrors()));
                    properties.put(name, new PropertyDescriptor(
                            name, typeName(component.asType()), component.asType().toString(), annotations,
                            typeUseDescriptor(component.asType())));
                });
        type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(ExecutableElement.class::cast)
                .filter(method -> method.getParameters().isEmpty())
                .filter(method -> method.getReturnType().getKind() != TypeKind.VOID)
                .sorted(Comparator.comparing(method -> method.getSimpleName().toString()))
                .forEach(method -> {
                    List<AnnotationDescriptor> annotations = annotationDescriptors(method.getAnnotationMirrors());
                    String name = method.getSimpleName().toString();
                    if (properties.containsKey(name) || hasSemanticAnnotation(annotations)) {
                        properties.putIfAbsent(name, new PropertyDescriptor(
                                name, typeName(method.getReturnType()), method.getReturnType().toString(),
                                annotations, typeUseDescriptor(method.getReturnType())));
                    }
                });
        return List.copyOf(properties.values());
    }

    private static boolean hasSemanticAnnotation(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(AnnotationDescriptor::qualifiedName)
                .anyMatch(name -> !name.startsWith("java.lang."));
    }

    private List<ExecutableElement> executableElements(TypeElement type) {
        return type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD
                                   || element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .sorted(Comparator.comparing((ExecutableElement e) -> e.getSimpleName().toString())
                                .thenComparing(e -> e.asType().toString()))
                .toList();
    }

    private ExecutableDescriptor executableDescriptor(ExecutableElement executable) {
        ExecutableKind kind = executable.getKind() == ElementKind.CONSTRUCTOR
                ? ExecutableKind.CONSTRUCTOR : ExecutableKind.METHOD;
        String returnType = kind == ExecutableKind.CONSTRUCTOR ? "void" : typeName(executable.getReturnType());
        TypeUseDescriptor returnTypeUse = kind == ExecutableKind.CONSTRUCTOR
                ? TypeUseDescriptor.EMPTY : typeUseDescriptor(executable.getReturnType());
        List<ParameterDescriptor> parameters = executable.getParameters().stream()
                .map(parameter -> new ParameterDescriptor(
                        parameter.getSimpleName().toString(), typeName(parameter.asType()),
                        annotationDescriptors(parameter.getAnnotationMirrors()), typeUseDescriptor(parameter.asType())))
                .toList();
        return new ExecutableDescriptor(
                kind, executable.getSimpleName().toString(), returnType, returnTypeUse, parameters,
                annotationDescriptors(executable.getAnnotationMirrors()),
                executable.getModifiers().contains(Modifier.STATIC));
    }

    private TypeUseDescriptor typeUseDescriptor(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.NONE) {
            return TypeUseDescriptor.EMPTY;
        }
        TypeUseDescriptor componentType = type instanceof ArrayType arrayType
                ? typeUseDescriptor(arrayType.getComponentType()) : null;
        List<TypeUseDescriptor> typeArguments = type instanceof DeclaredType declaredType
                ? declaredType.getTypeArguments().stream().map(this::typeUseDescriptor).toList()
                : List.of();
        return new TypeUseDescriptor(typeName(type), annotationDescriptors(type.getAnnotationMirrors()),
                                     typeArguments, componentType);
    }

    private List<WebRouteDescriptor> webRoutes(AnnotationDescriptor annotation, HandlerSpec spec,
                                               String packageName,
                                               TypeElement type,
                                               ExecutableDescriptor executable) {
        List<String> packagePaths = packagePaths(packageName);
        Optional<String> typePath = typePath(type, packageName);
        Optional<String> methodPath = WebRoutePaths.pathValue(executable.annotations(), type.getSimpleName().toString());
        List<String> handlerPaths = annotation.values("value");
        List<String> methods = annotation.isOrHas("HandleWeb", "io.fluxzero.sdk.web.HandleWeb")
                               && !annotation.values("method").isEmpty()
                ? annotation.values("method") : spec.webMethods();
        boolean autoHead = annotation.booleanValue("autoHead", spec.defaultAutoHead());
        boolean autoOptions = annotation.booleanValue("autoOptions", spec.defaultAutoOptions());
        List<String> paths = WebRoutePaths.paths(packagePaths, typePath, methodPath, handlerPaths);
        return List.of(new WebRouteDescriptor(paths, methods, autoHead, autoOptions));
    }

    private Optional<String> typePath(TypeElement type, String packageName) {
        for (Element current = type; current instanceof TypeElement currentType;
             current = currentType.getEnclosingElement()) {
            Optional<String> result = WebRoutePaths.pathValue(
                    annotationDescriptors(currentType.getAnnotationMirrors()), simplePackageName(packageName));
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private boolean isSelfHandler(HandlerSpec spec, TypeElement type, ExecutableElement executable) {
        return (spec.messageType().isRequest() || spec.messageType() == MessageType.SCHEDULE)
               && !spec.web()
               && executable.getKind() == ElementKind.METHOD
               && executable.getParameters().isEmpty()
               && requiresPayloadInstance(type);
    }

    private boolean requiresPayloadInstance(TypeElement type) {
        if (type.getKind() == ElementKind.RECORD) {
            return true;
        }
        if (type.getKind() != ElementKind.CLASS) {
            return false;
        }
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        return !constructors.isEmpty()
               && constructors.stream().noneMatch(constructor -> constructor.getParameters().isEmpty());
    }

    private PackageMetadata packageMetadata(String packageName) {
        for (String current = packageName; current != null; current = parentPackage(current)) {
            PackageElement packageElement = packages.get(current);
            if (packageElement == null) {
                packageElement = processingEnv.getElementUtils().getPackageElement(current);
            }
            if (packageElement == null) {
                continue;
            }
            List<AnnotationDescriptor> annotations = annotationDescriptors(packageElement.getAnnotationMirrors());
            if (!annotations.isEmpty()) {
                return new PackageMetadata(
                        localHandlerConfig(annotations).orElse(null),
                        consumerDescriptor(annotations).orElse(null),
                        hasTrackSelf(annotations));
            }
        }
        return PackageMetadata.empty();
    }

    private List<String> packagePaths(String packageName) {
        List<String> result = new ArrayList<>();
        for (String current = packageName; current != null; current = parentPackage(current)) {
            PackageElement packageElement = packages.get(current);
            if (packageElement == null) {
                packageElement = processingEnv.getElementUtils().getPackageElement(current);
            }
            if (packageElement == null) {
                continue;
            }
            WebRoutePaths.pathValue(annotationDescriptors(packageElement.getAnnotationMirrors()), simplePackageName(current))
                    .ifPresent(path -> result.add(0, path));
        }
        return List.copyOf(result);
    }

    private static String simplePackageName(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? Objects.requireNonNullElse(packageName, "") : packageName.substring(lastDot + 1);
    }

    private List<AnnotationDescriptor> annotationDescriptors(Collection<? extends AnnotationMirror> annotations) {
        return annotations.stream()
                .map(this::annotationDescriptor)
                .sorted(Comparator.comparing(AnnotationDescriptor::qualifiedName))
                .toList();
    }

    private static List<AnnotationDescriptor> mergeAnnotations(
            List<AnnotationDescriptor> first, List<AnnotationDescriptor> second) {
        Map<String, AnnotationDescriptor> result = new LinkedHashMap<>();
        first.forEach(annotation -> result.putIfAbsent(annotation.qualifiedName(), annotation));
        second.forEach(annotation -> result.putIfAbsent(annotation.qualifiedName(), annotation));
        return List.copyOf(result.values());
    }

    private AnnotationDescriptor annotationDescriptor(AnnotationMirror annotation) {
        return annotationDescriptor(annotation, new LinkedHashSet<>());
    }

    private AnnotationDescriptor annotationDescriptor(AnnotationMirror annotation, Set<String> visited) {
        Element annotationElement = annotation.getAnnotationType().asElement();
        String qualifiedName = annotationElement instanceof TypeElement type
                ? type.getQualifiedName().toString() : annotation.getAnnotationType().toString();
        String simpleName = annotationElement.getSimpleName().toString();
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        Map<String, List<AnnotationDescriptor>> nestedAnnotations = new LinkedHashMap<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getSimpleName().toString()))
                .forEach(entry -> {
                    String name = entry.getKey().getSimpleName().toString();
                    attributes.put(name, values(entry.getValue()));
                    List<AnnotationDescriptor> nested = nestedAnnotations(entry.getValue());
                    if (!nested.isEmpty()) {
                        nestedAnnotations.put(name, nested);
                    }
                });
        visited.add(qualifiedName);
        List<AnnotationDescriptor> metaAnnotations = annotationElement.getAnnotationMirrors().stream()
                .filter(metaAnnotation -> includeMetaAnnotation(metaAnnotation, visited))
                .map(metaAnnotation -> annotationDescriptor(metaAnnotation, new LinkedHashSet<>(visited)))
                .toList();
        return new AnnotationDescriptor(simpleName, qualifiedName, attributes, nestedAnnotations, metaAnnotations);
    }

    private static boolean includeMetaAnnotation(AnnotationMirror annotation, Set<String> visited) {
        Element annotationElement = annotation.getAnnotationType().asElement();
        String qualifiedName = annotationElement instanceof TypeElement type
                ? type.getQualifiedName().toString() : annotation.getAnnotationType().toString();
        return !qualifiedName.startsWith("java.lang.annotation.")
               && !visited.contains(qualifiedName);
    }

    private List<String> values(AnnotationValue value) {
        Object raw = value.getValue();
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof AnnotationValue annotationValue) {
                    result.addAll(values(annotationValue));
                } else {
                    result.add(value(item));
                }
            }
            return result;
        }
        return List.of(value(raw));
    }

    private String value(Object value) {
        if (value instanceof TypeMirror type) {
            return typeName(type);
        }
        if (value instanceof VariableElement enumConstant) {
            return enumConstant.getSimpleName().toString();
        }
        if (value instanceof AnnotationMirror annotation) {
            Element annotationElement = annotation.getAnnotationType().asElement();
            String name = annotationElement instanceof TypeElement type
                    ? type.getQualifiedName().toString() : annotation.getAnnotationType().toString();
            return "@" + name;
        }
        return String.valueOf(value);
    }

    private List<AnnotationDescriptor> nestedAnnotations(AnnotationValue value) {
        Object raw = value.getValue();
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<AnnotationDescriptor> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof AnnotationValue annotationValue) {
                    result.addAll(nestedAnnotations(annotationValue));
                }
            }
            return result;
        }
        if (raw instanceof AnnotationMirror annotation) {
            return List.of(annotationDescriptor(annotation));
        }
        return List.of();
    }

    private List<RegisteredTypeDescriptor> registeredTypes(
            List<AnnotationDescriptor> annotations, String defaultRoot, List<String> allTypeNames) {
        return annotations.stream()
                .map(annotation -> annotation.find("RegisterType", REGISTER_TYPE))
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

    private Optional<ConsumerDescriptor> consumerDescriptor(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.find("Consumer", CONSUMER))
                .flatMap(Optional::stream)
                .findFirst()
                .map(annotation -> new ConsumerDescriptor(
                        annotation.firstValue("name").or(() -> annotation.firstValue("value")).orElse(""),
                        annotation.attributes(), annotation));
    }

    private Optional<LocalHandlerConfig> localHandlerConfig(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.find("LocalHandler", LOCAL_HANDLER))
                .flatMap(Optional::stream)
                .reduce((first, second) -> second)
                .map(annotation -> {
                    boolean enabled = annotation.booleanValue("value", true);
                    boolean allowExternalMessages = enabled && annotation.booleanValue("allowExternalMessages", false);
                    return new LocalHandlerConfig(enabled, allowExternalMessages);
                });
    }

    private static boolean hasTrackSelf(List<AnnotationDescriptor> annotations) {
        return annotations.stream().anyMatch(annotation -> annotation.isOrHas("TrackSelf", TRACK_SELF));
    }

    private static Optional<HandlerMatch> handlerMatch(AnnotationDescriptor annotation) {
        for (String name : HANDLER_NAMES) {
            Optional<AnnotationDescriptor> match = annotation.find(simpleName(name), name);
            if (match.isPresent()) {
                return Optional.of(new HandlerMatch(match.get(), HANDLERS.get(name)));
            }
        }
        return Optional.empty();
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    private Set<ComponentCapability> componentCapabilities(TypeElement type, Set<HandlerRoute> routes,
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
                .map(INFRASTRUCTURE_CAPABILITIES::get)
                .filter(Objects::nonNull)
                .forEach(result::add);
        INFRASTRUCTURE_CAPABILITIES.forEach((typeName, capability) -> {
            if (isAssignable(type, typeName)) {
                result.add(capability);
            }
        });
        if (isAssignable(type, REQUEST)) {
            result.add(ComponentCapability.REQUEST_PAYLOAD);
        }
        if (isAssignable(type, ID)) {
            result.add(ComponentCapability.ID_SUBTYPE);
        }
        if (result.contains(ComponentCapability.HANDLER_INTERCEPTOR)) {
            result.add(ComponentCapability.HANDLER_DECORATOR);
        }
        if (result.contains(ComponentCapability.WEB_RESPONSE_MAPPER)) {
            result.add(ComponentCapability.RESPONSE_MAPPER);
        }
        return Set.copyOf(result);
    }

    private boolean isAssignable(TypeElement type, String targetTypeName) {
        TypeElement targetType = processingEnv.getElementUtils().getTypeElement(targetTypeName);
        if (targetType == null) {
            return false;
        }
        return processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(type.asType()),
                processingEnv.getTypeUtils().erasure(targetType.asType()));
    }

    private List<String> superTypeNames(TypeElement type) {
        List<String> result = new ArrayList<>();
        TypeMirror superclass = type.getSuperclass();
        if (superclass != null && superclass.getKind() != TypeKind.NONE
            && !"java.lang.Object".equals(superclass.toString())) {
            result.add(superclass.toString());
        }
        type.getInterfaces().stream().map(TypeMirror::toString).forEach(result::add);
        return List.copyOf(result);
    }

    private Optional<ComponentKind> componentKind(TypeElement type) {
        return switch (type.getKind()) {
            case CLASS -> Optional.of(ComponentKind.CLASS);
            case RECORD -> Optional.of(ComponentKind.RECORD);
            case INTERFACE, ANNOTATION_TYPE -> Optional.of(ComponentKind.INTERFACE);
            case ENUM -> Optional.of(ComponentKind.ENUM);
            default -> Optional.empty();
        };
    }

    private String binaryName(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    private String className(TypeElement type) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binaryName = binaryName(type);
        return packageName.isBlank() ? binaryName : binaryName.substring(packageName.length() + 1);
    }

    private String typeName(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    private String typeName(TypeMirror type) {
        if (type == null) {
            return "";
        }
        if (type.getKind().name().equals("VOID")) {
            return "void";
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return typeName(((ArrayType) type).getComponentType()) + "[]";
        }
        return processingEnv.getTypeUtils().erasure(type).toString();
    }

    private static String parentPackage(String packageName) {
        int lastDot = packageName == null ? -1 : packageName.lastIndexOf('.');
        return lastDot < 0 ? null : packageName.substring(0, lastDot);
    }

    private record HandlerSpec(MessageType messageType, boolean defaultPassive, boolean defaultSkipExpiredRequests,
                               boolean web, List<String> webMethods, boolean defaultAutoHead,
                               boolean defaultAutoOptions) {
    }

    private record HandlerMatch(AnnotationDescriptor annotation, HandlerSpec spec) {
    }

    private record LocalHandlerConfig(boolean enabled, boolean allowExternalMessages) {
    }

    private record GeneratedInvocationTarget(
            String componentName,
            String sourceTypeName,
            List<GeneratedInvocationExecutable> executables) {
    }

    private record GeneratedInvocationExecutable(
            ExecutableKind kind,
            String name,
            String executableId,
            boolean returnsVoid,
            boolean isStatic,
            List<String> parameterExpressions) {
    }

    private record PackageMetadata(LocalHandlerConfig localHandler, ConsumerDescriptor consumer, boolean trackSelf) {
        private static PackageMetadata empty() {
            return new PackageMetadata(null, null, false);
        }
    }
}
