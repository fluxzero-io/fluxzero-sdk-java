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

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auto.service.AutoService;
import io.fluxzero.common.serialization.JsonUtils;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
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
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.fluxzero.sdk.web.HttpRequestMethod.DELETE;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.HEAD;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;
import static io.fluxzero.sdk.web.HttpRequestMethod.PATCH;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static io.fluxzero.sdk.web.HttpRequestMethod.PUT;
import static io.fluxzero.sdk.web.HttpRequestMethod.TRACE;
import static javax.tools.StandardLocation.CLASS_OUTPUT;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Generates an OpenAPI document from Fluxzero web handler annotations during Java compilation.
 * <p>
 * The processor is intentionally lightweight: it only inspects source elements in the current javac round, never scans
 * the runtime classpath, and writes a single resource when at least one documented HTTP endpoint is found.
 * </p>
 *
 * <h2>Compiler Options</h2>
 * <ul>
 *     <li>{@value #ENABLED_OPTION}: set to {@code false} to disable generation.</li>
 *     <li>{@value #OUTPUT_OPTION}: generated resource path, defaults to {@value #DEFAULT_OUTPUT}.</li>
 *     <li>{@value #OPENAPI_VERSION_OPTION}: OpenAPI document version, defaults to {@code 3.0.1}.</li>
 *     <li>{@value #TITLE_OPTION}: OpenAPI {@code info.title}, defaults to {@code Fluxzero API}.</li>
 *     <li>{@value #VERSION_OPTION}: OpenAPI {@code info.version}, defaults to {@code 0.0.0}.</li>
 *     <li>{@value #DESCRIPTION_OPTION}: optional OpenAPI {@code info.description}.</li>
 *     <li>{@value #SERVERS_OPTION}: comma-separated server URLs.</li>
 * </ul>
 *
 * @see ApiDoc
 * @see ApiDocInfo
 * @see ApiDocComponent
 * @see ApiDocResponse
 * @see ApiDocExclude
 * @see OpenApiRenderer
 */
@SupportedAnnotationTypes({
        OpenApiProcessor.API_DOC,
        OpenApiProcessor.API_DOC_INFO,
        OpenApiProcessor.API_DOC_RESPONSE,
        OpenApiProcessor.API_DOC_RESPONSES,
        OpenApiProcessor.API_DOC_EXCLUDE,
        OpenApiProcessor.PATH,
        OpenApiProcessor.HANDLE_WEB,
        OpenApiProcessor.HANDLE_GET,
        OpenApiProcessor.HANDLE_POST,
        OpenApiProcessor.HANDLE_PUT,
        OpenApiProcessor.HANDLE_PATCH,
        OpenApiProcessor.HANDLE_DELETE,
        OpenApiProcessor.HANDLE_HEAD,
        OpenApiProcessor.HANDLE_OPTIONS,
        OpenApiProcessor.HANDLE_TRACE,
        OpenApiProcessor.HANDLE_SOCKET_HANDSHAKE,
        OpenApiProcessor.HANDLE_SOCKET_MESSAGE,
        OpenApiProcessor.HANDLE_SOCKET_OPEN,
        OpenApiProcessor.HANDLE_SOCKET_CLOSE,
        OpenApiProcessor.HANDLE_SOCKET_PONG,
        OpenApiProcessor.QUERY_PARAM,
        OpenApiProcessor.PATH_PARAM,
        OpenApiProcessor.HEADER_PARAM,
        OpenApiProcessor.COOKIE_PARAM,
        OpenApiProcessor.FORM_PARAM,
        OpenApiProcessor.BODY_PARAM,
        OpenApiProcessor.SWAGGER_SCHEMA,
        OpenApiProcessor.SWAGGER_ARRAY_SCHEMA,
        OpenApiProcessor.SWAGGER_HIDDEN
})
@SupportedOptions({
        OpenApiProcessor.ENABLED_OPTION,
        OpenApiProcessor.OUTPUT_OPTION,
        OpenApiProcessor.OPENAPI_VERSION_OPTION,
        OpenApiProcessor.TITLE_OPTION,
        OpenApiProcessor.VERSION_OPTION,
        OpenApiProcessor.DESCRIPTION_OPTION,
        OpenApiProcessor.SERVERS_OPTION,
        OpenApiProcessor.GRADLE_AGGREGATING_OPTION
})
@AutoService(Processor.class)
public class OpenApiProcessor extends AbstractProcessor {
    public static final String DEFAULT_OUTPUT = "META-INF/fluxzero/openapi.json";
    public static final String ENABLED_OPTION = "fluxzero.openapi.enabled";
    public static final String OUTPUT_OPTION = "fluxzero.openapi.output";
    public static final String OPENAPI_VERSION_OPTION = "fluxzero.openapi.specVersion";
    public static final String TITLE_OPTION = "fluxzero.openapi.title";
    public static final String VERSION_OPTION = "fluxzero.openapi.version";
    public static final String DESCRIPTION_OPTION = "fluxzero.openapi.description";
    public static final String SERVERS_OPTION = "fluxzero.openapi.servers";
    static final String GRADLE_AGGREGATING_OPTION = "org.gradle.annotation.processing.aggregating";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> OPENAPI_METHODS =
            Set.of(GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE);

    static final String HANDLE_WEB = "io.fluxzero.sdk.web.HandleWeb";
    static final String HANDLE_GET = "io.fluxzero.sdk.web.HandleGet";
    static final String HANDLE_POST = "io.fluxzero.sdk.web.HandlePost";
    static final String HANDLE_PUT = "io.fluxzero.sdk.web.HandlePut";
    static final String HANDLE_PATCH = "io.fluxzero.sdk.web.HandlePatch";
    static final String HANDLE_DELETE = "io.fluxzero.sdk.web.HandleDelete";
    static final String HANDLE_HEAD = "io.fluxzero.sdk.web.HandleHead";
    static final String HANDLE_OPTIONS = "io.fluxzero.sdk.web.HandleOptions";
    static final String HANDLE_TRACE = "io.fluxzero.sdk.web.HandleTrace";
    static final String HANDLE_SOCKET_HANDSHAKE = "io.fluxzero.sdk.web.HandleSocketHandshake";
    static final String HANDLE_SOCKET_MESSAGE = "io.fluxzero.sdk.web.HandleSocketMessage";
    static final String HANDLE_SOCKET_OPEN = "io.fluxzero.sdk.web.HandleSocketOpen";
    static final String HANDLE_SOCKET_CLOSE = "io.fluxzero.sdk.web.HandleSocketClose";
    static final String HANDLE_SOCKET_PONG = "io.fluxzero.sdk.web.HandleSocketPong";
    static final String WEB_PARAM = "io.fluxzero.sdk.web.WebParam";
    static final String QUERY_PARAM = "io.fluxzero.sdk.web.QueryParam";
    static final String PATH_PARAM = "io.fluxzero.sdk.web.PathParam";
    static final String HEADER_PARAM = "io.fluxzero.sdk.web.HeaderParam";
    static final String COOKIE_PARAM = "io.fluxzero.sdk.web.CookieParam";
    static final String FORM_PARAM = "io.fluxzero.sdk.web.FormParam";
    static final String BODY_PARAM = "io.fluxzero.sdk.web.BodyParam";
    static final String PATH = "io.fluxzero.sdk.web.Path";
    static final String API_DOC = "io.fluxzero.sdk.web.ApiDoc";
    static final String API_DOC_INFO = "io.fluxzero.sdk.web.ApiDocInfo";
    static final String API_DOC_RESPONSE = "io.fluxzero.sdk.web.ApiDocResponse";
    static final String API_DOC_RESPONSES = "io.fluxzero.sdk.web.ApiDocResponses";
    static final String API_DOC_EXCLUDE = "io.fluxzero.sdk.web.ApiDocExclude";
    static final String SWAGGER_SCHEMA = "io.swagger.v3.oas.annotations.media.Schema";
    static final String SWAGGER_ARRAY_SCHEMA = "io.swagger.v3.oas.annotations.media.ArraySchema";
    static final String SWAGGER_HIDDEN = "io.swagger.v3.oas.annotations.Hidden";
    private static final String JACKSON_JSON_VALUE = "com.fasterxml.jackson.annotation.JsonValue";
    private static final String JACKSON_TYPE_INFO = "com.fasterxml.jackson.annotation.JsonTypeInfo";
    private static final String JACKSON_SUB_TYPES = "com.fasterxml.jackson.annotation.JsonSubTypes";

    private static final List<String> FRAMEWORK_PARAMETER_TYPES = List.of(
            "io.fluxzero.sdk.web.WebRequest",
            "io.fluxzero.sdk.web.WebRequestContext",
            "io.fluxzero.sdk.web.SocketSession",
            "io.fluxzero.common.api.Metadata",
            "io.fluxzero.sdk.common.HasMessage",
            "io.fluxzero.sdk.common.serialization.DeserializingMessage",
            "io.fluxzero.common.api.SerializedMessage",
            "io.fluxzero.sdk.tracking.handling.authentication.User",
            "java.time.Instant",
            "java.time.Clock"
    );

    private final List<Endpoint> endpoints = new ArrayList<>();
    private final Set<String> visitedTypes = new LinkedHashSet<>();

    private Filer filer;
    private Messager messager;
    private Elements elements;
    private Types types;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        elements = processingEnv.getElementUtils();
        types = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!isEnabled()) {
            return false;
        }
        if (roundEnv.processingOver()) {
            writeDocument();
            return false;
        }
        for (Element root : roundEnv.getRootElements()) {
            scan(root);
        }
        return false;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private void scan(Element element) {
        if (element instanceof TypeElement type) {
            scanType(type);
        }
    }

    private void scanType(TypeElement type) {
        String qualifiedName = type.getQualifiedName().toString();
        if (!qualifiedName.isBlank() && !visitedTypes.add(qualifiedName)) {
            return;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                scanType(nested);
            } else if (enclosed instanceof ExecutableElement executable
                       && enclosed.getKind() == ElementKind.METHOD) {
                scanMethod(type, executable);
            }
        }
    }

    private void scanMethod(TypeElement handlerType, ExecutableElement method) {
        List<WebMapping> mappings = webMappings(method);
        if (mappings.isEmpty() || isExcluded(handlerType, method) || !isDocumented(handlerType, method)) {
            return;
        }
        String rootPath = handlerPath(handlerType, method);
        Documentation documentation = documentation(handlerType, method);
        List<Response> responses = responses(handlerType, method);
        for (WebMapping mapping : mappings) {
            if (mapping.disabled()) {
                continue;
            }
            List<String> values = mapping.values().isEmpty() ? List.of("") : mapping.values();
            for (String methodValue : mapping.methods()) {
                for (String value : values) {
                    WebPattern pattern =
                            new WebPattern(WebUtils.concatenateUrlParts(rootPath, value), methodValue,
                                           mapping.autoHead(), mapping.autoOptions());
                    for (WebRouteMatcher.RouteVariant variant :
                            WebRouteMatcher.RouteVariants.expand(pattern.getPath())) {
                        endpoints.add(new Endpoint(
                                handlerType, method, pattern.getOrigin(), variant.path(), pattern.getMethod(),
                                documentation, parameters(method, variant.path()), requestBodies(method),
                                method.getReturnType(), responses));
                    }
                }
            }
        }
    }

    private List<WebMapping> webMappings(ExecutableElement method) {
        List<WebMapping> result = new ArrayList<>();
        for (AnnotationMirror annotation : method.getAnnotationMirrors()) {
            webMapping(annotation).ifPresent(result::add);
        }
        return result;
    }

    private Optional<WebMapping> webMapping(AnnotationMirror annotation) {
        TypeElement annotationType = asTypeElement(annotation.getAnnotationType());
        if (annotationType == null) {
            return Optional.empty();
        }
        Map<String, AnnotationValue> values;
        if (qualifiedName(annotationType).equals(HANDLE_WEB)) {
            values = annotationValues(annotation);
        } else {
            Optional<Map<String, AnnotationValue>> metaValues = metaAnnotationValues(annotationType, HANDLE_WEB);
            if (metaValues.isEmpty()) {
                return Optional.empty();
            }
            values = new LinkedHashMap<>(metaValues.orElseThrow());
            values.putAll(annotationValues(annotation));
        }
        return Optional.of(new WebMapping(
                stringList(values.get("value")),
                stringList(values.get("method"), List.of(HttpRequestMethod.ANY)),
                booleanValue(values.get("disabled")),
                booleanValue(values.get("autoHead"), true),
                booleanValue(values.get("autoOptions"), true)));
    }

    private String handlerPath(TypeElement handlerType, ExecutableElement method) {
        List<String> hierarchy = new ArrayList<>();
        for (PackageElement packageElement : packageHierarchy(handlerType)) {
            pathValue(packageElement, simplePackageName(packageElement)).ifPresent(hierarchy::add);
        }
        pathValue(handlerType, simplePackageName(elements.getPackageOf(handlerType))).ifPresent(hierarchy::add);
        pathValue(method, handlerType.getSimpleName().toString()).ifPresent(hierarchy::add);

        String result = "";
        for (String part : hierarchy) {
            result = WebUtils.isAbsolutePathOrUrl(part) ? part : WebUtils.concatenateUrlParts(result, part);
        }
        return result;
    }

    private List<PackageElement> packageHierarchy(TypeElement type) {
        String packageName = elements.getPackageOf(type).getQualifiedName().toString();
        if (packageName.isBlank()) {
            return List.of();
        }
        List<PackageElement> result = new ArrayList<>();
        String[] parts = packageName.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            String name = String.join(".", Arrays.copyOfRange(parts, 0, i + 1));
            PackageElement packageElement = elements.getPackageElement(name);
            if (packageElement != null) {
                result.add(packageElement);
            }
        }
        return result;
    }

    private Optional<String> pathValue(Element element, String blankValue) {
        AnnotationMirror path = findAnnotation(element, PATH);
        if (path == null) {
            return Optional.empty();
        }
        String value = stringValue(annotationValues(path).get("value"));
        return Optional.of(isBlank(value) ? blankValue : value);
    }

    private List<ParameterInfo> parameters(ExecutableElement method, String path) {
        List<ParameterInfo> parameters = new ArrayList<>();
        Set<String> pathParameterNames = pathParameterNames(path);
        for (String pathParameterName : pathParameterNames) {
            parameters.add(new ParameterInfo(pathParameterName, WebParameterSource.PATH,
                                             elements.getTypeElement(String.class.getName()).asType(), null));
        }
        for (VariableElement parameter : method.getParameters()) {
            webParam(parameter).ifPresent(param -> {
                String name = !isBlank(param.name()) ? param.name() : parameter.getSimpleName().toString();
                if (param.source() != WebParameterSource.PATH || pathParameterNames.contains(name)) {
                    addOrReplace(parameters, new ParameterInfo(name, param.source(), parameter.asType(), parameter));
                }
            });
        }
        return parameters;
    }

    private List<BodyInfo> requestBodies(ExecutableElement method) {
        List<BodyInfo> requestBodies = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            if (webParam(parameter).isEmpty() && !isFrameworkParameter(parameter.asType())) {
                requestBodies.add(new BodyInfo(parameter.getSimpleName().toString(), parameter.asType(), parameter));
            }
        }
        return requestBodies;
    }

    private void addOrReplace(List<ParameterInfo> parameters, ParameterInfo parameter) {
        for (int i = 0; i < parameters.size(); i++) {
            ParameterInfo existing = parameters.get(i);
            if (Objects.equals(existing.name(), parameter.name()) && existing.source() == parameter.source()) {
                parameters.set(i, parameter);
                return;
            }
        }
        parameters.add(parameter);
    }

    private Optional<WebParamInfo> webParam(VariableElement parameter) {
        for (AnnotationMirror annotation : parameter.getAnnotationMirrors()) {
            TypeElement annotationType = asTypeElement(annotation.getAnnotationType());
            if (annotationType == null) {
                continue;
            }
            Optional<Map<String, AnnotationValue>> metaValues = metaAnnotationValues(annotationType, WEB_PARAM);
            if (metaValues.isEmpty()) {
                continue;
            }
            Map<String, AnnotationValue> values = new LinkedHashMap<>(metaValues.orElseThrow());
            values.putAll(annotationValues(annotation));
            return Optional.of(new WebParamInfo(
                    sourceValue(values.get("type")),
                    stringValue(values.get("value"))));
        }
        return Optional.empty();
    }

    private Documentation documentation(TypeElement handlerType, ExecutableElement method) {
        DocumentationBuilder builder = new DocumentationBuilder();
        for (PackageElement packageElement : packageHierarchy(handlerType)) {
            builder.apply(findAnnotation(packageElement, API_DOC));
        }
        builder.apply(findAnnotation(handlerType, API_DOC));
        builder.apply(findAnnotation(method, API_DOC));
        return builder.build();
    }

    private List<Response> responses(TypeElement handlerType, ExecutableElement method) {
        Map<Integer, Response> result = new LinkedHashMap<>();
        for (PackageElement packageElement : packageHierarchy(handlerType)) {
            addResponses(result, packageElement);
        }
        addResponses(result, handlerType);
        addResponses(result, method);
        return new ArrayList<>(result.values());
    }

    private void addResponses(Map<Integer, Response> target, Element element) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            String name = annotationName(annotation);
            if (API_DOC_RESPONSE.equals(name)) {
                Response response = response(annotation);
                target.put(response.status(), response);
            } else if (API_DOC_RESPONSES.equals(name)) {
                for (AnnotationMirror nested : annotationList(annotationValues(annotation).get("value"))) {
                    Response response = response(nested);
                    target.put(response.status(), response);
                }
            }
        }
    }

    private Response response(AnnotationMirror annotation) {
        Map<String, AnnotationValue> values = annotationValues(annotation);
        return new Response(
                intValue(values.get("status")),
                stringValue(values.get("description")),
                stringValue(values.get("ref")),
                typeValue(values.get("type")),
                stringValue(values.get("contentType")));
    }

    private boolean isExcluded(TypeElement handlerType, ExecutableElement method) {
        for (PackageElement packageElement : packageHierarchy(handlerType)) {
            if (findAnnotation(packageElement, API_DOC_EXCLUDE) != null) {
                return true;
            }
        }
        return findAnnotation(handlerType, API_DOC_EXCLUDE) != null
               || findAnnotation(method, API_DOC_EXCLUDE) != null;
    }

    private boolean isDocumented(TypeElement handlerType, ExecutableElement method) {
        for (PackageElement packageElement : packageHierarchy(handlerType)) {
            if (findAnnotation(packageElement, API_DOC) != null) {
                return true;
            }
        }
        return findAnnotation(handlerType, API_DOC) != null || findAnnotation(method, API_DOC) != null;
    }

    private void writeDocument() {
        if (endpoints.stream().noneMatch(endpoint -> openApiMethod(endpoint.httpMethod()) != null)) {
            return;
        }
        try {
            FileObject resource = filer.createResource(CLASS_OUTPUT, "", outputPath(),
                                                       endpoints.stream().map(Endpoint::executable)
                                                               .toArray(Element[]::new));
            try (Writer writer = resource.openWriter()) {
                writer.write(JsonUtils.asPrettyJson(openApi()));
                writer.write("\n");
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                                  "Failed to generate Fluxzero OpenAPI document: " + e.getMessage());
        }
    }

    private ObjectNode openApi() {
        DocumentInfo documentInfo = documentInfo();
        SchemaContext schemaContext = new SchemaContext(documentInfo.openApiVersion());
        ObjectNode document = object();
        document.put("openapi", documentInfo.openApiVersion());
        document.set("info", info(documentInfo));
        documentInfo.extensions().forEach(document::set);
        if (!documentInfo.servers().isEmpty()) {
            ArrayNode serverNodes = document.putArray("servers");
            documentInfo.servers().forEach(server -> {
                ObjectNode node = object().put("url", server.url());
                if (!isBlank(server.description())) {
                    node.put("description", server.description());
                }
                serverNodes.add(node);
            });
        }
        if (!documentInfo.security().isEmpty()) {
            document.set("security", security(documentInfo.security()));
        }
        ObjectNode paths = document.putObject("paths");
        Map<String, Integer> operationIds = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            String method = openApiMethod(endpoint.httpMethod());
            if (method == null) {
                continue;
            }
            ObjectNode path = paths.has(endpoint.path())
                    ? (ObjectNode) paths.get(endpoint.path()) : paths.putObject(endpoint.path());
            path.set(method, operation(endpoint, uniqueOperationId(endpoint, operationIds), schemaContext));
        }
        if (!schemaContext.schemas.isEmpty() || !documentInfo.components().isEmpty()) {
            ObjectNode components = document.putObject("components");
            if (!schemaContext.schemas.isEmpty()) {
                components.set("schemas", schemaContext.schemasNode());
            }
            documentInfo.components().forEach((path, value) -> setComponent(components, path, value.deepCopy()));
        }
        return document;
    }

    private void setComponent(ObjectNode components, String path, JsonNode value) {
        if (isBlank(path)) {
            return;
        }
        String[] parts = path.split("\\.");
        ObjectNode target = components;
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isBlank()) {
                return;
            }
            target = target.withObject("/" + parts[i]);
        }
        String name = parts[parts.length - 1];
        if (!name.isBlank()) {
            target.set(name, value);
        }
    }

    private DocumentInfo documentInfo() {
        DocumentInfoBuilder builder = new DocumentInfoBuilder();
        Set<Element> seen = new LinkedHashSet<>();
        for (Endpoint endpoint : endpoints) {
            for (PackageElement packageElement : packageHierarchy(endpoint.handlerType())) {
                if (seen.add(packageElement)) {
                    builder.apply(findAnnotation(packageElement, API_DOC_INFO));
                }
            }
            if (seen.add(endpoint.handlerType())) {
                builder.apply(findAnnotation(endpoint.handlerType(), API_DOC_INFO));
            }
        }
        option(OPENAPI_VERSION_OPTION).ifPresent(builder::openApiVersion);
        option(TITLE_OPTION).ifPresent(builder::title);
        option(VERSION_OPTION).ifPresent(builder::version);
        option(DESCRIPTION_OPTION).ifPresent(builder::description);
        List<String> servers = optionList(SERVERS_OPTION);
        if (!servers.isEmpty()) {
            builder.replaceServers(servers);
        }
        return builder.build();
    }

    private ObjectNode info(DocumentInfo documentInfo) {
        ObjectNode info = object();
        info.put("title", documentInfo.title());
        info.put("version", documentInfo.version());
        if (!isBlank(documentInfo.description())) {
            info.put("description", documentInfo.description());
        }
        if (!isBlank(documentInfo.termsOfService())) {
            info.put("termsOfService", documentInfo.termsOfService());
        }
        if (!isBlank(documentInfo.contactName()) || !isBlank(documentInfo.contactUrl())
            || !isBlank(documentInfo.contactEmail())) {
            ObjectNode contact = info.putObject("contact");
            if (!isBlank(documentInfo.contactName())) {
                contact.put("name", documentInfo.contactName());
            }
            if (!isBlank(documentInfo.contactUrl())) {
                contact.put("url", documentInfo.contactUrl());
            }
            if (!isBlank(documentInfo.contactEmail())) {
                contact.put("email", documentInfo.contactEmail());
            }
        }
        if (!isBlank(documentInfo.licenseName()) || !isBlank(documentInfo.licenseUrl())) {
            ObjectNode license = info.putObject("license");
            if (!isBlank(documentInfo.licenseName())) {
                license.put("name", documentInfo.licenseName());
            }
            if (!isBlank(documentInfo.licenseUrl())) {
                license.put("url", documentInfo.licenseUrl());
            }
        }
        if (!isBlank(documentInfo.logoUrl()) || !isBlank(documentInfo.logoAltText())) {
            ObjectNode logo = info.putObject("x-logo");
            if (!isBlank(documentInfo.logoUrl())) {
                logo.put("url", documentInfo.logoUrl());
            }
            if (!isBlank(documentInfo.logoAltText())) {
                logo.put("altText", documentInfo.logoAltText());
            }
        }
        return info;
    }

    private ObjectNode operation(Endpoint endpoint, String operationId, SchemaContext schemaContext) {
        ObjectNode operation = object();
        Documentation doc = endpoint.documentation();
        if (!isBlank(doc.summary())) {
            operation.put("summary", doc.summary());
        }
        if (!isBlank(doc.description())) {
            operation.put("description", doc.description());
        }
        operation.put("operationId", operationId);
        if (!doc.tags().isEmpty()) {
            ArrayNode tags = operation.putArray("tags");
            doc.tags().forEach(tags::add);
        }
        if (doc.deprecated()) {
            operation.put("deprecated", true);
        }
        if (!doc.security().isEmpty()) {
            operation.set("security", security(doc.security()));
        }
        if (!isBlank(endpoint.origin())) {
            operation.putArray("servers").add(object().put("url", endpoint.origin()));
        }
        ArrayNode parameters = parameters(endpoint, schemaContext);
        if (!parameters.isEmpty()) {
            operation.set("parameters", parameters);
        }
        requestBody(endpoint, schemaContext).ifPresent(body -> operation.set("requestBody", body));
        operation.set("responses", responses(endpoint, schemaContext));
        return operation;
    }

    private ArrayNode parameters(Endpoint endpoint, SchemaContext schemaContext) {
        ArrayNode result = JSON.arrayNode();
        for (ParameterInfo parameter : endpoint.parameters()) {
            if (parameter.source() == WebParameterSource.BODY || parameter.source() == WebParameterSource.FORM
                || isHidden(parameter.element())) {
                continue;
            }
            ObjectNode node = object();
            node.put("name", parameter.name());
            node.put("in", location(parameter.source()));
            if (parameter.source() == WebParameterSource.PATH || isRequired(parameter.element())) {
                node.put("required", true);
            }
            ObjectNode schema = schema(parameter.type(), schemaContext);
            applySchemaMetadata(schema, metadata(parameter.element()), schemaContext);
            removeDeclarationApiDocMetadata(schema, parameter.element());
            node.set("schema", schema);
            applyParameterMetadata(node, parameter.element());
            result.add(node);
        }
        return result;
    }

    private Optional<ObjectNode> requestBody(Endpoint endpoint, SchemaContext schemaContext) {
        List<BodyInfo> requestBodies = endpoint.requestBodies().stream()
                .filter(body -> !isHidden(body.element())).toList();
        if (!requestBodies.isEmpty()) {
            return Optional.of(fullRequestBody(requestBodies, schemaContext));
        }
        List<ParameterInfo> bodyParameters = endpoint.parameters().stream()
                .filter(p -> p.source() == WebParameterSource.BODY && !isHidden(p.element())).toList();
        if (!bodyParameters.isEmpty()) {
            return Optional.of(parameterObjectRequestBody("application/json", bodyParameters, schemaContext));
        }
        List<ParameterInfo> formParameters = endpoint.parameters().stream()
                .filter(p -> p.source() == WebParameterSource.FORM && !isHidden(p.element())).toList();
        if (!formParameters.isEmpty()) {
            String mediaType = formParameters.stream().anyMatch(p -> isBinaryType(p.type()))
                    ? "multipart/form-data" : "application/x-www-form-urlencoded";
            return Optional.of(parameterObjectRequestBody(mediaType, formParameters, schemaContext));
        }
        return Optional.empty();
    }

    private ObjectNode fullRequestBody(List<BodyInfo> requestBodies, SchemaContext schemaContext) {
        if (requestBodies.size() == 1) {
            BodyInfo body = requestBodies.getFirst();
            ObjectNode schema = schema(body.type(), schemaContext);
            applySchemaMetadata(schema, metadata(body.element()), schemaContext);
            return requestBody(inferMediaType(body.type()), schema);
        }
        ObjectNode schema = object().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = JSON.arrayNode();
        for (BodyInfo body : requestBodies) {
            ObjectNode property = schema(body.type(), schemaContext);
            applySchemaMetadata(property, metadata(body.element()), schemaContext);
            properties.set(body.name(), property);
            if (isRequired(body.element())) {
                required.add(body.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return requestBody("application/json", schema);
    }

    private ObjectNode parameterObjectRequestBody(String mediaType, List<ParameterInfo> parameters,
                                                  SchemaContext schemaContext) {
        ObjectNode schema = object().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = JSON.arrayNode();
        for (ParameterInfo parameter : parameters) {
            ObjectNode property = schema(parameter.type(), schemaContext);
            applySchemaMetadata(property, metadata(parameter.element()), schemaContext);
            properties.set(parameter.name(), property);
            if (isRequired(parameter.element())) {
                required.add(parameter.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return requestBody(mediaType, schema);
    }

    private ObjectNode requestBody(String mediaType, ObjectNode schema) {
        ObjectNode requestBody = object();
        ObjectNode content = requestBody.putObject("content");
        content.putObject(mediaType).set("schema", schema);
        return requestBody;
    }

    private ObjectNode responses(Endpoint endpoint, SchemaContext schemaContext) {
        ObjectNode responses = object();
        Optional<RenderedResponse> defaultResponse = defaultResponse(endpoint, schemaContext);
        defaultResponse.ifPresent(response -> responses.set(response.status(), response.node()));
        for (Response descriptor : endpoint.responses()) {
            String status = String.valueOf(descriptor.status());
            if (!isBlank(descriptor.ref())) {
                responses.set(status, reference(descriptor.ref(), "#/components/responses/"));
                continue;
            }
            ObjectNode base = defaultResponse
                    .filter(response -> response.status().equals(status))
                    .map(response -> response.node().deepCopy())
                    .orElseGet(this::object);
            responses.set(status, response(descriptor, base, schemaContext));
        }
        return responses;
    }

    private Optional<RenderedResponse> defaultResponse(Endpoint endpoint, SchemaContext schemaContext) {
        TypeMirror responseType = endpoint.responseType();
        if (isNoResponseType(responseType)) {
            return Optional.of(new RenderedResponse("204", object().put("description", "No Content")));
        }
        ObjectNode response = object().put("description", "OK");
        if (!isDynamicWebResponse(responseType)) {
            ObjectNode schema = responseSchema(responseType, schemaContext);
            removeDeclarationApiDocMetadata(schema, endpoint.executable());
            addContent(response, inferMediaType(responseType), schema);
        }
        return Optional.of(new RenderedResponse("200", response));
    }

    private ObjectNode response(Response descriptor, ObjectNode response, SchemaContext schemaContext) {
        if (!response.has("description") || !isBlank(descriptor.description())) {
            response.put("description", isBlank(descriptor.description())
                    ? defaultDescription(descriptor.status()) : descriptor.description());
        }
        if (!isNoResponseType(descriptor.type())) {
            addContent(response,
                       isBlank(descriptor.contentType()) ? inferMediaType(descriptor.type())
                               : descriptor.contentType(),
                       responseSchema(descriptor.type(), schemaContext));
        }
        return response;
    }

    private void addContent(ObjectNode target, String mediaType, ObjectNode schema) {
        target.putObject("content").putObject(mediaType).set("schema", schema);
    }

    private ArrayNode security(List<String> requirements) {
        ArrayNode result = JSON.arrayNode();
        for (String requirement : requirements) {
            if (!isBlank(requirement)) {
                ObjectNode node = securityRequirement(requirement);
                if (!node.isEmpty()) {
                    result.add(node);
                }
            }
        }
        return result;
    }

    private ObjectNode securityRequirement(String requirement) {
        ObjectNode result = object();
        int separator = requirement.indexOf('=');
        String name = separator < 0 ? requirement.trim() : requirement.substring(0, separator).trim();
        if (name.isBlank()) {
            return result;
        }
        ArrayNode scopes = result.putArray(name);
        if (separator >= 0) {
            for (String scope : requirement.substring(separator + 1).split(",")) {
                if (!scope.isBlank()) {
                    scopes.add(scope.trim());
                }
            }
        }
        return result;
    }

    private ObjectNode reference(String ref, String defaultPrefix) {
        String value = ref.trim();
        return object().put("$ref", value.startsWith("#/") ? value : defaultPrefix + value);
    }

    private ObjectNode schema(TypeMirror type, SchemaContext schemaContext) {
        return schema(type, new LinkedHashSet<>(), schemaContext, false);
    }

    private ObjectNode responseSchema(TypeMirror type, SchemaContext schemaContext) {
        return schema(type, new LinkedHashSet<>(), schemaContext, true);
    }

    private ObjectNode schema(TypeMirror type, Set<String> visiting, SchemaContext schemaContext,
                              boolean responseSchema) {
        if (type == null) {
            return object().put("type", "object");
        }
        TypeKind kind = type.getKind();
        if (kind == TypeKind.ARRAY) {
            if (isBinaryType(type)) {
                return object().put("type", "string").put("format", "binary");
            }
            ObjectNode schema = object().put("type", "array").set("items",
                                                                  schema(((ArrayType) type).getComponentType(),
                                                                         visiting, schemaContext, responseSchema));
            applySchemaMetadata(schema, metadata(type), schemaContext);
            return schema;
        }
        if (kind.isPrimitive()) {
            ObjectNode schema = primitiveSchema((PrimitiveType) type);
            applySchemaMetadata(schema, metadata(type), schemaContext);
            return schema;
        }
        if (type instanceof TypeVariable || kind == TypeKind.TYPEVAR) {
            return object().put("type", "object");
        }
        if (type instanceof WildcardType wildcardType) {
            TypeMirror extendsBound = wildcardType.getExtendsBound();
            return schema(extendsBound == null ? objectType() : extendsBound, visiting, schemaContext,
                          responseSchema);
        }
        if (!(type instanceof DeclaredType declaredType)) {
            return object().put("type", "object");
        }
        TypeElement typeElement = asTypeElement(declaredType);
        if (typeElement == null) {
            return object().put("type", "object");
        }
        if (isAssignable(type, "java.util.Optional") && !declaredType.getTypeArguments().isEmpty()) {
            ObjectNode schema = nullableSchema(schema(declaredType.getTypeArguments().getFirst(), visiting,
                                                     schemaContext, responseSchema), schemaContext);
            applySchemaMetadata(schema, metadata(type), schemaContext);
            return schema;
        }
        if (isAssignable(type, "java.util.Collection") && !declaredType.getTypeArguments().isEmpty()) {
            ObjectNode schema = object().put("type", "array").set("items",
                                                                  schema(declaredType.getTypeArguments().getFirst(),
                                                                         visiting, schemaContext, responseSchema));
            applySchemaMetadata(schema, metadata(type), schemaContext);
            return schema;
        }
        if (isAssignable(type, "java.util.Map")) {
            ObjectNode node = object().put("type", "object");
            TypeMirror valueType = declaredType.getTypeArguments().size() > 1
                    ? declaredType.getTypeArguments().get(1) : objectType();
            node.set("additionalProperties", schema(valueType, visiting, schemaContext, responseSchema));
            applySchemaMetadata(node, metadata(type), schemaContext);
            return node;
        }
        String qualifiedName = qualifiedName(typeElement);
        ObjectNode knownSchema = knownSchema(qualifiedName, type);
        if (knownSchema != null) {
            applySchemaMetadata(knownSchema, metadata(type), schemaContext);
            return knownSchema;
        }
        if (isBinaryType(type)) {
            return object().put("type", "string").put("format", "binary");
        }
        Optional<ObjectNode> jsonValueSchema = jsonValueSchema(typeElement, visiting, schemaContext, responseSchema);
        if (jsonValueSchema.isPresent()) {
            ObjectNode schema = jsonValueSchema.get();
            applySchemaMetadata(schema, metadata(type), schemaContext);
            return schema;
        }
        if (typeElement.getKind() == ElementKind.ENUM) {
            ObjectNode node = object().put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Element enclosed : typeElement.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                    values.add(enclosed.getSimpleName().toString());
                }
            }
            return node;
        }
        if ("java.lang.Object".equals(qualifiedName) || qualifiedName.startsWith("java.")) {
            return object().put("type", "object");
        }
        if (shouldReference(typeElement, type)) {
            ObjectNode reference = schemaContext.ref(typeElement, visiting, responseSchema);
            applySchemaMetadata(reference, metadata(type), schemaContext);
            return reference;
        }
        String signature = type.toString();
        if (!visiting.add(signature)) {
            return object().put("type", "object");
        }
        try {
            return objectSchema(typeElement, visiting, schemaContext, responseSchema);
        } finally {
            visiting.remove(signature);
        }
    }

    private ObjectNode primitiveSchema(PrimitiveType type) {
        return switch (type.getKind()) {
            case BOOLEAN -> object().put("type", "boolean");
            case BYTE, SHORT, INT, CHAR -> object().put("type", "integer").put("format", "int32");
            case LONG -> object().put("type", "integer").put("format", "int64");
            case FLOAT -> object().put("type", "number").put("format", "float");
            case DOUBLE -> object().put("type", "number").put("format", "double");
            default -> object().put("type", "object");
        };
    }

    private ObjectNode knownSchema(String qualifiedName, TypeMirror type) {
        return switch (qualifiedName) {
            case "java.lang.String", "java.lang.Character" -> object().put("type", "string");
            case "java.util.UUID" -> object().put("type", "string").put("format", "uuid");
            case "java.net.URI", "java.net.URL" -> object().put("type", "string").put("format", "uri");
            case "java.time.LocalDate" -> object().put("type", "string").put("format", "date");
            case "java.time.LocalTime" -> object().put("type", "string").put("format", "partial-time");
            case "java.time.ZoneId" -> object().put("type", "string").put("format", "IANA timezone");
            case "java.util.Date", "java.time.Instant", "java.time.LocalDateTime", "java.time.OffsetDateTime",
                    "java.time.ZonedDateTime" -> object().put("type", "string").put("format", "date-time");
            case "java.lang.Boolean" -> object().put("type", "boolean");
            case "java.lang.Byte", "java.lang.Short", "java.lang.Integer" -> object().put("type", "integer")
                    .put("format", "int32");
            case "java.lang.Long", "java.math.BigInteger" -> object().put("type", "integer")
                    .put("format", "int64");
            case "java.lang.Float" -> object().put("type", "number").put("format", "float");
            case "java.lang.Double" -> object().put("type", "number").put("format", "double");
            case "java.math.BigDecimal" -> object().put("type", "number");
            default -> {
                if (isAssignable(type, "java.lang.CharSequence")) {
                    yield object().put("type", "string");
                }
                if (isAssignable(type, "java.lang.Number")) {
                    yield object().put("type", "number");
                }
                yield null;
            }
        };
    }

    private ObjectNode objectSchema(TypeElement type, Set<String> visiting, SchemaContext schemaContext,
                                    boolean responseSchema) {
        ObjectNode node = object().put("type", "object");
        applySchemaMetadata(node, metadata(type), schemaContext);
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = JSON.arrayNode();
        if (isRecord(type)) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (isRecordComponent(enclosed)) {
                    if (isHidden(enclosed)) {
                        continue;
                    }
                    ObjectNode property = schema(implementationType(enclosed).orElse(enclosed.asType()), visiting,
                                                 schemaContext, responseSchema);
                    applySchemaMetadata(property, metadata(enclosed), schemaContext);
                    properties.set(enclosed.getSimpleName().toString(), property);
                    if (isRequired(enclosed) || responseSchema && isRequiredArrayProperty(property)) {
                        required.add(enclosed.getSimpleName().toString());
                    }
                }
            }
            addRequired(node, required);
            addPolymorphism(node, type, visiting, schemaContext, responseSchema);
            return node;
        }
        TypeElement superType = asTypeElement(type.getSuperclass());
        boolean inheritedSchema = superType != null && !"java.lang.Object".equals(qualifiedName(superType))
                                  && shouldReference(superType, superType.asType());
        if (inheritedSchema) {
            node.remove("properties");
        }
        ObjectNode propertySchema = inheritedSchema ? object().put("type", "object") : node;
        properties = inheritedSchema ? propertySchema.putObject("properties") : properties;
        for (VariableElement field : inheritedSchema ? declaredFields(type) : fields(type)) {
            if (field.getSimpleName().toString().contains("$")
                || field.getModifiers().contains(Modifier.STATIC)
                || field.getModifiers().contains(Modifier.TRANSIENT)
                || isHidden(field)) {
                continue;
            }
            ObjectNode property = schema(implementationType(field).orElse(field.asType()), visiting, schemaContext,
                                         responseSchema);
            applySchemaMetadata(property, metadata(field), schemaContext);
            properties.set(field.getSimpleName().toString(), property);
            if (isRequired(field) || responseSchema && isRequiredArrayProperty(property)) {
                required.add(field.getSimpleName().toString());
            }
        }
        for (ExecutableElement method : inheritedSchema ? declaredMethods(type) : methods(type)) {
            Optional<String> propertyName = beanPropertyName(method);
            if (propertyName.isEmpty() || properties.has(propertyName.get()) || !isDocumentedAccessor(method)) {
                continue;
            }
            ObjectNode property = schema(implementationType(method).orElse(method.getReturnType()), visiting,
                                         schemaContext, responseSchema);
            applySchemaMetadata(property, metadata(method), schemaContext);
            properties.set(propertyName.get(), property);
            if (isRequired(method) || responseSchema && isRequiredArrayProperty(property)) {
                required.add(propertyName.get());
            }
        }
        addRequired(propertySchema, required);
        if (inheritedSchema) {
            ArrayNode allOf = node.putArray("allOf");
            allOf.add(schemaContext.ref(superType, visiting, responseSchema));
            if (!properties.isEmpty() || propertySchema.has("required")) {
                allOf.add(propertySchema);
            }
        }
        addPolymorphism(node, type, visiting, schemaContext, responseSchema);
        return node;
    }

    private Optional<ObjectNode> jsonValueSchema(TypeElement type, Set<String> visiting, SchemaContext schemaContext,
                                                 boolean responseSchema) {
        for (VariableElement field : fields(type)) {
            if (field.getModifiers().contains(Modifier.STATIC) || !isJsonValue(field)) {
                continue;
            }
            ObjectNode schema = schema(implementationType(field).orElse(field.asType()), visiting, schemaContext,
                                       responseSchema);
            applySchemaMetadata(schema, metadata(field), schemaContext);
            return Optional.of(schema);
        }
        for (ExecutableElement method : methods(type)) {
            if (method.getParameters().isEmpty()
                && !method.getModifiers().contains(Modifier.STATIC)
                && method.getReturnType().getKind() != TypeKind.VOID
                && isJsonValue(method)) {
                ObjectNode schema = schema(implementationType(method).orElse(method.getReturnType()), visiting,
                                           schemaContext, responseSchema);
                applySchemaMetadata(schema, metadata(method), schemaContext);
                return Optional.of(schema);
            }
        }
        return Optional.empty();
    }

    private boolean shouldReference(TypeElement typeElement, TypeMirror type) {
        String qualifiedName = qualifiedName(typeElement);
        return !type.getKind().isPrimitive()
               && type.getKind() != TypeKind.ARRAY
               && typeElement.getKind() != ElementKind.ENUM
               && !"java.lang.Object".equals(qualifiedName)
               && !qualifiedName.startsWith("java.")
               && !isBinaryType(type);
    }

    private List<VariableElement> fields(TypeElement type) {
        List<VariableElement> result = new ArrayList<>();
        TypeMirror superclass = type.getSuperclass();
        TypeElement superType = asTypeElement(superclass);
        if (superType != null && !"java.lang.Object".equals(qualifiedName(superType))) {
            result.addAll(fields(superType));
        }
        result.addAll(declaredFields(type));
        return result;
    }

    private List<VariableElement> declaredFields(TypeElement type) {
        List<VariableElement> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof VariableElement field && enclosed.getKind() == ElementKind.FIELD) {
                result.add(field);
            }
        }
        return result;
    }

    private List<ExecutableElement> methods(TypeElement type) {
        List<ExecutableElement> result = new ArrayList<>();
        TypeMirror superclass = type.getSuperclass();
        TypeElement superType = asTypeElement(superclass);
        if (superType != null && !"java.lang.Object".equals(qualifiedName(superType))) {
            result.addAll(methods(superType));
        }
        result.addAll(declaredMethods(type));
        return result;
    }

    private List<ExecutableElement> declaredMethods(TypeElement type) {
        List<ExecutableElement> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method && enclosed.getKind() == ElementKind.METHOD) {
                result.add(method);
            }
        }
        return result;
    }

    private boolean isDocumentedAccessor(ExecutableElement method) {
        return method.getParameters().isEmpty()
               && !method.getModifiers().contains(Modifier.STATIC)
               && method.getReturnType().getKind() != TypeKind.VOID
               && !isHidden(method)
               && (findAnnotation(method, API_DOC) != null
                   || findAnnotation(method, SWAGGER_SCHEMA) != null
                   || findAnnotation(method, SWAGGER_ARRAY_SCHEMA) != null);
    }

    private boolean isJsonValue(Element element) {
        AnnotationMirror jsonValue = findAnnotation(element, JACKSON_JSON_VALUE);
        return jsonValue != null && booleanValue(annotationValues(jsonValue).get("value"), true);
    }

    private Optional<String> beanPropertyName(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        if (name.startsWith("get") && name.length() > 3 && !"getClass".equals(name)) {
            return Optional.of(decapitalize(name.substring(3)));
        }
        if (name.startsWith("is") && name.length() > 2 && isBooleanType(method.getReturnType())) {
            return Optional.of(decapitalize(name.substring(2)));
        }
        return Optional.empty();
    }

    private boolean isBooleanType(TypeMirror type) {
        return type.getKind() == TypeKind.BOOLEAN || "java.lang.Boolean".equals(type.toString());
    }

    private String decapitalize(String value) {
        if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private void addRequired(ObjectNode schema, ArrayNode required) {
        if (!required.isEmpty()) {
            ArrayNode sorted = JSON.arrayNode();
            List<String> names = new ArrayList<>();
            required.forEach(name -> names.add(name.asText()));
            names.stream().sorted().forEach(sorted::add);
            schema.set("required", sorted);
        }
    }

    private boolean isRequiredArrayProperty(ObjectNode property) {
        return "array".equals(schemaType(property)) && !property.path("nullable").asBoolean(false);
    }

    private void addPolymorphism(ObjectNode schema, TypeElement type, Set<String> visiting,
                                 SchemaContext schemaContext, boolean responseSchema) {
        AnnotationMirror subTypes = findAnnotation(type, JACKSON_SUB_TYPES);
        if (subTypes == null) {
            return;
        }
        List<AnnotationMirror> values = annotationList(annotationValues(subTypes).get("value"));
        if (values.isEmpty()) {
            return;
        }
        AnnotationMirror typeInfo = findAnnotation(type, JACKSON_TYPE_INFO);
        String propertyName = typeInfo == null ? "" : stringValue(annotationValues(typeInfo).get("property"));
        ObjectNode mapping = null;
        if (!isBlank(propertyName)) {
            ObjectNode discriminator = schema.putObject("discriminator").put("propertyName", propertyName);
            mapping = discriminator.putObject("mapping");
        }
        ArrayNode oneOf = schema.putArray("oneOf");
        for (AnnotationMirror value : values) {
            TypeMirror subTypeMirror = typeValue(annotationValues(value).get("value"));
            TypeElement subType = asTypeElement(subTypeMirror);
            if (subType == null || types.isSameType(types.erasure(subType.asType()), types.erasure(type.asType()))) {
                continue;
            }
            ObjectNode reference = schemaContext.ref(subType, visiting, responseSchema);
            oneOf.add(reference);
            if (mapping != null) {
                String name = stringValue(annotationValues(value).get("name"));
                if (isBlank(name)) {
                    name = subType.getSimpleName().toString();
                }
                mapping.put(name, reference.path("$ref").asText());
            }
        }
        if (oneOf.isEmpty()) {
            schema.remove("oneOf");
            schema.remove("discriminator");
        }
    }

    private void applyParameterMetadata(ObjectNode node, Element element) {
        SchemaMetadata metadata = metadata(element);
        if (!isBlank(metadata.description())) {
            node.put("description", metadata.description());
        }
        if (metadata.deprecated()) {
            node.put("deprecated", true);
        }
    }

    private void removeDeclarationApiDocMetadata(ObjectNode schema, Element element) {
        if (element == null) {
            return;
        }
        AnnotationMirror apiDoc = findAnnotation(element, API_DOC);
        if (apiDoc == null) {
            return;
        }
        Map<String, AnnotationValue> values = annotationValues(apiDoc);
        String description = stringValue(values.get("description"));
        if (!isBlank(description) && description.trim().equals(schema.path("description").asText("").trim())) {
            schema.remove("description");
        }
        if (booleanValue(values.get("deprecated")) && schema.path("deprecated").asBoolean(false)) {
            schema.remove("deprecated");
        }
    }

    private Optional<TypeMirror> implementationType(Element element) {
        return metadata(element).implementationType();
    }

    private boolean isHidden(Element element) {
        return metadata(element).hidden();
    }

    private boolean isRequired(Element element) {
        return metadata(element).required();
    }

    private void applySchemaMetadata(ObjectNode schema, SchemaMetadata metadata, SchemaContext schemaContext) {
        boolean reference = schema.has("$ref");
        if (reference && !OpenApiOptions.isOpenApi31(schemaContext.openApiVersion())
            && hasReferenceSiblingMetadata(metadata)) {
            wrapReference(schema);
        }
        if (!isBlank(metadata.description())) {
            schema.put("description", metadata.description());
        }
        if (!reference) {
            if (!isBlank(metadata.type())) {
                if (Set.of("date", "date-time", "time", "uuid", "uri", "email", "binary").contains(metadata.type())) {
                    schema.put("type", "string");
                    schema.put("format", metadata.type());
                } else {
                    schema.put("type", metadata.type());
                }
            }
            if (!isBlank(metadata.format())) {
                schema.put("format", metadata.format());
            }
            if (!metadata.allowableValues().isEmpty()) {
                ArrayNode values = schema.putArray("enum");
                metadata.allowableValues().forEach(values::add);
            }
        }
        putSchemaValue(schema, "example", metadata.example());
        putSchemaValue(schema, "default", metadata.defaultValue());
        putDecimal(schema, "minimum", metadata.minimum());
        putDecimal(schema, "maximum", metadata.maximum());
        if (metadata.minSize() != null) {
            schema.put("array".equals(schema.path("type").asText()) ? "minItems" : "minLength",
                       metadata.minSize());
        }
        if (metadata.maxSize() != null) {
            schema.put("array".equals(schema.path("type").asText()) ? "maxItems" : "maxLength",
                       metadata.maxSize());
        }
        if (!isBlank(metadata.pattern())) {
            schema.put("pattern", metadata.pattern());
        }
        if (metadata.deprecated()) {
            schema.put("deprecated", true);
        }
    }

    private boolean hasReferenceSiblingMetadata(SchemaMetadata metadata) {
        return !isBlank(metadata.description()) || !isBlank(metadata.example()) || !isBlank(metadata.defaultValue())
               || !isBlank(metadata.minimum()) || !isBlank(metadata.maximum()) || metadata.minSize() != null
               || metadata.maxSize() != null || !isBlank(metadata.pattern()) || metadata.deprecated();
    }

    private void wrapReference(ObjectNode schema) {
        JsonNode ref = schema.remove("$ref");
        if (ref != null && !schema.has("allOf")) {
            schema.putArray("allOf").add(object().set("$ref", ref));
        }
    }

    private void putDecimal(ObjectNode node, String name, String value) {
        if (isBlank(value)) {
            return;
        }
        try {
            node.put(name, new BigDecimal(value));
        } catch (NumberFormatException ignored) {
            node.put(name, value);
        }
    }

    private void putSchemaValue(ObjectNode schema, String name, String value) {
        if (isBlank(value)) {
            return;
        }
        String trimmed = value.trim();
        try {
            switch (schemaType(schema)) {
                case "boolean" -> {
                    if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                        schema.put(name, Boolean.parseBoolean(trimmed));
                        return;
                    }
                }
                case "integer" -> {
                    schema.put(name, Long.parseLong(trimmed));
                    return;
                }
                case "number" -> {
                    schema.put(name, new BigDecimal(trimmed));
                    return;
                }
                default -> {
                }
            }
        } catch (NumberFormatException ignored) {
            // Fall through to JSON/string handling.
        }
        if (looksLikeJsonValue(trimmed)) {
            try {
                schema.set(name, JsonUtils.fromJson(trimmed, JsonNode.class));
                return;
            } catch (RuntimeException ignored) {
                // Fall through to a string value.
            }
        }
        schema.put(name, value);
    }

    private String schemaType(ObjectNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return "";
        }
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            for (JsonNode item : type) {
                if (item.isTextual() && !"null".equals(item.asText())) {
                    return item.asText();
                }
            }
        }
        return "";
    }

    private boolean looksLikeJsonValue(String value) {
        return value.startsWith("{") || value.startsWith("[") || value.startsWith("\"")
               || "true".equals(value) || "false".equals(value) || "null".equals(value)
               || value.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    private SchemaMetadata metadata(Element element) {
        SchemaMetadataBuilder builder = new SchemaMetadataBuilder();
        if (element == null) {
            return builder.build();
        }
        builder.description(javadocDescription(element));
        AnnotationMirror arraySchema = findAnnotation(element, SWAGGER_ARRAY_SCHEMA);
        if (arraySchema != null) {
            builder.apply(annotationMirror(annotationValues(arraySchema).get("arraySchema")));
        }
        builder.apply(findAnnotation(element, SWAGGER_SCHEMA));
        builder.apply(findAnnotation(element, API_DOC));
        builder.hidden(findAnnotation(element, SWAGGER_HIDDEN) != null
                       || findAnnotation(element, API_DOC_EXCLUDE) != null);
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            builder.applyValidation(annotation);
        }
        return builder.build();
    }

    private String javadocDescription(Element element) {
        if (element == null) {
            return "";
        }
        String docComment = elements.getDocComment(element);
        if (isBlank(docComment)) {
            return "";
        }
        StringBuilder description = new StringBuilder();
        boolean paragraphBreak = false;
        for (String line : docComment.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("@")) {
                break;
            }
            if (trimmed.isBlank()) {
                paragraphBreak = description.length() > 0;
                continue;
            }
            if (!description.isEmpty()) {
                description.append(paragraphBreak ? "\n\n" : " ");
            }
            description.append(trimmed);
            paragraphBreak = false;
        }
        return normalizeInlineJavadoc(description.toString());
    }

    private String normalizeInlineJavadoc(String value) {
        return value.replaceAll("\\{@(?:link|linkplain|code|literal)\\s+([^}]+)}", "$1").trim();
    }

    private SchemaMetadata metadata(TypeMirror type) {
        SchemaMetadataBuilder builder = new SchemaMetadataBuilder();
        if (type == null) {
            return builder.build();
        }
        AnnotationMirror apiDoc = findAnnotation(type.getAnnotationMirrors(), API_DOC);
        builder.apply(findAnnotation(type.getAnnotationMirrors(), SWAGGER_SCHEMA));
        builder.apply(apiDoc);
        builder.hidden(findAnnotation(type.getAnnotationMirrors(), API_DOC_EXCLUDE) != null);
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            builder.applyValidation(annotation);
        }
        return builder.build();
    }

    private AnnotationMirror findAnnotation(List<? extends AnnotationMirror> annotations, String annotationName) {
        for (AnnotationMirror annotation : annotations) {
            if (annotationName.equals(annotationName(annotation))) {
                return annotation;
            }
        }
        return null;
    }

    private AnnotationMirror annotationMirror(AnnotationValue value) {
        return value != null && value.getValue() instanceof AnnotationMirror annotation ? annotation : null;
    }

    private ObjectNode nullableSchema(ObjectNode schema, SchemaContext schemaContext) {
        if (OpenApiOptions.isOpenApi31(schemaContext.openApiVersion()) && schema.has("type")
            && schema.get("type").isTextual()) {
            ArrayNode types = JSON.arrayNode();
            types.add(schema.get("type").asText());
            types.add("null");
            schema.set("type", types);
        } else {
            schema.put("nullable", true);
        }
        return schema;
    }

    private Set<String> pathParameterNames(String path) {
        Set<String> parameters = new LinkedHashSet<>();
        if (isBlank(path)) {
            return parameters;
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) != '{') {
                continue;
            }
            int end = parameterEnd(path, i);
            String value = path.substring(i + 1, end);
            int regexStart = value.indexOf(':');
            String name = regexStart < 0 ? value : value.substring(0, regexStart);
            if (!name.isBlank()) {
                parameters.add(name);
            }
            i = end;
        }
        return parameters;
    }

    private int parameterEnd(String path, int start) {
        int depth = 0;
        for (int i = start; i < path.length(); i++) {
            char current = path.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Route path parameter closing delimiter '}' is missing in: " + path);
    }

    private Optional<Map<String, AnnotationValue>> metaAnnotationValues(TypeElement annotationType,
                                                                        String targetAnnotation) {
        return metaAnnotationValues(annotationType, targetAnnotation, new LinkedHashSet<>());
    }

    private Optional<Map<String, AnnotationValue>> metaAnnotationValues(TypeElement annotationType,
                                                                        String targetAnnotation,
                                                                        Set<String> visitedAnnotationTypes) {
        String qualifiedName = qualifiedName(annotationType);
        if (!qualifiedName.isBlank() && !visitedAnnotationTypes.add(qualifiedName)) {
            return Optional.empty();
        }
        for (AnnotationMirror annotation : annotationType.getAnnotationMirrors()) {
            String annotationName = annotationName(annotation);
            if (targetAnnotation.equals(annotationName)) {
                return Optional.of(annotationValues(annotation));
            }
            if (annotationName.startsWith("java.lang.annotation.")) {
                continue;
            }
            TypeElement nestedType = asTypeElement(annotation.getAnnotationType());
            if (nestedType == null) {
                continue;
            }
            Optional<Map<String, AnnotationValue>> nested =
                    metaAnnotationValues(nestedType, targetAnnotation, visitedAnnotationTypes);
            if (nested.isPresent()) {
                Map<String, AnnotationValue> result = new LinkedHashMap<>(nested.orElseThrow());
                result.putAll(annotationValues(annotation));
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    private AnnotationMirror findAnnotation(Element element, String annotationName) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotationName.equals(annotationName(annotation))) {
                return annotation;
            }
        }
        return null;
    }

    private Map<String, AnnotationValue> annotationValues(AnnotationMirror annotation) {
        Map<String, AnnotationValue> result = new LinkedHashMap<>();
        TypeElement annotationType = asTypeElement(annotation.getAnnotationType());
        if (annotationType == null) {
            return result;
        }
        for (Element enclosed : annotationType.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement attribute) {
                AnnotationValue value = annotation.getElementValues().get(attribute);
                if (value == null) {
                    value = attribute.getDefaultValue();
                }
                if (value != null) {
                    result.put(attribute.getSimpleName().toString(), value);
                }
            }
        }
        return result;
    }

    private List<String> stringList(AnnotationValue value) {
        return stringList(value, List.of());
    }

    private List<String> stringList(AnnotationValue value, List<String> fallback) {
        if (value == null) {
            return fallback;
        }
        Object rawValue = value.getValue();
        if (rawValue instanceof List<?> list) {
            return list.stream().map(v -> stringValue((AnnotationValue) v)).toList();
        }
        return List.of(stringValue(value));
    }

    private String stringValue(AnnotationValue value) {
        if (value == null || value.getValue() == null) {
            return "";
        }
        return String.valueOf(value.getValue());
    }

    private boolean booleanValue(AnnotationValue value) {
        return booleanValue(value, false);
    }

    private boolean booleanValue(AnnotationValue value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value.getValue()));
    }

    private int intValue(AnnotationValue value) {
        return value == null ? 0 : Integer.parseInt(String.valueOf(value.getValue()));
    }

    private Integer integerValue(AnnotationValue value) {
        return value == null ? null : Integer.parseInt(String.valueOf(value.getValue()));
    }

    private TypeMirror typeValue(AnnotationValue value) {
        return value != null && value.getValue() instanceof TypeMirror type ? type : null;
    }

    private List<AnnotationMirror> annotationList(AnnotationValue value) {
        if (value == null || !(value.getValue() instanceof List<?> list)) {
            return List.of();
        }
        List<AnnotationMirror> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof AnnotationValue annotationValue
                && annotationValue.getValue() instanceof AnnotationMirror annotationMirror) {
                result.add(annotationMirror);
            }
        }
        return result;
    }

    private WebParameterSource sourceValue(AnnotationValue value) {
        if (value == null) {
            return WebParameterSource.QUERY;
        }
        Object rawValue = value.getValue();
        String name = rawValue instanceof VariableElement variable
                ? variable.getSimpleName().toString() : String.valueOf(rawValue);
        return WebParameterSource.valueOf(name);
    }

    private boolean isEnabled() {
        return option(ENABLED_OPTION)
                .map(value -> !Set.of("false", "0", "no", "off").contains(value.trim().toLowerCase(Locale.ROOT)))
                .orElse(true);
    }

    private String outputPath() {
        return option(OUTPUT_OPTION).filter(s -> !isBlank(s)).orElse(DEFAULT_OUTPUT);
    }

    private Optional<String> option(String name) {
        return Optional.ofNullable(processingEnv.getOptions().get(name));
    }

    private List<String> optionList(String name) {
        return option(name).stream().flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private boolean isFrameworkParameter(TypeMirror type) {
        return FRAMEWORK_PARAMETER_TYPES.stream().anyMatch(fqn -> isAssignable(type, fqn));
    }

    private boolean isBinaryType(TypeMirror type) {
        if (type == null) {
            return false;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) type).getComponentType().getKind() == TypeKind.BYTE;
        }
        return isAssignable(type, "java.nio.ByteBuffer")
               || isAssignable(type, "java.io.InputStream")
               || isAssignable(type, "io.fluxzero.sdk.web.WebFormPart");
    }

    private boolean isNoResponseType(TypeMirror type) {
        return type == null || type instanceof NoType || type.getKind() == TypeKind.VOID
               || isSameErasure(type, "java.lang.Void");
    }

    private boolean isDynamicWebResponse(TypeMirror type) {
        return isAssignable(type, "io.fluxzero.sdk.web.WebResponse");
    }

    private String inferMediaType(TypeMirror type) {
        if (isBinaryType(type)) {
            return "application/octet-stream";
        }
        if (isSameErasure(type, "java.lang.String") || isAssignable(type, "java.lang.CharSequence")) {
            return "text/plain";
        }
        return "application/json";
    }

    private boolean isAssignable(TypeMirror type, String targetFqn) {
        TypeElement target = elements.getTypeElement(targetFqn);
        if (type == null || target == null || type.getKind().isPrimitive()) {
            return false;
        }
        try {
            return types.isAssignable(types.erasure(type), types.erasure(target.asType()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isSameErasure(TypeMirror type, String targetFqn) {
        TypeElement target = elements.getTypeElement(targetFqn);
        if (type == null || target == null || type.getKind().isPrimitive()) {
            return false;
        }
        return types.isSameType(types.erasure(type), types.erasure(target.asType()));
    }

    private TypeMirror objectType() {
        return elements.getTypeElement(Object.class.getName()).asType();
    }

    private TypeElement asTypeElement(DeclaredType type) {
        return type.asElement() instanceof TypeElement typeElement ? typeElement : null;
    }

    private TypeElement asTypeElement(TypeMirror type) {
        return type instanceof DeclaredType declaredType ? asTypeElement(declaredType) : null;
    }

    private String annotationName(AnnotationMirror annotation) {
        TypeElement annotationType = asTypeElement(annotation.getAnnotationType());
        return annotationType == null ? "" : qualifiedName(annotationType);
    }

    private String qualifiedName(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    private String simplePackageName(PackageElement packageElement) {
        String qualifiedName = packageElement == null ? "" : packageElement.getQualifiedName().toString();
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    private boolean isRecord(TypeElement type) {
        return "RECORD".equals(type.getKind().name());
    }

    private boolean isRecordComponent(Element element) {
        return "RECORD_COMPONENT".equals(element.getKind().name());
    }

    private String location(WebParameterSource source) {
        return switch (source) {
            case PATH -> "path";
            case QUERY -> "query";
            case HEADER -> "header";
            case COOKIE -> "cookie";
            case BODY, FORM -> throw new IllegalArgumentException(source + " is rendered as requestBody");
        };
    }

    private String openApiMethod(String method) {
        return OPENAPI_METHODS.contains(method) ? method.toLowerCase() : null;
    }

    private String uniqueOperationId(Endpoint endpoint, Map<String, Integer> operationIds) {
        String base = !isBlank(endpoint.documentation().operationId())
                ? endpoint.documentation().operationId() : endpoint.executable().getSimpleName().toString();
        int count = operationIds.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + count;
    }

    private String defaultDescription(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 500 -> "Internal Server Error";
            default -> "Response";
        };
    }

    private ObjectNode object() {
        return JSON.objectNode();
    }

    private record WebMapping(
            List<String> values,
            List<String> methods,
            boolean disabled,
            boolean autoHead,
            boolean autoOptions
    ) {
    }

    private record WebParamInfo(WebParameterSource source, String name) {
    }

    private record Endpoint(
            TypeElement handlerType,
            ExecutableElement executable,
            String origin,
            String path,
            String httpMethod,
            Documentation documentation,
            List<ParameterInfo> parameters,
            List<BodyInfo> requestBodies,
            TypeMirror responseType,
            List<Response> responses
    ) {
    }

    private record ParameterInfo(String name, WebParameterSource source, TypeMirror type, VariableElement element) {
    }

    private record BodyInfo(String name, TypeMirror type, VariableElement element) {
    }

    private record Response(int status, String description, String ref, TypeMirror type, String contentType) {
    }

    private record Documentation(
            String summary,
            String description,
            String operationId,
            List<String> tags,
            boolean deprecated,
            List<String> security
    ) {
    }

    private record RenderedResponse(String status, ObjectNode node) {
    }

    private record DocumentInfo(
            String openApiVersion,
            String title,
            String version,
            String description,
            String termsOfService,
            String contactName,
            String contactUrl,
            String contactEmail,
            String licenseName,
            String licenseUrl,
            String logoUrl,
            String logoAltText,
            List<ServerInfo> servers,
            List<String> security,
            Map<String, JsonNode> components,
            Map<String, JsonNode> extensions
    ) {
    }

    private record ServerInfo(String url, String description) {
    }

    private final class DocumentInfoBuilder {
        private String openApiVersion = "";
        private String title = "";
        private String version = "";
        private String description = "";
        private String termsOfService = "";
        private String contactName = "";
        private String contactUrl = "";
        private String contactEmail = "";
        private String licenseName = "";
        private String licenseUrl = "";
        private String logoUrl = "";
        private String logoAltText = "";
        private final Map<String, ServerInfo> servers = new LinkedHashMap<>();
        private final List<String> security = new ArrayList<>();
        private final Map<String, JsonNode> components = new LinkedHashMap<>();
        private final Map<String, JsonNode> extensions = new LinkedHashMap<>();

        void apply(AnnotationMirror info) {
            if (info == null) {
                return;
            }
            Map<String, AnnotationValue> values = annotationValues(info);
            openApiVersion(stringValue(values.get("openApiVersion")));
            title(stringValue(values.get("title")));
            version(stringValue(values.get("version")));
            description(stringValue(values.get("description")));
            termsOfService(stringValue(values.get("termsOfService")));
            contactName(stringValue(values.get("contactName")));
            contactUrl(stringValue(values.get("contactUrl")));
            contactEmail(stringValue(values.get("contactEmail")));
            licenseName(stringValue(values.get("licenseName")));
            licenseUrl(stringValue(values.get("licenseUrl")));
            logoUrl(stringValue(values.get("logoUrl")));
            logoAltText(stringValue(values.get("logoAltText")));
            for (AnnotationMirror server : annotationList(values.get("servers"))) {
                Map<String, AnnotationValue> serverValues = annotationValues(server);
                server(stringValue(serverValues.get("url")), stringValue(serverValues.get("description")));
            }
            for (AnnotationMirror component : annotationList(values.get("components"))) {
                Map<String, AnnotationValue> componentValues = annotationValues(component);
                component(stringValue(componentValues.get("path")), stringValue(componentValues.get("json")));
            }
            for (String requirement : stringList(values.get("security"))) {
                if (!isBlank(requirement) && !security.contains(requirement)) {
                    security.add(requirement);
                }
            }
            stringList(values.get("extensions")).forEach(this::extension);
        }

        void replaceServers(List<String> urls) {
            servers.clear();
            urls.forEach(url -> server(url, ""));
        }

        DocumentInfo build() {
            return new DocumentInfo(
                    isBlank(openApiVersion) ? OpenApiOptions.DEFAULT_OPENAPI_VERSION : openApiVersion,
                    isBlank(title) ? "Fluxzero API" : title,
                    isBlank(version) ? "0.0.0" : version,
                    description,
                    termsOfService,
                    contactName,
                    contactUrl,
                    contactEmail,
                    licenseName,
                    licenseUrl,
                    logoUrl,
                    logoAltText,
                    List.copyOf(servers.values()),
                    List.copyOf(security),
                    new LinkedHashMap<>(components),
                    new LinkedHashMap<>(extensions));
        }

        void openApiVersion(String value) {
            if (!isBlank(value)) {
                openApiVersion = value;
            }
        }

        void title(String value) {
            if (!isBlank(value)) {
                title = value;
            }
        }

        void version(String value) {
            if (!isBlank(value)) {
                version = value;
            }
        }

        void description(String value) {
            if (!isBlank(value)) {
                description = value;
            }
        }

        private void termsOfService(String value) {
            if (!isBlank(value)) {
                termsOfService = value;
            }
        }

        private void contactName(String value) {
            if (!isBlank(value)) {
                contactName = value;
            }
        }

        private void contactUrl(String value) {
            if (!isBlank(value)) {
                contactUrl = value;
            }
        }

        private void contactEmail(String value) {
            if (!isBlank(value)) {
                contactEmail = value;
            }
        }

        private void licenseName(String value) {
            if (!isBlank(value)) {
                licenseName = value;
            }
        }

        private void licenseUrl(String value) {
            if (!isBlank(value)) {
                licenseUrl = value;
            }
        }

        private void logoUrl(String value) {
            if (!isBlank(value)) {
                logoUrl = value;
            }
        }

        private void logoAltText(String value) {
            if (!isBlank(value)) {
                logoAltText = value;
            }
        }

        private void server(String url, String description) {
            if (!isBlank(url)) {
                servers.put(url, new ServerInfo(url, isBlank(description) ? "" : description));
            }
        }

        private void component(String path, String json) {
            if (isBlank(path) || isBlank(json)) {
                return;
            }
            try {
                components.put(path.trim(), JsonUtils.fromJson(json, JsonNode.class));
            } catch (RuntimeException ignored) {
                components.put(path.trim(), JSON.textNode(json));
            }
        }

        private void extension(String extension) {
            if (isBlank(extension)) {
                return;
            }
            int separator = extension.indexOf('=');
            if (separator <= 0) {
                return;
            }
            String name = extension.substring(0, separator).trim();
            String value = extension.substring(separator + 1).trim();
            if (name.isBlank()) {
                return;
            }
            try {
                extensions.put(name, JsonUtils.fromJson(value, JsonNode.class));
            } catch (RuntimeException ignored) {
                extensions.put(name, JSON.textNode(value));
            }
        }
    }

    private final class SchemaContext {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, ObjectNode> schemas = new LinkedHashMap<>();
        private final Set<String> responseSchemas = new LinkedHashSet<>();
        private final String openApiVersion;

        SchemaContext(String openApiVersion) {
            this.openApiVersion = openApiVersion;
        }

        ObjectNode ref(TypeElement type, Set<String> visiting, boolean responseSchema) {
            String qualifiedName = qualifiedName(type);
            String name = name(type);
            if (!schemas.containsKey(name) || responseSchema && !responseSchemas.contains(name)) {
                ObjectNode placeholder = schemas.computeIfAbsent(name, ignored -> object());
                if (responseSchema) {
                    responseSchemas.add(name);
                }
                placeholder.removeAll();
                placeholder.setAll(objectSchema(type, visiting, this, responseSchema));
            }
            names.putIfAbsent(qualifiedName, name);
            return object().put("$ref", "#/components/schemas/" + name);
        }

        ObjectNode schemasNode() {
            ObjectNode node = object();
            schemas.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> node.set(entry.getKey(), entry.getValue()));
            return node;
        }

        String openApiVersion() {
            return openApiVersion;
        }

        private String name(TypeElement type) {
            return names.computeIfAbsent(qualifiedName(type), ignored -> uniqueName(type));
        }

        private String uniqueName(TypeElement type) {
            String base = type.getSimpleName().toString().replaceAll("[^A-Za-z0-9._-]", "");
            if (base.isBlank()) {
                base = "Schema";
            }
            String candidate = base;
            int index = 2;
            while (names.containsValue(candidate)) {
                candidate = base + index++;
            }
            return candidate;
        }
    }

    private record SchemaMetadata(
            String description,
            String type,
            String format,
            String example,
            String defaultValue,
            String minimum,
            String maximum,
            Integer minSize,
            Integer maxSize,
            String pattern,
            List<String> allowableValues,
            boolean required,
            boolean deprecated,
            boolean hidden,
            TypeMirror implementation
    ) {
        Optional<TypeMirror> implementationType() {
            return Optional.ofNullable(implementation);
        }

    }

    private final class SchemaMetadataBuilder {
        private String description = "";
        private String type = "";
        private String format = "";
        private String example = "";
        private String defaultValue = "";
        private String minimum = "";
        private String maximum = "";
        private Integer minSize;
        private Integer maxSize;
        private String pattern = "";
        private final List<String> allowableValues = new ArrayList<>();
        private boolean required;
        private boolean deprecated;
        private boolean hidden;
        private TypeMirror implementation;

        void apply(AnnotationMirror annotation) {
            if (annotation == null) {
                return;
            }
            if (API_DOC.equals(annotationName(annotation))) {
                Map<String, AnnotationValue> values = annotationValues(annotation);
                description(stringValue(values.get("description")));
                type(stringValue(values.get("type")));
                format(stringValue(values.get("format")));
                example(stringValue(values.get("example")));
                defaultValue(stringValue(values.get("defaultValue")));
                minimum(stringValue(values.get("minimum")));
                maximum(stringValue(values.get("maximum")));
                allowableValues.addAll(stringList(values.get("allowableValues")).stream()
                                               .filter(value -> !isBlank(value)).toList());
                required(booleanValue(values.get("required")));
                deprecated(booleanValue(values.get("deprecated")));
                TypeMirror implementationValue = typeValue(values.get("implementation"));
                if (implementationValue != null && !isNoResponseType(implementationValue)) {
                    implementation = implementationValue;
                }
                return;
            }
            Map<String, AnnotationValue> values = annotationValues(annotation);
            description(stringValue(values.get("description")));
            type(stringValue(values.get("type")));
            format(stringValue(values.get("format")));
            example(stringValue(values.get("example")));
            defaultValue(stringValue(values.get("defaultValue")));
            minimum(stringValue(values.get("minimum")));
            maximum(stringValue(values.get("maximum")));
            allowableValues.addAll(stringList(values.get("allowableValues")).stream()
                                           .filter(value -> !isBlank(value)).toList());
            String requiredMode = stringValue(values.get("requiredMode"));
            required(requiredMode.endsWith("REQUIRED") || booleanValue(values.get("required")));
            deprecated(booleanValue(values.get("deprecated")));
            hidden(booleanValue(values.get("hidden")));
            TypeMirror implementationValue = typeValue(values.get("implementation"));
            if (implementationValue != null && !isNoResponseType(implementationValue)) {
                implementation = implementationValue;
            }
        }

        void applyValidation(AnnotationMirror annotation) {
            String name = annotationName(annotation);
            Map<String, AnnotationValue> values = annotationValues(annotation);
            switch (name) {
                case "jakarta.validation.constraints.NotNull",
                        "jakarta.validation.constraints.NotBlank",
                        "jakarta.validation.constraints.NotEmpty" -> required(true);
                case "jakarta.validation.constraints.Min" -> minimum(stringValue(values.get("value")));
                case "jakarta.validation.constraints.Max" -> maximum(stringValue(values.get("value")));
                case "jakarta.validation.constraints.DecimalMin" -> minimum(stringValue(values.get("value")));
                case "jakarta.validation.constraints.DecimalMax" -> maximum(stringValue(values.get("value")));
                case "jakarta.validation.constraints.Positive",
                        "jakarta.validation.constraints.PositiveOrZero" -> minimum("0");
                case "jakarta.validation.constraints.Size" -> {
                    Integer min = integerValue(values.get("min"));
                    Integer max = integerValue(values.get("max"));
                    if (min != null && min > 0) {
                        minSize = min;
                    }
                    if (max != null && max < Integer.MAX_VALUE) {
                        maxSize = max;
                    }
                }
                case "jakarta.validation.constraints.Pattern" -> pattern(stringValue(values.get("regexp")));
                case "jakarta.validation.constraints.Email" -> format("email");
                default -> {
                }
            }
        }

        void description(String value) {
            if (!isBlank(value)) {
                description = value.trim();
            }
        }

        void type(String value) {
            if (!isBlank(value)) {
                type = value;
            }
        }

        void format(String value) {
            if (!isBlank(value)) {
                format = value;
            }
        }

        void example(String value) {
            if (!isBlank(value)) {
                example = value;
            }
        }

        void defaultValue(String value) {
            if (!isBlank(value)) {
                defaultValue = value;
            }
        }

        void minimum(String value) {
            if (!isBlank(value)) {
                minimum = value;
            }
        }

        void maximum(String value) {
            if (!isBlank(value)) {
                maximum = value;
            }
        }

        void pattern(String value) {
            if (!isBlank(value)) {
                pattern = value;
            }
        }

        void required(boolean value) {
            required = required || value;
        }

        void deprecated(boolean value) {
            deprecated = deprecated || value;
        }

        void hidden(boolean value) {
            hidden = hidden || value;
        }

        SchemaMetadata build() {
            return new SchemaMetadata(description, type, format, example, defaultValue, minimum, maximum,
                                      minSize, maxSize, pattern, List.copyOf(allowableValues), required,
                                      deprecated, hidden, implementation);
        }
    }

    private class DocumentationBuilder {
        private String summary = "";
        private String description = "";
        private String operationId = "";
        private final List<String> tags = new ArrayList<>();
        private final List<String> security = new ArrayList<>();
        private boolean deprecated;

        void apply(AnnotationMirror annotation) {
            if (annotation == null) {
                return;
            }
            Map<String, AnnotationValue> values = annotationValues(annotation);
            String value = stringValue(values.get("summary"));
            if (!isBlank(value)) {
                summary = value;
            }
            value = stringValue(values.get("description"));
            if (!isBlank(value)) {
                description = value;
            }
            value = stringValue(values.get("operationId"));
            if (!isBlank(value)) {
                operationId = value;
            }
            for (String tag : stringList(values.get("tags"))) {
                if (!isBlank(tag) && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
            for (String requirement : stringList(values.get("security"))) {
                if (!isBlank(requirement) && !security.contains(requirement)) {
                    security.add(requirement);
                }
            }
            deprecated = deprecated || booleanValue(values.get("deprecated"));
        }

        Documentation build() {
            return new Documentation(summary, description, operationId, List.copyOf(tags), deprecated,
                                     List.copyOf(security));
        }
    }
}
