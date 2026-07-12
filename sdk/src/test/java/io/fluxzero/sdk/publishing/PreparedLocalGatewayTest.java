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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.Trigger;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.sdk.publishing.RecursivePublicationGuard.PUBLICATION_DEPTH_METADATA_KEY;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class PreparedLocalGatewayTest {

    @Test
    void exposesLazyMessageUserInvocationAndCompletionContexts() {
        MockUser user = new MockUser("prepared-local-user");
        ContextHandler handler = new ContextHandler();
        try (Fluxzero fluxzero = createFluxzero(
                DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(user)), handler)) {
            ContextResult result = fluxzero.commandGateway().sendAndWait(new ContextCommand("value"));

            assertEquals("value", result.value());
            assertSame(user, result.user());
            assertEquals("0", result.publicationDepth());
            assertNotNull(result.messageId());
            assertNotNull(result.invocationId());
            assertTrue(handler.invocationCompleted.get());
            assertTrue(handler.batchCompleted.get());
        }
    }

    @Test
    void injectsUserAndMetadataIntoPreparedLocalHandler() {
        MockUser user = new MockUser("injected-user");
        try (Fluxzero fluxzero = createFluxzero(
                DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(user)),
                new ContextParameterHandler())) {
            ContextParameterResult result = fluxzero.commandGateway().sendAndWait(new ContextParameterCommand());

            assertSame(user, result.user());
            assertEquals("0", result.metadata().get(PUBLICATION_DEPTH_METADATA_KEY));
        }
    }

    @Test
    void validatesConstrainedPayloadBeforeInvokingHandler() {
        ValidatingHandler handler = new ValidatingHandler();
        try (Fluxzero fluxzero = createFluxzero(builderWithUser("validation-user"), handler)) {
            assertThrows(ValidationException.class,
                         () -> fluxzero.commandGateway().sendAndWait(new ValidatedCommand(null)));
            assertEquals(0, handler.invocations);
            assertEquals("valid", fluxzero.commandGateway().sendAndWait(new ValidatedCommand("valid")));
            assertEquals(1, handler.invocations);
        }
    }

    @Test
    void invokesHandlerDefinedOnPayloadThroughPreparedRoute() {
        try (Fluxzero fluxzero = createFluxzero(
                builderWithUser("self-handler-user"), new ValueHandler("external"))) {
            SelfHandlingCommand command = new SelfHandlingCommand("result");

            assertEquals("external", fluxzero.commandGateway().sendAndWait(new ValueCommand()));
            assertEquals("result", fluxzero.commandGateway().sendAndWait(command));
            assertEquals("external", fluxzero.commandGateway().sendAndWait(new ValueCommand()));
            assertEquals(1, command.invocations);
        }
    }

    @Test
    void customDispatchInterceptorUsesCanonicalRoute() {
        AtomicInteger interceptorCalls = new AtomicInteger();
        MetadataHandler handler = new MetadataHandler();
        try (Fluxzero fluxzero = createFluxzero(
                builderWithUser("dispatch-user").addDispatchInterceptor((message, messageType, topic) -> {
                    interceptorCalls.incrementAndGet();
                    return message.addMetadata("custom", "intercepted");
                }, COMMAND), handler)) {
            assertEquals("intercepted", fluxzero.commandGateway().sendAndWait(new MetadataCommand()));
            assertEquals(1, interceptorCalls.get());
        }
    }

    @Test
    void customHandlerInterceptorUsesCanonicalRoute() {
        try (Fluxzero fluxzero = createFluxzero(
                builderWithUser("interceptor-user").addHandlerInterceptor(
                        (function, invoker) -> message -> "intercepted-" + function.apply(message), COMMAND),
                new ValueHandler("value"))) {
            assertEquals("intercepted-value", fluxzero.commandGateway().sendAndWait(new ValueCommand()));
        }
    }

    @Test
    void unrelatedDynamicHandlerDoesNotDisablePreparedRegistry() {
        PreparedCountingDispatchInterceptor interceptor = new PreparedCountingDispatchInterceptor();
        try (Fluxzero fluxzero = createFluxzero(
                builderWithUser("mixed-registry-user").addDispatchInterceptor(interceptor, COMMAND),
                new ValueHandler("prepared"), new DynamicContextHandler())) {
            assertEquals("prepared", fluxzero.commandGateway().sendAndWait(new ValueCommand()));
            assertEquals(0, interceptor.canonicalInvocations.get());
        }
    }

    @Test
    void messageBasedUserProviderCanOptOutOfDirectResolution() {
        MockUser dispatchedUser = new MockUser("dispatched");
        MockUser resolvedUser = new MockUser("resolved");
        MessageBasedUserProvider provider = new MessageBasedUserProvider(dispatchedUser, resolvedUser);
        try (Fluxzero fluxzero = createFluxzero(
                DefaultFluxzero.builder().registerUserProvider(provider), new UserHandler())) {
            assertSame(resolvedUser, fluxzero.commandGateway().sendAndWait(new UserCommand()));
            assertTrue(provider.messageResolutions.get() > 0);
        }
    }

    @Test
    void composedUserProviderPreservesMessageBasedResolutionOptOut() {
        UserProvider standard = new FixedUserProvider(new MockUser("standard"));
        UserProvider messageBased = new MessageBasedUserProvider(
                new MockUser("dispatched"), new MockUser("resolved"));

        assertTrue(standard.andThen(messageBased).requiresMessageBasedLocalResolution());
        assertTrue(messageBased.andThen(standard).requiresMessageBasedLocalResolution());
    }

    @Test
    void invalidatesPreparedPlanWhenHandlersChange() {
        try (Fluxzero fluxzero = createFluxzero(builderWithUser("invalidation-user"))) {
            Registration first = fluxzero.commandGateway().registerHandler(new ValueHandler("first"));
            assertEquals("first", fluxzero.commandGateway().sendAndWait(new ValueCommand()));

            first.cancel();
            fluxzero.commandGateway().registerHandler(new ValueHandler("second"));

            assertEquals("second", fluxzero.commandGateway().sendAndWait(new ValueCommand()));
        }
    }

    @Test
    void nestedLocalDispatchPreservesOuterContextAndIncrementsDepth() {
        NestedHandler handler = new NestedHandler();
        try (Fluxzero fluxzero = createFluxzero(builderWithUser("nested-user"), handler)) {
            handler.gateway = fluxzero.commandGateway();

            NestedResult result = fluxzero.commandGateway().sendAndWait(new OuterCommand());

            assertEquals("0", result.outerDepthBefore());
            assertEquals("1", result.innerDepth());
            assertEquals("0", result.outerDepthAfter());
        }
    }

    @Test
    void awaitsAsynchronousLocalResult() {
        MockUser user = new MockUser("async-user");
        try (Fluxzero fluxzero = createFluxzero(
                DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(user)), new AsyncHandler())) {
            assertEquals("async", fluxzero.commandGateway().sendAndWait(new AsyncCommand()));
        }
    }

    @Test
    void appliesRequestTimeoutToAsynchronousLocalResult() {
        MockUser user = new MockUser("timeout-user");
        try (Fluxzero fluxzero = createFluxzero(
                DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(user)), new AsyncHandler())) {
            assertThrows(TimeoutException.class,
                         () -> fluxzero.commandGateway().sendAndWait(new NeverCompletingCommand()));
        }
    }

    private static Fluxzero createFluxzero(FluxzeroBuilder builder, Object... handlers) {
        Fluxzero fluxzero = builder.build(LocalClient.newInstance(null));
        for (Object handler : handlers) {
            fluxzero.commandGateway().registerHandler(handler);
        }
        return fluxzero;
    }

    private static FluxzeroBuilder builderWithUser(String name) {
        return DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(new MockUser(name)));
    }

    private record ContextCommand(String value) {
    }

    private record ContextParameterCommand() {
    }

    private record ContextParameterResult(User user, Metadata metadata) {
    }

    @LocalHandler
    private static class ContextParameterHandler {
        @HandleCommand
        ContextParameterResult handle(ContextParameterCommand ignored, User user, Metadata metadata) {
            return new ContextParameterResult(user, metadata);
        }
    }

    private record ContextResult(String value, User user, String publicationDepth, String messageId,
                                 String invocationId) {
    }

    @LocalHandler
    private static class ContextHandler {
        private final AtomicBoolean invocationCompleted = new AtomicBoolean();
        private final AtomicBoolean batchCompleted = new AtomicBoolean();

        @HandleCommand
        ContextResult handle(ContextCommand command) {
            DeserializingMessage message = DeserializingMessage.getOptionally().orElseThrow();
            Invocation invocation = Invocation.getCurrent();
            assertNotNull(invocation);
            Invocation.whenHandlerCompletes((result, error) -> invocationCompleted.set(error == null));
            DeserializingMessage.whenBatchCompletes(error -> batchCompleted.set(error == null));
            return new ContextResult(command.value(), User.getCurrent(),
                                     message.getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY),
                                     message.getMessageId(), invocation.getId());
        }
    }

    private record ValidatedCommand(@NotNull String value) {
    }

    private static class SelfHandlingCommand {
        private final String result;
        private int invocations;

        private SelfHandlingCommand(String result) {
            this.result = result;
        }

        @HandleCommand
        String handle() {
            invocations++;
            return result;
        }
    }

    @LocalHandler
    private static class ValidatingHandler {
        private int invocations;

        @HandleCommand
        String handle(ValidatedCommand command) {
            invocations++;
            return command.value();
        }
    }

    private record MetadataCommand() {
    }

    @LocalHandler
    private static class MetadataHandler {
        @HandleCommand
        String handle(MetadataCommand ignored) {
            return DeserializingMessage.getCurrent().getMetadata().get("custom");
        }
    }

    private record UserCommand() {
    }

    @LocalHandler
    private static class UserHandler {
        @HandleCommand
        User handle(UserCommand ignored) {
            return User.getCurrent();
        }
    }

    private static class MessageBasedUserProvider extends FixedUserProvider {
        private final User resolvedUser;
        private final AtomicInteger messageResolutions = new AtomicInteger();

        private MessageBasedUserProvider(User dispatchedUser, User resolvedUser) {
            super(dispatchedUser);
            this.resolvedUser = resolvedUser;
        }

        @Override
        public boolean requiresMessageBasedLocalResolution() {
            return true;
        }

        @Override
        public User fromMessage(io.fluxzero.sdk.common.HasMessage message) {
            messageResolutions.incrementAndGet();
            return resolvedUser;
        }
    }

    private record ValueCommand() {
    }

    private record DynamicContextCommand() {
    }

    @LocalHandler
    private static class DynamicContextHandler {
        @HandleCommand
        String handle(@Trigger Object ignoredTrigger, DynamicContextCommand ignoredCommand) {
            return "dynamic";
        }
    }

    private static class PreparedCountingDispatchInterceptor implements DispatchInterceptor {
        private final AtomicInteger canonicalInvocations = new AtomicInteger();

        @Override
        public Message interceptDispatch(Message message, io.fluxzero.common.MessageType messageType, String topic) {
            canonicalInvocations.incrementAndGet();
            return message;
        }

        @Override
        public PreparedLocalDispatch prepareLocalDispatch(LocalDispatchDescriptor descriptor) {
            return PreparedLocalDispatch.noOp;
        }
    }

    @LocalHandler
    private static class ValueHandler {
        private final String value;

        private ValueHandler(String value) {
            this.value = value;
        }

        @HandleCommand
        String handle(ValueCommand ignored) {
            return value;
        }
    }

    private record OuterCommand() {
    }

    private record InnerCommand() {
    }

    private record NestedResult(String outerDepthBefore, String innerDepth, String outerDepthAfter) {
    }

    @LocalHandler
    private static class NestedHandler {
        private CommandGateway gateway;

        @HandleCommand
        NestedResult handle(OuterCommand ignored) {
            String before = currentDepth();
            String inner = gateway.sendAndWait(new InnerCommand());
            return new NestedResult(before, inner, currentDepth());
        }

        @HandleCommand
        String handle(InnerCommand ignored) {
            return currentDepth();
        }

        private static String currentDepth() {
            return DeserializingMessage.getCurrent().getMetadata().get(PUBLICATION_DEPTH_METADATA_KEY);
        }
    }

    private record AsyncCommand() {
    }

    @Timeout(value = 10, timeUnit = MILLISECONDS)
    private record NeverCompletingCommand() {
    }

    @LocalHandler
    private static class AsyncHandler {
        @HandleCommand
        CompletableFuture<String> handle(AsyncCommand ignored) {
            return CompletableFuture.completedFuture("async");
        }

        @HandleCommand
        CompletableFuture<String> handle(NeverCompletingCommand ignored) {
            return new CompletableFuture<>();
        }
    }
}
