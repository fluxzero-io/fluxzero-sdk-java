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

package io.fluxzero.sdk;

import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.caching.Cache;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.UuidFactory;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroConfiguration;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.spring.FluxzeroSpringConfig;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.DelegatingEntity;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelBatchScope;
import io.fluxzero.sdk.persisting.eventsourcing.EventStore;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.SnapshotStore;
import io.fluxzero.sdk.persisting.keyvalue.KeyValueStore;
import io.fluxzero.sdk.persisting.repository.AggregateRepository;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.persisting.search.BulkUpdateBuilder;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import io.fluxzero.sdk.persisting.search.IndexOperation;
import io.fluxzero.sdk.persisting.search.Search;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.publishing.ErrorGateway;
import io.fluxzero.sdk.publishing.EventGateway;
import io.fluxzero.sdk.publishing.GenericGateway;
import io.fluxzero.sdk.publishing.MetricsGateway;
import io.fluxzero.sdk.publishing.QueryGateway;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.publishing.WebRequestGateway;
import io.fluxzero.sdk.publishing.correlation.CorrelationDataProvider;
import io.fluxzero.sdk.publishing.correlation.DefaultCorrelationDataProvider;
import io.fluxzero.sdk.scheduling.MessageScheduler;
import io.fluxzero.sdk.scheduling.Periodic;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.Tracking;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.Request;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebRequestSettings;
import io.fluxzero.sdk.web.WebResponse;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.CUSTOM;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.ObjectUtils.rethrow;
import static io.fluxzero.common.reflection.ReflectionUtils.getCallerClass;
import static java.util.Arrays.stream;

/**
 * High-level entry point for all interactions with the Fluxzero Runtime.
 * <p>
 * This interface exposes static convenience methods to publish and track messages, interact with aggregates, schedule
 * tasks, index/search documents, and more. It is designed to reduce boilerplate and promote location transparency in
 * message-driven systems.
 * </p>
 *
 * <h2>Usage Patterns</h2>
 * <ul>
 *   <li>To send or publish messages, use static methods such as {@link #sendCommand(Object)} or {@link #publishEvent(Object)}.</li>
 *   <li>To track incoming messages, register handlers using {@link #registerHandlers(Object...)}.</li>
 *   <li>To interact with independent models, use {@link #loadModel(Id)} or {@link #modelRepository()}.</li>
 *   <li>To interact with legacy aggregates, use {@link #loadAggregate(Id)} or {@link #aggregateRepository()}.</li>
 * </ul>
 *
 * <p>
 * Most applications will never need to hold or inject a {@code Fluxzero} instance directly. Instead, the Java SDK
 * automatically binds the relevant instance to a thread-local scope, allowing access via static methods.
 * </p>
 *
 * <p>
 * A concrete instance is typically constructed using {@link DefaultFluxzero}.
 * </p>
 */
public interface Fluxzero extends AutoCloseable {

    /**
     * Fluxzero instance set by the current application. Used as a fallback when no threadlocal instance was set. This
     * is added as a convenience for applications that never have more than one than Fluxzero instance which will be the
     * case for nearly all applications. On application startup simply fill this application instance.
     */
    AtomicReference<Fluxzero> applicationInstance = new AtomicReference<>();

    /**
     * Thread-local binding of the current {@code Fluxzero} instance.
     * <p>
     * This is automatically set during message processing to ensure that handlers can invoke commands, queries, or
     * schedule events without explicitly injecting dependencies.
     * </p>
     *
     * <p>
     * Example: Inside a {@code @HandleCommand} method, you can call {@code Fluxzero.sendCommand(...)} and it will
     * automatically use the correct instance, without needing manual wiring.
     * </p>
     */
    ThreadLocal<Fluxzero> instance = ThreadLocalContext.create();

    /**
     * Returns the Fluxzero instance bound to the current thread or else set by the current application. Throws an
     * exception if no instance was registered.
     */
    static Fluxzero get() {
        return Optional.ofNullable(instance.get())
                .orElseGet(() -> Optional.ofNullable(applicationInstance.get())
                        .orElseThrow(() -> new IllegalStateException(
                                FluxzeroErrors.fluxzeroInstanceMissing().format())));
    }

    /**
     * Returns the Fluxzero client bound to the current thread or else set by the current application as Optional.
     * Returns an empty Optional if no instance was registered.
     */
    static Optional<Fluxzero> getOptionally() {
        Fluxzero result = instance.get();
        return result == null ? Optional.ofNullable(applicationInstance.get()) : Optional.of(result);
    }

