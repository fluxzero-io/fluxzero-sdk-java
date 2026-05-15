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

package io.fluxzero.sdk.tracking.handling.validation.jakarta;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.tracking.handling.validation.DefaultValidator;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.valueextraction.ValueExtractor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

record ValidationSettings(ClockProvider clockProvider, MessageInterpolator messageInterpolator,
                          ConstraintValidatorFactory constraintValidatorFactory,
                          ParameterNameProvider parameterNameProvider,
                          ValueExtractorRegistry valueExtractors,
                          BooleanSupplier beanPropertyMethodNamesOnlySupplier) {
    static ValidationSettings createDefault() {
        return new ValidationSettings(Fluxzero::currentClock, ValidationMessages.defaultMessageInterpolator(),
                                      new DefaultConstraintValidatorFactory(), new DefaultParameterNameProvider(),
                                      ValueExtractorRegistry.empty(),
                                      () -> ApplicationProperties.getBooleanProperty(
                                              DefaultValidator.BEAN_PROPERTY_METHOD_NAMES_ONLY_PROPERTY, false));
    }

    ValidationSettings withClock(Clock clock) {
        return new ValidationSettings(() -> Objects.requireNonNull(clock), messageInterpolator,
                                      constraintValidatorFactory, parameterNameProvider, valueExtractors,
                                      beanPropertyMethodNamesOnlySupplier);
    }

    ValidationSettings withClockProvider(ClockProvider provider) {
        return new ValidationSettings(Objects.requireNonNull(provider), messageInterpolator,
                                      constraintValidatorFactory, parameterNameProvider, valueExtractors,
                                      beanPropertyMethodNamesOnlySupplier);
    }

    ValidationSettings withMessageInterpolator(MessageInterpolator interpolator) {
        return new ValidationSettings(clockProvider, Objects.requireNonNull(interpolator),
                                      constraintValidatorFactory, parameterNameProvider, valueExtractors,
                                      beanPropertyMethodNamesOnlySupplier);
    }

    ValidationSettings withConstraintValidatorFactory(ConstraintValidatorFactory factory) {
        return new ValidationSettings(clockProvider, messageInterpolator, Objects.requireNonNull(factory),
                                      parameterNameProvider, valueExtractors, beanPropertyMethodNamesOnlySupplier);
    }

    ValidationSettings withParameterNameProvider(ParameterNameProvider provider) {
        return new ValidationSettings(clockProvider, messageInterpolator, constraintValidatorFactory,
                                      Objects.requireNonNull(provider), valueExtractors,
                                      beanPropertyMethodNamesOnlySupplier);
    }

    ValidationSettings withValueExtractors(Collection<ValueExtractor<?>> extractors) {
        return new ValidationSettings(clockProvider, messageInterpolator, constraintValidatorFactory,
                                      parameterNameProvider, new ValueExtractorRegistry(extractors),
                                      beanPropertyMethodNamesOnlySupplier);
    }

    ValidationSettings withValueExtractor(ValueExtractor<?> extractor) {
        return new ValidationSettings(clockProvider, messageInterpolator, constraintValidatorFactory,
                                      parameterNameProvider, valueExtractors.plus(extractor),
                                      beanPropertyMethodNamesOnlySupplier);
    }

    boolean beanPropertyMethodNamesOnly() {
        return beanPropertyMethodNamesOnlySupplier.getAsBoolean();
    }

    static final class DefaultConstraintValidatorFactory implements ConstraintValidatorFactory {
    /** {@inheritDoc} */
    @Override
    public <T extends jakarta.validation.ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
        try {
            Constructor<T> constructor = key.getDeclaredConstructor();
            io.fluxzero.common.reflection.ReflectionUtils.ensureAccessible(constructor);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new jakarta.validation.ValidationException(
                    "Could not instantiate constraint validator " + key.getName(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void releaseInstance(jakarta.validation.ConstraintValidator<?, ?> instance) {
    }
}

    static final class DefaultParameterNameProvider implements ParameterNameProvider {
    /** {@inheritDoc} */
    @Override
    public List<String> getParameterNames(Constructor<?> constructor) {
        return parameterNames(constructor.getParameters());
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getParameterNames(Method method) {
        return parameterNames(method.getParameters());
    }

    private static List<String> parameterNames(java.lang.reflect.Parameter[] parameters) {
        if (Arrays.stream(parameters).allMatch(java.lang.reflect.Parameter::isNamePresent)) {
            return Arrays.stream(parameters).map(java.lang.reflect.Parameter::getName).toList();
        }
        return IntStream.range(0, parameters.length).mapToObj(i -> "arg" + i).toList();
    }
}
}
