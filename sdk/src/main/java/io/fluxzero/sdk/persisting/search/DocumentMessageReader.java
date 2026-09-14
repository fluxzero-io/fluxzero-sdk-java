/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.persisting.search;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.tracking.handling.DocumentHandlerTopics;
import io.fluxzero.sdk.tracking.handling.HandleDocument;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Internal, registration-scoped document reader. Retains the original composition for typed Graph handlers while
 * preserving the ordinary logical message, handler selection, payload parameters and interceptor input.
 * The root's upcast payload is reused; descendant paths and revisions always refer to the original composition.
 * Explicit internal Model-source handlers also require exactly one upcast state; ordinary document streams retain
 * split/drop semantics. Registrations are reference-counted and removed with their handlers.
 */
public final class DocumentMessageReader {
    private final Map<String, Integer> registrations = new HashMap<>();
    private final Map<String, Integer> sourceRegistrations = new HashMap<>();
    private volatile Set<String> graphTopics = Set.of();
    private volatile Set<String> sourceTopics = Set.of();

    /** Records accepted typed Graph and Model-source methods until the returned registration is cancelled. */
    public synchronized Registration register(Object target, HandlerFilter filter) {
        if (target instanceof Handler<?>) {
            return Registration.noOp(); // Opaque handlers own their parameter resolution.
        }
        Set<String> topics = new HashSet<>();
        Set<String> sources = new HashSet<>();
        Class<?> type = ReflectionUtils.asClass(target);
        for (var method : ReflectionUtils.getAnnotatedMethods(type, HandleDocument.class)) {
            HandleDocument annotation = ReflectionUtils.<HandleDocument>getMethodAnnotation(
                    method, HandleDocument.class).orElseThrow();
            if (!annotation.disabled() && annotation.modelGraph() != Void.class && filter.test(type, method)
                    && Arrays.stream(method.getParameterTypes()).anyMatch(Graph.class::isAssignableFrom)) {
                String topic = DocumentHandlerTopics.resolve(annotation, method);
                if (topic != null) { topics.add(topic); }
            }
            if (!annotation.disabled() && annotation.modelState() != Void.class && filter.test(type, method)) {
                sources.add(DocumentHandlerTopics.resolve(annotation, method));
            }
        }
        if (topics.isEmpty() && sources.isEmpty()) { return Registration.noOp(); }
        topics.forEach(topic -> registrations.merge(topic, 1, Integer::sum));
        sources.forEach(topic -> sourceRegistrations.merge(topic, 1, Integer::sum));
        graphTopics = Set.copyOf(registrations.keySet());
        sourceTopics = Set.copyOf(sourceRegistrations.keySet());
        AtomicBoolean cancelled = new AtomicBoolean();
        return () -> {
            synchronized (this) {
                if (cancelled.compareAndSet(false, true)) {
                    topics.forEach(topic -> registrations.computeIfPresent(topic,
                            (ignored, count) -> count == 1 ? null : count - 1));
                    sources.forEach(topic -> sourceRegistrations.computeIfPresent(topic,
                            (ignored, count) -> count == 1 ? null : count - 1));
                    graphTopics = Set.copyOf(registrations.keySet());
                    sourceTopics = Set.copyOf(sourceRegistrations.keySet());
                }
            }
        };
    }

    /** Whether this collection needs the original composition alongside its logical document payload. */
    public boolean readsGraphs(String topic) {
        return topic != null && graphTopics.contains(topic);
    }

    /** Reads a batch; topics without typed Graph handlers retain the original serializer stream. */
    public Stream<DeserializingMessage> read(List<SerializedMessage> messages, String topic, Serializer serializer) {
        boolean source = topic != null && sourceTopics.contains(topic);
        if (!source && !readsGraphs(topic)) {
            return serializer.deserializeMessages(messages.stream(), MessageType.DOCUMENT, topic);
        }
        return messages.stream().flatMap(message -> {
            Stream<DeserializingMessage> decoded = serializer.deserializeMessages(
                    Stream.of(message), MessageType.DOCUMENT, topic);
            if (!source && !message.metadataContainsKey(ModelGraphDocumentManifest.METADATA_KEY)) {
                return decoded;
            }
            List<DeserializingMessage> roots = decoded.toList();
            if (roots.size() != 1) {
                throw new DeserializationException("%s document %s requires exactly one root state; "
                        .formatted(source ? "Model source" : "Materialized Graph", message.getMessageId())
                                                   + "upcasting produced " + roots.size());
            }
            if (source) { return roots.stream(); }
            return Stream.of(roots.getFirst().putContext(GraphSource.class, new GraphSource(message, serializer)));
        });
    }

    static SerializedMessage graphSource(DeserializingMessage message) {
        return message.getContext(GraphSource.class).map(GraphSource::message).orElse(null);
    }

    static boolean usesSerializer(DeserializingMessage message, DocumentSerializer serializer) {
        return message.getContext(GraphSource.class).map(source -> source.serializer == serializer).orElse(false);
    }

    private record GraphSource(SerializedMessage message, Serializer serializer) {}
}