    /**
     * Gets the clock of the current Fluxzero instance (obtained via {@link #getOptionally()}). If there is no current
     * instance the system's UTC clock is returned.
     */
    static Clock currentClock() {
        Fluxzero result = instance.get();
        if (result == null) {
            result = applicationInstance.get();
        }
        Clock clock = result == null ? null : result.clock();
        return clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Gets the time according to the current Fluxzero clock (obtained via {@link #currentClock()}). If there is no
     * current Fluxzero instance the system's UTC time is returned.
     */
    static Instant currentTime() {
        return currentClock().instant();
    }

    /**
     * Stores the given value in a memoization store that is scoped to the current Fluxzero instance and the calling
     * class.
     */
    static void memoize(Object key, Object value) {
        memoize(key, value, null);
    }

    /**
     * Stores the given value in a memoization store that is scoped to the current Fluxzero instance and the calling
     * class, evicting it after the given lifespan.
     */
    static void memoize(Object key, Object value, Duration lifespan) {
        get().memoization().put(getScopedMemoizationKey(getCallerClass(), key), value, lifespan);
    }

    /**
     * Stores the given value in a memoization store that is scoped only to the current Fluxzero instance.
     */
    static void memoizeGlobally(Object key, Object value) {
        memoizeGlobally(key, value, null);
    }

    /**
     * Stores the given value in a memoization store that is scoped only to the current Fluxzero instance, evicting it
     * after the given lifespan.
     */
    static void memoizeGlobally(Object key, Object value, Duration lifespan) {
        get().memoization().put(getGlobalMemoizationKey(key), value, lifespan);
    }

    /**
     * Computes and stores a memoized value in a scope bound to the current Fluxzero instance and the calling class.
     */
    static <K, V> V memoize(K key, BiFunction<K, V, V> supplier) {
        return memoize(key, supplier, null);
    }

    /**
     * Computes and stores a memoized value in a scope bound to the current Fluxzero instance and the calling class,
     * evicting it after the given lifespan.
     */
    static <K, V> V memoize(K key, BiFunction<K, V, V> supplier, Duration lifespan) {
        return get().memoization()
                .compute(getScopedMemoizationKey(getCallerClass(), key), key, supplier, lifespan);
    }

    /**
     * Computes and stores a memoized value in a scope bound only to the current Fluxzero instance.
     */
    static <K, V> V memoizeGlobally(K key, BiFunction<K, V, V> supplier) {
        return memoizeGlobally(key, supplier, null);
    }

    /**
     * Computes and stores a memoized value in a scope bound only to the current Fluxzero instance, evicting it after
     * the given lifespan.
     */
    static <K, V> V memoizeGlobally(K key, BiFunction<K, V, V> supplier, Duration lifespan) {
        return get().memoization()
                .compute(getGlobalMemoizationKey(key), key, supplier, lifespan);
    }

    /**
     * Returns the memoized value for the given key in the scope of the current calling class, computing it only when
     * absent or expired.
     */
    static <K, V> V memoizeIfAbsent(K key, Function<K, V> supplier) {
        return memoizeIfAbsent(key, supplier, null);
    }

    /**
     * Returns the memoized value for the given key in the scope of the current calling class, computing it only when
     * absent or expired and evicting it after the given lifespan.
     */
    static <K, V> V memoizeIfAbsent(K key, Function<K, V> supplier, Duration lifespan) {
        return get().memoization()
                .computeIfAbsent(getScopedMemoizationKey(getCallerClass(), key), key, supplier, lifespan);
    }

    /**
     * Returns the memoized value for the given key in the global scope of the current Fluxzero instance, computing it
     * only when absent or expired.
     */
    static <K, V> V memoizeGloballyIfAbsent(K key, Function<K, V> supplier) {
        return memoizeGloballyIfAbsent(key, supplier, null);
    }

    /**
     * Returns the memoized value for the given key in the global scope of the current Fluxzero instance, computing it
     * only when absent or expired and evicting it after the given lifespan.
     */
    static <K, V> V memoizeGloballyIfAbsent(K key, Function<K, V> supplier, Duration lifespan) {
        return get().memoization()
                .computeIfAbsent(getGlobalMemoizationKey(key), key, supplier, lifespan);
    }

    /**
     * Returns the memoized value for the given key in the scope of the current calling class.
     */
    static <K, V> V getMemoized(K key) {
        return get().memoization().get(getScopedMemoizationKey(getCallerClass(), key));
    }

    /**
     * Returns the memoized value for the given key in the global scope of the current Fluxzero instance.
     */
    static <K, V> V getGloballyMemoized(K key) {
        return get().memoization().get(getGlobalMemoizationKey(key));
    }

    private static MemoizationKey getScopedMemoizationKey(Class<?> scope, Object key) {
        return new MemoizationKey(scope, key);
    }

    private static MemoizationKey getGlobalMemoizationKey(Object key) {
        return new MemoizationKey(GlobalMemoizationScope.marker, key);
    }

    /**
     * Generates a functional ID using the current {@link IdentityProvider}. This is typically used for
     * application-level entities such as aggregates or user-defined messages.
     *
     * @return a unique, traceable identifier string
     */
    static String generateId() {
        return currentIdentityProvider().nextFunctionalId();
    }

    /**
     * Generates a strongly typed ID of given {@code idClass} using the current {@link IdentityProvider}.
     *
     * @return a unique, traceable typed identifier
     */
    static <T extends Id<?>> T generateId(Class<T> idClass) {
        return JsonUtils.convertValue(TextNode.valueOf(generateId()), idClass);
    }

    /**
     * Generates an ID derived from the given {@code name} using the current {@link IdentityProvider}.
     * <p>
     * For a given name, this method always returns the same identifier.
     *
     * @param name the name to derive the identifier from
     * @return a unique, traceable identifier string
     */
    static String idForName(String name) {
        return currentIdentityProvider().idForName(name);
    }

    /**
     * Fetches the configured identity provider used for both functional and technical IDs. The default is a
     * {@link UuidFactory} that generates UUIDs.
     * <p>
     * If there is no current Fluxzero instance, a new UUID factory is generated.
     */
    static IdentityProvider currentIdentityProvider() {
        return getOptionally().map(Fluxzero::identityProvider).orElseGet(UuidFactory::new);
    }

    /**
     * Gets the current correlation data, which by default depends on the current {@link Client}, {@link Tracker} and
     * {@link DeserializingMessage}
     */
    static Map<String, String> currentCorrelationData() {
        return getOptionally().map(Fluxzero::correlationDataProvider).orElse(
                DefaultCorrelationDataProvider.INSTANCE).getCorrelationData();
    }

    /**
     * Publishes the given application event. The event may be an instance of a {@link Message} in which case it will be
     * published as is. Otherwise the event is published using the passed value as payload without additional metadata.
     *
     * <p><strong>Note:</strong> These events are <em>not</em> persisted for event sourcing. To publish domain events
     * as part of an aggregate lifecycle, apply the events using {@link Entity#apply} after loading an entity.</p>
     *
     * @see #aggregateRepository() if you're interested in publishing events that belong to an aggregate.
     */
    static void publishEvent(Object event) {
        get().eventGateway().publish(event);
    }

    /**
     * Publishes an event with given payload and metadata.
     *
     * <p><strong>Note:</strong> These events are <em>not</em> persisted for event sourcing. To publish domain events
     * as part of an aggregate lifecycle, apply the events using {@link Entity#apply} after loading an entity.</p>
     *
     * @see #publishEvent(Object) for more info
     */
    static void publishEvent(Object payload, Metadata metadata) {
        get().eventGateway().publish(payload, metadata);
    }

    /**
     * Publishes given application events. The events may be instances of {@link Message} in which case they will be
     * published as is. Otherwise, the events are published using the passed value as payload without additional
     * metadata.
     * <p><strong>Note:</strong> These events are <em>not</em> persisted for event sourcing. To publish domain events
     * as part of an aggregate lifecycle, apply the events using {@link Entity#apply} after loading an entity.</p>
     *
     * @see #aggregateRepository() if you're interested in publishing events that belong to an aggregate.
     */
    static void publishEvents(Object... events) {
        get().eventGateway().publish(events);
    }

    /**
     * Sends the given command and doesn't wait for a result. The command may be an instance of a {@link Message} in
     * which case it will be sent as is. Otherwise the command is published using the passed value as payload without
     * additional metadata.
     *
     * @see #sendCommand(Object) to send a command and inspect its result asynchronously
     */
    static void sendAndForgetCommand(Object command) {
        get().commandGateway().sendAndForget(command);
    }

    /**
     * Sends the given commands and doesn't wait for results. Commands may be an instance of a {@link Message} in which
     * case it will be sent as is. Otherwise, the commands are published using the passed value as payload without
     * additional metadata.
     *
     * @see #sendCommands(Object...)  to send commands and inspect their results asynchronously
     */
    static void sendAndForgetCommands(Object... commands) {
        get().commandGateway().sendAndForget(commands);
    }

    /**
     * Sends a command with given payload and metadata and don't wait for a result.
     *
     * @see #sendCommand(Object, Metadata) to send a command and inspect its result
     */
    static void sendAndForgetCommand(Object payload, Metadata metadata) {
        get().commandGateway().sendAndForget(payload, metadata);
    }

    /**
     * Sends a command with given payload and metadata and don't wait for a result. With a guarantee the method will
     * wait for the command itself to be sent or stored.
     *
     * @see #sendCommand(Object, Metadata) to send a command and inspect its result
     */
    static void sendAndForgetCommand(Object payload, Metadata metadata, Guarantee guarantee) {
        get().commandGateway().sendAndForget(payload, metadata, guarantee);
    }


    /**
     * Sends the given command and returns a future that will be completed with the command's result. The command may be
     * an instance of a {@link Message} in which case it will be sent as is. Otherwise the command is published using
     * the passed value as payload without additional metadata.
     */
    static <R> CompletableFuture<R> sendCommand(Object command) {
        return get().commandGateway().send(command);
    }

    /**
     * Sends the given command and returns a future that will be completed with the command's result. The command may be
     * an instance of a {@link Message} in which case it will be sent as is. Otherwise the command is published using
     * the passed value as payload without additional metadata.
     * <p>
     * The return type is determined by the given command.
     */
    static <R> CompletableFuture<R> sendCommand(Request<R> command) {
        return get().commandGateway().send(command);
    }

    /**
     * Sends the given commands and returns a list of futures that will be completed with the commands' results. The
     * commands may be instances of a {@link Message} in which case they will be sent as is. Otherwise, the commands are
     * published using the passed values as payload without additional metadata.
     */
    static <R> List<CompletableFuture<R>> sendCommands(Object... commands) {
        return get().commandGateway().send(commands);
    }

    /**
     * Sends a command with given payload and metadata and returns a future that will be completed with the command's
     * result.
     */
    static <R> CompletableFuture<R> sendCommand(Object payload, Metadata metadata) {
        return get().commandGateway().send(payload, metadata);
    }

    /**
     * Sends a command with given payload and metadata and returns a future that will be completed with the command's
     * result.
     * <p>
     * The return type is determined by the given command.
     */
    static <R> CompletableFuture<R> sendCommand(Request<R> payload, Metadata metadata) {
        return get().commandGateway().send(payload, metadata);
    }

    /**
     * Sends the given command and returns the command's result. The command may be an instance of a {@link Message} in
     * which case it will be sent as is. Otherwise, the command is published using the passed value as payload without
     * additional metadata.
     */
    static <R> R sendCommandAndWait(Object command) {
        return get().commandGateway().sendAndWait(command);
    }

    /**
     * Sends the given command and returns the command's result. The command may be an instance of a {@link Message} in
     * which case it will be sent as is. Otherwise, the command is published using the passed value as payload without
     * additional metadata.
     * <p>
     * The return type is determined by the given command.
     */
    static <R> R sendCommandAndWait(Request<R> command) {
        return get().commandGateway().sendAndWait(command);
    }

    /**
     * Sends a command with given payload and metadata and returns a future that will be completed with the command's
     * result.
     */
    static <R> R sendCommandAndWait(Object payload, Metadata metadata) {
        return get().commandGateway().sendAndWait(payload, metadata);
    }

    /**
     * Sends a command with given payload and metadata and returns a future that will be completed with the command's
     * result.
     * <p>
     * The return type is determined by the given command.
     */
    static <R> R sendCommandAndWait(Request<R> payload, Metadata metadata) {
        return get().commandGateway().sendAndWait(payload, metadata);
    }

    /**
     * Runs apply interceptors and immediate model assertions declared for the given update without invoking applies or
     * committing model changes.
     * <p>
     * Assertions marked with {@link io.fluxzero.sdk.modeling.AssertLegal#afterHandler()} are not invoked because this
     * validation-only operation does not produce a post-apply model state. This enters the model pipeline directly and
     * does not dispatch the update as a command.
     *
     * @param update the update payload or message to validate
     */
    static void assertLegal(Object update) {
        awaitModelCommit(get().executeModelAssertions(modelMessage(update)));
    }

    /**
     * Runs apply interceptors and immediate model assertions with the supplied metadata, without invoking applies or
     * committing model changes.
     *
     * @param update   the update payload to validate
     * @param metadata metadata available to interceptors and assertions
     */
    static void assertLegal(Object update, Metadata metadata) {
        Message message = modelMessage(update);
        awaitModelCommit(get().executeModelAssertions(
                message.withMetadata(message.getMetadata().with(metadata))));
    }

    /**
     * Runs the model assertions, apply interceptors, and applies declared for the given update and waits until the
     * resulting model commit has been committed. If this application has no locally reachable model apply, immediate
     * assertions and apply interceptors still run, after which the call logs a warning and returns without committing.
     * <p>
     * This enters the model-commit pipeline directly. It does not dispatch the update as a command and therefore can
     * safely be called from an explicit {@link HandleCommand} handler for the same payload type.
     *
     * @param update the update payload or message to assert and apply
     */
    static void assertAndApply(Object update) {
        awaitModelCommit(get().executeModelCommit(modelMessage(update)));
    }

    /**
     * Runs and commits a model commit with the supplied metadata.
     *
     * @param update   the update payload to assert and apply
     * @param metadata metadata to attach to the model event
     */
    static void assertAndApply(Object update, Metadata metadata) {
        Message message = modelMessage(update);
        awaitModelCommit(get().executeModelCommit(
                message.withMetadata(message.getMetadata().with(metadata))));
    }

    /**
     * Commits Model changes already produced in the current handling context without waiting for its normal automatic
     * commit boundary.
     * <p>
     * This is an optional early flush of the existing Model commit, not a separate mutation or commit path. The
     * returned future is the existing durable commit completion and therefore carries the same success or failure.
     * When the current context has no pending Model changes, the method returns an already completed future and sends
     * nothing to the Runtime. Automatic committing remains active and observes the same completion.
     *
     * @return completion of the current durable Model commit, or completed completion when no changes are pending
     */
    static CompletableFuture<Void> commit() {
        return get().commitModelChanges();
    }

    /**
     * Runs and commits an update against the explicitly selected model graph, independently of any model ID carried by
     * the update payload. Interceptors, assertions, applies, event publication, conflict handling and commit guarantees
     * are otherwise identical to {@link #assertAndApply(Object)}.
     * <p>
     * Prefer {@link Graph#assertAndApply(Object)} in application code. This overload owns the direct model-pipeline
     * bridge used by that convenience.
     *
     * @param target explicitly selected model graph
     * @param update update payload or message to assert and apply
     * @return the freshly loaded graph after the durable commit
     */
    static <T> Graph<T> assertAndApply(Graph<T> target, Object update) {
        Objects.requireNonNull(target, "target");
        String repositoryId = target.id().toString();
        awaitModelCommit(get().executeModelCommit(
                modelMessage(update), repositoryId, target.type()));
        return io.fluxzero.sdk.modeling.Graphs.lazyRepositoryId(
                repositoryId, target.type(), currentModelRepository());
    }

    /**
     * Runs and commits an update with additional metadata against the explicitly selected model graph.
     *
     * @see #assertAndApply(Graph, Object)
     */
    static <T> Graph<T> assertAndApply(
            Graph<T> target, Object update, Metadata metadata) {
        Objects.requireNonNull(target, "target");
        String repositoryId = target.id().toString();
        Message message = modelMessage(update);
        awaitModelCommit(get().executeModelCommit(
                message.withMetadata(message.getMetadata().with(metadata)),
                repositoryId, target.type()));
        return io.fluxzero.sdk.modeling.Graphs.lazyRepositoryId(
                repositoryId, target.type(), currentModelRepository());
    }

    /**
     * Runs the model assertions, apply interceptors, and applies declared for the given update without blocking the
     * caller, and completes after the resulting model commit has been durably stored.
     * <p>
     * The update is converted to a message before this method returns, so metadata and context inherited from the
     * current handler remain available to the asynchronous commit pipeline.
     *
     * @param update the update payload or message to assert and apply
     * @return completion of the durable model commit
     */
    static CompletableFuture<Void> assertAndApplyAsync(Object update) {
        return startModelCommit(get(), modelMessage(update));
    }

    /**
     * Runs and commits a model commit asynchronously with the supplied metadata.
     *
     * @param update   the update payload to assert and apply
     * @param metadata metadata to attach to the model event
     * @return completion of the durable model commit
     */
    static CompletableFuture<Void> assertAndApplyAsync(Object update, Metadata metadata) {
        Message message = modelMessage(update);
        return startModelCommit(
                get(), message.withMetadata(message.getMetadata().with(metadata)));
    }

    /**
     * Runs and commits multiple independent model updates asynchronously. Every update remains a separate model commit
     * with its own conflict handling and durability boundary. Implementations may batch commits that become ready
     * together for transport, without making the updates atomic or delaying already-full transport batches.
     * <p>
     * All updates are converted to messages before this method returns, so metadata and context inherited from the
     * current handler remain available to every asynchronous commit pipeline.
     *
     * @param updates independent update payloads or messages to assert and apply
     * @return completion after every resulting model commit has been durably stored
     */
    static CompletableFuture<Void> assertAndApplyAllAsync(Collection<?> updates) {
        Objects.requireNonNull(updates, "updates");
        List<Message> messages = updates.stream()
                .map(update -> modelMessage(Objects.requireNonNull(update, "update")))
                .toList();
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Fluxzero fluxzero = get();
        ThreadLocalContext.Snapshot context = ThreadLocalContext.capture();
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Fluxzero-model-commit-batch").start(context.wrap(() -> {
            try {
                fluxzero.executeModelCommits(messages).whenComplete(context.wrap((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(failure);
                    }
                }));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }));
        return result;
    }

