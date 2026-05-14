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
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerInterceptor;
import lombok.AllArgsConstructor;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_CLOSE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_MESSAGE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_PONG;
import static io.fluxzero.sdk.web.WebRequest.methodKey;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertAuthorized;
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
    public Function<DeserializingMessage, Object> interceptHandling(Function<DeserializingMessage, Object> function,
                                                                    HandlerInvoker invoker) {
        return m -> {
            User previous = User.getCurrent();
            User user = getCurrentUser(m);
            try {
                User.current.set(user);
                if (m.getType() != null) {
                    assertAuthorized(m.getPayloadClass(), user);
                }
                return function.apply(m);
            } finally {
                User.current.set(previous);
            }
        };
    }

    protected User getCurrentUser(DeserializingMessage m) {
        Optional<RefreshableUser> refreshableUser = m.getContext(RefreshableUser.class);
        if (refreshableUser.isPresent()) {
            return refreshableUser.get().user();
        }
        return userProvider.fromMessage(m);
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
                            return Optional.of(new HandlerInvoker.DelegatingHandlerInvoker(i) {
                                @Override
                                public Object invoke(BiFunction<Object, Object, Object> resultCombiner) {
                                    throw e;
                                }
                            });
                        }
                    });
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
