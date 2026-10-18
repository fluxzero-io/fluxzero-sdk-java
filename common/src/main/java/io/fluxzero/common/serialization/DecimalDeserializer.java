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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import io.fluxzero.common.Decimal;

import java.io.IOException;

/** Reads exact decimal strings and legacy numeric JSON tokens without a floating-point intermediate. */
public final class DecimalDeserializer extends StdScalarDeserializer<Decimal> {
    public DecimalDeserializer() {
        super(Decimal.class);
    }

    @Override
    public Decimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_STRING) || parser.hasToken(JsonToken.VALUE_NUMBER_INT)
            || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
            String text = parser.getText();
            parser.streamReadConstraints().validateFPLength(text.length());
            try {
                return new Decimal(text);
            } catch (NumberFormatException e) {
                return (Decimal) context.handleWeirdStringValue(Decimal.class, text, "Expected an exact decimal with a valid scale");
            }
        }
        return (Decimal) context.handleUnexpectedToken(Decimal.class, parser);
    }
}
