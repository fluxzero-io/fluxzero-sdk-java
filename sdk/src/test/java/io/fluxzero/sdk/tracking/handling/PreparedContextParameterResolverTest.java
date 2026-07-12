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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerMethodPlan;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserParameterResolver;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class PreparedContextParameterResolverTest {

    @Test
    void injectsUserAndMetadataWithoutMaterializingMessage() {
        User user = new MockUser("prepared-context");
        UserProvider userProvider = new FixedUserProvider(user);
        Metadata metadata = Metadata.of("key", "value");
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new ContextHandler(), HandleCommand.class,
                List.of(new UserParameterResolver(userProvider), new MetadataParameterResolver(),
                        new PayloadParameterResolver()));
        LazyInput input = new LazyInput(new ContextCommand(), user, metadata);

        var applicability = handler.getHandlerMethodPlanner().prepareApplicability(input);
        ContextResult result = (ContextResult) applicability.preparation().plan().invoke(input);

        assertSame(user, result.user());
        assertSame(metadata, result.metadata());
    }

    private record ContextCommand() {
    }

    private record ContextResult(User user, Metadata metadata) {
    }

    private static class ContextHandler {
        @HandleCommand
        ContextResult handle(ContextCommand ignored, User user, Metadata metadata) {
            return new ContextResult(user, metadata);
        }
    }

    private record LazyInput(Object payload, User user, Metadata metadata) implements LocalHandlerInput {
        @Override
        public Object getPayload() {
            return payload;
        }

        @Override
        public DeserializingMessage getMessage() {
            throw new AssertionError("User and metadata injection should not materialize the message");
        }

        @Override
        public DeserializingMessage getMessageIfAvailable() {
            return null;
        }

        @Override
        public MessageType getMessageType() {
            return MessageType.COMMAND;
        }

        @Override
        public User getUser(UserProvider provider) {
            return user;
        }

        @Override
        public boolean hasResolvedUser() {
            return true;
        }

        @Override
        public Metadata getMetadata() {
            return metadata;
        }

        @Override
        public boolean containsMetadata(String key) {
            return metadata.containsKey(key);
        }

        @Override
        public Long getIndex() {
            return null;
        }

        @Override
        public Object invoke(HandlerMethodPlan<DeserializingMessage> plan) {
            return plan.invoke(this);
        }
    }
}
