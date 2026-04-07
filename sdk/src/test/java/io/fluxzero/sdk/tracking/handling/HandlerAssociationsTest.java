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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.sdk.publishing.routing.RoutingKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerAssociationsTest {

    @Test
    void reusesAssociationMetadataPerType() {
        var first = new HandlerAssociations(SampleHandler.class, List.of(), e -> null);
        var second = new HandlerAssociations(SampleHandler.class, List.of(), e -> null);

        assertSame(first.getAssociationProperties(), second.getAssociationProperties());
    }

    @Test
    void cachesAssociationAndExecutableMetadata() throws NoSuchMethodException {
        HandlerAssociations associations = new HandlerAssociations(SampleHandler.class, List.of(), e -> null);

        var associationProperties = associations.getAssociationProperties();
        assertEquals(List.of("aliasId", "someId"), associationProperties.keySet().stream().sorted().toList());
        assertEquals("someId", associationProperties.get("aliasId").getPath());

        Method alwaysMethod = SampleHandler.class.getDeclaredMethod("always", SamplePayload.class);
        assertTrue(associations.alwaysAssociate(alwaysMethod));

        Method routingKeyMethod = SampleHandler.class.getDeclaredMethod("routingKeyAssociation", SamplePayload.class);
        var routingKeyAssociations = associations.getMethodAssociationProperties(routingKeyMethod);
        assertSame(routingKeyAssociations, associations.getMethodAssociationProperties(routingKeyMethod));
        assertEquals(1, routingKeyAssociations.size());
        assertEquals("customId", routingKeyAssociations.getFirst().getPropertyName());
        assertFalse(routingKeyAssociations.getFirst().isComputedRoutingKey());

        Method computedMethod = SampleHandler.class.getDeclaredMethod("computedAssociation", SamplePayload.class);
        var computedAssociations = associations.getMethodAssociationProperties(computedMethod);
        assertEquals(1, computedAssociations.size());
        assertTrue(computedAssociations.getFirst().isComputedRoutingKey());

        Method parameterMethod = SampleHandler.class.getDeclaredMethod("parameterAssociation", SampleCommand.class);
        var parameterAssociations = associations.getMethodAssociationProperties(parameterMethod);
        assertEquals(1, parameterAssociations.size());
        assertEquals("orderId", parameterAssociations.getFirst().getPropertyName());
        assertEquals("targetId", parameterAssociations.getFirst().getAssociationValue().getPath());
    }

    static class SampleHandler {
        @Association({"someId", "aliasId"})
        private String someId;

        @HandleCommand
        @Association(always = true)
        static void always(SamplePayload payload) {
        }

        @HandleCommand
        @Association
        @RoutingKey("customId")
        void routingKeyAssociation(SamplePayload payload) {
        }

        @HandleCommand
        @Association
        void computedAssociation(SamplePayload payload) {
        }

        @HandleCommand
        void parameterAssociation(@Association(value = "orderId", path = "targetId") SampleCommand command) {
        }
    }

    record SamplePayload(String someId) {
    }

    record SampleCommand(String orderId) {
    }
}
