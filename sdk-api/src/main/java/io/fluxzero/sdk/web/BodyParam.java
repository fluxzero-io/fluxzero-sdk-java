/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
package io.fluxzero.sdk.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a field from a JSON request body into a handler method parameter.
 * <p>
 * If no explicit field name is provided, the method parameter name is used.
 * Nested JSON properties may be addressed using dot or slash notation.
 * </p>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * @HandlePost("/bookings")
 * BookingId createBooking(@BodyParam HotelId hotelId,
 *                         @BodyParam RoomId roomId,
 *                         @BodyParam BookingDetails details) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@WebParam(type = WebParameterSource.BODY)
public @interface BodyParam {
    /**
     * Body field name. If left empty, it defaults to the method parameter's name.
     */
    String value() default "";
}
