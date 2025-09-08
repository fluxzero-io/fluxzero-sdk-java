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

package io.fluxzero.javaclient.common.logging;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.javaclient.Fluxzero;
import io.fluxzero.javaclient.common.ClientUtils;
import io.fluxzero.javaclient.test.TestFixture;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Slf4j
class FluxzeroLogbackAppenderTest {

    private final Fluxzero fluxzero = TestFixture.create().spy().getFluxzero();

    @BeforeEach
    void setUp() {
        Fluxzero.instance.set(fluxzero);
        FluxzeroLogbackAppender.attach();
    }

    @AfterEach
    void tearDown() {
        FluxzeroLogbackAppender.detach();
        Fluxzero.instance.remove();
    }

    @Test
    void testConsoleError() {
        log.error("mock error");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR)).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleError.class.getName().equals(message.getData().getType())));
    }

    @Test
    void testConsoleErrorWithIgnoreMarker() {
        log.error(ClientUtils.ignoreMarker, "mock error");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR), never()).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleError.class.getName().equals(message.getData().getType())));
    }

    @Test
    void testConsoleWarning() {
        String messageTemplate = "mock warning {}";
        log.warn(messageTemplate, "foo");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR)).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleWarning.class.getName().equals(message.getData().getType())
                        && messageTemplate.equals(message.getMetadata().get("messageTemplate"))));
    }
}