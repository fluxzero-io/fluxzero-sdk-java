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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.GeneratedExecutableInvocations;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.Substitutable;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentRegistryException;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.ConsumerDescriptor;
import io.fluxzero.sdk.registry.ExecutableKind;
import io.fluxzero.sdk.registry.InvocationPlanDescriptor;
import io.fluxzero.sdk.registry.JvmCompatibilityBackend;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.registry.PackageDescriptor;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration class that defines how a message consumer behaves during message tracking and handler invocation.
 * <p>
 * {@code ConsumerConfiguration} is used to fine-tune the behavior of message consumers beyond what is possible with the
 * {@link Consumer} annotation. It supports handler filtering, tracking concurrency, custom interceptors, and more.
 *
 * <p><strong>Usage:</strong> Consumers can be declared programmatically using this configuration object, generated
 * automatically from {@code @Consumer} annotations on handler classes or packages, or derived from the default
 * consumer template for handlers without an explicit consumer.
 *
 * @see Consumer
 * @see io.fluxzero.sdk.configuration.FluxzeroBuilder#addConsumerConfiguration
 */
@Value
@Builder(builderClassName = "Builder", toBuilder = true)
public class ConsumerConfiguration implements Substitutable<ConsumerConfiguration> {
    /**
     * Chooses how unconfigured tracking handlers are assigned to default consumers.
     * <p>
     * Supported values are {@link #PER_HANDLER_CONSUMER_MODE} and {@link #DEFAULT_APP_CONSUMER_MODE}. When absent,
     * Fluxzero derives the default from {@link ApplicationProperties#DEFAULTS_VERSION_PROPERTY}.
     */
    public static final String UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY =
            "fluxzero.tracking.unconfiguredHandlerConsumerMode";

    /**
     * Creates an isolated default consumer per unconfigured handler class.
     */
    public static final String PER_HANDLER_CONSUMER_MODE = "perHandler";

    /**
     * Assigns unconfigured handlers to the shared default application consumer for their message type.
     */
    public static final String DEFAULT_APP_CONSUMER_MODE = "defaultAppConsumer";

    /**
     * Configures the default serialized payload byte limit per tracking fetch. Consumers can override this default
     * with {@link Builder#maxFetchBytes(long)} or {@link Consumer#maxFetchBytes()}.
     */
    public static final String MAX_FETCH_BYTES_PROPERTY = "fluxzero.tracking.maxFetchBytes";

    /**
     * App-wide default handler execution mode used when a consumer and its message-type default consumer both use
     * {@link ConsumerHandlingMode#DEFAULT}.
     */
    public static final String DEFAULT_HANDLING_MODE_PROPERTY = "fluxzero.tracking.defaultHandlingMode";

    /**
     * Prefix for message-type specific default handler execution mode properties.
     * <p>
     * For example, {@code fluxzero.tracking.defaultHandlingMode.webrequest = async} changes only web request default
     * consumers while {@link #DEFAULT_HANDLING_MODE_PROPERTY} still applies app-wide.
     */
    public static final String DEFAULT_HANDLING_MODE_BY_MESSAGE_TYPE_PROPERTY_PREFIX =
            DEFAULT_HANDLING_MODE_PROPERTY + ".";

    /**
     * Default serialized payload byte limit per tracking fetch.
     */
    public static final long DEFAULT_MAX_FETCH_BYTES = 100L * 1024L * 1024L;

    /**
     * Sentinel value for consumers that inherit the configured default max fetch byte limit. This value is resolved
     * before read requests are sent.
     */
    public static final long USE_DEFAULT_MAX_FETCH_BYTES = -1L;

