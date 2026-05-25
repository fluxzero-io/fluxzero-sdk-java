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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.PropertySource;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.client.DefaultTracker;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.web.WebRequest;
import lombok.AllArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.newWorkerPool;
import static io.fluxzero.common.ObjectUtils.unwrapException;
import static io.fluxzero.sdk.common.ClientUtils.getLocalHandlerAnnotation;
import static io.fluxzero.sdk.common.ClientUtils.waitForResults;
import static io.fluxzero.sdk.common.serialization.DeserializingMessage.handleBatch;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_HANDSHAKE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_MESSAGE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_OPEN;
import static java.lang.String.format;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Default implementation of the {@link Tracking} interface that coordinates message tracking for a specific
 * {@link MessageType}.
 * <p>
 * This class is responsible for:
 * <ul>
 *   <li>Assigning handler objects to appropriate {@link ConsumerConfiguration}s based on declared filters</li>
 *   <li>Creating and managing {@link Tracker} instances for those consumers and their associated topics</li>
 *   <li>Ensuring messages are deserialized, dispatched, and (if applicable) responded to with proper error handling</li>
 *   <li>Invoking handlers using the provided {@link HandlerFactory} and {@link HandlerInvoker}</li>
 *   <li>Integrating with {@link ResultGateway} to send back command/query/web responses when needed</li>
 * </ul>
 *
 * <p>
 * Supports per-consumer batch interceptors and general batch processing logic, including:
 * <ul>
 *   <li>Functional and technical exception management with retry hooks</li>
 *   <li>Tracking exclusivity to prevent handlers from being assigned to multiple consumers simultaneously</li>
 *   <li>Internal shutdown coordination and pending message flushes via {@link #close()}</li>
 * </ul>
 *
 * <h2>Typical Usage</h2>
 * This class is used internally when starting a {@link Fluxzero#registerHandlers(List)} invocation
 * for a given {@link MessageType}, and typically shouldn't be used directly by application developers.
 *
 * @see Tracking
 * @see ConsumerConfiguration
 * @see Tracker
 * @see ResultGateway
 */
@AllArgsConstructor
@Slf4j
public class DefaultTracking implements Tracking {
    private static final LocalDate PER_HANDLER_DEFAULTS_VERSION = LocalDate.of(2026, 5, 20);
    private static final DateTimeFormatter DEFAULTS_VERSION_FORMAT = DateTimeFormatter.ofPattern("uuuu.MM.dd");
    private static final CompletionStage<Void> completedReport = CompletableFuture.completedFuture(null);

    private final HandlerFilter handlerFilter = (t, m) -> getLocalHandlerAnnotation(t, m)
            .map(LocalHandler::allowExternalMessages).orElse(true);
    private final MessageType messageType;
    private final ResultGateway resultGateway;
    private final List<ConsumerConfiguration> customConfigurations;
    private final List<ConsumerConfiguration> defaultConfigurations;
    private final Serializer serializer;
    private final HandlerFactory handlerFactory;

    private final Map<ConsumerConfiguration, List<Handler<DeserializingMessage>>> startedHandlers =
            new LinkedHashMap<>();
    private final Map<ConsumerConfiguration, Map<String, TopicTracker>> startedTopics = new LinkedHashMap<>();
    private final Collection<CompletableFuture<?>> outstandingRequests = new CopyOnWriteArrayList<>();
    private final ExecutorService chunkedMessageHandlerExecutor = newWorkerPool("tracking-chunked-message-handler", 8);
    private final AtomicReference<Registration> shutdownFunction = new AtomicReference<>(Registration.noOp());

    /**
     * Starts tracking by assigning the given handlers to configured consumers and creating topic-specific or shared
     * trackers.
     * <p>
     * Throws a {@link TrackingException} if handlers can't be matched to consumers.
     *
     * @param fluxzero the owning {@link Fluxzero} instance
     * @param handlers      the handler instances to assign and activate
     * @return a {@link Registration} that can be used to stop all created trackers
     * @throws TrackingException if no consumer is found for a handler
     */
    @SuppressWarnings("unchecked")
    @Override
    @Synchronized
    public Registration start(Fluxzero fluxzero, List<?> handlers) {
        return fluxzero.apply(fc -> {
            Map<ConsumerConfiguration, List<Object>> assignedHandlers = assignHandlersToConsumers(fc, handlers);
            Map<Object, List<ConsumerConfiguration>> consumersByHandler = new IdentityHashMap<>();
            assignedHandlers.forEach((config, matches) ->
                    matches.forEach(target -> consumersByHandler.computeIfAbsent(target, t -> new ArrayList<>())
                            .add(config)));
            Map<ConsumerConfiguration, List<Handler<DeserializingMessage>>> consumers =
                    assignedHandlers.entrySet().stream().flatMap(e -> {
                        List<Handler<DeserializingMessage>> converted = e.getValue().stream().flatMap(target -> {
                            HandlerDecorator decorator =
                                    conditionalExclusivityDecorator(e.getKey(), consumersByHandler.get(target));
                            if (target instanceof Handler<?> handler) {
                                return Stream.of(decorator.wrap((Handler<DeserializingMessage>) handler));
                            }
                            return handlerFactory.createHandler(target, handlerFilter,
                                                                e.getKey().getHandlerInterceptors())
                                    .map(decorator::wrap).stream();
                        }).collect(toList());
                        return converted.isEmpty() ? Stream.empty() :
                                Stream.of(new SimpleEntry<>(e.getKey(), converted));
                    }).collect(toMap(Entry::getKey, Entry::getValue));

            Registration registration = consumers.entrySet().stream()
                    .map(e -> registerConsumerHandlers(e.getKey(), e.getValue(), fc))
                    .reduce(Registration::merge).orElse(Registration.noOp());
            shutdownFunction.updateAndGet(r -> r.merge(registration));
            return registration;
        });
    }

    private Registration registerConsumerHandlers(ConsumerConfiguration configuration,
                                                  List<Handler<DeserializingMessage>> handlers, Fluxzero fluxzero) {
        var addedHandlers = List.copyOf(handlers);
        List<Handler<DeserializingMessage>> activeHandlers =
                startedHandlers.computeIfAbsent(configuration, ignored -> new CopyOnWriteArrayList<>());
        activeHandlers.addAll(addedHandlers);
        List<String> retainedTopics = retainTrackingTopics(configuration, activeHandlers, addedHandlers, fluxzero);
        AtomicBoolean cancelled = new AtomicBoolean();
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                unregisterConsumerHandlers(configuration, addedHandlers, retainedTopics);
            }
        };
    }

    @Synchronized
    private void unregisterConsumerHandlers(ConsumerConfiguration configuration,
                                            List<Handler<DeserializingMessage>> handlers,
                                            List<String> topics) {
        Optional.ofNullable(startedHandlers.get(configuration)).ifPresent(activeHandlers -> {
            activeHandlers.removeAll(handlers);
            if (activeHandlers.isEmpty()) {
                startedHandlers.remove(configuration);
            }
        });
        releaseTrackingTopics(configuration, topics);
    }

    /**
     * Matches the given handlers to known {@link ConsumerConfiguration}s using handler filters and exclusivity rules.
     * <p>
     * Throws a {@link TrackingException} if:
     * <ul>
     *   <li>No consumer is found for a handler</li>
     *   <li>Conflicting consumers have been defined for the same handler</li>
     * </ul>
     */
    private Map<ConsumerConfiguration, List<Object>> assignHandlersToConsumers(Fluxzero fluxzero, List<?> handlers) {
        List<ConsumerConfiguration> explicitConfigurations = explicitConfigurations(handlers).toList();
        if (useSharedDefaultAppConsumerForUnconfiguredHandlers(fluxzero)) {
            return assignHandlersToConsumers(
                    handlers, Stream.concat(explicitConfigurations.stream(), defaultConfigurations.stream()));
        }
        List<Object> fallbackHandlers = fallbackHandlers(handlers, explicitConfigurations);
        return assignHandlersToConsumers(
                handlers, Stream.concat(explicitConfigurations.stream(),
                                        defaultConsumerConfigurations(fluxzero, fallbackHandlers)));
    }

    private Stream<ConsumerConfiguration> explicitConfigurations(List<?> handlers) {
        return Stream.concat(
                ConsumerConfiguration.configurations(handlers.stream().map(ReflectionUtils::asClass).collect(toList())),
                customConfigurations.stream());
    }

    private Map<ConsumerConfiguration, List<Object>> assignHandlersToConsumers(
            List<?> handlers, Stream<ConsumerConfiguration> configurations) {
        var unassignedHandlers = new ArrayList<Object>(handlers);
        var result = normalizeConfigurations(configurations).stream().map(config -> {
            var matches =
                    unassignedHandlers.stream().filter(h -> config.getHandlerFilter().test(h)).toList();
            if (config.exclusive() && !config.conditionallyExclusive()) {
                unassignedHandlers.removeAll(matches);
            }
            return Map.entry(config, matches);
        }).collect(toMap(Entry::getKey, Entry::getValue));
        unassignedHandlers.removeAll(
                result.values().stream().flatMap(Collection::stream).distinct().toList());
        unassignedHandlers.forEach(h -> {
            throw new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                    "No consumer configuration found for handler",
                    "Fluxzero could not match handler `%s` to any configured consumer.".formatted(h),
                    "Annotate the handler, class, or package with @Consumer, or add a ConsumerConfiguration whose "
                    + "handlerFilter matches this handler.",
                    h, null));
        });
        return result;
    }

    private static boolean useSharedDefaultAppConsumerForUnconfiguredHandlers(Fluxzero fluxzero) {
        return unconfiguredHandlerConsumerMode(fluxzero.propertySource())
               == UnconfiguredHandlerConsumerMode.DEFAULT_APP_CONSUMER;
    }

    private static UnconfiguredHandlerConsumerMode unconfiguredHandlerConsumerMode(PropertySource propertySource) {
        String configuredMode = propertySource.get(
                ConsumerConfiguration.UNCONFIGURED_HANDLER_CONSUMER_MODE_PROPERTY);
        if (configuredMode != null) {
            return parseUnconfiguredHandlerConsumerMode(configuredMode);
        }
        return defaultsVersionUsesPerHandlerConsumers(propertySource)
                ? UnconfiguredHandlerConsumerMode.PER_HANDLER
                : UnconfiguredHandlerConsumerMode.DEFAULT_APP_CONSUMER;
    }

    private static UnconfiguredHandlerConsumerMode parseUnconfiguredHandlerConsumerMode(String mode) {
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

    private static boolean defaultsVersionUsesPerHandlerConsumers(PropertySource propertySource) {
        return Optional.ofNullable(defaultsVersion(propertySource))
                .map(version -> !version.isBefore(PER_HANDLER_DEFAULTS_VERSION)).orElse(false);
    }

    private static LocalDate defaultsVersion(PropertySource propertySource) {
        String value = propertySource.get(ApplicationProperties.DEFAULTS_VERSION_PROPERTY);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DEFAULTS_VERSION_FORMAT);
        } catch (DateTimeParseException e) {
            throw new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                    "Invalid Fluxzero defaults version",
                    "Property `%s` must use format `yyyy.MM.dd`, but found `%s`.".formatted(
                            ApplicationProperties.DEFAULTS_VERSION_PROPERTY, value),
                    "Set a date like `2026.05.20`, or remove the property to use compatibility defaults.",
                    null, value), e);
        }
    }

    private enum UnconfiguredHandlerConsumerMode {
        DEFAULT_APP_CONSUMER,
        PER_HANDLER
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

    private List<ConsumerConfiguration> normalizeConfigurations(Stream<ConsumerConfiguration> configurations) {
        return configurations
                .sorted(Comparator.comparing(ConsumerConfiguration::exclusive))
                .map(ConsumerConfiguration::ordered)
                .map(ConsumerConfiguration::substituteProperties)
                .collect(toMap(ConsumerConfiguration::getName, Function.identity(),
                               DefaultTracking::mergeConfigurations, LinkedHashMap::new))
                .values().stream().toList();
    }

    private static ConsumerConfiguration mergeConfigurations(ConsumerConfiguration a, ConsumerConfiguration b) {
        if (a.equals(b)) {
            return a.toBuilder().handlerFilter(a.getHandlerFilter().or(b.getHandlerFilter())).build();
        }
        throw new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                "Consumer name is configured more than once",
                "Fluxzero found multiple different consumer configurations named `%s`.".formatted(
                        a.getName()),
                "Use unique consumer names, or make the repeated @Consumer configurations identical so "
                + "Fluxzero can merge their handler filters.",
                null, a.getName()));
    }

    private Stream<ConsumerConfiguration> defaultConsumerConfigurations(Fluxzero fluxzero, List<Object> handlers) {
        if (handlers.isEmpty() || defaultConfigurations.isEmpty()) {
            return Stream.empty();
        }
        ConsumerConfiguration template = defaultConfigurations.getFirst();
        List<Class<?>> handlerTypes = handlers.stream().map(ReflectionUtils::asClass).distinct().toList();
        Map<String, Integer> simpleNameCounts = new HashMap<>();
        handlerTypes.stream().map(DefaultTracking::consumerSimpleName)
                .forEach(name -> simpleNameCounts.merge(name, 1, Integer::sum));
        return handlerTypes.stream().map(handlerType -> defaultConsumerConfiguration(
                fluxzero.client().name(), template, handlerType,
                simpleNameCounts.get(consumerSimpleName(handlerType)) > 1));
    }

    private static ConsumerConfiguration defaultConsumerConfiguration(
            String applicationName, ConsumerConfiguration template, Class<?> handlerType, boolean includePackageName) {
        Predicate<Object> handlerFilter = h -> ReflectionUtils.asClass(h).equals(handlerType);
        return template.toBuilder()
                .name(defaultConsumerName(applicationName, handlerType, includePackageName))
                .handlerFilter(template.getHandlerFilter().and(handlerFilter))
                .build();
    }

    private static String defaultConsumerName(String applicationName, Class<?> handlerType,
                                              boolean includePackageName) {
        String handlerName = includePackageName ? handlerType.getName() : consumerSimpleName(handlerType);
        String sanitizedHandlerName = handlerName.replace('.', '_').replace('$', '_');
        return applicationName == null || applicationName.isBlank() ? sanitizedHandlerName
                : "%s_%s".formatted(applicationName, sanitizedHandlerName);
    }

    private static String consumerSimpleName(Class<?> handlerType) {
        String simpleName = handlerType.getSimpleName();
        return simpleName == null || simpleName.isBlank() ? handlerType.getName() : simpleName;
    }

    private HandlerDecorator conditionalExclusivityDecorator(ConsumerConfiguration currentConfig,
                                                             List<ConsumerConfiguration> handlerConsumers) {
        if (handlerConsumers == null || handlerConsumers.stream().noneMatch(ConsumerConfiguration::conditionallyExclusive)) {
            return HandlerDecorator.noOp;
        }
        return handler -> new Handler<>() {
            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                var index = message.getIndex();
                ConsumerConfiguration selected = null;
                int highestPriority = Integer.MIN_VALUE;
                for (ConsumerConfiguration config : handlerConsumers) {
                    int priority = config.exclusivityPriority(index);
                    if (priority > highestPriority) {
                        highestPriority = priority;
                        selected = config;
                    }
                }
                return Objects.equals(selected, currentConfig) ? handler.getInvokerOrNull(message) : null;
            }

            @Override
            public Class<?> getTargetClass() {
                return handler.getTargetClass();
            }
        };
    }

    protected List<String> retainTrackingTopics(ConsumerConfiguration configuration,
                                                List<Handler<DeserializingMessage>> activeHandlers,
                                                List<Handler<DeserializingMessage>> addedHandlers,
                                                Fluxzero fluxzero) {
        Set<String> topics = trackingTopics(addedHandlers);
        Map<String, TopicTracker> activeTopics =
                startedTopics.computeIfAbsent(configuration, ignored -> new LinkedHashMap<>());
        List<String> retainedTopics = new ArrayList<>();
        for (String topic : topics) {
            activeTopics.computeIfAbsent(topic,
                    t -> new TopicTracker(startTracking(configuration, activeHandlers, t, fluxzero))).retain();
            retainedTopics.add(topic);
        }
        return retainedTopics;
    }

    private void releaseTrackingTopics(ConsumerConfiguration configuration, List<String> topics) {
        Map<String, TopicTracker> activeTopics = startedTopics.get(configuration);
        if (activeTopics == null) {
            return;
        }
        for (String topic : topics) {
            TopicTracker tracker = activeTopics.get(topic);
            if (tracker != null && tracker.release()) {
                activeTopics.remove(topic);
            }
        }
        if (activeTopics.isEmpty()) {
            startedTopics.remove(configuration);
        }
    }

    protected Set<String> trackingTopics(List<Handler<DeserializingMessage>> handlers) {
        var topics = ClientUtils.getTopics(messageType, handlers.stream().<Class<?>>map(Handler::getTargetClass)
                .filter(Objects::nonNull).toList());
        if (!topics.isEmpty()) {
            return new HashSet<>(topics);
        }
        return switch (messageType) {
            case DOCUMENT, CUSTOM -> Set.of();
            default -> new HashSet<>(Collections.singleton(null));
        };
    }

    protected Registration startTracking(ConsumerConfiguration configuration,
                                         List<Handler<DeserializingMessage>> handlers, String topic,
                                         Fluxzero fluxzero) {
        return topic == null
                ? DefaultTracker.start(createConsumer(configuration, handlers), messageType, configuration, fluxzero)
                : DefaultTracker.start(createConsumer(configuration, handlers), messageType, topic, configuration,
                                       fluxzero);
    }

    private static class TopicTracker {
        private final Registration registration;
        private int references;

        private TopicTracker(Registration registration) {
            this.registration = registration;
        }

        void retain() {
            references++;
        }

        boolean release() {
            references--;
            if (references > 0) {
                return false;
            }
            registration.cancel();
            return true;
        }
    }

    protected Consumer<List<SerializedMessage>> createConsumer(ConsumerConfiguration config,
                                                               List<Handler<DeserializingMessage>> handlers) {
        Map<String, ChunkedDeserializingMessage> activeChunkedMessages = new ConcurrentHashMap<>();
        return serializedMessages -> {
            String topic = Tracker.current().orElseThrow().getTopic();
            TrackingClient trackingClient = Fluxzero.get().client().forNamespace(config.getNamespace())
                    .getTrackingClient(messageType, topic);
            try {
                handleBatch(deserializeMessages(serializedMessages, topic, trackingClient, activeChunkedMessages,
                                                config.getMaxFetchSize()))
                        .forEach(m -> handlers.forEach(h -> tryHandle(m, h, config, true)));
            } catch (BatchProcessingException e) {
                throw e;
            } catch (Throwable e) {
                config.getErrorHandler().handleError(
                        e, format("Failed to handle batch of consumer %s", config.getName()),
                        () -> handleBatch(
                                        deserializeMessages(serializedMessages, topic, trackingClient,
                                                            activeChunkedMessages, config.getMaxFetchSize()))
                                .forEach(m -> handlers.forEach(h -> tryHandle(m, h, config, false))));
            }
        };
    }

    protected Stream<DeserializingMessage> deserializeMessages(List<SerializedMessage> serializedMessages, String topic,
                                                               TrackingClient trackingClient,
                                                               Map<String, ChunkedDeserializingMessage>
                                                                       activeChunkedMessages,
                                                               int recoveryMaxFetchSize) {
        boolean hasChunkedMessages = false;
        for (SerializedMessage message : serializedMessages) {
            if (message.chunked()) {
                hasChunkedMessages = true;
                break;
            }
        }
        if (!hasChunkedMessages) {
            return deserializeNonChunkedMessages(serializedMessages, topic).stream();
        }
        List<DeserializingMessage> result = new ArrayList<>();
        Map<String, List<SerializedMessage>> pendingContinuations = new HashMap<>();
        for (SerializedMessage message : serializedMessages) {
            if (!message.chunked()) {
                DeserializingMessage deserializedMessage = deserializeNonChunkedMessageOrNull(message, topic);
                if (deserializedMessage != null) {
                    result.add(deserializedMessage);
                }
                continue;
            }
            if (!message.firstChunk()) {
                String key = chunkKey(topic, message);
                ChunkedDeserializingMessage chunkedMessage = activeChunkedMessages.get(key);
                if (chunkedMessage == null) {
                    chunkedMessage = recoverChunkedMessage(topic, trackingClient, message, recoveryMaxFetchSize)
                            .orElse(null);
                    if (chunkedMessage != null) {
                        result.add(chunkedMessage);
                        trackActiveChunkedMessage(activeChunkedMessages, key, chunkedMessage);
                    }
                }
                if (chunkedMessage == null) {
                    pendingContinuations.computeIfAbsent(message.getMessageId(), ignored -> new ArrayList<>())
                            .add(message);
                } else {
                    chunkedMessage.appendObservedContinuation(message);
                }
                continue;
            }
            ChunkedDeserializingMessage chunkedMessage =
                    new ChunkedDeserializingMessage(message, messageType, topic, serializer);
            String chunkKey = chunkKey(topic, message);
            List<SerializedMessage> observedContinuations = pendingContinuations.remove(message.getMessageId());
            if (observedContinuations != null) {
                observedContinuations.forEach(chunkedMessage::appendObservedContinuation);
            }
            trackActiveChunkedMessage(activeChunkedMessages, chunkKey, chunkedMessage);
            result.add(chunkedMessage);
        }
        pendingContinuations.values().stream().flatMap(Collection::stream).forEach(this::logSkippedContinuation);
        return result.stream();
    }

    private List<DeserializingMessage> deserializeNonChunkedMessages(List<SerializedMessage> serializedMessages,
                                                                     String topic) {
        List<DeserializingMessage> result = new ArrayList<>(serializedMessages.size());
        for (SerializedMessage message : serializedMessages) {
            DeserializingMessage deserializedMessage = deserializeNonChunkedMessageOrNull(message, topic);
            if (deserializedMessage != null) {
                result.add(deserializedMessage);
            }
        }
        return result;
    }

    private DeserializingMessage deserializeNonChunkedMessageOrNull(SerializedMessage message, String topic) {
        return serializer.deserializeFirstMessageOrNull(message, messageType, topic);
    }

    private String chunkKey(String topic, SerializedMessage message) {
        return messageType + ":" + topic + ":" + message.getMessageId();
    }

    private void trackActiveChunkedMessage(Map<String, ChunkedDeserializingMessage> activeChunkedMessages,
                                           String chunkKey, ChunkedDeserializingMessage chunkedMessage) {
        if (!chunkedMessage.completion().isDone()) {
            activeChunkedMessages.put(chunkKey, chunkedMessage);
            chunkedMessage.completion().whenComplete(
                    (ignored, error) -> activeChunkedMessages.remove(chunkKey, chunkedMessage));
        }
    }

    private Optional<ChunkedDeserializingMessage> recoverChunkedMessage(String topic, TrackingClient trackingClient,
                                                                       SerializedMessage continuation,
                                                                       int maxFetchSize) {
        if (continuation.getIndex() == null || continuation.getTimestamp() == null) {
            return Optional.empty();
        }
        long minIndex = Math.min(continuation.getIndex(),
                                 Math.max(0L, IndexUtils.indexFromMillis(continuation.getTimestamp())));
        Optional<ChunkedDeserializingMessage> result = ChunkedDeserializingMessage.recoverFromContinuation(
                continuation, messageType, topic, serializer, minIndex, maxFetchSize, trackingClient::readRange);
        result.ifPresent(message -> log.debug(
                "Recovered chunked {} message {} from index range [{}, {}) after observing continuation at index {}",
                messageType, continuation.getMessageId(), minIndex, continuation.getIndex(),
                continuation.getIndex()));
        return result;
    }

    private void logSkippedContinuation(SerializedMessage message) {
        log.warn("Skipping chunked {} message {} at index {} because the first chunk was not observed "
                 + "(firstChunk={}, finalChunk={}, chunkIndex={})",
                 messageType, message.getMessageId(), message.getIndex(),
                 message.firstChunk(), message.lastChunk(), message.getMetadata().get(HasMetadata.CHUNK_INDEX));
    }

    protected void tryHandle(DeserializingMessage message, Handler<DeserializingMessage> handler,
                             ConsumerConfiguration config, boolean reportResult) {
        Optional<HandlerInvoker> optionalInvoker = getInvoker(message, handler, config);
        if (optionalInvoker.isEmpty()) {
            return;
        }
        HandlerInvoker h = optionalInvoker.get();
        Object result;
        try {
            result = handle(message, h, handler, config);
        } catch (Throwable e) {
            try {
                stopTracker(message, handler, e);
                return;
            } finally {
                if (reportResult) {
                    reportResult(e, h, message, config);
                }
            }
        }
        try {
            if (reportResult) {
                reportResult(result, h, message, config);
            }
        } catch (Throwable e) {
            stopTracker(message, handler, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected Optional<HandlerInvoker> getInvoker(DeserializingMessage message, Handler<DeserializingMessage> handler,
                                                  ConsumerConfiguration config) {
        try {
            return Optional.ofNullable(handler.getInvokerOrNull(message));
        } catch (Throwable e) {
            try {
                Object retryResult = config.getErrorHandler().handleError(
                        e, format("Failed to check if handler %s is able to handle %s", handler, message),
                        () -> Optional.ofNullable(handler.getInvokerOrNull(message)));
                return retryResult instanceof Optional<?> ? (Optional<HandlerInvoker>) retryResult : Optional.empty();
            } catch (Throwable e2) {
                stopTracker(message, handler, e2);
                return Optional.empty();
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected Object handle(DeserializingMessage message, HandlerInvoker h, Handler<DeserializingMessage> handler,
                            ConsumerConfiguration config) {
        if (message instanceof ChunkedDeserializingMessage) {
            Fluxzero fluxzero = Fluxzero.getOptionally().orElse(null);
            Tracker tracker = Tracker.current().orElse(null);
            User user = User.getCurrent();
            return supplyAsync(
                    () -> withHandlerContext(message, fluxzero, tracker, user,
                                             () -> doHandle(message, h, handler, config)),
                    chunkedMessageHandlerExecutor);
        }
        return doHandle(message, h, handler, config);
    }

    @SuppressWarnings("unchecked")
    protected Object doHandle(DeserializingMessage message, HandlerInvoker h, Handler<DeserializingMessage> handler,
                              ConsumerConfiguration config) {
        try {
            Object result = Invocation.performInvocation(h, h::invoke);
            return result instanceof CompletionStage<?> ? ((CompletionStage<Object>) result)
                    .exceptionally(e -> message.apply(m -> processError(e, message, h, handler, config))) : result;
        } catch (Throwable e) {
            return processError(e, message, h, handler, config);
        }
    }

    protected <T> T withHandlerContext(DeserializingMessage message, Fluxzero fluxzero, Tracker tracker, User user,
                                       Supplier<T> task) {
        Fluxzero previousFluxzero = Fluxzero.instance.get();
        Tracker previousTracker = Tracker.current.get();
        User previousUser = User.getCurrent();
        try {
            setThreadLocal(Fluxzero.instance, fluxzero);
            setThreadLocal(Tracker.current, tracker);
            setThreadLocal(User.current, user);
            return message.apply(m -> task.get());
        } finally {
            setThreadLocal(Fluxzero.instance, previousFluxzero);
            setThreadLocal(Tracker.current, previousTracker);
            setThreadLocal(User.current, previousUser);
        }
    }

    protected <T> void setThreadLocal(ThreadLocal<T> threadLocal, T value) {
        if (value == null) {
            threadLocal.remove();
        } else {
            threadLocal.set(value);
        }
    }

    protected Object processError(Throwable e, DeserializingMessage message, HandlerInvoker h,
                                  Handler<DeserializingMessage> handler, ConsumerConfiguration config) {
        return config.getErrorHandler().handleError(
                unwrapException(e), format("Handler %s failed to handle a %s", handler, message),
                () -> Invocation.performInvocation(h, h::invoke));
    }

    protected CompletionStage<Void> reportResult(Object result, HandlerInvoker h, DeserializingMessage message,
                                                 ConsumerConfiguration config) {
        if (result instanceof CompletionStage<?> s) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            CompletableFuture<?> resultFuture = s.toCompletableFuture();
            outstandingRequests.add(resultFuture);
            s.whenComplete((r, e) -> {
                try {
                    message.run(m -> reportResult(Optional.<Object>ofNullable(e).orElse(r), h, message, config)
                            .toCompletableFuture().join());
                    completion.complete(null);
                } catch (Throwable t) {
                    completion.completeExceptionally(t);
                } finally {
                    outstandingRequests.remove(resultFuture);
                    if (e != null) {
                        close();
                    }
                }
            });
            return completion;
        } else {
            if (shouldSendResponse(h, message, result, config)) {
                if (result instanceof Throwable) {
                    result = unwrapException((Throwable) result);
                    if (!(result instanceof FunctionalException)) {
                        result = new TechnicalException(FluxzeroErrors.handlerInvocationFailed(
                                h.getMethod().toString(), message.toString(), (Throwable) result), (Throwable) result);
                    }
                }
                SerializedMessage request = message.getSerializedObject();
                ResultGateway resultGateway = this.resultGateway.forNamespace(config.getNamespace());
                try {
                    resultGateway.respond(result, request.getSource(), request.getRequestId());
                } catch (Throwable e) {
                    Object response = result;
                    config.getErrorHandler().handleError(
                            e, format("Failed to send result of a %s from handler %s", message, h.getMethod()),
                            () -> resultGateway.respond(response, request.getSource(), request.getRequestId()));
                }
            }
            return completedReport;
        }
    }

    protected boolean shouldSendResponse(HandlerInvoker invoker, DeserializingMessage request,
                                         Object result, ConsumerConfiguration config) {
        if (!request.getMessageType().isRequest() || config.passive() || invoker.isPassive()) {
            return false;
        }
        if (request.getMessageType() == MessageType.WEBREQUEST) {
            switch (WebRequest.getMethod(request.getMetadata())) {
                case WS_HANDSHAKE, WS_OPEN, WS_MESSAGE -> {
                    return true;
                }
            }
        }
        return request.getSerializedObject().getRequestId() != null;
    }

    protected void stopTracker(DeserializingMessage message, Handler<DeserializingMessage> handler, Throwable e) {
        throw e instanceof BatchProcessingException
                ? new BatchProcessingException(format("Handler %s failed to handle a %s", handler, message),
                                               e.getCause(), ((BatchProcessingException) e).getMessageIndex())
                : new BatchProcessingException(message.getIndex());
    }

    /**
     * Shuts down all started trackers and waits briefly for asynchronous results (e.g. command responses) to complete.
     */
    @Override
    @Synchronized
    public void close() {
        shutdownFunction.get().merge(() -> waitForResults(Duration.ofSeconds(2), outstandingRequests))
                .merge(chunkedMessageHandlerExecutor::shutdown)
                .cancel();
    }
}
