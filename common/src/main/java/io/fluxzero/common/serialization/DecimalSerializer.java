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

package io.fluxzero.common.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import io.fluxzero.common.Decimal;

import java.io.IOException;

/** Writes decimals exactly as strings in JSON and as numbers in the document index. */
public final class DecimalSerializer extends StdScalarSerializer<Decimal> {
    /** Internal serialization context set by the document inverter; never changes an ObjectMapper globally. */
    public static final String NUMERIC_DOCUMENT = DecimalSerializer.class.getName() + ".numericDocument";

    public DecimalSerializer() {
        super(Decimal.class);
    }

    @Override
    public void serialize(Decimal value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        if (Boolean.TRUE.equals(provider.getAttribute(NUMERIC_DOCUMENT))) {
            generator.writeNumber(value.toBigDecimal());
        } else {
            generator.writeString(value.toString());
        }
    }
}
