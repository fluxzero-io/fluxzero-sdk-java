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

import lombok.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders an incubating Fluxzero component registry as a human and agent-readable application blueprint.
 * <p>
 * The blueprint is a pure read model export. It does not compile source files, load classes, run annotation processors,
 * or change Fluxzero dispatch behavior.
 */
public final class ComponentRegistryBlueprint {
    /**
     * Runtime property that enables writing a Markdown blueprint to the configured path.
     */
    public static final String BLUEPRINT_PROPERTY = "fluxzero.registry.blueprint";

    /**
     * Environment variable that enables writing a Markdown blueprint to the configured path.
     */
    public static final String BLUEPRINT_ENV = "FLUXZERO_REGISTRY_BLUEPRINT";

    private static final Pattern QUALIFIED_TYPE_NAME =
            Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+[A-Z_$][\\w$]*(?:\\.[A-Z_$][\\w$]*)*\\b");

    private final ComponentRegistry registry;
    private final String title;

    private ComponentRegistryBlueprint(ComponentRegistry registry, String title) {
        this.registry = registry.normalized();
        this.title = title;
    }

    /**
     * Creates a blueprint renderer for the supplied registry.
     */
    public static ComponentRegistryBlueprint from(@NonNull ComponentRegistry registry) {
        return new ComponentRegistryBlueprint(registry, "Fluxzero App Blueprint");
    }

    /**
     * Returns a copy of this renderer with a custom Markdown title.
     */
    public ComponentRegistryBlueprint withTitle(@NonNull String title) {
        return new ComponentRegistryBlueprint(registry, title);
    }

    /**
     * Renders the registry as Markdown with summary tables and a Mermaid component graph.
     */
    public String toMarkdown() {
        StringBuilder result = new StringBuilder();
        List<RouteRow> routes = routes();

        result.append("# ").append(title).append("\n\n");
        appendGraph(result, routes);
        appendConsumerGraph(result, routes);
        appendSummary(result, routes);
        appendPackages(result);
        appendComponents(result);
        appendRoutes(result, routes);
        appendConsumers(result);
        appendRegisteredTypes(result);
        return result.toString();
    }

