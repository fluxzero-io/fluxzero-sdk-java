/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.AutomaticModelHandling;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.ThrowingErrorHandler;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static io.fluxzero.sdk.tracking.ConsumerHandlingMode.SYNC;

/**
 * Runs a published-event Aggregate-to-Model migration in an isolated Fluxzero application context.
 *
 * <p>The migration application registers only the selected Model definitions and one global EVENT consumer. It never
 * registers ordinary application handlers, automatic Model command handling or materialized Graph projections while
 * replaying. Every original global event is reduced through the normal Model replay pipeline without being published
 * again. Direct documents remain invisible until {@link #adopt(long)} verifies the durable consumer boundary and asks
 * the {@link io.fluxzero.sdk.persisting.repository.ModelRepository} to adopt them.</p>
 *
 * <p>The consumer name must remain stable across restarts and application instances. Fluxzero fixes the consumer to
 * one globally ordered tracker and one synchronous handler thread; extra application instances therefore act as
 * failover candidates. The durable consumer position and idempotent source-event identity make restart overlap safe.</p>
 *
 * <p>A legacy Aggregate application and this migration may run concurrently as separate applications. If an old
 * entity and its replacement Model use the same fully qualified class name, they cannot coexist in one classloader;
 * the migration application must then be built from the new Model classes while the old application keeps running in
 * its own process.</p>
 */
@Slf4j
public final class PublishedEventModelMigration implements AutoCloseable {
    private static final int DEFAULT_MAX_FETCH_SIZE = 100;

    private final String name;
    private final Client client;
    private final List<Class<?>> modelTypes;
    private final DefaultFluxzero application;
    private final Registration modelRegistration;
    private final EventMigrator eventMigrator;
    private final AtomicBoolean replaying = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Registration trackerRegistration = Registration.noOp();

    private PublishedEventModelMigration(Builder builder) {
        this.name = requireName(builder.name);
        this.client = Objects.requireNonNull(builder.client, "Client");
        this.modelTypes = resolveModelTypes(builder.modelTypes, builder.modelPackages);

        FluxzeroBuilder applicationBuilder = DefaultFluxzero.builder()
                .addConsumerConfiguration(
                        consumerConfiguration(name, builder.maxFetchSize), EVENT)
                .configureAutomaticModelHandling(AutomaticModelHandling.DISABLED)
                .disableErrorReporting()
                .disableAutomaticAggregateCaching()
                .disableScheduledCommandHandler()
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .disableApplicationLifecycleMetrics();
        if (builder.serializer != null) {
            applicationBuilder.replaceSerializer(builder.serializer)
                    .replaceSnapshotSerializer(builder.serializer);
        }
        this.application = (DefaultFluxzero) applicationBuilder.build(client);
        this.eventMigrator = new EventMigrator(application);
        try {
            this.modelRegistration = application.registerMigrationTypes(modelTypes);
        } catch (RuntimeException | Error failure) {
            application.close();
            throw failure;
        }
    }

    /** Creates a migration builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the stable durable consumer name shared by all migration instances. */
    public String name() {
        return name;
    }

    /** Returns the complete, validated Model catalog used for replay and adoption. */
    public List<Class<?>> modelTypes() {
        return modelTypes;
    }

    /** Returns the isolated repository used to validate migrated state before cutover. */
    public ModelRepository repository() {
        requireOpen();
        return application.modelRepository();
    }

    /**
     * Starts replaying the global event log from its beginning.
     *
     * <p>Multiple processes may call this with the same migration name. Exactly one tracker receives the complete
     * segment range; the others remain available for failover.</p>
     *
     * @return a registration that stops this process' replay tracker
     */
    public Registration replay() {
        requireOpen();
        if (!replaying.compareAndSet(false, true)) {
            throw new IllegalStateException("Published-event Model migration is already replaying");
        }
        try {
            Registration registration = application.registerHandlers(eventMigrator);
            trackerRegistration = registration;
            log.info("Published-event Model migration {} is replaying with {} Model definitions",
                     name, modelTypes.size());
            return () -> stopReplay(registration);
        } catch (RuntimeException | Error failure) {
            replaying.set(false);
            close();
            throw failure;
        }
    }

    /**
     * Adopts all staged Model documents after the replay consumer has durably reached {@code cutoverEventIndex}.
     *
     * <p>Run this as an explicit cutover operation. Per-Model Runtime adoption is atomic and resumable; invoking a
     * single adoption job avoids redundant Graph rebuilds and ambiguous operator reporting.</p>
     *
     * @param cutoverEventIndex inclusive global event index that must already have been processed
     * @return the number of staged Model documents visited and adopted by this invocation
     */
    public CompletableFuture<Integer> adopt(long cutoverEventIndex) {
        requireOpen();
        if (replaying.get()) {
            throw new IllegalStateException(
                    "Stop replay before adopting staged Model documents");
        }
        if (cutoverEventIndex < 0L) {
            throw new IllegalArgumentException("Cutover event index must not be negative");
        }
        Position migrationPosition = client.getTrackingClient(EVENT).getPosition(name);
        requireCatchUp(migrationPosition, cutoverEventIndex);
        return application.modelRepository().adoptModelMigrations();
    }

