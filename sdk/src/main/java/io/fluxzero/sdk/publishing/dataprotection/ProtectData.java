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

package io.fluxzero.sdk.publishing.dataprotection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or type within a message payload as containing sensitive information that should be protected.
 * <p>
 * When a field is annotated with {@code @ProtectData}, Fluxzero does <strong>not</strong> always protect the entire
 * object graph rooted at that field. Instead, it applies the following rules:
 * <p>
 * 1) The field value is protected as a whole if the value is a leaf value as determined by
 * {@link io.fluxzero.common.reflection.ReflectionUtils#isLeafValue(Object)}, a
 * {@link com.fasterxml.jackson.databind.JsonNode}, a {@link io.fluxzero.common.api.Data}, an {@link Iterable}, a
 * {@link java.util.Map}, or a type annotated with {@code @ProtectData}.
 * <p>
 * 2) Otherwise, Fluxzero only traverses into nested properties that are themselves explicitly annotated with
 * {@code @ProtectData}.
 * <p>
 * 3) For nested paths, every property in the path must therefore be explicitly annotated with {@code @ProtectData}.
 * If any intermediate property is not annotated, traversal stops at that point and nested values below it are not
 * protected.
 * <p>
 * This makes the behavior explicit and opt-in: sensitive nested values are only protected when each step in the path
 * is marked for protection, while scalar or container-like values are offloaded as a single protected value.
 * <p>
 * When a message is later deserialized and passed to a handler, Fluxzero will automatically reinject the protected
 * information into the payload prior to invoking the handler method.
 * <p>
 * To permanently remove protected data after it is no longer needed, consider using the {@link DropProtectedData}
 * annotation on a handler method.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public record RegisterCitizen(
 *     String name,
 *     @ProtectData String socialSecurityNumber
 * ) {
 * }
 * }</pre>
 *
 * <h2>Nested Example</h2>
 * <pre>{@code
 * public record RegisterCitizen(
 *     @ProtectData SensitiveDetails details
 * ) {
 * }
 *
 * public record SensitiveDetails(
 *     @ProtectData String socialSecurityNumber,
 *     String displayName
 * ) {
 * }
 * }</pre>
 *
 * In this example, {@code details/socialSecurityNumber} is protected, while {@code details/displayName} remains part
 * of the regular payload because it is not annotated.
 *
 * @see DropProtectedData
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
public @interface ProtectData {
}
