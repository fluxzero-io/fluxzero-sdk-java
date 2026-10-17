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

package io.fluxzero.sdk.givenwhenthen.classpathpolicy;

import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.Then;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthenticatedException;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import io.fluxzero.sdk.tracking.handling.authentication.User;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ClasspathPackagePolicyScenario {
    private static final String ROOT = "example.classpathpolicy";
    private final List<TestFixture> fixtures = new ArrayList<>();
    private final boolean asynchronous;
    private volatile User user;

    private ClasspathPackagePolicyScenario(boolean asynchronous) {
        this.asynchronous = asynchronous;
    }

    public static void verify(String expected, String firstModule, boolean preloadParent, boolean asynchronous)
            throws Exception {
        var loader = ClasspathPackagePolicyScenario.class.getClassLoader();
        String secondModule = firstModule.equals("FirstModule") ? "SecondModule" : "FirstModule";
        Class<?> first = loader.loadClass(ROOT + ".child." + firstModule);
        Class<?> second = loader.loadClass(ROOT + ".child." + secondModule);
        assertSame(first.getPackage(), second.getPackage());
        assertNotEquals(first.getProtectionDomain().getCodeSource().getLocation(),
                        second.getProtectionDomain().getCodeSource().getLocation());
        assertNull(loader.getDefinedPackage(ROOT));
        if (preloadParent) {
            loader.loadClass(ROOT + ".RootMarker");
            assertNotNull(loader.getDefinedPackage(ROOT));
        }
        var scenario = new ClasspathPackagePolicyScenario(asynchronous);
        try {
            scenario.verifyModule(firstModule, expected.equals("second"));
            scenario.verifyModule(secondModule, expected.equals("second"));
            assertEquals(expected + "-child", first.getPackage().getAnnotation(Deprecated.class).since());
            Package parent = loader.getDefinedPackage(ROOT);
            assertNotNull(parent);
            assertEquals(expected, parent.getAnnotation(Deprecated.class).since());
            assertEquals(expected.equals("first") ? 1 : 2,
                         ReflectionUtils.getPackageAnnotations(first.getPackage(), false).size());
        } finally {
            scenario.fixtures.forEach(fixture -> fixture.getFluxzero().close(true));
        }
    }

    private void verifyModule(String module, boolean requiresAdmin) throws Exception {
        Object request = instance(module + "$Request");
        TestFixture payload = fixture(new PublicHandler());
        TestFixture handler = fixture(instance(module + "$Handler"));
        user = null;
        expectAccess(payload.whenQuery(request), requiresAdmin ? UnauthenticatedException.class : null);
        expectAccess(payload.whenCommand(request), requiresAdmin ? UnauthenticatedException.class : null);
        expectAccess(handler.whenQuery("request"), requiresAdmin ? UnauthenticatedException.class : null);
        user = new MockUser("member");
        expectAccess(payload.whenQuery(request), requiresAdmin ? UnauthorizedException.class : null);
        expectAccess(handler.whenQuery("request"), requiresAdmin ? UnauthorizedException.class : null);
        user = new MockUser("admin");
        payload.whenQuery(request).expectResult("handled");
        handler.whenQuery("request").expectResult("handled");
        user = null;
        expectAccess(payload.whenQuery(request), requiresAdmin ? UnauthenticatedException.class : null);
        expectAccess(handler.whenQuery("request"), requiresAdmin ? UnauthenticatedException.class : null);
    }

    private void expectAccess(Then<Object> result, Class<? extends Throwable> failure) {
        if (failure == null) {
            result.expectResult("handled");
        } else {
            result.expectExceptionalResult(failure);
        }
    }

    private TestFixture fixture(Object handler) {
        var builder = DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(() -> user));
        TestFixture fixture = asynchronous ? TestFixture.createAsync(builder, handler) : TestFixture.create(builder, handler);
        fixtures.add(fixture);
        return fixture;
    }

    private Object instance(String name) throws Exception {
        return Class.forName(ROOT + ".child." + name).getConstructor().newInstance();
    }

    @NoUserRequired
    private static class PublicHandler {
        @HandleCommand
        @HandleQuery
        String handle(Object request) {
            return "handled";
        }
    }
}