    /**
     * Runs the standard migration command-line contract: no arguments starts replay; {@code adopt <event-index>}
     * performs the checked cutover operation and then closes the isolated application.
     */
    public void run(String... args) {
        Long adoptionBoundary;
        try {
            adoptionBoundary = adoptionBoundary(args);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
        if (adoptionBoundary == null) {
            replay();
            return;
        }
        try {
            int adopted = adopt(adoptionBoundary).join();
            log.info("Adopted {} staged Model documents after migration {} reached event index {}",
                     adopted, name, adoptionBoundary);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopReplay(trackerRegistration);
            modelRegistration.cancel();
            application.close();
        }
    }

    CompletableFuture<Void> migrate(DeserializingMessage event) {
        return application.migratePublishedEvent(event);
    }

    static Long adoptionBoundary(String[] args) {
        Objects.requireNonNull(args, "Arguments");
        if (args.length == 0) {
            return null;
        }
        if (args.length != 2 || !"adopt".equalsIgnoreCase(args[0])) {
            throw new IllegalArgumentException(
                    "Expected no argument for replay or 'adopt <cutover-event-index>' for adoption");
        }
        try {
            long boundary = Long.parseLong(args[1]);
            if (boundary < 0L) {
                throw new IllegalArgumentException("Cutover event index must not be negative");
            }
            return boundary;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cutover event index must be a long", e);
        }
    }

    static void requireCatchUp(Position migrationPosition, long adoptionBoundary) {
        long migratedThrough = Objects.requireNonNull(migrationPosition, "Migration position")
                .lowestIndexForSegment(new int[]{0, MAX_SEGMENT})
                .orElseThrow(() -> new IllegalStateException(
                        "Published-event Model migration has no durable tracking position"));
        if (migratedThrough < adoptionBoundary) {
            throw new IllegalStateException(
                    "Published-event Model migration has reached event index %d, before cutover index %d"
                            .formatted(migratedThrough, adoptionBoundary));
        }
    }

    static ConsumerConfiguration consumerConfiguration(String name, int maxFetchSize) {
        if (maxFetchSize <= 0) {
            throw new IllegalArgumentException("Migration max fetch size must be positive");
        }
        return ConsumerConfiguration.builder()
                .name(requireName(name))
                .handlerFilter(handler -> handler instanceof EventMigrator)
                .errorHandler(new ThrowingErrorHandler())
                .threads(1)
                .minIndex(0L)
                .maxFetchSize(maxFetchSize)
                .singleTracker(true)
                .handlingMode(SYNC)
                .build();
    }

    private static List<Class<?>> resolveModelTypes(
            Collection<Class<?>> explicitTypes, Collection<String> modelPackages) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>(explicitTypes);
        if (!modelPackages.isEmpty()) {
            ReflectionUtils.getRegisteredTypes().stream()
                    .filter(type -> modelPackages.stream().anyMatch(root -> inPackage(type, root)))
                    .filter(type -> EntityMetadata.of(type).isModel())
                    .forEach(result::add);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "Configure at least one Model type or package containing registered Models");
        }
        result.forEach(type -> {
            if (!EntityMetadata.of(type).isModel()) {
                throw new IllegalArgumentException(type.getName() + " is not a Model root");
            }
        });
        return result.stream().sorted(Comparator.comparing(Class::getName)).toList();
    }

    private static boolean inPackage(Class<?> type, String root) {
        String packageName = type.getPackageName();
        return packageName.equals(root) || packageName.startsWith(root + ".");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Migration name must not be blank");
        }
        return name;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Published-event Model migration is closed");
        }
    }

    private void stopReplay(Registration registration) {
        registration.cancel();
        if (trackerRegistration == registration) {
            trackerRegistration = Registration.noOp();
            replaying.set(false);
        }
    }

    private record EventMigrator(DefaultFluxzero application) {
        @HandleEvent
        CompletableFuture<Void> migrate(Object ignored, DeserializingMessage event) {
            return application.migratePublishedEvent(event);
        }
    }

    /** Builder for one isolated published-event Model migration application. */
    public static final class Builder {
        private String name;
        private Client client;
        private Serializer serializer;
        private final LinkedHashSet<Class<?>> modelTypes = new LinkedHashSet<>();
        private final LinkedHashSet<String> modelPackages = new LinkedHashSet<>();
        private int maxFetchSize = DEFAULT_MAX_FETCH_SIZE;

        private Builder() {
        }

        /** Sets the stable durable consumer name. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the low-level client owned and closed by the migration application. */
        public Builder client(Client client) {
            this.client = client;
            return this;
        }

        /** Uses the serializer and upcaster chain for legacy events, Model snapshots and documents. */
        public Builder serializer(Serializer serializer) {
            this.serializer = Objects.requireNonNull(serializer, "Serializer");
            return this;
        }

        /** Adds explicit Model roots to the migration catalog. */
        public Builder modelTypes(Class<?>... modelTypes) {
            return modelTypes(List.of(modelTypes));
        }

        /** Adds explicit Model roots to the migration catalog. */
        public Builder modelTypes(Collection<Class<?>> modelTypes) {
            this.modelTypes.addAll(Objects.requireNonNull(modelTypes, "Model types"));
            return this;
        }

        /** Discovers registered Model roots in this package and its subpackages. */
        public Builder modelsInPackage(String packageName) {
            String root = Objects.requireNonNull(packageName, "Model package").trim();
            if (root.isEmpty()) {
                throw new IllegalArgumentException("Model package must not be blank");
            }
            modelPackages.add(root);
            return this;
        }

        /** Overrides the conservative replay batch size. Ordering and tracker count remain fixed. */
        public Builder maxFetchSize(int maxFetchSize) {
            this.maxFetchSize = maxFetchSize;
            return this;
        }

        /** Builds the isolated migration application and validates its complete Model catalog. */
        public PublishedEventModelMigration build() {
            return new PublishedEventModelMigration(this);
        }
    }
}
