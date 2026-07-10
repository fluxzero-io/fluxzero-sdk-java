/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.ConsistentHashing;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventStore;
import io.fluxzero.sdk.persisting.repository.AggregateRepository;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.Invocation;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.sdk.modeling.EventPublication.IF_MODIFIED;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

/**
 * A mutable, stateful {@link AggregateRoot} implementation that allows in-place updates and applies events
 * with commit support for persisting the state and synchronizing it with the Fluxzero Runtime.
 * <p>
 * This class acts as a wrapper around an immutable entity (typically an {@link ImmutableAggregateRoot}),
 * providing additional lifecycle management for:
 * <ul>
 *     <li>Capturing uncommitted changes during an event or command handler execution.</li>
 *     <li>Intercepting and applying updates and events with legality assertions.</li>
 *     <li>Buffering applied events and committing them via a {@link CommitHandler} callback.</li>
 *     <li>Supporting batch-commit semantics for grouped event processing.</li>
 *     <li>Tracking active aggregates for correlation and relationship resolution during handlers.</li>
 * </ul>
 *
 * <h2>Commit Lifecycle</h2>
 * During handler invocation, intercepted updates and applied events are stored in a temporary buffer.
 * When the handler completes successfully, events are committed via the configured {@link CommitHandler},
 * which typically stores events in the {@link EventStore} and
 * publishes them if needed.
 * <p>
 * If the handler fails (throws), state changes and captured events are discarded, and the aggregate is rolled
 * back to the last stable state.
 *
 * <h2>Publication Strategy</h2>
 * Events are published according to the {@link EventPublication} and {@link EventPublicationStrategy} specified
 * either globally or on individual {@link Apply}-annotated methods. This allows
 * fine-grained control over which events are stored or published.
 *
 * <h2>Execution-Scoped Aggregates</h2>
 * All active {@code ModifiableAggregateRoot} instances are tracked per thread and aggregate repository,
 * enabling relationship resolution and update tracking across aggregates within the same handler context while
 * isolating nested or interleaved repository executions.
 * <p>
 * This mechanism enables other parts of the framework to determine which aggregates are currently in use
 * or updated within a processing thread, without requiring external state.
 *
 * @param <T> the type of the aggregate's value.
 *
 * @see ImmutableAggregateRoot
 * @see LazyAggregateRoot
 * @see CommitHandler
 * @see EventPublication
 * @see EventPublicationStrategy
 */
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ModifiableAggregateRoot<T> extends DelegatingEntity<T> implements AggregateRoot<T> {

    private static final Object defaultActiveAggregateContext = new Object();
    private static final ThreadLocal<Map<Object, Map<String, ModifiableAggregateRoot<?>>>> activeAggregateContexts =
            ThreadLocal.withInitial(IdentityHashMap::new);

    @SuppressWarnings("unchecked")
    public static <T> Optional<ModifiableAggregateRoot<T>> getIfActive(Object aggregateId) {
        String id = aggregateId.toString();
        return activeAggregateContexts.get().values().stream()
                .map(aggregates -> aggregates.get(id)).filter(Objects::nonNull).findFirst()
                .map(aggregate -> (ModifiableAggregateRoot<T>) aggregate);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<ModifiableAggregateRoot<T>> getIfActive(Object context, Object aggregateId) {
        Map<String, ModifiableAggregateRoot<?>> activeAggregates = getActiveAggregates(context);
        return activeAggregates == null ? Optional.empty()
                : ofNullable((ModifiableAggregateRoot<T>) activeAggregates.get(aggregateId.toString()));
    }

    public static Map<String, Class<?>> getActiveAggregatesFor(@NonNull Object entityId) {
        return getActiveAggregatesFor(
                activeAggregateContexts.get().values().stream().flatMap(m -> m.values().stream()).toList(), entityId);
    }

    /**
     * Returns aggregates in the supplied repository that contain the entity on the current thread.
     */
    public static Map<String, Class<?>> getActiveAggregatesFor(
            @NonNull AggregateRepository repository, @NonNull Object entityId) {
        return getActiveAggregatesFor((Object) repository, entityId);
    }

    private static Map<String, Class<?>> getActiveAggregatesFor(Object context, Object entityId) {
        Map<String, ModifiableAggregateRoot<?>> activeAggregates = getActiveAggregates(context);
        return getActiveAggregatesFor(activeAggregates == null ? List.of() : activeAggregates.values(), entityId);
    }

    private static Map<String, Class<?>> getActiveAggregatesFor(
            Collection<? extends ModifiableAggregateRoot<?>> activeAggregates, Object entityId) {
        List<Entity<?>> candidates = activeAggregates.stream()
                .filter(a -> a.getEntity(entityId).isPresent()).collect(Collectors.toList());
        Comparator<Entity<?>> byPresent = Comparator.comparing(
                a -> a.getEntity(entityId).map(Entity::isPresent).orElse(false));
        Comparator<Entity<?>> byOrder = Comparator.comparing(candidates::indexOf);
        return candidates.stream()
                .sorted(byPresent.thenComparing(byOrder))
                .collect(toMap(e -> e.id().toString(), Entity::type, (a, b) -> b, LinkedHashMap::new));
    }

    public static <T> Entity<T> load(
            Object aggregateId, Supplier<Entity<T>> loader, AggregateCommitPolicy commitPolicy,
            EventPublication eventPublication, EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting, boolean eventSourced,
            EntityHelper entityHelper, Serializer serializer, DispatchInterceptor dispatchInterceptor,
            CommitHandler commitHandler) {
        return load(defaultActiveAggregateContext, aggregateId, loader, commitPolicy, eventPublication,
                    publicationStrategy, eventRouting, eventSourced, entityHelper, serializer, dispatchInterceptor,
                    commitHandler);
    }

    /**
     * Loads an aggregate in a scope owned by the supplied repository.
     */
    public static <T> Entity<T> load(
            @NonNull AggregateRepository repository,
            Object aggregateId, Supplier<Entity<T>> loader, AggregateCommitPolicy commitPolicy,
            EventPublication eventPublication, EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting, boolean eventSourced,
            EntityHelper entityHelper, Serializer serializer, DispatchInterceptor dispatchInterceptor,
            CommitHandler commitHandler) {
        return load((Object) repository, aggregateId, loader, commitPolicy, eventPublication, publicationStrategy,
                    eventRouting, eventSourced, entityHelper, serializer, dispatchInterceptor, commitHandler);
    }

    private static <T> Entity<T> load(
            Object context, Object aggregateId, Supplier<Entity<T>> loader, AggregateCommitPolicy commitPolicy,
            EventPublication eventPublication, EventPublicationStrategy publicationStrategy,
            AggregateEventRouting eventRouting, boolean eventSourced,
            EntityHelper entityHelper, Serializer serializer, DispatchInterceptor dispatchInterceptor,
            CommitHandler commitHandler) {
        return ModifiableAggregateRoot.<T>getIfActive(context, aggregateId).orElseGet(
                () -> new ModifiableAggregateRoot<>(context, loader.get(), commitPolicy, eventPublication,
                                                    publicationStrategy, eventRouting, eventSourced, entityHelper,
                                                    serializer, dispatchInterceptor, commitHandler));
    }

    private Entity<T> lastCommitted;
    private Entity<T> lastStable;
    private final Object activeAggregateContext;
    private final AggregateCommitPolicy commitPolicy;

    private final EventPublication aggregateEventPublication;
    private final EventPublicationStrategy aggregatePublicationStrategy;
    private final AggregateEventRouting aggregateEventRouting;
    private final boolean eventSourced;
    private final EntityHelper entityHelper;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final CommitHandler commitHandler;
    private final boolean awaitAfterHandlerCommitsBeforeResults;

    private final AtomicBoolean waitingForHandlerEnd = new AtomicBoolean(), waitingForBatchEnd = new AtomicBoolean();
    private final List<AppliedEvent> applied = new ArrayList<>(), uncommitted = new ArrayList<>();
    private final List<CompletableFuture<Void>> pendingBatchCompletions = new ArrayList<>();
    private final List<UnaryOperator<Entity<T>>> queued = new ArrayList<>();
    private volatile boolean updating, committing, commitPending;

    protected ModifiableAggregateRoot(Entity<T> delegate, AggregateCommitPolicy commitPolicy,
                                      EventPublication eventPublication, EventPublicationStrategy publicationStrategy,
                                      AggregateEventRouting eventRouting, boolean eventSourced,
                                      EntityHelper entityHelper, Serializer serializer,
                                      DispatchInterceptor dispatchInterceptor, CommitHandler commitHandler) {
        this(defaultActiveAggregateContext, delegate, commitPolicy, eventPublication, publicationStrategy, eventRouting,
             eventSourced, entityHelper, serializer, dispatchInterceptor, commitHandler);
    }

    private ModifiableAggregateRoot(Object activeAggregateContext, Entity<T> delegate,
                                    AggregateCommitPolicy commitPolicy,
                                    EventPublication eventPublication, EventPublicationStrategy publicationStrategy,
                                    AggregateEventRouting eventRouting, boolean eventSourced,
                                    EntityHelper entityHelper, Serializer serializer,
                                    DispatchInterceptor dispatchInterceptor, CommitHandler commitHandler) {
        super(delegate);
        this.entityHelper = entityHelper;
        this.activeAggregateContext = activeAggregateContext;
        this.lastCommitted = delegate;
        this.lastStable = delegate;
        this.commitPolicy = commitPolicy;
        this.aggregateEventPublication = eventPublication;
        this.aggregatePublicationStrategy = publicationStrategy;
        this.aggregateEventRouting = eventRouting == AggregateEventRouting.DEFAULT
                ? AggregateEventRouting.MESSAGE_ROUTING_KEY : eventRouting;
        this.eventSourced = eventSourced;
        this.serializer = serializer;
        this.dispatchInterceptor = dispatchInterceptor;
        this.commitHandler = commitHandler;
        this.awaitAfterHandlerCommitsBeforeResults = ApplicationProperties.getBooleanProperty(
                AggregateCommitPolicy.AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY, true);
    }

    @Override
    public <E extends Exception> Entity<T> assertLegal(Object update) throws E {
        entityHelper.intercept(update, this).forEach(c -> entityHelper.assertLegal(c, this));
        return this;
    }

    @Override
    public Entity<T> assertAndApply(Object payloadOrMessage) {
        entityHelper.intercept(payloadOrMessage, this).forEach(m -> apply(Message.asMessage(m), true));
        return this;
    }

    @Override
    public Entity<T> update(UnaryOperator<T> function) {
        return handleUpdate(a -> a.update(function));
    }

    @Override
    public Entity<T> apply(Message message) {
        entityHelper.intercept(message, this).forEach(m -> apply(Message.asMessage(m), false));
        return this;
    }

    protected Entity<T> apply(Message message, boolean assertLegal) {
        return handleUpdate(a -> {
            Optional<Apply> applyAnnotation = entityHelper.applyInvoker(
                            new DeserializingMessage(message, EVENT, null, serializer), a, true)
                    .map(HandlerInvoker::getMethodAnnotation);

            var eventPublication = applyAnnotation.map(Apply::eventPublication)
                    .filter(ep -> ep != EventPublication.DEFAULT).orElse(this.aggregateEventPublication);
            var publicationStrategy = applyAnnotation.map(Apply::publicationStrategy)
                    .filter(ep -> ep != EventPublicationStrategy.DEFAULT).orElse(this.aggregatePublicationStrategy);
            var eventRouting = applyAnnotation.map(Apply::eventRouting)
                    .filter(er -> er != AggregateEventRouting.DEFAULT).orElse(this.aggregateEventRouting);

            if (assertLegal) {
                entityHelper.assertLegal(message, a);
            }

            boolean interceptBeforeApply = shouldInterceptDispatchBeforeApply(eventPublication);
            Message dispatchMessage = message;
            if (interceptBeforeApply) {
                dispatchMessage = dispatchInterceptor.interceptDispatch(message, EVENT, null);
                if (dispatchMessage == null) {
                    return a;
                }
                dispatchMessage = addAggregateMetadata(dispatchMessage, publicationStrategy);
            }

            int hashCodeBefore = eventPublication == IF_MODIFIED ? a.get() == null ? -1 : a.get().hashCode() : -1;

            Entity<T> result = a.apply(interceptBeforeApply ? dispatchMessage : message);
            boolean skipStateUpdate = eventSourced && publicationStrategy == EventPublicationStrategy.PUBLISH_ONLY;
            boolean publishEvent = switch (eventPublication) {
                case ALWAYS, DEFAULT -> true;
                case IF_MODIFIED -> !Objects.equals(a.get(), result.get())
                                    || (result.get() != null && result.get().hashCode() != hashCodeBefore);
                case NEVER -> false;
            };
            if (publishEvent) {
                if (!interceptBeforeApply) {
                    dispatchMessage = dispatchInterceptor.interceptDispatch(message, EVENT, null);
                    if (dispatchMessage == null) {
                        return skipStateUpdate ? a : result;
                    }
                    dispatchMessage = addAggregateMetadata(dispatchMessage, publicationStrategy);
                }
                var serializedEvent = dispatchInterceptor.modifySerializedMessage(
                        serializeAggregateEvent(dispatchMessage, eventRouting), dispatchMessage, EVENT, null);
                if (serializedEvent == null) {
                    return interceptBeforeApply || skipStateUpdate ? a : result;
                }
                Message appliedMessage = dispatchMessage;
                applied.add(new AppliedEvent(new DeserializingMessage(serializedEvent, type ->
                        serializer.convert(appliedMessage.getPayload(), type), EVENT, null, serializer),
                                             publicationStrategy));
            }
            if (skipStateUpdate) {
                return a;
            }
            if (!publishEvent) {
                return eventPublication == IF_MODIFIED ? a : withoutPublishedEventMetadata(a, result);
            }
            return result;
        });
    }

    private Entity<T> withoutPublishedEventMetadata(Entity<T> before, Entity<T> result) {
        if (result instanceof ImmutableAggregateRoot<T> root) {
            return root.toBuilder()
                    .lastEventId(before.lastEventId())
                    .lastEventIndex(before.lastEventIndex())
                    .previous(before.previous())
                    .sequenceNumber(before.sequenceNumber())
                    .build();
        }
        return result;
    }

    private boolean shouldInterceptDispatchBeforeApply(EventPublication eventPublication) {
        return eventSourced && eventPublication != EventPublication.NEVER;
    }

    private SerializedMessage serializeAggregateEvent(Message message, AggregateEventRouting eventRouting) {
        SerializedMessage result = message.serialize(serializer);
        if (eventRouting == AggregateEventRouting.AGGREGATE_ID) {
            result.setSegment(ConsistentHashing.computeSegment(id().toString()));
        }
        return result;
    }

    private Message addAggregateMetadata(Message message, EventPublicationStrategy publicationStrategy) {
        return publicationStrategy == EventPublicationStrategy.PUBLISH_ONLY
                ? message.addMetadata(Entity.AGGREGATE_ID_METADATA_KEY, id().toString(),
                                      Entity.AGGREGATE_TYPE_METADATA_KEY, type().getName())
                : message.addMetadata(Entity.AGGREGATE_ID_METADATA_KEY, id().toString(),
                                      Entity.AGGREGATE_TYPE_METADATA_KEY, type().getName(),
                                      Entity.AGGREGATE_SN_METADATA_KEY,
                                      String.valueOf(getDelegate().sequenceNumber() + 1L));
    }

    protected Entity<T> handleUpdate(UnaryOperator<Entity<T>> update) {
        if (updating) {
            queued.add(update);
            return this;
        }
        try {
            updating = true;
            boolean firstUpdate = waitingForHandlerEnd.compareAndSet(false, true);
            if (firstUpdate) {
                getOrCreateActiveAggregates(activeAggregateContext).putIfAbsent(id().toString(), this);
            }
            try {
                delegate = update.apply(getDelegate());
            } finally {
                if (firstUpdate) {
                    Invocation.whenHandlerCompletes((r, e) -> whenHandlerCompletes(e));
                }
            }
        } finally {
            updating = false;
        }
        while (!queued.isEmpty()) {
            delegate = queued.removeFirst().apply(getDelegate());
        }
        return this;
    }

    protected void whenHandlerCompletes(Throwable error) {
        waitingForHandlerEnd.set(false);
        if (error == null) {
            uncommitted.addAll(applied);
            lastStable = delegate;
        } else {
            delegate = lastStable;
        }
        applied.clear();
        if (commitPolicy.commitAfterBatch()) {
            awaitBatchCompletion();
        } else {
            commit();
            if (commitPolicy.awaitAfterBatch()) {
                awaitBatchCompletion();
            } else {
                removeActiveAggregate();
            }
        }
    }

    protected void whenBatchCompletes(Throwable error) {
        waitingForBatchEnd.set(false);
        if (commitPolicy.commitAfterBatch()) {
            commit();
        }
        if (!awaitPendingBatchCompletionsAndRemoveActive()) {
            removeActiveAggregate();
        }
    }

    private void awaitBatchCompletion() {
        if (waitingForBatchEnd.compareAndSet(false, true)) {
            DeserializingMessage.whenBatchCompletes(this::whenBatchCompletes);
        }
    }

    @Override
    public Entity<T> commit() {
        if (committing) {
            commitPending = true;
            return this;
        }
        try {
            committing = true;
            commitPending = false;
            uncommitted.addAll(applied);
            applied.clear();
            lastStable = delegate;
            List<AppliedEvent> events = new ArrayList<>(uncommitted);
            uncommitted.clear();
            var before = lastCommitted;
            lastCommitted = lastStable;
            CompletableFuture<Void> completion = commitHandler.handle(lastStable, events, before);
            if (commitPolicy.async()) {
                if (!commitPolicy.commitAfterBatch() && commitPolicy.awaitAfterBatch()
                    && DeserializingMessage.getCurrent() != null) {
                    pendingBatchCompletions.add(completion);
                    if (awaitAfterHandlerCommitsBeforeResults) {
                        Invocation.awaitBeforeResultPublication(completion);
                    }
                    awaitBatchCompletion();
                } else if (AsyncCompletionScope.isActive()) {
                    AsyncCompletionScope.register(completion);
                } else {
                    completion.join();
                }
            } else {
                completion.join();
            }
        } finally {
            committing = false;
        }
        while (commitPending) {
            commit();
        }
        return this;
    }

    private boolean awaitPendingBatchCompletionsAndRemoveActive() {
        if (pendingBatchCompletions.isEmpty()) {
            return false;
        }
        List<CompletableFuture<Void>> completions = new ArrayList<>(pendingBatchCompletions);
        pendingBatchCompletions.clear();
        CompletableFuture<Void> completion = CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
        if (AsyncCompletionScope.isActive()) {
            AsyncCompletionScope.register(completion, this::removeActiveAggregate);
        } else {
            try {
                completion.join();
            } finally {
                removeActiveAggregate();
            }
        }
        return true;
    }

    private void removeActiveAggregate() {
        Map<Object, Map<String, ModifiableAggregateRoot<?>>> contexts = activeAggregateContexts.get();
        Map<String, ModifiableAggregateRoot<?>> activeAggregates = contexts.get(activeAggregateContext);
        if (activeAggregates == null) {
            return;
        }
        activeAggregates.remove(id().toString(), this);
        if (activeAggregates.isEmpty()) {
            contexts.remove(activeAggregateContext);
            if (contexts.isEmpty()) {
                activeAggregateContexts.remove();
            }
        }
    }

    private static Map<String, ModifiableAggregateRoot<?>> getActiveAggregates(Object context) {
        return activeAggregateContexts.get().get(context);
    }

    private static Map<String, ModifiableAggregateRoot<?>> getOrCreateActiveAggregates(Object context) {
        return activeAggregateContexts.get().computeIfAbsent(context, ignored -> new LinkedHashMap<>());
    }

    @Override
    public Collection<? extends Entity<?>> entities() {
        return super.entities().stream().map(e -> new ModifiableEntity<>(e, this)).collect(Collectors.toList());
    }

    @Override
    public Entity<T> previous() {
        Entity<T> previous = getDelegate().previous();
        return previous == null ? null : new ModifiableEntity<>(previous, this);
    }

    @FunctionalInterface
    public interface CommitHandler {
        /**
         * Commits the aggregate state and any unpublished events.
         *
         * @param model        aggregate state after the change
         * @param unpublished  events that must be committed
         * @param beforeUpdate aggregate state before the change
         * @return future that completes when all commit work has finished
         */
        CompletableFuture<Void> handle(Entity<?> model, List<AppliedEvent> unpublished, Entity<?> beforeUpdate);
    }

}
