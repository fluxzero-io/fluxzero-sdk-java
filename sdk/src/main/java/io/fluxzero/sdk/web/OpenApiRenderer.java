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
import io.fluxzero.common.serialization.JsonUtils;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.fluxzero.common.reflection.ReflectionUtils.getPackageAndParentPackages;
import static io.fluxzero.sdk.web.HttpRequestMethod.DELETE;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.HEAD;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;
import static io.fluxzero.sdk.web.HttpRequestMethod.PATCH;
import static io.fluxzero.sdk.web.HttpRequestMethod.POST;
import static io.fluxzero.sdk.web.HttpRequestMethod.PUT;
import static io.fluxzero.sdk.web.HttpRequestMethod.TRACE;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Renders a generic {@link ApiDocCatalog} as an OpenAPI JSON document.
 * <p>
 * The renderer intentionally uses a small inline schema generator instead of binding the SDK API to an OpenAPI-specific
 * model library. The generated schemas cover common Java primitives, enums, records, arrays, collections, maps, and
 * simple field-based POJOs.
 * </p>
 *
 * @see OpenApiProcessor
 */
public final class OpenApiRenderer {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> OPENAPI_METHODS =
            Set.of(GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE);

    private OpenApiRenderer() {
    }

    public static ObjectNode render(ApiDocCatalog catalog) {
        return render(catalog, null);
    }

