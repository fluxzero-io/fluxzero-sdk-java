/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.testserver;

import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchDocumentsResult;
import io.fluxzero.common.search.Facet;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.spring.FluxzeroTestConfig;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.metrics.DisableMetrics;
import io.fluxzero.sdk.tracking.metrics.ProcessBatchEvent;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(classes = {FluxzeroTestConfig.class, TestServerTest.FooConfig.class, TestServerTest.BarConfig.class})
@Slf4j
class TestServerTest {

    private static final int port = 9123;

    @BeforeAll
    static void beforeAll() {
        TestServer.start(port);
    }

    @Autowired
    private TestFixture testFixture;

    @Test
    void testFirstOrderEffect() {
        testFixture.whenCommand(new DoSomething()).expectEvents(new DoSomething());
    }

    @Test
    void testSecondOrderEffect() {
        testFixture.whenCommand(new DoSomething()).expectCommands(new DoSomethingElse());
    }

    @Test
    void testFetchLotsOfDocuments() {
        testFixture.given(fc -> {
            fc.documentStore().index("bla1", "test").get();
            fc.documentStore().index("bla2", "test").get();
            fc.documentStore().index("bla3", "test").get();
        }).whenApplying(fc -> fc.documentStore().search("test").lookAhead("bla").stream(2).toList())
                .expectResult(list -> list.size() == 3);
    }

    @Test
    void testFacetsHandlerIncluded() {
        testFixture.given(fc -> fc.documentStore().index(new FacetedObject("bla"), "test").get())
                .whenApplying(fc -> fc.documentStore().search("test").facetStats())
                .expectResult(list -> list.size() == 1);
    }

    @Test
    void testGetSchedule() {
        Schedule schedule = new Schedule("bla", "test",
                                         testFixture.getCurrentTime().plusSeconds(10));
        testFixture.givenSchedules(schedule)
                .whenApplying(fc -> fc.messageScheduler().getSchedule("test").orElse(null))
                .expectResult(schedule);
    }

    @Test
    void allowMetrics() {
        final String consumerName = "MetricsBlocked-consumer";
        @Consumer(name = consumerName)
        class Handler {
            @HandleEvent
            void handle(String ignored) {
                Fluxzero.search("mock").fetchAll();
            }
        }
        testFixture.registerHandlers(new Handler()).whenEvent("test")
                .expectMetrics(SearchDocumentsResult.Metric.class, SearchDocuments.class)
                .<ProcessBatchEvent>expectMetric(e -> consumerName.equals(e.getConsumer()));
    }

    @Test
    void blockHandlerMetrics() {
        final String consumerName = "MetricsBlocked-consumer";
        @Consumer(name = consumerName, handlerInterceptors = DisableMetrics.class)
        class Handler {
            @HandleEvent
            void handle(String ignored) {
                Fluxzero.search("mock").fetchAll();
            }
        }
        testFixture.registerHandlers(new Handler()).whenExecuting(fc -> {
                    fc.eventGateway().publish("test");
                    Thread.sleep(100);
                })
                .expectNoMetricsLike(SearchDocumentsResult.Metric.class)
                .expectNoMetricsLike(SearchDocuments.class)
                .<ProcessBatchEvent>expectMetric(e -> consumerName.equals(e.getConsumer()));
    }

    @Test
    void handleDocument() {
        testFixture.whenExecuting(fc -> {
                    Fluxzero.index("testDoc", "test").get();
                    Thread.sleep(100);
                })
                .expectEvents("testDoc");
    }

    @Test
    void handleCustom() {
        testFixture.whenCustom("test", "testCustom")
                .expectEvents("testCustom");
    }

    @Test
    void handleSchedule() {
        testFixture
                .whenExecuting(fc -> {
                    Fluxzero.schedule("foo", Fluxzero.currentTime().minusSeconds(1));
                    Thread.sleep(500);
                })
                .expectEvents("foo");
    }

    @Configuration
    static class FooConfig {
        @Bean
        public WebSocketClient.ClientConfig webSocketClientProperties() {
            return WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl("ws://localhost:" + port)
                    .namespace("clienttest")
                    .name("GivenWhenThenSpringCustom Client Test")
                    .build();
        }

        @Bean
        public FooHandler fooHandler() {
            return new FooHandler();
        }

        @Bean
        DocumentHandler documentHandler() {
            return new DocumentHandler();
        }

        @Bean
        CustomHandler customHandler() {
            return new CustomHandler();
        }

        @Bean
        SchedulingHandler schedulingHandler() {
            return new SchedulingHandler();
        }
    }

    private static class FooHandler {
        @HandleCommand
        public void handle(DoSomething command) {
            Fluxzero.publishEvent(command);
        }
    }

    static class DocumentHandler {
        @HandleDocument("test")
        void handle(String doc) {
            Fluxzero.publishEvent(doc);
        }
    }

    static class CustomHandler {
        @HandleCustom("test")
        void handle(String doc) {
            Fluxzero.publishEvent(doc);
        }
    }

    static class SchedulingHandler {
        @HandleSchedule
        public void handle(String schedule) {
            Fluxzero.publishEvent(schedule);
        }
    }

    @Configuration
    static class BarConfig {
        @Bean
        public BarHandler barHandler() {
            return new BarHandler();
        }
    }

    static class BarHandler {
        @HandleEvent
        public void handle(DoSomething event) {
            Fluxzero.sendAndForgetCommand(new DoSomethingElse());
        }
    }

    @Value
    private static class DoSomething {
    }

    @Value
    private static class DoSomethingElse {
    }

    @Value
    private static class FacetedObject {
        @Facet
        String something;
    }

}

