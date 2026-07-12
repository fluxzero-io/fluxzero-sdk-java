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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMethodApplicability;
import io.fluxzero.common.handling.HandlerMethodPlan;
import io.fluxzero.common.handling.HandlerMethodPreparation;
import io.fluxzero.common.handling.HandlerMethodPlanner;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.TrackSelf;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.sdk.common.ClientUtils.getLocalHandlerAnnotation;
import static java.util.Collections.emptyList;

/**
 * In-memory implementation of {@link HandlerRegistry} that manages and dispatches local message handlers — i.e.,
 * handlers that are invoked directly in the publishing thread without involving the Fluxzero Runtime.
 * <p>
 * The {@code LocalHandlerRegistry} only registers and invokes handlers that meet the criteria for local handling. These
 * include:
 * <ul>
 *     <li>Methods explicitly annotated with {@link LocalHandler}</li>
 *     <li>Handlers defined inside a message payload class (e.g., query or command) <b>not</b> annotated with {@link TrackSelf}</li>
 * </ul>
 * <p>
 * This mechanism is useful for bypassing asynchronous tracking and Fluxzero Runtime involvement when
 * immediate, in-process execution is preferred — such as for fast local queries, synchronous command handlers,
 * or test scenarios.
 *
 * <h2>Self-Handlers</h2>
 * <p>
 * If a message's payload type defines a handler method (e.g., {@code @HandleQuery}) and is not marked
 * with {@link TrackSelf}, then that handler is considered a "self-handler" and is treated as local. These handlers are
 * lazily constructed by the {@link HandlerFactory} and automatically included during message dispatch.
 *
 * <h2>Fallback to Fluxzero Runtime</h2>
 * If no local handlers are found for a given message, it will not be processed in the publishing thread. Instead:
 * <ul>
 *     <li>The message will be published to the Fluxzero Runtime using the appropriate gateway</li>
 *     <li>It will be logged so that remote trackers or consumers can handle it asynchronously</li>
 * </ul>
 * <p>
 * This ensures consistent delivery semantics while giving applications control over what is handled locally.
 *
 * <h2>Thread Safety</h2>
 * Registered handlers are stored in a {@link java.util.concurrent.CopyOnWriteArrayList}, making the registry
 * safe for concurrent usage and dynamic handler registration.
 *
 * @see LocalHandler
 * @see TrackSelf
 * @see HandlerRegistry
 * @see HasLocalHandlers
 * @see HandlerFactory
 */
@RequiredArgsConstructor
@Slf4j
public class LocalHandlerRegistry implements HandlerRegistry {
    private static final int maxPlanCacheSize = 256;
    @Getter
    private final HandlerFactory handlerFactory;
    private final DispatchInterceptor dispatchInterceptor;
    @Getter
    private final List<Handler<DeserializingMessage>> localHandlers = new CopyOnWriteArrayList<>();
    private final List<Class<?>> registeredClassHandlers = new CopyOnWriteArrayList<>();

    @Getter
    @NonNull
    private HandlerFilter selfHandlerFilter = (t, m) -> !ClientUtils.isSelfTracking(t, m);
    private final AtomicLong handlerVersion = new AtomicLong();
    private final ConcurrentHashMap<Object, PreparedLocalPlan> preparedPlans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, RawPlanCache> rawPlans = new ConcurrentHashMap<>();
    private volatile CachedPlan lastPreparedPlan;
    private volatile RawPlanCache rawPlanCache;

    private final Function<Class<?>, Optional<Handler<DeserializingMessage>>> selfHandlers
            = memoize(payloadType -> getHandlerFactory().createHandler(payloadType, getSelfHandlerFilter(), List.of()));

