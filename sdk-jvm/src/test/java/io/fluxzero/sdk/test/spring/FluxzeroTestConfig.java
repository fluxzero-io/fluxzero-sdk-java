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

package io.fluxzero.sdk.test.spring;


import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.configuration.spring.FluxzeroCustomizer;
import io.fluxzero.sdk.configuration.spring.FluxzeroSpringConfig;
import io.fluxzero.sdk.configuration.spring.SpringHandlerRegistry;
import io.fluxzero.sdk.test.TestFixture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Optional;

/**
 * Spring configuration class for enabling {@link TestFixture}-based testing in full application contexts.
 * <p>
 * This configuration allows integration tests to leverage Fluxzero's {@code given-when-then} style
 * testing while still wiring up the entire Spring Boot application.
 * <p>
 * The {@link TestFixture} bean provided here behaves like a production {@code Fluxzero} instance,
 * but internally uses a test fixture engine to record and assert behaviors.
 * It is set up in <strong>asynchronous mode</strong> by default.
 *
 * <p><strong>Usage:</strong><br>
 * Include this configuration in your test using Spring's {@code @SpringBootTest}:
 *
 * <pre>{@code
 * @SpringBootTest(classes = {App.class, FluxzeroTestConfig.class})
 * class MyIntegrationTest {
 *
 *     @Autowired TestFixture fixture;
 *
 *     @Test
 *     void testSomething() {
 *         fixture.givenCommands("fixtures/setup-command.json")
 *                .whenCommand("commands/my-command.json")
 *                .expectResult("results/expected-response.json");
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Enabling synchronous fixture mode:</strong><br>
 * To test with a synchronous {@code TestFixture} (where all handlers are invoked in the same thread),
 * set the following property in {@code src/test/resources/application.properties} or override it per test:
 *
 * <pre>{@code
 * fluxzero.test.sync=true
 * }</pre>
 *
 * Or override it in a single test using:
 *
 * <pre>{@code
 * @SpringBootTest(classes = {App.class, FluxzeroTestConfig.class})
 * @TestPropertySource(properties = "fluxzero.test.sync=true")
 * class MySyncTest { ... }
 * }</pre>
 *
 * <p>This configuration:
 * <ul>
 *   <li>Imports {@link FluxzeroSpringConfig} for automatic handler registration.</li>
 *   <li>Registers a primary {@link Fluxzero} bean backed by the test fixture.</li>
 *   <li>Supports customization via {@link FluxzeroCustomizer}s.</li>
 *   <li>Falls back to creating an in-memory {@link LocalClient} if no client bean is available in the context.</li>
 *   <li>Supports both synchronous and asynchronous execution modes (see {@code fluxzero.test.sync}).</li>
 * </ul>
 *
 * @see TestFixture
 * @see FluxzeroSpringConfig
 * @see FluxzeroCustomizer
 */
@Configuration
@RequiredArgsConstructor
@Import(FluxzeroSpringConfig.class)
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("!main")
public class FluxzeroTestConfig {

    private final ApplicationContext context;

    @Value("${fluxzero.test.sync:false}")
    private boolean synchronous;

    /**
     * Registers a {@link Fluxzero} bean backed by the {@link TestFixture}.
     * <p>
     * This allows your Spring application and test components to inject the fixture transparently
     * wherever a {@code Fluxzero} is expected.
     */
    @Bean
    @Primary
    public Fluxzero testFluxzero(TestFixture testFixture) {
        log.info("Using test fixture for Fluxzero");
        return testFixture.getFluxzero();
    }

    /**
     * Constructs an asynchronous {@link TestFixture} using a configured {@link FluxzeroBuilder}.
     * <p>
     * If a {@link Client} or {@link WebSocketClient.ClientConfig} is present in the Spring context,
     * it will be used to initialize the fixture. Otherwise, a local in-memory client will be used.
     * <p>
     * All {@link FluxzeroCustomizer}s found in the context will be applied to the builder before creation.
     */
    @Bean
    public TestFixture testFixture(FluxzeroBuilder fluxzeroBuilder, List<FluxzeroCustomizer> customizers) {
        fluxzeroBuilder.makeApplicationInstance(false);
        FluxzeroCustomizer customizer = customizers.stream()
                .reduce((first, second) -> b -> second.customize(first.customize(b)))
                .orElse(b -> b);
        fluxzeroBuilder = customizer.customize(fluxzeroBuilder);
        Client client = getBean(Client.class).orElseGet(() -> getBean(WebSocketClient.ClientConfig.class).<Client>map(
                WebSocketClient::newInstance).orElse(null));
        if (client == null) {
            return synchronous
                    ? TestFixture.create(fluxzeroBuilder)
                    : TestFixture.createAsync(fluxzeroBuilder);
        }
        return synchronous
                ? TestFixture.create(fluxzeroBuilder)
                : TestFixture.createAsync(fluxzeroBuilder, client);
    }

    @Bean
    SpringHandlerRegistry springHandlerRegistry(TestFixture testFixture) {
        return handlers -> {
            testFixture.registerHandlers(handlers);
            return Registration.noOp();
        };
    }

    /**
     * Helper method to retrieve a single bean of the given type from the Spring context, if available.
     */
    protected <T> Optional<T> getBean(Class<T> type) {
        return context.getBeansOfType(type).values().stream().findFirst();
    }
}
