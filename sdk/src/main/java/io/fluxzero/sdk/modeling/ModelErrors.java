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

import io.fluxzero.sdk.tracking.handling.IllegalCommandException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Customer-facing errors for SDK-provided Model checks. */
public final class ModelErrors {
    private ModelErrors() {}

    /** Existing Entity creation rejection, retained as the same instance. */
    public static final IllegalCommandException ALREADY_EXISTS_EXCEPTION = Entity.ALREADY_EXISTS_EXCEPTION;
    /** Existing Entity lookup rejection, retained as the same instance. */
    public static final IllegalCommandException NOT_FOUND_EXCEPTION = Entity.NOT_FOUND_EXCEPTION;

    /** Required reference is absent. */
    public static IllegalCommandException parentRequired(Class<?> parentType) {
        String name = label(parentType);
        return new IllegalCommandException(name == null ? "A related item is required."
                : article(name) + " " + name + " is required.");
    }

    /** Required referenced parent does not exist in the final mutation state. */
    public static IllegalCommandException parentNotFound(Class<?> parentType) {
        String name = label(parentType);
        return new IllegalCommandException(name == null ? "The related item does not exist."
                : "The " + name + " does not exist.");
    }

    /** Mutation would grow a child scope beyond its capacity. */
    public static IllegalCommandException maxChildrenExceeded(Class<?> parentType, Class<?> childType) {
        String parent = label(parentType), child = label(childType);
        return new IllegalCommandException(parent == null || child == null
                ? "The maximum number of related items has been reached."
                : "Cannot add another " + child + " to this " + parent + ".");
    }

    /** A deletion-protected child survives parent deletion. */
    public static IllegalCommandException parentDeletionBlocked(Class<?> parentType, Class<?> childType) {
        String parent = label(parentType), child = label(childType);
        return new IllegalCommandException(parent == null || child == null
                ? "This item cannot be deleted while dependent items exist."
                : "Cannot delete this " + parent + " while a dependent " + child + " exists.");
    }

    private static String label(Class<?> type) {
        if (type == null || type.getSimpleName().isBlank()) { return null; }
        return Arrays.stream(type.getSimpleName().split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))
                .map(word -> word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT))
                        ? word : word.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private static String article(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("user") || lower.startsWith("uni") || lower.startsWith("euro")) { return "A"; }
        return "aeiou".indexOf(lower.charAt(0)) >= 0 ? "An" : "A";
    }
}
