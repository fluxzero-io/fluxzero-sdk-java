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
import io.fluxzero.sdk.modeling.EntityMetadata;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Optional;

/**
 * Resolves the document topic selected by a {@link HandleDocument} handler.
 */
public final class DocumentHandlerTopics {

    private DocumentHandlerTopics() {
    }

    /**
     * Returns the explicit collection, materialized model-graph collection, document collection or inferred parameter
     * collection selected by the handler, in that order.
     */
    public static String resolve(HandleDocument handleDocument, Executable executable) {
        return Optional.ofNullable(handleDocument)
                .filter(handler -> !handler.disabled())
                .flatMap(handler -> Optional.ofNullable(handler.value()).filter(value -> !value.isBlank())
                        .or(() -> Void.class.equals(handler.modelGraph()) ? Optional.empty() :
                                Optional.of(EntityMetadata.validate(handler.modelGraph())
                                                    .graphProjectionConfiguration()
                                                    .orElseThrow(() -> new IllegalArgumentException(
                                                            "%s does not enable a materialized model graph projection"
                                                                    .formatted(handler.modelGraph().getName())))
                                                    .getCollection()))
                        .or(() -> Void.class.equals(handler.documentClass()) ? Optional.empty() :
                                Optional.of(ClientUtils.determineSearchCollection(handler.documentClass()))))
                .or(() -> Arrays.stream(executable.getParameters()).findFirst().map(Parameter::getType)
                        .map(ClientUtils::determineSearchCollection))
                .filter(value -> !value.isBlank())
                .orElse(null);
    }
}
