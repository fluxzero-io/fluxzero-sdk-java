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

import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ClockProvider;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableType;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.valueextraction.ValueExtractor;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Programmatic configuration for the Fluxzero Jakarta Validation provider.
 * <p>
 * The configuration supports the SDK extension points that are useful in-process: message interpolation, constraint
 * validator creation, parameter names, clocks, and value extractors. XML mappings and {@code validation.xml} are not
 * supported; streams passed to {@link #addMapping(InputStream)} are rejected when the factory is built.
 */
public final class FluxzeroValidationConfiguration implements Configuration<FluxzeroValidationConfiguration>, ConfigurationState {
    private boolean ignoreXmlConfiguration = true;
    private MessageInterpolator messageInterpolator = ValidationMessages.defaultMessageInterpolator();
    private TraversableResolver traversableResolver = new DefaultTraversableResolver();
    private ConstraintValidatorFactory constraintValidatorFactory = new ValidationSettings.DefaultConstraintValidatorFactory();
    private ParameterNameProvider parameterNameProvider = new ValidationSettings.DefaultParameterNameProvider();
    private ClockProvider clockProvider = ValidationSettings.createDefault().clockProvider();
    private final Set<InputStream> mappingStreams = new LinkedHashSet<>();
    private final Set<ValueExtractor<?>> valueExtractors = new LinkedHashSet<>();
    private final Map<String, String> properties = new LinkedHashMap<>();

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration ignoreXmlConfiguration() {
        ignoreXmlConfiguration = true;
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration messageInterpolator(MessageInterpolator interpolator) {
        messageInterpolator = interpolator;
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration traversableResolver(TraversableResolver resolver) {
        traversableResolver = resolver;
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration constraintValidatorFactory(ConstraintValidatorFactory factory) {
        constraintValidatorFactory = factory;
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration parameterNameProvider(ParameterNameProvider provider) {
        parameterNameProvider = provider;
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration clockProvider(ClockProvider provider) {
        clockProvider = provider;
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration addValueExtractor(ValueExtractor<?> extractor) {
        valueExtractors.add(extractor);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration addMapping(InputStream stream) {
        mappingStreams.add(stream);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public FluxzeroValidationConfiguration addProperty(String name, String value) {
        properties.put(name, value);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public MessageInterpolator getDefaultMessageInterpolator() {
        return ValidationMessages.defaultMessageInterpolator();
    }

    /** {@inheritDoc} */
    @Override
    public TraversableResolver getDefaultTraversableResolver() {
        return new DefaultTraversableResolver();
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintValidatorFactory getDefaultConstraintValidatorFactory() {
        return new ValidationSettings.DefaultConstraintValidatorFactory();
    }

    /** {@inheritDoc} */
    @Override
    public ParameterNameProvider getDefaultParameterNameProvider() {
        return new ValidationSettings.DefaultParameterNameProvider();
    }

    /** {@inheritDoc} */
    @Override
    public ClockProvider getDefaultClockProvider() {
        return ValidationSettings.createDefault().clockProvider();
    }

    /** {@inheritDoc} */
    @Override
    public BootstrapConfiguration getBootstrapConfiguration() {
        return new DefaultBootstrapConfiguration(properties);
    }

    /** {@inheritDoc} */
    @Override
    public ValidatorFactory buildValidatorFactory() {
        return new FluxzeroValidationProvider.DefaultValidatorFactory(this);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isIgnoreXmlConfiguration() {
        return ignoreXmlConfiguration;
    }

    /** {@inheritDoc} */
    @Override
    public MessageInterpolator getMessageInterpolator() {
        return messageInterpolator;
    }

    /** {@inheritDoc} */
    @Override
    public Set<InputStream> getMappingStreams() {
        return Set.copyOf(mappingStreams);
    }

    /** {@inheritDoc} */
    @Override
    public Set<ValueExtractor<?>> getValueExtractors() {
        return Set.copyOf(valueExtractors);
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        return constraintValidatorFactory;
    }

    /** {@inheritDoc} */
    @Override
    public TraversableResolver getTraversableResolver() {
        return traversableResolver;
    }

    /** {@inheritDoc} */
    @Override
    public ParameterNameProvider getParameterNameProvider() {
        return parameterNameProvider;
    }

    /** {@inheritDoc} */
    @Override
    public ClockProvider getClockProvider() {
        return clockProvider;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, String> getProperties() {
        return Map.copyOf(properties);
    }

    ValidationSettings settings() {
        return ValidationSettings.createDefault()
                .withClockProvider(clockProvider)
                .withMessageInterpolator(messageInterpolator)
                .withConstraintValidatorFactory(constraintValidatorFactory)
                .withParameterNameProvider(parameterNameProvider)
                .withValueExtractors(valueExtractors);
    }

    private record DefaultBootstrapConfiguration(Map<String, String> properties) implements BootstrapConfiguration {
        /** {@inheritDoc} */
        @Override
        public String getDefaultProviderClassName() {
            return FluxzeroValidationProvider.class.getName();
        }

        /** {@inheritDoc} */
        @Override
        public String getConstraintValidatorFactoryClassName() {
            return ValidationSettings.DefaultConstraintValidatorFactory.class.getName();
        }

        /** {@inheritDoc} */
        @Override
        public String getMessageInterpolatorClassName() {
            return ValidationMessages.defaultMessageInterpolator().getClass().getName();
        }

        /** {@inheritDoc} */
        @Override
        public String getTraversableResolverClassName() {
            return DefaultTraversableResolver.class.getName();
        }

        /** {@inheritDoc} */
        @Override
        public String getParameterNameProviderClassName() {
            return ValidationSettings.DefaultParameterNameProvider.class.getName();
        }

        /** {@inheritDoc} */
        @Override
        public String getClockProviderClassName() {
            return ValidationSettings.createDefault().clockProvider().getClass().getName();
        }

        /** {@inheritDoc} */
        @Override
        public Set<String> getValueExtractorClassNames() {
            return Set.of();
        }

        /** {@inheritDoc} */
        @Override
        public Set<String> getConstraintMappingResourcePaths() {
            return Set.of();
        }

        /** {@inheritDoc} */
        @Override
        public boolean isExecutableValidationEnabled() {
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public Set<ExecutableType> getDefaultValidatedExecutableTypes() {
            return Set.of(ExecutableType.CONSTRUCTORS, ExecutableType.NON_GETTER_METHODS, ExecutableType.GETTER_METHODS);
        }

        /** {@inheritDoc} */
        @Override
        public Map<String, String> getProperties() {
            return properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    private static final class DefaultTraversableResolver implements TraversableResolver {
        /** {@inheritDoc} */
        @Override
        public boolean isReachable(Object traversableObject, jakarta.validation.Path.Node traversableProperty,
                                   Class<?> rootBeanType, jakarta.validation.Path pathToTraversableObject,
                                   java.lang.annotation.ElementType elementType) {
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public boolean isCascadable(Object traversableObject, jakarta.validation.Path.Node traversableProperty,
                                    Class<?> rootBeanType, jakarta.validation.Path pathToTraversableObject,
                                    java.lang.annotation.ElementType elementType) {
            return true;
        }
    }
}
