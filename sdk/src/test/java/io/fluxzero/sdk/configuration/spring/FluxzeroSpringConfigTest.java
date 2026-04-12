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

import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.persisting.caching.Cache;
import io.fluxzero.sdk.persisting.caching.CacheEviction;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.Stateful;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserParameterResolver;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import io.fluxzero.sdk.web.SocketEndpoint;
import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = FluxzeroSpringConfigTest.Config.class)
@Slf4j
public class FluxzeroSpringConfigTest {
    private static final User mockUser = mock(User.class);
    private static final UserProvider mockUserProvider = mock(UserProvider.class);

    static {
        when(mockUserProvider.fromMessage(any(DeserializingMessage.class))).thenReturn(mockUser);
    }

    @BeforeAll
    static void beforeAll() {
        System.setProperty("existingProperty", "test");
        System.setProperty("emptyProperty", "");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("existingProperty");
        System.clearProperty("emptyProperty");
    }

    @Autowired
    private Fluxzero fluxzero;

    @Autowired
    ListableBeanFactory beanFactory;

    @Test
    void testHandleCommand() {
        String result = fluxzero.commandGateway().sendAndWait("command");
        assertEquals("upcasted result", result);
    }

    @Test
    void testHandleAggregateCommand() {
        Object result = fluxzero.commandGateway().sendAndWait(new AggregateCommand());
        assertNull(result);
    }

    @Test
    void testUserProviderInjected() {
        assertEquals(mockUser, fluxzero.queryGateway().sendAndWait(new GetUser()));
    }

    @Test
    void testDefaultCacheReplaced() {
        assertTrue(fluxzero.cache() instanceof CustomCache);
    }