    @Override
    public boolean hasLocalHandlers() {
        return !localHandlers.isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
        if (handlerFactory instanceof DefaultHandlerFactory defaultHandlerFactory) {
            defaultHandlerFactory.setRegisteredHandlerTypePredicate(registeredClassHandlers::contains);
        }
        if (target instanceof Handler<?>) {
            localHandlers.add((Handler<DeserializingMessage>) target);
            invalidatePlans();
            return () -> {
                localHandlers.remove(target);
                invalidatePlans();
            };
        }
        Optional<Handler<DeserializingMessage>> handler = handlerFactory.createHandler(
                target, handlerFilter, emptyList());
        handler.ifPresent(h -> {
            localHandlers.add(h);
            if (target instanceof Class<?> targetClass) {
                registeredClassHandlers.add(targetClass);
            }
            invalidatePlans();
        });
        return () -> handler.ifPresent(h -> {
            localHandlers.remove(h);
            if (target instanceof Class<?> targetClass) {
                registeredClassHandlers.remove(targetClass);
            }
            invalidatePlans();
        });
    }

    public void setSelfHandlerFilter(@NonNull HandlerFilter selfHandlerFilter) {
        this.selfHandlerFilter = selfHandlerFilter;
        invalidatePlans();
    }

    private void invalidatePlans() {
        handlerVersion.incrementAndGet();
        preparedPlans.clear();
        rawPlans.clear();
        lastPreparedPlan = null;
        rawPlanCache = null;
    }

    @Override
    public LocalHandlerResult handleResult(DeserializingMessage message) {
        MessageHandlerInput input = new MessageHandlerInput(message);
        return handleResult(input);
    }

    @Override
    public LocalHandlerResult handleResult(LocalHandlerInput input) {
        Class<?> payloadClass = payloadClass(input.getPayload());
        long version = handlerVersion.get();
        PreparedLocalPlan rawPlan = input.getMessageIfAvailable() == null
                ? getRawPlan(payloadClass, version) : null;
        PreparedLocalPlan prepared = rawPlan != null ? rawPlan
                : getPreparedPlan(input, getLocalHandlers(input.getMessageType(), payloadClass), version,
                                  payloadClass);
        if (prepared == PreparedLocalPlan.unsupported) {
            return handle(input.getMessage()).map(LocalHandlerResult::asynchronous)
                    .orElseGet(LocalHandlerResult::notHandled);
        }
        if (prepared == PreparedLocalPlan.noMatch) {
            return LocalHandlerResult.notHandled();
        }
        try {
            Object result = input.invoke(prepared.method());
            if (result instanceof Optional<?> optional) {
                result = optional.orElse(null);
            }
            return result instanceof CompletableFuture<?> future
                    ? LocalHandlerResult.asynchronous(future) : LocalHandlerResult.completed(result);
        } catch (Throwable e) {
            return LocalHandlerResult.failed(e);
        }
    }

    @Override
    public boolean handleLocal(LocalExecution execution) {
        Class<?> payloadClass = payloadClass(execution.getPayload());
        long version = handlerVersion.get();
        PreparedLocalPlan rawPlan = execution.getMessageIfAvailable() == null
                ? getRawPlan(payloadClass, version) : null;
        PreparedLocalPlan prepared = rawPlan != null ? rawPlan
                : getPreparedPlan(execution, getLocalHandlers(execution.getMessageType(), payloadClass), version,
                                  payloadClass);
        if (prepared == PreparedLocalPlan.unsupported || prepared == PreparedLocalPlan.noMatch) {
            return false;
        }
        try {
            Object result = execution.invoke(prepared.method());
            if (result instanceof Optional<?> optional) {
                result = optional.orElse(null);
            }
            if (result instanceof CompletableFuture<?> future) {
                execution.completeAsync(future);
            } else {
                execution.complete(result);
            }
        } catch (Throwable e) {
            execution.completeExceptionally(e);
        }
        return true;
    }

    private static Class<?> payloadClass(Object payload) {
        return payload == null ? Void.class : payload.getClass();
    }

    private PreparedLocalPlan getRawPlan(Class<?> payloadClass, long version) {
        RawPlanCache cached = rawPlanCache;
        if (cached != null && cached.version() == version && cached.payloadClass() == payloadClass) {
            return cached.plan();
        }
        cached = rawPlans.get(payloadClass);
        if (cached != null && cached.version() == version) {
            rawPlanCache = cached;
            return cached.plan();
        }
        return null;
    }

