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

import jakarta.validation.ClockProvider;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidatorContext;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.spi.BootstrapState;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.spi.ValidationProvider;
import jakarta.validation.valueextraction.ValueExtractor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Jakarta Validation provider that exposes the SDK validator through the standard bootstrap API.
 * <p>
 * This provider supports the SDK's Jakarta Validation profile. XML configuration/mappings, CDI lifecycle integration,
 * TraversableResolver reachability rules, and full Expression Language message evaluation are intentionally not part of
 * that profile.
 */
public final class FluxzeroValidationProvider implements ValidationProvider<FluxzeroValidationConfiguration> {
    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration createSpecializedConfiguration(BootstrapState state) {
        return new FluxzeroValidationConfiguration();
    }

    /** {@inheritDoc} */
    @Override
    public Configuration<?> createGenericConfiguration(BootstrapState state) {
        return new FluxzeroValidationConfiguration();
    }

    /** {@inheritDoc} */
    @Override
    public ValidatorFactory buildValidatorFactory(ConfigurationState configurationState) {
        return new DefaultValidatorFactory(configurationState);
    }

    static final class DefaultValidatorFactory implements ValidatorFactory {
        private final ValidationSettings settings;
        private final TraversableResolver traversableResolver;
        private boolean closed;

        DefaultValidatorFactory(ConfigurationState state) {
            if (!state.getMappingStreams().isEmpty()) {
                throw new jakarta.validation.ValidationException("XML constraint mappings are not supported");
            }
            settings = ValidationSettings.createDefault()
                    .withClockProvider(state.getClockProvider())
                    .withMessageInterpolator(state.getMessageInterpolator())
                    .withConstraintValidatorFactory(state.getConstraintValidatorFactory())
                    .withParameterNameProvider(state.getParameterNameProvider())
                    .withValueExtractors(state.getValueExtractors());
            traversableResolver = state.getTraversableResolver();
        }

        /** {@inheritDoc} */
        @Override
        public jakarta.validation.Validator getValidator() {
            assertOpen();
            return new JakartaValidatorAdapter(settings);
        }

        /** {@inheritDoc} */
        @Override
        public ValidatorContext usingContext() {
            assertOpen();
            return new Context(settings, this);
        }

        /** {@inheritDoc} */
        @Override
        public MessageInterpolator getMessageInterpolator() {
            return settings.messageInterpolator();
        }

        /** {@inheritDoc} */
        @Override
        public TraversableResolver getTraversableResolver() {
            return traversableResolver;
        }

        /** {@inheritDoc} */
        @Override
        public ConstraintValidatorFactory getConstraintValidatorFactory() {
            return settings.constraintValidatorFactory();
        }

        /** {@inheritDoc} */
        @Override
        public ParameterNameProvider getParameterNameProvider() {
            return settings.parameterNameProvider();
        }

        /** {@inheritDoc} */
        @Override
        public ClockProvider getClockProvider() {
            return settings.clockProvider();
        }

        /** {@inheritDoc} */
        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new jakarta.validation.ValidationException("Cannot unwrap to " + type.getName());
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            if (!closed) {
                ConstraintValidators.release(settings.constraintValidatorFactory());
                closed = true;
            }
        }

        private void assertOpen() {
            if (closed) {
                throw new jakarta.validation.ValidationException("ValidatorFactory is closed");
            }
        }

        private record Context(ValidationSettings settings, DefaultValidatorFactory factory) implements ValidatorContext {
            /** {@inheritDoc} */
            @Override
            public ValidatorContext messageInterpolator(MessageInterpolator messageInterpolator) {
                return new Context(settings.withMessageInterpolator(messageInterpolator), factory);
            }

            /** {@inheritDoc} */
            @Override
            public ValidatorContext traversableResolver(TraversableResolver traversableResolver) {
                return this;
            }

            /** {@inheritDoc} */
            @Override
            public ValidatorContext constraintValidatorFactory(ConstraintValidatorFactory constraintValidatorFactory) {
                return new Context(settings.withConstraintValidatorFactory(constraintValidatorFactory), factory);
            }

            /** {@inheritDoc} */
            @Override
            public ValidatorContext parameterNameProvider(ParameterNameProvider parameterNameProvider) {
                return new Context(settings.withParameterNameProvider(parameterNameProvider), factory);
            }

            /** {@inheritDoc} */
            @Override
            public ValidatorContext clockProvider(ClockProvider clockProvider) {
                return new Context(settings.withClockProvider(clockProvider), factory);
            }

            /** {@inheritDoc} */
            @Override
            public ValidatorContext addValueExtractor(ValueExtractor<?> extractor) {
                return new Context(settings.withValueExtractor(extractor), factory);
            }

            /** {@inheritDoc} */
            @Override
            public jakarta.validation.Validator getValidator() {
                factory.assertOpen();
                return new JakartaValidatorAdapter(settings);
            }
        }
    }

    static final class JakartaValidatorAdapter implements jakarta.validation.Validator {
        private final DefaultJakartaValidator delegate;
        private final ExecutableValidator executableValidator = new Executables();

        JakartaValidatorAdapter(ValidationSettings settings) {
            delegate = new DefaultJakartaValidator(settings);
        }

        /** {@inheritDoc} */
        @Override
        public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
            return delegate.getJakartaConstraintViolations(object, groups);
        }

        /** {@inheritDoc} */
        @Override
        public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
            return delegate.getPropertyConstraintViolations(object, propertyName, groups);
        }

        /** {@inheritDoc} */
        @Override
        public <T> Set<ConstraintViolation<T>> validateValue(
                Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
            return delegate.getValueConstraintViolations(beanType, propertyName, value, groups);
        }

        /** {@inheritDoc} */
        @Override
        public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
            return DefaultValidationMetadata.of(clazz);
        }

        /** {@inheritDoc} */
        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            if (type.isInstance(delegate)) {
                return type.cast(delegate);
            }
            throw new jakarta.validation.ValidationException("Cannot unwrap to " + type.getName());
        }

        /** {@inheritDoc} */
        @Override
        public ExecutableValidator forExecutables() {
            return executableValidator;
        }

        private final class Executables implements ExecutableValidator {
            /** {@inheritDoc} */
            @Override
            public <T> Set<ConstraintViolation<T>> validateParameters(
                    T object, Method method, Object[] parameterValues, Class<?>... groups) {
                return delegate.getParameterConstraintViolations(object, method, parameterValues, groups);
            }

            /** {@inheritDoc} */
            @Override
            public <T> Set<ConstraintViolation<T>> validateReturnValue(
                    T object, Method method, Object returnValue, Class<?>... groups) {
                return delegate.getReturnValueConstraintViolations(object, method, returnValue, groups);
            }

            /** {@inheritDoc} */
            @Override
            public <T> Set<ConstraintViolation<T>> validateConstructorParameters(
                    Constructor<? extends T> constructor, Object[] parameterValues, Class<?>... groups) {
                return delegate.getParameterConstraintViolations(null, constructor, parameterValues, groups);
            }

            /** {@inheritDoc} */
            @Override
            public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(
                    Constructor<? extends T> constructor, T createdObject, Class<?>... groups) {
                return delegate.getReturnValueConstraintViolations(createdObject, constructor, createdObject, groups);
            }
        }
    }
}
