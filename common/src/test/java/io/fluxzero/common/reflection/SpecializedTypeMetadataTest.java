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

package io.fluxzero.common.reflection;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static io.fluxzero.common.reflection.ReflectionUtils.getTypeMetadata;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpecializedTypeMetadataTest {

    @Test
    void cachesByJavaTypeAndMetadataKind() {
        var metadata = getTypeMetadata(First.class);
        Info first = metadata.specializedMetadata(Info.class, Info::new);

        assertSame(first, metadata.specializedMetadata(Info.class, ignored -> {
            throw new AssertionError("A cached value must not be recomputed");
        }));
        assertSame(First.class, first.type());
        assertSame(Second.class, getTypeMetadata(Second.class).specializedMetadata(Info.class, Info::new).type());
        assertEquals(new OtherInfo(First.class), metadata.specializedMetadata(OtherInfo.class, OtherInfo::new));
    }

    @Test
    void retainsTheFirstPublishedValueDuringReentrantComputation() {
        var metadata = getTypeMetadata(Reentrant.class);
        Info nested = new Info(Reentrant.class);
        Info result = metadata.specializedMetadata(Info.class, type -> {
            assertSame(nested, metadata.specializedMetadata(Info.class, ignored -> nested));
            return new Info(type);
        });

        assertSame(nested, result);
        assertSame(result, metadata.specializedMetadata(Info.class, Info::new));
    }

    @Test
    void doesNotCacheFailedOrNullComputations() {
        var metadata = getTypeMetadata(Failing.class);
        assertThrows(IllegalStateException.class, () -> metadata.specializedMetadata(Info.class, ignored -> {
            throw new IllegalStateException("Failed computation");
        }));
        assertThrows(NullPointerException.class, () -> metadata.specializedMetadata(Info.class, ignored -> null));

        assertSame(Failing.class, metadata.specializedMetadata(Info.class, Info::new).type());
    }

    @Test
    void concurrentColdComputationsPublishOneSharedValue() throws Exception {
        var metadata = getTypeMetadata(Concurrent.class);
        CyclicBarrier computing = new CyclicBarrier(2);
        Function<Class<?>, Info> factory = type -> {
            try {
                computing.await(5, SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new Info(type);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> metadata.specializedMetadata(Info.class, factory));
            var second = executor.submit(() -> metadata.specializedMetadata(Info.class, factory));
            Info shared = first.get(5, SECONDS);

            assertSame(shared, second.get(5, SECONDS));
            assertSame(Concurrent.class, shared.type());
            assertSame(shared, metadata.specializedMetadata(Info.class, Info::new));
        }
    }

    private record Info(Class<?> type) {
    }

    private record OtherInfo(Class<?> type) {
    }

    private static class First {
    }

    private static class Second {
    }

    private static class Reentrant {
    }

    private static class Failing {
    }

    private static class Concurrent {
    }
}
