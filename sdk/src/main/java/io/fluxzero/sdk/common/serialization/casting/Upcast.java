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

package io.fluxzero.sdk.common.serialization.casting;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.Serializer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method that transforms serialized data from one revision to the next during deserialization.
 * <p>
 * An upcaster is selected by the serialized object's {@linkplain #type() type} and {@linkplain #revision() revision}.
 * After it has been applied, the caster chain continues with the resulting type and revision until no matching
 * upcaster remains.
 * </p>
 *
 * <h2>Method signature</h2>
 * <p>An upcaster method may declare zero or more supported parameters in any order:</p>
 * <ul>
 *     <li>the intermediate payload value, such as an {@code ObjectNode};</li>
 *     <li>the corresponding {@link Data};</li>
 *     <li>the source {@link SerializedMessage}, when the input is a message;</li>
 *     <li>the message {@link Metadata}, when the input is a message.</li>
 * </ul>
 * <p>
 * {@code Metadata} may therefore be injected alongside the payload or {@code Data}. Upcasting also applies to
 * non-message data, such as snapshots, key-value entries, and documents. If no message context is available, a
 * {@code Metadata} parameter causes deserialization to fail unless the parameter has an annotation whose simple name
 * is {@code Nullable}. A nullable parameter receives {@code null}; Kotlin nullable parameter types are recognized as
 * well.
 * </p>
 *
 * <h2>Metadata result</h2>
 * <p>
 * Returning {@link Metadata} replaces the complete message metadata while leaving the payload unchanged and
 * advancing its revision by one. Use methods such as {@link Metadata#with(Metadata)} or
 * {@link Metadata#with(Object, Object)} to retain existing entries. The original {@code SerializedMessage} is not
 * mutated.
 * </p>
 * <pre>{@code
 * @Upcast(type = "com.example.UserCreated", revision = 0)
 * Metadata addTenant(Metadata metadata) {
 *     return metadata.with("tenant", "default");
 * }
 * }</pre>
 * <p>
 * A {@code Metadata} return type requires message input, even if a metadata parameter is nullable, because
 * non-message data has nowhere to store the result. Returning {@code null} is not supported.
 * </p>
 *
 * <p>Upcaster methods should be side-effect-free and deterministic.</p>
 *
 * <p>
 * <strong>Spring Integration:</strong> Any Spring bean containing methods annotated with {@code @Upcast}
 * is automatically discovered and registered with the serializer at startup.
 * </p>
 *
 * @see Cast
 * @see Downcast
 * @see Serializer#registerUpcasters(Object...)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Cast(revisionDelta = 1)
@Repeatable(UpcastRepeatable.class)
public @interface Upcast {

    /**
     * The fully qualified type name this upcaster applies to (e.g., the original serialized class name).
     */
    String type();

    /**
     * The revision number of the input type this method handles.
     * <p>
     * This is the revision the method accepts and transforms from.
     */
    int revision();
}
