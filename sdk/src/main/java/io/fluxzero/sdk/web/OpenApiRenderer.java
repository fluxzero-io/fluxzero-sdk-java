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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.serialization.JsonUtils;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
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
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * Renders a generic {@link ApiDocCatalog} as an OpenAPI 3.1 JSON document.
 * <p>
 * The renderer intentionally uses a small inline schema generator instead of binding the SDK API to an OpenAPI-specific
 * model library. The generated schemas cover common Java primitives, enums, records, arrays, collections, maps, and
 * simple field-based POJOs.
 * </p>
 */
public final class OpenApiRenderer {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> OPENAPI_METHODS =
            Set.of(GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE);

    private OpenApiRenderer() {
    }

    public static ObjectNode render(ApiDocCatalog catalog) {
        return render(catalog, OpenApiOptions.defaults());
    }

    public static ObjectNode render(ApiDocCatalog catalog, OpenApiOptions options) {
        ObjectNode document = object();
        document.put("openapi", "3.1.0");
        document.set("info", info(options));
        if (!options.servers().isEmpty()) {
            ArrayNode servers = document.putArray("servers");
            options.servers().forEach(server -> servers.add(object().put("url", server)));
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
            path.set(method, operation(endpoint, uniqueOperationId(endpoint, operationIds)));
        }
        return document;
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

    private static ObjectNode info(OpenApiOptions options) {
        ObjectNode info = object();
        info.put("title", options.title());
        info.put("version", options.version());
        if (!options.description().isBlank()) {
            info.put("description", options.description());
        }
        return info;
    }

    private static ObjectNode operation(ApiDocEndpoint endpoint, String operationId) {
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
        if (!isBlank(endpoint.origin())) {
            operation.putArray("servers").add(object().put("url", endpoint.origin()));
        }
        ArrayNode parameters = parameters(endpoint);
        if (!parameters.isEmpty()) {
            operation.set("parameters", parameters);
        }
        requestBody(endpoint).ifPresent(body -> operation.set("requestBody", body));
        operation.set("responses", responses(endpoint));
        return operation;
    }

    private static ArrayNode parameters(ApiDocEndpoint endpoint) {
        ArrayNode result = JSON.arrayNode();
        for (ApiDocParameter parameter : endpoint.parameters()) {
            if (parameter.source() == WebParameterSource.BODY || parameter.source() == WebParameterSource.FORM) {
                continue;
            }
            ObjectNode node = object();
            node.put("name", parameter.name());
            node.put("in", location(parameter.source()));
            node.put("required", parameter.source() == WebParameterSource.PATH);
            node.set("schema", schema(parameter.type()));
            result.add(node);
        }
        return result;
    }

    private static Optional<ObjectNode> requestBody(ApiDocEndpoint endpoint) {
        if (!endpoint.requestBodies().isEmpty()) {
            return Optional.of(fullRequestBody(endpoint));
        }
        List<ApiDocParameter> bodyParameters = endpoint.parameters().stream()
                .filter(p -> p.source() == WebParameterSource.BODY).toList();
        if (!bodyParameters.isEmpty()) {
            return Optional.of(parameterObjectRequestBody("application/json", bodyParameters));
        }
        List<ApiDocParameter> formParameters = endpoint.parameters().stream()
                .filter(p -> p.source() == WebParameterSource.FORM).toList();
        if (!formParameters.isEmpty()) {
            String mediaType = formParameters.stream().anyMatch(p -> isBinaryType(p.type()))
                    ? "multipart/form-data" : "application/x-www-form-urlencoded";
            return Optional.of(parameterObjectRequestBody(mediaType, formParameters));
        }
        return Optional.empty();
    }

    private static ObjectNode fullRequestBody(ApiDocEndpoint endpoint) {
        if (endpoint.requestBodies().size() == 1) {
            ApiDocRequestBody body = endpoint.requestBodies().getFirst();
            return requestBody(inferMediaType(body.type()), schema(body.type()));
        }
        ObjectNode schema = object().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (ApiDocRequestBody body : endpoint.requestBodies()) {
            properties.set(parameterName(body.parameter()), schema(body.type()));
        }
        return requestBody("application/json", schema);
    }

    private static ObjectNode parameterObjectRequestBody(String mediaType, List<ApiDocParameter> parameters) {
        ObjectNode schema = object().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (ApiDocParameter parameter : parameters) {
            properties.set(parameter.name(), schema(parameter.type()));
        }
        return requestBody(mediaType, schema);
    }

    private static ObjectNode requestBody(String mediaType, ObjectNode schema) {
        ObjectNode requestBody = object();
        ObjectNode content = requestBody.putObject("content");
        content.putObject(mediaType).set("schema", schema);
        return requestBody;
    }

    private static ObjectNode responses(ApiDocEndpoint endpoint) {
        ObjectNode responses = object();
        defaultResponse(endpoint).ifPresent(response -> responses.set(response.status(), response.node()));
        for (ApiDocResponseDescriptor response : endpoint.responses()) {
            responses.set(String.valueOf(response.status()), response(response));
        }
        return responses;
    }

    private static Optional<RenderedResponse> defaultResponse(ApiDocEndpoint endpoint) {
        Type responseType = endpoint.responseType();
        if (isNoResponseType(responseType)) {
            return Optional.of(new RenderedResponse("204", object().put("description", "No Content")));
        }
        ObjectNode response = object().put("description", "OK");
        if (!isDynamicWebResponse(responseType)) {
            addContent(response, inferMediaType(responseType), schema(responseType));
        }
        return Optional.of(new RenderedResponse("200", response));
    }

    private static ObjectNode response(ApiDocResponseDescriptor descriptor) {
        ObjectNode response = object().put("description", isBlank(descriptor.description())
                ? defaultDescription(descriptor.status()) : descriptor.description());
        if (!isNoResponseType(descriptor.type())) {
            addContent(response,
                       isBlank(descriptor.contentType()) ? inferMediaType(descriptor.type()) : descriptor.contentType(),
                       schema(descriptor.type()));
        }
        return response;
    }

    private static void addContent(ObjectNode target, String mediaType, ObjectNode schema) {
        target.putObject("content").putObject(mediaType).set("schema", schema);
    }

    private static ObjectNode schema(Type type) {
        return schema(type, new LinkedHashSet<>());
    }

    private static ObjectNode schema(Type type, Set<Type> visiting) {
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> rawType = rawClass(parameterizedType.getRawType());
            if (rawType != null && Optional.class.isAssignableFrom(rawType)) {
                return nullableSchema(schema(parameterizedType.getActualTypeArguments()[0], visiting));
            }
            if (rawType != null && Collection.class.isAssignableFrom(rawType)) {
                return object().put("type", "array")
                        .set("items", schema(parameterizedType.getActualTypeArguments()[0], visiting));
            }
            if (rawType != null && Map.class.isAssignableFrom(rawType)) {
                ObjectNode node = object().put("type", "object");
                Type valueType = parameterizedType.getActualTypeArguments().length > 1
                        ? parameterizedType.getActualTypeArguments()[1] : Object.class;
                node.set("additionalProperties", schema(valueType, visiting));
                return node;
            }
            return schema(rawType == null ? Object.class : rawType, visiting);
        }
        if (type instanceof GenericArrayType arrayType) {
            return object().put("type", "array").set("items", schema(arrayType.getGenericComponentType(), visiting));
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            return schema(upperBounds.length == 0 ? Object.class : upperBounds[0], visiting);
        }
        if (type instanceof TypeVariable<?>) {
            return object().put("type", "object");
        }
        if (!(type instanceof Class<?> c)) {
            return object().put("type", "object");
        }
        return schema(c, visiting);
    }

    private static ObjectNode schema(Class<?> type, Set<Type> visiting) {
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
            return object().put("type", "array").set("items", schema(type.getComponentType(), visiting));
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
        if (!visiting.add(type)) {
            return object().put("type", "object");
        }
        try {
            return objectSchema(type, visiting);
        } finally {
            visiting.remove(type);
        }
    }

    private static ObjectNode objectSchema(Class<?> type, Set<Type> visiting) {
        ObjectNode node = object().put("type", "object");
        ObjectNode properties = node.putObject("properties");
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                properties.set(component.getName(), schema(component.getGenericType(), visiting));
            }
            return node;
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            properties.set(field.getName(), schema(field.getGenericType(), visiting));
        }
        return node;
    }

    private static ObjectNode nullableSchema(ObjectNode schema) {
        if (schema.has("type") && schema.get("type").isTextual()) {
            ArrayNode types = JSON.arrayNode();
            types.add(schema.get("type").asText());
            types.add("null");
            schema.set("type", types);
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

    private record RenderedResponse(String status, ObjectNode node) {
    }
}
