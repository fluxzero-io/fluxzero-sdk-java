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
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.client.DefaultTracker;
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
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final HandlerFilter handlerFilter = (t, m) -> getLocalHandlerAnnotation(t, m)
            .map(LocalHandler::allowExternalMessages).orElse(true);
    private final MessageType messageType;
    private final ResultGateway resultGateway;
    private final List<ConsumerConfiguration> configurations;
    private final Serializer serializer;
    private final HandlerFactory handlerFactory;

    private final Set<ConsumerConfiguration> startedConfigurations = new HashSet<>();
    private final Collection<CompletableFuture<?>> outstandingRequests = new CopyOnWriteArrayList<>();
    private final ExecutorService chunkedMessageExecutor = newWorkerPool("tracking-chunked-message", 8);
    private final AtomicReference<Registration> shutdownFunction = new AtomicReference<>(Registration.noOp());

    /**
     * Starts tracking by assigning the given handlers to configured consumers and creating topic-specific or shared
     * trackers.
     * <p>
     * Throws a {@link TrackingException} if handlers can't be matched to consumers or if a consumer has already been
     * started previously.
     *
     * @param fluxzero the owning {@link Fluxzero} instance
     * @param handlers      the handler instances to assign and activate
     * @return a {@link Registration} that can be used to stop all created trackers
     * @throws TrackingException if no consumer is found for a handler or if tracking has already been started
     */
    @SuppressWarnings("unchecked")
    @Override
    @Synchronized
    public Registration start(Fluxzero fluxzero, List<?> handlers) {
        return fluxzero.apply(fc -> {
            Map<ConsumerConfiguration, List<Object>> assignedHandlers = assignHandlersToConsumers(handlers);
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


            if (!Collections.disjoint(consumers.keySet(), startedConfigurations)) {
                throw new TrackingException("Failed to start tracking. "
                                            + "Consumers for some handlers have already started tracking.");
            }

            startedConfigurations.addAll(consumers.keySet());
            Registration registration =
                    consumers.entrySet().stream().map(e -> startTracking(e.getKey(), e.getValue(), fc))
                            .reduce(Registration::merge).orElse(Registration.noOp());
            shutdownFunction.updateAndGet(r -> r.merge(registration));
            return registration;
        });
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
    private Map<ConsumerConfiguration, List<Object>> assignHandlersToConsumers(List<?> handlers) {
        var unassignedHandlers = new ArrayList<Object>(handlers);
        var configurations = Stream.concat(
                        ConsumerConfiguration.configurations(handlers.stream().map(ReflectionUtils::asClass).collect(toList())),
                        this.configurations.stream())
                .sorted(Comparator.comparing(ConsumerConfiguration::exclusive))
                .map(ConsumerConfiguration::ordered)
                .map(ConsumerConfiguration::substituteProperties)
                .collect(toMap(ConsumerConfiguration::getName, Function.identity(), (a, b) -> {
                    if (a.equals(b)) {
                        return a.toBuilder().handlerFilter(a.getHandlerFilter().or(b.getHandlerFilter())).build();
                    }
                    throw new IllegalStateException(format("Consumer name %s is already in use", a.getName()));
                }, LinkedHashMap::new));
        var result = configurations.values().stream().map(config -> {
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
            throw new TrackingException(format("Failed to find consumer for %s", h));
        });
        return result;
    }

    private HandlerDecorator conditionalExclusivityDecorator(ConsumerConfiguration currentConfig,
                                                             List<ConsumerConfiguration> handlerConsumers) {
        if (handlerConsumers == null || handlerConsumers.stream().noneMatch(ConsumerConfiguration::conditionallyExclusive)) {
            return HandlerDecorator.noOp;
        }
        return handler -> new Handler<>() {
            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
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
                return Objects.equals(selected, currentConfig) ? handler.getInvoker(message) : Optional.empty();
            }

            @Override
            public Class<?> getTargetClass() {
                return handler.getTargetClass();
            }
        };
    }

    protected Registration startTracking(ConsumerConfiguration configuration,
                                         List<Handler<DeserializingMessage>> handlers, Fluxzero fluxzero) {
        var topics = ClientUtils.getTopics(messageType, handlers.stream().<Class<?>>map(Handler::getTargetClass)
                .filter(Objects::nonNull).toList());
        if (topics.isEmpty()) {
            return switch (messageType) {
                case DOCUMENT, CUSTOM -> Registration.noOp();
                default -> DefaultTracker.start(
                        createConsumer(configuration, handlers), messageType, configuration, fluxzero);
            };
        }
        return topics.stream().map(topic -> DefaultTracker.start(
                        createConsumer(configuration, handlers), messageType, topic, configuration, fluxzero))
                .reduce(Registration::merge).orElseGet(Registration::noOp);
    }

    protected Consumer<List<SerializedMessage>> createConsumer(ConsumerConfiguration config,
                                                               List<Handler<DeserializingMessage>> handlers) {
        Map<String, ChunkedDeserializingMessage> activeChunkedMessages = new ConcurrentHashMap<>();
        Map<String, Long> expectedChunkIndices = new ConcurrentHashMap<>();
        return serializedMessages -> {
            String topic = Tracker.current().orElseThrow().getTopic();
            try {
                handleBatch(deserializeMessages(serializedMessages, topic, activeChunkedMessages, expectedChunkIndices))
                        .forEach(m -> handlers.forEach(h -> tryHandle(m, h, config, true)));
            } catch (BatchProcessingException e) {
                throw e;
            } catch (Throwable e) {
                config.getErrorHandler().handleError(
                        e, format("Failed to handle batch of consumer %s", config.getName()),
                        () -> handleBatch(deserializeMessages(serializedMessages, topic, activeChunkedMessages,
                                                              expectedChunkIndices))
                                .forEach(m -> handlers.forEach(h -> tryHandle(m, h, config, false))));
            }
        };
    }

    protected Stream<DeserializingMessage> deserializeMessages(List<SerializedMessage> serializedMessages, String topic,
                                                               Map<String, ChunkedDeserializingMessage> activeChunkedMessages,
                                                               Map<String, Long> expectedChunkIndices) {
        List<DeserializingMessage> result = new ArrayList<>();
        for (SerializedMessage message : serializedMessages) {
            if (!message.chunked()) {
                result.add(serializer.deserializeMessages(Stream.of(message), messageType, topic)
                                   .findAny().orElseThrow());
                continue;
            }
            ChunkedDeserializingMessage chunkedMessage = activeChunkedMessages.get(message.getMessageId());
            if (chunkedMessage == null) {
                if (!message.firstChunk()) {
                    log.warn(
                            "Skipping chunked {} message {} at index {} because the first chunk was not observed "
                            + "(firstChunk={}, finalChunk={}, chunkIndex={})",
                            messageType, message.getMessageId(), message.getIndex(),
                            message.firstChunk(), message.lastChunk(),
                            message.getMetadata().get(HasMetadata.CHUNK_INDEX));
                    continue;
                }
                chunkedMessage = new ChunkedDeserializingMessage(message, messageType, topic, serializer);
                if (!message.lastChunk()) {
                    activeChunkedMessages.put(message.getMessageId(), chunkedMessage);
                    Long nextChunkIndex = nextProxyChunkIndex(message);
                    if (nextChunkIndex != null) {
                        expectedChunkIndices.put(message.getMessageId(), nextChunkIndex);
                    }
                }
                result.add(chunkedMessage);
            } else {
                checkChunkSequence(message, expectedChunkIndices.get(message.getMessageId()));
                chunkedMessage.appendChunk(message);
                if (message.lastChunk()) {
                    activeChunkedMessages.remove(message.getMessageId());
                    expectedChunkIndices.remove(message.getMessageId());
                } else {
                    Long nextChunkIndex = nextProxyChunkIndex(message);
                    if (nextChunkIndex != null) {
                        expectedChunkIndices.put(message.getMessageId(), nextChunkIndex);
                    }
                }
            }
        }
        return result.stream();
    }

    protected void checkChunkSequence(SerializedMessage message, Long expectedChunkIndex) {
        Long actualChunkIndex = chunkIndex(message);
        if (actualChunkIndex != null && expectedChunkIndex != null && !actualChunkIndex.equals(expectedChunkIndex)) {
            log.warn("Observed out-of-sequence chunked {} message {}: expected chunkIndex={} but got {} "
                     + "(firstChunk={}, finalChunk={}, index={})",
                     messageType, message.getMessageId(), expectedChunkIndex, actualChunkIndex,
                     message.firstChunk(), message.lastChunk(), message.getIndex());
        }
    }

    protected Long nextProxyChunkIndex(SerializedMessage message) {
        Long current = chunkIndex(message);
        return current == null ? null : current + 1;
    }

    protected Long chunkIndex(SerializedMessage message) {
        try {
            String value = message.getMetadata().get(HasMetadata.CHUNK_INDEX);
            return value == null ? null : Long.valueOf(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    protected void tryHandle(DeserializingMessage message, Handler<DeserializingMessage> handler,
                             ConsumerConfiguration config, boolean reportResult) {
        getInvoker(message, handler, config).ifPresent(h -> {
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
        });
    }

    @SuppressWarnings("unchecked")
    protected Optional<HandlerInvoker> getInvoker(DeserializingMessage message, Handler<DeserializingMessage> handler,
                                                  ConsumerConfiguration config) {
        try {
            return handler.getInvoker(message);
        } catch (Throwable e) {
            try {
                Object retryResult = config.getErrorHandler().handleError(
                        e, format("Failed to check if handler %s is able to handle %s", handler, message),
                        () -> handler.getInvoker(message));
                return retryResult instanceof Optional<?> ? (Optional<HandlerInvoker>) retryResult : Optional.empty();
            } catch (Throwable e2) {
                stopTracker(message, handler, e2);
                return Optional.empty();
            }
        }
    }

    protected Object handle(DeserializingMessage message, HandlerInvoker h, Handler<DeserializingMessage> handler,
                            ConsumerConfiguration config) {
        if (message instanceof ChunkedDeserializingMessage) {
            Fluxzero fluxzero = Fluxzero.getOptionally().orElse(null);
            Tracker tracker = Tracker.current().orElse(null);
            User user = User.getCurrent();
            return trackOutstanding(supplyAsync(
                    () -> withHandlerContext(message, fluxzero, tracker, user,
                                             () -> doHandle(message, h, handler, config)),
                    chunkedMessageExecutor));
        }
        return doHandle(message, h, handler, config);
    }

    @SuppressWarnings("unchecked")
    protected Object doHandle(DeserializingMessage message, HandlerInvoker h, Handler<DeserializingMessage> handler,
                              ConsumerConfiguration config) {
        try {
            Object result = Invocation.performInvocation(h::invoke);
            return result instanceof CompletionStage<?> ? trackOutstanding(((CompletionStage<Object>) result)
                    .exceptionally(e -> message.apply(
                            m -> processError(e, message, h, handler, config)))) : result;
        } catch (Throwable e) {
            return processError(e, message, h, handler, config);
        }
    }

    protected <T> CompletionStage<T> trackOutstanding(CompletionStage<T> stage) {
        var future = stage.toCompletableFuture();
        outstandingRequests.add(future);
        return future.whenComplete((r, e) -> outstandingRequests.remove(future));
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
                () -> Invocation.performInvocation(h::invoke));
    }

    protected void reportResult(Object result, HandlerInvoker h, DeserializingMessage message,
                                ConsumerConfiguration config) {
        if (result instanceof CompletionStage<?> s) {
            s.whenComplete((r, e) -> {
                try {
                    message.run(m -> reportResult(Optional.<Object>ofNullable(e).orElse(r), h, message, config));
                } finally {
                    if (e != null) {
                        close();
                    }
                }
            });
        } else {
            if (shouldSendResponse(h, message, result, config)) {
                if (result instanceof Throwable) {
                    result = unwrapException((Throwable) result);
                    if (!(result instanceof FunctionalException)) {
                        result = new TechnicalException(format("Handler %s failed to handle a %s",
                                                               h.getMethod(), message), (Throwable) result);
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
                .merge(chunkedMessageExecutor::shutdown)
                .cancel();
    }
}
