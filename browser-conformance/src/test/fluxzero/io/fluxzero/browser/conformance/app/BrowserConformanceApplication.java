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

package io.fluxzero.browser.conformance.app;

import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.dataprotection.DropProtectedData;
import io.fluxzero.sdk.publishing.dataprotection.ProtectData;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleError;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.tracking.handling.HandleNotification;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.HandleResult;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.Stateful;
import io.fluxzero.sdk.tracking.handling.authentication.ForbidsUser;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresAnyRole;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.web.BodyParam;
import io.fluxzero.sdk.web.CookieParam;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.HandleSocketClose;
import io.fluxzero.sdk.web.HandleSocketHandshake;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HandleSocketPong;
import io.fluxzero.sdk.web.HeaderParam;
import io.fluxzero.sdk.web.PathParam;
import io.fluxzero.sdk.web.QueryParam;

import java.time.Instant;
import java.util.Map;

/**
 * Source-only browser conformance app. The browser generator scans this file and emits reflection-free dispatch code.
 */
@Consumer(name = "browser-conformance", threads = 2, ignoreSegment = true,
          dispatchInterceptors = BrowserDispatchProbe.class,
          handlerInterceptors = BrowserHandlerProbe.class,
          batchInterceptors = BrowserBatchProbe.class)
@RegisterType(rootClass = BrowserConformanceApplication.class, contains = {"Order", "Browser"})
public final class BrowserConformanceApplication {

    @HandleCommand(allowedClasses = CreateOrder.class)
    @RoutingKey("orderId")
    @RequiresUser
    OrderCreated create(CreateOrder command) {
        return new OrderCreated(command.orderId(), command.email(), Instant.EPOCH);
    }

    @HandleQuery
    @LocalHandler
    @FilterContent
    OrderView get(GetOrder query) {
        return new OrderView(query.orderId(), "visible");
    }

    @HandleEvent(passive = true)
    @DropProtectedData
    void project(OrderCreated event) {
    }

    @HandleNotification(skipExpiredRequests = true)
    void notify(OrderCreated event) {
    }

    @HandleResult
    void result(OrderView view) {
    }

    @HandleError
    void error(RuntimeException error) {
    }

    @HandleMetrics
    void metrics(BrowserMetric metric) {
    }

    @HandleCustom(topic = "browser.topic")
    void custom(BrowserCustom custom) {
    }

    @HandleDocument(collection = "orders")
    void document(OrderView view) {
    }

    @HandleSchedule
    BrowserScheduleTick schedule(BrowserScheduleTick tick) {
        return tick;
    }

    @HandleCommand(disabled = true)
    void disabled(DisabledCommand command) {
    }

    @HandleGet("/orders/{orderId}")
    @NoUserRequired
    OrderView getOrder(@PathParam("orderId") String orderId,
                       @QueryParam("include") String include,
                       @HeaderParam("x-correlation-id") String correlationId,
                       @CookieParam("session") String session) {
        return new OrderView(orderId, include + ":" + correlationId + ":" + session);
    }

    @HandlePost("/orders")
    @RequiresAnyRole("admin")
    OrderCreated postOrder(@BodyParam CreateOrder command) {
        return create(command);
    }

    @HandleSocketHandshake("/socket")
    Map<String, String> handshake(@HeaderParam("sec-websocket-key") String key) {
        return Map.of("accepted", key);
    }

    @HandleSocketOpen("/socket")
    void socketOpen() {
    }

    @HandleSocketMessage("/socket")
    BrowserSocketReply socketMessage(BrowserSocketMessage message) {
        return new BrowserSocketReply(message.value());
    }

    @HandleSocketPong("/socket")
    void socketPong() {
    }

    @HandleSocketClose("/socket")
    @ForbidsUser
    void socketClose() {
    }
}

@TrackSelf
@RequiresUser
record CreateOrder(@RoutingKey String orderId, @ProtectData String email) {

    @HandleCommand
    OrderCreated handle() {
        return new OrderCreated(orderId, email, Instant.EPOCH);
    }
}

record GetOrder(String orderId) {
}

record OrderCreated(String orderId, String email, Instant timestamp) {
}

record OrderView(String orderId, String status) {

    @FilterContent
    OrderView filter() {
        return new OrderView(orderId, status);
    }
}

record BrowserMetric(String name, long value) {
}

record BrowserCustom(String value) {
}

record BrowserScheduleTick(String value) {
}

record BrowserSocketMessage(String value) {
}

record BrowserSocketReply(String value) {
}

record DisabledCommand(String value) {
}

@Aggregate(snapshotPeriod = 10, searchable = true, collection = "orders")
record OrderAggregate(@EntityId String orderId, String status) {

    @EntityId
    public String orderId() {
        return orderId;
    }

    @Apply
    OrderAggregate apply(OrderCreated event) {
        return new OrderAggregate(event.orderId(), "created");
    }
}

@Stateful(collection = "orderProcesses", commitInBatch = true)
record OrderProcess(@EntityId String orderId, String state) {

    @HandleEvent
    OrderProcess on(OrderCreated event) {
        return new OrderProcess(event.orderId(), "projected");
    }
}

final class BrowserDispatchProbe {
}

final class BrowserHandlerProbe {
}

final class BrowserBatchProbe {
}
