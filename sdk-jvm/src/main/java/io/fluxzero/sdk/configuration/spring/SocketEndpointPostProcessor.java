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

package io.fluxzero.sdk.configuration.spring;

import io.fluxzero.sdk.web.SocketEndpoint;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor} that detects
 * {@link SocketEndpoint} types within the application's component-scan scope and registers them as
 * {@link FluxzeroPrototype} definitions for use in Fluxzero.
 * <p>
 * This enables WebSocket endpoints to be activated per application scan scope without exposing those endpoint types as
 * regular Spring beans.
 *
 * <h2>Usage</h2>
 * To expose a WebSocket endpoint in your application:
 * <pre>{@code
 * @SocketEndpoint
 * public class ChatSocketEndpoint {
 *     @HandleSocketMessage
 *     public ChatResponse handle(ChatRequest request) {
 *         // respond to request via WebSocket
 *     }
 * }
 * }</pre>
 * Ensure that Spring picks up this processor, typically via:
 * <pre>{@code
 * @SpringBootApplication
 * @Import(FluxzeroSpringConfig.class)
 * public class MyApp { ... }
 * }</pre>
 *
 * @see SocketEndpoint
 * @see FluxzeroPrototype
 * @see FluxzeroSpringConfig
 */
@Slf4j
public class SocketEndpointPostProcessor extends ComponentScanPrototypePostProcessor {
    @Override
    protected Class<SocketEndpoint> getTargetAnnotation() {
        return SocketEndpoint.class;
    }

    @Override
    protected String getBeanNameSuffix() {
        return "$$SocketEndpoint";
    }
}