    private static CompletableFuture<Void> startModelCommit(Fluxzero fluxzero, Message message) {
        ThreadLocalContext.Snapshot context = ThreadLocalContext.capture();
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Fluxzero-model-commit").start(context.wrap(() -> {
            try {
                fluxzero.executeModelCommit(message).whenComplete(context.wrap((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(failure);
                    }
                }));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }));
        return result;
    }

    private static Message modelMessage(Object update) {
        if (update instanceof HasMessage) {
            return Message.asMessage(update);
        }
        DeserializingMessage current = DeserializingMessage.getCurrent();
        return current == null ? Message.asMessage(update)
                : new Message(update, current.getMetadata(), null, current.getTimestamp());
    }

    private static void awaitModelCommit(CompletableFuture<Void> completion) {
        try {
            completion.join();
        } catch (CompletionException e) {
            throw rethrow(e);
        }
    }

    /**
     * Sends the given query and returns a future that will be completed with the query's result. The query may be an
     * instance of a {@link Message} in which case it will be sent as is. Otherwise, the query is published using the
     * passed value as payload without additional metadata.
     */
    static <R> CompletableFuture<R> query(Object query) {
        return get().queryGateway().send(query);
    }

    /**
     * Sends the given query and returns a future that will be completed with the query's result. The query may be an
     * instance of a {@link Message} in which case it will be sent as is. Otherwise, the query is published using the
     * passed value as payload without additional metadata.
     * <p>
     * The return type is determined by the given query.
     */
    static <R> CompletableFuture<R> query(Request<R> query) {
        return get().queryGateway().send(query);
    }

    /**
     * Sends a query with given payload and metadata and returns a future that will be completed with the query's
     * result.
     */
    static <R> CompletableFuture<R> query(Object payload, Metadata metadata) {
        return get().queryGateway().send(payload, metadata);
    }

    /**
     * Sends a query with given payload and metadata and returns a future that will be completed with the query's
     * result.
     * <p>
     * The return type is determined by the given query.
     */
    static <R> CompletableFuture<R> query(Request<R> payload, Metadata metadata) {
        return get().queryGateway().send(payload, metadata);
    }

    /**
     * Sends the given query and returns the query's result. The query may be an instance of a {@link Message} in which
     * case it will be sent as is. Otherwise, the query is published using the passed value as payload without
     * additional metadata.
     */
    static <R> R queryAndWait(Object query) {
        return get().queryGateway().sendAndWait(query);
    }

    /**
     * Sends the given query and returns the query's result. The query may be an instance of a {@link Message} in which
     * case it will be sent as is. Otherwise, the query is published using the passed value as payload without
     * additional metadata.
     * <p>
     * The return type is determined by the given query.
     */
    static <R> R queryAndWait(Request<R> query) {
        return get().queryGateway().sendAndWait(query);
    }

    /**
     * Sends a query with given payload and metadata and returns the query's result.
     */
    static <R> R queryAndWait(Object payload, Metadata metadata) {
        return get().queryGateway().sendAndWait(payload, metadata);
    }

    /**
     * Sends a query with given payload and metadata and returns the query's result.
     * <p>
     * The return type is determined by the given query.
     */
    static <R> R queryAndWait(Request<R> payload, Metadata metadata) {
        return get().queryGateway().sendAndWait(payload, metadata);
    }

    /**
     * Starts a new periodic schedule, returning the schedule's id. The {@code schedule} parameter may be an instance of
     * a {@link Message} or the schedule payload. If the payload is not annotated with {@link Periodic} an
     * {@link IllegalArgumentException} is thrown.
     *
     * @see Periodic
     */
    static String schedulePeriodic(Object schedule) {
        return get().messageScheduler().schedulePeriodic(schedule);
    }

    /**
     * Starts a new periodic schedule using given schedule id. The {@code schedule} parameter may be an instance of a
     * {@link Message} or the schedule payload. If the payload is not annotated with {@link Periodic} an
     * {@link IllegalArgumentException} is thrown.
     *
     * @see Periodic
     */
    static void schedulePeriodic(Object schedule, Object scheduleId) {
        get().messageScheduler().schedulePeriodic(schedule, scheduleId);
    }

    /**
     * Schedules a message for the given timestamp, returning the schedule's id. The {@code schedule} parameter may be
     * an instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the schedule is published
     * using the passed value as payload without additional metadata.
     */
    static String schedule(Object schedule, Instant deadline) {
        return get().messageScheduler().schedule(schedule, deadline);
    }

    /**
     * Schedules a message with given {@code scheduleId} for the given timestamp. The {@code schedule} parameter may be
     * an instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the schedule is published
     * using the passed value as payload without additional metadata.
     */
    static void schedule(Object schedule, Object scheduleId, Instant deadline) {
        get().messageScheduler().schedule(schedule, scheduleId, deadline);
    }

    /**
     * Schedules a message after the given delay, returning the schedule's id. The {@code schedule} parameter may be an
     * instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the schedule is published
     * using the passed value as payload without additional metadata.
     */
    static String schedule(Object schedule, Duration delay) {
        return get().messageScheduler().schedule(schedule, delay);
    }

    /**
     * Schedules a message with given {@code scheduleId} after given delay. The {@code schedule} parameter may be an
     * instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the schedule is published
     * using the passed value as payload without additional metadata.
     */
    static void schedule(Object schedule, Object scheduleId, Duration delay) {
        get().messageScheduler().schedule(schedule, scheduleId, delay);
    }

    /**
     * Schedule a message object (of type {@link Schedule}) for execution, using the {@link Guarantee#SENT} guarantee.
     *
     * @param schedule the message to schedule
     */
    static void schedule(Schedule schedule) {
        get().messageScheduler().schedule(schedule);
    }

    /**
     * Schedule a message object (of type {@link Schedule}) for execution, using the {@link Guarantee#SENT} guarantee.
     *
     * @param schedule the message to schedule
     */
    static void schedule(Schedule schedule, boolean ifAbsent) {
        get().messageScheduler().schedule(schedule, ifAbsent);
    }

    /**
     * Schedules a command for the given timestamp, returning the command schedule's id. The {@code command} parameter
     * may be an instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the command is
     * scheduled using the passed value as payload without additional metadata.
     */
    static String scheduleCommand(Object command, Instant deadline) {
        return get().messageScheduler().scheduleCommand(command, deadline);
    }

    /**
     * Schedules a command with given {@code scheduleId} for the given timestamp. The {@code command} parameter may be
     * an instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the command is published
     * using the passed value as payload without additional metadata.
     */
    static void scheduleCommand(Object command, Object scheduleId, Instant deadline) {
        get().messageScheduler().scheduleCommand(command, scheduleId, deadline);
    }

    /**
     * Schedules a command after given delay, returning the command schedule's id. The {@code command} parameter may be
     * an instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the command is scheduled
     * using the passed value as payload without additional metadata.
     */
    static String scheduleCommand(Object command, Duration delay) {
        return get().messageScheduler().scheduleCommand(command, delay);
    }

    /**
     * Schedules a command with given {@code scheduleId} after given delay. The {@code command} parameter may be an
     * instance of a {@link Message} in which case it will be scheduled as is. Otherwise, the command is published using
     * the passed value as payload without additional metadata.
     */
    static void scheduleCommand(Object command, Object scheduleId, Duration delay) {
        get().messageScheduler().scheduleCommand(command, scheduleId, delay);
    }

    /**
     * Schedule a command using the given scheduling settings, using the {@link Guarantee#SENT} guarantee.
     */
    static void scheduleCommand(Schedule message) {
        get().messageScheduler().scheduleCommand(message);
    }

    /**
     * Schedule a command using the given scheduling settings if no other with same ID exists, using the
     * {@link Guarantee#SENT} guarantee.
     */
    static void scheduleCommand(Schedule message, boolean ifAbsent) {
        get().messageScheduler().scheduleCommand(message, ifAbsent);
    }

    /**
     * Cancels the schedule with given {@code scheduleId}.
     */
    static void cancelSchedule(Object scheduleId) {
        get().messageScheduler().cancelSchedule(scheduleId);
    }

    /**
     * Sends the given web request using default request settings and returns a future that completes with the
     * response.
     * <p>
     * The request must have an absolute URL to be forwarded by the Fluxzero proxy.
     */
    static CompletableFuture<WebResponse> sendWebRequest(WebRequest request) {
        return get().webRequestGateway().send(request);
    }

    /**
     * Sends the given web request using the given request settings and returns a future that completes with the
     * response.
     * <p>
     * The request must have an absolute URL to be forwarded by the Fluxzero proxy.
     */
    static CompletableFuture<WebResponse> sendWebRequest(WebRequest request, WebRequestSettings settings) {
        return get().webRequestGateway().send(request, settings);
    }

    /**
     * Sends the given web request using default request settings and waits for the response synchronously.
     * <p>
     * This method blocks the calling thread until the request is completed or times out.
     * <p>
     * The request must have an absolute URL to be forwarded by the Fluxzero proxy.
     */
    static WebResponse sendWebRequestAndWait(WebRequest request) {
        return get().webRequestGateway().sendAndWait(request);
    }

    /**
     * Sends the given web request using given request settings and waits for the response synchronously.
     * <p>
     * This method blocks the calling thread until the request is completed or times out.
     * <p>
     * The request must have an absolute URL to be forwarded by the Fluxzero proxy.
     */
    static WebResponse sendWebRequestAndWait(WebRequest request, WebRequestSettings settings) {
        return get().webRequestGateway().sendAndWait(request, settings);
    }

    /**
     * Publishes a metrics event. The parameter may be an instance of a {@link Message} in which case it will be sent as
     * is. Otherwise the metrics event is published using the passed value as payload without additional metadata.
     * <p>
     * Metrics events can be published in any form to log custom performance metrics about an application.
     */
    static void publishMetrics(Object metrics) {
        get().metricsGateway().publish(metrics);
    }

    /**
     * Publishes a metrics event with given payload and metadata. Metrics events can be published in any form to log
     * custom performance metrics about an application.
     */
    static void publishMetrics(Object payload, Metadata metadata) {
        get().metricsGateway().publish(payload, metadata, Guarantee.NONE);
    }

    /**
     * Loads the aggregate root of type {@code <T>} with given aggregateId.
     * <p>
     * If the aggregate is loaded while handling an event of the aggregate, the returned Aggregate will automatically be
     * played back to the event currently being handled. Otherwise, the most recent state of the aggregate is loaded.
     * Typed identifiers whose declared type is an independent {@link Model} are delegated to the model repository. This
     * preserves source-compatible typed loads while migrating an aggregate root to an independent model.
     *
     * @see Aggregate for more info on how to define an event-sourced aggregate root
     */
    static <T> Entity<T> loadAggregate(Id<T> aggregateId) {
        if (aggregateId.getType().isAnnotationPresent(Model.class)) {
            return legacyModelEntity(loadModel(aggregateId), aggregateId, aggregateId.getType());
        }
        return playbackToHandledMessage(get().aggregateRepository().load(aggregateId));
    }

    /**
     * Loads the aggregate root with the given aggregateId. If the aggregate exists, it will be loaded and returned with
     * its respective type, if not, an empty {@link Entity} of type {@link Object} will be returned.
     * <p>
     * If the aggregate is loaded while handling an event of the aggregate, the returned Aggregate will automatically be
     * played back to the event currently being handled. Otherwise, the most recent state of the aggregate is loaded.
     *
     * @see Aggregate for more info on how to define an event-sourced aggregate root
     */
    static <T> Entity<T> loadAggregate(Object aggregateId) {
        return playbackToHandledMessage(get().aggregateRepository().load(aggregateId));
    }

    /**
     * Loads the aggregate root of type {@code <T>} with given aggregateId.
     * <p>
     * If the aggregate is loaded while handling an event of the aggregate, the returned Aggregate will automatically be
     * played back to the event currently being handled. Otherwise, the most recent state of the aggregate is loaded.
     *
     * @see Aggregate for more info on how to define an event-sourced aggregate root
     */
    static <T> Entity<T> loadAggregate(Object aggregateId, Class<T> aggregateType) {
        if (aggregateType.isAnnotationPresent(Model.class)) {
            return legacyModelEntity(loadModel(aggregateId, aggregateType), aggregateId, aggregateType);
        }
        return playbackToHandledMessage(get().aggregateRepository().load(aggregateId, aggregateType));
    }

    /**
     * Loads the independently stored model identified by the given typed ID.
     * <p>
     * The typed ID's repository representation is wrapped in any prefix or postfix declared by the model's
     * {@link EntityId @EntityId}. When no primary model has that identity, a current {@link Alias @Alias} value may
     * resolve the model instead.
     */
    static <T> Entity<T> loadModel(Id<T> modelId) {
        return currentModelRepository().load(modelId);
    }

    /**
     * Loads an independently stored model by ID. Typed IDs provide the requested model type; untyped IDs let the
     * repository resolve the stored type.
     * <p>
     * A primary model ID is tried first, followed by a current {@link Alias @Alias} value.
     */
    static <T> Entity<T> loadModel(Object modelId) {
        return currentModelRepository().load(modelId);
    }

    /**
     * Loads an independently stored model by ID and expected type.
     * <p>
     * A primary model ID is tried first, followed by a current {@link Alias @Alias} value.
     */
    static <T> Entity<T> loadModel(Object modelId, Class<T> modelType) {
        return currentModelRepository().load(modelId, modelType);
    }

    /**
     * Loads a parent-scoped model by functional child ID and explicit parent type.
     */
    static <T> Entity<T> loadModel(
            Object parentId, Class<?> parentType,
            Object modelId, Class<T> modelType) {
        return currentModelRepository().load(parentId, parentType, modelId, modelType);
    }

    /**
     * Lazily loads the independently stored model identified by the typed ID as a relationship graph.
     * <p>
     * The source model is loaded only when its value, history or relationship contents are requested. A typed ancestor
     * lookup can normally resolve directly from stored relationship identities.
     */
    static <T> Graph<T> loadGraph(Id<T> modelId) {
        return io.fluxzero.sdk.modeling.Graphs.lazy(
                modelId, modelId.getType(), currentModelRepository());
    }

    /**
     * Loads a model whose concrete type is resolved from storage as a lazy relationship graph. The source must be
     * loaded once to discover that type; subsequent relationship navigation uses the ordinary graph API.
     */
    static Graph<?> loadGraph(Object modelId) {
        Entity<?> entity = loadModel(modelId);
        long stateIndex = entity instanceof io.fluxzero.sdk.modeling.ModelRoot<?> root
                ? root.stateIndex() : -1L;
        return io.fluxzero.sdk.modeling.Graphs.lazy(
                entity, stateIndex, currentModelRepository());
    }

    /**
     * Lazily loads an independently stored model by ID and expected type as a relationship graph.
     */
    static <T> Graph<T> loadGraph(Object modelId, Class<T> modelType) {
        return io.fluxzero.sdk.modeling.Graphs.lazy(
                modelId, modelType, currentModelRepository());
    }

    /**
     * Loads the latest state of an independently stored model as a relationship graph, without inheriting an event or
     * notification handler's historical read boundary.
     * <p>
     * Use this after a synchronous nested command when the remainder of the handler deliberately needs that command's
     * updated Model state. Ordinary {@link #loadGraph(Object, Class)} reads remain coherent with the message being
     * handled and are therefore preferred everywhere else.
     */
    static <T> Graph<T> loadCurrentGraph(Object modelId, Class<T> modelType) {
        return io.fluxzero.sdk.modeling.Graphs.lazyCurrent(
                modelId, modelType, currentModelRepository());
    }

    /**
     * Loads the latest state of the independently stored model identified by the typed ID as a relationship graph.
     *
     * @see #loadCurrentGraph(Object, Class)
     */
    static <T> Graph<T> loadCurrentGraph(Id<T> modelId) {
        return loadCurrentGraph(modelId, modelId.getType());
    }

    /**
     * Lazily loads a parent-scoped model by functional child ID and explicit parent type as a relationship graph.
     */
    static <T> Graph<T> loadGraph(
            Object parentId, Class<?> parentType,
            Object modelId, Class<T> modelType) {
        return io.fluxzero.sdk.modeling.Graphs.lazy(
                parentId, parentType, modelId, modelType, currentModelRepository());
    }

    /**
     * Reconstructs a complete historical graph at the requested durable model-state boundary.
     */
    static <T> Graph<T> loadGraphAt(Id<T> modelId, long stateIndex) {
        return currentModelRepository().loadGraphAt(modelId, stateIndex);
    }

    /**
     * Loads several independently stored models at one coherent state boundary.
     *
     * <p>The native repository batches stream I/O and reconstruction while preserving input order.</p>
     */
    static <T> List<Entity<T>> loadModels(
            List<?> modelIds, Class<T> modelType) {
        return currentModelRepository().loadAll(modelIds, modelType);
    }

    private static ModelRepository currentModelRepository() {
        ModelRepository repository = get().modelRepository();
        DeserializingMessage message = DeserializingMessage.getCurrent();
        return message == null
                ? repository
                : repository.forNamespace(
                        ClientUtils.getConsumerNamespace(message));
    }

    /**
     * Loads the aggregate root of type {@code <T>} that currently contains the entity with given entityId. If no such
     * aggregate exists an empty aggregate root is returned with given {@code defaultType} as its type.
     * <p>
     * This method can also be used if the entity is the aggregate root (aggregateId is equal to entityId). If the
     * entity is associated with more than one aggregate the behavior of this method is unpredictable, though the
     * default behavior is that any one of the associated aggregates is returned.
     * <p>
     * If the aggregate is loaded while handling an event of the aggregate, the returned Aggregate will automatically be
     * played back to the event currently being handled. Otherwise, the most recent state of the aggregate is loaded.
     *
     * @see Aggregate for more info on how to define an event-sourced aggregate root
     */
    static <T> Entity<T> loadAggregateFor(Object entityId, Class<?> defaultType) {
        return playbackToHandledMessage(get().aggregateRepository().loadFor(entityId, defaultType));
    }

    /**
     * Loads the aggregate root that currently contains the entity with given entityId. If no such aggregate exists an
     * empty aggregate root is returned of type {@code Object}. In that case be aware that applying events to create the
     * aggregate may yield an undesired result; to prevent this use {@link #loadAggregateFor(Object, Class)}.
     * <p>
     * This method can also be used if the entity is the aggregate root (aggregateId is equal to entityId). If the
     * entity is associated with more than one aggregate the behavior of this method is unpredictable, though the
     * default behavior is that any one of the associated aggregates is returned.
     * <p>
     * If the aggregate is loaded while handling an event of the aggregate, the returned Aggregate will automatically be
     * played back to the event currently being handled. Otherwise, the most recent state of the aggregate is loaded.
     *
     * @see Aggregate for more info on how to define an event-sourced aggregate root
     */
    static <T> Entity<T> loadAggregateFor(Object entityId) {
        return loadAggregateFor(entityId, entityId instanceof Id<?> id ? id.getType() : Object.class);
    }

    /**
     * Loads the entity with given id. If the entity is not associated with any aggregate yet, a new aggregate root is
     * loaded with the entityId as aggregate identifier. In case multiple entities are associated with the given
     * entityId the most recent entity is returned.
     * <p>
     * If the entity is loaded while handling an event its aggregate, the returned entity will automatically be played
     * back to the event currently being handled. Otherwise, the most recent state of the entity is loaded.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> Entity<T> loadEntity(Object entityId) {
        if (entityId instanceof Id<?> id) {
            return (Entity<T>) loadEntity(id);
        }
        try {
            Entity<T> aggregateEntity = (Entity<T>) loadAggregateFor(entityId).getEntity(entityId)
                    .orElseGet(() -> entityId instanceof Id id
                            ? loadAggregate(id) : loadAggregate(entityId.toString(), Object.class));
            if (!aggregateEntity.isEmpty()) {
                return aggregateEntity;
            }
            try {
                return loadModel(entityId);
            } catch (EventSourcingException ignored) {
                return aggregateEntity;
            }
        } catch (EventSourcingException aggregateFailure) {
            try {
                return loadModel(entityId);
            } catch (EventSourcingException modelFailure) {
                aggregateFailure.addSuppressed(modelFailure);
                throw aggregateFailure;
            }
        }
    }

    /**
     * Loads the entity with given id. If the entity is not associated with any aggregate yet, a new aggregate root is
     * loaded with the entityId as aggregate identifier. In case multiple entities are associated with the given
     * entityId the most recent entity is returned.
     * <p>
     * If the entity is loaded while handling an event its aggregate, the returned entity will automatically be played
     * back to the event currently being handled. Otherwise, the most recent state of the entity is loaded.
     */
    static <T> Entity<T> loadEntity(Id<T> entityId) {
        if (entityId.getType().isAnnotationPresent(Model.class)) {
            return loadModel(entityId);
        }
        return loadEntity(entityId, entityId.getType());
    }

    /**
     * Loads an entity by functional ID and expected type. The type makes {@link EntityId} affixes available for
     * independent models and entities embedded in legacy aggregates.
     */
    static <T> Entity<T> loadEntity(Object entityId, Class<T> entityType) {
        if (entityType.isAnnotationPresent(Model.class)) {
            return loadModel(entityId, entityType);
        }
        String repositoryId = io.fluxzero.sdk.modeling.EntityMetadata.of(entityType).repositoryId(entityId);
        return loadAggregateFor(repositoryId).getEntity(entityId, entityType)
                .orElseGet(() -> loadAggregate(entityId, entityType));
    }

    private static <T> Entity<T> legacyModelEntity(
            Entity<T> initial, Object modelId, Class<T> modelType) {
        return new DelegatingEntity<>(initial) {
            @Override
            public Entity<T> update(java.util.function.UnaryOperator<T> function) {
                delegate = delegate.update(function);
                return this;
            }

            @Override
            public Entity<T> apply(Message eventMessage) {
                Fluxzero.get().executeStoredModelEvent(eventMessage).join();
                delegate = Fluxzero.loadModel(modelId, modelType);
                return this;
            }

            @Override
            public <E extends Exception> Entity<T> assertLegal(Object update) throws E {
                Fluxzero.assertLegal(update);
                return this;
            }

            @Override
            public Entity<T> commit() {
                return this;
            }
        };
    }

    /**
     * Loads the current entity value for given entity id. Entity may be the aggregate root or any ancestral entity. If
     * no such entity exists or its value is not set {@code null} is returned.
     * <p>
     * If the entity is loaded while handling an event its aggregate, the returned entity will automatically be played
     * back to the event currently being handled. Otherwise, the most recent state of the entity is loaded.
     */
    @SuppressWarnings("unchecked")
    static <T> T loadEntityValue(Object entityId) {
        return (T) loadAggregateFor(entityId).getEntity(entityId).map(Entity::get).orElse(null);
    }

    /**
     * Loads the current entity value for given entity id. Entity may be the aggregate root or any ancestral entity. If
     * no such entity exists or its value is not set {@code null} is returned.
     * <p>
     * If the entity is loaded while handling an event its aggregate, the returned entity will automatically be played
     * back to the event currently being handled. Otherwise, the most recent state of the entity is loaded.
     */
    @SuppressWarnings("unchecked")
    static <T> T loadEntityValue(Id<T> entityId) {
        return loadEntity(entityId).get();
    }

    /** Loads an entity value by functional ID and expected type. */
    static <T> T loadEntityValue(Object entityId, Class<T> entityType) {
        return loadEntity(entityId, entityType).get();
    }

    /**
     * Returns an Entity containing given value. The returned entity won't exhibit any side effects when they are
     * updated, i.e. they won't be synced to any repository or give rise to any events. Other than, that they are fully
     * functional.
     */
    static <T> Entity<T> asEntity(T value) {
        return get().aggregateRepository().asEntity(value);
    }

    private static <T> Entity<T> playbackToHandledMessage(Entity<T> entity) {
        DeserializingMessage message = DeserializingMessage.getCurrent();
        if (message != null && (message.getMessageType() == EVENT || message.getMessageType() == NOTIFICATION)
            && !Entity.isApplying()
            && entity.id().toString().equals(Entity.getAggregateId(message))
            && entity.rootConfiguration().eventSourced()
            && entity.sequenceNumber() >= 0L) {
            return entity.playBackToEvent(message.getIndex(), message.getMessageId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Could not load entity %s of type %s for event %s. Entity (%s) started at event %s"
                                    .formatted(entity.id(), entity.type().getSimpleName(), message.getIndex(),
                                               entity, entity.lastEventIndex())));
        }
        return entity;
    }

    /**
     * Prepare given object for indexing for search. This returns a mutable builder that allows defining an id,
     * collection, etc.
     * <p>
     * If the object is annotated with {@link Searchable @Searchable} the collection name and any timestamp or end path
     * defined there will be used.
     * <p>
     * If the object has a property annotated with {@link EntityId}, it will be used as the id of the document.
     * Otherwise, a random id will be assigned to the document.
     * <p>
     * This method returns once the object is stored.
     *
     * @see DocumentStore for more advanced uses.
     * @see Searchable for ways to define collection name etc
     */
    static IndexOperation prepareIndex(@NonNull Object object) {
        return get().documentStore().prepareIndex(object);
    }

    /**
     * Index given object for search.
     * <p>
     * If the object is annotated with {@link Searchable @Searchable} the collection name and any timestamp or end path
     * defined there will be used.
     * <p>
     * If the object has a property annotated with {@link EntityId}, it will be used as the id of the document.
     * Otherwise, a random id will be assigned to the document.
     * <p>
     * This method returns once the object is stored.
     *
     * @see DocumentStore for more advanced uses.
     * @see Searchable for ways to define collection name etc
     */
    static CompletableFuture<Void> index(Object object) {
        return get().documentStore().index(object);
    }

    /**
     * Index given object for search.
     * <p>
     * If the object has a property annotated with {@link EntityId}, it will be used as the id of the document.
     * Otherwise, a random id will be assigned to the document.
     * <p>
     * This method returns once the object is stored.
     *
     * @see DocumentStore for more advanced uses.
     */
    static CompletableFuture<Void> index(Object object, Object collection) {
        return get().documentStore().index(object, collection);
    }

    /**
     * Index given object for search. This method returns once the object is stored.
     *
     * @see DocumentStore for more advanced uses.
     */
    static CompletableFuture<Void> index(Object object, Object id, Object collection) {
        return get().documentStore().index(object, id, collection);
    }

    /**
     * Index given object for search. This method returns once the object is stored.
     *
     * @see DocumentStore for more advanced uses.
     */
    static CompletableFuture<Void> index(Object object, Object id, Object collection, Instant timestamp) {
        return get().documentStore().index(object, id, collection, timestamp);
    }

    /**
     * Index given object for search. This method returns once the object is stored.
     *
     * @see DocumentStore for more advanced uses.
     */
    static CompletableFuture<Void> index(Object object, Object id, Object collection, Instant begin, Instant end) {
        return get().documentStore().index(object, id, collection, begin, end);
    }

    /**
     * Index given objects for search. Use {@code idFunction} to provide the document's required id. Use
     * {@code timestampFunction} and {@code endFunction} to provide the object's timestamp. If none are supplied the
     * document will not be timestamped.
     * <p>
     * This method returns once all objects are stored.
     *
     * @see DocumentStore for more advanced uses.
     */
    static <T> CompletableFuture<Void> index(Collection<? extends T> objects, Object collection,
                                             Function<? super T, String> idFunction,
                                             Function<? super T, Instant> timestampFunction,
                                             Function<? super T, Instant> endFunction) {
        return get().documentStore().index(objects, collection, idFunction, timestampFunction, endFunction);
    }

    /**
     * Prepare a fluent bulk update for the given document collection.
     * <p>
     * Example usage: {@code Fluxzero.bulkUpdate("my_collection").index(doc1).delete(doc2Id).execute();}
     *
     * @see DocumentStore#bulkUpdate(Object)
     */
    static BulkUpdateBuilder bulkUpdate(Object collection) {
        return get().documentStore().bulkUpdate(collection);
    }

    /**
     * Search the given collection for documents. Usually collection is the String name of the collection. However, it
     * is also possible to call it with a {@link Collection} containing one or multiple collection names.
     * <p>
     * If collection is of type {@link Class} it is expected that the class is annotated with {@link Searchable}. It
     * will then use the collection configured there.
     * <p>
     * For all other inputs, the collection name will be obtained by calling {@link Object#toString()} on the input.
     * <p>
     * Example usage: {@code Fluxzero.<MyDocument>search("myCollection").query("foo !bar").fetch(100)}.
     */
    static <T> Search<T> search(Object collection) {
        return get().documentStore().search(collection);
    }

    /**
     * Search the collection represented by the given document class and retain that class as the default result type.
     */
    static <T> Search<T> search(Class<T> collection) {
        return get().documentStore().search(collection);
    }

    /**
     * Search the collections represented by the given document class and additional collection identifiers while
     * retaining the document class as the default result type.
     */
    static <T> Search<T> search(Class<T> collection, Object... additionalCollections) {
        return get().documentStore()
                .search(Stream.concat(Stream.of(collection), stream(additionalCollections)).toList());
    }

    /**
     * Search the given collections for documents.
     * <p>
     * If collection is of type {@link Class} it is expected that the class is annotated with {@link Searchable}. It
     * will then use the collection configured there. For all other inputs, the collection name will be obtained by
     * calling {@link Object#toString()} on the input.
     * <p>
     * Example usage: Fluxzero.search("myCollection", "myOtherCollection).query("foo !bar").fetch(100);
     */
    static <T> Search<T> search(Object collection, Object... additionalCollections) {
        return get().documentStore()
                .search(Stream.concat(Stream.of(collection), stream(additionalCollections)).toList());
    }

    /**
     * Search documents using given reusable query builder.
     * <p>
     * Example usage: Fluxzero.search(SearchQuery.builder().search("myCollection").query("foo !bar")).fetch(100);
     */
    static <T> Search<T> search(SearchQuery.Builder queryBuilder) {
        return get().documentStore().search(queryBuilder);
    }

    /**
     * Searches complete graph views for an independent model root. A configured materialized view is preferred;
     * otherwise the graph is composed live.
     */
    static <T> Search<Graph<T>> searchGraph(
            Class<T> rootModelType) {
        return get().documentStore()
                .searchGraph(rootModelType);
    }

    /**
     * Searches complete graph views for an independent model root.
     *
     * @param forceAdHoc whether to bypass a configured materialized view and compose the current graph live
     */
    static <T> Search<Graph<T>> searchGraph(
            Class<T> rootModelType,
            boolean forceAdHoc) {
        return get().documentStore()
                .searchGraph(
                        rootModelType,
                        forceAdHoc);
    }

    /**
     * Checks whether a document exists for the given identifier and its associated type. The type is used to determine
     * the document collection.
     */
    static boolean hasDocument(Id<?> id) {
        return hasDocument(id, id.getType());
    }

    /**
     * Checks if a document exists in the specified collection.
     */
    static boolean hasDocument(Object id, Object collection) {
        return get().documentStore().hasDocument(id, collection);
    }

    /**
     * Fetches a document by id using the associated type to determine the collection. The result is deserialized into
     * the stored type.
     */
    static <T> Optional<T> getDocument(Id<T> id) {
        return get().documentStore().fetchDocument(id);
    }

    /**
     * Gets the document with given id in given collection, returning the value in the type that it was stored.
     */
    static <T> Optional<T> getDocument(Object id, Object collection) {
        return get().documentStore().fetchDocument(id, collection);
    }

    /**
     * Gets the document with given id in given collection type, returning the value.
     */
    static <T> Optional<T> getDocument(Object id, Class<T> collection) {
        return get().documentStore().fetchDocument(id, collection, collection);
    }

    /**
     * Gets the document with given id in given collection, converting the matching document to a value with given
     * type.
     */
    static <T> Optional<T> getDocument(Object id, Object collection, Class<T> type) {
        return get().documentStore().fetchDocument(id, collection, type);
    }

    /**
     * Gets a collection of documents by their IDs from the given collection and deserializes them into the stored
     * type.
     */
    static <T> Collection<T> getDocuments(Collection<?> ids, Object collection) {
        return get().documentStore().fetchDocuments(ids, collection);
    }

    /**
     * Gets a collection of documents by their IDs from the given collection type.
     */
    static <T> Collection<T> getDocuments(Collection<?> ids, Class<T> collection) {
        return get().documentStore().fetchDocuments(ids, collection, collection);
    }

    /**
     * Gets a collection of documents by their IDs, converting the matching documents to value with the given type.
     */
    static <T> Collection<T> getDocuments(Collection<?> ids, Object collection, Class<T> type) {
        return get().documentStore().fetchDocuments(ids, collection, type);
    }

    /**
     * Deletes the document with given id in given collection if it exists.
     */
    static CompletableFuture<Void> deleteDocument(Object id, Object collection) {
        return get().documentStore().deleteDocument(id, collection);
    }

    /**
     * Deletes a search collection if it exists.
     */
    static CompletableFuture<Void> deleteCollection(Object collection) {
        return get().documentStore().deleteCollection(collection);
    }

    /**
     * Modify given value before it's passed to the given viewer. See {@link FilterContent} for info on how to filter
     * the value.
     */
    static <T> T filterContent(T value, User user) {
        return get().serializer().filterContent(value, user);
    }

    /**
     * Downcasts the given object to a previous revision.
     *
     * @param object          the object to downcast
     * @param desiredRevision the target revision
     * @return a serialized form of the object downcasted to the given revision
     */
    static Object downcast(Object object, int desiredRevision) {
        return get().serializer().downcast(object, desiredRevision);
    }

    /**
     * Downcasts a {@link Data} object to the specified revision level.
     *
     * @param data            the serialized data
     * @param desiredRevision the target revision number
     * @return a serialized form of the object downcasted to the given revision
     */
    static Object downcast(Data<?> data, int desiredRevision) {
        return get().serializer().downcast(data, desiredRevision);
    }

    /**
     * Registers given handlers and initiates message tracking (i.e. listening for messages).
     * <p>
     * The given handlers will be inspected for annotated handler methods (e.g. methods annotated with
     * {@link HandleCommand}). Depending on this inspection message tracking will commence for any handled message
     * types. To stop listening at any time invoke {@link Registration#cancel()} on the returned object.
     * <p>
     * Note that an exception may be thrown if tracking for a given message type is already in progress.
     * <p>
     * If any of the handlers is a local handler or contains local handler methods, i.e. if type or method is annotated
     * with {@link LocalHandler}, the target object will (also) be registered as local handler. Local handlers will
     * handle messages in the publishing thread. If a published message can be handled locally it will not be published
     * to the Fluxzero Runtime. Local handling of messages may come in handy in several situations: e.g. when the
     * message is expressly meant to be handled only by the current application or if the message needs to be handled as
     * quickly as possible. However, in most cases it will not be necessary to register local handlers.
     * <p>
     * Note that it will generally not be necessary to invoke this method manually if you use Spring to configure your
     * application.
     *
     * @see FluxzeroSpringConfig for more info on how to configure your application using Spring
     * @see LocalHandler for more info on local handlers.
     */
    default Registration registerHandlers(Object... handlers) {
        return registerHandlers(Arrays.asList(handlers));
    }

    /**
     * Registers given handlers and initiates message tracking.
     *
     * @see #registerHandlers(Object...) for more info
     */
    default Registration registerHandlers(List<?> handlers) {
        return apply(f -> {
            Registration local = handlers.stream().flatMap(h -> Stream
                            .of(commandGateway().registerHandler(h), queryGateway().registerHandler(h),
                                eventGateway().registerHandler(h), eventStore().registerHandler(h),
                                errorGateway().registerHandler(h), webRequestGateway().registerHandler(h),
                                ClientUtils.getTopics(CUSTOM, h).stream()
                                        .map(topic -> customGateway(topic).registerHandler(h))
                                        .reduce(Registration::merge).orElse(Registration.noOp())))
                    .reduce(Registration::merge).orElse(Registration.noOp());
            local = local.merge(handlers.stream().map(this::registerScheduleLocalHandler)
                                        .reduce(Registration::merge).orElse(Registration.noOp()));

            Registration tracking = stream(MessageType.values()).map(t -> tracking(t).start(this, handlers))
                    .reduce(Registration::merge).orElse(Registration.noOp());
            return tracking.merge(local);
        });
    }

    private Registration registerScheduleLocalHandler(Object handler) {
        return messageScheduler() instanceof HasLocalHandlers localHandlers
                ? localHandlers.registerHandler(handler) : Registration.noOp();
    }

    /**
     * Have Fluxzero use the given Clock when generating timestamps, e.g. when creating a {@link Message}.
     */
    void withClock(Clock clock);

    /**
     * Returns a client to assist with event sourcing.
     */
    AggregateRepository aggregateRepository();

    /**
     * Returns the repository for independently stored models.
     * <p>
     * The default keeps existing custom {@code Fluxzero} implementations binary/source compatible while the model
     * action transport is introduced. Standard runtime-backed configurations override this when that transport is
     * available.
     */
    default ModelRepository modelRepository() {
        throw new UnsupportedOperationException("Independent model persistence is not configured");
    }

    /**
     * Returns the store for aggregate events.
     */
    EventStore eventStore();

    /**
     * Returns the store for aggregate snapshots.
     */
    SnapshotStore snapshotStore();

    /**
     * Returns the gateway to schedule messages.
     *
     * @see MessageType#SCHEDULE
     */
    MessageScheduler messageScheduler();

    /**
     * Returns the gateway for command messages.
     */
    CommandGateway commandGateway();

    /**
     * Returns the gateway for query messages.
     */
    QueryGateway queryGateway();

    /**
     * Returns the message gateway for application events. Use {@link #aggregateRepository()} to publish events
     * belonging to an aggregate.
     */
    EventGateway eventGateway();

    /**
     * Returns the gateway for result messages sent by handlers of commands and queries.
     */
    ResultGateway resultGateway();

    /**
     * Returns the gateway for any error messages published while handling a command or query.
     */
    ErrorGateway errorGateway();

    /**
     * Returns the gateway for metrics events. Metrics events can be published in any form to log custom performance
     * metrics about an application.
     */
    MetricsGateway metricsGateway();

    /**
     * Returns the gateway for sending web requests.
     */
    WebRequestGateway webRequestGateway();

    /**
     * Returns the gateway for given custom message topic.
     */
    GenericGateway customGateway(String topic);

    /**
     * Returns a client to assist with the tracking of a given message type.
     */
    Tracking tracking(MessageType messageType);

    /**
     * Returns a client for the key value service offered by Fluxzero.
     */
    KeyValueStore keyValueStore();

    /**
     * Returns a client for the document search service offered by Fluxzero.
     */
    DocumentStore documentStore();

    /**
     * Returns the UserProvider used by Fluxzero to authenticate users. May be {@code null} if user authentication is
     * disabled.
     */
    UserProvider userProvider();

    /**
     * Returns the cache used by the client to cache aggregates etc.
     */
    Cache cache();

    /**
     * Returns the provider of correlation data for published messages.
     */
    CorrelationDataProvider correlationDataProvider();

    /**
     * Returns the default serializer
     */
    Serializer serializer();

    /**
     * Returns the clock used by Fluxzero to generate timestamps.
     */
    Clock clock();

    /**
     * Returns the factory used by Fluxzero to generate identifiers.
     */
    IdentityProvider identityProvider();

    /**
     * Returns the {@link PropertySource} configured for this Fluxzero instance.
     */
    PropertySource propertySource();

    /**
     * Returns the {@link TaskScheduler} of this Fluxzero instance.
     */
    TaskScheduler taskScheduler();

    /**
     * Returns the memoization store of this Fluxzero instance.
     */
    Memoization memoization();

    /**
     * Returns the {@link FluxzeroConfiguration} of this Fluxzero instance.
     */
    FluxzeroConfiguration configuration();

    /**
     * Executes one model commit without routing it through command handlers.
     * <p>
     * This is an infrastructure extension point used by {@link #assertAndApply(Object)}. Custom Fluxzero
     * implementations that support independent models should override it.
     *
     * @param update message containing the model update
     * @return completion of the durable model commit
     */
    default CompletableFuture<Void> executeModelCommit(Message update) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This Fluxzero implementation does not support direct model commits"));
    }

    /**
     * Flushes Model changes already attached to the current handling context. This extension point backs
     * {@link #commit()} and may be overridden by custom Fluxzero implementations with a different Model pipeline.
     */
    default CompletableFuture<Void> commitModelChanges() {
        return ModelBatchScope.commitCurrent();
    }

    /**
     * Executes one model commit against an explicitly selected persisted model identity. This is the infrastructure
     * extension used by {@link Graph#assertAndApply(Object)}; custom implementations supporting independent models may
     * override it alongside {@link #executeModelCommit(Message)}.
     */
    default CompletableFuture<Void> executeModelCommit(
            Message update, String modelId, Class<?> modelType) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This Fluxzero implementation does not support targeted model commits"));
    }

    /**
     * Executes multiple independent model commits without routing them through command handlers. The default
     * implementation preserves compatibility for custom implementations by invoking {@link #executeModelCommit(Message)}
     * for every update. Implementations may override this to batch transport while retaining separate commit semantics.
     *
     * @param updates messages containing independent model updates
     * @return completion after every durable model commit
     */
    default CompletableFuture<Void> executeModelCommits(List<Message> updates) {
        Objects.requireNonNull(updates, "updates");
        return CompletableFuture.allOf(updates.stream()
                .map(update -> executeModelCommit(Objects.requireNonNull(update, "update")))
                .toArray(CompletableFuture[]::new));
    }

    /**
     * Applies an event that was already accepted by its original command flow to independent models.
     * Assertions and apply interceptors are skipped, while regular {@code @Apply} methods and durable event
     * publication are preserved. This infrastructure hook is primarily used by replay and test fixtures.
     *
     * @param event previously accepted event to apply
     * @return completion of the durable model commit
     */
    default CompletableFuture<Void> executeStoredModelEvent(Message event) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This Fluxzero implementation does not support stored model event application"));
    }

    /**
     * Executes model apply interceptors and immediate assertions without applying or committing the update.
     * <p>
     * This is an infrastructure extension point used by {@link #assertLegal(Object)}. Custom Fluxzero implementations
     * that support independent models should override it.
     *
     * @param update message containing the model update to validate
     * @return completion of the validation-only model evaluation
     */
    default CompletableFuture<Void> executeModelAssertions(Message update) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This Fluxzero implementation does not support direct model assertions"));
    }

    /**
     * Returns the low level client used by this Fluxzero instance to interface with the Fluxzero Runtime. Of course the
     * returned client may also be a stand-in for the actual service.
     */
    Client client();

    /**
     * Applies the given function with this Fluxzero set as current threadlocal instance.
     */
    @SneakyThrows
    default <R> R apply(ThrowingFunction<Fluxzero, R> function) {
        Fluxzero current = Fluxzero.instance.get();
        try {
            Fluxzero.instance.set(this);
            return function.apply(this);
        } finally {
            Fluxzero.instance.set(current);
        }
    }

    /**
     * Executes the given task with this Fluxzero set as current threadlocal instance.
     */
    @SneakyThrows
    default void execute(ThrowingConsumer<Fluxzero> task) {
        Fluxzero current = Fluxzero.instance.get();
        try {
            Fluxzero.instance.set(this);
            task.accept(this);
        } finally {
            Fluxzero.instance.set(current);
        }
    }

    /**
     * Register a task to run before this Fluxzero instance is closed.
     */
    Registration beforeShutdown(Runnable task);

    /**
     * Closes this Fluxzero instance gracefully.
     */
    @Override
    default void close() {
        close(false);
    }

    /**
     * Closes this Fluxzero instance gracefully. If silently is true, shutdown is done without logging.
     */
    void close(boolean silently);

    record MemoizationKey(Object scope, Object key) {
    }

    final class GlobalMemoizationScope {
        private static final Object marker = new Object();

        private GlobalMemoizationScope() {
        }
    }
}