    /**
     * Writes the Markdown blueprint to the supplied path.
     */
    public void writeMarkdown(@NonNull Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, toMarkdown(), StandardCharsets.UTF_8);
    }

    private void appendSummary(StringBuilder result, List<RouteRow> routes) {
        result.append("## Summary\n\n");
        appendTable(result,
                    List.of("Metric", "Value"),
                    List.of(
                            List.of("Source root", registry.sourceRoot() == null ? "classpath / mixed"
                                    : relativePath(registry.sourceRoot())),
                            List.of("Packages", Integer.toString(packageRows().size())),
                            List.of("Components", Integer.toString(registry.components().size())),
                            List.of("Handler routes", Integer.toString(routes.size())),
                            List.of("Web routes", Long.toString(routes.stream()
                                    .flatMap(route -> route.route().webRoutes().stream()).count())),
                            List.of("Consumers", Long.toString(consumerCount())),
                            List.of("Registered types", Long.toString(registeredTypeCount()))));
    }

    private void appendGraph(StringBuilder result, List<RouteRow> routes) {
        result.append("## Component Graph\n\n");
        result.append("```mermaid\n");
        result.append("flowchart LR\n");
        result.append("  app[\"Fluxzero app\"]\n");
        List<ComponentDescriptor> components = components();
        for (int i = 0; i < components.size(); i++) {
            ComponentDescriptor component = components.get(i);
            result.append("  component_").append(i).append("[\"")
                    .append(mermaid(component.className())).append("\"]\n");
            result.append("  app --> component_").append(i).append("\n");
        }
        for (int i = 0; i < routes.size(); i++) {
            RouteRow row = routes.get(i);
            int componentIndex = components.indexOf(row.component());
            result.append("  route_").append(i).append("[\"")
                    .append(mermaid(routeLabel(row))).append("\"]\n");
            result.append("  component_").append(componentIndex).append(" --> route_").append(i).append("\n");
        }
        result.append("```\n\n");
    }

    private void appendConsumerGraph(StringBuilder result, List<RouteRow> routes) {
        result.append("## Consumer Graph\n\n");
        result.append("```mermaid\n");
        result.append("flowchart LR\n");
        result.append("  app[\"Fluxzero app\"]\n");
        List<String> consumers = routes.stream().map(this::consumerName).distinct().sorted().toList();
        for (int i = 0; i < consumers.size(); i++) {
            result.append("  consumer_").append(i).append("[\"")
                    .append(mermaid(consumers.get(i))).append("\"]\n");
            result.append("  app --> consumer_").append(i).append("\n");
        }
        for (int i = 0; i < routes.size(); i++) {
            RouteRow row = routes.get(i);
            int consumerIndex = consumers.indexOf(consumerName(row));
            result.append("  component_consumer_").append(i).append("[\"")
                    .append(mermaid(row.component().className())).append("\"]\n");
            result.append("  route_consumer_").append(i).append("[\"")
                    .append(mermaid(routeLabel(row))).append("\"]\n");
            result.append("  consumer_").append(consumerIndex).append(" --> component_consumer_").append(i).append("\n");
            result.append("  component_consumer_").append(i).append(" --> route_consumer_").append(i).append("\n");
        }
        result.append("```\n\n");
    }

    private void appendPackages(StringBuilder result) {
        result.append("## Packages\n\n");
        appendTable(result,
                    List.of("Package", "Capabilities", "Consumer", "Registered types", "Source"),
                    packageRows().stream()
                            .map(p -> List.of(
                                    packageName(p.packageName()),
                                    join(p.capabilities()),
                                    p.consumer() == null ? "" : p.consumer().name(),
                                    registeredTypes(p.registeredTypes()),
                                    path(p.sourceFile())))
                            .toList());
    }

    private void appendComponents(StringBuilder result) {
        result.append("## Components\n\n");
        appendTable(result,
                    List.of("Component", "Package", "Kind", "Capabilities", "Consumer", "Routes", "Source"),
                    components().stream()
                            .map(c -> List.of(
                                    c.className(),
                                    packageName(c.packageName()),
                                    c.componentKind().name(),
                                    join(c.capabilities()),
                                    c.consumerMetadata().map(ConsumerDescriptor::name).orElse(""),
                                    Integer.toString(c.handlerRoutes().size()),
                                    path(c.sourceFile())))
                            .toList());
    }

    private void appendRoutes(StringBuilder result, List<RouteRow> routes) {
        result.append("## Handler Routes\n\n");
        appendTable(result,
                    List.of("Message", "Component", "Executable", "Payloads", "Allowed classes",
                            "Dispatch", "Flags", "Web"),
                    routes.stream()
                            .map(row -> List.of(
                                    row.route().messageType().name(),
                                    row.component().className(),
                                    executable(row.route()),
                                    joinSimple(row.route().payloadTypeNames()),
                                    joinSimple(row.route().allowedClassNames()),
                                    dispatch(row.route()),
                                    flags(row.route()),
                                    web(row.route())))
                            .toList());
    }

    private void appendConsumers(StringBuilder result) {
        result.append("## Consumers\n\n");
        List<List<String>> rows = new ArrayList<>();
        packageRows().stream()
                .filter(p -> p.consumer() != null)
                .forEach(p -> rows.add(List.of("package", p.packageName(),
                                               p.consumer().name(),
                                               attributes(p.consumer().attributes()))));
        components().stream()
                .filter(c -> c.consumerMetadata().isPresent())
                .forEach(c -> rows.add(List.of("component", c.className(),
                                               c.consumerMetadata().orElseThrow().name(),
                                               attributes(c.consumerMetadata().orElseThrow().attributes()))));
        appendTable(result, List.of("Scope", "Owner", "Consumer", "Attributes"), rows);
    }

    private void appendRegisteredTypes(StringBuilder result) {
        result.append("## Registered Types\n\n");
        List<List<String>> rows = new ArrayList<>();
        packageRows().forEach(p -> p.registeredTypes().forEach(registeredType -> rows.add(
                registeredTypeRow("package", p.packageName(), registeredType))));
        components().forEach(c -> c.registeredTypes().forEach(registeredType -> rows.add(
                registeredTypeRow("component", c.className(), registeredType))));
        appendTable(result, List.of("Scope", "Owner", "Root", "Contains", "Candidates"), rows);
    }

    private static List<String> registeredTypeRow(String scope, String owner,
                                                  RegisteredTypeDescriptor registeredType) {
        return List.of(scope, owner, simpleTypeNames(registeredType.root()), join(registeredType.contains()),
                       joinSimple(registeredType.candidateTypeNames()));
    }

    private List<PackageRow> packageRows() {
        Map<String, PackageRow> result = new LinkedHashMap<>();
        registry.packages().stream()
                .sorted(Comparator.comparing(PackageDescriptor::packageName))
                .forEach(p -> result.put(p.packageName(), new PackageRow(
                        p.packageName(), p.sourceFile(), p.capabilities(), p.consumer(), p.registeredTypes())));
        components().stream().map(ComponentDescriptor::packageName).distinct().sorted()
                .forEach(packageName -> result.putIfAbsent(packageName, new PackageRow(
                        packageName, null, Set.of(), null, List.of())));
        return List.copyOf(result.values());
    }

    private List<ComponentDescriptor> components() {
        return registry.components().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::fullClassName))
                .toList();
    }

    private List<RouteRow> routes() {
        return components().stream()
                .flatMap(component -> component.routes().stream().map(route -> new RouteRow(component, route)))
                .sorted(Comparator.comparing((RouteRow row) -> row.component().fullClassName())
                        .thenComparing(row -> row.route().messageType().ordinal())
                        .thenComparing(row -> executable(row.route()))
                        .thenComparing(row -> join(row.route().payloadTypeNames())))
                .toList();
    }

    private long consumerCount() {
        return Stream.concat(
                packageRows().stream().filter(p -> p.consumer() != null),
                registry.components().stream().filter(c -> c.consumerMetadata().isPresent()))
                .count();
    }

    private long registeredTypeCount() {
        return packageRows().stream().mapToLong(p -> p.registeredTypes().size()).sum()
               + registeredTypeCount(registry.components());
    }

    private static long registeredTypeCount(Collection<?> descriptors) {
        return descriptors.stream()
                .mapToLong(descriptor -> {
                    if (descriptor instanceof PackageDescriptor packageDescriptor) {
                        return packageDescriptor.registeredTypes().size();
                    }
                    if (descriptor instanceof ComponentDescriptor componentDescriptor) {
                        return componentDescriptor.registeredTypes().size();
                    }
                    return 0;
                })
                .sum();
    }

    private static void appendTable(StringBuilder result, List<String> headings, List<List<String>> rows) {
        result.append("| ").append(String.join(" | ", headings)).append(" |\n");
        result.append("| ").append(String.join(" | ", headings.stream().map(ignored -> "---").toList()))
                .append(" |\n");
        if (rows.isEmpty()) {
            List<String> emptyRow = headings.stream().map(ignored -> "").collect(java.util.stream.Collectors.toList());
            emptyRow.set(0, "_none_");
            result.append("| ").append(String.join(" | ", emptyRow)).append(" |\n\n");
            return;
        }
        for (List<String> row : rows) {
            result.append("| ");
            for (int i = 0; i < headings.size(); i++) {
                if (i > 0) {
                    result.append(" | ");
                }
                result.append(markdown(i < row.size() ? row.get(i) : ""));
            }
            result.append(" |\n");
        }
        result.append("\n");
    }

    private static String executable(HandlerRoute route) {
        return route.executableMetadata().map(executable -> executable.name() + "("
                + executable.parameters().stream()
                        .map(parameter -> parameter.name() + ": " + simpleTypeNames(parameter.typeName()))
                        .reduce((left, right) -> left + ", " + right).orElse("")
                + ") -> " + simpleTypeNames(executable.returnTypeName())).orElse("");
    }

    private String routeLabel(RouteRow row) {
        HandlerRoute route = row.route();
        String payload = route.payloadTypeNames().stream().findFirst()
                .orElse(route.webRoutes().stream().findFirst()
                                .map(webRoute -> String.join(", ", webRoute.methods()) + " "
                                                 + String.join(", ", webRoute.paths()))
                                .orElse(""));
        String status = route.disabled() ? "disabled" : route.local() && route.tracked() ? "local + tracked"
                : route.local() ? "local" : route.tracked() ? "tracked" : "unrouted";
        return route.messageType().name() + "<br/>" + simpleTypeNames(payload) + "<br/>"
               + status + "<br/>" + consumerName(row);
    }

    private String consumerName(RouteRow row) {
        return row.component().consumerMetadata().map(ConsumerDescriptor::name)
                .filter(name -> !name.isBlank())
                .orElse("default consumer");
    }

    private static String dispatch(HandlerRoute route) {
        if (route.local() && route.tracked()) {
            return "local, tracked";
        }
        if (route.local()) {
            return "local";
        }
        if (route.tracked()) {
            return "tracked";
        }
        return "";
    }

    private static String flags(HandlerRoute route) {
        List<String> flags = new ArrayList<>();
        if (route.disabled()) {
            flags.add("disabled");
        }
        if (route.passive()) {
            flags.add("passive");
        }
        if (route.skipExpiredRequests()) {
            flags.add("skipExpiredRequests");
        }
        return String.join(", ", flags);
    }

    private static String web(HandlerRoute route) {
        return route.webRoutes().stream()
                .map(webRoute -> String.join(",", webRoute.methods()) + " " + String.join(", ", webRoute.paths())
                                 + (webRoute.autoHead() ? " autoHead" : "")
                                 + (webRoute.autoOptions() ? " autoOptions" : ""))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private static String registeredTypes(List<RegisteredTypeDescriptor> registeredTypes) {
        return registeredTypes.stream().map(registeredType -> simpleTypeNames(registeredType.root()))
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String attributes(java.util.Map<String, List<String>> attributes) {
        return attributes.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + join(entry.getValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String path(Path path) {
        return path == null ? "" : relativePath(path);
    }

    private String relativePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (registry.sourceRoot() != null) {
            Path sourceRoot = registry.sourceRoot().toAbsolutePath().normalize();
            if (!absolute.equals(sourceRoot) && absolute.startsWith(sourceRoot)) {
                return sourceRoot.relativize(absolute).toString();
            }
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (absolute.startsWith(cwd)) {
            return cwd.relativize(absolute).toString();
        }
        return path.toString();
    }

    private static String packageName(String packageName) {
        return packageName == null || packageName.isBlank() ? "(default package)" : packageName;
    }

    private static String join(Set<?> values) {
        return join(values.stream().map(Objects::toString).sorted().toList());
    }

    private static String joinSimple(Set<?> values) {
        return joinSimple(values.stream().map(Objects::toString).sorted().toList());
    }

    private static String joinSimple(List<?> values) {
        return values.stream().map(value -> simpleTypeNames(Objects.toString(value)))
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String join(List<?> values) {
        return values.stream().map(Objects::toString)
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String simpleTypeNames(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = QUALIFIED_TYPE_NAME.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(simpleName(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String simpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(index + 1);
    }

    private static String markdown(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", "<br/>");
    }

    private static String mermaid(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", "<br/>");
    }

    private record RouteRow(ComponentDescriptor component, HandlerRoute route) {
    }

    private record PackageRow(
            String packageName,
            Path sourceFile,
            Set<ComponentCapability> capabilities,
            ConsumerDescriptor consumer,
            List<RegisteredTypeDescriptor> registeredTypes) {
    }
}
