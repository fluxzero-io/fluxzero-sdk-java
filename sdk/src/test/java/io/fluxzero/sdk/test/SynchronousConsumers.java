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

package io.fluxzero.sdk.test;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.modeling.ModelCommitHandlerRegistry;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.BatchInterceptor;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.DefaultTracking.PreparedHandler;
import io.fluxzero.sdk.tracking.handling.HandleMessage;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Checks consumer admission while retaining the fixture's synchronous handler execution. */
final class SynchronousConsumers {
    private static final Set<Class<?>> describedMessageTypes =
            Set.of(Message.class, WebRequest.class, WebResponse.class, Schedule.class);

    private record Key(MessageType type, ConsumerConfiguration configuration) {}

    private final Map<Key, Group> groups = new HashMap<>();
    private final Map<Object, Map<MessageType, List<Group>>> targets = new IdentityHashMap<>();
    private final AtomicLong revisions = new AtomicLong();
    private Serializer headerSerializer;

    Message remember(SerializedMessage wire, Message message) {
        return new WireInput(message, wire);
    }

    synchronized Registration observe(Fluxzero fluxzero, MessageType type, ConsumerConfiguration configuration,
                                      List<PreparedHandler> handlers) {
        if (headerSerializer == null) {
            headerSerializer = new HeaderSerializer(fluxzero.serializer());
        }
        Key key = new Key(type, configuration);
        Group group = groups.computeIfAbsent(key, ignored -> new Group());
        group.handlers.addAll(handlers);
        handlers.forEach(handler -> targets.computeIfAbsent(handler.target(), ignored -> new HashMap<>())
                .computeIfAbsent(type, ignored -> new ArrayList<>()).add(group));
        if (Boolean.TRUE.equals(configuration.getConsumerFiltering())) {
            if (group.blueprint == null) {
                var interceptors = BatchInterceptor.join(
                        BatchInterceptor.effectiveInterceptors(configuration, type, fluxzero));
                group.blueprint = new ConsumerBlueprint(fluxzero.serializer(),
                        () -> ConsumerFilter.union(group.handlers.stream().map(PreparedHandler::handler)
                                                           .map(Handler::getConsumerFilter).toList()),
                        revisions::incrementAndGet, interceptors::describeConsumerFilter);
            } else {
                group.blueprint.refresh();
            }
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                synchronized (SynchronousConsumers.this) {
                    handlers.forEach(handler -> {
                        Map<MessageType, List<Group>> byType = targets.get(handler.target());
                        List<Group> assigned = byType.get(type);
                        assigned.remove(group);
                        if (assigned.isEmpty()) {
                            byType.remove(type);
                        }
                        if (byType.isEmpty()) {
                            targets.remove(handler.target());
                        }
                    });
                    group.handlers.removeAll(handlers);
                    if (group.handlers.isEmpty()) {
                        groups.remove(key);
                        if (group.blueprint != null) {
                            group.blueprint.close();
                        }
                    } else if (group.blueprint != null) {
                        group.blueprint.refresh();
                    }
                }
            }
        };
    }

    Handler<DeserializingMessage> wrap(Object target, Handler<DeserializingMessage> handler) {
        return new Handler.DelegatingHandler<>(handler) {
            @Override
            public ConsumerFilter getConsumerFilter() {
                return delegate.getConsumerFilter();
            }

            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return delegate.getInvoker(message).filter(invoker -> mayHandle(target, message, invoker));
            }
            // A cached invocation plan keyed only by Java payload class must never bypass this wire-level check.
        };
    }

    private synchronized boolean mayHandle(Object target, DeserializingMessage message, HandlerInvoker invoker) {
        if (ClientUtils.getLocalHandlerAnnotation(invoker).isPresent()
            || invoker.getTargetClass().isAssignableFrom(message.getPayloadClass())
               && !ClientUtils.isSelfTracking(message.getPayloadClass(), invoker.getMethod()) && invoker.getMethodAnnotation() != null
               && invoker.getMethodAnnotation().annotationType().isAnnotationPresent(HandleMessage.class)) {
            return true;
        }
        MessageType type = invoker.getMethodAnnotation() instanceof HandleNotification
                ? MessageType.NOTIFICATION : message.getMessageType();
        Object owner = target == ModelCommitHandlerRegistry.automaticHandlerTarget()
                ? message.getPayloadClass() : target;
        List<Group> assigned = targets.getOrDefault(owner, Map.of()).getOrDefault(type, List.of());
        if (assigned.isEmpty() || assigned.stream().anyMatch(Group::isUnrestricted)) {
            return true;
        }
        Message logical = message.toMessage();
        SerializedMessage wire;
        if (logical instanceof WireInput original) {
            wire = original.wire;
        } else if (describedMessageTypes.contains(logical.getClass())) {
            try {
                logical.getTimestamp().toEpochMilli();
            } catch (ArithmeticException ignored) {
                // Admission ignores timestamps; retain locally valid instants outside the wire's millisecond range.
                logical = logical.withTimestamp(Instant.EPOCH);
            }
            wire = logical.serialize(headerSerializer);
        } else {
            // An unknown envelope can rewrite the header or require a real payload body during serialization.
            return true;
        }
        return assigned.stream().anyMatch(group -> group.blueprint.mayHandle(wire));
    }

    @RequiredArgsConstructor
    private static final class HeaderSerializer implements Serializer {
        @Delegate
        private final Serializer delegate;

        @Override
        public Data<byte[]> serialize(Object object) {
            return serialize(object, null);
        }

        @Override
        public Data<byte[]> serialize(Object object, String format) {
            return delegate.describeSerialization(object, format)
                    .orElseGet(() -> new Data<>(new byte[0], null, 0, format));
        }
    }

    /** The source travels with this logical input, including copies; IDs never identify a source envelope. */
    private static final class WireInput extends Message {
        private final SerializedMessage wire;

        private WireInput(Message message, SerializedMessage wire) {
            super(message.getPayload(), message.getMetadata(), message.getMessageId(), message.getTimestamp());
            this.wire = wire;
        }

        @Override
        public Message withMetadata(Metadata metadata) {
            return new WireInput(super.withMetadata(metadata), wire.withMetadata(metadata));
        }

        @Override
        public Message withMessageId(String id) {
            return new WireInput(super.withMessageId(id), wire);
        }

        @Override
        public Message withTimestamp(Instant timestamp) {
            return new WireInput(super.withTimestamp(timestamp), wire);
        }
    }

    private static final class Group {
        private final List<PreparedHandler> handlers = new CopyOnWriteArrayList<>();
        private ConsumerBlueprint blueprint;

        private boolean isUnrestricted() {
            if (blueprint == null) {
                return true;
            }
            ConsumerFilter filter = blueprint.getFilter();
            return filter.referenceOnly() || filter.version() != ConsumerFilter.VERSION
                   || !filter.payloadRestricted() && !filter.routeRestricted();
        }
    }
}
