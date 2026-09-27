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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.Graph;

import java.lang.reflect.Executable;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;


/** Resolves the source, scope and document topic of a {@link HandleDocument} handler. */
public final class DocumentHandlerTopics {
    private DocumentHandlerTopics() { }

    /** Infers the one concrete Graph root, independently of context-parameter order. */
    public static Class<?> graphType(HandleDocument annotation, Executable executable) {
        if (annotation == null || annotation.disabled() || !annotation.value().isBlank()) { return Void.class; }
        Class<?> firstPayload = payloadType(executable);
        if (!Graph.class.isAssignableFrom(firstPayload)) { return Void.class; }
        var parameters = Arrays.stream(executable.getParameters())
                .filter(parameter -> Graph.class.isAssignableFrom(parameter.getType())).toList();
        if (parameters.isEmpty()) { return Void.class; }
        if (parameters.size() != 1) {
            throw new IllegalArgumentException("A document handler must select exactly one Graph: " + executable);
        }
        var parameter = parameters.getFirst();
        if (!(parameter.getParameterizedType() instanceof ParameterizedType generic)
                || generic.getActualTypeArguments().length != 1
                || !(generic.getActualTypeArguments()[0] instanceof Class<?> type)
                || !EntityMetadata.of(type).isModel()) {
            throw new IllegalArgumentException("A document Graph parameter requires a concrete Model type: " + parameter);
        }
        if (annotation.source() != DocumentSource.SEARCH) {
            throw new IllegalArgumentException("MODEL_STATE selects one Model value, not a Graph: " + executable);
        }
        if (annotation.documentClass() != Void.class && annotation.documentClass() != type) {
            return Void.class; // An explicit ordinary payload keeps Graph parameters contextual.
        }
        return type;
    }

    private static Class<?> documentType(HandleDocument annotation, Executable executable) {
        if (annotation.documentClass() != Void.class) { return annotation.documentClass(); }
        return payloadType(executable);
    }

    private static Class<?> payloadType(Executable executable) {
        return Arrays.stream(executable.getParameterTypes())
                .filter(type -> type != io.fluxzero.common.api.Metadata.class
                        && type != io.fluxzero.common.api.SerializedMessage.class
                        && type != io.fluxzero.sdk.common.Message.class
                        && type != io.fluxzero.sdk.common.serialization.DeserializingMessage.class)
                .findFirst().orElse(Void.class);
    }

    /** Identifies canonical Model documents for single-state deserialization and guarded schema rewriting. */
    public static Class<?> modelSourceType(HandleDocument annotation, Executable executable) {
        if (annotation == null || annotation.disabled() || graphType(annotation, executable) != Void.class) {
            return Void.class;
        }
        Class<?> type = documentType(annotation, executable);
        EntityMetadata metadata = EntityMetadata.of(type);
        if (!metadata.isModel()) { return Void.class; }
        String collection = metadata.modelDocumentCollection().orElse(null);
        return annotation.value().isBlank() || annotation.value().equals(collection) ? type : Void.class;
    }

    /** Resolves an explicit collection or the collection implied by the selected Model, Graph or document type. */
    public static String resolve(HandleDocument annotation, Executable executable) {
        if (annotation == null || annotation.disabled()) { return null; }
        Class<?> graphType = graphType(annotation, executable);
        Class<?> type = graphType == Void.class ? documentType(annotation, executable) : graphType;
        EntityMetadata metadata = EntityMetadata.of(type);
        if (annotation.source() == DocumentSource.MODEL_STATE) {
            if (!annotation.value().isBlank() || !metadata.isModel()) {
                throw new IllegalArgumentException("MODEL_STATE requires a Model type without an explicit collection: "
                                                   + executable);
            }
            return metadata.modelSourceDocumentCollection(ApplicationProperties.getProperty(
                    ApplicationProperties.MODEL_NAME_PREFIX_PROPERTY, ""))
                    .orElseThrow(() -> new IllegalArgumentException("Model has no maintained internal document source: "
                                                                   + type.getName()));
        }
        if (!annotation.value().isBlank()) { return annotation.value(); }
        if (type == Void.class) { return null; }
        if (metadata.isModel() && !metadata.isSearchable()) {
            throw new IllegalArgumentException("Model document handler requires searchable state: " + type.getName()
                    + "; use source = MODEL_STATE for internal state maintenance");
        }
        return graphType == Void.class ? ClientUtils.determineSearchCollection(type)
                : metadata.graphSearchConfiguration(List.of(type), ApplicationProperties.getProperty(
                        ApplicationProperties.MODEL_NAME_PREFIX_PROPERTY, "")).orElseThrow().getCollection();
    }
}
