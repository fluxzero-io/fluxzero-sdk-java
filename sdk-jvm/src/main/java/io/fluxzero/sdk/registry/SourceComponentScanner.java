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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Incubating scanner for local Java source files.
 * <p>
 * Filesystem concerns stay in the JVM module; source-text discovery lives in the browser-safe
 * {@link SourceComponentTextScanner}.
 */
public class SourceComponentScanner extends SourceComponentTextScanner {

    /**
     * Scans the source root and returns indexed component metadata.
     */
    public ComponentRegistry scan(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new ComponentRegistryException(
                    "Component source root does not exist or is not a directory: " + sourceRoot);
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            Map<String, String> sources = new LinkedHashMap<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                sources.put(file.toString(), Files.readString(file));
            }
            return scanSources(sourceRoot.toString(), sources);
        } catch (Exception e) {
            if (e instanceof ComponentRegistryException componentRegistryException) {
                throw componentRegistryException;
            }
            throw new ComponentRegistryException("Failed to index component source root: " + sourceRoot, e);
        }
    }

    /**
     * Scans the source root, writes the registry JSON artifact, and returns the indexed registry.
     */
    public ComponentRegistry writeJson(Path sourceRoot, Path output) {
        ComponentRegistry registry = scan(sourceRoot);
        ComponentRegistryJson.write(registry, output);
        return registry;
    }
}
