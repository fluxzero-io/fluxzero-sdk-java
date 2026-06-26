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

package io.fluxzero.sdk.configuration.spring;

import lombok.SneakyThrows;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.springframework.util.ClassUtils.forName;

/**
 * {@link Conditional} that only matches when a bean of the specified type is <em>not</em> present in the context.
 * <p>
 * This is commonly used to allow user-defined overrides of default beans.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @ConditionalOnMissingBean
 * @Bean
 * public MyFallbackService myService() {
 *     return new MyFallbackService();
 * }
 * }</pre>
 *
 * If no type is specified, the return type of the method is used.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnMissingBean.Condition.class)
public @interface ConditionalOnMissingBean {

    Class<?> value() default void.class;

    @SuppressWarnings({"NullableProblems", "ConstantConditions"})
    @Order
    class Condition implements org.springframework.context.annotation.Condition {
        @Override
        @SneakyThrows
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Class<?> type = (Class<?>) metadata.getAnnotationAttributes(ConditionalOnMissingBean.class.getName()).get("value");

            if (void.class.equals(type)) {
                type = switch (metadata) {
                    case MethodMetadata mm -> forName(mm.getReturnTypeName(), context.getClassLoader());
                    case AnnotationMetadata am -> forName(am.getClassName(), context.getClassLoader());
                    default -> type;
                };
            }

            return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context.getBeanFactory(), type).length == 0;
        }
    }
}
