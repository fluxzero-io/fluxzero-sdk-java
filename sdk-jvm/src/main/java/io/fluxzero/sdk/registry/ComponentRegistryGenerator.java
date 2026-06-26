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

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Build-time source producer for the incubating Fluxzero component registry.
 * <p>
 * The generator scans Java source text only. It does not invoke javac, load application classes, or run annotation
 * processors. This makes it suitable for {@code src/main/fluxzero} and {@code src/test/fluxzero} sources that should
 * remain outside the normal application compilation path while still producing a registry artifact that runtime and
 * tooling can consume.
 */
public final class ComponentRegistryGenerator {
    /**
     * Default source root for on-demand Fluxzero source units.
     */
    public static final Path DEFAULT_SOURCE_ROOT = Path.of("src/main/fluxzero");

    /**
     * Default test source root for on-demand Fluxzero source units.
     */
    public static final Path DEFAULT_TEST_SOURCE_ROOT = Path.of("src/test/fluxzero");

    /**
     * Default class-output artifact path.
     */
    public static final Path DEFAULT_OUTPUT = Path.of("target/classes")
            .resolve(ComponentRegistryJson.DEFAULT_RESOURCE);

    /**
     * Default test class-output artifact path.
     */
    public static final Path DEFAULT_TEST_OUTPUT = Path.of("target/test-classes")
            .resolve(ComponentRegistryJson.DEFAULT_RESOURCE);

    private static final String DEFAULT_TITLE = "Fluxzero Source Registry";

    private ComponentRegistryGenerator() {
    }

    /**
     * Generates the registry JSON artifact and optionally a Markdown blueprint.
     * <p>
     * Missing source roots are treated as an empty registry. Use {@link #generate(Path, Path, Path, String, boolean)}
     * with {@code strict=true} when build tooling should fail if the source root is absent.
     *
     * @param sourceRoot source root to scan
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @return the generated registry, or an empty registry when the source root is absent
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, @NonNull Path output, Path blueprintOutput, String blueprintTitle) {
        return generate(sourceRoot, output, blueprintOutput, blueprintTitle, false);
    }

    /**
     * Generates the registry JSON artifact and optionally a Markdown blueprint.
     *
     * @param sourceRoot source root to scan
     * @param output registry JSON output path
     * @param blueprintOutput optional Markdown blueprint output path, or {@code null}
     * @param blueprintTitle Markdown title used when a blueprint output path is supplied
     * @param strict whether a missing source root should fail generation
     * @return the generated registry, or an empty registry when the source root is absent and strict mode is disabled
     */
    public static ComponentRegistry generate(
            @NonNull Path sourceRoot, @NonNull Path output, Path blueprintOutput,
            String blueprintTitle, boolean strict) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(output, "output");
        if (!Files.isDirectory(sourceRoot)) {
            if (strict) {
                throw new ComponentRegistryException(
                        "Component source root does not exist or is not a directory: " + sourceRoot);
            }
            return ComponentRegistry.empty();
        }
        ComponentRegistry registry = new SourceComponentScanner().scan(sourceRoot).normalized();
        ComponentRegistryJson.write(registry, output);
        if (blueprintOutput != null) {
            try {
                ComponentRegistryBlueprint.from(registry)
                        .withTitle(blueprintTitle == null || blueprintTitle.isBlank() ? DEFAULT_TITLE : blueprintTitle)
                        .writeMarkdown(blueprintOutput);
            } catch (Exception e) {
                throw new ComponentRegistryException(
                        "Failed to write Fluxzero component registry blueprint: " + blueprintOutput, e);
            }
        }
        return registry;
    }

    /**
     * Command-line entrypoint for build tools and local agent workflows.
     */
    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path sourceRoot = null;
        Path output = null;
        Path blueprint = null;
        String title = DEFAULT_TITLE;
        boolean strict = false;
        boolean testSources = false;

        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--source-root" -> sourceRoot = Path.of(value(args, ++i, arg));
                    case "--output" -> output = Path.of(value(args, ++i, arg));
                    case "--blueprint" -> blueprint = Path.of(value(args, ++i, arg));
                    case "--title" -> title = value(args, ++i, arg);
                    case "--strict" -> strict = true;
                    case "--test" -> testSources = true;
                    case "--help", "-h" -> {
                        out.println(usage());
                        return 0;
                    }
                    default -> {
                        err.println("Unknown argument: " + arg);
                        err.println(usage());
                        return 2;
                    }
                }
            }
            sourceRoot = sourceRoot == null
                    ? testSources ? DEFAULT_TEST_SOURCE_ROOT : DEFAULT_SOURCE_ROOT : sourceRoot;
            output = output == null
                    ? testSources ? DEFAULT_TEST_OUTPUT : DEFAULT_OUTPUT : output;

            ComponentRegistry registry = generate(sourceRoot, output, blueprint, title, strict);
            if (registry.isEmpty() && !Files.isDirectory(sourceRoot)) {
                out.println("Fluxzero component registry skipped; source root does not exist: " + sourceRoot);
            } else {
                out.println("Fluxzero component registry written: " + output);
                if (blueprint != null) {
                    out.println("Fluxzero component blueprint written: " + blueprint);
                }
            }
            return 0;
        } catch (Exception e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static String usage() {
        return """
                Usage: java io.fluxzero.sdk.registry.ComponentRegistryGenerator [options]

                Options:
                  --source-root <path>   Source root to scan. Defaults to src/main/fluxzero.
                  --output <path>        Registry JSON output. Defaults to target/classes/%s.
                  --test                 Use test defaults: src/test/fluxzero and target/test-classes/%s.
                  --blueprint <path>     Optional Markdown blueprint output.
                  --title <title>        Optional blueprint title.
                  --strict               Fail when the source root is absent.
                  --help                 Show this help.
                """.formatted(ComponentRegistryJson.DEFAULT_RESOURCE, ComponentRegistryJson.DEFAULT_RESOURCE);
    }
}
