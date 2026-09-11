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

package io.fluxzero.sdk.tracking.handling.authentication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserIdentityContractTest {
    @TempDir Path classes;

    @Test
    void idIsMandatoryAndPrincipalNameIsOptional() throws Exception {
        assertTrue(Modifier.isAbstract(User.class.getMethod("id").getModifiers()));
        assertFalse(User.class.getMethod("id").isDefault());
        assertTrue(User.class.getMethod("getName").isDefault());
        User user = new User() {
            public String id() { return "stable-id"; }
            public boolean hasRole(String role) { return false; }
        };
        Principal principal = user;
        assertEquals("stable-id", principal.getName());
    }

    @Test
    void compilerRejectsNameOnlyImplementationButAcceptsIdOnlyImplementation() throws Exception {
        assertFalse(compiles("public String getName() { return \"old-name\"; }"));
        assertTrue(compiles("public String id() { return \"stable-id\"; }"));
    }

    @Test
    void idOnlyImplementationRetainsCompleteUserJsonWithoutAnAutomaticIdProperty() throws Exception {
        User user = new IdOnlyUser(List.of("reader"));
        var mapper = io.fluxzero.common.api.Metadata.objectMapper;
        var json = mapper.valueToTree(user);
        assertFalse(json.has("id"));
        assertEquals("stable-id", json.path("name").asText());
        User restored = mapper.readValue(mapper.writeValueAsBytes(user), User.class);
        assertEquals(user, restored);
        assertEquals("stable-id", restored.id());
    }

    public record IdOnlyUser(List<String> roles) implements User {
        public String id() { return "stable-id"; }
        public boolean hasRole(String role) { return roles.contains(role); }
    }

    private boolean compiles(String identityMethod) throws Exception {
        String source = """
                import io.fluxzero.sdk.tracking.handling.authentication.User;
                class IdentityImplementation implements User {
                    public boolean hasRole(String role) { return false; }
                    %s
                }
                """.formatted(identityMethod);
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///IdentityImplementation.java"),
                                                       JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            boolean compiled = compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "25", "-classpath", System.getProperty("java.class.path")),
                    null, List.of(unit)).call();
            if (!compiled) {
                assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getMessage(null).contains("id()")),
                           diagnostics.getDiagnostics().toString());
            }
            return compiled;
        }
    }
}
