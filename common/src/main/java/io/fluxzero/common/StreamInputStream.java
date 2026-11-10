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

package io.fluxzero.common;

import java.io.OutputStream;
import java.util.stream.Stream;

/**
 * Compatibility name for {@link SerializingInputStream}.
 *
 * @deprecated use {@link SerializingInputStream} instead
 */
@Deprecated
public class StreamInputStream<T> extends SerializingInputStream<T> {
    /**
     * Creates an input stream that serializes each source element with the supplied writer.
     *
     * @param stream the source elements; must not be null
     * @param writer the element serializer; must not be null
     */
    public StreamInputStream(Stream<? extends T> stream,
                             ThrowingBiConsumer<? super T, ? super OutputStream> writer) {
        super(stream, writer);
    }
}
