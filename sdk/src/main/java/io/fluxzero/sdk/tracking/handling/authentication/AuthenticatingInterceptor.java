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

package io.fluxzero.sdk.tracking.handling.authentication;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInput;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.handling.HandlerMethodApplicability;
import io.fluxzero.common.handling.HandlerMethodPlan;
import io.fluxzero.common.handling.HandlerMethodPreparation;
import io.fluxzero.common.handling.HandlerMethodPlanner;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.publishing.LocalDispatchDescriptor;
import io.fluxzero.sdk.publishing.PreparedLocalDispatch;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import io.fluxzero.sdk.tracking.handling.LocalHandlerInput;
import lombok.AllArgsConstructor;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.ObjectUtils.rethrow;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_CLOSE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_MESSAGE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_PONG;
import static io.fluxzero.sdk.web.WebRequest.methodKey;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertAuthorized;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.requiresAuthorization;
import static java.util.Optional.ofNullable;

@AllArgsConstructor
public class AuthenticatingInterceptor implements DispatchInterceptor, HandlerInterceptor {

    private final UserProvider userProvider;

    @Override
    public Message interceptDispatch(Message m, MessageType messageType, String topic) {
        if (!userProvider.containsUser(m.getMetadata())) {
            User user = getDispatchUser(m, messageType);
            if (user != null) {
                m = m.withMetadata(userProvider.addToMetadata(m.getMetadata(), user));
            }
        }
        return m;
    }