    public static ObjectNode render(ApiDocCatalog catalog, OpenApiOptions options) {
        DocumentInfo documentInfo = documentInfo(catalog, options);
        SchemaContext schemaContext = new SchemaContext(documentInfo.openApiVersion());
        ObjectNode document = object();
        document.put("openapi", documentInfo.openApiVersion());
        document.set("info", info(documentInfo));
        documentInfo.extensions().forEach(document::set);
        if (!documentInfo.servers().isEmpty()) {
            ArrayNode servers = document.putArray("servers");
            documentInfo.servers().forEach(server -> {
                ObjectNode node = object().put("url", server.url());
                if (!isBlank(server.description())) {
                    node.put("description", server.description());
                }
                servers.add(node);
            });
        }
        if (!documentInfo.security().isEmpty()) {
            document.set("security", security(documentInfo.security()));
        }
        ObjectNode paths = document.putObject("paths");
        Map<String, Integer> operationIds = new LinkedHashMap<>();
        for (ApiDocEndpoint endpoint : catalog.endpoints()) {
            String method = openApiMethod(endpoint.method());
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

    private static void setComponent(ObjectNode components, String path, JsonNode value) {
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

    public static String renderJson(ApiDocCatalog catalog) {
        return JsonUtils.asJson(render(catalog));
    }

    public static String renderJson(ApiDocCatalog catalog, OpenApiOptions options) {
        return JsonUtils.asJson(render(catalog, options));
    }

    public static String renderPrettyJson(ApiDocCatalog catalog, OpenApiOptions options) {
        return JsonUtils.asPrettyJson(render(catalog, options));
    }

    private static DocumentInfo documentInfo(ApiDocCatalog catalog, OpenApiOptions options) {
        DocumentInfoBuilder builder = new DocumentInfoBuilder();
        for (ApiDocEndpoint endpoint : catalog.endpoints()) {
            packages(endpoint.handlerType()).forEach(p -> builder.apply(p.getAnnotation(ApiDocInfo.class)));
            builder.apply(endpoint.handlerType().getAnnotation(ApiDocInfo.class));
        }
        if (options != null) {
            builder.apply(options);
        }
        return builder.build();
    }

    private static List<Package> packages(Class<?> type) {
        Package leaf = type.getPackage();
        if (leaf == null || leaf.getName().isBlank()) {
            return List.of();
        }
        return getPackageAndParentPackages(leaf).reversed();
    }

    private static ObjectNode info(DocumentInfo documentInfo) {
        ObjectNode info = object();
        info.put("title", documentInfo.title());
        info.put("version", documentInfo.version());
        if (!documentInfo.description().isBlank()) {
            info.put("description", documentInfo.description());
        }
        if (!documentInfo.termsOfService().isBlank()) {
            info.put("termsOfService", documentInfo.termsOfService());
        }
        if (!documentInfo.contactName().isBlank() || !documentInfo.contactUrl().isBlank()
            || !documentInfo.contactEmail().isBlank()) {
            ObjectNode contact = info.putObject("contact");
            if (!documentInfo.contactName().isBlank()) {
                contact.put("name", documentInfo.contactName());
            }
            if (!documentInfo.contactUrl().isBlank()) {
                contact.put("url", documentInfo.contactUrl());
            }
            if (!documentInfo.contactEmail().isBlank()) {
                contact.put("email", documentInfo.contactEmail());
            }
        }
        if (!documentInfo.licenseName().isBlank() || !documentInfo.licenseUrl().isBlank()) {
            ObjectNode license = info.putObject("license");
            if (!documentInfo.licenseName().isBlank()) {
                license.put("name", documentInfo.licenseName());
            }
            if (!documentInfo.licenseUrl().isBlank()) {
                license.put("url", documentInfo.licenseUrl());
            }
        }
        if (!documentInfo.logoUrl().isBlank() || !documentInfo.logoAltText().isBlank()) {
            ObjectNode logo = info.putObject("x-logo");
            if (!documentInfo.logoUrl().isBlank()) {
                logo.put("url", documentInfo.logoUrl());
            }
            if (!documentInfo.logoAltText().isBlank()) {
                logo.put("altText", documentInfo.logoAltText());
            }
        }
        return info;
    }

    private static ObjectNode operation(ApiDocEndpoint endpoint, String operationId, SchemaContext schemaContext) {
        ObjectNode operation = object();
        ApiDocDetails doc = endpoint.documentation();
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

    private static ArrayNode parameters(ApiDocEndpoint endpoint, SchemaContext schemaContext) {
        ArrayNode result = JSON.arrayNode();
        for (ApiDocParameter parameter : endpoint.parameters()) {
            if (parameter.source() == WebParameterSource.BODY || parameter.source() == WebParameterSource.FORM
                || isHidden(parameter.parameter())) {
                continue;
            }
            ObjectNode node = object();
            node.put("name", parameter.name());
            node.put("in", location(parameter.source()));
            if (parameter.source() == WebParameterSource.PATH || isRequired(parameter.parameter())) {
                node.put("required", true);
            }
            ObjectNode schema = parameter.parameter() == null
                    ? schema(parameter.type(), schemaContext) : schema(parameter.parameter().getAnnotatedType(),
                                                                       schemaContext);
            applySchemaMetadata(schema, metadata(parameter.parameter()), schemaContext);
            removeDeclarationApiDocMetadata(schema, parameter.parameter());
            node.set("schema", schema);
            applyParameterMetadata(node, parameter.parameter());
            result.add(node);
        }
        return result;
    }

    private static Optional<ObjectNode> requestBody(ApiDocEndpoint endpoint, SchemaContext schemaContext) {
        List<ApiDocRequestBody> requestBodies = endpoint.requestBodies().stream()
                .filter(body -> !isHidden(body.parameter())).toList();
        if (!requestBodies.isEmpty()) {
            return Optional.of(fullRequestBody(requestBodies, schemaContext));
        }
        List<ApiDocParameter> bodyParameters = endpoint.parameters().stream()
                .filter(p -> p.source() == WebParameterSource.BODY && !isHidden(p.parameter())).toList();
        if (!bodyParameters.isEmpty()) {
            return Optional.of(parameterObjectRequestBody("application/json", bodyParameters, schemaContext));
        }
        List<ApiDocParameter> formParameters = endpoint.parameters().stream()
                .filter(p -> p.source() == WebParameterSource.FORM && !isHidden(p.parameter())).toList();
        if (!formParameters.isEmpty()) {
            String mediaType = formParameters.stream().anyMatch(p -> isBinaryType(p.type()))
                    ? "multipart/form-data" : "application/x-www-form-urlencoded";
            return Optional.of(parameterObjectRequestBody(mediaType, formParameters, schemaContext));
        }
        return Optional.empty();
    }

    private static ObjectNode fullRequestBody(List<ApiDocRequestBody> requestBodies, SchemaContext schemaContext) {
        if (requestBodies.size() == 1) {
            ApiDocRequestBody body = requestBodies.getFirst();
            ObjectNode schema = schema(body.type(), schemaContext);
            applySchemaMetadata(schema, metadata(body.parameter()), schemaContext);
            return requestBody(inferMediaType(body.type()), schema);
        }
        ObjectNode schema = object().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (ApiDocRequestBody body : requestBodies) {
            ObjectNode property = schema(body.parameter().getAnnotatedType(), schemaContext);
            applySchemaMetadata(property, metadata(body.parameter()), schemaContext);
            properties.set(parameterName(body.parameter()), property);
        }
        return requestBody("application/json", schema);
    }

    private static ObjectNode parameterObjectRequestBody(String mediaType, List<ApiDocParameter> parameters,
                                                         SchemaContext schemaContext) {
        ObjectNode schema = object().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = JSON.arrayNode();
        for (ApiDocParameter parameter : parameters) {
            ObjectNode property = parameter.parameter() == null
                    ? schema(parameter.type(), schemaContext) : schema(parameter.parameter().getAnnotatedType(),
                                                                       schemaContext);
            applySchemaMetadata(property, metadata(parameter.parameter()), schemaContext);
            properties.set(parameter.name(), property);
            if (isRequired(parameter.parameter())) {
                required.add(parameter.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return requestBody(mediaType, schema);
    }

    private static ObjectNode requestBody(String mediaType, ObjectNode schema) {
        ObjectNode requestBody = object();
        ObjectNode content = requestBody.putObject("content");
        content.putObject(mediaType).set("schema", schema);
        return requestBody;
    }

    private static ObjectNode responses(ApiDocEndpoint endpoint, SchemaContext schemaContext) {
        ObjectNode responses = object();
        Optional<RenderedResponse> defaultResponse = defaultResponse(endpoint, schemaContext);
        defaultResponse.ifPresent(response -> responses.set(response.status(), response.node()));
        for (ApiDocResponseDescriptor descriptor : endpoint.responses()) {
            String status = String.valueOf(descriptor.status());
            if (!isBlank(descriptor.ref())) {
                responses.set(status, reference(descriptor.ref(), "#/components/responses/"));
                continue;
            }
            ObjectNode base = defaultResponse
                    .filter(response -> response.status().equals(status))
                    .map(response -> response.node().deepCopy())
                    .orElseGet(OpenApiRenderer::object);
            responses.set(status, response(descriptor, base, schemaContext));
        }
        return responses;
    }

    private static Optional<RenderedResponse> defaultResponse(ApiDocEndpoint endpoint, SchemaContext schemaContext) {
        Type responseType = endpoint.responseType();
        if (isNoResponseType(responseType)) {
            return Optional.of(new RenderedResponse("204", object().put("description", "No Content")));
        }
        ObjectNode response = object().put("description", "OK");
        if (!isDynamicWebResponse(responseType)) {
            ObjectNode schema = endpoint.executable() instanceof Method method
                    ? responseSchema(method.getAnnotatedReturnType(), schemaContext)
                    : responseSchema(responseType, schemaContext);
            if (endpoint.executable() instanceof Method method) {
                removeDeclarationApiDocMetadata(schema, method);
            }
            addContent(response, inferMediaType(responseType), schema);
        }
        return Optional.of(new RenderedResponse("200", response));
    }

    private static ObjectNode response(ApiDocResponseDescriptor descriptor, ObjectNode response,
                                       SchemaContext schemaContext) {
        if (!response.has("description") || !isBlank(descriptor.description())) {
            response.put("description", isBlank(descriptor.description())
                    ? defaultDescription(descriptor.status()) : descriptor.description());
        }
        if (!isNoResponseType(descriptor.type())) {
            addContent(response,
                       isBlank(descriptor.contentType()) ? inferMediaType(descriptor.type()) : descriptor.contentType(),
                       responseSchema(descriptor.type(), schemaContext));
        }
        return response;
    }

    private static ArrayNode security(List<String> requirements) {
        ArrayNode result = JSON.arrayNode();
        requirements.stream().filter(requirement -> !isBlank(requirement))
                .map(OpenApiRenderer::securityRequirement)
                .filter(node -> !node.isEmpty())
                .forEach(result::add);
        return result;
    }

    private static ObjectNode securityRequirement(String requirement) {
        String value = requirement.trim();
        int separator = value.indexOf('=');
        String name = separator < 0 ? value : value.substring(0, separator).trim();
        ObjectNode result = object();
        if (name.isBlank()) {
            return result;
        }
        ArrayNode scopes = result.putArray(name);
        if (separator >= 0) {
            Arrays.stream(value.substring(separator + 1).split(","))
                    .map(String::trim)
                    .filter(scope -> !scope.isBlank())
                    .forEach(scopes::add);
        }
        return result;
    }

    private static ObjectNode reference(String ref, String defaultPrefix) {
        String value = ref.trim();
        return object().put("$ref", value.startsWith("#/") ? value : defaultPrefix + value);
    }

    private static void addContent(ObjectNode target, String mediaType, ObjectNode schema) {
        target.putObject("content").putObject(mediaType).set("schema", schema);
    }

    private static ObjectNode schema(Type type, SchemaContext schemaContext) {
        return schema(type, new LinkedHashSet<>(), schemaContext, false);
    }

    private static ObjectNode responseSchema(Type type, SchemaContext schemaContext) {
        return schema(type, new LinkedHashSet<>(), schemaContext, true);
    }

    private static ObjectNode schema(AnnotatedType annotatedType, SchemaContext schemaContext) {
        return schema(annotatedType, new LinkedHashSet<>(), schemaContext, false);
    }

    private static ObjectNode responseSchema(AnnotatedType annotatedType, SchemaContext schemaContext) {
        return schema(annotatedType, new LinkedHashSet<>(), schemaContext, true);
    }

    private static ObjectNode schema(AnnotatedType annotatedType, Set<Type> visiting, SchemaContext schemaContext,
                                     boolean responseSchema) {
        if (annotatedType == null) {
            return schema(Object.class, visiting, schemaContext, responseSchema);
        }
        Type type = annotatedType.getType();
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> rawType = rawClass(parameterizedType.getRawType());
            AnnotatedType[] arguments = annotatedType instanceof AnnotatedParameterizedType parameterized
                    ? parameterized.getAnnotatedActualTypeArguments() : new AnnotatedType[0];
            if (rawType != null && Optional.class.isAssignableFrom(rawType)) {
                ObjectNode schema = nullableSchema(arguments.length > 0
                        ? schema(arguments[0], visiting, schemaContext, responseSchema)
                        : schema(parameterizedType.getActualTypeArguments()[0], visiting, schemaContext,
                                 responseSchema),
                                                   schemaContext);
                applySchemaMetadata(schema, metadata(annotatedType), schemaContext);
                return schema;
            }
            if (rawType != null && Collection.class.isAssignableFrom(rawType)) {
                ObjectNode schema = object().put("type", "array");
                schema.set("items", arguments.length > 0
                        ? schema(arguments[0], visiting, schemaContext, responseSchema)
                        : schema(parameterizedType.getActualTypeArguments()[0], visiting, schemaContext,
                                 responseSchema));
                applySchemaMetadata(schema, metadata(annotatedType), schemaContext);
                return schema;
            }
            if (rawType != null && Map.class.isAssignableFrom(rawType)) {
                ObjectNode schema = object().put("type", "object");
                Type valueType = parameterizedType.getActualTypeArguments().length > 1
                        ? parameterizedType.getActualTypeArguments()[1] : Object.class;
                schema.set("additionalProperties", arguments.length > 1
                        ? schema(arguments[1], visiting, schemaContext, responseSchema)
                        : schema(valueType, visiting, schemaContext, responseSchema));
                applySchemaMetadata(schema, metadata(annotatedType), schemaContext);
                return schema;
            }
        }
        if (annotatedType instanceof AnnotatedArrayType arrayType) {
            ObjectNode schema = object().put("type", "array")
                    .set("items", schema(arrayType.getAnnotatedGenericComponentType(), visiting, schemaContext,
                                         responseSchema));
            applySchemaMetadata(schema, metadata(annotatedType), schemaContext);
            return schema;
        }
        if (annotatedType instanceof AnnotatedWildcardType wildcardType
            && wildcardType.getAnnotatedUpperBounds().length > 0) {
            ObjectNode schema = schema(wildcardType.getAnnotatedUpperBounds()[0], visiting, schemaContext,
                                       responseSchema);
            applySchemaMetadata(schema, metadata(annotatedType), schemaContext);
            return schema;
        }
        ObjectNode schema = schema(type, visiting, schemaContext, responseSchema);
        applySchemaMetadata(schema, metadata(annotatedType), schemaContext);
        return schema;
    }

    private static ObjectNode schema(Type type, Set<Type> visiting, SchemaContext schemaContext,
                                     boolean responseSchema) {
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> rawType = rawClass(parameterizedType.getRawType());
            if (rawType != null && Optional.class.isAssignableFrom(rawType)) {
                return nullableSchema(schema(parameterizedType.getActualTypeArguments()[0], visiting,
                                             schemaContext, responseSchema), schemaContext);
            }
            if (rawType != null && Collection.class.isAssignableFrom(rawType)) {
                return object().put("type", "array")
                        .set("items", schema(parameterizedType.getActualTypeArguments()[0], visiting,
                                             schemaContext, responseSchema));
            }
            if (rawType != null && Map.class.isAssignableFrom(rawType)) {
                ObjectNode node = object().put("type", "object");
                Type valueType = parameterizedType.getActualTypeArguments().length > 1
                        ? parameterizedType.getActualTypeArguments()[1] : Object.class;
                node.set("additionalProperties", schema(valueType, visiting, schemaContext, responseSchema));
                return node;
            }
            return schema(rawType == null ? Object.class : rawType, visiting, schemaContext, responseSchema);
        }
        if (type instanceof GenericArrayType arrayType) {
            return object().put("type", "array")
                    .set("items", schema(arrayType.getGenericComponentType(), visiting, schemaContext,
                                         responseSchema));
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            return schema(upperBounds.length == 0 ? Object.class : upperBounds[0], visiting, schemaContext,
                          responseSchema);
        }
        if (type instanceof TypeVariable<?>) {
            return object().put("type", "object");
        }
        if (!(type instanceof Class<?> c)) {
            return object().put("type", "object");
        }
        return schema(c, visiting, schemaContext, responseSchema);
    }

    private static ObjectNode schema(Class<?> type, Set<Type> visiting, SchemaContext schemaContext,
                                     boolean responseSchema) {
        if (String.class.equals(type) || CharSequence.class.isAssignableFrom(type)
            || Character.class.equals(type) || char.class.equals(type)) {
            return object().put("type", "string");
        }
        if (UUID.class.equals(type)) {
            return object().put("type", "string").put("format", "uuid");
        }
        if (URI.class.equals(type) || URL.class.equals(type)) {
            return object().put("type", "string").put("format", "uri");
        }
        if (LocalDate.class.equals(type)) {
            return object().put("type", "string").put("format", "date");
        }
        if (LocalTime.class.equals(type)) {
            return object().put("type", "string").put("format", "partial-time");
        }
        if (ZoneId.class.equals(type)) {
            return object().put("type", "string").put("format", "IANA timezone");
        }
        if (Date.class.isAssignableFrom(type) || Instant.class.equals(type) || LocalDateTime.class.equals(type)
            || OffsetDateTime.class.equals(type) || ZonedDateTime.class.equals(type)) {
            return object().put("type", "string").put("format", "date-time");
        }
        if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            return object().put("type", "boolean");
        }
        if (byte.class.equals(type) || Byte.class.equals(type) || short.class.equals(type)
            || Short.class.equals(type) || int.class.equals(type) || Integer.class.equals(type)) {
            return object().put("type", "integer").put("format", "int32");
        }
        if (long.class.equals(type) || Long.class.equals(type) || BigInteger.class.equals(type)) {
            return object().put("type", "integer").put("format", "int64");
        }
        if (float.class.equals(type) || Float.class.equals(type)) {
            return object().put("type", "number").put("format", "float");
        }
        if (double.class.equals(type) || Double.class.equals(type)) {
            return object().put("type", "number").put("format", "double");
        }
        if (BigDecimal.class.equals(type) || Number.class.isAssignableFrom(type)) {
            return object().put("type", "number");
        }
        if (isBinaryType(type)) {
            return object().put("type", "string").put("format", "binary");
        }
        if (type.isArray()) {
            return object().put("type", "array").set("items", schema(type.getComponentType(), visiting,
                                                                      schemaContext, responseSchema));
        }
        Optional<ObjectNode> jsonValueSchema = jsonValueSchema(type, visiting, schemaContext, responseSchema);
        if (jsonValueSchema.isPresent()) {
            return jsonValueSchema.get();
        }
        if (type.isEnum()) {
            ObjectNode node = object().put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return node;
        }
        if (Object.class.equals(type) || type.getName().startsWith("java.")) {
            return object().put("type", "object");
        }
        if (shouldReference(type)) {
            return schemaContext.ref(type, visiting, responseSchema);
        }
        if (!visiting.add(type)) {
            return object().put("type", "object");
        }
        try {
            return objectSchema(type, visiting, schemaContext, responseSchema);
        } finally {
            visiting.remove(type);
        }
    }

    private static ObjectNode objectSchema(Class<?> type, Set<Type> visiting, SchemaContext schemaContext,
                                           boolean responseSchema) {
        ObjectNode node = object().put("type", "object");
        applySchemaMetadata(node, metadata(type), schemaContext);
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = JSON.arrayNode();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (isHidden(component)) {
                    continue;
                }
                ObjectNode property = implementationType(component)
                        .map(componentType -> schema(componentType, visiting, schemaContext, responseSchema))
                        .orElseGet(() -> schema(component.getAnnotatedType(), visiting, schemaContext,
                                                responseSchema));
                applySchemaMetadata(property, metadata(component), schemaContext);
                properties.set(component.getName(), property);
                if (isRequired(component) || responseSchema && isRequiredArrayProperty(property)) {
                    required.add(component.getName());
                }
            }
            addRequired(node, required);
            addPolymorphism(node, type, visiting, schemaContext, responseSchema);
            return node;
        }
        Class<?> superclass = type.getSuperclass();
        boolean inheritedSchema = superclass != null && !Object.class.equals(superclass) && shouldReference(superclass);
        if (inheritedSchema) {
            node.remove("properties");
        }
        ObjectNode propertySchema = inheritedSchema ? object().put("type", "object") : node;
        properties = inheritedSchema ? propertySchema.putObject("properties") : properties;
        for (Field field : inheritedSchema ? List.of(type.getDeclaredFields()) : fields(type)) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers()) || isHidden(field)) {
                continue;
            }
            ObjectNode property = implementationType(field)
                    .map(fieldType -> schema(fieldType, visiting, schemaContext, responseSchema))
                    .orElseGet(() -> schema(field.getAnnotatedType(), visiting, schemaContext, responseSchema));
            applySchemaMetadata(property, metadata(field), schemaContext);
            properties.set(field.getName(), property);
            if (isRequired(field) || responseSchema && isRequiredArrayProperty(property)) {
                required.add(field.getName());
            }
        }
        for (Method method : inheritedSchema ? List.of(type.getDeclaredMethods()) : methods(type)) {
            Optional<String> propertyName = beanPropertyName(method);
            if (propertyName.isEmpty() || properties.has(propertyName.get()) || !isDocumentedAccessor(method)) {
                continue;
            }
            ObjectNode property = implementationType(method)
                    .map(methodType -> schema(methodType, visiting, schemaContext, responseSchema))
                    .orElseGet(() -> schema(method.getAnnotatedReturnType(), visiting, schemaContext,
                                            responseSchema));
            applySchemaMetadata(property, metadata(method), schemaContext);
            properties.set(propertyName.get(), property);
            if (isRequired(method) || responseSchema && isRequiredArrayProperty(property)) {
                required.add(propertyName.get());
            }
        }
        addRequired(propertySchema, required);
        if (inheritedSchema) {
            ArrayNode allOf = node.putArray("allOf");
            allOf.add(schemaContext.ref(superclass, visiting, responseSchema));
            if (!properties.isEmpty() || propertySchema.has("required")) {
                allOf.add(propertySchema);
            }
        }
        addPolymorphism(node, type, visiting, schemaContext, responseSchema);
        return node;
    }

    private static Optional<ObjectNode> jsonValueSchema(Class<?> type, Set<Type> visiting,
                                                        SchemaContext schemaContext, boolean responseSchema) {
        for (Field field : fields(type)) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || !isJsonValue(field)) {
                continue;
            }
            ObjectNode schema = implementationType(field)
                    .map(fieldType -> schema(fieldType, visiting, schemaContext, responseSchema))
                    .orElseGet(() -> schema(field.getAnnotatedType(), visiting, schemaContext, responseSchema));
            applySchemaMetadata(schema, metadata(field), schemaContext);
            return Optional.of(schema);
        }
        for (Method method : methods(type)) {
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers()) || method.getParameterCount() > 0
                || Void.TYPE.equals(method.getReturnType()) || !isJsonValue(method)) {
                continue;
            }
            ObjectNode schema = implementationType(method)
                    .map(methodType -> schema(methodType, visiting, schemaContext, responseSchema))
                    .orElseGet(() -> schema(method.getAnnotatedReturnType(), visiting, schemaContext,
                                            responseSchema));
            applySchemaMetadata(schema, metadata(method), schemaContext);
            return Optional.of(schema);
        }
        return Optional.empty();
    }

    private static boolean shouldReference(Class<?> type) {
        return !type.isPrimitive() && !type.isArray() && !type.isEnum() && !Object.class.equals(type)
               && !type.getName().startsWith("java.") && !isBinaryType(type);
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && !Object.class.equals(superclass)) {
            result.addAll(fields(superclass));
        }
        result.addAll(List.of(type.getDeclaredFields()));
        return result;
    }

    private static List<Method> methods(Class<?> type) {
        List<Method> result = new ArrayList<>();
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && !Object.class.equals(superclass)) {
            result.addAll(methods(superclass));
        }
        result.addAll(List.of(type.getDeclaredMethods()));
        return result;
    }

    private static boolean isDocumentedAccessor(Method method) {
        return !method.isSynthetic()
               && !Modifier.isStatic(method.getModifiers())
               && method.getParameterCount() == 0
               && !Void.TYPE.equals(method.getReturnType())
               && !isHidden(method)
               && (method.isAnnotationPresent(ApiDoc.class)
                   || annotation(method, "io.swagger.v3.oas.annotations.media.Schema") != null
                   || annotation(method, "io.swagger.v3.oas.annotations.media.ArraySchema") != null);
    }

    private static boolean isJsonValue(AnnotatedElement element) {
        Annotation jsonValue = annotation(element, "com.fasterxml.jackson.annotation.JsonValue");
        if (jsonValue == null) {
            return false;
        }
        Object value = annotationValue(jsonValue, "value");
        return !(value instanceof Boolean enabled) || enabled;
    }

    private static Optional<String> beanPropertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3 && !"getClass".equals(name)) {
            return Optional.of(decapitalize(name.substring(3)));
        }
        if (name.startsWith("is") && name.length() > 2
            && (Boolean.TYPE.equals(method.getReturnType()) || Boolean.class.equals(method.getReturnType()))) {
            return Optional.of(decapitalize(name.substring(2)));
        }
        return Optional.empty();
    }

    private static String decapitalize(String value) {
        if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static void addRequired(ObjectNode schema, ArrayNode required) {
        if (!required.isEmpty()) {
            ArrayNode sorted = JSON.arrayNode();
            List<String> names = new ArrayList<>();
            required.forEach(name -> names.add(name.asText()));
            names.stream().sorted().forEach(sorted::add);
            schema.set("required", sorted);
        }
    }

    private static boolean isRequiredArrayProperty(ObjectNode property) {
        return "array".equals(schemaType(property)) && !property.path("nullable").asBoolean(false);
    }

    private static void addPolymorphism(ObjectNode schema, Class<?> type, Set<Type> visiting,
                                        SchemaContext schemaContext, boolean responseSchema) {
        Annotation subTypes = annotation(type, "com.fasterxml.jackson.annotation.JsonSubTypes");
        if (subTypes == null) {
            return;
        }
        Object rawValue = annotationValue(subTypes, "value");
        if (!(rawValue instanceof Object[] values) || values.length == 0) {
            return;
        }
        Annotation typeInfo = annotation(type, "com.fasterxml.jackson.annotation.JsonTypeInfo");
        String propertyName = stringValue(annotationValue(typeInfo, "property"));
        if (!isBlank(propertyName)) {
            schema.putObject("discriminator").put("propertyName", propertyName);
        }
        ArrayNode oneOf = schema.putArray("oneOf");
        ObjectNode mapping = schema.has("discriminator")
                ? (ObjectNode) schema.path("discriminator").withObject("/mapping") : null;
        for (Object value : values) {
            if (!(value instanceof Annotation subType)) {
                continue;
            }
            Class<?> subTypeClass = classValue(annotationValue(subType, "value"));
            if (subTypeClass == null || type.equals(subTypeClass)) {
                continue;
            }
            ObjectNode reference = schemaContext.ref(subTypeClass, visiting, responseSchema);
            oneOf.add(reference);
            if (mapping != null) {
                String name = stringValue(annotationValue(subType, "name"));
                if (isBlank(name)) {
                    name = subTypeClass.getSimpleName();
                }
                mapping.put(name, reference.path("$ref").asText());
            }
        }
        if (oneOf.isEmpty()) {
            schema.remove("oneOf");
            schema.remove("discriminator");
        }
    }

    private static void applyParameterMetadata(ObjectNode node, Parameter parameter) {
        if (parameter == null) {
            return;
        }
        SchemaMetadata metadata = metadata(parameter);
        if (!isBlank(metadata.description)) {
            node.put("description", metadata.description);
        }
        if (metadata.deprecated) {
            node.put("deprecated", true);
        }
    }

    private static void removeDeclarationApiDocMetadata(ObjectNode schema, AnnotatedElement element) {
        if (element == null) {
            return;
        }
        ApiDoc apiDoc = element.getAnnotation(ApiDoc.class);
        if (apiDoc == null) {
            return;
        }
        if (!isBlank(apiDoc.description())
            && apiDoc.description().trim().equals(schema.path("description").asText("").trim())) {
            schema.remove("description");
        }
        if (apiDoc.deprecated() && schema.path("deprecated").asBoolean(false)) {
            schema.remove("deprecated");
        }
    }

    private static Optional<Type> implementationType(AnnotatedElement element) {
        return metadata(element).implementationType();
    }

    private static boolean isHidden(AnnotatedElement element) {
        return metadata(element).hidden;
    }

    private static boolean isRequired(AnnotatedElement element) {
        return metadata(element).required;
    }

    private static void applySchemaMetadata(ObjectNode schema, SchemaMetadata metadata,
                                            SchemaContext schemaContext) {
        boolean reference = schema.has("$ref");
        if (reference && !OpenApiOptions.isOpenApi31(schemaContext.openApiVersion())
            && hasReferenceSiblingMetadata(metadata)) {
            wrapReference(schema);
        }
        if (!isBlank(metadata.description)) {
            schema.put("description", metadata.description);
        }
        if (!reference) {
            if (!isBlank(metadata.type)) {
                if (Set.of("date", "date-time", "time", "uuid", "uri", "email", "binary").contains(metadata.type)) {
                    schema.put("type", "string");
                    schema.put("format", metadata.type);
                } else {
                    schema.put("type", metadata.type);
                }
            }
            if (!isBlank(metadata.format)) {
                schema.put("format", metadata.format);
            }
            if (!metadata.allowableValues.isEmpty()) {
                ArrayNode values = schema.putArray("enum");
                metadata.allowableValues.forEach(values::add);
            }
        }
        putSchemaValue(schema, "example", metadata.example);
        putSchemaValue(schema, "default", metadata.defaultValue);
        putDecimal(schema, "minimum", metadata.minimum);
        putDecimal(schema, "maximum", metadata.maximum);
        if (metadata.minSize != null) {
            schema.put("array".equals(schema.path("type").asText()) ? "minItems" : "minLength", metadata.minSize);
        }
        if (metadata.maxSize != null) {
            schema.put("array".equals(schema.path("type").asText()) ? "maxItems" : "maxLength", metadata.maxSize);
        }
        if (!isBlank(metadata.pattern)) {
            schema.put("pattern", metadata.pattern);
        }
        if (metadata.deprecated) {
            schema.put("deprecated", true);
        }
    }

    private static boolean hasReferenceSiblingMetadata(SchemaMetadata metadata) {
        return !isBlank(metadata.description) || !isBlank(metadata.example) || !isBlank(metadata.defaultValue)
               || !isBlank(metadata.minimum) || !isBlank(metadata.maximum) || metadata.minSize != null
               || metadata.maxSize != null || !isBlank(metadata.pattern) || metadata.deprecated;
    }

    private static void wrapReference(ObjectNode schema) {
        JsonNode ref = schema.remove("$ref");
        if (ref != null && !schema.has("allOf")) {
            schema.putArray("allOf").add(object().set("$ref", ref));
        }
    }

    private static void putDecimal(ObjectNode node, String name, String value) {
        if (isBlank(value)) {
            return;
        }
        try {
            node.put(name, new BigDecimal(value));
        } catch (NumberFormatException ignored) {
            node.put(name, value);
        }
    }

    private static void putSchemaValue(ObjectNode schema, String name, String value) {
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

    private static String schemaType(ObjectNode schema) {
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

    private static boolean looksLikeJsonValue(String value) {
        return value.startsWith("{") || value.startsWith("[") || value.startsWith("\"")
               || "true".equals(value) || "false".equals(value) || "null".equals(value)
               || value.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    private static SchemaMetadata metadata(AnnotatedElement element) {
        SchemaMetadata.Builder builder = new SchemaMetadata.Builder();
        if (element == null) {
            return builder.build();
        }
        Annotation arraySchema = annotation(element, "io.swagger.v3.oas.annotations.media.ArraySchema");
        if (arraySchema != null && annotationValue(arraySchema, "arraySchema") instanceof Annotation schema) {
            builder.apply(schema);
        }
        Annotation schema = annotation(element, "io.swagger.v3.oas.annotations.media.Schema");
        if (schema != null) {
            builder.apply(schema);
        }
        ApiDoc apiDoc = element.getAnnotation(ApiDoc.class);
        if (apiDoc != null) {
            builder.apply(apiDoc);
        }
        builder.hidden(annotation(element, "io.swagger.v3.oas.annotations.Hidden") != null
                       || element.isAnnotationPresent(ApiDocExclude.class));
        for (Annotation annotation : element.getAnnotations()) {
            builder.applyValidation(annotation);
        }
        return builder.build();
    }

    private static Annotation annotation(AnnotatedElement element, String annotationName) {
        if (element == null) {
            return null;
        }
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getName().equals(annotationName)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(Annotation annotation, String name) {
        if (annotation == null) {
            return null;
        }
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Class<?> classValue(Object value) {
        return value instanceof Class<?> c && !Void.class.equals(c) ? c : null;
    }

    private static ObjectNode nullableSchema(ObjectNode schema, SchemaContext schemaContext) {
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

    private static String location(WebParameterSource source) {
        return switch (source) {
            case PATH -> "path";
            case QUERY -> "query";
            case HEADER -> "header";
            case COOKIE -> "cookie";
            case BODY, FORM -> throw new IllegalArgumentException(source + " is rendered as requestBody");
        };
    }

    private static String openApiMethod(String method) {
        return OPENAPI_METHODS.contains(method) ? method.toLowerCase() : null;
    }

    private static String uniqueOperationId(ApiDocEndpoint endpoint, Map<String, Integer> operationIds) {
        String base = !isBlank(endpoint.documentation().operationId())
                ? endpoint.documentation().operationId() : endpoint.executable().getName();
        if ("<init>".equals(base)) {
            base = endpoint.handlerType().getSimpleName();
        }
        int count = operationIds.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + count;
    }

    private static String parameterName(Parameter parameter) {
        return parameter.isNamePresent() ? parameter.getName() : "body";
    }

    private static String inferMediaType(Type type) {
        Class<?> rawType = rawClass(type);
        if (rawType != null && isBinaryType(rawType)) {
            return "application/octet-stream";
        }
        if (rawType != null && (String.class.equals(rawType) || CharSequence.class.isAssignableFrom(rawType))) {
            return "text/plain";
        }
        return "application/json";
    }

    private static boolean isBinaryType(Type type) {
        Class<?> rawType = rawClass(type);
        return rawType != null && (byte[].class.equals(rawType) || ByteBuffer.class.isAssignableFrom(rawType)
                                   || InputStream.class.isAssignableFrom(rawType)
                                   || WebFormPart.class.isAssignableFrom(rawType));
    }

    private static boolean isNoResponseType(Type type) {
        return Void.TYPE.equals(type) || Void.class.equals(type);
    }

    private static boolean isDynamicWebResponse(Type type) {
        Class<?> rawType = rawClass(type);
        return rawType != null && WebResponse.class.isAssignableFrom(rawType);
    }

    private static Class<?> rawClass(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p when p.getRawType() instanceof Class<?> c -> c;
            case GenericArrayType ignored -> null;
            case null, default -> null;
        };
    }

    private static String defaultDescription(int status) {
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

    private static ObjectNode object() {
        return JSON.objectNode();
    }

    private static final class SchemaContext {
        private final Map<Class<?>, String> names = new LinkedHashMap<>();
        private final Map<String, ObjectNode> schemas = new LinkedHashMap<>();
        private final Set<String> responseSchemas = new LinkedHashSet<>();
        private final String openApiVersion;

        SchemaContext(String openApiVersion) {
            this.openApiVersion = openApiVersion;
        }

        ObjectNode ref(Class<?> type, Set<Type> visiting, boolean responseSchema) {
            String name = name(type);
            if (!schemas.containsKey(name) || responseSchema && !responseSchemas.contains(name)) {
                ObjectNode placeholder = schemas.computeIfAbsent(name, ignored -> object());
                if (responseSchema) {
                    responseSchemas.add(name);
                }
                placeholder.removeAll();
                placeholder.setAll(objectSchema(type, visiting, this, responseSchema));
            }
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

        private String name(Class<?> type) {
            return names.computeIfAbsent(type, this::uniqueName);
        }

        private String uniqueName(Class<?> type) {
            String base = type.getSimpleName().replaceAll("[^A-Za-z0-9._-]", "");
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

    private static final class DocumentInfoBuilder {
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

        void apply(ApiDocInfo info) {
            if (info == null) {
                return;
            }
            openApiVersion(info.openApiVersion());
            title(info.title());
            version(info.version());
            description(info.description());
            termsOfService(info.termsOfService());
            contactName(info.contactName());
            contactUrl(info.contactUrl());
            contactEmail(info.contactEmail());
            licenseName(info.licenseName());
            licenseUrl(info.licenseUrl());
            logoUrl(info.logoUrl());
            logoAltText(info.logoAltText());
            for (ApiDocServer server : info.servers()) {
                server(server.url(), server.description());
            }
            Arrays.stream(info.security())
                    .filter(s -> !isBlank(s) && !security.contains(s))
                    .forEach(security::add);
            for (ApiDocComponent component : info.components()) {
                component(component.path(), component.json());
            }
            for (String extension : info.extensions()) {
                extension(extension);
            }
        }

        void apply(OpenApiOptions options) {
            openApiVersion(options.openApiVersion());
            title(options.title());
            version(options.version());
            description(options.description());
            if (!options.servers().isEmpty()) {
                servers.clear();
                options.servers().forEach(server -> server(server, ""));
            }
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

        private void openApiVersion(String value) {
            if (!isBlank(value)) {
                openApiVersion = value;
            }
        }

        private void title(String value) {
            if (!isBlank(value)) {
                title = value;
            }
        }

        private void version(String value) {
            if (!isBlank(value)) {
                version = value;
            }
        }

        private void description(String value) {
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
            Class<?> implementation
    ) {
        Optional<Type> implementationType() {
            return Optional.ofNullable(implementation).map(type -> type);
        }

        private static final class Builder {
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
            private Class<?> implementation;

            void apply(Annotation schema) {
                description(stringValue(annotationValue(schema, "description")));
                type(stringValue(annotationValue(schema, "type")));
                format(stringValue(annotationValue(schema, "format")));
                example(stringValue(annotationValue(schema, "example")));
                defaultValue(stringValue(annotationValue(schema, "defaultValue")));
                minimum(stringValue(annotationValue(schema, "minimum")));
                maximum(stringValue(annotationValue(schema, "maximum")));
                Object allowable = annotationValue(schema, "allowableValues");
                if (allowable instanceof String[] values) {
                    allowableValues.addAll(List.of(values).stream().filter(v -> !isBlank(v)).toList());
                }
                String requiredMode = stringValue(annotationValue(schema, "requiredMode"));
                required(requiredMode.endsWith("REQUIRED") || Boolean.TRUE.equals(annotationValue(schema, "required")));
                deprecated(Boolean.TRUE.equals(annotationValue(schema, "deprecated")));
                hidden(Boolean.TRUE.equals(annotationValue(schema, "hidden")));
                Class<?> implementationValue = classValue(annotationValue(schema, "implementation"));
                if (implementationValue != null) {
                    implementation = implementationValue;
                }
            }

            void apply(ApiDoc apiDoc) {
                description(apiDoc.description());
                type(apiDoc.type());
                format(apiDoc.format());
                example(apiDoc.example());
                defaultValue(apiDoc.defaultValue());
                minimum(apiDoc.minimum());
                maximum(apiDoc.maximum());
                allowableValues.addAll(List.of(apiDoc.allowableValues()).stream()
                                               .filter(value -> !isBlank(value)).toList());
                required(apiDoc.required());
                deprecated(apiDoc.deprecated());
                if (!Void.class.equals(apiDoc.implementation())) {
                    implementation = apiDoc.implementation();
                }
            }

            void applyValidation(Annotation annotation) {
                String name = annotation.annotationType().getName();
                switch (name) {
                    case "jakarta.validation.constraints.NotNull",
                            "jakarta.validation.constraints.NotBlank",
                            "jakarta.validation.constraints.NotEmpty" -> required(true);
                    case "jakarta.validation.constraints.Min" -> minimum(stringValue(annotationValue(annotation,
                                                                                                      "value")));
                    case "jakarta.validation.constraints.Max" -> maximum(stringValue(annotationValue(annotation,
                                                                                                      "value")));
                    case "jakarta.validation.constraints.DecimalMin" -> minimum(stringValue(annotationValue(annotation,
                                                                                                             "value")));
                    case "jakarta.validation.constraints.DecimalMax" -> maximum(stringValue(annotationValue(annotation,
                                                                                                             "value")));
                    case "jakarta.validation.constraints.Positive",
                            "jakarta.validation.constraints.PositiveOrZero" -> minimum("0");
                    case "jakarta.validation.constraints.Size" -> {
                        Object min = annotationValue(annotation, "min");
                        Object max = annotationValue(annotation, "max");
                        if (min instanceof Integer value && value > 0) {
                            minSize = value;
                        }
                        if (max instanceof Integer value && value < Integer.MAX_VALUE) {
                            maxSize = value;
                        }
                    }
                    case "jakarta.validation.constraints.Pattern" -> pattern(stringValue(annotationValue(annotation,
                                                                                                          "regexp")));
                    case "jakarta.validation.constraints.Email" -> format("email");
                    default -> {
                    }
                }
            }

            void description(String value) {
                if (!isBlank(value)) {
                    description = value;
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
    }

    private record RenderedResponse(String status, ObjectNode node) {
    }
}
