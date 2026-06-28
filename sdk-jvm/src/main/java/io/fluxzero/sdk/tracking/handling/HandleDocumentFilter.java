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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link MessageFilter} that routes {@link DeserializingMessage} instances to methods annotated with
 * {@link HandleDocument}, based on the message's topic.
 *
 * <p>This filter checks if the target handler method is annotated with {@code @HandleDocument}, and if so,
 * whether the resolved topic from the annotation matches the topic of the incoming message. If both conditions are met,
 * the message will be considered eligible for handling by the method.
 *
 * <p>The resolved topic is derived using {@link ClientUtils#getTopic(HandleDocument, Executable)},
 * which supports dynamic resolution based on annotation configuration and handler context.
 *
 * @see HandleDocument
 * @see DeserializingMessage
 * @see ClientUtils#getTopic(HandleDocument, Executable)
 */
public class HandleDocumentFilter implements MessageFilter<DeserializingMessage> {
    private final MetadataExecutableAnnotationResolver annotationResolver = MetadataExecutableAnnotationResolver.create();

    @Override
    public boolean test(DeserializingMessage message, Executable executable,
                        Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
        return documentAnnotation(executable)
                .map(handleDocument -> ClientUtils.getTopic(handleDocument, executable))
                .map(handlerCollection -> Objects.equals(message.getTopic(), handlerCollection))
                .orElse(false);
    }

    @Override
    public boolean test(DeserializingMessage message, ExecutableView executable,
                        Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
        return documentTopic(executable)
                .map(handlerCollection -> Objects.equals(message.getTopic(), handlerCollection))
                .orElse(false);
    }

    private Optional<HandleDocument> documentAnnotation(Executable executable) {
        Optional<HandleDocument> metadata = annotationResolver.getAnnotation(executable, HandleDocument.class)
                .map(HandleDocument.class::cast);
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata;
        }
        return JvmCompatibilityBackend.introspector().executableAnnotation(executable, HandleDocument.class);
    }

    private Optional<String> documentTopic(ExecutableView executable) {
        Optional<HandleDocument> metadata = annotationResolver.getAnnotation(executable, HandleDocument.class)
                .map(HandleDocument.class::cast);
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.map(handleDocument -> ClientUtils.getTopic(handleDocument, executable));
        }
        return executable.annotation(HandleDocument.class)
                .filter(h -> !h.disabled())
                .flatMap(h -> Optional.ofNullable(h.value()).filter(s -> !s.isBlank())
                        .or(() -> Optional.ofNullable(h.collection()).filter(s -> !s.isBlank()))
                        .or(() -> Void.class.equals(h.documentClass()) ? Optional.empty() :
                                Optional.of(ClientUtils.determineSearchCollection(h.documentClass())))
                        .or(() -> executable.parameters().stream().findFirst()
                                .flatMap(parameter -> parameter.type()
                                        .map(ClientUtils::determineSearchCollection))))
                .filter(s -> !s.isBlank());
    }
}
