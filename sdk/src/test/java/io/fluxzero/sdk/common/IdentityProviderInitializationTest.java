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
 */

package io.fluxzero.sdk.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IdentityProviderInitializationTest {
    @ParameterizedTest
    @ValueSource(strings = {"fallback", "custom", "invalid"})
    void concurrentFirstUseDoesNotWaitForProviderDiscovery(String mode, @TempDir Path directory) throws Exception {
        Path output = directory.resolve("process.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode, directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Provider process did not exit");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Throwable {
            String mode = args[0];
            CountDownLatch discovering = new CountDownLatch(1), proceed = new CountDownLatch(1);
            Path service = Path.of(args[1]).resolve("provider");
            Files.writeString(service, mode.equals("invalid") ? "missing.Provider" : CustomProvider.class.getName()
                    + "\n" + UnselectedProvider.class.getName());
            ClassLoader loader = new ClassLoader(Probe.class.getClassLoader()) {
                @Override public Enumeration<URL> getResources(String name) throws IOException {
                    if (!name.equals("META-INF/services/" + IdentityProvider.class.getName())) {
                        return super.getResources(name);
                    }
                    discovering.countDown();
                    try {
                        if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Discovery gate timed out"); }
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                    return Collections.enumeration(mode.equals("fallback") ? Collections.emptyList()
                            : Collections.singletonList(service.toUri().toURL()));
                }
            };
            CompletableFuture<IdentityProvider> selected = start(loader, () -> IdentityProvider.defaultIdentityProvider);
            if (!discovering.await(10, TimeUnit.SECONDS)) { throw new AssertionError("No eager SPI discovery"); }
            try {
                // On the broken implementation this holds the implementation's initialization monitor and waits
                // for IdentityProvider, whose discovery then waits for that same implementation.
                IdentityProvider direct = start(Probe.class.getClassLoader(), () -> mode.equals("custom")
                        ? new CustomProvider() : new UuidFactory()).get(5, TimeUnit.SECONDS);
                if (direct.nextFunctionalId() == null) { throw new AssertionError("Missing ID"); }
            } finally { proceed.countDown(); }
            if (mode.equals("invalid")) {
                try { selected.get(5, TimeUnit.SECONDS); throw new AssertionError("Invalid SPI accepted"); }
                catch (java.util.concurrent.ExecutionException expected) {
                    if (!(expected.getCause() instanceof ServiceConfigurationError)) { throw expected; }
                }
                return;
            }
            IdentityProvider provider = selected.get(5, TimeUnit.SECONDS);
            if (provider != IdentityProvider.defaultIdentityProvider) { throw new AssertionError("Unstable provider"); }
            if (mode.equals("custom")) {
                if (!(provider instanceof CustomProvider) || CustomProvider.instances.get() != 2) {
                    throw new AssertionError("SPI provider was wrapped, changed or repeatedly constructed");
                }
                if (!provider.nextTechnicalId().equals("custom") || !provider.idForName("name").equals("name")) {
                    throw new AssertionError("Custom ID semantics changed");
                }
            } else if (!(provider instanceof UuidFactory) || provider.nextFunctionalId().length() != 32) {
                throw new AssertionError("UUID fallback changed");
            }
            // Resolve the historical field owner as old bytecode and reflective callers do.
            if (java.lang.invoke.MethodHandles.publicLookup().findStaticGetter(
                    IdentityProvider.class, "defaultIdentityProvider", IdentityProvider.class).invokeWithArguments() != provider) {
                throw new AssertionError("Historical field owner no longer resolves");
            }
        }

        private static <T> CompletableFuture<T> start(ClassLoader loader, java.util.function.Supplier<T> action) {
            CompletableFuture<T> result = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try { result.complete(action.get()); } catch (Throwable e) { result.completeExceptionally(e); }
            });
            thread.setDaemon(true);
            thread.setContextClassLoader(loader);
            thread.start();
            return result;
        }
    }

    public static class CustomProvider implements IdentityProvider {
        static final AtomicInteger instances = new AtomicInteger();
        public CustomProvider() { instances.incrementAndGet(); }
        @Override public String nextFunctionalId() { return "custom"; }
        @Override public String idForName(String name) { return name; }
    }

    public static class UnselectedProvider implements IdentityProvider {
        public UnselectedProvider() { throw new AssertionError("Only the first SPI provider should be created"); }
        @Override public String nextFunctionalId() { return "unselected"; }
        @Override public String idForName(String name) { return name; }
    }
}
