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

import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebMatcherTest {

    @Test
    void literalHandlerWinsOverPathParameter() {
        var handler = new GetHandler();
        var matcher = WebHandlerMatcher.create(handler.getClass(), Collections.emptyList(), HandlerConfiguration.
                <DeserializingMessage>builder().methodAnnotation(HandleWeb.class).build());

        WebRequest webRequest = WebRequest.builder().method(HttpRequestMethod.GET).url("/abc/henk").build();
        var m = new DeserializingMessage(webRequest, WEBREQUEST, mock(Serializer.class));



        assertTrue(matcher.canHandle(m));
        assertEquals("henk", matcher.getInvoker(handler, m).orElseThrow().invoke());
    }

    @Test
    void literalHandlerWinsOverWildcard() {
        var handler = new WildcardHandler();
        var matcher = WebHandlerMatcher.create(handler.getClass(), Collections.emptyList(), HandlerConfiguration.
                <DeserializingMessage>builder().methodAnnotation(HandleWeb.class).build());

        WebRequest webRequest = WebRequest.builder().method(HttpRequestMethod.GET).url("/a/b/c").build();
        var m = new DeserializingMessage(webRequest, WEBREQUEST, mock(Serializer.class));

        assertTrue(matcher.canHandle(m));
        assertEquals("specific", matcher.getInvoker(handler, m).orElseThrow().invoke());
    }

    static class GetHandler {
        @HandleGet("/abc/{userId}")
        String handleAny() {
            return "any";
        }

        @HandleGet("/abc/henk")
        String handleHenk() {
            return "henk";
        }

        @HandleSocketOpen("/abc/henk")
        String handleWsHenk() {
            return "henk";
        }
    }

    static class WildcardHandler {
        @HandleGet("/a/*/c")
        String handleWildcard() {
            return "wildcard";
        }

        @HandleGet("/a/b/c")
        String handleSpecific() {
            return "specific";
        }
    }
}
