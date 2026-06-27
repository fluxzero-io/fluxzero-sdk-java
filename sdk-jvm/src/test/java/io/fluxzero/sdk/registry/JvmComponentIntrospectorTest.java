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

package io.fluxzero.sdk.registry;

import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleWeb;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmComponentIntrospectorTest {

    private final JvmComponentIntrospector introspector = JvmComponentIntrospector.getInstance();

    @Test
    void exposesExecutableAnnotationDescriptors() throws Exception {
        Executable executable = CommandHandler.class.getDeclaredMethod("handle", Command.class);

        AnnotationDescriptor annotation = introspector.executableAnnotations(executable).getFirst();

        assertEquals("HandleCommand", annotation.name());
        assertEquals(HandleCommand.class.getName(), annotation.qualifiedName());
        assertEquals(List.of(Command.class.getName()), annotation.values("allowedClasses"));
    }

    @Test
    void projectsMetaAnnotationsOnExecutables() throws Exception {
        Executable executable = WebHandler.class.getDeclaredMethod("handle");

        WebProjection projection = introspector.executableAnnotationAs(
                executable, HandleWeb.class, WebProjection.class).orElseThrow();

        assertFalse(projection.isSkipExpiredRequests());
    }

    @Test
    void exposesPropertyAccessWithoutCallersUsingReflectionUtils() {
        Payload payload = new Payload("test-id");

        AccessibleObject property = introspector.annotatedProperty(Payload.class, Marker.class).orElseThrow();

        assertEquals("id", introspector.propertyName(property));
        assertEquals("test-id", introspector.annotatedPropertyValue(payload, Marker.class).orElseThrow());
        assertEquals(Marker.class.getName(), introspector.propertyAnnotations(property).getFirst().qualifiedName());
    }

    @Test
    void invokesExecutableHandlesBehindTheBoundary() throws Exception {
        Executable executable = CommandHandler.class.getDeclaredMethod("reply", String.class);

        Object result = introspector.invoke(executable, new CommandHandler(), List.of("ping"));

        assertEquals("handled ping", result);
    }

    @Test
    void exposesTypeSpecificityComparator() {
        assertTrue(introspector.typeSpecificityComparator().compare(Command.class, SpecialCommand.class) > 0);
    }

    static class CommandHandler {
        @HandleCommand(allowedClasses = Command.class)
        void handle(Command command) {
        }

        String reply(String value) {
            return "handled " + value;
        }
    }

    static class WebHandler {
        @HandleGet(value = "/test", skipExpiredRequests = false)
        void handle() {
        }
    }

    record Payload(@Marker String id) {
    }

    static class Command {
    }

    static class SpecialCommand extends Command {
    }

    @Value
    static class WebProjection {
        boolean skipExpiredRequests;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
    @interface Marker {
    }
}
