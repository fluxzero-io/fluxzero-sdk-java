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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.caching.NoOpCache;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.AggregateCommitPolicy;
import io.fluxzero.sdk.modeling.AggregateEventRouting;
import io.fluxzero.sdk.modeling.AnnotatedEntityHolder;
import io.fluxzero.sdk.modeling.AppliedEvent;
import io.fluxzero.sdk.modeling.DefaultEntityHelper;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.modeling.EventPublicationStrategy;
import io.fluxzero.sdk.modeling.ImmutableAggregateRoot;
import io.fluxzero.sdk.modeling.ImmutableEntity;
import io.fluxzero.sdk.modeling.LazyAggregateRoot;
import io.fluxzero.sdk.modeling.ModifiableAggregateRoot;
import io.fluxzero.sdk.modeling.NoOpEntity;
import io.fluxzero.sdk.modeling.SideEffectFreeEntity;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.EventStore;
import io.fluxzero.sdk.persisting.eventsourcing.NoOpSnapshotStore;
import io.fluxzero.sdk.persisting.eventsourcing.NoSnapshotTrigger;
import io.fluxzero.sdk.persisting.eventsourcing.PeriodicSnapshotTrigger;
import io.fluxzero.sdk.persisting.eventsourcing.SnapshotStore;
import io.fluxzero.sdk.persisting.eventsourcing.SnapshotTrigger;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.common.SearchUtils.parseTimeProperty;
import static io.fluxzero.common.reflection.ReflectionUtils.classForName;
import static io.fluxzero.common.reflection.ReflectionUtils.getAnnotatedProperty;
import static io.fluxzero.sdk.modeling.ModifiableAggregateRoot.getActiveAggregatesFor;
import static java.lang.String.format;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;


/**
 * Default implementation of the {@link AggregateRepository} interface.
 * <p>
 * This class supports aggregates that are either event-sourced or document-based. Its behavior is driven by the
 * {@link Aggregate @Aggregate} annotation on the aggregate class, which determines aspects such as:
 * <ul>
 *   <li>Whether the aggregate is event-sourced or backed by a document store.</li>
 *   <li>If snapshots should be used and their frequency (via {@code snapshotPeriod}).</li>
 *   <li>Whether the aggregate is searchable (indexed for queries).</li>
 *   <li>Whether events should be published and how (via {@link EventPublicationStrategy}).</li>
 * </ul>
 *
 * <p>It supports caching of aggregates and entity-aggregate relationship metadata via the provided {@link Cache} instances.
 * These are consulted and updated during load and commit operations.
 *
 * <p>This repository coordinates with several components to manage aggregates:
 * <ul>
 *   <li>{@link EventStore} and {@link EventStoreClient} for storing and loading events.</li>
 *   <li>{@link SnapshotStore} for loading and storing snapshots.</li>
 *   <li>{@link DocumentStore} for searchable aggregates (non-event-sourced).</li>
 *   <li>{@link DispatchInterceptor} to inspect or modify outgoing events before dispatch.</li>
 * </ul>
 *
 * <p>This implementation also tracks relationships between aggregates and nested entities (e.g., value objects or sub-entities)
 * and can repair these links after structural refactors using {@link #repairRelationships(Entity)}.
 *
 * <p>The inner {@code AnnotatedAggregateRepository<T>} class handles aggregate-specific operations by introspecting the
 * annotations and metadata declared on a given aggregate type.
 *
 * @see Aggregate
 * @see AggregateRepository
 * @see Entity
 */
@Slf4j
@Getter(AccessLevel.PRIVATE)
@Accessors(fluent = true)
public class DefaultAggregateRepository implements AggregateRepository {
    /**
     * Property that overrides the commit policy for aggregates whose annotation uses
     * {@link AggregateCommitPolicy#DEFAULT}.
     */
    public static final String AGGREGATE_COMMIT_POLICY_PROPERTY = "fluxzero.aggregate.commitPolicy";

