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

package io.fluxzero.sdk.execution;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.FluxzeroConfiguration;
import io.fluxzero.sdk.modeling.DefaultHandlerRepository;
import io.fluxzero.sdk.modeling.HandlerRepository;
import io.fluxzero.sdk.registry.ComponentCapability;
import io.fluxzero.sdk.registry.ComponentDescriptor;
import io.fluxzero.sdk.registry.ComponentRegistry;
import io.fluxzero.sdk.registry.ComponentRegistryGenerator;
import io.fluxzero.sdk.registry.ComponentRegistryJson;
import io.fluxzero.sdk.registry.HandlerRoute;
import io.fluxzero.sdk.registry.PackageDescriptor;
import io.fluxzero.sdk.registry.SourceComponentScanner;
import io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory;
import io.fluxzero.sdk.tracking.handling.DefaultRepositoryProvider;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import io.fluxzero.sdk.tracking.handling.RepositoryProvider;
import lombok.NonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.ERROR;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.METRICS;
import static io.fluxzero.common.MessageType.NOTIFICATION;
import static io.fluxzero.common.MessageType.QUERY;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.common.MessageType.WEBREQUEST;

/**
 * Experimental execution mode that compiles and loads local Java handler source units on demand.
 * <p>
 * V1 is intended for trusted local development. It provides classloader isolation and cache-based recompilation, not
 * a security sandbox for untrusted code.
 */
public class OnDemandExecution implements ExecutionMode, AutoCloseable {
    /**
     * Property that makes conventional on-demand execution require a generated component registry artifact.
     * <p>
     * When set to {@code true}, Fluxzero will not fall back to runtime source scanning for conventional
     * {@code src/main/fluxzero} or {@code src/test/fluxzero} roots.
     */
    public static final String REGISTRY_REQUIRED_PROPERTY = "fluxzero.execution.on-demand.registry-required";

    /**
     * Property that controls whether conventional on-demand execution checks source hashes on matching invocations.
     * <p>
     * The default is {@code true}, which is convenient for local development. Production-style runs can set this to
     * {@code false} to avoid per-invocation source checks.
     */
    public static final String CHECK_SOURCE_CHANGES_PROPERTY =
            "fluxzero.execution.on-demand.check-source-changes";

    /**
     * Cache TTL value that keeps loaded source units active until the execution mode is closed.
     */
    public static final Duration NEVER_EVICT = Duration.ofMillis(-1);

