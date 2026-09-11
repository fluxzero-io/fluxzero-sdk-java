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
package io.fluxzero.common.modeling;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Indexes {@code @Model} declarations independently of serialization aliases or handler registration.
 * Contributing modules must enable this processor and preserve their Model index when packaged.
 * In explicit processor lists, run this observer before processors that exclusively claim annotations.
 */
@SupportedAnnotationTypes("*")
public class ModelTypeProcessor extends AbstractProcessor {
    /** Resource containing the binary names of Model declarations in a compiled module. */
    public static final String TYPES_FILE = "META-INF/io.fluxzero.sdk.modeling.Model";
    private static final String MODEL_ANNOTATION = "io.fluxzero.sdk.modeling.Model";

    // Both common and SDK provide a local service entry before their claiming processors. The first observer owns
    // the complete compilation; subsequent providers must not write the same index again. Weak keys keep this state
    // tied to the compiler invocation, without retaining its environment through a processor instance.
    private static final WeakHashMap<ProcessingEnvironment, Boolean> compilations = new WeakHashMap<>();
    private boolean owner;

    private final Set<String> types = new TreeSet<>();
    private final Set<String> visited = new TreeSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        synchronized (compilations) {
            owner = compilations.putIfAbsent(processingEnv, Boolean.TRUE) == null;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!owner) {
            return false;
        }
        roundEnv.getRootElements().forEach(this::visit);
        if (roundEnv.processingOver() && !roundEnv.errorRaised()) {
            try {
                mergePrevious();
                if (!types.isEmpty() || !visited.isEmpty()) {
                    try (var writer = processingEnv.getFiler().createResource(
                            StandardLocation.CLASS_OUTPUT, "", TYPES_FILE).openWriter()) {
                        for (String type : types) {
                            writer.write(type + "\n");
                        }
                    }
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                        "Could not write Model type index: " + e.getMessage());
            }
        }
        // Also run when a previously indexed source loses @Model; never claim other processors' annotations.
        return false;
    }

    private void visit(Element element) {
        if (element instanceof TypeElement type) {
            String name = processingEnv.getElementUtils().getBinaryName(type).toString();
            visited.add(name);
            if (isIndexedModel(type)) {
                types.add(name);
            }
        }
        element.getEnclosedElements().stream().filter(TypeElement.class::isInstance).forEach(this::visit);
    }

    private boolean isIndexedModel(TypeElement type) {
        return (type.getKind().isClass() || type.getKind().isInterface())
               && isModel(type, new TreeSet<>());
    }

    private boolean isModel(TypeElement type, Set<String> visited) {
        if (!visited.add(type.getQualifiedName().toString())) {
            return false;
        }
        if (processingEnv.getElementUtils().getAllAnnotationMirrors(type).stream()
                .anyMatch(annotation -> annotation.getAnnotationType().toString().equals(MODEL_ANNOTATION))) {
            return true;
        }
        // Runtime TypeMetadata also inherits annotations from implemented interfaces, not just superclasses.
        return processingEnv.getTypeUtils().directSupertypes(type.asType()).stream()
                .filter(DeclaredType.class::isInstance).map(DeclaredType.class::cast)
                .map(parent -> (TypeElement) parent.asElement()).anyMatch(parent -> isModel(parent, visited));
    }

    private void mergePrevious() throws IOException {
        try (var reader = new BufferedReader(processingEnv.getFiler()
                                                    .getResource(StandardLocation.CLASS_OUTPUT, "", TYPES_FILE)
                                                    .openReader(true))) {
            for (String name; (name = reader.readLine()) != null; ) {
                if (!name.isBlank() && !visited.contains(name)) {
                    TypeElement type = processingEnv.getElementUtils().getTypeElement(name.replace('$', '.'));
                    if (type != null && isIndexedModel(type)) {
                        types.add(name);
                    }
                }
            }
        } catch (FileNotFoundException | NoSuchFileException ignored) {
            // The first compilation has no previous index.
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }
}
