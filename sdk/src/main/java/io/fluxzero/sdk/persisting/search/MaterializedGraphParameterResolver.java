/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.handling.PreparedParameterResolver;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.ModelGraphProjections;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleDocument;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Injects a typed materialized model graph into {@link HandleDocument} methods. */
public final class MaterializedGraphParameterResolver
        implements PreparedParameterResolver<Object> {
    private final DocumentSerializer documentSerializer;
    private final Supplier<ModelRepository> repositorySupplier;
    private final Supplier<List<Class<?>>> modelTypesSupplier;

    public MaterializedGraphParameterResolver(
            DocumentSerializer documentSerializer,
            Supplier<ModelRepository> repositorySupplier,
            Supplier<List<Class<?>>> modelTypesSupplier) {
        this.documentSerializer = Objects.requireNonNull(
                documentSerializer, "documentSerializer");
        this.repositorySupplier = Objects.requireNonNull(
                repositorySupplier, "repositorySupplier");
        this.modelTypesSupplier = Objects.requireNonNull(
                modelTypesSupplier, "modelTypesSupplier");
    }

    @Override
    public Function<Object, Object> resolve(
            Parameter parameter,
            Annotation methodAnnotation) {
        if (!(methodAnnotation instanceof HandleDocument annotation)
            || annotation.modelGraph() == Void.class
            || !Graph.class.isAssignableFrom(parameter.getType())) {
            return null;
        }
        Class<?> rootType = annotation.modelGraph();
        List<java.lang.reflect.Type> typeArguments =
                ReflectionUtils.getTypeArguments(
                        parameter.getParameterizedType());
        Class<?> parameterRootType = typeArguments.size() == 1
                ? ReflectionUtils.rawClass(typeArguments.getFirst())
                : null;
        if (!rootType.equals(parameterRootType)) {
            throw new IllegalArgumentException(
                    "@HandleDocument(modelGraph = %s.class) parameter %s must be Graph<%s>"
                            .formatted(rootType.getSimpleName(), parameter,
                                       rootType.getSimpleName()));
        }
        Map<String, String> pathOverrides =
                ModelGraphProjections.configuration(rootType)
                        .map(configuration ->
                                     configuration.getPathOverrides().stream()
                                             .collect(Collectors.toMap(
                                                     override -> override.getPath(),
                                                     override -> override.getProjectionPath(),
                                                     (first, second) -> second)))
                        .orElseGet(Map::of);
        return input -> create(
                requireDocumentMessage(input), rootType, pathOverrides);
    }

    @Override
    public Function<Object, Object> prepare(
            Parameter parameter,
            Annotation methodAnnotation) {
        return resolve(parameter, methodAnnotation);
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        HandleDocument annotation = ReflectionUtils
                .<HandleDocument>getMethodAnnotation(
                        method, HandleDocument.class)
                .orElse(null);
        return annotation != null
               && annotation.modelGraph() != Void.class
               && java.util.Arrays.stream(method.getParameterTypes())
                       .anyMatch(Graph.class::isAssignableFrom);
    }

    @Override
    public boolean matches(
            Parameter parameter,
            Annotation methodAnnotation,
            Object value) {
        return value instanceof DeserializingMessage
               && resolve(parameter, methodAnnotation) != null;
    }

    @Override
    public Function<Object, Object> resolveIfPossible(
            Parameter parameter,
            Annotation methodAnnotation,
            Object value) {
        return value instanceof DeserializingMessage
                ? resolve(parameter, methodAnnotation) : null;
    }

    private static DeserializingMessage requireDocumentMessage(Object input) {
        if (input instanceof DeserializingMessage message) {
            return message;
        }
        throw new IllegalArgumentException(
                "A materialized Graph parameter requires a document message");
    }

    private Graph<?> create(
            DeserializingMessage message,
            Class<?> rootType,
            Map<String, String> pathOverrides) {
        Long start = parseLong(message.getMetadata().get("$start"));
        Long end = parseLong(message.getMetadata().get("$end"));
        ModelGraphDocumentManifest manifest =
                ModelGraphDocumentManifest.from(message.getMetadata())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Materialized graph document %s has no typed graph manifest"
                                        .formatted(message.getMessageId())));
        return MaterializedGraphFactory.create(
                message.getPayloadAs(JsonNode.class),
                message.getMessageId(), message.getTopic(),
                start, end, manifest, rootType,
                documentSerializer,
                repositorySupplier, modelTypesSupplier.get(),
                pathOverrides);
    }

    private static Long parseLong(Object value) {
        return value == null ? null
                : value instanceof Number number
                        ? number.longValue()
                        : Long.valueOf(value.toString());
    }
}
