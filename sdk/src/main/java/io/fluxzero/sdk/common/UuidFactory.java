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

package io.fluxzero.sdk.common;

import lombok.AllArgsConstructor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link IdentityProvider} that generates random UUIDs.
 * <p>
 * Optionally removes dashes from the UUID to produce a more compact identifier.
 * </p>
 *
 * <h2>Example Output</h2>
 * <ul>
 *   <li>{@code 61f3c6d26c9c42d3b56b4c0a7e34c939} (if {@code removeDashes = true})</li>
 *   <li>{@code 61f3c6d2-6c9c-42d3-b56b-4c0a7e34c939} (if {@code removeDashes = false})</li>
 * </ul>
 */
@AllArgsConstructor
public class UuidFactory implements IdentityProvider {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final long UUID_VERSION_MASK = 0xffff_ffff_ffff_0fffL;
    private static final long UUID_VERSION_4 = 0x0000_0000_0000_4000L;
    private static final long UUID_VARIANT_2 = 0x8000_0000_0000_0000L;
    private static final long UUID_SEQUENCE_MASK = 0x3fff_ffff_ffff_ffffL;
    private static final UUID technicalSeed = UUID.randomUUID();
    private static final long technicalPrefix =
            technicalSeed.getMostSignificantBits() & UUID_VERSION_MASK
            | UUID_VERSION_4;
    private static final AtomicLong technicalSequence =
            new AtomicLong(
                    technicalSeed.getLeastSignificantBits()
                    & UUID_SEQUENCE_MASK);

    /**
     * Whether to remove dashes from generated UUIDs.
     */
    private final boolean removeDashes;

    /**
     * Creates a {@code UuidFactory} that removes dashes (default behavior).
     */
    public UuidFactory() {
        this(true);
    }

    /**
     * Returns a new UUID string, optionally stripped of dashes.
     *
     * @return a unique identifier string
     */
    @Override
    public String nextFunctionalId() {
        UUID id = UUID.randomUUID();
        return removeDashes ? compact(id.getMostSignificantBits(), id.getLeastSignificantBits()) : id.toString();
    }

    /**
     * Returns a process-unique UUID without consulting the secure random source for every technical message.
     *
     * <p>A securely generated process prefix is combined with a 62-bit atomic sequence. The resulting value retains
     * the UUID version-4 and IETF variant bits and cannot repeat within one process before the sequence space is
     * exhausted. Functional IDs remain independently random through {@link #nextFunctionalId()}.</p>
     */
    @Override
    public String nextTechnicalId() {
        long leastSignificantBits = UUID_VARIANT_2
                                    | technicalSequence.getAndIncrement()
                                      & UUID_SEQUENCE_MASK;
        return removeDashes
                ? compact(technicalPrefix, leastSignificantBits)
                : new UUID(technicalPrefix, leastSignificantBits).toString();
    }

    @Override
    public String idForName(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        return removeDashes ? compact(id.getMostSignificantBits(), id.getLeastSignificantBits()) : id.toString();
    }

    private static String compact(long mostSignificantBits, long leastSignificantBits) {
        char[] result = new char[32];
        writeHex(mostSignificantBits, result, 0);
        writeHex(leastSignificantBits, result, 16);
        return new String(result);
    }

    private static void writeHex(long value, char[] target, int offset) {
        for (int index = 15; index >= 0; index--) {
            target[offset + index] = HEX_DIGITS[(int) value & 0xf];
            value >>>= 4;
        }
    }
}
