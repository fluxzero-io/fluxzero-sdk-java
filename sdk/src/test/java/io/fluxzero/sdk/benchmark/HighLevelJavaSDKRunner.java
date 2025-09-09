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

package io.fluxzero.sdk.benchmark;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class HighLevelJavaSDKRunner extends AbstractClientBenchmark {

    public static void main(final String[] args) {
        HighLevelJavaSDKRunner runner = new HighLevelJavaSDKRunner(
                1_000, WebSocketClient.ClientConfig.builder()
                .name("benchmark-" + UUID.randomUUID())
                .projectId("benchmark")
                .serviceBaseUrl("https://fluxzero.sloppy.zone")
                .build());
        runner.testCommands();
        System.exit(0);
    }

    private final Fluxzero fluxzero;

    public HighLevelJavaSDKRunner(int commandCount) {
        super(commandCount);
        fluxzero = DefaultFluxzero.builder().build(WebSocketClient.newInstance(getClientConfig()));
        fluxzero.registerHandlers(this);
    }

    public HighLevelJavaSDKRunner(int commandCount, WebSocketClient.ClientConfig clientConfig) {
        super(commandCount, clientConfig);
        fluxzero = DefaultFluxzero.builder().build(WebSocketClient.newInstance(clientConfig));
        fluxzero.registerHandlers(this);
    }

    @Override
    protected void doSendCommand(String payload) {
        fluxzero.commandGateway().sendAndForget(payload);
    }

    @HandleCommand(passive = true)
    public void handleCommand(String command) {
        getCommandCountDownLatch().countDown();
    }

}