    @Override
    public PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
        if (getClass() != AuthenticatingInterceptor.class
            || descriptor.messageType() == WEBREQUEST
            || userProvider.requiresMessageBasedLocalResolution()
            || userProvider.containsUser(io.fluxzero.common.api.Metadata.empty())) {
            return null;
        }
        return new PreparedLocalDispatch() {
            @Override
            public boolean prepare(io.fluxzero.sdk.tracking.handling.LocalExecution execution) {
                User activeUser = userProvider.getActiveUser();
                User dispatchedUser = activeUser != null ? activeUser : userProvider.getSystemUser();
                execution.setUser(dispatchedUser);
                return dispatchedUser != null;
            }

            @Override
            public io.fluxzero.common.api.Metadata interceptMetadata(
                    io.fluxzero.common.api.Metadata metadata,
                    io.fluxzero.sdk.tracking.handling.LocalExecution execution) {
                User user = execution.getUser(userProvider);
                return user == null ? metadata : userProvider.addToMetadata(metadata, user);
            }
        };
    }

    @Override
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        PreparedHandlerInterceptor prepared = prepare(invoker);
        return prepared.interceptHandling(function, invoker);
    }

    @Override
    public PreparedHandlerInterceptor prepare(HandlerDescriptor handler) {
        return (message, descriptor, combiner, next) -> {
            User previous = User.getCurrent();
            User user = getCurrentUser(message);
            try {
                User.current.set(user);
                if (message.getType() != null) {
                    assertAuthorized(message.getPayloadClass(), user);
                }
                return next.apply(message, descriptor, combiner);
            } finally {
                User.current.set(previous);
            }
        };
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(HandlerDescriptor handler) {
        return prepareInput(handler, null);
    }

    @Override
    public PreparedHandlerInputInterceptor prepareInput(
            HandlerDescriptor handler, HandlerInput<DeserializingMessage> preparedInput) {
        if (userProvider.requiresMessageBasedLocalResolution()) {
            return null;
        }
        Object preparedPayload = preparedInput == null ? null : preparedInput.getPayload();
        boolean authorizePayload = preparedInput == null
                || preparedPayload != null && requiresAuthorization(preparedPayload.getClass());
        return (input, descriptor, next) -> {
            if (!(input instanceof LocalHandlerInput localInput) || !localInput.hasResolvedUser()) {
                return prepare(descriptor).interceptHandling(
                        input.getMessage(), descriptor, (first, second) -> first,
                        (current, nextMessage, ignored, combiner) -> next.apply(input, descriptor));
            }
            User previous = User.getCurrent();
            User user = localInput.getUser(userProvider);
            try {
                User.current.set(user);
                Object payload = input.getPayload();
                if (authorizePayload && payload != null) {
                    assertAuthorized(payload.getClass(), user);
                }
                return next.apply(input, descriptor);
            } finally {
                User.current.set(previous);
            }
        };
    }

    @Override
    public boolean supportsPreparation() {
        return true;
    }

    protected User getCurrentUser(DeserializingMessage m) {
        return getAuthorizingUser(m);
    }

    protected User getDispatchUser(Message m, MessageType messageType) {
        Optional<DeserializingMessage> currentMessage = ofNullable(DeserializingMessage.getCurrent());
        Optional<RefreshableUser> refreshableUser =
                currentMessage.flatMap(message -> message.getContext(RefreshableUser.class));
        if (refreshableUser.isPresent()) {
            return refreshableUser.get().user();
        }
        if (isEstablishedWebsocketFrame(m, messageType)) {
            return null;
        }
        if (currentMessage.isPresent()) {
            return currentMessage.get().getMessageType() == WEBREQUEST
                    ? userProvider.getActiveUser() : userProvider.getSystemUser();
        }
        User activeUser = userProvider.getActiveUser();
        return activeUser != null || messageType == WEBREQUEST ? activeUser : userProvider.getSystemUser();
    }

    protected boolean isEstablishedWebsocketFrame(Message m, MessageType messageType) {
        if (messageType != WEBREQUEST) {
            return false;
        }
        return switch (m.getMetadata().get(methodKey)) {
            case WS_MESSAGE, WS_PONG, WS_CLOSE -> true;
            case null, default -> false;
        };
    }

    protected User getAuthorizingUser(DeserializingMessage m) {
        Optional<RefreshableUser> refreshableUser = m.getContext(RefreshableUser.class);
        if (refreshableUser.isPresent()) {
            return refreshUser(refreshableUser.get(), m);
        }
        User user;
        try {
            user = userProvider.fromMessage(m);
        } catch (Throwable ignored) {
            user = null;
        }
        if (user == null) {
            user = userProvider.getActiveUser();
        }
        return user;
    }

    protected User refreshUser(RefreshableUser refreshableUser, DeserializingMessage m) {
        Optional<RefreshedUser> refreshedUser = m.getContext(RefreshedUser.class);
        if (refreshedUser.isPresent()) {
            return refreshedUser.get().user();
        }
        User user = refreshableUser.user();
        if (userProvider instanceof RefreshingUserProvider<?> refreshingUserProvider) {
            user = refreshUser(refreshingUserProvider, user, m);
            refreshableUser.update(user);
        }
        m.putContext(RefreshedUser.class, new RefreshedUser(user));
        return user;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private User refreshUser(RefreshingUserProvider refreshingUserProvider, User user, DeserializingMessage m) {
        return (User) refreshingUserProvider.refreshUser(user, m);
    }

    private record RefreshedUser(User user) {
    }

    @Override
    public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
        return new AuthorizingHandler(HandlerInterceptor.super.wrap(handler));
    }

    @AllArgsConstructor
    private class AuthorizingHandler implements Handler<DeserializingMessage> {
        private final Handler<DeserializingMessage> delegate;

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage m) {
            return delegate.getInvoker(m).flatMap(
                    i -> {
                        if (Optional.ofNullable(m.getPayloadClass())
                                .map(c -> i.getTargetClass().isAssignableFrom(c)).orElse(false)) {
                            return Optional.of(i);
                        }
                        User user = getAuthorizingUser(m);
                        try {
                            return assertAuthorized(i.getTargetClass(), i.getMethod(), user)
                                    ? Optional.of(i) : Optional.empty();
                        } catch (Throwable e) {
                            Throwable error = mapAuthorizationFailure(e, m);
                            return Optional.of(new HandlerInvoker.DelegatingHandlerInvoker(i) {
                                @Override
                                public Object invoke(BiFunction<Object, Object, Object> resultCombiner) {
                                    throw rethrow(error);
                                }
                            });
                        }
                    });
        }

        @Override
        public HandlerMethodPlanner<DeserializingMessage> getHandlerMethodPlanner() {
            HandlerMethodPlanner<DeserializingMessage> planner = delegate.getHandlerMethodPlanner();
            if (planner == null) {
                return null;
            }
            return new HandlerMethodPlanner<>() {
                @Override
                public Object getCacheKey(DeserializingMessage message) {
                    return planner.getCacheKey(message);
                }

                @Override
                public Object getCacheKey(HandlerInput<DeserializingMessage> input) {
                    return planner.getCacheKey(input);
                }

                @Override
                public HandlerMethodPreparation<DeserializingMessage> prepare(DeserializingMessage message) {
                    return authorize(planner.prepare(message), message.getPayloadClass());
                }

                @Override
                public HandlerMethodPreparation<DeserializingMessage> prepare(
                        HandlerInput<DeserializingMessage> input) {
                    Object payload = input.getPayload();
                    return authorize(planner.prepare(input), payload == null ? null : payload.getClass());
                }

                @Override
                public HandlerMethodApplicability<DeserializingMessage> prepareApplicability(
                        HandlerInput<DeserializingMessage> input) {
                    HandlerMethodApplicability<DeserializingMessage> source = planner.prepareApplicability(input);
                    if (!source.isCacheable()) {
                        return HandlerMethodApplicability.unsupported();
                    }
                    Object payload = input.getPayload();
                    return source.withPreparation(authorize(
                            source.preparation(), payload == null ? null : payload.getClass()));
                }

                private HandlerMethodPreparation<DeserializingMessage> authorize(
                        HandlerMethodPreparation<DeserializingMessage> preparation, Class<?> payloadClass) {
                    if (!preparation.isPrepared()) {
                        return preparation;
                    }
                    HandlerMethodPlan<DeserializingMessage> plan = preparation.plan();
                    if (payloadClass != null && plan.getTargetClass().isAssignableFrom(payloadClass)) {
                        return preparation;
                    }
                    try {
                        return assertAuthorized(plan.getTargetClass(), plan.getMethod(), null)
                                ? preparation : HandlerMethodPreparation.noMatch();
                    } catch (Throwable ignored) {
                        return HandlerMethodPreparation.unsupported();
                    }
                }

                @Override
                public boolean isPayloadClassKey(HandlerInput<DeserializingMessage> input) {
                    return planner.isPayloadClassKey(input);
                }

                @Override
                public boolean isNoMatchPayloadClassKey(HandlerInput<DeserializingMessage> input) {
                    return planner.isNoMatchPayloadClassKey(input);
                }
            };
        }

        protected Throwable mapAuthorizationFailure(Throwable failure, DeserializingMessage message) {
            return message.getContext(AuthorizationFailureMapper.class)
                    .map(mapper -> mapper.mapAuthorizationFailure(failure))
                    .orElse(failure);
        }

        @Override
        public Class<?> getTargetClass() {
            return delegate.getTargetClass();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
