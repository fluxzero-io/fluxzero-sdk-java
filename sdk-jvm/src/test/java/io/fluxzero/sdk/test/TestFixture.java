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

package io.fluxzero.sdk.test;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.DelegatingClock;
import io.fluxzero.common.DirectExecutorService;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.InMemoryTaskScheduler;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.Registration;
import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.SerializedObject;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Order;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Downcast;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.configuration.spring.ConditionalOnMissingProperty;
import io.fluxzero.sdk.configuration.spring.ConditionalOnProperty;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.persisting.search.DefaultDocumentStore;
import io.fluxzero.sdk.persisting.search.Search;
import io.fluxzero.sdk.publishing.DefaultMetricsGateway;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.scheduling.DefaultMessageScheduler;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.scheduling.ScheduledCommand;
import io.fluxzero.sdk.scheduling.client.LocalSchedulingClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.BatchInterceptor;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.TrackingException;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.sdk.web.WebUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.HttpCookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.CUSTOM;
import static io.fluxzero.common.MessageType.DOCUMENT;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.common.ObjectUtils.run;
import static io.fluxzero.common.api.Data.JSON_FORMAT;
import static io.fluxzero.common.reflection.ReflectionUtils.getCallerClass;
import static io.fluxzero.common.reflection.ReflectionUtils.ifClass;
import static io.fluxzero.sdk.common.ClientUtils.getLocalHandlerAnnotation;
import static io.fluxzero.sdk.common.Message.asMessage;
import static io.fluxzero.sdk.web.HttpRequestMethod.isWebsocket;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.empty;

/**
 * A test harness for simulating and verifying message-driven behavior using the Fluxzero framework.
 * <p>
 * A {@code TestFixture} enables writing tests in the Given-When-Then format. It allows simulating messages, time
 * travel, and verifying resulting effects such as commands, events, errors, web requests, and system state.
 * <p>
 * The fixture operates in one of two modes:
 * <ul>
 *   <li>{@link #create(Object...)} — synchronous mode: all handlers are treated as local, executed on the calling thread</li>
 *   <li>{@link #createAsync(Object...)} — asynchronous mode: messages are dispatched and consumed across threads, closely simulating production runtime behavior</li>
 * </ul>
 * In async mode, handlers behave as they would in real deployments. This means:
 * <ul>
 *   <li>Tracked messages are processed via trackers</li>
 *   <li>Non-local handlers are scheduled and dispatched asynchronously</li>
 *   <li>Handlers annotated with {@code @LocalHandler} still execute locally (on the calling thread)</li>
 * </ul>
 *
 * <p>
 * Handlers may be registered by class or by instance. In general:
 * <ul>
 *   <li>Stateless singleton handlers may be registered either by instance or class</li>
 *   <li>Handlers annotated with {@code @Stateful}, {@code @TrackSelf}, or {@code @SocketEndpoint} must be registered by <strong>class</strong> only</li>
 * </ul>
 *
 * <p>
 * JSON files may be used in any {@code givenXyz(...)} or {@code whenXyz(...)} methods. Any string argument ending in {@code ".json"}
 * will be resolved as a resource, loaded and deserialized using {@link JsonUtils}.
 * <br> The JSON must include an {@code @class} declaration to indicate the object type to deserialize.
 * <br> JSON resources may also {@code @extends} another file to support inheritance and override behavior.
 *
 * <h2>Example 1: Issue a command and expect a result and event</h2>
 *
 * <pre>{@code
 * TestFixture fixture = TestFixture.create(new UserHandler());
 *
 * fixture.givenCommands("input/create-user.json")
 *        .whenCommand("input/update-user.json")
 *        .expectResult("expected/update-user-response.json")
 *        .expectEvents(UpdateUser.class);
 * }</pre>
 *
 * <h2>Example 2: Using predicates and class-based matchers</h2>
 *
 * <pre>{@code
 * fixture.whenCommand(new DeleteUser("userId-123"))
 *        .expectResult(r -> r instanceof SuccessResponse)
 *        .expectEvent(DeleteUser.class);
 * }</pre>
 *
 * <h2>Example 3: Asserting no side effects occurred</h2>
 *
 * <pre>{@code
 * fixture.whenCommand("input/no-op-command.json")
 *        .expectNoEvents()
 *        .expectNoResult()
 *        .expectNoErrors();
 * }</pre>
 *
 * <h2>Continuing to the next phase</h2>
 *
 * <pre>{@code
 * fixture.whenCommand("input/command-a.json")
 *        .expectResult("expected/result-a.json")
 *        .andThen()
 *        .whenCommand("input/command-b.json")
 *        .expectResult("expected/result-b.json");
 * }</pre>
 *
 * @see Given
 * @see When
 * @see Then
 * @see #create(Object...)
 * @see #createAsync(Object...)
 * @see JsonUtils
 */
@Slf4j
@Getter(AccessLevel.PACKAGE)
public class TestFixture implements Given<TestFixture>, When {
    /**
     * Creates a synchronous {@code TestFixture} with the given handlers.
     * <p>
     * In synchronous mode, all messages are dispatched and handled in the same thread. Handlers are automatically
     * registered as local handlers.
     *
     * @param handlers one or more handler instances or handler classes to register
     * @return a new {@code TestFixture} instance
     */
    public static TestFixture create(Object... handlers) {
        return create(DefaultFluxzero.builder(), handlers);
    }

    /**
     * Creates a synchronous {@code TestFixture} using a custom {@link FluxzeroBuilder} and handlers.
     *
     * @param fluxzeroBuilder a builder for configuring the test Fluxzero instance
     * @param handlers        one or more handler instances or classes
     * @return a new {@code TestFixture}
     */
    public static TestFixture create(FluxzeroBuilder fluxzeroBuilder, Object... handlers) {
        return create(fluxzeroBuilder, fc -> Arrays.asList(handlers));
    }

    /**
     * Creates a synchronous {@code TestFixture} using a factory function to produce handlers after the {@link Fluxzero}
     * is built.
     *
     * @param handlersFactory a function that takes a {@link Fluxzero} and returns a list of handler instances
     * @return a new {@code TestFixture}
     */
    public static TestFixture create(Function<Fluxzero, List<?>> handlersFactory) {
        return create(DefaultFluxzero.builder(), handlersFactory);
    }

    /**
     * Creates a synchronous {@code TestFixture} using a custom {@link FluxzeroBuilder} and a handler factory.
     *
     * @param fluxzeroBuilder a builder for configuring the Fluxzero instance
     * @param handlersFactory a function that takes a {@link Fluxzero} and returns a list of handler instances
     * @return a new {@code TestFixture}
     */
    public static TestFixture create(FluxzeroBuilder fluxzeroBuilder,
                                     Function<Fluxzero, List<?>> handlersFactory) {
        return new TestFixture(fluxzeroBuilder, handlersFactory, LocalClient.newInstance(null), true);
    }

    /**
     * Creates a synchronous fixture that explicitly opts into JVM compatibility metadata mode.
     * <p>
     * This mode is intended for legacy fixture tests that still exercise reflection-backed JVM semantics while the
     * default runtime migrates to generated metadata.
     */
    public static TestFixture createJvmCompatibility(Object... handlers) {
        return create(jvmCompatibilityMetadata(DefaultFluxzero.builder()), handlers);
    }

    /**
     * Creates a synchronous fixture with a custom builder that explicitly opts into JVM compatibility metadata mode.
     */
    public static TestFixture createJvmCompatibility(FluxzeroBuilder fluxzeroBuilder, Object... handlers) {
        return create(jvmCompatibilityMetadata(fluxzeroBuilder), handlers);
    }

    /**
     * Creates a synchronous fixture with a handler factory that explicitly opts into JVM compatibility metadata mode.
     */
    public static TestFixture createJvmCompatibility(Function<Fluxzero, List<?>> handlersFactory) {
        return create(jvmCompatibilityMetadata(DefaultFluxzero.builder()), handlersFactory);
    }

    /**
     * Creates a synchronous fixture with a custom builder and handler factory that explicitly opts into JVM
     * compatibility metadata mode.
     */
    public static TestFixture createJvmCompatibility(FluxzeroBuilder fluxzeroBuilder,
                                                     Function<Fluxzero, List<?>> handlersFactory) {
        return create(jvmCompatibilityMetadata(fluxzeroBuilder), handlersFactory);
    }

    /**
     * Creates an asynchronous {@code TestFixture} with the given handlers.
     * <p>
     * In async mode, messages are dispatched to handlers in separate threads unless they are marked with
     * {@code @LocalHandler}.
     *
     * @param handlers one or more handler instances or classes
     * @return a new {@code TestFixture}
     */
    public static TestFixture createAsync(Object... handlers) {
        return createAsync(DefaultFluxzero.builder(), handlers);
    }

    /**
     * Creates an asynchronous {@code TestFixture} using a custom {@link FluxzeroBuilder} and handlers.
     *
     * @param fluxzeroBuilder a builder for configuring the Fluxzero instance
     * @param handlers        one or more handler instances or classes
     * @return a new {@code TestFixture}
     */
    public static TestFixture createAsync(FluxzeroBuilder fluxzeroBuilder, Object... handlers) {
        return createAsync(fluxzeroBuilder, fc -> Arrays.asList(handlers));
    }

    /**
     * Creates an asynchronous {@code TestFixture} using a factory function to produce handlers after the
     * {@link Fluxzero} is built.
     *
     * @param handlersFactory a function that takes a {@link Fluxzero} and returns a list of handler instances
     * @return a new {@code TestFixture}
     */
    public static TestFixture createAsync(Function<Fluxzero, List<?>> handlersFactory) {
        return createAsync(DefaultFluxzero.builder(), handlersFactory);
    }

    /**
     * Creates an asynchronous {@code TestFixture} using a custom {@link FluxzeroBuilder} and a factory function to
     * produce handlers after the {@link Fluxzero} is built.
     *
     * @param fluxzeroBuilder a builder for configuring the Fluxzero instance
     * @param handlersFactory a function that takes a {@link Fluxzero} and returns a list of handler instances
     * @return a new {@code TestFixture}
     */
    public static TestFixture createAsync(FluxzeroBuilder fluxzeroBuilder,
                                          Function<Fluxzero, List<?>> handlersFactory) {
        return new TestFixture(fluxzeroBuilder, handlersFactory, LocalClient.newInstance(null), false);
    }

    /**
     * Creates an asynchronous fixture that explicitly opts into JVM compatibility metadata mode.
     */
    public static TestFixture createAsyncJvmCompatibility(Object... handlers) {
        return createAsync(jvmCompatibilityMetadata(DefaultFluxzero.builder()), handlers);
    }

    /**
     * Creates an asynchronous fixture with a custom builder that explicitly opts into JVM compatibility metadata mode.
     */
    public static TestFixture createAsyncJvmCompatibility(FluxzeroBuilder fluxzeroBuilder, Object... handlers) {
        return createAsync(jvmCompatibilityMetadata(fluxzeroBuilder), handlers);
    }

    /**
     * Creates an asynchronous fixture with a handler factory that explicitly opts into JVM compatibility metadata mode.
     */
    public static TestFixture createAsyncJvmCompatibility(Function<Fluxzero, List<?>> handlersFactory) {
        return createAsync(jvmCompatibilityMetadata(DefaultFluxzero.builder()), handlersFactory);
    }

