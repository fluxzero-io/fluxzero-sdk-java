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

package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiProcessorContractTest {
    private static final String SOURCE = """
            package contracts;

            import com.fasterxml.jackson.annotation.JsonSubTypes;
            import com.fasterxml.jackson.annotation.JsonTypeInfo;
            import io.fluxzero.sdk.web.ApiDoc;
            import io.fluxzero.sdk.web.BodyParam;
            import io.fluxzero.sdk.web.FormParam;
            import io.fluxzero.sdk.web.HandlePost;
            import jakarta.validation.constraints.NotBlank;
            import jakarta.validation.constraints.Pattern;
            import jakarta.validation.constraints.Size;
            import java.util.Map;

            @ApiDoc
            public class ContractApi {
                @HandlePost("/required")
                void required(@ApiDoc(required = true)
                        Map<@Pattern(regexp = "[a-z]{2}") @Size(min = 2, max = 2) String,
                        @Size(min = 2, max = 8) String> body) {}

                @HandlePost("/optional")
                void optional(Map<String, String> body) {}

                @HandlePost("/parameters")
                void parameters(@BodyParam("mandatory") @NotBlank String mandatory,
                                @BodyParam("optional") String optional) {}

                @HandlePost("/multiple")
                void multiple(@NotBlank String mandatory, String optional) {}

                @HandlePost("/form")
                void form(@FormParam("mandatory") @NotBlank String mandatory,
                          @FormParam("optional") String optional) {}

                @HandlePost("/named")
                void named(NamedPayload body) {}

                @HandlePost("/deduced")
                void deduced(DeducedPayload body) {}

                @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
                @JsonSubTypes(@JsonSubTypes.Type(value = NamedValue.class, name = "named"))
                interface NamedPayload {}

                record NamedValue(String value) implements NamedPayload {}

                @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, property = "ignored")
                @JsonSubTypes({
                        @JsonSubTypes.Type(DeducedText.class),
                        @JsonSubTypes.Type(DeducedNumber.class)
                })
                interface DeducedPayload {}

                record DeducedText(String text) implements DeducedPayload {}

                record DeducedNumber(int number) implements DeducedPayload {}
            }
            """;

    @TempDir
    Path outputDirectory;

    @Test
    void generatesEquivalentRequestAndPolymorphismContractsForOpenApi30And31() throws IOException {
        for (String version : List.of("3.0.4", "3.1.1")) {
            JsonNode document = compile(version);
            assertEquals(version, document.path("openapi").asText());
            JsonNode paths = document.path("paths");

            JsonNode requiredBody = paths.path("/required").path("post").path("requestBody");
            assertTrue(requiredBody.path("required").asBoolean());
            JsonNode mapSchema = requiredBody.path("content").path("application/json").path("schema");
            assertEquals(2, mapSchema.path("additionalProperties").path("minLength").asInt());
            assertEquals(8, mapSchema.path("additionalProperties").path("maxLength").asInt());
            if (OpenApiOptions.isOpenApi31(version)) {
                assertEquals("string", mapSchema.path("propertyNames").path("type").asText());
                assertEquals("[a-z]{2}", mapSchema.path("propertyNames").path("pattern").asText());
                assertEquals(2, mapSchema.path("propertyNames").path("minLength").asInt());
                assertEquals(2, mapSchema.path("propertyNames").path("maxLength").asInt());
            } else {
                assertFalse(mapSchema.has("propertyNames"));
            }

            assertFalse(paths.path("/optional").path("post").path("requestBody").has("required"));
            JsonNode bodyParameters = paths.path("/parameters").path("post").path("requestBody");
            assertTrue(bodyParameters.path("required").asBoolean());
            assertTrue(contains(bodyParameters.path("content").path("application/json").path("schema")
                                        .path("required"), "mandatory"));
            JsonNode multipleBodies = paths.path("/multiple").path("post").path("requestBody");
            assertTrue(multipleBodies.path("required").asBoolean());
            assertTrue(contains(multipleBodies.path("content").path("application/json").path("schema")
                                        .path("required"), "mandatory"));
            JsonNode formBody = paths.path("/form").path("post").path("requestBody");
            assertTrue(formBody.path("required").asBoolean());
            assertTrue(contains(formBody.path("content").path("application/x-www-form-urlencoded").path("schema")
                                        .path("required"), "mandatory"));

            JsonNode schemas = document.path("components").path("schemas");
            JsonNode named = schemas.path("NamedPayload");
            assertEquals("kind", named.path("discriminator").path("propertyName").asText());
            assertEquals("#/components/schemas/NamedValue", named.path("discriminator").path("mapping")
                    .path("named").asText());
            assertEquals(1, named.path("oneOf").size());
            JsonNode deduced = schemas.path("DeducedPayload");
            assertFalse(deduced.has("discriminator"));
            assertEquals(2, deduced.path("oneOf").size());
        }
    }

    private JsonNode compile(String version) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path output = Files.createDirectories(outputDirectory.resolve(version));
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            JavaFileObject source = new SimpleJavaFileObject(
                    URI.create("string:///contracts/ContractApi.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return SOURCE;
                }
            };
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-parameters", "-proc:only",
                    "-A" + OpenApiProcessor.OPENAPI_VERSION_OPTION + "=" + version);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
                                                                 List.of(source));
            task.setProcessors(List.of(new OpenApiProcessor()));
            assertTrue(task.call(), () -> "Compilation failed: " + diagnostics.getDiagnostics());
        }
        return JsonUtils.fromJson(Files.readAllBytes(output.resolve(OpenApiProcessor.DEFAULT_OUTPUT)), JsonNode.class);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
