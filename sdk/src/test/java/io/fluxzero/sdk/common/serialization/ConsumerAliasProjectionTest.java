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

package io.fluxzero.sdk.common.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.LocalTrackingClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.sdk.tracking.IndexUtils.indexFromMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerAliasProjectionTest {
    private static final AtomicInteger aliasInitializations = new AtomicInteger();

    @Test
    void projectingAnExactAliasDoesNotInitializeItsTargetClass() {
        var serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.Initializer", StaticAliasTarget.class.getName());
        assertFalse(serializer.describeConsumerFilter(handlers(null, false)).compile()
                .test(message("legacy.Initializer", 0, null, "{}", 1L)));
        assertEquals(0, aliasInitializations.get());
    }

    @Test
    void exactAliasesFollowPayloadSelectionWithoutBypassingRoutes() {
        var serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.Accepted", String.class.getName());
        serializer.registerTypeAlias("legacy.Ignored", Integer.class.getName());
        var filter = serializer.describeConsumerFilter(handlers(null, true)).compile();
        assertTrue(filter.test(message("legacy.Accepted", 0, "GET /accepted", "\"accepted\"", 1L)));
        assertFalse(filter.test(message("legacy.Accepted", 0, "GET /other", "\"accepted\"", 1L)));
        assertFalse(filter.test(message("legacy.Ignored", 0, "GET /accepted", "42", 1L)));
        assertEquals("accepted", serializer.deserialize(
                message("legacy.Accepted", 0, "GET /accepted", "\"accepted\"", 1L)));
        assertEquals(Integer.valueOf(42), serializer.deserialize(
                message("legacy.Ignored", 0, "GET /accepted", "42", 1L)));

        var routesOnly = serializer.describeConsumerFilter(ConsumerFilter.select(null, 0L, ConsumerFilter.VERSION,
                null, null, List.of(new ConsumerFilter.RoutePattern("GET ", "/accepted")), List.of())).compile();
        assertTrue(routesOnly.test(message("legacy.Ignored", 0, "GET /accepted", "42", 1L)));
        assertFalse(routesOnly.test(message("legacy.Ignored", 0, "GET /other", "42", 1L)));
    }

    @Test
    void exactChainsPreserveEveryAliasAndPayloadRevisionConstraint() {
        var serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.First", "legacy.Second");
        serializer.registerTypeAlias("legacy.Second", String.class.getName());
        var filter = serializer.describeConsumerFilter(handlers(3, false)).compile();
        for (String type : List.of("legacy.First", "legacy.Second", String.class.getName())) {
            assertTrue(filter.test(message(type, 3, null, "\"accepted\"", 1L)));
            assertFalse(filter.test(message(type, 2, null, "\"accepted\"", 1L)));
        }
        assertEquals("accepted", serializer.deserialize(message("legacy.First", 3, null, "\"accepted\"", 1L)));
    }

    @Test
    void unprovenGenericUnknownAndPackageAliasesRetainTheirBroadFallback() {
        var serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.Unknown", "unavailable.Type");
        serializer.registerTypeAlias("legacy.Generic", "java.util.List<java.lang.String>");
        serializer.registerTypeAlias("legacy.Array", byte[].class.getName());
        serializer.registerTypeAlias("legacy.Exact", String.class.getName());
        var filter = serializer.describeConsumerFilter(handlers(null, true)).compile();
        for (String type : List.of("legacy.Unknown", "legacy.Generic", "legacy.Array", "legacy.Exact<unavailable.Type>")) {
            assertTrue(filter.test(message(type, 0, "GET /other", "null", 1L)), type);
        }
        serializer.registerPackageAlias("old.package", "new.package");
        assertTrue(serializer.describeConsumerFilter(handlers(null, true)).compile()
                .test(message("legacy.Exact", 0, "GET /other", "null", 1L)));
    }

    @Test
    void typeChangingCasterOnAliasSourceRetainsPayloadAndRouteBypass() {
        var serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.Ignored", Integer.class.getName());
        var input = message("legacy.Ignored", 0, "GET /other", "\"accepted\"", 1L);
        assertFalse(serializer.describeConsumerFilter(handlers(null, true)).compile().test(input));
        var registration = serializer.registerUpcasters(new RenamingCaster());
        assertTrue(serializer.describeConsumerFilter(handlers(null, true)).compile().test(input));
        assertEquals("accepted", serializer.deserialize(input));
        registration.cancel();
        assertFalse(serializer.describeConsumerFilter(handlers(null, true)).compile().test(input));
    }

    @Test
    void casterOnAnAliasTargetKeepsTheConservativeFallback() {
        var serializer = new JacksonSerializer(List.of(new TargetCaster()));
        serializer.registerTypeAlias("legacy.Ignored", Integer.class.getName());
        assertTrue(serializer.describeConsumerFilter(handlers(null, true)).compile()
                .test(message("legacy.Ignored", 0, "GET /other", "42", 1L)));
    }

    @Test
    void dynamicAliasRetargetingRefreshesAnAlreadyOpenRead() throws Exception {
        var serializer = new JacksonSerializer();
        serializer.registerTypeAlias("legacy.Dynamic", Integer.class.getName());
        try (var blueprint = new ConsumerBlueprint(serializer, () -> handlers(null, false), new AtomicLong()::incrementAndGet);
             var client = new LocalTrackingClient(MessageType.EVENT, null, null)) {
            var config = ConsumerConfiguration.builder().name("alias-consumer").consumerBlueprint(blueprint)
                    .maxWaitDuration(Duration.ofMinutes(15)).maxScanLookBack(60_000L).clientControlledIndex(true).build();
            long start = indexFromMillis(System.currentTimeMillis());
            var pending = client.read("tracker", start, config);
            client.append(Guarantee.STORED, message("legacy.Dynamic", 0, null, "42", start + 1L)).join();
            assertFalse(pending.isDone());
            var registration = serializer.registerTypeAlias("legacy.Dynamic", String.class.getName());
            var accepted = message("legacy.Dynamic", 0, null, "\"accepted\"", start + 2L);
            client.append(Guarantee.STORED, accepted).join();
            assertEquals(List.of(accepted), pending.get(3, TimeUnit.SECONDS).getMessages());

            registration.cancel();
            var afterCancellation = client.read("tracker", start + 2L, config);
            client.append(Guarantee.STORED, message("legacy.Dynamic", 0, null, "\"excluded\"", start + 3L)).join();
            var marker = message(String.class.getName(), 0, null, "\"marker\"", start + 4L);
            client.append(Guarantee.STORED, marker).join();
            assertEquals(List.of(marker), afterCancellation.get(3, TimeUnit.SECONDS).getMessages());
            client.disconnectTracker("alias-consumer", "tracker", false, Guarantee.STORED).join();
        }
    }

    private static ConsumerFilter handlers(Integer revision, boolean routes) {
        return ConsumerFilter.select(null, 0L, ConsumerFilter.VERSION, null,
                List.of(new ConsumerFilter.PayloadType(String.class.getName(), revision)),
                routes ? List.of(new ConsumerFilter.RoutePattern("GET ", "/accepted")) : null, List.of());
    }

    private static SerializedMessage message(String type, int revision, String route, String payload, long index) {
        var message = new SerializedMessage(new Data<>(payload.getBytes(UTF_8), type, revision, Data.JSON_FORMAT),
                Metadata.empty(), type + index, 0L);
        message.setIndex(index);
        message.setSegment(0);
        message.setRoute(route);
        return message;
    }

    static class RenamingCaster {
        @Upcast(type = "legacy.Ignored", revision = 0)
        Data<JsonNode> rename(JsonNode input) { return new Data<>(input, String.class.getName(), 0); }
    }

    static class TargetCaster {
        @Upcast(type = "java.lang.Integer", revision = 0)
        JsonNode update(JsonNode input) { return input; }
    }

    static final class StaticAliasTarget {
        static { aliasInitializations.incrementAndGet(); }
    }
}