    /**
     * Creates an asynchronous fixture with a custom builder and handler factory that explicitly opts into JVM
     * compatibility metadata mode.
     */
    public static TestFixture createAsyncJvmCompatibility(FluxzeroBuilder fluxzeroBuilder,
                                                         Function<Fluxzero, List<?>> handlersFactory) {
        return createAsync(jvmCompatibilityMetadata(fluxzeroBuilder), handlersFactory);
    }

    /**
     * Creates an asynchronous {@code TestFixture} using a custom {@link FluxzeroBuilder}, a handler factory, and a
     * preconfigured {@link Client}.
     * <p>
     * This variant allows more control over the test environment—for example, injecting a customized local or remote
     * client implementation.
     *
     * @param fluxzeroBuilder builder for configuring the Fluxzero instance
     * @param client          the client to use in the test fixture
     * @param handlers        one or more handler instances or classes
     * @return a new {@code TestFixture}
     */
    public static TestFixture createAsync(FluxzeroBuilder fluxzeroBuilder, Client client,
                                          Object... handlers) {
        return new TestFixture(fluxzeroBuilder, fc -> Arrays.asList(handlers), client, false);
    }

    /**
     * Creates an asynchronous fixture with a custom builder, client and explicit JVM compatibility metadata mode.
     */
    public static TestFixture createAsyncJvmCompatibility(FluxzeroBuilder fluxzeroBuilder, Client client,
                                                          Object... handlers) {
        return createAsync(jvmCompatibilityMetadata(fluxzeroBuilder), client, handlers);
    }

    /**
     * Prepends the JVM compatibility metadata-mode property to a builder.
     */
    public static FluxzeroBuilder jvmCompatibilityMetadata(FluxzeroBuilder fluxzeroBuilder) {
        return fluxzeroBuilder.replacePropertySource(source -> new SimplePropertySource(Map.of(
                ComponentMetadataLookups.METADATA_MODE_PROPERTY, ComponentMetadataLookups.JVM_COMPATIBILITY_MODE))
                .andThen(source));
    }

    public static Duration defaultResultTimeout = Duration.ofSeconds(2L);
    public static Duration defaultConsumerTimeout = Duration.ofSeconds(5L);
    private static final LocalDate PER_HANDLER_DEFAULTS_VERSION = LocalDate.of(2026, 5, 20);

    @Getter
    private final Fluxzero fluxzero;
    private final FluxzeroBuilder fluxzeroBuilder;
    private final GivenWhenThenInterceptor interceptor;
    private Duration resultTimeout = defaultResultTimeout;
    private Duration consumerTimeout = defaultConsumerTimeout;
    private boolean warnOnPendingConsumers = true;
    private boolean ignoreErrorsInGiven;
    private boolean skipScheduleDeadlines;
    private final boolean synchronous;
    private final boolean spying;
    private final boolean productionUserProvider;
    private Registration registration = Registration.noOp();

    private final Map<ActiveConsumer, List<Message>> consumers = new ConcurrentHashMap<>();
    private final Map<HandlerConsumerKey, Set<ConsumerIdentity>> handlerConsumers = new ConcurrentHashMap<>();
    private final Set<String> requestDispatches = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Deque<ActiveHandler>> activeHandlers = ThreadLocal.withInitial(ArrayDeque::new);

    private FixtureResult fixtureResult = new FixtureResult();

    private final BeanParameterResolver beanParameterResolver = new BeanParameterResolver();
    private final Map<String, String> testProperties = new HashMap<>();
    private final List<HttpCookie> cookies = new ArrayList<>();
    private final Map<String, List<String>> headers = WebUtils.emptyHeaderMap();

    private final List<ThrowingConsumer<TestFixture>> modifiers = new CopyOnWriteArrayList<>();
    private final Set<Class<?>> registeredTrackSelfHandlers = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<List<TestFixture>> activeFixtures = ThreadLocal.withInitial(ArrayList::new);

    public static void shutDownActiveFixtures() {
        var fixtures = activeFixtures.get();
        if (!fixtures.isEmpty()) {
            activeFixtures.remove();
            fixtures.forEach(fixture -> ObjectUtils.tryRun(() -> fixture.fluxzero.execute(
                    fc -> fixture.fluxzero.close(true))));
            ofNullable(Fluxzero.instance.get()).ifPresent(fc -> Fluxzero.instance.remove());
            GivenWhenThenAssertionError.clearTrace();
        }
    }

