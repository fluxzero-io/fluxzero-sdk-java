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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchModelGraphDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.search.DefaultDocumentSerializer;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.search.ModelGraphInvalidation;
import io.fluxzero.common.search.ModelSearchDocument;
import io.fluxzero.common.search.SearchExclusions;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.tracking.handling.DocumentHandlerTopics;
import io.fluxzero.sdk.tracking.handling.HandleDocument;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;


/**
 * Internal, registration-scoped document reader. Retains the original composition for typed Graph handlers while
 * preserving the ordinary logical message, handler selection, payload parameters and interceptor input.
 * The root's upcast payload is reused; descendant paths and revisions always refer to the original composition.
 * Explicit internal Model-source handlers also require exactly one upcast state; ordinary document streams retain
 * split/drop semantics. Registrations are reference-counted and removed with their handlers.
 */
public final class DocumentMessageReader {
    private final Supplier<SearchClient> searchClient;
    private final Map<String, ModelGraphProjectionConfiguration> graphDefinitions = new HashMap<>();
    private volatile Map<String, ModelGraphProjectionConfiguration> definitions = Map.of();

    public DocumentMessageReader() { this(null); }

    /** Binds live Graph hydration to the owning client and consumer namespace. */
    public DocumentMessageReader(Supplier<SearchClient> searchClient) { this.searchClient = searchClient; }

    private final Map<String, Integer> registrations = new HashMap<>();
    private final Map<String, Integer> sourceRegistrations = new HashMap<>();
    private volatile Set<String> graphTopics = Set.of();
    private volatile Set<String> sourceTopics = Set.of();

    /** Records accepted typed Graph and Model-source methods until the returned registration is cancelled. */
    public Registration register(Object target, HandlerFilter filter) {
        return register(target, filter, Fluxzero.getOptionally().map(Fluxzero::modelRepository).orElse(null));
    }