    @Test
    void testConditionalPropertyMissing() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ConditionalPropertyMissing.class));
    }

    @Test
    void testConditionalPropertyEmpty() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ConditionalPropertyEmpty.class));
    }

    @Test
    void testConditionalPropertyPresent() {
        assertNotNull(beanFactory.getBean(ConditionalPropertyPresent.class));
    }

    @Test
    void testConditionalOnMissingPropertyMissing() {
        assertNotNull(beanFactory.getBean(ConditionalOnMissingProperty_PropertyMissing.class));
    }

    @Test
    void testConditionalOnMissingPropertyPresent() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ConditionalOnMissingProperty_PropertyPresent.class));
    }

    @Test
    void testConditionalComponentMissing() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ConditionalBeanMissing.class));
    }

    @Test
    void testConditionalComponentPresent() {
        assertNotNull(beanFactory.getBean(ConditionalBeanPresent.class));
    }

    @Test
    void testPropertySetUsingCustomizer() {
        assertEquals("firstCustomizerValue", fluxzero.apply(fc -> ApplicationProperties.getProperty("bar")));
        assertEquals("secondCustomizerValue", fluxzero.apply(fc -> ApplicationProperties.getProperty("foo")));
    }

    @Test
    void testConfigurationExposesAssembledParameterResolvers() {
        assertTrue(fluxzero.configuration().parameterResolvers().stream()
                           .anyMatch(SpringBeanParameterResolver.class::isInstance));
        assertTrue(fluxzero.configuration().parameterResolvers().stream()
                           .anyMatch(UserParameterResolver.class::isInstance));
        assertTrue(fluxzero.configuration().parameterResolvers().stream()
                           .anyMatch(MessageParameterResolver.class::isInstance));
    }

    @Test
    void statefulTypesAreRegisteredWithoutExposingSpringBeans() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ScannedStatefulHandler.class));
        assertRegisteredAsPrototype(ScannedStatefulHandler.class);
    }

    @Test
    void trackSelfTypesAreRegisteredWithoutExposingSpringBeans() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ScannedSelfTrackedPayload.class));
        assertRegisteredAsPrototype(ScannedSelfTrackedPayload.class);
    }

    @Test
    void socketEndpointsAreRegisteredWithoutExposingSpringBeans() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> beanFactory.getBean(ScannedSocketEndpoint.class));
        assertRegisteredAsPrototype(ScannedSocketEndpoint.class);
    }

    @Test
    void statefulConditionalOnPropertyIsRespected() {
        assertRegisteredAsPrototype(ConditionalStatefulPresent.class);
        assertNotRegisteredAsPrototype(ConditionalStatefulMissing.class);
    }

    @Test
    void trackSelfConditionalOnMissingPropertyIsRespected() {
        assertRegisteredAsPrototype(ConditionalSelfTrackedMissingProperty.class);
        assertNotRegisteredAsPrototype(ConditionalSelfTrackedExistingProperty.class);
    }

    @Test
    void socketEndpointConditionalOnPropertyIsRespected() {
        assertRegisteredAsPrototype(ConditionalSocketEndpointPresent.class);
        assertNotRegisteredAsPrototype(ConditionalSocketEndpointMissing.class);
    }

    private void assertRegisteredAsPrototype(Class<?> type) {
        assertTrue(beanFactory.getBeansOfType(FluxzeroPrototype.class).values().stream()
                           .anyMatch(prototype -> prototype.getType().equals(type)),
                    () -> "Expected " + type.getName() + " to be registered as FluxzeroPrototype");
    }

    private void assertNotRegisteredAsPrototype(Class<?> type) {
        assertTrue(beanFactory.getBeansOfType(FluxzeroPrototype.class).values().stream()
                           .noneMatch(prototype -> prototype.getType().equals(type)),
                    () -> "Expected " + type.getName() + " not to be registered as FluxzeroPrototype");
    }

    @Component
    public static class SomeHandler {
        @HandleCommand
        public Object handleCommand(String command, User user) {
            requireNonNull(user, "User is null");
            return "result";
        }

        @HandleCommand
        void handle(AggregateCommand command, @Autowired UserProvider userProvider) {
            assertNotNull(userProvider, "User provider should have been injected");
            Fluxzero.loadAggregate("whatever", Object.class).assertAndApply(command);
        }
    }

    @Component @LocalHandler
    public static class SomeLocalHandler {
        @HandleQuery
        public User handle(GetUser query) {
            return User.getCurrent();
        }
    }

    @Component
    public static class StringUpcaster {
        @Upcast(type = "java.lang.String", revision = 0)
        @Upcast(type = "java.lang.String", revision = 1)
        public TextNode upcastResult(TextNode node, @NonNull SerializedMessage message) {
            return TextNode.valueOf(switch (node.asText()) {
                case "result" -> "intermediate result";
                case "intermediate result" -> "upcasted result";
                default -> node.asText();
            });
        }
    }

    @Stateful
    public static class ScannedStatefulHandler {
    }

    @TrackSelf
    public static class ScannedSelfTrackedPayload {
    }

    @SocketEndpoint
    public static class ScannedSocketEndpoint {
    }

    @Stateful
    @ConditionalOnProperty("existingProperty")
    public static class ConditionalStatefulPresent {
    }

    @Stateful
    @ConditionalOnProperty("missingProperty")
    public static class ConditionalStatefulMissing {
    }

    @TrackSelf
    @ConditionalOnMissingProperty("missingProperty")
    public static class ConditionalSelfTrackedMissingProperty {
    }

    @TrackSelf
    @ConditionalOnMissingProperty("existingProperty")
    public static class ConditionalSelfTrackedExistingProperty {
    }

    @SocketEndpoint
    @ConditionalOnProperty("existingProperty")
    public static class ConditionalSocketEndpointPresent {
    }

    @SocketEndpoint
    @ConditionalOnProperty("missingProperty")
    public static class ConditionalSocketEndpointMissing {
    }

    @Configuration
    @Import(FluxzeroSpringConfig.class)
    @ComponentScan
    public static class Config {

        @Autowired
        void configure(FluxzeroBuilder builder) {
            builder.makeApplicationInstance(false);
        }

        @Bean
        @ConditionalOnMissingBean(UserProvider.class)
        public UserProvider userProvider() {
            return mockUserProvider;
        }

        @Bean
        FluxzeroCustomizer firstCustomizer() {
            return builder -> builder.replacePropertySource(existing -> new SimplePropertySource(
                    Map.of("foo", "firstCustomizerVale", "bar", "firstCustomizerValue")).andThen(existing));
        }

        @Bean
        FluxzeroCustomizer secondCustomizer() {
            return builder -> builder.replacePropertySource(existing -> new SimplePropertySource(
                    Map.of("foo", "secondCustomizerValue")).andThen(existing));
        }
    }

    @Component
    @ConditionalOnMissingBean
    private static class CustomCache implements Cache {
        @Delegate
        private final Cache delegate = new DefaultCache();

        @Override
        public Cache rebuild() {
            return new CustomCache();
        }
    }

    @Value
    static class GetUser {
    }

    @Value
    static class AggregateCommand {
        @Apply
        Object apply(User user) {
            if (user == null) {
                throw new IllegalCommandException("User is null");
            }
            return new Object();
        }
    }

    @Value
    @ConditionalOnProperty("missingProperty")
    @Component
    public static class ConditionalPropertyMissing {
    }

    @Value
    @ConditionalOnMissingProperty("missingProperty")
    @Component
    public static class ConditionalOnMissingProperty_PropertyMissing {
    }

    @Value
    @ConditionalOnMissingProperty("existingProperty")
    @Component
    public static class ConditionalOnMissingProperty_PropertyPresent {
    }

    @Value
    @ConditionalOnProperty("emptyProperty")
    @Component
    public static class ConditionalPropertyEmpty {
    }

    @Value
    @ConditionalOnProperty("existingProperty")
    @Component
    public static class ConditionalPropertyPresent {
    }

    @Value
    @ConditionalOnBean(ConditionalPropertyPresent.class)
    @Component
    public static class ConditionalBeanPresent {
    }

    @Value
    @ConditionalOnBean(ConditionalPropertyMissing.class)
    @Component
    public static class ConditionalBeanMissing {
    }

}
