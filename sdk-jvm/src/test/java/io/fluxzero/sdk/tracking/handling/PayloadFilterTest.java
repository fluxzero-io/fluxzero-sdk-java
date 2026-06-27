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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadFilterTest {

    @Test
    void filtersAllowedClassesThroughMetadataIntrospection() throws Exception {
        Executable executable = Handler.class.getDeclaredMethod("handle");
        PayloadFilter filter = new PayloadFilter();

        MessageFilter<? super HasMessage> prepared =
                filter.prepare(executable, HandleCommand.class, Handler.class);

        assertTrue(prepared.test(new TestMessage(SpecialCommand.class), executable, HandleCommand.class,
                                 Handler.class));
        assertFalse(prepared.test(new TestMessage(OtherCommand.class), executable, HandleCommand.class,
                                  Handler.class));
        assertEquals(Command.class, filter.getLeastSpecificAllowedClass(
                executable, HandleCommand.class).orElseThrow());
    }

    static class Handler {
        @HandleCommand(allowedClasses = Command.class)
        void handle() {
        }
    }

    static class Command {
    }

    static class SpecialCommand extends Command {
    }

    static class OtherCommand {
    }

    private record TestMessage(Class<?> payloadClass) implements HasMessage {
        @Override
        public Message toMessage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<?> getPayloadClass() {
            return payloadClass;
        }

        @Override
        public Metadata getMetadata() {
            return Metadata.empty();
        }
    }
}
