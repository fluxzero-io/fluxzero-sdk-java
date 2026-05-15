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
 *
 */

package io.fluxzero.sdk.tracking.handling.validation.jakarta;

import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.StreamSupport;

import static jakarta.validation.ElementKind.CONTAINER_ELEMENT;

record ValidationPath(List<PathNode> nodes, @Nullable IterableLocation iterableLocation) implements Path {
    static ValidationPath root() {
        return new ValidationPath(List.of(), null);
    }

    static String propertyPath(ConstraintViolation<?> violation, boolean full) {
        if (full) {
            return violation.getPropertyPath().toString();
        }
        List<Path.Node> path = StreamSupport.stream(violation.getPropertyPath().spliterator(), false).toList();
        path = path.stream().skip(Math.max(0, path.size() - 2)).toList();
        if (path.isEmpty()) {
            return violation.getPropertyPath().toString();
        }
        if (path.size() == 2) {
            Path.Node a = path.get(0), b = path.get(1);
            if (a.getKind() == ElementKind.RETURN_VALUE && b.getKind() != CONTAINER_ELEMENT) {
                return b.getName();
            }
            if (b.getKind() == ElementKind.RETURN_VALUE) {
                return "return value";
            }
            if (a.getKind() == CONTAINER_ELEMENT && b.getKind() != CONTAINER_ELEMENT) {
                return b.getName();
            }
            return b.isInIterable() && b.getKind() != CONTAINER_ELEMENT ? b.getName() :
                    String.format("%s %s", a.getName(), b.getKind() == CONTAINER_ELEMENT ? "element" : b.getName());
        }
        return path.get(0).getName();
    }

    ValidationPath appendProperty(String name) {
        return append(new PathNode(name, ElementKind.PROPERTY, iterableLocation));
    }

    ValidationPath appendExecutable(String name, ElementKind kind) {
        return append(new PathNode(name, kind, null));
    }

    ValidationPath appendParameter(String name, int index) {
        return append(new PathNode(name, ElementKind.PARAMETER, iterableLocation, index));
    }

    ValidationPath appendCrossParameter() {
        return append(new PathNode("<cross-parameter>", ElementKind.CROSS_PARAMETER, null));
    }

    ValidationPath appendReturnValue() {
        return append(new PathNode("<return value>", ElementKind.RETURN_VALUE, null));
    }

    ValidationPath appendBean() {
        return append(new PathNode(null, ElementKind.BEAN, iterableLocation));
    }

    ValidationPath appendContainerElement(String name, @Nullable Integer index, @Nullable Object key) {
        return append(new PathNode(name, ElementKind.CONTAINER_ELEMENT, new IterableLocation(index, key)));
    }

    ValidationPath appendContainerValue(String name) {
        return append(new PathNode(name, ElementKind.CONTAINER_ELEMENT, null));
    }

    ValidationPath iterableElement(@Nullable Integer index, @Nullable Object key) {
        return new ValidationPath(nodes, new IterableLocation(index, key));
    }

    private ValidationPath append(PathNode node) {
        if (nodes.isEmpty()) {
            return new ValidationPath(List.of(node), null);
        }
        if (nodes.size() == 1) {
            return new ValidationPath(List.of(nodes.getFirst(), node), null);
        }
        List<PathNode> result = new ArrayList<>(nodes);
        result.add(node);
        return new ValidationPath(List.copyOf(result), null);
    }

    /** {@inheritDoc} */
    @Override
    public Iterator<Path.Node> iterator() {
        return Collections.unmodifiableList(new ArrayList<Path.Node>(nodes)).iterator();
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (PathNode node : nodes) {
            if (node.kind() == ElementKind.BEAN) {
                continue;
            }
            if (!result.isEmpty() && node.iterableLocation() == null) {
                result.append('.');
            }
            if (node.iterableLocation() != null && !result.isEmpty()) {
                appendIterable(result, node.iterableLocation());
                if (node.name() != null && node.kind() != ElementKind.CONTAINER_ELEMENT) {
                    result.append('.');
                }
            }
            if (node.name() != null) {
                result.append(node.name());
            }
            if (node.iterableLocation() != null && result.isEmpty()) {
                appendIterable(result, node.iterableLocation());
            }
        }
        return result.toString();
    }

    private static void appendIterable(StringBuilder result, IterableLocation location) {
        if (location.index() != null) {
            result.append('[').append(location.index()).append(']');
        } else if (location.key() != null) {
            result.append('[').append(location.key()).append(']');
        } else {
            result.append("[]");
        }
    }

record IterableLocation(@Nullable Integer index, @Nullable Object key) {
}

record PathNode(@Nullable String name, ElementKind kind, @Nullable IterableLocation iterableLocation,
                @Nullable Integer parameterIndex)
        implements Path.PropertyNode, Path.ContainerElementNode, Path.ParameterNode, Path.BeanNode,
        Path.MethodNode, Path.ConstructorNode, Path.CrossParameterNode, Path.ReturnValueNode {
    PathNode(@Nullable String name, ElementKind kind, @Nullable IterableLocation iterableLocation) {
        this(name, kind, iterableLocation, null);
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return name;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInIterable() {
        return iterableLocation != null;
    }

    /** {@inheritDoc} */
    @Override
    public Integer getIndex() {
        return iterableLocation == null ? null : iterableLocation.index();
    }

    /** {@inheritDoc} */
    @Override
    public Object getKey() {
        return iterableLocation == null ? null : iterableLocation.key();
    }

    /** {@inheritDoc} */
    @Override
    public ElementKind getKind() {
        return kind;
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Path.Node> T as(Class<T> nodeType) {
        if (nodeType.isInstance(this)) {
            return (T) this;
        }
        throw new IllegalArgumentException("Unsupported node type " + nodeType.getName());
    }

    /** {@inheritDoc} */
    @Override
    public Class<?> getContainerClass() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public Integer getTypeArgumentIndex() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public int getParameterIndex() {
        return parameterIndex == null ? -1 : parameterIndex;
    }

    /** {@inheritDoc} */
    @Override
    public List<Class<?>> getParameterTypes() {
        return List.of();
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
}