    private PreparedLocalPlan getPreparedPlan(
            LocalHandlerInput input, List<Handler<DeserializingMessage>> handlers, long version,
            Class<?> payloadClass) {
        Object key = new PlanVersion(version);
        List<HandlerMethodPreparation<DeserializingMessage>> preparations =
                new java.util.ArrayList<>(handlers.size());
        boolean payloadClassKey = input.getMessageIfAvailable() == null;
        for (Handler<DeserializingMessage> handler : handlers) {
            HandlerMethodPlanner<DeserializingMessage> planner = handler.getHandlerMethodPlanner();
            if (planner == null) {
                return PreparedLocalPlan.unsupported;
            }
            HandlerMethodApplicability<DeserializingMessage> applicability = planner.prepareApplicability(input);
            if (!applicability.isCacheable()) {
                return PreparedLocalPlan.unsupported;
            }
            key = new CombinedPlanKey(key, applicability.cacheKey());
            preparations.add(applicability.preparation());
            payloadClassKey &= applicability.payloadClassKey();
        }
        CachedPlan cached = lastPreparedPlan;
        if (cached != null && cached.key().equals(key)) {
            if (payloadClassKey) {
                cacheRawPlan(version, payloadClass, cached.plan());
            }
            return cached.plan();
        }
        PreparedLocalPlan result = preparedPlans.get(key);
        if (result == null) {
            result = prepareLocalPlan(preparations);
            if (preparedPlans.size() < maxPlanCacheSize) {
                PreparedLocalPlan existing = preparedPlans.putIfAbsent(key, result);
                if (existing != null) {
                    result = existing;
                }
            }
        }
        lastPreparedPlan = new CachedPlan(key, result);
        if (payloadClassKey) {
            cacheRawPlan(version, payloadClass, result);
        }
        return result;
    }

    private void cacheRawPlan(long version, Class<?> payloadClass, PreparedLocalPlan plan) {
        RawPlanCache cached = new RawPlanCache(version, payloadClass, plan);
        rawPlanCache = cached;
        if (rawPlans.size() < maxPlanCacheSize || rawPlans.containsKey(payloadClass)) {
            rawPlans.compute(payloadClass, (ignored, existing) ->
                    existing == null || existing.version() <= version ? cached : existing);
        }
    }

