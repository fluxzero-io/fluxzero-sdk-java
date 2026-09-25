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

package io.fluxzero.sdk.configuration;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static io.fluxzero.common.Guarantee.SENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLoadedInterceptorTest {

    @Test
    void autoLoadsInterceptorsFromServiceLoader(@TempDir Path directory) throws Exception {
        // SPI discovery initializes global interface defaults. Give this test its own application classpath so its
        // providers cannot change unrelated SDK tests in a whole-project IntelliJ run.
        Path serviceRoot = Path.of(getClass().getResource("/service-loader").toURI());
        Path output = directory.resolve("service-loader.log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-ea", "-Xmx256m"));
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-XX:TieredStopAtLevel="))
                .forEach(command::add);
        command.addAll(List.of("-cp",
                serviceRoot + File.pathSeparator + System.getProperty("java.class.path"), Probe.class.getName()));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Service-loader application did not terminate");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Service-loader application did not stop");
            }
        }
    }

    private static String readOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            int before = ServiceLoadedInterceptor.batchInvocations.get();
            try {
                TestFixture.createAsync(new Handler()).whenCommand(new ServiceLoadedCommand())
                        .expectEvents("dispatch service-loaded")
                        .expectResult("handler service-loaded");
                assertTrue(ServiceLoadedInterceptor.batchInvocations.get() > before);
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
            verifyStreamingDelivery();
        }

        private static void verifyStreamingDelivery() {
            CompletableFuture<String> firstRead = new CompletableFuture<>();
            CompletableFuture<String> completed = new CompletableFuture<>();
            try {
                TestFixture.createAsync(new Object() {
                    @HandleEvent
                    void handle(DeserializingMessage message) throws Exception {
                        InputStream input = message.getPayloadAs(InputStream.class);
                        firstRead.complete(new String(input.readNBytes(6), StandardCharsets.UTF_8));
                        completed.complete(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }).whenExecuting(fc -> {
                    String id = fc.identityProvider().nextTechnicalId();
                    fc.client().getGatewayClient(MessageType.EVENT).append(SENT, chunk(id, "hello ", false)).get();
                    // The final chunk is deliberately withheld until the service-loaded handler sees the first.
                    assertEquals("hello ", firstRead.get(5, TimeUnit.SECONDS));
                    fc.client().getGatewayClient(MessageType.EVENT).append(SENT, chunk(id, "world", true)).get();
                }).expectThat(fc -> assertEquals("world", completed.orTimeout(5, TimeUnit.SECONDS).join()));
            } finally {
                TestFixture.shutDownActiveFixtures();
            }
        }

        private static SerializedMessage chunk(String id, String text, boolean last) {
            SerializedMessage result = new SerializedMessage(
                    new Data<>(text.getBytes(StandardCharsets.UTF_8), byte[].class.getName(), 0, null),
                    Metadata.of(HasMetadata.FIRST_CHUNK, Boolean.toString(!last),
                                HasMetadata.FINAL_CHUNK, Boolean.toString(last),
                                HasMetadata.CHUNK_INDEX, last ? "1" : "0"), id, System.currentTimeMillis());
            result.setSegment(0);
            return result;
        }
    }

    static class Handler {
        @HandleCommand
        String handle(ServiceLoadedCommand command) {
            Fluxzero.publishEvent("service-loaded");
            return "service-loaded";
        }
    }

    static class ServiceLoadedCommand {
    }
}
