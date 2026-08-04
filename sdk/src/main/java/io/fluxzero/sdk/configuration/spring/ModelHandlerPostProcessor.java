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

package io.fluxzero.sdk.configuration.spring;

import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelMetadata;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;

/**
 * Discovers independent models and self-applying model command types within Spring component-scan boundaries.
 * <p>
 * Discovered types are registered as {@link FluxzeroPrototype Fluxzero prototypes}, just like self-tracking payloads.
 * Their automatic command handlers therefore use the asynchronous tracker infrastructure and never turn publishing
 * from the same application into local command handling. A discovered command only becomes a handler when its locally
 * registered model chain contains a reachable model {@link Apply @Apply}. An interceptor with a dynamically typed
 * result is also tracked because its concrete output can only be known during command handling.
 */
public class ModelHandlerPostProcessor extends ComponentScanPrototypePostProcessor {

    @Override
    protected Class<Model> getTargetAnnotation() {
        return Model.class;
    }

    @Override
    protected String getBeanNameSuffix() {
        return "$$ModelHandler";
    }

    @Override
    protected TypeFilter getTargetTypeFilter() {
        return (metadataReader, metadataReaderFactory) -> {
            AnnotationMetadata metadata = metadataReader.getAnnotationMetadata();
            return metadata.hasAnnotation(Model.class.getName())
                   || metadata.hasMetaAnnotation(Model.class.getName())
                   || metadata.getAnnotatedMethods(Apply.class.getName()).stream()
                           .anyMatch(method -> {
                               try {
                                   AnnotationMetadata resultMetadata =
                                           metadataReaderFactory.getMetadataReader(method.getReturnTypeName())
                                                   .getAnnotationMetadata();
                                   return resultMetadata.hasAnnotation(Model.class.getName())
                                          || resultMetadata.hasMetaAnnotation(Model.class.getName());
                               } catch (IOException ignored) {
                                   return false;
                               }
                           })
                   || metadata.hasAnnotatedMethods(InterceptApply.class.getName());
        };
    }

    @Override
    protected boolean isTargetType(Class<?> type) {
        ModelMetadata metadata = ModelMetadata.of(type);
        return metadata.isModel() || metadata.handlerMethods().stream()
                .anyMatch(handler -> handler.kind() == ModelMetadata.HandlerKind.INTERCEPT_APPLY
                                     || handler.kind() == ModelMetadata.HandlerKind.APPLY
                                        && !handler.targetModelTypes().isEmpty());
    }

}