    private PreparedLocalPlan prepareLocalPlan(
            List<HandlerMethodPreparation<DeserializingMessage>> preparations) {
        HandlerMethodPlan<DeserializingMessage> selected = null;
        for (HandlerMethodPreparation<DeserializingMessage> preparation : preparations) {
            if (preparation.isUnsupported()) {
                return PreparedLocalPlan.unsupported;
            }
            if (preparation.isPrepared()) {
                HandlerMethodPlan<DeserializingMessage> candidate = preparation.plan();
                if (candidate.isPassive() || selected != null || logMessage(candidate)) {
                    return PreparedLocalPlan.unsupported;
                }
                selected = candidate;
            }
        }
        return selected == null ? PreparedLocalPlan.noMatch : new PreparedLocalPlan(selected);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Optional<CompletableFuture<Object>> handle(DeserializingMessage message) {
        List<Handler<DeserializingMessage>> localHandlers = getLocalHandlers(message);
        if (localHandlers.isEmpty()) {
            return Optional.empty();
        }
        return message.apply(m -> {
            boolean handled = false;
            boolean logMessage = false;
            boolean request = m.getMessageType().isRequest();
            CompletableFuture<Object> future = new CompletableFuture<>();
            for (Handler<DeserializingMessage> handler : localHandlers) {
                var optionalInvoker = handler.getInvoker(m);
                if (optionalInvoker.isPresent()) {
                    var invoker = optionalInvoker.get();
                    boolean passive = invoker.isPassive();
                    if (!handled || !request || passive) {
                        try {
                            Object result = Invocation.performInvocation(invoker, invoker::invoke);
                            if (result instanceof Optional<?> optional) {
                                result = optional.orElse(null);
                            }
                            if (!passive && !future.isDone()) {
                                if (result instanceof CompletableFuture<?>) {
                                    future = ((CompletableFuture<Object>) result);
                                } else {
                                    future.complete(result);
                                }
                            }
                        } catch (Throwable e) {
                            if (passive) {
                                log.error("Passive handler {} failed to handle a {}", invoker, m.getPayloadClass(),
                                          e);
                            } else {
                                future.completeExceptionally(e);
                            }
                        } finally {
                            if (!passive) {
                                handled = true;
                            }
                            logMessage = logMessage || logMessage(invoker);
                        }
                    }
                }
            }
            try {
                return handled ? Optional.of(future) : Optional.empty();
            } finally {
                if (handled && logMessage) {
                    Fluxzero.getOptionally().ifPresent(fc -> {
                        SerializedMessage serializedMessage = message.getSerializedObject();
                        serializedMessage = dispatchInterceptor.modifySerializedMessage(
                                serializedMessage, m.toMessage(), m.getMessageType(), m.getTopic());
                        if (serializedMessage != null) {
                            fc.client().getGatewayClient(m.getMessageType(), m.getTopic())
                                    .append(Guarantee.NONE, serializedMessage);
                        }
                    });
                }
            }
        });
    }

    @Override
    public boolean canHandle(DeserializingMessage message) {
        return message.apply(m -> getLocalHandlers(m).stream().anyMatch(handler -> handler.getInvoker(m).isPresent()));
    }

    /**
     * Returns the full list of handlers that should be used to process the given message.
     * <p>
     * This may include a self-handler if the message is a request or schedule type.
     */
    protected List<Handler<DeserializingMessage>> getLocalHandlers(DeserializingMessage message) {
        if (!message.getMessageType().isRequest() && message.getMessageType() != MessageType.SCHEDULE) {
            return localHandlers;
        }
        return getLocalHandlers(message.getMessageType(), message.getPayloadClass());
    }

    private List<Handler<DeserializingMessage>> getLocalHandlers(MessageType messageType, Class<?> payloadClass) {
        if (!messageType.isRequest() && messageType != MessageType.SCHEDULE) {
            return localHandlers;
        }
        return selfHandlers.apply(payloadClass)
                .filter(ignored -> registeredClassHandlers.stream()
                        .noneMatch(handlerClass -> handlerClass.isAssignableFrom(payloadClass)))
                .map(h -> Stream.concat(localHandlers.stream(), Stream.of(h)).toList()).orElse(localHandlers);
    }

    /**
     * Determines whether a handler allows its message to be sent to the Fluxzero Runtime.
     */
    protected boolean logMessage(HandlerDescriptor invoker) {
        return getLocalHandlerAnnotation(invoker.getTargetClass(), invoker.getMethod())
                .map(LocalHandler::logMessage).orElse(false);
    }

    private record MessageHandlerInput(DeserializingMessage message) implements LocalHandlerInput {
        @Override
        public Object getPayload() {
            return message.getPayload();
        }

        @Override
        public DeserializingMessage getMessage() {
            return message;
        }

        @Override
        public MessageType getMessageType() {
            return message.getMessageType();
        }

        @Override
        public io.fluxzero.sdk.tracking.handling.authentication.User getUser(
                io.fluxzero.sdk.tracking.handling.authentication.UserProvider provider) {
            throw new UnsupportedOperationException("No dispatch user was captured");
        }

        @Override
        public boolean hasResolvedUser() {
            return false;
        }

        @Override
        public io.fluxzero.common.api.Metadata getMetadata() {
            return message.getMetadata();
        }

        @Override
        public boolean containsMetadata(String key) {
            return message.getMetadata().containsKey(key);
        }

        @Override
        public Long getIndex() {
            return message.getIndex();
        }

        @Override
        public Object invoke(HandlerMethodPlan<DeserializingMessage> plan) {
            return message.apply(m -> Invocation.performInvocation(plan, () -> plan.invoke(this)));
        }
    }

    private record PreparedLocalPlan(HandlerMethodPlan<DeserializingMessage> method) {
        private static final PreparedLocalPlan unsupported = new PreparedLocalPlan(null);
        private static final PreparedLocalPlan noMatch = new PreparedLocalPlan(null);
    }

    private record CachedPlan(Object key, PreparedLocalPlan plan) {
    }

    private record PlanVersion(long value) {
    }

    private record CombinedPlanKey(Object parent, Object handler) {
    }

    private record RawPlanCache(long version, Class<?> payloadClass, PreparedLocalPlan plan) {
    }
}
