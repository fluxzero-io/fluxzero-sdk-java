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

package io.fluxzero.sdk.test;

import io.fluxzero.common.application.SimplePropertySource;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestFixturePropertyTest {

    @Test
    void propertyConfiguredAfterProductionUserProviderCopyIsVisible() {
        TestFixture.create(new PropertyHandler())
                .withProductionUserProvider()
                .atFixedTime(Instant.parse("2026-09-11T10:15:30Z"))
                .withProperty("fixture.instanceId", "instance-1")
                .whenCommand(new ReadInstanceId())
                .expectResult("instance-1");
    }

    @Test
    void propertiesBeforeAndAfterAsyncSpyAndSyncCopiesRemainVisible() {
        TestFixture fixture = TestFixture.create()
                .withProperty("fixture.before", "before")
                .async()
                .withProperty("fixture.async", "async")
                .spy()
                .withProperty("fixture.spy", "spy")
                .sync();

        assertEquals("before", fixture.getFluxzero().propertySource().get("fixture.before"));
        assertEquals("async", fixture.getFluxzero().propertySource().get("fixture.async"));
        assertEquals("spy", fixture.getFluxzero().propertySource().get("fixture.spy"));
    }

    @Test
    void siblingCopiesKeepIndependentOverridesAndRemovals() {
        TestFixture parent = TestFixture.create(
                        DefaultFluxzero.builder().replacePropertySource(
                                ignored -> new SimplePropertySource(Map.of("fixture.base", "base"))))
                .withProperty("fixture.shared", "parent");
        TestFixture asyncSibling = parent.async().withProperty("fixture.shared", "async");
        TestFixture spyingSibling = parent.spy();

        asyncSibling.withProperty("fixture.shared", null);

        assertNull(asyncSibling.getFluxzero().propertySource().get("fixture.shared"));
        assertEquals("parent", spyingSibling.getFluxzero().propertySource().get("fixture.shared"));
        assertEquals("base", asyncSibling.getFluxzero().propertySource().get("fixture.base"));
        assertEquals("base", spyingSibling.getFluxzero().propertySource().get("fixture.base"));
    }

    @Test
    void separatelyCreatedFixturesUsingTheSameBuilderDoNotShareOverrides() {
        var builder = DefaultFluxzero.builder().replacePropertySource(
                ignored -> new SimplePropertySource(Map.of("fixture.base", "base")));

        TestFixture first = TestFixture.create(builder).withProperty("fixture.private", "first");
        TestFixture second = TestFixture.create(builder);

        assertEquals("first", first.getFluxzero().propertySource().get("fixture.private"));
        assertNull(second.getFluxzero().propertySource().get("fixture.private"));
        assertEquals("base", second.getFluxzero().propertySource().get("fixture.base"));
    }

    private static class PropertyHandler {
        @HandleCommand
        String handle(ReadInstanceId ignored) {
            return ApplicationProperties.getProperty("fixture.instanceId");
        }
    }

    private record ReadInstanceId() {
    }
}