    private static final LocalDate ASYNC_AGGREGATE_COMMIT_DEFAULTS_VERSION = LocalDate.of(2026, 6, 9);

    private final EventStore eventStore;
    private final EventStoreClient eventStoreClient;
    private final SnapshotStore snapshotStore;
    private final Cache aggregateCache;
    private final RelationshipsCache relationshipsCache;
    private final DocumentStore documentStore;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final EntityHelper entityHelper;

    private final Function<Class<?>, AnnotatedAggregateRepository<?>> delegates =
            memoize(AnnotatedAggregateRepository::new);

    public DefaultAggregateRepository(EventStore eventStore, EventStoreClient eventStoreClient,
                                      SnapshotStore snapshotStore, Cache aggregateCache, Cache relationshipsCache,
                                      DocumentStore documentStore, Serializer serializer,
                                      DispatchInterceptor dispatchInterceptor, EntityHelper entityHelper) {
        this.eventStore = eventStore;
        this.eventStoreClient = eventStoreClient;
        this.snapshotStore = snapshotStore;
        this.aggregateCache = aggregateCache;
        this.relationshipsCache = RelationshipsCache.of(relationshipsCache);
        this.documentStore = documentStore;
        this.serializer = serializer;
        this.dispatchInterceptor = dispatchInterceptor;
        this.entityHelper = entityHelper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Entity<T> load(@NonNull Object aggregateId, Class<T> type) {
        Class<?> knownType;
        if (Object.class.equals(type)) {
            Class<?> mappedType = getAggregatesFor(aggregateId).getOrDefault(aggregateId.toString(), Object.class);
            knownType = Void.class.equals(mappedType) ? Object.class : mappedType;
            if (Object.class.equals(knownType)) {
                knownType = inferAggregateTypeFromFirstEvent(aggregateId).orElse(Object.class);
            }
        } else {
            knownType = type;
        }
        Class<?> delegateType = knownType;
        if (Entity.isLoading()) {
            return new NoOpEntity<>(() -> (Entity<T>) delegates.apply(delegateType).load(aggregateId));
        }
        return (Entity<T>) delegates.apply(delegateType).load(aggregateId);
    }

    private Optional<Class<?>> inferAggregateTypeFromFirstEvent(Object aggregateId) {
        try {
            Optional<SerializedMessage> firstEvent =
                    eventStoreClient.getEvents(aggregateId.toString(), -1L, 1).findFirst();
            if (firstEvent.isEmpty()) {
                return Optional.empty();
            }
            Optional<DeserializingMessage> firstMessage = serializer.deserializeMessages(
                    Stream.of(firstEvent.get()), EVENT, UnknownTypeStrategy.FAIL).findFirst();
            if (firstMessage.isEmpty()) {
                return Optional.empty();
            }
            Optional<Class<?>> aggregateType = Optional.<Class<?>>ofNullable(Entity.getAggregateType(firstMessage.get()))
                    .or(() -> inferAggregateTypeFromApplyFactory(firstMessage.get().getPayloadClass()));
            if (aggregateType.isEmpty()) {
                throw new EventSourcingException(format(
                        "Could not infer aggregate type from first event for aggregate %s.", aggregateId));
            }
            return aggregateType;
        } catch (EventSourcingException e) {
            throw e;
        } catch (Throwable e) {
            throw new EventSourcingException(format(
                    "Failed to infer aggregate type from first event for aggregate %s.", aggregateId), e);
        }
    }

    private static Optional<Class<?>> inferAggregateTypeFromApplyFactory(Class<?> eventType) {
        return ReflectionUtils.getAnnotatedMethods(eventType, Apply.class).stream()
                .filter(method -> method.getParameterCount() == 0)
                .map(Method::getReturnType)
                .filter(returnType -> !void.class.equals(returnType)
                                      && !Void.class.equals(returnType)
                                      && !Object.class.equals(returnType))
                .findFirst()
                .map(returnType -> (Class<?>) returnType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Entity<T> loadFor(@NonNull Object entityId, Class<?> defaultType) {
        Map<String, Class<?>> aggregates = getAggregatesFor(entityId);
        if (aggregates.isEmpty()) {
            return (Entity<T>) load(entityId, defaultType);
        }
        if (aggregates.containsKey(entityId.toString())) {
            Entity<T> result = (Entity<T>) load(entityId, aggregates.get(entityId.toString()));
            if (!result.isEmpty()) {
                return result;
            }
        }
        if (aggregates.size() > 1) {
            log.debug("Found multiple aggregates containing entity {}. Loading the most recent one.", entityId);
        }
        return aggregates.entrySet().stream().filter(e -> !Void.class.equals(e.getValue()))
                .reduce((a, b) -> b).map(e -> (Entity<T>) load(e.getKey(), e.getValue()))
                .orElseGet(() -> (Entity<T>) load(entityId, defaultType));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Entity<T> asEntity(T entityValue) {
        AnnotatedAggregateRepository<T> repository = (AnnotatedAggregateRepository<T>) delegates.apply(
                entityValue == null ? Object.class : entityValue.getClass());
        return repository.fromValue(entityValue);
    }

    @Override
    @SuppressWarnings("Java8MapForEach")
    public Map<String, Class<?>> getAggregatesFor(@NonNull Object entityId) {
        LinkedHashMap<String, Class<?>> result = new LinkedHashMap<>(getActiveAggregatesFor(entityId));
        relationshipsCache.getAggregatesFor(
                        entityId, id -> eventStoreClient.getAggregatesFor(id)
                                .entrySet().stream().collect(toMap(Map.Entry::getKey, e -> classForName(
                                        serializer.upcastType(e.getValue()), Void.class), (a, b) -> b, LinkedHashMap::new)))
                .entrySet().forEach(e -> result.putIfAbsent(e.getKey(), e.getValue()));
        return result;
    }

    @Override
    public CompletableFuture<Void> deleteAggregate(Object aggregateId) {
        var type = getAggregatesFor(aggregateId).getOrDefault(aggregateId.toString(), Void.class);
        return delegates.apply(type).delete(aggregateId);
    }

    @Override
    public CompletableFuture<Void> repairRelationships(Entity<?> aggregate) {
        aggregate = aggregate.root();
        return eventStoreClient.repairRelationships(new RepairRelationships(
                aggregate.id().toString(), aggregate.type().getName(),
                aggregate.relationships().stream().map(Relationship::getEntityId).collect(toSet()), STORED));
    }

    /**
     * Aggregate-type-specific delegate used internally by {@link DefaultAggregateRepository}.
     * <p>
     * This class is instantiated per aggregate class and parses metadata from the {@link Aggregate} annotation to determine:
     * <ul>
     *   <li>Whether the aggregate is event-sourced or backed by the document store.</li>
     *   <li>If and how frequently snapshots should be created.</li>
     *   <li>Whether the aggregate is indexed for search.</li>
     *   <li>How events should be published and committed.</li>
     *   <li>Which cache(s) to use.</li>
     * </ul>
     *
     * <p>It provides loading and committing capabilities tailored to the configured storage and publication strategy.
     * It also supports refetching aggregates from the Fluxzero Runtime and maintaining relationships with child entities.
     *
     * <p>Snapshot and document-based aggregates are supported out of the box. Event replay is triggered as needed.
     *
     * <p>This class is not intended to be used directly outside the {@code DefaultAggregateRepository}.
     *
     * @param <T> the aggregate root type
     */
    public class AnnotatedAggregateRepository<T> {

        private final Class<T> type;
        private final Cache aggregateCache;
        private final RelationshipsCache relationshipsCache;
        private final boolean eventSourced;
        private final AggregateCommitPolicy commitPolicy;
        private final EventPublication eventPublication;
        private final EventPublicationStrategy publicationStrategy;
        private final AggregateEventRouting eventRouting;
        private final SnapshotTrigger snapshotTrigger;
        private final SnapshotStore snapshotStore;
        private final boolean searchable;
        private final String collection;
        private final Function<Entity<?>, Instant> timestampFunction;
        private final Function<Entity<?>, Instant> endFunction;
        private final String idProperty;
        private final boolean ignoreUnknownEvents;

        public AnnotatedAggregateRepository(Class<T> type) {
            this.type = type;

            Aggregate annotation = DefaultEntityHelper.getRootAnnotation(type);
            this.aggregateCache = annotation.cached()
                    ? DefaultAggregateRepository.this.aggregateCache : NoOpCache.INSTANCE;
            this.relationshipsCache = annotation.cached()
                    ? DefaultAggregateRepository.this.relationshipsCache : RelationshipsCache.noOp();
            this.eventSourced = annotation.eventSourced();
            this.commitPolicy = resolveCommitPolicy(annotation);
            this.eventPublication = annotation.eventPublication();
            this.publicationStrategy = annotation.publicationStrategy();
            this.eventRouting = annotation.eventRouting();
            int snapshotPeriod = annotation.eventSourced() || annotation.searchable() ? annotation.snapshotPeriod() : 1;
            this.snapshotTrigger = snapshotPeriod > 0 ? new PeriodicSnapshotTrigger(snapshotPeriod) :
                    NoSnapshotTrigger.INSTANCE;
            this.snapshotStore = snapshotPeriod > 0
                    ? DefaultAggregateRepository.this.snapshotStore : NoOpSnapshotStore.INSTANCE;
            this.searchable = annotation.searchable();
            this.collection = Optional.of(annotation).map(Aggregate::collection)
                    .filter(s -> !s.isEmpty()).map(ApplicationProperties::substituteProperties)
                    .orElse(type.getSimpleName());
            this.idProperty = getAnnotatedProperty(type, EntityId.class).map(ReflectionUtils::getName).orElse(null);
            String timestampPath = Optional.of(annotation)
                    .map(Aggregate::timestampPath)
                    .filter(not(String::isBlank))
                    .orElse(null);
            this.timestampFunction = a -> parseTimeProperty(timestampPath, a.get(), false, a::timestamp);
            String endPath = Optional.of(annotation)
                    .map(Aggregate::endPath)
                    .filter(not(String::isBlank))
                    .orElse(null);
            this.endFunction = a -> parseTimeProperty(endPath, a.get(), true, () -> timestampFunction.apply(a));
            this.ignoreUnknownEvents = annotation.ignoreUnknownEvents();
        }

        @SuppressWarnings("unchecked")
        public Entity<T> fromValue(T value) {
            return new SideEffectFreeEntity<>(ImmutableAggregateRoot
                                                      .<T>builder()
                                                      .idProperty(idProperty)
                                                      .id(ReflectionUtils.readProperty(idProperty, value).orElse(null))
                                                      .value(value)
                                                      .type((Class<T>) (value != null ? value.getClass() :
                                                              Object.class))
                                                      .timestamp(Fluxzero.currentTime())
                                                      .entityHelper(entityHelper)
                                                      .eventStore(eventStore)
                                                      .serializer(serializer)
                                                      .sequenceNumber(0)
                                                      .build());
        }

        public CompletableFuture<Void> delete(Object id) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            String aggregateId = id.toString();
            aggregateCache.remove(aggregateId);
            relationshipsCache.removeAggregate(aggregateId);
            futures.add(eventStoreClient.repairRelationships(
                    new RepairRelationships(aggregateId, type.getName(), Collections.emptySet(), STORED)));
            futures.add(eventStoreClient.deleteEvents(aggregateId, STORED));
            futures.add(snapshotStore.deleteSnapshot(id));
            if (searchable) {
                futures.add(documentStore.deleteDocument(id, collection));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }

        public Entity<T> load(Object id) {
            return ModifiableAggregateRoot.load(
                    id, type, () -> aggregateCache.compute(id.toString(), (stringId, v) -> {
                        if (v != null) {
                            if (type.isAssignableFrom(v.type())) {
                                return v;
                            }
                            if (v.isPresent()) {
                                log.warn("Cache already contains a non-empty aggregate with id {} of type {} "
                                         + "that is not assignable to requested type {}. "
                                         + "This is likely to cause issues. Loading the aggregate again..",
                                         id, v.type(), type);
                            }
                        }
                        return eventSourceModel(loadSnapshot(id));
                    }), commitPolicy, eventPublication, publicationStrategy, eventRouting, eventSourced,
                    entityHelper, serializer, dispatchInterceptor, this::commit);
        }

        protected Entity<T> loadSnapshot(Object id) {
            var builder =
                    ImmutableAggregateRoot.<T>builder().id(id).type(type)
                            .idProperty(idProperty)
                            .entityHelper(entityHelper).serializer(serializer)
                            .eventStore(eventStore);
            return (searchable && !eventSourced
                    ? documentStore.<T>fetchDocument(id, collection)
                    .map(d -> builder.value(d).build())
                    : snapshotStore.<T>getSnapshot(id).map(
                    a -> withHistoricalSnapshots(restoreSnapshotId(id, a), id)))
                    .filter(a -> {
                        boolean assignable =
                                a.get() == null
                                || type.isAssignableFrom(a.get().getClass());
                        if (!assignable) {
                            log.warn(
                                    "Stored aggregate snapshot with id {} of type {} is not"
                                    + " assignable to the requested type {}. Ignoring the snapshot.",
                                    id, a.type(), type);
                        }
                        return assignable;
                    })
                    .map(a -> (Entity<T>) a)
                    .orElseGet(builder::build);
        }

        protected ImmutableAggregateRoot<T> restoreSnapshotId(Object id, Entity<T> snapshot) {
            return ImmutableAggregateRoot.from(snapshot, entityHelper, serializer, eventStore)
                    .toBuilder().id(id).build();
        }

        protected Entity<T> withHistoricalSnapshots(ImmutableAggregateRoot<T> snapshot, Object aggregateId) {
            if (snapshot.sequenceNumber() <= 0L || !eventSourced) {
                return snapshot;
            }
            Entity<T> checkpoint = snapshotStore.<T>getSnapshotBefore(aggregateId, snapshot.sequenceNumber())
                    .map(a -> withHistoricalSnapshots(restoreSnapshotId(aggregateId, a), aggregateId))
                    .orElseGet(() -> ImmutableAggregateRoot.<T>builder()
                            .id(aggregateId)
                            .type(snapshot.type())
                            .idProperty(snapshot.idProperty())
                            .entityHelper(entityHelper)
                            .serializer(serializer)
                            .eventStore(eventStore)
                            .sequenceNumber(-1L)
                            .build());
            Entity<T> previous = LazyAggregateRoot.from(snapshot.toBuilder()
                                                                .sequenceNumber(snapshot.sequenceNumber() - 1L)
                                                                .lastEventId(null)
                                                                .lastEventIndex(null)
                                                                .previous(null)
                                                                .build(),
                                                        checkpoint);
            return snapshot.toBuilder().previous(previous).build();
        }

        @SuppressWarnings("unchecked")
        protected Entity<T> eventSourceModel(Entity<T> model) {
            try {
                if (eventSourced) {
                    AggregateEventStream<DeserializingMessage> eventStream
                            = eventStore.getEvents(model.id().toString(), model.sequenceNumber(), -1,
                                                   ignoreUnknownEvents);
                    Iterator<DeserializingMessage> iterator = eventStream.iterator();
                    boolean wasLoading = Entity.isLoading();
                    var previousRouteCache = new LinkedHashMap<>(ImmutableEntity.snapshotLoadingRouteCache());
                    var previousEntityCache = new LinkedHashMap<>(AnnotatedEntityHolder.snapshotLoadingEntityCache());
                    var previousRouteValuesCache = new LinkedHashMap<>(AnnotatedEntityHolder.snapshotLoadingRouteValuesCache());
                    try {
                        Entity.loading.set(true);
                        ImmutableEntity.clearLoadingRouteCache();
                        AnnotatedEntityHolder.clearLoadingEntityCache();
                        AnnotatedEntityHolder.clearLoadingRouteValuesCache();
                        while (iterator.hasNext()) {
                            DeserializingMessage next = iterator.next();
                            if (model.isEmpty()) {
                                var t = Entity.getAggregateType(next);
                                if (t != null && !t.equals(this.type) && this.type.isAssignableFrom(t)) {
                                    model = model.withType((Class<T>) t);
                                }
                            }
                            try {
                                model = model.apply(next);
                            } catch (Throwable e) {
                                throw new EventSourcingException(format(
                                        "Failed to apply event %s to aggregate %s.", next.getIndex(), model.id()), e);
                            }
                        }
                    } finally {
                        AnnotatedEntityHolder.restoreLoadingRouteValuesCache(previousRouteValuesCache);
                        AnnotatedEntityHolder.restoreLoadingEntityCache(previousEntityCache);
                        ImmutableEntity.restoreLoadingRouteCache(previousRouteCache);
                        Entity.loading.set(wasLoading);
                    }
                    model = model.withSequenceNumber(
                            eventStream.getLastSequenceNumber().orElse(model.sequenceNumber()));
                }
                return model;
            } catch (EventSourcingException e) {
                throw e;
            } catch (Throwable e) {
                throw new EventSourcingException("Failed to apply events to aggregate " + model.id(), e);
            }
        }

        public CompletableFuture<Void> commit(Entity<?> after, List<AppliedEvent> unpublishedEvents, Entity<?> before) {
            if (after.type() != null && !Objects.equals(after.type(), type)) {
                return delegates.apply(after.type()).commit(after, unpublishedEvents, before);
            }
            if (after == before && unpublishedEvents.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            boolean stateChanged = after != before;
            List<AppliedEvent> aggregateStateEvents = unpublishedEvents.stream()
                    .filter(this::updatesAggregateState).toList();
            AtomicReference<Entity<?>> conflictingCachedHead = new AtomicReference<>();
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                CompletableFuture<Void> relationshipUpdates = CompletableFuture.completedFuture(null);
                if (stateChanged) {
                    AtomicBoolean aggregateEvicted = new AtomicBoolean();
                    aggregateCache.<Entity<?>>compute(after.id().toString(), (stringId, current) -> {
                        if (current == null || current == before) {
                            return after;
                        }
                        conflictingCachedHead.set(current);
                        if (aggregateStateEvents.isEmpty()) {
                            aggregateEvicted.set(true);
                            return null;
                        }
                        return current.apply(aggregateStateEvents.stream().map(AppliedEvent::getEvent).toList());
                    });
                    Set<Relationship> associations = after.associations(before), dissociations =
                            after.dissociations(before);
                    relationshipsCache.updateLinks(associations, dissociations, after.type());
                    if (!associations.isEmpty() || !dissociations.isEmpty()) {
                        relationshipUpdates = eventStoreClient.updateRelationships(
                                new UpdateRelationships(associations, dissociations, STORED));
                        futures.add(relationshipUpdates);
                    }
                    if (aggregateEvicted.get()) {
                        relationshipsCache.invalidateLookupsFor(conflictingCachedHead.get(), before, after);
                    }
                }
                CompletableFuture<Void> relationshipReady = relationshipUpdates;
                CompletableFuture<Void> eventsStored = relationshipReady;
                if (!unpublishedEvents.isEmpty()) {
                    eventsStored = relationshipReady.thenCompose(
                            ignored -> storeEvents(after.id().toString(), unpublishedEvents));
                    futures.add(eventsStored);
                    if (stateChanged && !aggregateStateEvents.isEmpty()
                        && snapshotTrigger.shouldCreateSnapshot(after, aggregateStateEvents)) {
                        futures.add(eventsStored.thenCompose(ignored -> snapshotStore.storeSnapshot(after)));
                    }
                }
                if (stateChanged && searchable) {
                    Object value = after.get();
                    if (value == null) {
                        futures.add(eventsStored.thenCompose(
                                ignored -> documentStore.deleteDocument(after.id().toString(), collection)));
                    } else {
                        futures.add(eventsStored.thenCompose(ignored -> documentStore.index(
                                value, after.id().toString(), collection,
                                timestampFunction.apply(after), endFunction.apply(after))));
                    }
                }
                return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .whenComplete((ignored, e) -> {
                            if (e != null) {
                                handleCommitFailure(after, before, conflictingCachedHead.get(), e);
                            }
                        });
            } catch (Exception e) {
                handleCommitFailure(after, before, conflictingCachedHead.get(), e);
                return CompletableFuture.failedFuture(e);
            }
        }

        private void handleCommitFailure(Entity<?> after, Entity<?> before, Entity<?> conflictingCachedHead,
                                         Throwable error) {
            log.error("Failed to commit aggregate {}", after.id(), error);
            aggregateCache.remove(after.id().toString());
            relationshipsCache.invalidateLookupsFor(conflictingCachedHead, before, after);
        }

        protected boolean updatesAggregateState(AppliedEvent event) {
            return !eventSourced || event.getPublicationStrategy() != EventPublicationStrategy.PUBLISH_ONLY;
        }

        CompletableFuture<Void> storeEvents(String aggregateId, List<AppliedEvent> appliedEvents) {
            Fluxzero.getOptionally().ifPresent(fc -> appliedEvents.forEach(
                    e -> e.getEvent().getSerializedObject().setSource(fc.client().id())));

            List<AppliedEvent> currentBatch = new ArrayList<>();
            EventPublicationStrategy currentStrategy = null;
            CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
            for (AppliedEvent event : appliedEvents) {
                if (event.getPublicationStrategy() != currentStrategy && currentStrategy != null) {
                    result = storeEventBatch(aggregateId, currentBatch, currentStrategy, result);
                    currentBatch.clear();
                }
                currentStrategy = event.getPublicationStrategy();
                currentBatch.add(event);
            }
            if (!currentBatch.isEmpty()) {
                result = storeEventBatch(aggregateId, currentBatch, currentStrategy, result);
            }
            return result;
        }

        private CompletableFuture<Void> storeEventBatch(String aggregateId, List<AppliedEvent> currentBatch,
                                                        EventPublicationStrategy currentStrategy,
                                                        CompletableFuture<Void> previous) {
            List<DeserializingMessage> events = currentBatch.stream().map(AppliedEvent::getEvent).toList();
            return previous.thenCompose(ignored -> eventStore.storeEvents(aggregateId, events, currentStrategy));
        }
    }

    static AggregateCommitPolicy resolveCommitPolicy(Aggregate annotation) {
        if (annotation.commitPolicy() != AggregateCommitPolicy.DEFAULT) {
            return annotation.commitPolicy();
        }
        AggregateCommitPolicy configured = configuredCommitPolicy();
        if (configured != AggregateCommitPolicy.DEFAULT) {
            return configured;
        }
        if (ApplicationProperties.defaultsVersionAtLeast(ASYNC_AGGREGATE_COMMIT_DEFAULTS_VERSION)) {
            return AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH;
        }
        return AggregateCommitPolicy.SYNC_AFTER_BATCH;
    }

    private static AggregateCommitPolicy configuredCommitPolicy() {
        String value = ApplicationProperties.getProperty(AGGREGATE_COMMIT_POLICY_PROPERTY);
        return value == null || value.isBlank() ? AggregateCommitPolicy.DEFAULT : parseCommitPolicy(value);
    }

    private static AggregateCommitPolicy parseCommitPolicy(String value) {
        try {
            return AggregateCommitPolicy.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EventSourcingException(
                    "Property `%s` must be one of %s, but found `%s`.".formatted(
                            AGGREGATE_COMMIT_POLICY_PROPERTY,
                            Arrays.toString(AggregateCommitPolicy.values()), value), e);
        }
    }

}
