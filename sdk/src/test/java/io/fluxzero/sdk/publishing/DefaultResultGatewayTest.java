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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.WebsocketGatewayClient;
import io.fluxzero.sdk.tracking.handling.DefaultResponseMapper;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultResultGatewayTest {

    @Test
    void batchedResponseUsesCapturedContextAndCompletesWithAppend() throws Exception {
        ThreadLocal<String> context = ThreadLocalContext.create();
        AtomicReference<String> mappedContext = new AtomicReference<>();
        AtomicReference<String> mappedThread = new AtomicReference<>();
        AtomicReference<String> monitoredContext = new AtomicReference<>();
        CountDownLatch appended = new CountDownLatch(1);
        CompletableFuture<Void> appendCompletion = new CompletableFuture<>();
        ResponseMapper mapper = recordingMapper(() -> {
            mappedContext.set(context.get());
            mappedThread.set(Thread.currentThread().getName());
        });
        DispatchInterceptor interceptor = new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, io.fluxzero.common.MessageType messageType,
                                             String topic) {
                return message;
            }

            @Override
            public void monitorDispatch(Message message, io.fluxzero.common.MessageType messageType, String topic,
                                        String namespace, boolean request) {
                monitoredContext.set(context.get());
            }
        };
        DefaultResultGateway gateway = gateway(mapper, interceptor, invocation -> {
            appended.countDown();
            return appendCompletion;
        });

        context.set("handler-context");
        String callerThread = Thread.currentThread().getName();
        CompletableFuture<Void> publication = gateway.respondBatched("result", "sender", 42);
        context.remove();

        assertTrue(appended.await(2, TimeUnit.SECONDS));
        assertEquals("handler-context", mappedContext.get());
        assertEquals("handler-context", monitoredContext.get());
        assertNotEquals(callerThread, mappedThread.get());
        assertFalse(publication.isDone());

        appendCompletion.complete(null);
        publication.get(2, TimeUnit.SECONDS);
        gateway.close();
    }

    @Test
    void preparationFailureIsIsolatedWithoutChangingResultOrder() throws Exception {
        ThreadLocal<String> context = ThreadLocalContext.create();
        DefaultResponseMapper delegate = new DefaultResponseMapper();
        ResponseMapper mapper = new ResponseMapper() {
            @Override
            public Message map(Object response) {
                return map(response, io.fluxzero.common.api.Metadata.empty());
            }

            @Override
            public Message map(Object response, io.fluxzero.common.api.Metadata metadata) {
                if ("result-257".equals(response)) {
                    throw new IllegalStateException("deliberate mapping failure");
                }
                return delegate.map(response, metadata);
            }
        };
        List<String> monitored = Collections.synchronizedList(new ArrayList<>());
        List<String> published = Collections.synchronizedList(new ArrayList<>());
        DispatchInterceptor interceptor = new DispatchInterceptor() {
            @Override
            public Message interceptDispatch(Message message, io.fluxzero.common.MessageType messageType,
                                             String topic) {
                return message;
            }

            @Override
            public void monitorDispatch(Message message, io.fluxzero.common.MessageType messageType, String topic,
                                        String namespace, boolean request) {
                monitored.add(message.getPayload());
            }
        };
        DefaultResultGateway gateway = gateway(mapper, interceptor, invocation -> {
            for (SerializedMessage message : messages(invocation)) {
                published.add(new String(message.getData().getValue(), StandardCharsets.UTF_8));
            }
            return CompletableFuture.completedFuture(null);
        });
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> failureContext = new AtomicReference<>();
        List<CompletableFuture<Void>> publications = new ArrayList<>();

        context.set("consumer-context");
        for (int index = 0; index < 512; index++) {
            publications.add(gateway.respondBatched("result-" + index, "sender", index, (failure, retry) -> {
                failures.incrementAndGet();
                failureContext.set(context.get());
                return CompletableFuture.completedFuture(null);
            }));
        }
        context.remove();
        CompletableFuture.allOf(publications.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);

        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 512; index++) {
            if (index != 257) {
                expected.add("result-" + index);
            }
        }
        assertEquals(1, failures.get());
        assertEquals("consumer-context", failureContext.get());
        assertEquals(expected, monitored);
        assertEquals(expected.stream().map(value -> '"' + value + '"').toList(), published);
        gateway.close();
    }

    private static ResponseMapper recordingMapper(Runnable recorder) {
        DefaultResponseMapper delegate = new DefaultResponseMapper();
        return new ResponseMapper() {
            @Override
            public Message map(Object response) {
                recorder.run();
                return delegate.map(response);
            }

            @Override
            public Message map(Object response, io.fluxzero.common.api.Metadata metadata) {
                recorder.run();
                return delegate.map(response, metadata);
            }
        };
    }

    private static DefaultResultGateway gateway(ResponseMapper mapper, DispatchInterceptor interceptor,
                                                Answer<CompletableFuture<Void>> append) {
        Client client = mock(Client.class);
        WebsocketGatewayClient gatewayClient = mock(WebsocketGatewayClient.class);
        when(client.namespace()).thenReturn("test");
        when(client.getGatewayClient(RESULT)).thenReturn(gatewayClient);
        when(gatewayClient.append(eq(Guarantee.NONE), any(SerializedMessage[].class))).thenAnswer(append);
        return new DefaultResultGateway(client, new JacksonSerializer(), interceptor, mapper);
    }

    private static List<SerializedMessage> messages(InvocationOnMock invocation) {
        Object[] arguments = invocation.getArguments();
        List<SerializedMessage> result = new ArrayList<>(arguments.length - 1);
        for (int index = 1; index < arguments.length; index++) {
            result.add((SerializedMessage) arguments[index]);
        }
        return result;
    }
}
