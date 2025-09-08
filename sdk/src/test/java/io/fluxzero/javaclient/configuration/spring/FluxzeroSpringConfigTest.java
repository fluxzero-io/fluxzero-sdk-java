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

package io.fluxzero.javaclient.configuration.spring;

import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.javaclient.Fluxzero;
import io.fluxzero.javaclient.common.serialization.DeserializingMessage;
import io.fluxzero.javaclient.common.serialization.casting.Upcast;
import io.fluxzero.javaclient.configuration.ApplicationProperties;
import io.fluxzero.javaclient.configuration.FluxzeroBuilder;
import io.fluxzero.javaclient.persisting.caching.DefaultCache;
import io.fluxzero.javaclient.persisting.eventsourcing.Apply;
import io.fluxzero.javaclient.tracking.handling.HandleCommand;
import io.fluxzero.javaclient.tracking.handling.HandleQuery;
import io.fluxzero.javaclient.tracking.handling.IllegalCommandException;
import io.fluxzero.javaclient.tracking.handling.LocalHandler;
import io.fluxzero.javaclient.tracking.handling.authentication.User;
import io.fluxzero.javaclient.tracking.handling.authentication.UserProvider;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.BeanFactory;
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
    BeanFactory beanFactory;

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
        public TextNode upcastResult(TextNode node, @NonNull SerializedMessage message) {
            return TextNode.valueOf(node.asText().equals("result") ? "intermediate result" : node.asText());
        }

        @Upcast(type = "java.lang.String", revision = 1)
        public TextNode upcastIntermediateResult(TextNode node, @NonNull SerializedMessage message) {
            return TextNode.valueOf(node.asText().equals("intermediate result") ? "upcasted result" : node.asText());
        }
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
    private static class CustomCache extends DefaultCache {
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
