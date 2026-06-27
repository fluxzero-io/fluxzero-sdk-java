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

package io.fluxzero.sdk.browser;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.browser.conformance.BrowserConformanceReport;
import io.fluxzero.sdk.browser.conformance.BrowserFeatureResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserExecutionCoreTest {

    @Test
    void registersGeneratedHandlersFromComponentMetadataLookup() {
        String componentName = "io.fluxzero.sdk.browser.MetadataHandler";
        BrowserComponentRegistry registry = new BrowserComponentRegistry(
                0,
                1,
                0,
                List.of(new BrowserRouteMetadata(
                        componentName,
                        MessageType.COMMAND,
                        false,
                        false,
                        false,
                        true,
                        true,
                        java.util.Set.of(CreateOrder.class.getName()),
                        java.util.Set.of(),
                        0)));
        BrowserExecutionCore core = BrowserExecutionCore.create(fixedClock(), registry);

        core.register(componentName, MessageType.COMMAND, CreateOrder.class.getName(),
                      message -> new OrderCreated(((CreateOrder) message.payload()).orderId()));

        Object result = core.messageBus().dispatch(MessageType.COMMAND, "", CreateOrder.class.getName(),
                                                   new CreateOrder("order-1"), Map.of());

        assertEquals(new OrderCreated("order-1"), result);
        assertEquals(1, ((Map<?, ?>) core.snapshot().get("metadata")).get("components"));
        assertEquals(1L, ((Map<?, ?>) core.messageBus().snapshot()).get("metadataHandlers"));
    }

    @Test
    void routesGatewaysThroughGeneratedHandlerRegistrations() {
        BrowserExecutionCore core = BrowserExecutionCore.create(fixedClock());
        core.messageBus().register(new BrowserHandlerRegistration(
                "handler.command", MessageType.COMMAND, "", CreateOrder.class.getName(), false,
                message -> new OrderCreated(((CreateOrder) message.payload()).orderId())));
        core.messageBus().register(new BrowserHandlerRegistration(
                "handler.event", MessageType.EVENT, "", OrderCreated.class.getName(), true,
                message -> "ignored"));

        Object result = core.messageBus().dispatch(MessageType.COMMAND, CreateOrder.class.getName(),
                                                   new CreateOrder("order-1"));
        core.messageBus().dispatch(MessageType.EVENT, OrderCreated.class.getName(), result);

        assertEquals(new OrderCreated("order-1"), result);
        assertEquals(2, core.messageBus().invocations().size());
        assertTrue(core.messageBus().invocations().contains(
                "handler.command:COMMAND:io.fluxzero.sdk.browser.BrowserExecutionCoreTest$CreateOrder"));
        assertTrue(core.messageBus().invocations().contains(
                "handler.event:EVENT:io.fluxzero.sdk.browser.BrowserExecutionCoreTest$OrderCreated"));
    }

    @Test
    void providesBrowserSafeStoresSchedulerWebRouterAndSockets() {
        BrowserExecutionCore core = BrowserExecutionCore.create(fixedClock());
        core.keyValueStore().put("cache:order-1", "cached");
        core.eventStore().append("order-1", new OrderCreated("order-1"));
        core.eventStore().snapshot("order-1", "snapshot");
        core.documentStore().index("orders", "order-1", Map.of("id", "order-1"));
        core.scheduler().schedule("schedule-1", Instant.parse("2026-01-01T00:00:00Z"), new Tick("due"));
        core.messageBus().register(new BrowserHandlerRegistration(
                "handler.schedule", MessageType.SCHEDULE, "", Tick.class.getName(), false,
                message -> "scheduled"));
        core.webRouter().register("GET", "/orders/{orderId}", exchange -> exchange.withResponse(
                200,
                exchange.pathParameters().get("orderId") + ":" + exchange.query().get("include") + ":"
                + exchange.headers().get("x-correlation-id") + ":" + exchange.cookies().get("session")));
        core.socketSimulator().handshake("/socket");
        core.socketSimulator().open("s1");
        core.socketSimulator().message("s1", "hello");
        core.socketSimulator().pong("s1");
        core.socketSimulator().close("s1");

        BrowserWebExchange response = core.webRouter().handle(new BrowserWebExchange(
                "GET", "/orders/order-1", null,
                Map.of("x-correlation-id", "corr-1"),
                Map.of("include", "details"),
                Map.of("session", "s1"),
                0, null));

        assertEquals("cached", core.keyValueStore().get("cache:order-1"));
        assertEquals(1, core.eventStore().events("order-1").size());
        assertEquals("snapshot", core.eventStore().snapshot("order-1"));
        assertEquals(1, core.documentStore().search("orders").size());
        assertEquals(1, core.scheduler().runDue());
        assertEquals(200, response.status());
        assertEquals("order-1:details:corr-1:s1", response.responseBody());
        assertEquals(5, ((Iterable<?>) core.socketSimulator().snapshot().get("events")).spliterator()
                .getExactSizeIfKnown());
        assertEquals(1, core.snapshot().get("scheduler") instanceof Map<?, ?> map ? map.get("expired") : 0);
    }

    @Test
    void appliesBrowserSafeInterceptorsErrorReportingAndRecursiveGuard() {
        BrowserExecutionCore core = BrowserExecutionCore.create(fixedClock());
        core.messageBus().enableRecursivePublicationGuard();
        core.messageBus().addDispatchInterceptor(message -> message.withMetadata("correlationId", "corr-1"));
        core.messageBus().addHandlerInterceptor((message, next) -> "handled:" + next.handle(message));
        core.messageBus().addBatchInterceptor(messages -> messages.subList(0, 1));
        core.messageBus().addErrorReporter((error, message) -> core.keyValueStore().put("lastError", error.getMessage()));
        core.messageBus().register(new BrowserHandlerRegistration(
                "handler.command", MessageType.COMMAND, "", CreateOrder.class.getName(), false,
                message -> {
                    core.messageBus().dispatch(MessageType.EVENT, OrderCreated.class.getName(),
                                               new OrderCreated("recursive"));
                    return message.metadata().get("correlationId");
                }));
        core.messageBus().register(new BrowserHandlerRegistration(
                "handler.error", MessageType.ERROR, "", RuntimeException.class.getName(), false,
                message -> {
                    throw new IllegalStateException("boom");
                }));

        Object result = core.messageBus().dispatch(MessageType.COMMAND, CreateOrder.class.getName(),
                                                   new CreateOrder("order-1"));
        try {
            core.messageBus().dispatch(MessageType.ERROR, RuntimeException.class.getName(),
                                       new RuntimeException("fail"));
        } catch (IllegalStateException ignored) {
            // Expected: the reporter records the exception before it is rethrown.
        }
        var batch = core.messageBus().processBatch(List.of(
                BrowserMessage.of(MessageType.COMMAND, "", new CreateOrder("a"), CreateOrder.class.getName(),
                                  Map.of(), Instant.EPOCH),
                BrowserMessage.of(MessageType.COMMAND, "", new CreateOrder("b"), CreateOrder.class.getName(),
                                  Map.of(), Instant.EPOCH)));

        assertEquals("handled:corr-1", result);
        assertEquals(1, core.messageBus().recursiveDispatchesBlocked());
        assertEquals(1, batch.size());
        assertEquals("boom", core.keyValueStore().get("lastError"));
        assertEquals(1, core.messageBus().errors().size());
    }

    @Test
    void providesGeneratedCodecRegistryAndValidatorContracts() {
        BrowserExecutionCore core = BrowserExecutionCore.create(fixedClock());
        core.codecRegistry().register(CreateOrder.class.getName(), new BrowserCodec() {
            @Override
            public Map<String, Object> encode(Object value) {
                return Map.of("orderId", ((CreateOrder) value).orderId());
            }

            @Override
            public Object decode(Map<String, Object> data) {
                return new CreateOrder((String) data.get("orderId"));
            }
        });
        core.codecRegistry().registerUpcaster(CreateOrder.class.getName(), 1, value -> new CreateOrder("upcasted"));
        core.codecRegistry().registerDowncaster(CreateOrder.class.getName(), 2, value -> "legacy");
        BrowserValidator validator = new BrowserValidator();
        validator.length("orderId", "order-1", 1, 20);

        Map<String, Object> encoded = core.codecRegistry().encode(CreateOrder.class.getName(),
                                                                  new CreateOrder("order-1"));
        Object decoded = core.codecRegistry().decode(CreateOrder.class.getName(), encoded);

        assertEquals(new CreateOrder("order-1"), decoded);
        assertEquals(new CreateOrder("upcasted"),
                     core.codecRegistry().upcast(CreateOrder.class.getName(), 1, new CreateOrder("old")));
        assertEquals("legacy", core.codecRegistry().downcast(CreateOrder.class.getName(), 2,
                                                             new CreateOrder("new")));
        assertTrue(validator.valid());
    }

    @Test
    void emitsJsonConformanceReportForJavaScript() {
        BrowserConformanceReport report = new BrowserConformanceReport();
        report.add(BrowserFeatureResult.passed("handler.command", Map.of("invocations", 1)));
        report.add(BrowserFeatureResult.failed("web.params", "missing query", Map.of("path", "/orders")));

        String json = report.toJson();

        assertTrue(json.contains("\"passed\":1"));
        assertTrue(json.contains("\"failed\":1"));
        assertTrue(json.contains("\"handler.command\""));
        assertTrue(json.contains("\"missing query\""));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-06-26T12:00:00Z"), ZoneOffset.UTC);
    }

    private record CreateOrder(String orderId) {
    }

    private record OrderCreated(String orderId) {
    }

    private record Tick(String value) {
    }
}
