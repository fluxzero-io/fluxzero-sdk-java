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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.AssertCurrent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityHelperAssertCurrentTest {
    @Test void customInvokerWithoutAnnotationStillWorksAndOldOverrideRuns() {
        AtomicInteger intercepted=new AtomicInteger();
        var helper=new DefaultEntityHelper(List.of(),true,false) {
            @Override public Stream<?> intercept(Object value,Entity<?> entity) {
                intercepted.incrementAndGet();return super.intercept(value,entity);
            }
            @Override protected java.util.Optional<HandlerInvoker> getInterceptInvoker(MessageWithEntity message) {
                return java.util.Optional.of(HandlerInvoker.call(()->null));
            }
        };
        helper.interceptForValidation("input", mock(Entity.class),(value,asserted)->fail());
        assertEquals(1,intercepted.get());
    }
    @Test void nestedUncheckedInterceptionDoesNotInheritValidation() {
        AtomicInteger checks=new AtomicInteger();
        var helper=new DefaultEntityHelper(List.of(),true,false) {
            @Override public <E extends Exception> void assertLegal(Object value,Entity<?> entity) {checks.incrementAndGet();}
        };
        Entity<?> entity=mock(Entity.class);
        helper.interceptForValidation(new Outer(helper,entity),entity,(value,asserted)->fail());
        assertEquals(1,checks.get());
        helper.intercept(new Inner(),entity).toList();
        assertEquals(1,checks.get());
    }
    record Outer(DefaultEntityHelper helper,Entity<?> entity) {
        @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept() {
            helper.intercept(new Inner(),entity).toList();return null;
        }
    }
    record Inner() { @InterceptApply(assertCurrent=AssertCurrent.ENABLED) Object intercept(){return null;} }
}
