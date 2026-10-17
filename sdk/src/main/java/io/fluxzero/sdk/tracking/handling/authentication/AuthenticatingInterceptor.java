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
import io.fluxzero.sdk.tracking.handling.LocalExecution;
import lombok.AllArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.common.ObjectUtils.rethrow;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_CLOSE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_MESSAGE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_PONG;
import static io.fluxzero.sdk.web.WebRequest.methodKey;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.assertAuthorized;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.canIgnoreSilently;
import static io.fluxzero.sdk.tracking.handling.validation.ValidationUtils.ignoreSilently;
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
            SkippableInvoker skippable = descriptor instanceof SkippableInvoker i ? i : null;
            if (skippable != null) {
                skippable.skipped = false;
            }
            User previous = User.getCurrent();
            try {
                User user = getCurrentUser(message);
                User.current.set(user);
                if (message.getType() != null && !assertAuthorized(message.getPayloadClass(), user)) {
                    if (skippable != null) {
                        skippable.skipped = true;
                    }
                    return null;
                }
                return next.apply(message, descriptor, combiner);
            } finally {
                if (skippable != null) {
                    skippable.completeSkipDecision();
                }
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
                if (authorizePayload && payload != null
                    && !assertAuthorized(payload.getClass(), user)) {
                    if (input instanceof LocalExecution execution) {
                        execution.markSkipped();
                    }
                    return null;
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
        if (!usesDefaultInterception()) {
            // Custom interception owns its completion semantics; retain its existing wrapping contract.
            return new AuthorizingHandler(HandlerInterceptor.super.wrap(handler));
        }
        Handler<DeserializingMessage> skippable = new Handler.DelegatingHandler<>(handler) {
            @Override
            public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                return Optional.ofNullable(getInvokerOrNull(message));
            }

            @Override
            public HandlerInvoker getInvokerOrNull(DeserializingMessage message) {
                HandlerInvoker invoker = delegate.getInvokerOrNull(message);
                Class<?> payloadClass = message.getPayloadClass();
                return invoker != null && payloadClass != null && canIgnoreSilently(payloadClass)
                        ? new SkippableInvoker(invoker) : invoker;
            }

            @Override
            public HandlerMethodPlanner<DeserializingMessage> getHandlerMethodPlanner() {
                return delegate.getHandlerMethodPlanner();
            }

            @Override
            public io.fluxzero.common.api.tracking.ConsumerFilter getConsumerFilter() {
                return delegate.getConsumerFilter();
            }
        };
        return new AuthorizingHandler(HandlerInterceptor.super.wrap(skippable));
    }

    private boolean usesDefaultInterception() {
        return usesDefaultInterception("prepare", HandlerDescriptor.class)
               && usesDefaultInterception("interceptHandling", Function.class, HandlerInvoker.class);
    }

    private boolean usesDefaultInterception(String name, Class<?>... parameterTypes) {
        return io.fluxzero.common.reflection.ReflectionUtils.getAllMethods(getClass()).stream()
                .filter(m -> m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), parameterTypes)).findFirst()
                .filter(m -> m.getDeclaringClass() == AuthenticatingInterceptor.class).isPresent();
    }

    private static class SkippableInvoker extends HandlerInvoker.DelegatingHandlerInvoker {
        private volatile boolean skipped;
        private volatile boolean skipPending = true;
        private CompletableFuture<Void> skipDecision;

        private SkippableInvoker(HandlerInvoker delegate) {
            super(delegate);
        }

        @Override
        public Object invoke(BiFunction<Object, Object, Object> combiner) {
            return delegate.invoke(combiner);
        }

        @Override
        public boolean maySkip() {
            return true;
        }

        @Override
        public boolean isSkipPending() {
            return skipPending || delegate.isSkipPending();
        }

        @Override
        public void onSkipDecision(Runnable callback) {
            CompletableFuture<Void> decision;
            synchronized (this) {
                if (skipPending && skipDecision == null) {
                    skipDecision = new CompletableFuture<>();
                }
                decision = skipDecision;
            }
            if (decision == null) {
                delegate.onSkipDecision(callback);
            } else {
                decision.thenRun(() -> delegate.onSkipDecision(callback));
            }
        }

        private void completeSkipDecision() {
            CompletableFuture<Void> decision;
            synchronized (this) {
                skipPending = false;
                decision = skipDecision;
            }
            if (decision != null) {
                decision.complete(null);
            }
        }

        @Override
        public boolean wasSkipped() {
            return skipped || delegate.wasSkipped();
        }
    }

    @AllArgsConstructor
    private class AuthorizingHandler implements Handler<DeserializingMessage> {
        private final Handler<DeserializingMessage> delegate;

        @Override
        public io.fluxzero.common.api.tracking.ConsumerFilter getConsumerFilter() {
            return delegate.getConsumerFilter();
        }

        @Override
        public Optional<HandlerInvoker> getInvoker(DeserializingMessage m) {
            return delegate.getInvoker(m).flatMap(
                    i -> {
                        Class<?> payloadClass = m.getPayloadClass();
                        boolean silentPayload = payloadClass != null && canIgnoreSilently(payloadClass);
                        User user = silentPayload ? getCurrentUser(m) : null;
                        if (silentPayload && ignoreSilently(payloadClass, user)) {
                            return Optional.empty();
                        }
                        if (Optional.ofNullable(payloadClass)
                                .map(c -> i.getTargetClass().isAssignableFrom(c)).orElse(false)) {
                            return Optional.of(i);
                        }
                        if (!silentPayload || AuthenticatingInterceptor.this.getClass() != AuthenticatingInterceptor.class) {
                            user = getAuthorizingUser(m);
                        }
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
                    return authorize(planner.prepare(message), message.getPayloadClass(), false);
                }

                @Override
                public HandlerMethodPreparation<DeserializingMessage> prepare(
                        HandlerInput<DeserializingMessage> input) {
                    Object payload = input.getPayload();
                    return authorize(planner.prepare(input), payload == null ? null : payload.getClass(),
                                     input instanceof LocalExecution);
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
                            source.preparation(), payload == null ? null : payload.getClass(),
                            input instanceof LocalExecution));
                }

                private HandlerMethodPreparation<DeserializingMessage> authorize(
                        HandlerMethodPreparation<DeserializingMessage> preparation, Class<?> payloadClass,
                        boolean localExecution) {
                    if (!preparation.isPrepared()) {
                        return preparation;
                    }
                    HandlerMethodPlan<DeserializingMessage> plan = preparation.plan();
                    if (!(payloadClass != null && plan.getTargetClass().isAssignableFrom(payloadClass))
                        && requiresAuthorization(plan.getTargetClass(), plan.getMethod())) {
                        return HandlerMethodPreparation.unsupported();
                    }
                    if (payloadClass == null || !canIgnoreSilently(payloadClass)) {
                        return preparation;
                    }
                    return localExecution ? HandlerMethodPreparation.prepared(guardPayload(plan, payloadClass))
                            : HandlerMethodPreparation.unsupported();
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

        private HandlerMethodPlan<DeserializingMessage> guardPayload(
                HandlerMethodPlan<DeserializingMessage> plan, Class<?> payloadClass) {
            return new HandlerMethodPlan<>() {
                @Override
                public boolean isApplicable(HandlerInput<DeserializingMessage> input) {
                    if (!(input instanceof LocalExecution execution) || !plan.isApplicable(input)) {
                        return false;
                    }
                    try {
                        if (!assertAuthorized(payloadClass, execution.getUser(userProvider))) {
                            return false;
                        }
                    } catch (Throwable ignored) {
                        // Preserve ordinary invocation-time reporting for throwing authorization failures.
                    }
                    return true;
                }

                @Override
                public Object invoke(HandlerInput<DeserializingMessage> input) {
                    return plan.invoke(input);
                }

                @Override
                public Class<?> getTargetClass() {
                    return plan.getTargetClass();
                }

                @Override
                public Executable getMethod() {
                    return plan.getMethod();
                }

                @Override
                public <A extends Annotation> A getMethodAnnotation() {
                    return plan.getMethodAnnotation();
                }

                @Override
                public boolean expectResult() {
                    return plan.expectResult();
                }

                @Override
                public boolean isPassive() {
                    return plan.isPassive();
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
