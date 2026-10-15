/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.test;

import org.junit.jupiter.api.Test;
import io.fluxzero.sdk.configuration.spring.SpringBeanParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BeanParameterResolverTest {

    private final BeanParameterResolver subject = new BeanParameterResolver();

    @Test
    void consumerMetadataOnlyTreatsAutowiredParametersAsBeanInputs() throws Exception {
        for (var resolver : java.util.List.of(subject, new SpringBeanParameterResolver(mock(ApplicationContext.class)))) {
            assertFalse(resolver.mayApply(parameter("ordinary"), null, getClass()));
            assertTrue(resolver.mayApply(parameter("required"), null, getClass()));
            assertTrue(resolver.mayApply(parameter("optional"), null, getClass()));
        }
        assertTrue(new BeanParameterResolver() {}.mayApply(parameter("ordinary"), null, getClass()));
        assertTrue(new SpringBeanParameterResolver(mock(ApplicationContext.class)) {}
                           .mayApply(parameter("ordinary"), null, getClass()));
    }

    @Test
    void returnsNullForMissingOptionalBean() throws Exception {
        assertNull(subject.resolve(parameter("optional"), null).apply(null));
    }

    @Test
    void rejectsMissingRequiredBean() throws Exception {
        assertThrows(IllegalStateException.class,
                     () -> subject.resolve(parameter("required"), null).apply(null));
    }

    private static java.lang.reflect.Parameter parameter(String methodName) throws NoSuchMethodException {
        Method method = BeanParameterResolverTest.class.getDeclaredMethod(methodName, CharSequence.class);
        return method.getParameters()[0];
    }

    @SuppressWarnings("unused")
    private void ordinary(CharSequence value) {}

    @SuppressWarnings("unused")
    private void optional(@Autowired(required = false) CharSequence value) {
    }

    @SuppressWarnings("unused")
    private void required(@Autowired CharSequence value) {
    }
}