    private static final Path DEFAULT_CACHE_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "fluxzero-on-demand-execution");
    private static final Set<ComponentCapability> INFRASTRUCTURE_CAPABILITIES = EnumSet.of(
            ComponentCapability.DISPATCH_INTERCEPTOR,
            ComponentCapability.HANDLER_DECORATOR,
            ComponentCapability.HANDLER_INTERCEPTOR,
            ComponentCapability.BATCH_INTERCEPTOR,
            ComponentCapability.RESPONSE_MAPPER,
            ComponentCapability.WEB_RESPONSE_MAPPER,
            ComponentCapability.VALIDATOR,
            ComponentCapability.PARAMETER_RESOLVER,
            ComponentCapability.SERIALIZER,
            ComponentCapability.DOCUMENT_SERIALIZER,
            ComponentCapability.CORRELATION_DATA_PROVIDER,
            ComponentCapability.IDENTITY_PROVIDER,
            ComponentCapability.USER_PROVIDER,
            ComponentCapability.CACHE,
            ComponentCapability.TASK_SCHEDULER,
            ComponentCapability.PROPERTY_SOURCE);

    private final Path sourceRoot;
    private final Duration cacheTtl;
    private final int release;
    private final Path cacheRoot;
    private final List<Path> extraClasspath;
    private final List<Path> extraSourcepath;
    private final ClassLoader parentClassLoader;
    private final boolean registerLocalHandlers;
    private final boolean startTracking;
    private final ComponentRegistry registry;
    private final boolean scanSourceWhenRegistryMissing;
    private final boolean checkSourceChangesOnInvocation;
    private final List<LazyExecutionHandler> activeHandlers = new CopyOnWriteArrayList<>();
    private final Map<String, LazyExecutionUnit> activeUnits = new ConcurrentHashMap<>();
    private final Set<String> resolvableSourceTypes = ConcurrentHashMap.newKeySet();

    private OnDemandExecution(Builder builder) {
        this.sourceRoot = builder.sourceRoot == null ? ComponentRegistryGenerator.DEFAULT_SOURCE_ROOT : builder.sourceRoot;
        this.cacheTtl = builder.cacheTtl;
        this.release = builder.release;
        this.cacheRoot = builder.cacheRoot;
        this.extraClasspath = List.copyOf(builder.extraClasspath);
        this.extraSourcepath = List.copyOf(builder.extraSourcepath);
        this.parentClassLoader = builder.parentClassLoader == null
                ? Thread.currentThread().getContextClassLoader() : builder.parentClassLoader;
        this.registerLocalHandlers = builder.registerLocalHandlers;
        this.startTracking = builder.startTracking;
        this.registry = builder.registry;
        this.scanSourceWhenRegistryMissing = builder.scanSourceWhenRegistryMissing;
        this.checkSourceChangesOnInvocation = builder.checkSourceChangesOnInvocation;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Indexes the configured source root and registers lazy handlers with the supplied Fluxzero instance.
     */
    @Override
    public Registration registerWith(@NonNull Fluxzero fluxzero) {
        ComponentRegistry registry = componentRegistry();
        if (registry.isEmpty()) {
            return Registration.noOp();
        }

        OnDemandCompiler compiler = new OnDemandCompiler(
                release, sourceRoot, cacheRoot, extraClasspath, extraSourcepath, parentClassLoader);
        List<LazyExecutionUnit> registeredUnits = new ArrayList<>();
        Map<MessageType, List<LazyExecutionHandler>> handlers =
                handlersByMessageType(fluxzero, registry, compiler, registeredUnits);
        List<LazyExecutionHandler> registeredHandlers = handlers.values().stream().flatMap(List::stream).toList();
        activeHandlers.addAll(registeredHandlers);
        Set<String> registeredResolvableTypes = resolvableSourceTypes(registry);
        resolvableSourceTypes.addAll(registeredResolvableTypes);
        Registration typeResolverRegistration = fluxzero.serializer().registerTypeResolver(this::loadType);

        Registration registration = handlers.entrySet().stream()
                .flatMap(e -> registrations(fluxzero, e.getKey(), e.getValue()))
                .reduce(Registration::merge).orElse(Registration.noOp());
        Registration registryRegistration = fluxzero.registerComponentRegistry(registry);
        Registration cleanup = fluxzero.beforeShutdown(() -> {
            close(registeredHandlers, List.copyOf(activeUnits.values()));
            resolvableSourceTypes.removeAll(registeredResolvableTypes);
        });
        return typeResolverRegistration.merge(registration).merge(registryRegistration).merge(cleanup)
                .merge(() -> {
                    close(registeredHandlers, List.copyOf(activeUnits.values()));
                    resolvableSourceTypes.removeAll(registeredResolvableTypes);
                });
    }

    /**
     * Compiles and instantiates source-defined Fluxzero infrastructure components.
     * <p>
     * Infrastructure components such as interceptors, response mappers, validators, and parameter resolvers affect how
     * the Fluxzero instance is assembled. They are therefore materialized during startup instead of lazily per handler
     * invocation. The loaded classes still use the same source cache and classloader lifecycle as on-demand handlers.
     */
    public List<Object> instantiateInfrastructureComponents() {
        return instantiateInfrastructureComponents(null);
    }

    /**
     * Compiles and instantiates source-defined Fluxzero infrastructure components with access to Fluxzero
     * configuration.
     * <p>
     * Components may either expose a no-argument constructor or a constructor that accepts
     * {@link FluxzeroConfiguration}. This keeps startup infrastructure explicit without turning on-demand execution
     * into a general dependency injection container.
     */
    public List<Object> instantiateInfrastructureComponents(FluxzeroConfiguration configuration) {
        ComponentRegistry registry = componentRegistry();
        if (registry.isEmpty()) {
            return List.of();
        }
        OnDemandCompiler compiler = new OnDemandCompiler(
                release, sourceRoot, cacheRoot, extraClasspath, extraSourcepath, parentClassLoader);
        return registry.components().stream()
                .filter(OnDemandExecution::hasInfrastructureCapability)
                .map(component -> instantiate(unitFor(component, compiler), configuration))
                .toList();
    }

    /**
     * Compiles and loads all currently registered on-demand source handlers that are not already active.
     * <p>
     * Registration remains index-only. Calling this method is an explicit opt-in for applications that want to move
     * first-use compilation cost out of the first live message flow.
     */
    public void prewarm() {
        prewarm(List.copyOf(activeHandlers));
    }

    /**
     * Compiles and loads currently registered on-demand source handlers for the supplied message types.
     * <p>
     * If no message types are supplied this behaves like {@link #prewarm()}.
     */
    public void prewarm(MessageType... messageTypes) {
        if (messageTypes.length == 0) {
            prewarm();
            return;
        }
        Set<MessageType> selected = EnumSet.copyOf(Arrays.asList(messageTypes));
        prewarm(activeHandlers.stream().filter(handler -> selected.contains(handler.messageType())).toList());
    }

    /**
     * Runs {@link #prewarm()} asynchronously using the common pool.
     */
    public CompletableFuture<Void> prewarmAsync() {
        return CompletableFuture.runAsync(this::prewarm);
    }

    /**
     * Runs {@link #prewarm(MessageType...)} asynchronously using the common pool.
     */
    public CompletableFuture<Void> prewarmAsync(MessageType... messageTypes) {
        return CompletableFuture.runAsync(() -> prewarm(messageTypes));
    }

    /**
     * Compiles and loads a registered source component type by fully qualified Java class name.
     * <p>
     * This method uses the same per-component classloader and cache entry as lazy handler invocation. Applications can
     * use it to materialize source-defined payload components explicitly; automatic deserialization of source payloads
     * is layered on top of this resolver rather than using a separate loading path.
     *
     * @return the loaded class when the type belongs to the currently registered source registry
     */
    public Optional<Class<?>> loadType(@NonNull String className) {
        if (!resolvableSourceTypes.contains(className)) {
            return Optional.empty();
        }
        LazyExecutionUnit unit = activeUnits.get(className);
        return unit == null ? Optional.empty() : Optional.of(unit.type());
    }

    private ComponentRegistry componentRegistry() {
        if (registry != null) {
            return registry;
        }
        List<ComponentRegistry> generated = ComponentRegistryJson.load(parentClassLoader).stream()
                .filter(this::matchesSourceRoot)
                .map(this::relocateRegistry)
                .toList();
        if (!generated.isEmpty()) {
            return ComponentRegistry.merge(generated);
        }
        if (scanSourceWhenRegistryMissing) {
            return new SourceComponentScanner().scan(sourceRoot);
        }
        throw new OnDemandExecutionException(
                "No generated Fluxzero component registry found for source root `%s`. Generate `%s` at build time or enable source scanning."
                        .formatted(sourceRoot, ComponentRegistryJson.DEFAULT_RESOURCE));
    }

    private boolean matchesSourceRoot(ComponentRegistry candidate) {
        if (candidate.sourceRoot() == null) {
            return false;
        }
        Path expected = sourceRoot.toAbsolutePath().normalize();
        Path actual = candidate.sourceRoot().toAbsolutePath().normalize();
        if (expected.equals(actual)) {
            return true;
        }
        Path configured = sourceRoot.normalize();
        Path registryRoot = candidate.sourceRoot().normalize();
        return configured.equals(registryRoot)
               || configured.endsWith(registryRoot)
               || expected.endsWith(registryRoot);
    }

    private ComponentRegistry relocateRegistry(ComponentRegistry candidate) {
        Path registryRoot = candidate.sourceRoot() == null ? sourceRoot : candidate.sourceRoot().normalize();
        List<PackageDescriptor> packages = candidate.packages().stream()
                .map(p -> new PackageDescriptor(
                        p.packageName(), relocate(p.sourceFile(), registryRoot), p.annotations(),
                        p.registeredTypes(), p.consumer(), p.capabilities()))
                .toList();
        List<ComponentDescriptor> components = candidate.components().stream()
                .map(c -> new ComponentDescriptor(
                        relocate(c.sourceFile(), registryRoot), relocate(c.packageInfoSource(), registryRoot),
                        c.componentKind(), c.packageName(), c.className(), c.superTypeNames(),
                        c.annotations(), c.properties(), c.executables(),
                        c.handlerRoutes(), c.registeredTypes(), c.consumer(), c.capabilities()))
                .toList();
        return new ComponentRegistry(sourceRoot, packages, components);
    }

    private Path relocate(Path path, Path registryRoot) {
        if (path == null || path.isAbsolute()) {
            return path;
        }
        Path normalized = path.normalize();
        if (!registryRoot.isAbsolute() && normalized.startsWith(registryRoot)) {
            return sourceRoot.resolve(registryRoot.relativize(normalized)).normalize();
        }
        return sourceRoot.resolve(normalized).normalize();
    }

    private Map<MessageType, List<LazyExecutionHandler>> handlersByMessageType(
            Fluxzero fluxzero, ComponentRegistry registry, OnDemandCompiler compiler,
            List<LazyExecutionUnit> registeredUnits) {
        Map<MessageType, HandlerFactory> factories = handlerFactories(fluxzero);
        Map<MessageType, List<LazyExecutionHandler>> result = new EnumMap<>(MessageType.class);
        for (ComponentDescriptor component : registry.components()) {
            LazyExecutionUnit unit = unitFor(component, compiler);
            registeredUnits.add(unit);
            for (HandlerRoute route : component.handlerRoutes()) {
                MessageType messageType = route.messageType();
                if (route.disabled() || !supported(messageType)) {
                    continue;
                }
                result.computeIfAbsent(messageType, ignored -> new ArrayList<>()).add(
                        new LazyExecutionHandler(unit, route, factories.get(messageType)));
            }
        }
        return result;
    }

    private LazyExecutionUnit unitFor(ComponentDescriptor component, OnDemandCompiler compiler) {
        return activeUnits.computeIfAbsent(component.fullClassName(), ignored -> new LazyExecutionUnit(
                component, compiler, cacheTtl, checkSourceChangesOnInvocation));
    }

    private static Set<String> resolvableSourceTypes(ComponentRegistry registry) {
        Set<String> result = new java.util.LinkedHashSet<>();
        registry.components().stream()
                .filter(component -> !component.handlerRoutes().isEmpty())
                .map(ComponentDescriptor::fullClassName)
                .forEach(result::add);
        registry.handlerRoutes().forEach(route -> {
            result.addAll(route.payloadTypeNames());
            result.addAll(route.allowedClassNames());
        });
        registry.registeredTypes()
                .flatMap(registeredType -> registeredType.candidateTypeNames().stream())
                .forEach(result::add);
        return Set.copyOf(result);
    }

    private static boolean hasInfrastructureCapability(ComponentDescriptor component) {
        return component.capabilities().stream().anyMatch(INFRASTRUCTURE_CAPABILITIES::contains);
    }

    private static Object instantiate(LazyExecutionUnit unit, FluxzeroConfiguration configuration) {
        Class<?> type = unit.type();
        if (configuration != null) {
            try {
                var constructor = type.getDeclaredConstructor(FluxzeroConfiguration.class);
                constructor.setAccessible(true);
                return constructor.newInstance(configuration);
            } catch (NoSuchMethodException ignored) {
                // Fall through to the no-argument constructor.
            } catch (Exception e) {
                throw new OnDemandExecutionException(
                        "Failed to instantiate source infrastructure component with FluxzeroConfiguration: "
                        + unit.component().fullClassName(), e);
            }
        }
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new OnDemandExecutionException(
                    "Source infrastructure component needs a no-argument constructor: "
                    + unit.component().fullClassName(), e);
        } catch (Exception e) {
            throw new OnDemandExecutionException(
                    "Failed to instantiate source infrastructure component: "
                    + unit.component().fullClassName(), e);
        }
    }

    private Map<MessageType, HandlerFactory> handlerFactories(Fluxzero fluxzero) {
        FluxzeroConfiguration configuration = fluxzero.configuration();
        RepositoryProvider repositoryProvider = new DefaultRepositoryProvider();
        Function<Class<?>, HandlerRepository> handlerRepositorySupplier =
                DefaultHandlerRepository.handlerRepositorySupplier(fluxzero::documentStore,
                                                                   configuration.documentSerializer());
        Map<MessageType, HandlerFactory> result = new EnumMap<>(MessageType.class);
        Stream.of(COMMAND, QUERY, EVENT, NOTIFICATION, ERROR, METRICS, SCHEDULE, WEBREQUEST).forEach(messageType ->
                result.put(messageType, new DefaultHandlerFactory(
                        messageType, handlerDecorator(configuration, messageType),
                        configuration.parameterResolvers(),
                        configuration.methodInvocationValidator(messageType),
                        handlerRepositorySupplier, repositoryProvider, false,
                        configuration.serializer())));
        return result;
    }

    private static HandlerDecorator handlerDecorator(FluxzeroConfiguration configuration, MessageType messageType) {
        MessageType decoratorType = messageType == NOTIFICATION ? EVENT : messageType;
        return configuration.effectiveHandlerDecorators()
                .getOrDefault(decoratorType, HandlerDecorator.noOp);
    }

    private Stream<Registration> registrations(Fluxzero fluxzero, MessageType messageType,
                                               List<LazyExecutionHandler> handlers) {
        List<LazyExecutionHandler> localHandlers = handlers.stream().filter(LazyExecutionHandler::local).toList();
        List<LazyExecutionHandler> trackingHandlers = handlers.stream().filter(LazyExecutionHandler::tracked).toList();
        Stream<Registration> local = registerLocalHandlers
                ? localHandlers.stream().map(handler -> registerLocal(fluxzero, messageType, handler))
                : Stream.empty();
        Stream<Registration> tracking = startTracking && !trackingHandlers.isEmpty()
                ? Stream.of(fluxzero.tracking(messageType).start(fluxzero, trackingHandlers))
                : Stream.empty();
        return Stream.concat(local, tracking);
    }

    private Registration registerLocal(Fluxzero fluxzero, MessageType messageType, LazyExecutionHandler handler) {
        return switch (messageType) {
            case COMMAND -> fluxzero.commandGateway().registerHandler(handler);
            case QUERY -> fluxzero.queryGateway().registerHandler(handler);
            case EVENT -> fluxzero.eventGateway().registerHandler(handler)
                    .merge(fluxzero.eventStore().registerHandler(handler));
            case NOTIFICATION -> fluxzero.eventGateway().registerHandler(handler);
            case ERROR -> fluxzero.errorGateway().registerHandler(handler);
            case METRICS -> fluxzero.metricsGateway().registerHandler(handler);
            case SCHEDULE -> fluxzero.messageScheduler() instanceof HasLocalHandlers localHandlers
                    ? localHandlers.registerHandler(handler) : Registration.noOp();
            case WEBREQUEST -> fluxzero.webRequestGateway().registerHandler(handler);
            default -> Registration.noOp();
        };
    }

    private static boolean supported(MessageType messageType) {
        return switch (messageType) {
            case COMMAND, QUERY, EVENT, NOTIFICATION, ERROR, METRICS, SCHEDULE, WEBREQUEST -> true;
            default -> false;
        };
    }

    private static void prewarm(List<LazyExecutionHandler> handlers) {
        if (handlers.isEmpty()) {
            return;
        }
        Map<OnDemandCompiler, Map<ComponentDescriptor, OnDemandCompiler.CompilationRequest>> requests =
                new IdentityHashMap<>();
        Map<LazyExecutionUnit, String> sourceHashes = new IdentityHashMap<>();
        for (LazyExecutionHandler handler : handlers) {
            OnDemandCompiler.CompilationRequest request = handler.compilationRequestIfNeeded();
            if (request != null) {
                requests.computeIfAbsent(handler.compiler(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(request.component(), request);
                sourceHashes.putIfAbsent(handler.unit(), request.sourceHash());
            }
        }
        requests.forEach((compiler, compilerRequests) -> compiler.compileAll(List.copyOf(compilerRequests.values())));
        handlers.forEach(handler -> handler.prewarm(sourceHashes.get(handler.unit())));
    }

    @Override
    public void close() {
        close(List.copyOf(activeHandlers), List.copyOf(activeUnits.values()));
    }

    private void close(List<LazyExecutionHandler> handlers, List<LazyExecutionUnit> units) {
        Set<LazyExecutionUnit> unitsToClose = Stream.concat(
                handlers.stream().map(LazyExecutionHandler::unit), units.stream())
                .collect(java.util.stream.Collectors.toSet());
        handlers.forEach(LazyExecutionHandler::close);
        unitsToClose.forEach(LazyExecutionUnit::close);
        activeHandlers.removeAll(handlers);
        activeUnits.entrySet().removeIf(entry -> unitsToClose.contains(entry.getValue()));
    }

    public static class Builder {
        private Path sourceRoot;
        private Duration cacheTtl = NEVER_EVICT;
        private int release = 21;
        private Path cacheRoot = DEFAULT_CACHE_ROOT;
        private final List<Path> extraClasspath = new ArrayList<>();
        private final List<Path> extraSourcepath = new ArrayList<>();
        private ClassLoader parentClassLoader;
        private boolean registerLocalHandlers = true;
        private boolean startTracking = true;
        private ComponentRegistry registry;
        private boolean scanSourceWhenRegistryMissing = true;
        private boolean checkSourceChangesOnInvocation = true;

        /**
         * Sets the root directory containing Java source files for on-demand execution.
         */
        public Builder sourceRoot(@NonNull Path sourceRoot) {
            this.sourceRoot = sourceRoot;
            return this;
        }

        /**
         * Sets how long an idle compiled classloader may be reused.
         * <p>
         * The default is {@link OnDemandExecution#NEVER_EVICT}. Set a positive duration to evict idle loaded source
         * units, or {@link Duration#ZERO} to reload matching units aggressively.
         */
        public Builder cacheTtl(@NonNull Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        /**
         * Sets the Java release used for on-demand compilation.
         */
        public Builder release(int release) {
            if (release < 1) {
                throw new IllegalArgumentException("release must be positive");
            }
            this.release = release;
            return this;
        }

        /**
         * Sets the directory used to store compiled on-demand execution class files.
         */
        public Builder cacheRoot(@NonNull Path cacheRoot) {
            this.cacheRoot = cacheRoot;
            return this;
        }

        /**
         * Adds an extra classpath entry for on-demand compilation.
         */
        public Builder addClasspath(@NonNull Path path) {
            this.extraClasspath.add(path);
            return this;
        }

        /**
         * Adds an extra sourcepath entry for on-demand compilation.
         */
        public Builder addSourcepath(@NonNull Path path) {
            this.extraSourcepath.add(path);
            return this;
        }

        /**
         * Sets the parent classloader used by compiled execution units.
         */
        public Builder parentClassLoader(ClassLoader parentClassLoader) {
            this.parentClassLoader = parentClassLoader;
            return this;
        }

        /**
         * Controls whether handlers are registered for direct local gateway dispatch.
         */
        public Builder registerLocalHandlers(boolean registerLocalHandlers) {
            this.registerLocalHandlers = registerLocalHandlers;
            return this;
        }

        /**
         * Controls whether handlers start normal Fluxzero tracking consumers.
         */
        public Builder startTracking(boolean startTracking) {
            this.startTracking = startTracking;
            return this;
        }

        /**
         * Supplies a prebuilt component registry for on-demand execution.
         * <p>
         * When set, the execution mode does not load generated registry artifacts and does not scan the source root
         * during registration.
         */
        public Builder registry(@NonNull ComponentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Controls whether registration may scan the source root when no generated registry artifact is available.
         * <p>
         * Disable this in production-style runs that must only use build-time registry artifacts.
         */
        public Builder scanSourceWhenRegistryMissing(boolean scanSourceWhenRegistryMissing) {
            this.scanSourceWhenRegistryMissing = scanSourceWhenRegistryMissing;
            return this;
        }

        /**
         * Controls whether each matching invocation checks source files for changes before using a loaded component.
         * <p>
         * Keep this enabled for local hot reload development. Disable it for production-style runs or benchmarks where
         * source changes should take effect only after the component is unloaded or the execution mode is re-registered.
         */
        public Builder checkSourceChangesOnInvocation(boolean checkSourceChangesOnInvocation) {
            this.checkSourceChangesOnInvocation = checkSourceChangesOnInvocation;
            return this;
        }

        public OnDemandExecution build() {
            Objects.requireNonNull(cacheTtl, "cacheTtl");
            Objects.requireNonNull(cacheRoot, "cacheRoot");
            return new OnDemandExecution(this);
        }
    }
}