    /** Registers durable notifications in the same namespace as the document consumer. */
    public synchronized Registration register(Object target, HandlerFilter filter, ModelRepository repository) {
        if (target instanceof Handler<?>) {
            return Registration.noOp(); // Opaque handlers own their parameter resolution.
        }
        Map<String, ModelGraphProjectionConfiguration> additions = new LinkedHashMap<>();
        Set<Class<?>> roots = new HashSet<>();
        Set<String> topics = new HashSet<>();
        Set<String> sources = new HashSet<>();
        Class<?> type = ReflectionUtils.asClass(target);
        for (var method : ReflectionUtils.getAnnotatedMethods(type, HandleDocument.class)) {
            HandleDocument annotation = ReflectionUtils.<HandleDocument>getMethodAnnotation(
                    method, HandleDocument.class).orElseThrow();
            if (!annotation.disabled() && DocumentHandlerTopics.graphType(annotation, method) != Void.class && filter.test(type, method)
                    && Arrays.stream(method.getParameterTypes()).anyMatch(Graph.class::isAssignableFrom)) {
                String topic = DocumentHandlerTopics.resolve(annotation, method);
                if (topic != null) {
                    Class<?> rootType = DocumentHandlerTopics.graphType(annotation, method);
                    var definition = repository instanceof DefaultModelRepository models
                            ? models.graphSearchDefinition(rootType).orElseThrow()
                            : EntityMetadata.validate(rootType).graphSearchConfiguration(List.of(rootType),
                                    ApplicationProperties.getProperty(ApplicationProperties.MODEL_NAME_PREFIX_PROPERTY, ""))
                                    .orElseThrow();
                    var previous = additions.putIfAbsent(topic, definition);
                    if (previous == null) { previous = graphDefinitions.get(topic); }
                    if (previous != null && !previous.equals(definition)) {
                        throw new IllegalArgumentException("Conflicting Graph definitions for document topic " + topic);
                    }
                    topics.add(topic);
                    if (repository != null && topic.equals(definition.getCollection())) {
                        roots.add(rootType);
                    }
                }
            }
            if (DocumentHandlerTopics.modelSourceType(annotation, method) != Void.class && filter.test(type, method)) {
                sources.add(DocumentHandlerTopics.resolve(annotation, method));
            }
        }
        if (topics.isEmpty() && sources.isEmpty()) { return Registration.noOp(); }
        // A failed validation/remote call must not leave partial local registrations behind.
        for (Class<?> root : roots) { repository.registerGraphProjection(root, false).join(); }
        graphDefinitions.putAll(additions);
        topics.forEach(topic -> registrations.merge(topic, 1, Integer::sum));
        sources.forEach(topic -> sourceRegistrations.merge(topic, 1, Integer::sum));
        definitions = Map.copyOf(graphDefinitions);
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
                    graphDefinitions.keySet().retainAll(registrations.keySet());
                    definitions = Map.copyOf(graphDefinitions);
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

    /** Whether this topic requires a single canonical Model state or the original Graph representation. */
    public boolean readsModelDocuments(String topic) {
        return readsGraphs(topic) || topic != null && sourceTopics.contains(topic);
    }

    /** Reads a batch; topics without typed Graph handlers retain the original serializer stream. */
    public Stream<DeserializingMessage> read(List<SerializedMessage> messages, String topic, Serializer serializer) {
        return read(messages, topic, serializer, searchClient == null ? null : searchClient.get());
    }

    /** Reads using the explicit consumer namespace while retaining this reader's registration-scoped definitions. */
    public Stream<DeserializingMessage> read(List<SerializedMessage> messages, String topic, Serializer serializer,
                                            SearchClient client) {
        boolean source = topic != null && sourceTopics.contains(topic);
        if (!source && !readsGraphs(topic)) {
            return serializer.deserializeMessages(messages.stream().map(SourceDocumentMessage::new),
                                                  MessageType.DOCUMENT, topic)
                    .map(message -> message.getSerializedObject() instanceof SourceDocumentMessage sourceMessage
                            ? message.putContext(DocumentSource.class, sourceMessage.original) : message);
        }
        List<SerializedMessage> resolved = hydrate(messages, topic, client);
        return resolved.stream().flatMap(message -> {
            Stream<DeserializingMessage> decoded = serializer.deserializeMessages(
                    Stream.of(message), MessageType.DOCUMENT, topic)
                    .map(decodedMessage -> retainSource(decodedMessage, message));
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
            DeserializingMessage result = roots.getFirst().putContext(GraphSource.class, new GraphSource(message, serializer));
            if (message.metadataContainsKey(ModelGraphInvalidation.METADATA_KEY)) {
                result = result.putContext(LiveGraph.class, new LiveGraph());
            }
            return Stream.of(result);
        });
    }

    private List<SerializedMessage> hydrate(List<SerializedMessage> messages, String topic, SearchClient client) {
        ModelGraphProjectionConfiguration definition = definitions.get(topic);
        if (definition == null) { return messages; }
        List<SerializedMessage> live = messages.stream().filter(message -> !definition.isStoreGraph()
                || message.metadataContainsKey(ModelGraphInvalidation.METADATA_KEY)).toList();
        if (live.isEmpty()) { return messages; }
        if (client == null) {
            throw new IllegalStateException("Live Graph document handling requires a configured search client");
        }
        List<String> ids = live.stream().map(SerializedMessage::getMessageId).distinct().toList();
        var request = new SearchModelGraphDocuments(SearchDocuments.builder()
                .query(SearchQuery.builder().collection(definition.getRootCollection()).build())
                .documentIds(ids).maxSize(ids.size()).build(), List.of(), definition.getComposition(),
                definition.getPathOverrides());
        Map<String, SerializedDocument> documents = new LinkedHashMap<>();
        try (var hits = client.searchModelGraph(request, Math.min(ids.size(), 100))) {
            hits.forEach(hit -> documents.put(hit.getId(), hit.getValue()));
        }
        Set<SerializedMessage> selected = Set.copyOf(live);
        return messages.stream().map(message -> {
            if (!selected.contains(message)) { return message; }
            SerializedDocument document = documents.get(message.getMessageId());
            Metadata metadata = message.getMetadata().without(
                    ModelGraphDocumentManifest.PREVIOUS_STATE_INDEX_METADATA_KEY)
                    .without(ModelGraphDocumentManifest.TOMBSTONE_METADATA_KEY)
                    .with(ModelGraphInvalidation.METADATA_KEY, true);
            if (document != null) {
                metadata = metadata.with(ModelGraphDocumentManifest.METADATA_KEY,
                        document.getMetadata().get(ModelGraphDocumentManifest.METADATA_KEY))
                        .with("$start", document.getTimestamp(), "$end", document.getEnd());
                return message.withData(document.getDocument()).withMetadata(metadata);
            }
            ModelGraphDocumentManifest manifest = ModelGraphDocumentManifest.from(metadata).orElseThrow(
                    () -> new IllegalArgumentException("Live Graph notification has no root identity: " + message.getMessageId()));
            return message.withData(new Data<>("null".getBytes(StandardCharsets.UTF_8),
                            manifest.type(manifest.nodes().getFirst()), manifest.nodes().getFirst().revision()))
                    .withMetadata(metadata.with(ModelGraphDocumentManifest.TOMBSTONE_METADATA_KEY, true));
        }).toList();
    }

    /** Retains the exact stored input across payload upcasting, without reading newer storage state. */
    public static DeserializingMessage retainSource(DeserializingMessage message, SerializedMessage source) {
        return message.putContext(DocumentSource.class, new DocumentSource(source.getData()));
    }

    /**
     * Returns metadata embedded in the handled document version, excluding the tracking envelope's time fields.
     * Derived search exclusions and Model search summaries are regenerated by the replacement serializer.
     * Decoding is deferred until a replacement is actually needed. Non-document inputs have no stored metadata.
     */
    public static Metadata sourceMetadata(DeserializingMessage message) {
        Metadata metadata = storedMetadata(message);
        if (metadata.containsKey(SearchExclusions.METADATA_KEY)) {
            metadata = metadata.without(SearchExclusions.METADATA_KEY);
        }
        if (metadata.containsKey(ModelSearchDocument.SUMMARY)) {
            metadata = metadata.without(ModelSearchDocument.SUMMARY);
        }
        return metadata;
    }

    private static Metadata storedMetadata(DeserializingMessage message) {
        Data<byte[]> data = message.getContext(DocumentSource.class).map(DocumentSource::data)
                .orElseGet(() -> message.getSerializedObject().getData());
        return DefaultDocumentSerializer.INSTANCE.canDeserialize(data)
                ? DefaultDocumentSerializer.INSTANCE.deserializeMetadata(data) : Metadata.empty();
    }

    private record DocumentSource(Data<byte[]> data) { }

    // Carry source attribution through serializer buffering, reordering, split/drop and metadata upcasts without
    // changing the serializer's batch boundary. The handling context then retains it across interceptor replacements.
    private static final class SourceDocumentMessage extends SerializedMessage {
        private final transient DocumentSource original;

        private SourceDocumentMessage(SerializedMessage source) {
            this(source, source.getData(), source.getMetadata(), source.getSegment(),
                 new DocumentSource(source.getData()));
        }

        private SourceDocumentMessage(SerializedMessage source, Data<byte[]> data, Metadata metadata, Integer segment,
                                      DocumentSource original) {
            super(data, metadata, segment, source.getIndex(), source.getSource(),
                  source.getTarget(), source.getRequestId(), source.getTimestamp(), source.getMessageId(),
                  source.getOriginalRevision());
            this.original = original;
        }

        @Override
        public SerializedMessage withData(Data<byte[]> data) {
            return data == getData() ? this : new SourceDocumentMessage(this, data, getMetadata(), getSegment(), original);
        }

        @Override
        public SerializedMessage withMetadata(Metadata metadata) {
            return metadata == getMetadata() ? this
                    : new SourceDocumentMessage(this, getData(), metadata, getSegment(), original);
        }

        @Override
        public SerializedMessage withSegment(Integer segment) {
            return java.util.Objects.equals(segment, getSegment()) ? this
                    : new SourceDocumentMessage(this, getData(), getMetadata(), segment, original);
        }
    }

    /** Live compositions are observational and must never be persisted as a handler return side effect. */
    public static boolean isLiveGraph(DeserializingMessage message) {
        return message.getContext(LiveGraph.class).isPresent();
    }

    private record LiveGraph() { }

    static SerializedMessage graphSource(DeserializingMessage message) {
        return message.getContext(GraphSource.class).map(GraphSource::message).orElse(null);
    }

    static boolean usesSerializer(DeserializingMessage message, DocumentSerializer serializer) {
        return message.getContext(GraphSource.class).map(source -> source.serializer == serializer).orElse(false);
    }

    private record GraphSource(SerializedMessage message, Serializer serializer) {}
}