    protected TestFixture(FluxzeroBuilder fluxzeroBuilder,
                          Function<Fluxzero, List<?>> handlerFactory, Client client, boolean synchronous) {
        activeFixtures.get().add(this);
        this.synchronous = synchronous;
        this.spying = false;
        this.productionUserProvider = false;
        fluxzeroBuilder.registerUserProvider(
                ofNullable(fluxzeroBuilder.userProvider())
                        .or(() -> Optional.ofNullable(UserProvider.defaultUserProvider))
                        .map(TestUserProvider::new).orElse(null));
        if (synchronous) {
            fluxzeroBuilder.disableScheduledCommandHandler();
        }
        fluxzeroBuilder.replacePropertySource(s -> new SimplePropertySource(testProperties).andThen(s));
        this.interceptor = new GivenWhenThenInterceptor(this);
        var dispatchInterceptor = new LowPriorityDispatchInterceptor(interceptor);
        client = trackRemoteDocumentUpdates(client);
        client.monitorDispatch(dispatchInterceptor::interceptClientDispatch);
        Clock fixtureClock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneId.systemDefault());
        fluxzeroBuilder = fluxzeroBuilder.disableShutdownHook()
                .addParameterResolver(beanParameterResolver)
                .addDispatchInterceptor(dispatchInterceptor)
                .replaceIdentityProvider(p -> p == IdentityProvider.defaultIdentityProvider
                        ? PredictableIdentityProvider.defaultPredictableIdentityProvider() : p)
                .replaceTaskScheduler(clock -> {
                    if (clock instanceof DelegatingClock delegatingClock) {
                        delegatingClock.setDelegate(fixtureClock);
                    }
                    return new InMemoryTaskScheduler(
                            "FluxzeroTaskScheduler", clock, DirectExecutorService.newInstance(), false);
                })
                .addBatchInterceptor(new HighPriorityBatchInterceptor(interceptor))
                .addHandlerInterceptor(new HighPriorityHandlerInterceptor(interceptor));
        this.fluxzeroBuilder = fluxzeroBuilder;
        this.fluxzero = fluxzeroBuilder.build(client);
        Fluxzero.instance.set(this.fluxzero);
        if (synchronous) {
            localHandlerRegistries(fluxzero).forEach(r -> r.setSelfHandlerFilter(HandlerFilter.ALWAYS_HANDLE));
        }
        withClock(fixtureClock);
        List<Object> handlers = new ArrayList<>();
        if (synchronous) {
            handlers.add(new Object() {
                @HandleSchedule
                void handle(ScheduledCommand schedule) {
                    SerializedMessage command = schedule.getCommand();
                    command.setTimestamp(Fluxzero.currentTime().toEpochMilli());
                    fluxzero.serializer()
                            .deserializeMessages(Stream.of(command), MessageType.COMMAND).findFirst().map(
                                    DeserializingMessage::toMessage).ifPresent(Fluxzero::sendAndForgetCommand);
                }
            });
        }
        handlers.addAll(handlerFactory.apply(fluxzero));
        registerHandlers(handlers);
    }

    protected TestFixture(TestFixture currentFixture, boolean synchronous, boolean spying,
                          boolean productionUserProvider) {
        activeFixtures.get().add(this);
        this.synchronous = synchronous;
        this.spying = spying;
        this.productionUserProvider = productionUserProvider;

        this.fluxzeroBuilder = currentFixture.fluxzeroBuilder;
        if (productionUserProvider != currentFixture.productionUserProvider) {
            this.fluxzeroBuilder.registerUserProvider(
                    ofNullable(UserProvider.defaultUserProvider)
                            .map(provider -> productionUserProvider
                                    ? provider : new TestUserProvider(provider))
                            .orElse(null));
        }
        (this.interceptor = currentFixture.interceptor).testFixture = this;
        var dispatchInterceptor = new LowPriorityDispatchInterceptor(interceptor);
        var currentClient = currentFixture.fluxzero.client().unwrap();
        var newClient = currentClient instanceof LocalClient
                ? LocalClient.newInstance(null) : currentClient;
        newClient = trackRemoteDocumentUpdates(newClient);
        newClient.monitorDispatch(dispatchInterceptor::interceptClientDispatch);
        this.fluxzero = spying
                ? new SpyingFluxzero(fluxzeroBuilder.build(new SpyingClient(newClient)))
                : fluxzeroBuilder.build(newClient);
        Fluxzero.instance.set(this.fluxzero);
        localHandlerRegistries(this.fluxzero).forEach(r -> r.setSelfHandlerFilter(
                synchronous ? HandlerFilter.ALWAYS_HANDLE : (t, m) -> !ClientUtils.isSelfTracking(t, m)));
        currentFixture.modifiers.forEach(this::modifyFixture);
    }

    /*
        Modifications
     */

    private Client trackRemoteDocumentUpdates(Client client) {
        return client.unwrap() instanceof LocalClient ? client : new DocumentTrackingClient(client, interceptor);
    }

    /**
     * Sets the maximum time to wait for a response to a request in the {@code given} and {@code when} phase.
     * <p>
     * Relevant only in asynchronous mode. Defaults to 2 seconds.
     */
    public TestFixture resultTimeout(Duration resultTimeout) {
        return modifyFixture(fixture -> fixture.resultTimeout = resultTimeout);
    }

    /**
     * Sets the maximum time to wait for message consumers to finish processing during {@code given} and {@code when}.
     * <p>
     * Relevant only in asynchronous mode. Defaults to 5 seconds.
     */
    public TestFixture consumerTimeout(Duration consumerTimeout) {
        return modifyFixture(fixture -> fixture.consumerTimeout = consumerTimeout);
    }

    /**
     * Disables the warning that is logged when {@code given}/{@code when} times out while consumers are still pending.
     * <p>
     * Useful for tests that intentionally verify paused or stalled consumers.
     */
    public TestFixture suppressPendingConsumerWarning() {
        return modifyFixture(fixture -> fixture.warnOnPendingConsumers = false);
    }

    /**
     * Returns an asynchronous version of this fixture with the same state.
     */
    public TestFixture async() {
        return synchronous ? new TestFixture(this, false, spying, false) : this;
    }

    /**
     * Returns a synchronous version of this fixture with the same state.
     */
    public TestFixture sync() {
        return !synchronous ? new TestFixture(this, true, spying, false) : this;
    }

    /**
     * Returns a new test fixture with Mockito spies on all major Fluxzero components.
     * <p>
     * Useful for verifying internal interactions, e.g. gateway or repository usage.
     */
    public TestFixture spy() {
        return spying ? this : new TestFixture(this, synchronous, true, false);
    }

    /**
     * Retrieves the result of the <strong>previous</strong> when-phase and casts it to the expected type. If no
     * previous result is available, {@code null} is returned.
     * <p>
     * If the previous result is a {@link Message}, the result payload will be returned.
     */
    @SuppressWarnings("unchecked")
    public <T> T previousResult() {
        FixtureResult previousResult = fixtureResult.getPreviousResult();
        return previousResult == null ? null :
                previousResult.getResult() instanceof HasMessage hm ? hm.getPayload() : (T) previousResult.getResult();
    }

    /**
     * Retrieves the result of the <strong>previous</strong> when-phase and casts it to the given type. If no previous
     * result is available, {@code null} is returned.
     * <p>
     * If the previous result is a {@link Message} but the requested type is not, the result payload will be returned.
     */
    public <T> T previousResult(Class<T> resultType) {
        FixtureResult previousResult = fixtureResult.getPreviousResult();
        if (previousResult == null) {
            return null;
        }
        if (previousResult.getResult() instanceof HasMessage hm && !Message.class.isAssignableFrom(resultType)) {
            return resultType.cast(hm.getPayload());
        }
        return resultType.cast(previousResult.getResult());
    }

    /**
     * Configures the test fixture to use the production default {@link UserProvider} instead of the test one.
     * <p>
     * By default, test fixtures use a {@link TestUserProvider}, which ensures an active user is always present (falling
     * back to the system user if no authenticated user is set).
     * <p>
     * Calling this method disables that fallback behavior and restores the actual default user provider used in
     * production (i.e., {@link UserProvider#defaultUserProvider}).
     *
     * <p>This is useful when testing unauthenticated user flows.
     *
     * @return a new test fixture instance with the production user provider active
     */
    public TestFixture withProductionUserProvider() {
        return productionUserProvider ? this : new TestFixture(this, synchronous, spying, true);
    }

    /**
     * Registers one or more message handlers with the fixture.
     * <p>
     * In async mode, all handlers for the same consumer must be registered together.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public TestFixture registerHandlers(List<?> handlers) {
        return modifyFixture(fixture -> {
            Fluxzero fc = fixture.getFluxzero();
            if (handlers.isEmpty()) {
                return;
            }
            warnIfDuplicateHandlers(handlers);
            handlers.stream().map(this::handlerType)
                    .filter(ClientUtils::isSelfTracking)
                    .forEach(registeredTrackSelfHandlers::add);
            if (fixture.synchronous) {
                fixture.rememberConsumerAssignments(handlers);
            }
            if (!fixture.synchronous) {
                fixture.registration = fixture.registration.merge(fc.registerHandlers(handlers));
                return;
            }
            HandlerFilter handlerFilter = (c, e) -> true;
            var registration = fc.apply(f -> {
                Registration local = handlers.stream().flatMap(
                                h -> Stream.concat(nonScheduleLocalHandlerRegistries(f)
                                                           .map(r -> r.registerHandler(h, handlerFilter)),
                                                   ClientUtils.getTopics(CUSTOM, h).stream()
                                                           .map(topic -> f.customGateway(topic)
                                                                   .registerHandler(h, handlerFilter))))
                        .reduce(Registration::merge).orElse(Registration.noOp());
                Registration schedules = scheduleLocalHandlerRegistry(f)
                        .map(s -> handlers.stream().map(h -> s.registerHandler(h, handlerFilter))
                                .reduce(Registration::merge).orElse(Registration.noOp()))
                        .orElse(Registration.noOp());
                return local.merge(schedules);
            });
            fixture.registration = fixture.registration.merge(registration);
        });
    }

    private void warnIfDuplicateHandlers(List<?> handlers) {
        Map<Class<?>, Object> uniqueHandlers = new HashMap<>();
        Set<Class<?>> duplicateHandlerTypes = new HashSet<>();
        for (Object handler : handlers) {
            Class<?> handlerType = handlerType(handler);
            if (uniqueHandlers.putIfAbsent(handlerType, handler) != null) {
                duplicateHandlerTypes.add(handlerType);
            }
        }
        duplicateHandlerTypes.forEach(handlerType -> log.warn(
                "Handler of type {} is registered more than once. Please make sure this is intentional.",
                handlerType));
    }

    private Class<?> handlerType(Object handler) {
        return ifClass(handler) instanceof Class<?> handlerClass ? handlerClass : handler instanceof Handler<?> h
                ? h.getTargetClass() : handler.getClass();
    }

    private void rememberConsumerAssignments(List<?> handlers) {
        Arrays.stream(MessageType.values()).forEach(messageType -> assignHandlersToConsumers(messageType, handlers)
                .forEach((configuration, assignedHandlers) -> assignedHandlers.forEach(handler -> {
                    Class<?> handlerType = handlerType(handler);
                    trackingTopics(messageType, handlerType).forEach(topic -> handlerConsumers
                            .computeIfAbsent(new HandlerConsumerKey(handlerType, messageType),
                                             ignored -> ConcurrentHashMap.newKeySet())
                            .add(new ConsumerIdentity(configuration.getName(), configuration.getNamespace(),
                                                      messageType, topic, configuration.getThreads())));
                })));
    }

    private Set<String> trackingTopics(MessageType messageType, Class<?> handlerType) {
        Set<String> topics = ClientUtils.getTopics(messageType, List.of(handlerType));
        if (!topics.isEmpty()) {
            return topics;
        }
        return switch (messageType) {
            case DOCUMENT, CUSTOM -> Set.of();
            default -> Collections.singleton(null);
        };
    }

    private Map<ConsumerConfiguration, List<Object>> assignHandlersToConsumers(
            MessageType messageType, List<?> handlers) {
        List<ConsumerConfiguration> explicitConfigurations = explicitConfigurations(messageType, handlers).toList();
        if (useSharedDefaultAppConsumerForUnconfiguredHandlers()) {
            return assignHandlersToConsumers(
                    handlers, Stream.concat(explicitConfigurations.stream(),
                                            sharedDefaultConsumerConfiguration(messageType)));
        }
        List<Object> fallbackHandlers = fallbackHandlers(handlers, explicitConfigurations);
        return assignHandlersToConsumers(
                handlers, Stream.concat(explicitConfigurations.stream(),
                                        defaultConsumerConfigurations(messageType, fallbackHandlers)));
    }

    private Stream<ConsumerConfiguration> explicitConfigurations(MessageType messageType, List<?> handlers) {
        return Stream.concat(
                ConsumerConfiguration.configurations(
                        handlers.stream().map(ReflectionUtils::asClass).collect(toList())),
                fluxzero.configuration().customConsumerConfigurations()
                        .getOrDefault(messageType, List.of()).stream());
    }

    private Map<ConsumerConfiguration, List<Object>> assignHandlersToConsumers(
            List<?> handlers, Stream<ConsumerConfiguration> configurations) {
        var unassignedHandlers = new ArrayList<Object>(handlers);
        var result = normalizeConfigurations(configurations).stream().map(config -> {
            var matches = unassignedHandlers.stream().filter(h -> config.getHandlerFilter().test(h)).toList();
            if (config.exclusive() && !config.conditionallyExclusive()) {
                unassignedHandlers.removeAll(matches);
            }
            return Map.entry(config, matches);
        }).collect(toMap(Entry::getKey, Entry::getValue));
        return result;
    }

    private boolean useSharedDefaultAppConsumerForUnconfiguredHandlers() {
        return unconfiguredHandlerConsumerMode(fluxzero.propertySource())
               == UnconfiguredHandlerConsumerMode.DEFAULT_APP_CONSUMER;
    }

    private UnconfiguredHandlerConsumerMode unconfiguredHandlerConsumerMode(PropertySource propertySource) {
        String configuredMode = propertySource.get(
                ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY);
        if (configuredMode != null) {
            return parseUnconfiguredHandlerConsumerMode(configuredMode);
        }
        return defaultsVersionUsesPerHandlerConsumers(propertySource)
                ? UnconfiguredHandlerConsumerMode.PER_HANDLER
                : UnconfiguredHandlerConsumerMode.DEFAULT_APP_CONSUMER;
    }

    private UnconfiguredHandlerConsumerMode parseUnconfiguredHandlerConsumerMode(String mode) {
        String normalized = mode.trim();
        if (ConsumerConfiguration.PER_HANDLER_CONSUMER_MODE.equalsIgnoreCase(normalized)) {
            return UnconfiguredHandlerConsumerMode.PER_HANDLER;
        }
        if (ConsumerConfiguration.DEFAULT_APP_CONSUMER_MODE.equalsIgnoreCase(normalized)) {
            return UnconfiguredHandlerConsumerMode.DEFAULT_APP_CONSUMER;
        }
        throw new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                "Invalid unconfigured handler consumer mode",
                "Property `%s` must be `%s` or `%s`, but found `%s`.".formatted(
                        ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY,
                        ConsumerConfiguration.PER_HANDLER_CONSUMER_MODE,
                        ConsumerConfiguration.DEFAULT_APP_CONSUMER_MODE, mode),
                "Set a supported mode, or remove the property to derive the default from `%s`.".formatted(
                        ApplicationProperties.DEFAULTS_VERSION_PROPERTY),
                null, mode));
    }

    private boolean defaultsVersionUsesPerHandlerConsumers(PropertySource propertySource) {
        try {
            return ApplicationProperties.defaultsVersionAtLeast(propertySource, PER_HANDLER_DEFAULTS_VERSION);
        } catch (IllegalArgumentException e) {
            throw new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                    "Invalid Fluxzero defaults version",
                    e.getMessage(),
                    "Set a date like `2026.05.20`, or remove the property to use compatibility defaults.",
                    null, propertySource.get(ApplicationProperties.DEFAULTS_VERSION_PROPERTY)), e);
        }
    }

    private List<Object> fallbackHandlers(List<?> handlers, Collection<ConsumerConfiguration> configurations) {
        var fallbackHandlers = new ArrayList<Object>(handlers);
        normalizeConfigurations(configurations.stream()).forEach(config -> {
            var matches = fallbackHandlers.stream().filter(h -> config.getHandlerFilter().test(h)).toList();
            if (config.exclusive() && !config.conditionallyExclusive()) {
                fallbackHandlers.removeAll(matches);
            }
        });
        return fallbackHandlers;
    }

    private Stream<ConsumerConfiguration> sharedDefaultConsumerConfiguration(MessageType messageType) {
        return ofNullable(fluxzero.configuration().defaultConsumerConfigurations().get(messageType))
                .map(configuration -> configuration.toBuilder()
                        .name(defaultApplicationConsumerName(
                                fluxzero.client().name(), configuration.getName()))
                        .build()).stream();
    }

    private Stream<ConsumerConfiguration> defaultConsumerConfigurations(
            MessageType messageType, List<Object> handlers) {
        if (handlers.isEmpty()) {
            return Stream.empty();
        }
        ConsumerConfiguration template = fluxzero.configuration().defaultConsumerConfigurations().get(messageType);
        if (template == null) {
            return Stream.empty();
        }
        List<Class<?>> handlerTypes = handlers.stream().map(ReflectionUtils::asClass).distinct().collect(toList());
        Map<String, Integer> simpleNameCounts = new HashMap<>();
        handlerTypes.stream().map(TestFixture::consumerSimpleName)
                .forEach(name -> simpleNameCounts.merge(name, 1, Integer::sum));
        return handlerTypes.stream().map(handlerType -> defaultConsumerConfiguration(
                fluxzero.client().name(), template, handlerType,
                simpleNameCounts.get(consumerSimpleName(handlerType)) > 1));
    }

    private List<ConsumerConfiguration> normalizeConfigurations(Stream<ConsumerConfiguration> configurations) {
        return configurations
                .sorted(Comparator.comparing(ConsumerConfiguration::exclusive))
                .map(ConsumerConfiguration::ordered)
                .map(ConsumerConfiguration::substituteProperties)
                .collect(toMap(ConsumerConfiguration::getName, Function.identity(),
                               TestFixture::mergeConfigurations, LinkedHashMap::new))
                .values().stream().toList();
    }

    private static ConsumerConfiguration mergeConfigurations(ConsumerConfiguration a, ConsumerConfiguration b) {
        if (a.equals(b)) {
            return a.toBuilder().handlerFilter(a.getHandlerFilter().or(b.getHandlerFilter())).build();
        }
        throw new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                "Consumer name is configured more than once",
                "Fluxzero found multiple different consumer configurations named `%s`.".formatted(a.getName()),
                "Use unique consumer names, or make the repeated @Consumer configurations identical so "
                + "Fluxzero can merge their handler filters.",
                null, a.getName()));
    }

    private static ConsumerConfiguration defaultConsumerConfiguration(
            String applicationName, ConsumerConfiguration template, Class<?> handlerType, boolean includePackageName) {
        Predicate<Object> handlerFilter = h -> ReflectionUtils.asClass(h).equals(handlerType);
        return template.toBuilder()
                .name(defaultConsumerName(applicationName, handlerType, includePackageName))
                .handlerFilter(template.getHandlerFilter().and(handlerFilter))
                .build();
    }

    private static String defaultApplicationConsumerName(String applicationName, String consumerName) {
        return applicationName == null || applicationName.isBlank() ? consumerName
                : "%s_%s".formatted(applicationName, consumerName);
    }

    private static String defaultConsumerName(String applicationName, Class<?> handlerType,
                                              boolean includePackageName) {
        String handlerName = includePackageName ? handlerType.getName() : consumerSimpleName(handlerType);
        String sanitizedHandlerName = handlerName.replace('.', '_').replace('$', '_');
        return defaultApplicationConsumerName(applicationName, sanitizedHandlerName);
    }

    private static String consumerSimpleName(Class<?> handlerType) {
        String simpleName = handlerType.getSimpleName();
        return simpleName == null || simpleName.isBlank() ? handlerType.getName() : simpleName;
    }

    private enum UnconfiguredHandlerConsumerMode {
        DEFAULT_APP_CONSUMER,
        PER_HANDLER
    }

    protected Stream<HasLocalHandlers> localHandlerRegistries(Fluxzero fluxzero) {
        return ObjectUtils.concat(
                nonScheduleLocalHandlerRegistries(fluxzero),
                scheduleLocalHandlerRegistry(fluxzero).stream());
    }

    protected Stream<HasLocalHandlers> nonScheduleLocalHandlerRegistries(Fluxzero fluxzero) {
        return ObjectUtils.concat(
                Stream.of(
                        fluxzero.commandGateway(),
                        fluxzero.queryGateway(),
                        fluxzero.eventGateway(),
                        fluxzero.eventStore(),
                        fluxzero.errorGateway(),
                        fluxzero.webRequestGateway(),
                        fluxzero.metricsGateway()),
                fluxzero.documentStore() instanceof DefaultDocumentStore s ? Stream.of(s) : Stream.empty());
    }

    protected Optional<HasLocalHandlers> scheduleLocalHandlerRegistry(Fluxzero fluxzero) {
        return fluxzero.messageScheduler() instanceof DefaultMessageScheduler s ? Optional.of(s) : Optional.empty();
    }

    /**
     * Register additional handlers with the test fixture.
     * <p>
     * For async test fixtures, make sure all handlers of the same consumer are registered together, i.e. either via one
     * of the test fixture creator methods, or all at the same time via registerHandlers. If handlers that share the
     * same consumer are registered separately, an exception will be raised.
     */
    public TestFixture registerHandlers(Object... handlers) {
        return registerHandlers(Arrays.asList(handlers));
    }

    /**
     * Registers {@link Upcast}/{@link Downcast} handlers for the test fixture's serializer.
     */
    public TestFixture registerCasters(Object... casterCandidates) {
        return modifyFixture(fixture -> fixture.getFluxzero().serializer().registerCasters(casterCandidates));
    }

    @Override
    public TestFixture withClock(Clock clock) {
        return modifyFixture(fixture -> fixture.setClock(clock));
    }

    @Override
    public TestFixture atFixedTime(Instant time) {
        return withClock(Clock.fixed(time, ZoneId.systemDefault()));
    }

    @Override
    public TestFixture withProperty(String name, Object value) {
        return modifyFixture(
                fixture -> fixture.testProperties.compute(name, (k, v) -> value == null ? null : value.toString()));
    }

    @Override
    public TestFixture withBean(Object bean) {
        return modifyFixture(fixture -> fixture.beanParameterResolver.registerBean(bean));
    }

    @Override
    public TestFixture ignoringErrors() {
        return modifyFixture(fixture -> fixture.ignoreErrorsInGiven = true);
    }

    @Override
    public TestFixture advanceTimeIncrementally(boolean enabled) {
        return modifyFixture(fixture -> fixture.skipScheduleDeadlines = !enabled);
    }

    protected TestFixture modifyFixture(ThrowingConsumer<TestFixture> modifier) {
        modifiers.add(modifier);
        return fluxzero.apply(fc -> {
            modifier.accept(this);
            return this;
        });
    }

    /*
        given
     */

    @Override
    public TestFixture givenCommands(Object... commands) {
        Class<?> callerClass = getCallerClass();
        for (Object command : commands) {
            givenModification(fixture -> fixture.asMessages(callerClass, command).forEach(
                    c -> fixture.getDispatchResult(fixture.getFluxzero().commandGateway().send(c))));
        }
        return this;
    }

    @Override
    public TestFixture givenCommandsByUser(Object user, Object... commands) {
        Class<?> callerClass = getCallerClass();
        for (Object command : commands) {
            givenModification(
                    fixture -> fixture.asMessages(callerClass, command).map(c -> fixture.addUser(getUser(user), c))
                            .forEach(c -> fixture.getDispatchResult(
                                    fixture.getFluxzero().commandGateway().send(c))));
        }
        return this;
    }

    @Override
    public TestFixture givenCustom(String topic, Object... requests) {
        Class<?> callerClass = getCallerClass();
        for (Object request : requests) {
            givenModification(fixture -> fixture.asMessages(callerClass, request).forEach(
                    c -> fixture.getDispatchResult(fixture.getFluxzero().customGateway(topic).send(c))));
        }
        return this;
    }

    @Override
    public TestFixture givenCustomByUser(Object user, String topic, Object... requests) {
        Class<?> callerClass = getCallerClass();
        for (Object request : requests) {
            givenModification(
                    fixture -> fixture.asMessages(callerClass, request).map(c -> fixture.addUser(getUser(user), c))
                            .forEach(c -> fixture.getDispatchResult(
                                    fixture.getFluxzero().customGateway(topic).send(c))));
        }
        return this;
    }

    @Override
    public TestFixture givenAppliedEvents(Id<?> aggregateId, Object... events) {
        return givenAppliedEvents(aggregateId.toString(), aggregateId.getType(), events);
    }

    @Override
    public TestFixture givenAppliedEvents(String aggregateId, Class<?> aggregateClass, Object... events) {
        Class<?> callerClass = getCallerClass();
        return givenModificationWithTrace(describeFixtureAction("applied events to", aggregateClass),
                                          fixture -> fixture.applyEvents(
                                                  aggregateId, aggregateClass, fixture.getFluxzero(),
                                                  fixture.asMessages(callerClass, events).toList()));
    }

    @Override
    public TestFixture givenEvents(Object... events) {
        Class<?> callerClass = getCallerClass();
        for (Object event : events) {
            givenModification(fixture -> fixture.asMessages(callerClass, event)
                    .forEach(e -> fixture.getFluxzero().eventGateway().publish(e)));
        }
        return this;
    }

    @Override
    public TestFixture givenMetrics(Object... metrics) {
        Class<?> callerClass = getCallerClass();
        for (Object metric : metrics) {
            givenModification(fixture -> fixture.asMessages(callerClass, metric)
                    .forEach(m -> fixture.getFluxzero().metricsGateway().publish(m)));
        }
        return this;
    }

    @Override
    public TestFixture givenDocument(Object document) {
        Class<?> callerClass = getCallerClass();
        return givenModification(
                fixture -> fixture.getFluxzero().documentStore().index(fixture.parseObject(document, callerClass))
                        .get());
    }

    @Override
    public TestFixture givenDocument(Object document, Object collection) {
        return givenDocument(document, getFluxzero().identityProvider().nextTechnicalId(), collection);
    }

    @Override
    public TestFixture givenDocument(Object document, Object id, Object collection) {
        return givenDocument(document, id, collection, null);
    }

    @Override
    public TestFixture givenDocument(Object document, Object id, Object collection, Instant timestamp) {
        return givenDocument(document, id, collection, timestamp, timestamp);
    }

    @Override
    public TestFixture givenDocument(Object document, Object id, Object collection, Instant timestamp, Instant end) {
        Class<?> callerClass = getCallerClass();
        return givenModification(fixture -> fixture.getFluxzero().documentStore()
                .index(fixture.parseObject(document, callerClass), id, collection, timestamp, end).get());
    }

    @Override
    public TestFixture givenDocuments(Object collection, Object firstDocument, Object... otherDocuments) {
        Class<?> callerClass = getCallerClass();
        for (Object document : Stream.concat(Stream.of(firstDocument), Arrays.stream(otherDocuments)).toList()) {
            givenModification(fixture -> fixture.getFluxzero().documentStore()
                    .index(fixture.<Object>parseObject(document, callerClass), collection).get());
        }
        return this;
    }

    @Override
    public TestFixture givenStateful(Object stateful) {
        return givenDocument(stateful);
    }

    @Override
    public TestFixture givenSchedules(Schedule... schedules) {
        Class<?> callerClass = getCallerClass();
        givenModification(fixture -> fixture.asMessages(callerClass, (Object[]) schedules).forEach(
                s -> run(
                        () -> fluxzero.messageScheduler().schedule((Schedule) s, false, Guarantee.STORED).get())));
        return this;
    }

    @Override
    public TestFixture givenScheduledCommands(Schedule... commands) {
        Class<?> callerClass = getCallerClass();
        givenModification(fixture -> fixture.asMessages(callerClass, (Object[]) commands).forEach(
                s -> run(
                        () -> fluxzero.messageScheduler().scheduleCommand((Schedule) s, false, Guarantee.STORED)
                                .get())));
        return this;
    }

    @Override
    public TestFixture givenExpiredSchedules(Object... schedules) {
        TestFixture self = this;
        var classes = Arrays.stream(schedules).filter(ReflectionUtils::isClass).map(ReflectionUtils::asClass).collect(
                toSet());
        for (Class<?> c : classes) {
            if (getFluxzero().client().getSchedulingClient() instanceof LocalSchedulingClient local) {
                var match = local.getFutureSchedules(getFluxzero().serializer())
                        .stream().filter(s -> c.isAssignableFrom(s.getPayloadClass()))
                        .min(Comparator.comparing(Schedule::getDeadline))
                        .orElseThrow(() -> new IllegalStateException("No future schedule of type " + c + " found"));
                self = self.givenTimeAdvancedTo(match.getDeadline());
            }
        }
        List<Schedule> mappedSchedules = Arrays.stream(schedules)
                .filter(Predicate.not(ReflectionUtils::isClass))
                .map(p -> p instanceof Schedule s ? s :
                new Schedule(p, getFluxzero().identityProvider().nextTechnicalId(), getCurrentTime())).toList();
        self = self.givenSchedules(mappedSchedules.toArray(Schedule[]::new));
        var lastDeadline = mappedSchedules.stream().map(Schedule::getDeadline).max(Comparator.naturalOrder()).orElseGet(
                self::getCurrentTime);
        return self.getCurrentTime().isBefore(lastDeadline) ? givenTimeAdvancedTo(lastDeadline) : self;
    }

    @Override
    public TestFixture withCookie(HttpCookie cookie) {
        return addCookie(cookie);
    }

    @Override
    public TestFixture withHeader(String headerName, String... headerValues) {
        if (headerValues.length == 0 || (headerValues.length == 1 && headerValues[0] == null)) {
            headers.remove(headerName);
            return this;
        }
        headers.put(headerName, Arrays.asList(headerValues));
        return this;
    }

    @Override
    public TestFixture givenTimeAdvancedTo(Instant instant) {
        return givenModification(fixture -> fixture.advanceTimeTo(instant));
    }

    @Override
    public TestFixture givenElapsedTime(Duration duration) {
        return givenModification(fixture -> fixture.advanceTimeBy(duration));
    }

    @Override
    public TestFixture givenWebRequest(WebRequest webRequest) {
        Class<?> callerClass = getCallerClass();
        return givenModification(fixture -> fixture.executeWebRequest(fixture.parseObject(webRequest, callerClass)));
    }

    @Override
    public TestFixture givenWebRequestByUser(Object user, WebRequest webRequest) {
        Class<?> callerClass = getCallerClass();
        return givenModification(fixture -> fixture.executeWebRequest(
                fixture.addUser(getUser(user), fixture.parseObject(webRequest, callerClass))));
    }

    @Override
    public TestFixture givenPost(String path, Object payload) {
        return givenWebRequest(WebRequest.post(path).payload(payload).build());
    }

    @Override
    public TestFixture givenPostByUser(Object user, String path, Object payload) {
        return givenWebRequestByUser(user, WebRequest.post(path).payload(payload).build());
    }

    @Override
    public TestFixture givenPut(String path, Object payload) {
        return givenWebRequest(WebRequest.put(path).payload(payload).build());
    }

    @Override
    public TestFixture givenPutByUser(Object user, String path, Object payload) {
        return givenWebRequestByUser(user, WebRequest.put(path).payload(payload).build());
    }

    @Override
    public TestFixture givenPatch(String path, Object payload) {
        return givenWebRequest(WebRequest.patch(path).payload(payload).build());
    }

    @Override
    public TestFixture givenPatchByUser(Object user, String path, Object payload) {
        return givenWebRequestByUser(user, WebRequest.patch(path).payload(payload).build());
    }

    @Override
    public TestFixture givenDelete(String path) {
        return givenWebRequest(WebRequest.delete(path).build());
    }

    @Override
    public TestFixture givenDeleteByUser(Object user, String path) {
        return givenWebRequestByUser(user, WebRequest.delete(path).build());
    }

    @Override
    public TestFixture givenGet(String path) {
        return givenWebRequest(WebRequest.get(path).build());
    }

    @Override
    public TestFixture givenGetByUser(Object user, String path) {
        return givenWebRequestByUser(user, WebRequest.get(path).build());
    }

    @Override
    public TestFixture given(ThrowingConsumer<Fluxzero> condition) {
        return givenModificationWithTrace("custom task", fixture -> condition.accept(fixture.getFluxzero()));
    }

    protected TestFixture givenModification(ThrowingConsumer<TestFixture> modifier) {
        try {
            fixtureResult.getTrace().startPhase("given");
            GivenWhenThenAssertionError.useTrace(this::renderTrace);
            return modifyFixture(fixture -> {
                modifier.accept(fixture);
                fixture.waitForConsumers();
            });
        } catch (Throwable e) {
            if (ignoreErrorsInGiven) {
                log.info("Ignoring error in given:", e);
                return this;
            }
            throw new IllegalStateException(enrichFailureMessage("Failed to execute given", e), e);
        }
    }

    private TestFixture givenModificationWithTrace(String description, ThrowingConsumer<TestFixture> modifier) {
        return givenModification(fixture -> fixture.executeGivenAction(description, modifier));
    }

    private void executeGivenAction(String description, ThrowingConsumer<TestFixture> modifier) throws Exception {
        FixtureTrace.ActionScope traceScope = fixtureResult.getTrace().beginAction(description);
        Throwable error = null;
        try {
            modifier.accept(this);
        } catch (RuntimeException | Error e) {
            error = e;
            throw e;
        } catch (Exception e) {
            error = e;
            throw e;
        } finally {
            traceScope.close(null, error);
        }
    }

    /*
        when
     */

    @Override
    public Then<Object> whenCommand(Object command) {
        Message message = trace(command);
        return executeWhen(fc -> message.getPayload() == null
                ? null : getDispatchResult(fc.commandGateway().send(message)));
    }

    @Override
    public Then<Object> whenCommandByUser(Object user, Object command) {
        Message message = trace(command);
        return executeWhen(fc -> message.getPayload() == null
                ? null : getDispatchResult(fc.commandGateway().send(addUser(getUser(user), message))));
    }

    @Override
    public Then<Object> whenQuery(Object query) {
        Message message = trace(query);
        return executeWhen(fc -> message.getPayload() == null
                ? null : getDispatchResult(fc.queryGateway().send(message)));
    }

    @Override
    public Then<Object> whenQueryByUser(Object user, Object query) {
        Message message = trace(query);
        return executeWhen(fc -> message.getPayload() == null
                ? null : getDispatchResult(fc.queryGateway().send(addUser(getUser(user), message))));
    }

    @Override
    public Then<Object> whenCustom(String topic, Object request) {
        Message message = trace(request);
        return executeWhen(fc -> message.getPayload() == null
                ? null : getDispatchResult(fc.customGateway(topic).send(message)));
    }

    @Override
    public Then<Object> whenCustomByUser(Object user, String topic, Object request) {
        Message message = trace(request);
        return executeWhen(fc -> message.getPayload() == null
                ? null : getDispatchResult(fc.customGateway(topic).send(addUser(getUser(user), message))));
    }

    @Override
    public Then<?> whenEvent(Object event) {
        Message message = trace(event);
        return message.getPayload() == null ? whenNothingHappens()
                : executeWhen(fc -> fc.eventGateway().publish(message, Guarantee.STORED).get());
    }

    @Override
    public Then<?> whenMetric(Object metric) {
        Message message = trace(metric);
        return message.getPayload() == null ? whenNothingHappens()
                : executeWhen(fc -> ((DefaultMetricsGateway) fc.metricsGateway()).sendAndForget(message,
                                                                                                Guarantee.STORED)
                        .get());
    }

    @Override
    public Then<?> whenEventsAreApplied(String aggregateId, Class<?> aggregateClass, Object... events) {
        Class<?> callerClass = getCallerClass();
        return executeWhenWithTrace(describeFixtureAction("applying events to", aggregateClass),
                                    fc -> {
                                        applyEvents(aggregateId, aggregateClass, fc,
                                                    asMessages(callerClass, events).collect(toList()));
                                        return null;
                                    });
    }

    @Override
    public <R> Then<List<R>> whenSearching(Object collection, UnaryOperator<Search> searchQuery) {
        return executeWhenWithTrace(describeFixtureAction("searching", collection),
                                    fc -> searchQuery.apply(fc.documentStore().search(collection)).fetchAll());
    }

    @Override
    public Then<Object> whenWebRequest(WebRequest request) {
        WebRequest message = trace(request);
        return doWhenWebRequest(message);
    }

    @Override
    public Then<Object> whenWebRequestByUser(Object user, WebRequest request) {
        WebRequest message = addUser(getUser(user), trace(request));
        return doWhenWebRequest(message);
    }

    @Override
    public Then<Object> whenPost(String path, Object payload) {
        return whenWebRequest(WebRequest.post(path).payload(payload).build());
    }

    @Override
    public Then<Object> whenPostByUser(Object user, String path, Object payload) {
        return whenWebRequestByUser(user, WebRequest.post(path).payload(payload).build());
    }

    @Override
    public Then<Object> whenPut(String path, Object payload) {
        return whenWebRequest(WebRequest.put(path).payload(payload).build());
    }

    @Override
    public Then<Object> whenPutByUser(Object user, String path, Object payload) {
        return whenWebRequestByUser(user, WebRequest.put(path).payload(payload).build());
    }

    @Override
    public Then<Object> whenPatch(String path, Object payload) {
        return whenWebRequest(WebRequest.patch(path).payload(payload).build());
    }

    @Override
    public Then<Object> whenPatchByUser(Object user, String path, Object payload) {
        return whenWebRequestByUser(user, WebRequest.patch(path).payload(payload).build());
    }

    @Override
    public Then<Object> whenDelete(String path) {
        return whenWebRequest(WebRequest.delete(path).build());
    }

    @Override
    public Then<Object> whenDeleteByUser(Object user, String path) {
        return whenWebRequestByUser(user, WebRequest.delete(path).build());
    }

    @Override
    public Then<Object> whenGet(String path) {
        return whenWebRequest(WebRequest.get(path).build());
    }

    @Override
    public Then<Object> whenGetByUser(Object user, String path) {
        return whenWebRequestByUser(user, WebRequest.get(path).build());
    }

    Then<Object> doWhenWebRequest(WebRequest message) {
        return executeWhen(fc -> {
            try {
                var response = executeWebRequest(message);
                if (response != null && synchronous
                    && (response.getPayload() != null || !isWebsocket(message.getMethod()))) {
                    registerWebResponse(response);
                }
                return response;
            } catch (Throwable e) {
                try {
                    if (synchronous && !isWebsocket(message.getMethod())) {
                        registerWebResponse(fluxzero.configuration().webResponseMapper().map(e));
                    }
                } catch (Throwable ignored) {
                }
                throw e;
            }
        });
    }

    @Override
    public Then<?> whenScheduleExpires(Object schedule) {
        if (ReflectionUtils.ifClass(schedule) instanceof Class<?> c) {
            if (getFluxzero().client().getSchedulingClient() instanceof LocalSchedulingClient local) {
                var match = local.getFutureSchedules(getFluxzero().serializer())
                        .stream().filter(s -> c.isAssignableFrom(s.getPayloadClass()))
                        .min(Comparator.comparing(Schedule::getDeadline))
                        .orElseThrow(() -> new IllegalStateException("No future schedule of type " + c + " found"));
                return whenTimeAdvancesTo(match.getDeadline());
            }
        }
        Message message = trace(schedule);
        return executeWhen(fc -> {
            if (message instanceof Schedule s) {
                fc.messageScheduler().schedule(s);
                if (s.getDeadline().isAfter(getCurrentTime())) {
                    advanceTimeTo(s.getDeadline());
                }
            } else {
                fc.messageScheduler().schedule(message, getCurrentTime());
            }
            return null;
        });
    }

    @Override
    @SneakyThrows
    public Then<?> whenTimeElapses(Duration duration) {
        return executeWhen(fc -> {
            advanceTimeBy(duration);
            return null;
        });
    }

    @Override
    public <R> Then<R> whenUpcasting(Object value) {
        Class<?> callerClass = getCallerClass();
        return executeWhenWithTrace("upcasting", fc -> parseObject(value, callerClass));
    }

    @Override
    @SneakyThrows
    public Then<?> whenTimeAdvancesTo(Instant instant) {
        return executeWhen(fc -> {
            advanceTimeTo(instant);
            return null;
        });
    }

    @Override
    public Then<?> whenExecuting(ThrowingConsumer<Fluxzero> action) {
        return executeWhenWithTrace("custom task", fc -> {
            action.accept(fc);
            return null;
        });
    }

    @Override
    public <R> Then<R> whenApplying(ThrowingFunction<Fluxzero, R> action) {
        return executeWhenWithTrace("applying", action);
    }

    @Override
    public Then<?> whenNothingHappens() {
        return executeWhenWithTrace("nothing happens", fc -> null);
    }

    private <R> Then<R> executeWhen(ThrowingFunction<Fluxzero, R> action) {
        return executeWhen(null, action);
    }

    private <R> Then<R> executeWhenWithTrace(String description, ThrowingFunction<Fluxzero, R> action) {
        return executeWhen(description, action);
    }

    private <R> Then<R> executeWhen(String description, ThrowingFunction<Fluxzero, R> action) {
        return fluxzero.apply(fc -> {
            fixtureResult.getTrace().startPhase("when");
            GivenWhenThenAssertionError.useTrace(this::renderTrace);
            fixtureResult.setWhenPhaseStarted(true);
            waitForConsumers();
            resetMocks();
            fixtureResult.setCollectingResults(true);
            FixtureTrace.ActionScope traceScope = description == null
                    ? null : fixtureResult.getTrace().beginAction(description);
            Object result = null;
            Throwable error = null;
            try {
                result = action.apply(fc);
                if (result instanceof CompletableFuture<?> future) {
                    result = getDispatchResult(future);
                }
            } catch (Throwable e) {
                error = e;
                registerError(e);
                result = e;
            } finally {
                if (traceScope != null) {
                    traceScope.close(result, error);
                }
            }
            fixtureResult.setResult(result);
            waitForConsumers();
            return new ResultValidator<>(this);
        });
    }

    /*
        helper
     */

    @SneakyThrows
    protected WebResponse executeWebRequest(WebRequest request) {
        if (isWebsocket(request.getMethod()) && !request.getMetadata().containsKey("sessionId")) {
            request = request.addMetadata("sessionId", "testSession");
        }
        if (!headers.isEmpty()) {
            request = request.toBuilder().headers(headers).build();
        }
        if (!cookies.isEmpty()) {
            var builder = request.toBuilder();
            for (HttpCookie cookie : cookies) {
                if (request.getCookie(cookie.getName()).isEmpty()) {
                    builder.cookie(cookie);
                }
            }
            request = builder.build();
        }
        WebResponse response = getDispatchResult(getFluxzero().webRequestGateway().send(request));
        response.getCookies().forEach(this::addCookie);
        return response;
    }

    protected TestFixture addCookie(HttpCookie cookie) {
        cookies.remove(cookie);
        if (!cookie.hasExpired()) {
            cookies.add(cookie);
        }
        return this;
    }

    protected User getUser(Object userOrId) {
        if (userOrId == null) {
            return null;
        }
        User result = userOrId instanceof User user ? user
                : fluxzero.apply(fc -> fc.userProvider().getUserById(userOrId));
        if (result == null) {
            throw new UnauthorizedException("User %s could not be provided".formatted(userOrId));
        }
        return result;
    }

    protected void applyEvents(String aggregateId, Class<?> aggregateClass, Fluxzero fc, List<Message> events) {
        fc.aggregateRepository().load(aggregateId, aggregateClass).apply(events.stream().map(
                        e -> e.withMetadata(e.getMetadata().with(
                                Entity.AGGREGATE_ID_METADATA_KEY, aggregateId,
                                Entity.AGGREGATE_TYPE_METADATA_KEY, aggregateClass.getName())))
                                                                                 .toList());
    }

    protected List<Schedule> getFutureSchedules() {
        return getFluxzero().client().getSchedulingClient() instanceof LocalSchedulingClient local ?
                local.getFutureSchedules(getFluxzero().serializer()) : emptyList();
    }

    protected SerializedSchedule getSchedule(String scheduleId) {
        return getFluxzero().client().getSchedulingClient() instanceof LocalSchedulingClient local ?
                local.getSchedule(scheduleId) : null;
    }

    protected void waitForConsumers() {
        if (synchronous) {
            return;
        }
        synchronized (consumers) {
            if (!checkConsumers()) {
                try {
                    consumers.wait(consumerTimeout.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!checkConsumers() && warnOnPendingConsumers) {
                log.warn("Some consumers in the test fixture did not finish processing all messages. "
                         + "This may cause your test to fail. Waiting consumers: {}",
                         consumers.entrySet().stream()
                                 .filter(e -> !e.getValue().isEmpty())
                                 .map(e -> e.getKey() + " : " + e.getValue().stream()
                                         .map(m -> m.getPayload() == null
                                                 ? "Void" : m.getPayload().getClass().getSimpleName()).collect(
                                                 Collectors.joining(", "))).collect(toList()));
            }
        }
    }

    protected TestFixture reset() {
        resetMocks();
        var previousResult = fixtureResult;
        fixtureResult = new FixtureResult();
        fixtureResult.setPreviousResult(previousResult);
        GivenWhenThenAssertionError.useTrace(this::renderTrace);
        return this;
    }

    ImmutableFixtureResult getFixtureResult() {
        return ImmutableFixtureResult.from(fixtureResult);
    }

    void registerWebParameter(String name, String value) {
        fixtureResult.getKnownWebParams().put(name, value);
    }

    protected void resetMocks() {
        if (spying) {
            ((SpyingClient) fluxzero.client()).resetMocks();
            ((SpyingFluxzero) fluxzero).resetMocks();
        }
    }

    protected void advanceTimeBy(Duration duration) {
        advanceTimeTo(getCurrentTime().plus(duration));
    }

    protected void advanceTimeTo(Instant instant) {
        try (FixtureTrace.TraceScope ignored = fixtureResult.getTrace().beginTimeShift(describeTimeShift(instant))) {
            setClock(Clock.fixed(instant, ZoneId.systemDefault()));
        }
    }

    private String describeTimeShift(Instant instant) {
        Duration duration = Duration.between(getCurrentTime(), instant);
        return duration.isNegative() ? formatDuration(duration.negated()) + " moved back"
                : formatDuration(duration) + " elapsed";
    }

    private static String formatDuration(Duration duration) {
        if (duration.isZero()) {
            return "0ms";
        }
        long millis = duration.toMillis();
        if (millis == 0L) {
            return "<1ms";
        }
        long days = millis / Duration.ofDays(1).toMillis();
        millis %= Duration.ofDays(1).toMillis();
        long hours = millis / Duration.ofHours(1).toMillis();
        millis %= Duration.ofHours(1).toMillis();
        long minutes = millis / Duration.ofMinutes(1).toMillis();
        millis %= Duration.ofMinutes(1).toMillis();
        long seconds = millis / Duration.ofSeconds(1).toMillis();
        millis %= Duration.ofSeconds(1).toMillis();
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "m");
        }
        if (seconds > 0) {
            parts.add(seconds + "s");
        }
        if (millis > 0) {
            parts.add(millis + "ms");
        }
        return String.join(" ", parts);
    }

    private static String describeFixtureAction(String action, Object target) {
        return target == null ? action : action + " " + describeFixtureActionTarget(target);
    }

    private static String describeFixtureActionTarget(Object target) {
        if (target instanceof Class<?> type) {
            return simpleTypeName(type);
        }
        return String.valueOf(target);
    }

    private static String simpleTypeName(Class<?> type) {
        if (type == null) {
            return "Unknown";
        }
        if (type.isArray()) {
            return simpleTypeName(type.getComponentType()) + "[]";
        }
        String simpleName = type.getSimpleName();
        return simpleName.isBlank() ? type.getName() : simpleName;
    }

    private static String describeHandlerMethod(Executable executable) {
        String parameters = Arrays.stream(executable.getParameterTypes())
                .map(TestFixture::simpleTypeName)
                .collect(Collectors.joining(", "));
        if (executable instanceof Method method) {
            return "%s(%s)".formatted(method.getName(), parameters);
        }
        return "%s(%s)".formatted(simpleTypeName(executable.getDeclaringClass()), parameters);
    }

    protected void setClock(Clock clock) {
        SchedulingClient schedulingClient = getFluxzero().client().getSchedulingClient();
        if (schedulingClient instanceof LocalSchedulingClient) {
            if (!skipScheduleDeadlines) {
                var target = clock.instant();
                Instant previousDeadline = null;
                Instant nextDeadline = getNextTaskDeadline(target);
                while (nextDeadline != null && !Objects.equals(previousDeadline, nextDeadline)) {
                    Clock nextClock = Clock.fixed(nextDeadline, ZoneId.systemDefault());
                    getFluxzero().withClock(nextClock);
                    previousDeadline = nextDeadline;
                    nextDeadline = getNextTaskDeadline(target);
                }
            }
            getFluxzero().withClock(clock);
        } else {
            getFluxzero().withClock(clock);
            log.warn("Could not update clock of scheduling client. Timing tests may not work.");
        }
    }

    private Instant getNextTaskDeadline(Instant target) {
        if (getFluxzero().taskScheduler() instanceof InMemoryTaskScheduler scheduler) {
            return scheduler.getScheduledDeadlines().stream().filter(deadline -> !deadline.isAfter(target)).findFirst()
                    .orElse(null);
        }
        return getFutureSchedules().stream().map(Schedule::getDeadline).filter(deadline -> !deadline.isAfter(target))
                .findFirst().orElse(null);
    }

    protected void registerCommand(Message command) {
        fixtureResult.getCommands().add(command);
    }

    protected void registerQuery(Message query) {
        fixtureResult.getQueries().add(query);
    }

    protected void registerMetric(Message metric) {
        fixtureResult.getMetrics().add(metric);
    }

    protected void registerCustom(String topic, Message message) {
        fixtureResult.getCustomMessages().computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(message);
    }

    protected void registerEvent(Message event) {
        fixtureResult.getEvents().add(event);
    }

    protected void registerWebRequest(Message request) {
        fixtureResult.getWebRequests().add(request);
    }

    protected void registerWebResponse(WebResponse response) {
        if (!response.getMetadata().contains("function", "ack")) {
            fixtureResult.getWebResponses().add(response);
        }
    }

    protected void registerSchedule(Schedule schedule) {
        fixtureResult.getSchedules().add(schedule);
    }

    protected void registerError(Throwable e) {
        fixtureResult.getErrors().addIfAbsent(e);
    }

    private ActiveHandler activeHandler(DeserializingMessage message, HandlerInvoker invoker) {
        return new ActiveHandler(
                message.getMessageId(),
                message.getPayloadClass(),
                invoker.getTargetClass(), invoker.getMethod(), invoker.isPassive(),
                message.getMessageType(), message.getTopic(), trackedConsumers(message, invoker));
    }

    private Set<ConsumerIdentity> trackedConsumers(DeserializingMessage message, HandlerInvoker invoker) {
        if (handledLocallyWithoutTracking(message, invoker)) {
            return Set.of();
        }
        return possibleConsumers(invoker.getTargetClass(), message.getMessageType()).stream()
                .filter(consumer -> Objects.equals(consumer.getTopic(), message.getTopic()))
                .collect(toSet());
    }

    private boolean handledLocallyWithoutTracking(DeserializingMessage message, HandlerInvoker invoker) {
        if (getLocalHandlerAnnotation(invoker.getTargetClass(), invoker.getMethod())
                .filter(LocalHandler::value)
                .isPresent()) {
            return true;
        }
        return invoker.getTargetClass().isAssignableFrom(message.getPayloadClass())
               && !ClientUtils.isSelfTracking(invoker.getTargetClass());
    }

    private Set<ConsumerIdentity> possibleConsumers(Class<?> handlerType, MessageType messageType) {
        HandlerConsumerKey key = new HandlerConsumerKey(handlerType, messageType);
        Set<ConsumerIdentity> result = handlerConsumers.get(key);
        if ((result == null || result.isEmpty()) && ClientUtils.isSelfTracking(handlerType)) {
            rememberConsumerAssignments(List.of(handlerType));
            result = handlerConsumers.get(key);
        }
        return result == null ? Set.of() : result;
    }

    private void assertNoSynchronousConsumerDeadlock(ActiveHandler nestedHandler) {
        if (!synchronous || !nestedHandler.getMessageType().isRequest()
            || !requestDispatches.contains(nestedHandler.getMessageId()) || nestedHandler.isPassive()
            || nestedHandler.getConsumers().isEmpty()) {
            return;
        }
        for (ActiveHandler waitingHandler : activeHandlers.get()) {
            List<ConsumerIdentity> sharedConsumers = nestedHandler.getConsumers().stream()
                    .filter(ConsumerIdentity::isSingleThreaded)
                    .filter(waitingHandler.getConsumers()::contains)
                    .toList();
            if (!sharedConsumers.isEmpty()) {
                throw new IllegalStateException("""
                        Synchronous TestFixture detected a production deadlock risk.

                        `%s` is handling `%s` and sends `%s` while waiting for a result.
                        `%s` handles that request, but both handlers may be assigned to the same consumer: %s.

                        In production a consumer processes one message at a time, so the second handler cannot run \
                        while the first handler is waiting.
                        Put these handlers on separate consumers, or configure this TestFixture with the same \
                        ConsumerConfiguration as production.""".formatted(
                        waitingHandler.describe(), waitingHandler.messageDescription(),
                        nestedHandler.messageDescription(), nestedHandler.describe(), sharedConsumers));
            }
        }
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    protected <R> R getDispatchResult(CompletableFuture<?> dispatchResult) {
        try {
            return (R) (synchronous
                    ? dispatchResult.get(0, MILLISECONDS)
                    : dispatchResult.get(resultTimeout.toMillis(), MILLISECONDS));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException timeout) {
                throw enrichTimeout(timeout);
            }
            throw e.getCause();
        } catch (TimeoutException e) {
            throw enrichTimeout(new TimeoutException(
                    "Test fixture did not receive a dispatch result in time. "
                    + "Perhaps some messages did not have handlers?"));
        }
    }

    private TimeoutException enrichTimeout(TimeoutException timeout) {
        TimeoutException result = new TimeoutException(fixtureResult.getTrace().timeoutMessage(
                ofNullable(timeout.getMessage()).orElse("Test fixture timed out while waiting for a dispatch result")));
        result.initCause(timeout);
        return result;
    }

    private String enrichFailureMessage(String message, Throwable cause) {
        String causeMessage = cause.getMessage();
        String enriched = causeMessage == null || causeMessage.isBlank() ? message : message + ": " + causeMessage;
        return fixtureResult.getTrace().appendTo(enriched);
    }

    private String renderTrace() {
        String body = renderTraceBody(fixtureResult);
        return body.isBlank() ? "" : "Test trace:" + System.lineSeparator()
                                    + System.lineSeparator() + body + System.lineSeparator();
    }

    private String renderTraceBody(FixtureResult result) {
        List<String> traces = new ArrayList<>();
        collectTraceBodies(result, traces);
        return String.join(System.lineSeparator() + "and then ", traces);
    }

    private void collectTraceBodies(FixtureResult result, List<String> traces) {
        if (result == null) {
            return;
        }
        collectTraceBodies(result.getPreviousResult(), traces);
        String trace = result.getTrace().renderBody();
        if (!trace.isBlank()) {
            traces.add(trace);
        }
    }

    void showTraceMessageType(MessageType messageType) {
        fixtureResult.getTrace().show(messageType);
    }

    void recordMissedTraceMessages(MessageType messageType, String topic, Collection<?> expectedMessages) {
        fixtureResult.getTrace().recordMissed(messageType, topic, expectedMessages);
    }

    void recordUnexpectedTraceMessages(MessageType messageType, String topic, Collection<?> unexpectedMessages) {
        fixtureResult.getTrace().recordUnexpected(messageType, topic, unexpectedMessages);
    }

    protected Stream<Message> asMessages(Class<?> callerClass, Object... messages) {
        return Arrays.stream(messages).flatMap(c -> {
            if (c == null) {
                return empty();
            }
            if (c instanceof Collection<?>) {
                return ((Collection<?>) c).stream();
            }
            if (c.getClass().isArray()) {
                return Arrays.stream((Object[]) c);
            }
            return Stream.of(c);
        }).flatMap(c -> {
            Object parsed = parseObject(c, callerClass);
            return parsed == null ? empty()
                    : parsed instanceof Collection<?> ? ((Collection<?>) parsed).stream()
                    : parsed.getClass().isArray() ? Arrays.stream((Object[]) parsed)
                    : Stream.of(parsed);
        }).map(Message::asMessage);
    }

    @SuppressWarnings("unchecked")
    protected <M extends Message> M trace(Object object) {
        Class<?> callerClass = getCallerClass();
        M result = (M) fluxzero.apply(fc -> asMessage(parseObject(object, callerClass)));
        registerAutomaticTrackSelfHandler(result);
        fixtureResult.setTracedMessage(result);
        return result;
    }

    protected void registerAutomaticTrackSelfHandler(Message message) {
        if (synchronous || message == null || message.getPayload() == null) {
            return;
        }
        Class<?> payloadClass = message.getPayloadClass();
        if (ClientUtils.isSelfTracking(payloadClass) && matchesTrackSelfConditions(payloadClass)
            && registeredTrackSelfHandlers.stream()
                .noneMatch(handlerType -> handlerType.isAssignableFrom(payloadClass))
            && registeredTrackSelfHandlers.add(payloadClass)) {
            registration = registration.merge(fluxzero.registerHandlers(payloadClass));
        }
    }

    private boolean matchesTrackSelfConditions(Class<?> payloadClass) {
        ConditionalOnProperty conditionalOnProperty =
                ReflectionUtils.getTypeAnnotation(payloadClass, ConditionalOnProperty.class);
        if (conditionalOnProperty != null) {
            String value = fluxzero.propertySource().get(conditionalOnProperty.value());
            if (value == null || !value.matches(conditionalOnProperty.pattern())) {
                return false;
            }
        }
        ConditionalOnMissingProperty conditionalOnMissingProperty =
                ReflectionUtils.getTypeAnnotation(payloadClass, ConditionalOnMissingProperty.class);
        if (conditionalOnMissingProperty != null) {
            String value = fluxzero.propertySource().get(conditionalOnMissingProperty.value());
            return value == null || value.isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    protected <M extends Message> M addUser(User user, M value) {
        Class<?> callerClass = getCallerClass();
        return (M) fluxzero.apply(fc -> asMessage(parseObject(value, callerClass)).addUser(user));
    }

    @SuppressWarnings("unchecked")
    protected <T> T parseObject(Object object, Class<?> callerClass) {
        if (object instanceof WebRequest message && WebUtils.hasPathParameter(message.getPath())) {
            String replacementUrl = message.getPath();
            var webParams = fixtureResult.getKnownWebParams();
            for (Map.Entry<String, String> entry : webParams.entrySet()) {
                replacementUrl = WebUtils.replacePathParameter(replacementUrl, entry.getKey(), entry.getValue());
            }
            var remaining = WebUtils.extractPathParameters(replacementUrl);
            if (remaining.size() > 1) {
                throw new IllegalStateException("Multiple path parameters are unknown: %s ".formatted(remaining));
            }
            if (remaining.size() == 1) {
                String value = lastResultValue().map(Object::toString).orElseThrow(
                        () -> new IllegalStateException(
                                "Path parameters is unknown: %s ".formatted(remaining.getFirst())));
                replacementUrl = WebUtils.replacePathParameter(replacementUrl, remaining.getFirst(), value);
                webParams.put(remaining.getFirst(), value);
            }
            object = message.toBuilder().url(replacementUrl).build();
        }
        if (object instanceof WebRequest message
            && message.getPayload() instanceof String payload && payload.endsWith(".json")) {
            return (T) message.toBuilder().payload(JsonUtils.fromFile(callerClass, payload, JsonNode.class))
                    .clearHeader("Content-Type").contentType(JSON_FORMAT).build();
        }
        if (object instanceof Message message) {
            return (T) message.withPayload(parseObject(message.getPayload(), callerClass));
        }
        if (object instanceof String && ((String) object).endsWith(".json")) {
            object = JsonUtils.fromFile(callerClass, (String) object);
        }
        if (object instanceof SerializedMessage s) {
            object = fluxzero.serializer().deserializeMessage(s, EVENT).toMessage();
        } else if (object instanceof SerializedObject<?> s) {
            SerializedObject<byte[]> eventBytes = s.data().getValue() instanceof byte[]
                    ? (SerializedObject<byte[]>) s
                    : fluxzero.serializer().normalize(s);
            object = fluxzero.serializer().deserialize(eventBytes);
        }
        return (T) object;
    }

    protected Optional<Object> lastResultValue() {
        if (fixtureResult.getPreviousResult() != null && fixtureResult.getPreviousResult()
                .getResult() instanceof Object v) {
            var value = v instanceof WebResponse r ? r.getPayload() instanceof Object rv ? rv : null : v;
            return ofNullable(value);
        }
        return Optional.empty();
    }

    protected boolean checkConsumers() {
        if (synchronous) {
            return true;
        }
        synchronized (consumers) {
            //either all consumer messages have been processed (aka removed), or they're schedules firing in the future
            if (consumers.values().stream().allMatch(l -> l.stream().allMatch(
                    m -> {
                        if (m instanceof Schedule s) {
                            //ensure schedule isn't canceled or expired
                            var currentSchedule = getSchedule(s.getScheduleId());
                            return currentSchedule == null || Instant.ofEpochMilli(currentSchedule.getTimestamp())
                                    .isAfter(getCurrentTime());
                        }
                        return false;
                    }))) {
                consumers.notifyAll();
                return true;
            }
        }
        return false;
    }

    @AllArgsConstructor
    protected static class GivenWhenThenInterceptor {
        private TestFixture testFixture;

        private final List<Schedule> publishedSchedules = new CopyOnWriteArrayList<>();
        private final Set<String> interceptedMessageIds = new CopyOnWriteArraySet<>();

        protected void interceptClientDispatch(MessageType messageType, String topic,
                                               String namespace, List<SerializedMessage> messages) {
            if (testFixture.fixtureResult.isCollectingResults()) {
                try {
                    testFixture.fluxzero.serializer()
                            .deserializeMessages(messages.stream()
                                                         .filter(m -> !interceptedMessageIds.contains(
                                                                 m.getMessageId())),
                                                 messageType)
                            .map(DeserializingMessage::toMessage)
                            .forEach(m -> monitorDispatch(m, messageType, topic, namespace, false));
                } catch (Exception ignored) {
                    log.warn("Failed to intercept a published message. This may cause your test to fail.");
                }
            }
        }

        public Message interceptDispatch(Message message, MessageType messageType, String topic) {
            return message;
        }

        public void monitorDispatch(Message message, MessageType messageType, String topic, String namespace,
                                    boolean request) {
            testFixture.fixtureResult.getTrace().monitorDispatch(message, messageType, topic, namespace);
            testFixture.registerAutomaticTrackSelfHandler(message);
            if (request) {
                testFixture.requestDispatches.add(message.getMessageId());
            }

            if (testFixture.fixtureResult.isCollectingResults()) {
                interceptedMessageIds.add(message.getMessageId());
            }

            if (messageType == SCHEDULE) {
                addMessage(publishedSchedules, (Schedule) message);
            }

            synchronized (testFixture.consumers) {
                testFixture.consumers.entrySet().stream()
                        .filter(t -> {
                            var consumer = t.getKey();
                            String consumerNamespace = ofNullable(consumer.getConfiguration().getNamespace()).orElseGet(
                                    () -> testFixture.getFluxzero().client().namespace());
                            return (

                                    //message type and topic match
                                    ((consumer.getMessageType() == messageType
                                      && Objects.equals(consumer.getTopic(), topic))
                                     || (consumer.getMessageType() == NOTIFICATION && messageType == EVENT))

                                    //type filter matches
                                    && ofNullable(consumer.getConfiguration().getTypeFilter())
                                            .map(f -> message.getPayload().getClass().getName().matches(f))
                                            .orElse(true)

                                    //namespace matches
                                    && Objects.equals(consumerNamespace, namespace)

                            );
                        }).forEach(e -> addMessage(e.getValue(), message));
            }

            if (captureMessage(message)) {
                switch (messageType) {
                    case COMMAND -> testFixture.registerCommand(message);
                    case QUERY -> testFixture.registerQuery(message);
                    case EVENT -> testFixture.registerEvent(message);
                    case SCHEDULE -> testFixture.registerSchedule((Schedule) message);
                    case WEBREQUEST -> testFixture.registerWebRequest(message);
                    case WEBRESPONSE -> testFixture.registerWebResponse((WebResponse) message);
                    case METRICS -> testFixture.registerMetric(message);
                    case CUSTOM -> testFixture.registerCustom(topic, message);
                }
            }
        }

        public void monitorDocumentDispatch(SerializedDocument document) {
            try {
                SerializedMessage message = new SerializedMessage(
                        document.getDocument(), Metadata.of("$start", document.getTimestamp(), "$end",
                                                            document.getEnd()),
                        document.getId(), document.getTimestamp());
                monitorDispatch(testFixture.fluxzero.serializer().deserializeMessage(message, DOCUMENT).toMessage(),
                                DOCUMENT, document.getCollection(), testFixture.fluxzero.client().namespace(), false);
            } catch (Exception e) {
                log.warn("Failed to monitor an indexed document. This may cause your test to fail.", e);
            }
        }

        public void cancelDocumentDispatch(List<SerializedDocument> documents) {
            Map<String, Set<String>> messageIdsByTopic = new HashMap<>();
            documents.forEach(document -> messageIdsByTopic
                    .computeIfAbsent(document.getCollection(), ignored -> new CopyOnWriteArraySet<>())
                    .add(document.getId()));
            synchronized (testFixture.consumers) {
                testFixture.consumers.forEach((consumer, messages) -> {
                    if (consumer.getMessageType() == DOCUMENT) {
                        ofNullable(messageIdsByTopic.get(consumer.getTopic()))
                                .ifPresent(messageIds -> messages.removeIf(
                                        message -> messageIds.contains(message.getMessageId())));
                    }
                });
                testFixture.checkConsumers();
            }
        }

        protected Boolean captureMessage(Message message) {
            return testFixture.fixtureResult.isCollectingResults()
                   && ofNullable(testFixture.fixtureResult.getTracedMessage())
                           .map(t -> !Objects.equals(t.getMessageId(), message.getMessageId())).orElse(true);
        }

        protected <T extends Message> void addMessage(List<T> messages, T message) {
            if (message instanceof Schedule) {
                messages.removeIf(m -> m instanceof Schedule && ((Schedule) m).getScheduleId()
                        .equals(((Schedule) message).getScheduleId()));
            }
            messages.add(message);
        }

        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            List<Message> messages;
            synchronized (testFixture.consumers) {
                messages = testFixture.consumers.computeIfAbsent(
                        new ActiveConsumer(tracker.getConfiguration(), tracker.getMessageType(), tracker.getTopic()),
                        c -> (c.getMessageType() == SCHEDULE
                                ? publishedSchedules : Collections.<Message>emptyList()).stream().filter(
                                        m -> ofNullable(c.getConfiguration().getTypeFilter())
                                                .map(f -> m.getPayload().getClass()
                                                        .getName().matches(f)).orElse(true))
                                .collect(toCollection(CopyOnWriteArrayList::new)));
            }
            return b -> {
                consumer.accept(b);
                Collection<String> messageIds =
                        b.getMessages().stream().map(SerializedMessage::getMessageId).collect(toSet());
                synchronized (testFixture.consumers) {
                    b.getMessages().forEach(m -> testFixture.consumers.entrySet().stream()
                            .filter(e -> e.getKey().getMessageType() == tracker.getMessageType()
                                         && Objects.equals(e.getKey().getTopic(), tracker.getTopic())
                                         && isOutsideBounds(e.getKey().getConfiguration(), m.getIndex()))
                            .forEach(e -> e.getValue().removeIf(m2 -> m.getMessageId().equals(m2.getMessageId()))));
                    messages.removeIf(m -> messageIds.contains(m.getMessageId()));
                    testFixture.checkConsumers();
                }
            };
        }

        private boolean isOutsideBounds(ConsumerConfiguration configuration, Long index) {
            return index != null && ((configuration.getMinIndex() != null && index < configuration.getMinIndex())
                                     || (configuration.getMaxIndexExclusive() != null
                                         && index >= configuration.getMaxIndexExclusive()));
        }

        public Function<DeserializingMessage, Object> interceptHandling(
                Function<DeserializingMessage, Object> function, HandlerInvoker invoker) {
            return m -> {
                ActiveHandler activeHandler = testFixture.synchronous ? testFixture.activeHandler(m, invoker) : null;
                Deque<ActiveHandler> activeHandlers = null;
                if (activeHandler != null) {
                    testFixture.assertNoSynchronousConsumerDeadlock(activeHandler);
                    activeHandlers = testFixture.activeHandlers.get();
                    activeHandlers.push(activeHandler);
                }
                try {
                    var traceScope = testFixture.fixtureResult.getTrace().beginHandling(m, invoker);
                    Object result = null;
                    Throwable error = null;
                    try {
                        result = function.apply(m);
                        return result;
                    } catch (Throwable e) {
                        error = e;
                        testFixture.registerError(e);
                        throw e;
                    } finally {
                        traceScope.close(result, error);
                        if (
                                m.getMessageType().isRequest()
                                && Tracker.current().map(Tracker::getMessageBatch)
                                        .map(batch -> batch.getMessages().stream()
                                                .noneMatch(bm -> bm.getMessageId().equals(m.getMessageId())))
                                        .orElse(true)
                                && getLocalHandlerAnnotation(
                                        invoker.getTargetClass(), invoker.getMethod())
                                        .map(l -> !l.logMessage()).orElse(true)
                        ) {
                            synchronized (testFixture.consumers) {
                                testFixture.consumers.entrySet().stream()
                                        .filter(t -> t.getKey().getMessageType() == m.getMessageType())
                                        .forEach(e -> e.getValue().removeIf(
                                                m2 -> m2.getMessageId().equals(m.getMessageId())));
                            }
                            testFixture.checkConsumers();
                        }
                    }
                } finally {
                    if (activeHandlers != null) {
                        activeHandlers.pop();
                        if (activeHandlers.isEmpty()) {
                            testFixture.activeHandlers.remove();
                        }
                    }
                }
            };
        }

        public void shutdown(Tracker tracker) {
            synchronized (testFixture.consumers) {
                testFixture.consumers.remove(
                        new ActiveConsumer(tracker.getConfiguration(), tracker.getMessageType(), tracker.getTopic()));
            }
            testFixture.checkConsumers();
        }
    }

    @Order(Order.LOWEST_PRECEDENCE)
    private static final class LowPriorityDispatchInterceptor implements DispatchInterceptor {
        private final GivenWhenThenInterceptor delegate;

        private LowPriorityDispatchInterceptor(GivenWhenThenInterceptor delegate) {
            this.delegate = delegate;
        }

        private void interceptClientDispatch(MessageType messageType, String topic, String namespace,
                                             List<SerializedMessage> messages) {
            delegate.interceptClientDispatch(messageType, topic, namespace, messages);
        }

        @Override
        public Message interceptDispatch(Message message, MessageType messageType, String topic) {
            return delegate.interceptDispatch(message, messageType, topic);
        }

        @Override
        public void monitorDispatch(Message message, MessageType messageType, String topic, String namespace,
                                    boolean request) {
            delegate.monitorDispatch(message, messageType, topic, namespace, request);
        }
    }

    @Order(Order.HIGHEST_PRECEDENCE)
    private static final class HighPriorityBatchInterceptor implements BatchInterceptor {
        private final GivenWhenThenInterceptor delegate;

        private HighPriorityBatchInterceptor(GivenWhenThenInterceptor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
            return delegate.intercept(consumer, tracker);
        }

        @Override
        public void shutdown(Tracker tracker) {
            delegate.shutdown(tracker);
        }
    }

    @Order(Order.HIGHEST_PRECEDENCE)
    private static final class HighPriorityHandlerInterceptor implements HandlerInterceptor {
        private final GivenWhenThenInterceptor delegate;

        private HighPriorityHandlerInterceptor(GivenWhenThenInterceptor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                        HandlerInvoker invoker) {
            return delegate.interceptHandling(function, invoker);
        }
    }

    @Value
    protected static class HandlerConsumerKey {
        Class<?> handlerType;
        MessageType messageType;
    }

    @Value
    protected static class ConsumerIdentity {
        String name;
        String namespace;
        MessageType messageType;
        String topic;
        int threads;

        boolean isSingleThreaded() {
            return threads <= 1;
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            parts.add("name=" + name);
            parts.add("messageType=" + messageType);
            if (topic != null) {
                parts.add("topic=" + topic);
            }
            if (namespace != null) {
                parts.add("namespace=" + namespace);
            }
            return "{" + String.join(", ", parts) + "}";
        }
    }

    @Value
    protected static class ActiveHandler {
        String messageId;
        Class<?> payloadType;
        Class<?> handlerType;
        Executable method;
        boolean passive;
        MessageType messageType;
        String topic;
        Set<ConsumerIdentity> consumers;

        String describe() {
            return "%s#%s".formatted(simpleTypeName(handlerType), describeHandlerMethod(method));
        }

        String messageDescription() {
            return "%s %s".formatted(messageType, simpleTypeName(payloadType));
        }
    }

    @Value
    protected static class ActiveConsumer {
        ConsumerConfiguration configuration;
        MessageType messageType;
        String topic;

        @Override
        public String toString() {
            return topic == null ? "{name=%s, messageType=%s}".formatted(configuration.getName(), messageType) :
                    "{name=%s, messageType=%s, topic=%s}".formatted(configuration.getName(), messageType, topic);
        }
    }
}
