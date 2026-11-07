/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.modeling;

import com.sun.management.ThreadMXBean;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.tracking.handling.MetadataParameterResolver;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static io.fluxzero.common.api.modeling.ModelEventMetadata.COMMIT_ID;
import static io.fluxzero.common.api.modeling.ModelEventMetadata.SUBSTEP;

/** Standalone selection-only diagnostic. Runs identical bytecode against pre-fix and candidate SDKs. */
public class GraphHandlerSelectionBenchmark {
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        var allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (Object target : List.of(new Plain(), new GraphOnly(), new GraphContext())) {
            var builder = HandlerConfiguration.<DeserializingMessage>builder().methodAnnotation(HandleEvent.class);
            boolean roles;
            try {
                var describe = GraphChangeHandlerDecorator.class.getMethod("describesParameters", Executable.class);
                Predicate<Executable> predicate = method -> {
                    try { return (boolean) describe.invoke(null, method); }
                    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                };
                builder.getClass().getMethod("describeParameterRoles", Predicate.class).invoke(builder, predicate);
                roles = true;
            } catch (NoSuchMethodException ignored) {
                roles = false;
            }
            Handler<DeserializingMessage> raw = HandlerInspector.createHandler(target,
                    List.of(new MessageParameterResolver(), new MetadataParameterResolver(),
                            new PayloadParameterResolver(), new EntityParameterResolver()), builder.build());
            @SuppressWarnings("unchecked")
            Handler<DeserializingMessage> handler = roles
                    ? (Handler<DeserializingMessage>) GraphChangeHandlerDecorator.class
                            .getMethod("wrapGraphChanges", Handler.class, MessageType.class, Handler.class)
                            .invoke(null, raw, MessageType.EVENT, raw)
                    : GraphChangeHandlerDecorator.wrapGraphChanges(raw, MessageType.EVENT);
            var message = new DeserializingMessage(new Message("value", Metadata.of(COMMIT_ID, "commit", SUBSTEP, 0)),
                                                   MessageType.EVENT, null);
            for (int i = 0; i < 1_000_000; i++) sink = handler.getInvokerOrNull(message);
            int count = 500_000;
            long bytes = allocation.getThreadAllocatedBytes(thread), start = System.nanoTime();
            for (int i = 0; i < count; i++) sink = handler.getInvokerOrNull(message);
            long duration = System.nanoTime() - start;
            double allocated = (allocation.getThreadAllocatedBytes(thread) - bytes) / (double) count;
            long[] samples = new long[20_000];
            for (int i = 0; i < samples.length; i++) {
                start = System.nanoTime();
                sink = handler.getInvokerOrNull(message);
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            if (sink == null) throw new AssertionError("Handler was not selected");
            System.out.printf(Locale.ROOT, "%s %.1f ns/op %.1f bytes/op p99=%d ns%n",
                    target.getClass().getSimpleName(), duration / (double) count, allocated, samples[19800]);
        }
    }

    @Model record Root(@EntityId String id) {}
    static class Plain { @HandleEvent void on(String payload) {} }
    static class GraphOnly { @HandleEvent void on(Graph<Root> graph) {} }
    static class GraphContext { @HandleEvent void on(Graph<Root> graph, Message message, Metadata metadata) {} }
}