    /**
     * Returns the property key for a message-type specific default handler execution mode.
     *
     * @param messageType message type to configure
     * @return property key using the lowercase message type name
     */
    public static String defaultHandlingModeProperty(MessageType messageType) {
        return DEFAULT_HANDLING_MODE_BY_MESSAGE_TYPE_PROPERTY_PREFIX
               + messageType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Unique name for the consumer. Used for tracking and identifying its state.
     */
    @NonNull
    String name;

    /**
     * Optional predicate that determines whether a given handler is included in this consumer.
     * <p>
     * This is especially useful when combining multiple handler classes or packages and you want to restrict
     * consumption to a subset.
     */
    @NonNull
    @Default
    @EqualsAndHashCode.Exclude
    Predicate<Object> handlerFilter = o -> true;

    /**
     * Defines how errors during handler invocation are handled. Defaults to logging errors and continuing with other
     * messages.
     */
    @NonNull
    @Default
    @EqualsAndHashCode.Exclude
    ErrorHandler errorHandler = new LoggingErrorHandler();

    //tracking config

    /**
     * Number of concurrent threads to process messages.
     */
    @Default
    int threads = 1;

    /**
     * Optional class name pattern to filter message types. When provided, only messages whose payload matches the type
     * name pattern will be processed.
     */
    @Default
    String typeFilter = null;

    /**
     * Maximum number of messages to fetch per poll from the message log.
     */
    @Default
    int maxFetchSize = 1024;

    /**
     * Maximum serialized payload bytes to fetch per poll from the message log.
     * <p>
     * The default {@link #USE_DEFAULT_MAX_FETCH_BYTES} inherits {@link #MAX_FETCH_BYTES_PROPERTY} when configured,
     * otherwise {@link #DEFAULT_MAX_FETCH_BYTES}. A value of {@code 0} disables this limit. If a single message is
     * larger than this limit, it may still be returned alone so tracking can make progress.
     */
    @Default
    long maxFetchBytes = USE_DEFAULT_MAX_FETCH_BYTES;

    /**
     * Effective byte limit used for read requests.
     */
    @Getter(lazy = true)
    @Accessors(fluent = true)
    @EqualsAndHashCode.Exclude
    long effectiveMaxFetchBytes = requireNonNegativeMaxFetchBytes(
            maxFetchBytes == USE_DEFAULT_MAX_FETCH_BYTES
                    ? ApplicationProperties.getLongProperty(MAX_FETCH_BYTES_PROPERTY, DEFAULT_MAX_FETCH_BYTES)
                    : maxFetchBytes);

    /**
     * Maximum wait time for polling new messages. Use {@link Duration#ZERO} to return immediately when no messages or
     * segment claim are available.
     */
    @Default
    @NonNull
    Duration maxWaitDuration = Duration.ofSeconds(60);

    /**
     * Interceptors that are invoked before and after a batch of messages is processed.
     * <p>
     * These interceptors are ordered within the consumer using {@link io.fluxzero.sdk.common.Order @Order}. When a
     * tracker is started with a {@link io.fluxzero.sdk.Fluxzero} instance, the ordered consumer interceptors wrap the
     * globally configured batch interceptors for that message type.
     */
    @Singular
    List<BatchInterceptor> batchInterceptors;

    /**
     * Interceptors that wrap handler execution for additional behavior (e.g. logging, metrics, authorization).
     * <p>
     * These interceptors are ordered within the consumer using {@link io.fluxzero.sdk.common.Order @Order}. Global
     * handler interceptors configured via {@code FluxzeroBuilder} keep their existing position in the chain; consumer
     * interceptors are only sorted relative to other interceptors in the same consumer configuration.
     */
    @Singular
    List<HandlerInterceptor> handlerInterceptors;

    /**
     * Dispatch interceptors that become active while this consumer is processing a batch.
     * <p>
     * These interceptors are ordered within the consumer using {@link io.fluxzero.sdk.common.Order @Order} and are
     * applied through {@link io.fluxzero.sdk.publishing.AdhocDispatchInterceptor}. They therefore affect dispatches
     * performed by this consumer without changing the global dispatch chain. If ad hoc dispatch interceptors are
     * disabled globally, these consumer-scoped dispatch interceptors are inactive.
     */
    @Singular
    List<DispatchInterceptor> dispatchInterceptors;

    /**
     * If true, only messages that target the current application will be processed. This allows isolating handlers to
     * specific applications.
     */
    @Default
    @Accessors(fluent = true)
    boolean filterMessageTarget = false;

    /**
     * If true, the consumer will ignore the segment assigned to it and process all messages regardless of partitioning.
     * Useful for global handlers such as notifications or singletons.
     */
    @Default
    @Accessors(fluent = true)
    boolean ignoreSegment = false;

    /**
     * If true, forces a single tracker to be created for this consumer regardless of segment count.
     */
    @Default
    @Accessors(fluent = true)
    boolean singleTracker = false;

    /**
     * If true, the consumer's position in the log will be controlled by the client (not automatically). Allows handling
     * messages manually or out of order.
     */
    @Default
    @Accessors(fluent = true)
    boolean clientControlledIndex = false;

    /**
     * Whether this consumer is taking manual control over storing its position in the log.
     * <p>
     * When {@code true}, the consumer is responsible for explicitly storing its position after processing one or more
     * message batches. This allows for greater control — for example, when handling long-running workflows that span
     * multiple batches, or when committing position should be deferred until post-processing is complete.
     * <p>
     * When {@code false} (the default), the position is automatically updated after each message batch is processed,
     * ensuring progress is recorded and avoiding reprocessing on restart.
     * <p>
     * Note: Even with manual position tracking enabled, the consumer will continue to receive "new" messages as long as
     * the tracking process remains active. However, its persisted position will not be updated unless explicitly
     * stored.
     */
    @Default
    @Accessors(fluent = true)
    boolean storePositionManually = false;

    /**
     * If true, asynchronous handler results are awaited before the consumer completes the current batch.
     * <p>
     * When false, asynchronous results are still published when they complete, but the consumer can store its position
     * and fetch the next batch without waiting for those futures. This is the historical default and is useful for
     * high-throughput request handlers where each request has its own response correlation and batch-level ordering is
     * not needed.
     */
    @Default
    @Accessors(fluent = true)
    boolean awaitAsyncResults = false;

    /**
     * If true, futures returned by fire-and-forget dispatches started during this consumer's batch processing are
     * awaited before the consumer stores its position.
     * <p>
     * This lets handlers use {@code sendAndForget(..., Guarantee.STORED)} without explicitly joining the returned
     * future just to ensure the dispatch has reached its guarantee before the tracker commits progress. Disable this
     * for consumers that intentionally let fire-and-forget dispatches complete independently from batch commits.
     * Failures while awaiting are handled by this consumer's error handler like other batch processing failures.
     */
    @Default
    @Accessors(fluent = true)
    boolean awaitSendAndForgetFutures = true;

    /**
     * Controls whether handlers assigned to this consumer execute in the tracker thread or on a worker thread.
     * <p>
     * {@link ConsumerHandlingMode#DEFAULT} inherits the message-type default consumer's resolved mode. If that is also
     * default, Fluxzero falls back to {@link #defaultHandlingModeProperty(MessageType)},
     * {@link #DEFAULT_HANDLING_MODE_PROPERTY}, and versioned SDK defaults.
     */
    @Default
    @NonNull
    ConsumerHandlingMode handlingMode = ConsumerHandlingMode.DEFAULT;

    /**
     * Optional minimum index to start processing messages from.
     */
    Long minIndex;

    /**
     * Optional exclusive upper limit for message index (messages with this index or higher will be skipped).
     */
    Long maxIndexExclusive;

    /**
     * Whether this consumer remains exclusive for shared handlers before {@link #getMinIndex()}.
     * <p>
     * Intended primarily for consumer split scenarios. Set this to {@code false} on the new consumer when it should
     * only become exclusively responsible from {@code minIndex} onward, while another consumer continues to handle
     * older messages.
     */
    @Default
    boolean exclusiveBeforeMinIndex = true;

    /**
     * Whether this consumer remains exclusive for shared handlers from {@link #getMaxIndexExclusive()} onward.
     * <p>
     * Intended primarily for consumer merge or handover scenarios. Set this to {@code false} on the retiring consumer
     * when another consumer should take over once {@code maxIndexExclusive} has been reached.
     */
    @Default
    boolean exclusiveAfterMaxIndex = true;

    /**
     * If true (default), ensures this consumer is exclusive — no other consumers with the same name will run
     * concurrently.
     */
    @Default
    @Accessors(fluent = true)
    boolean exclusive = true;

    /**
     * If true, the consumer will passively listen to messages without marking them as consumed. Useful for debug,
     * logging, or metrics purposes.
     */
    @Default
    @Accessors(fluent = true)
    boolean passive = false;

    /**
     * Factory for generating a tracker ID for this consumer. The default implementation appends a random UUID to the
     * client ID.
     */
    @Default
    @EqualsAndHashCode.Exclude
    Function<Client, String> trackerIdFactory = client -> String.format("%s_%s", client.id(), UUID.randomUUID());

    /**
     * Optional delay after which previously consumed segments should be purged from memory.
     */
    @Default
    Duration purgeDelay = null;

    /**
     * Regulates the flow of messages to the consumer to prevent overload. Defaults to {@link NoOpFlowRegulator}, which
     * performs no throttling.
     */
    @Default
    FlowRegulator flowRegulator = NoOpFlowRegulator.getInstance();

    /**
     * Specifies the namespace under which the consumer tracks messages.
     * <p>
     * If set to {@code null}, the default namespace of the client application is used.
     *
     * @see Client#forNamespace(String)
     */
    @Default
    String namespace = null;

    @Override
    public ConsumerConfiguration substituteProperties() {
        return toBuilder()
                .name(ApplicationProperties.substituteProperties(name))
                .namespace(ApplicationProperties.substituteProperties(namespace))
                .typeFilter(ApplicationProperties.substituteProperties(typeFilter))
                .build();
    }

    private static long requireNonNegativeMaxFetchBytes(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "`maxFetchBytes` must be greater than or equal to 0 after resolving `%s`, but found `%s`."
                            .formatted(MAX_FETCH_BYTES_PROPERTY, value));
        }
        return value;
    }

    /**
     * Returns a copy with consumer-specific interceptors ordered by {@link io.fluxzero.sdk.common.Order}.
     */
    public ConsumerConfiguration ordered() {
        return toBuilder()
                .clearBatchInterceptors()
                .batchInterceptors(batchInterceptors.stream()
                                           .sorted(Comparator.comparingInt(ClientUtils::orderOf))
                                           .collect(Collectors.toList()))
                .clearHandlerInterceptors()
                .handlerInterceptors(handlerInterceptors.stream()
                                             .sorted(Comparator.comparingInt(ClientUtils::orderOf))
                                             .collect(Collectors.toList()))
                .clearDispatchInterceptors()
                .dispatchInterceptors(dispatchInterceptors.stream()
                                             .sorted(Comparator.comparingInt(ClientUtils::orderOf))
                                             .collect(Collectors.toList()))
                .build();
    }

    public boolean conditionallyExclusive() {
        return exclusive && ((minIndex != null && !exclusiveBeforeMinIndex)
                             || (maxIndexExclusive != null && !exclusiveAfterMaxIndex));
    }

    public int exclusivityPriority(Long index) {
        if (!exclusive) {
            return -1;
        }
        if (index == null) {
            return conditionallyExclusive() ? 2 : 1;
        }
        if (minIndex != null && index < minIndex) {
            return exclusiveBeforeMinIndex ? 0 : -1;
        }
        if (maxIndexExclusive != null && index >= maxIndexExclusive) {
            return exclusiveAfterMaxIndex ? 0 : -1;
        }
        return minIndex != null || maxIndexExclusive != null ? 2 : 1;
    }

    /* ---------- Utilities for deriving configuration from annotations ---------- */

    /**
     * Returns a stream of {@code ConsumerConfiguration}s by inspecting the given handler classes and their packages.
     * Includes both class-level and package-level {@code @Consumer} annotations.
     */
    public static Stream<ConsumerConfiguration> configurations(Collection<Class<?>> handlerClasses) {
        List<Class<?>> types = handlerClasses.stream().distinct().toList();
        List<Class<?>> scannableTypes = types.stream().filter(JvmComponentMetadataLookup::isScannable).toList();
        Optional<ComponentMetadataLookup> lookup =
                ComponentMetadataLookups.lookup(scannableTypes.toArray(Class<?>[]::new));
        if (lookup.isEmpty()) {
            return Stream.empty();
        }
        Stream<ConsumerConfiguration> classConfigurations =
                scannableTypes.stream().flatMap(type -> classConfigurations(lookup.get(), type));
        return Stream.concat(classConfigurations, packageMetadata(scannableTypes, lookup.get())
                .flatMap(ConsumerConfiguration::packageConfigurations));
    }

    private static Stream<PackageDescriptor> packageMetadata(
            Collection<Class<?>> handlerClasses, ComponentMetadataLookup lookup) {
        return handlerClasses.stream()
                .map(Class::getPackageName)
                .distinct()
                .flatMap(packageName -> lookup.packageMetadataChain(packageName).stream())
                .collect(Collectors.toMap(
                        PackageDescriptor::packageName, Function.identity(), (a, b) -> a, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(PackageDescriptor::packageName).reversed());
    }

    private static Stream<ConsumerConfiguration> classConfigurations(ComponentMetadataLookup lookup, Class<?> type) {
        return consumerDescriptor(lookup.typeAnnotations(type.getName()))
                .map(c -> getConfiguration(c, h -> handlerType(h).equals(type))).stream();
    }

    private static Stream<ConsumerConfiguration> packageConfigurations(PackageDescriptor descriptor) {
        return descriptor.consumerMetadata()
                .map(c -> getConfiguration(
                        c, h -> {
                            Class<?> type = handlerType(h);
                            String packageName = type.getPackageName();
                            return packageName.equals(descriptor.packageName())
                                   || packageName.startsWith(descriptor.packageName() + ".");
                        })).stream();
    }

    private static Class<?> handlerType(Object handler) {
        return handler instanceof Class<?> type ? type : handler.getClass();
    }

    private static Optional<ConsumerDescriptor> consumerDescriptor(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(Consumer.class.getName())
                                      || annotation.name().equals(Consumer.class.getSimpleName()))
                .findFirst()
                .map(annotation -> new ConsumerDescriptor(
                        annotation.firstValue("name").or(() -> annotation.firstValue("value")).orElse(""),
                        annotation.attributes(), annotation));
    }

    private static ConsumerConfiguration getConfiguration(ConsumerDescriptor consumer, Predicate<Object> handlerFilter) {
        return ConsumerConfiguration.builder()
                .name(consumer.name())
                .handlerFilter(handlerFilter)
                .errorHandler(errorHandler(classAttribute(consumer, "errorHandler", Object.class)))
                .flowRegulator(flowRegulator(classAttribute(consumer, "flowRegulator", Object.class)))
                .threads(intAttribute(consumer, "threads", 1))
                .maxFetchSize(intAttribute(consumer, "maxFetchSize", 1024))
                .maxFetchBytes(longAttribute(consumer, "maxFetchBytes", -1L))
                .maxWaitDuration(Duration.of(
                        longAttribute(consumer, "maxWaitDuration", 60L),
                        enumAttribute(consumer, "durationUnit", ChronoUnit.class, ChronoUnit.SECONDS)))
                .batchInterceptors(instantiateAll(consumer, "batchInterceptors"))
                .handlerInterceptors(instantiateAll(consumer, "handlerInterceptors"))
                .dispatchInterceptors(instantiateAll(consumer, "dispatchInterceptors"))
                .filterMessageTarget(booleanAttribute(consumer, "filterMessageTarget", false))
                .ignoreSegment(booleanAttribute(consumer, "ignoreSegment", false))
                .clientControlledIndex(booleanAttribute(consumer, "clientControlledIndex", false))
                .storePositionManually(booleanAttribute(consumer, "storePositionManually", false))
                .awaitAsyncResults(booleanAttribute(consumer, "awaitAsyncResults", false))
                .awaitSendAndForgetFutures(booleanAttribute(consumer, "awaitSendAndForgetFutures", true))
                .handlingMode(enumAttribute(
                        consumer, "handlingMode", ConsumerHandlingMode.class, ConsumerHandlingMode.DEFAULT))
                .singleTracker(booleanAttribute(consumer, "singleTracker", false))
                .minIndex(optionalIndex(longAttribute(consumer, "minIndex", -1L)))
                .maxIndexExclusive(optionalIndex(longAttribute(consumer, "maxIndexExclusive", -1L)))
                .exclusiveBeforeMinIndex(booleanAttribute(consumer, "exclusiveBeforeMinIndex", true))
                .exclusiveAfterMaxIndex(booleanAttribute(consumer, "exclusiveAfterMaxIndex", true))
                .exclusive(booleanAttribute(consumer, "exclusive", true))
                .passive(booleanAttribute(consumer, "passive", false))
                .typeFilter(blankToNull(stringAttribute(consumer, "typeFilter", "")))
                .namespace(blankToNull(stringAttribute(consumer, "namespace", "")))
                .build().ordered();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> instantiateAll(ConsumerDescriptor consumer, String attribute) {
        return consumer.annotation().values(attribute).stream()
                .map(className -> (T) instantiateComponent(
                        JvmComponentMetadataLookup.classForMetadataName(className)
                                .orElseGet(() -> introspector().classForName(className))))
                .collect(Collectors.toList());
    }

    private static JvmComponentIntrospector introspector() {
        return JvmCompatibilityBackend.introspector();
    }

    private static String stringAttribute(ConsumerDescriptor consumer, String attribute, String defaultValue) {
        return consumer.annotation().firstValue(attribute).orElse(defaultValue);
    }

    private static Class<?> classAttribute(
            ConsumerDescriptor consumer, String attribute, Class<?> defaultValue) {
        String className = stringAttribute(consumer, attribute, defaultValue.getName());
        return JvmComponentMetadataLookup.classForMetadataName(className, defaultValue);
    }

    private static int intAttribute(ConsumerDescriptor consumer, String attribute, int defaultValue) {
        return Integer.parseInt(stringAttribute(consumer, attribute, String.valueOf(defaultValue)));
    }

    private static long longAttribute(ConsumerDescriptor consumer, String attribute, long defaultValue) {
        return Long.parseLong(stringAttribute(consumer, attribute, String.valueOf(defaultValue)));
    }

    private static boolean booleanAttribute(ConsumerDescriptor consumer, String attribute, boolean defaultValue) {
        return Boolean.parseBoolean(stringAttribute(consumer, attribute, String.valueOf(defaultValue)));
    }

    private static <T extends Enum<T>> T enumAttribute(
            ConsumerDescriptor consumer, String attribute, Class<T> type, T defaultValue) {
        return Enum.valueOf(type, stringAttribute(consumer, attribute, defaultValue.name()));
    }

    private static Long optionalIndex(long index) {
        return index < 0 ? null : index;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ErrorHandler errorHandler(Class<?> type) {
        return Object.class.equals(type) ? new LoggingErrorHandler() : instantiateComponent(type);
    }

    private static FlowRegulator flowRegulator(Class<?> type) {
        return Object.class.equals(type) ? NoOpFlowRegulator.getInstance() : instantiateComponent(type);
    }

    @SuppressWarnings("unchecked")
    private static <T> T instantiateComponent(Class<?> type) {
        ComponentMetadataLookups.ensureGeneratedExecutions(type);
        var generatedConstructor = GeneratedExecutableInvocations.find(
                type, InvocationPlanDescriptor.executableId(ExecutableKind.CONSTRUCTOR, "<init>", List.of()));
        if (generatedConstructor.isPresent()) {
            return (T) generatedConstructor.orElseThrow().invoke(null);
        }
        if (ComponentMetadataLookups.strictGeneratedOnlyMode()) {
            throw new ComponentRegistryException(
                    "Generated-only metadata mode cannot instantiate %s without a generated no-arg constructor"
                            .formatted(type.getName()));
        }
        return introspector().instantiate(type);
    }
}
