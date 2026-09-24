/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.ThreadMXBean;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.GraphProjection;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.tracking.handling.HandleDocument;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.mockito.Mockito.mock;

/** Manual paired allocation/time diagnostic; the baseline is the pre-source-retention document pipeline. */
public class DocumentGraphReadBenchmark {
    private static volatile long sink;
    private static final int ITERATIONS = 10_000;
    private static final String COLLECTION = "document-graph-benchmark";
    private final JacksonSerializer serializer = new JacksonSerializer(List.of(new RootCaster()));
    private final DocumentMessageReader ordinary = new DocumentMessageReader();
    private final DocumentMessageReader graphs = new DocumentMessageReader();
    private final Function<Object, Object> resolve;
    private final List<SerializedMessage> current;
    private final List<SerializedMessage> legacy;

    DocumentGraphReadBenchmark() throws Exception {
        graphs.register(new Reader(), HandlerFilter.ALWAYS_HANDLE);
        ModelRepository repository = mock(ModelRepository.class);
        var resolver = new MaterializedGraphParameterResolver(serializer, () -> repository,
                () -> List.of(Root.class, Child.class));
        var method = Reader.class.getDeclaredMethod("read", Graph.class);
        resolve = resolver.resolve(method.getParameters()[0], method.getAnnotation(HandleDocument.class));
        current = document(1);
        legacy = document(0);
    }

    public static void main(String[] args) throws Exception {
        var benchmark = new DocumentGraphReadBenchmark();
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        for (String scenario : List.of("ordinary", "current-graph", "upcast-graph")) {
            for (int warmup = 0; warmup < 6; warmup++) {
                benchmark.run(scenario, false);
                benchmark.run(scenario, true);
            }
            for (int round = 0; round < 3; round++) {
                for (boolean candidate : new boolean[]{false, true, true, false}) {
                    long beforeBytes = allocation.getCurrentThreadAllocatedBytes();
                    long beforeTime = System.nanoTime();
                    benchmark.run(scenario, candidate);
                    long elapsed = System.nanoTime() - beforeTime;
                    long bytes = allocation.getCurrentThreadAllocatedBytes() - beforeBytes;
                    System.out.printf(Locale.ROOT, "%s,%s,%d,%.1f,%.1f%n", scenario, candidate ? "candidate" : "baseline", round,
                            (double) elapsed / ITERATIONS, (double) bytes / ITERATIONS);
                }
            }
        }
    }

    private void run(String scenario, boolean candidate) {
        boolean graph = !scenario.equals("ordinary");
        var source = scenario.equals("upcast-graph") ? legacy : current;
        long result = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            DeserializingMessage message = (candidate ? (graph ? graphs : ordinary).read(source, COLLECTION, serializer)
                    : serializer.deserializeMessages(source.stream(), MessageType.DOCUMENT, COLLECTION))
                    .findFirst().orElseThrow();
            if (graph) {
                Graph<?> value = (Graph<?>) resolve.apply(message);
                result += ((Root) value.get()).name().length();
                for (Graph<Child> child : value.children(Child.class)) { result += child.get().value(); }
            } else {
                result += ((Root) message.getPayload()).name().length();
            }
        }
        sink = result;
    }

    private List<SerializedMessage> document(int revision) {
        ObjectNode json = serializer.getObjectMapper().createObjectNode().put("id", "root");
        json.put(revision == 0 ? "oldName" : "name", "Example");
        var children = json.putArray("children");
        List<ModelGraphDocumentManifest.Node> nodes = new ArrayList<>();
        nodes.add(new ModelGraphDocumentManifest.Node("root", 0, 0, revision, -1, -1, 0));
        for (int i = 0; i < 8; i++) {
            children.addObject().put("id", "child-" + i).put("value", i);
            nodes.add(new ModelGraphDocumentManifest.Node("child-" + i, 1, 1, 0, 0, 0, i));
        }
        var manifest = new ModelGraphDocumentManifest(41L, List.of("BenchmarkRoot", "BenchmarkChild"),
                List.of(Root.class.getName(), Child.class.getName()), List.of("children"), nodes);
        return List.of(new SerializedMessage(serializer.serialize(json).withType(Root.class.getName())
                .withRevision(revision), Metadata.of(ModelGraphDocumentManifest.METADATA_KEY, manifest.serialize()),
                "root", 0L));
    }

    @Model(name = "BenchmarkRoot", materializeGraph = true,
            graphProjection = @GraphProjection(collection = COLLECTION))
    @Revision(1)
    record Root(@EntityId String id, String name) {}
    @Model(name = "BenchmarkChild")
    record Child(@EntityId String id, int value) {}
    static class Reader {
        @HandleDocument(modelGraph = Root.class)
        void read(Graph<Root> graph) {}
    }
    static class RootCaster {
        @Upcast(type = "io.fluxzero.sdk.persisting.search.DocumentGraphReadBenchmark$Root", revision = 0)
        JsonNode cast(ObjectNode node) {
            // Deliberately repeatable so the old double-upcast path can serve as a timing baseline.
            node.set("name", node.get("oldName"));
            return node;
        }
    }
}
