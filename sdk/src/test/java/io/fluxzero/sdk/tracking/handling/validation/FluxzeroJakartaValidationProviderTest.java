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

package io.fluxzero.sdk.tracking.handling.validation;

import io.fluxzero.sdk.tracking.handling.validation.jakarta.FluxzeroValidationConfiguration;
import io.fluxzero.sdk.tracking.handling.validation.jakarta.FluxzeroValidationProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluxzeroJakartaValidationProviderTest {

    @Test
    void defaultJakartaBootstrapUsesFluxzeroProvider() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertSame(factory, factory.unwrap(ValidatorFactory.class));

            Set<ConstraintViolation<SimpleBean>> violations = factory.getValidator()
                    .validate(new SimpleBean(""));

            assertEquals(Set.of("name"), paths(violations));
        }
    }

    @Test
    void specializedProviderBootstrapUsesConfiguredClockProvider() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .clockProvider(() -> clock)
                .buildValidatorFactory()) {

            assertTrue(factory.getValidator().validate(new ScheduledEvent(
                    Instant.parse("2026-01-02T00:00:00Z"))).isEmpty());
            assertEquals(Set.of("deadline"), paths(factory.getValidator().validate(new ScheduledEvent(
                    Instant.parse("2025-12-31T23:59:59Z")))));
        }
    }

    @Test
    void validatorContextCanOverrideMessageInterpolator() {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .buildValidatorFactory()) {

            Set<ConstraintViolation<SimpleBean>> violations = factory.usingContext()
                    .messageInterpolator(new FixedMessageInterpolator("nope"))
                    .getValidator()
                    .validate(new SimpleBean(""));

            assertEquals(Set.of("nope"), messages(violations));
        }
    }

    @Test
    void configuredConstraintValidatorFactoryCreatesCustomValidators() {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .constraintValidatorFactory(new FactoryCreatingValidatorFactory())
                .buildValidatorFactory()) {

            assertTrue(factory.getValidator().validate(new FactoryBean("value")).isEmpty());
        }
    }

    @Test
    void validatesSinglePropertyAndAdHocPropertyValue() {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .buildValidatorFactory()) {

            jakarta.validation.Validator validator = factory.getValidator();

            assertEquals(Set.of("name"), paths(validator.validateProperty(new SimpleBean(""), "name")));
            assertEquals(Set.of("name"), paths(validator.validateValue(SimpleBean.class, "name", "")));
            assertTrue(validator.validateValue(SimpleBean.class, "name", "ok").isEmpty());
        }
    }

    @Test
    void validatesExecutableParametersAndReturnValues() throws Exception {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .buildValidatorFactory()) {
            ExecutableValidator executables = factory.getValidator().forExecutables();
            ExecutableService service = new ExecutableService();
            Method method = ExecutableService.class.getDeclaredMethod("handle", String.class);

            assertEquals(Set.of("handle.arg0"), paths(executables.validateParameters(
                    service, method, new Object[]{""})));
            assertEquals(Set.of("handle.<return value>"), paths(executables.validateReturnValue(
                    service, method, "")));
        }
    }

    @Test
    void validatesConstructorParametersAndReturnValues() throws Exception {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .buildValidatorFactory()) {
            ExecutableValidator executables = factory.getValidator().forExecutables();
            Constructor<ConstructedBean> constructor = ConstructedBean.class.getDeclaredConstructor(String.class);

            assertEquals(Set.of("ConstructedBean.arg0"), paths(executables.validateConstructorParameters(
                    constructor, new Object[]{""})));
            assertEquals(Set.of("ConstructedBean.<return value>.name"), paths(executables.validateConstructorReturnValue(
                    constructor, new ConstructedBean(null))));
        }
    }

    @Test
    void executableValidationUsesConfiguredParameterNameProvider() throws Exception {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .parameterNameProvider(new FixedParameterNameProvider())
                .buildValidatorFactory()) {
            Method method = ExecutableService.class.getDeclaredMethod("handle", String.class);

            assertEquals(Set.of("handle.payload"), paths(factory.getValidator().forExecutables()
                    .validateParameters(new ExecutableService(), method, new Object[]{""})));
        }
    }

    @Test
    void registeredValueExtractorValidatesCustomContainerElements() {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .addValueExtractor(new BoxValueExtractor())
                .buildValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();

            Set<ConstraintViolation<WrappedText>> textViolations = validator.validate(new WrappedText(new Box<>("")));
            assertEquals(Set.of("must not be blank"), messages(textViolations));
            assertEquals(Set.of("text.value"), paths(textViolations));
            assertEquals(Set.of("must not be null"), messages(validator.validate(
                    new WrappedChild(new Box<>(new Child(null))))));
        }
    }

    @Test
    void exposesUsefulConstraintMetadata() {
        try (ValidatorFactory factory = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .buildValidatorFactory()) {

            BeanDescriptor descriptor = factory.getValidator().getConstraintsForClass(MetadataBean.class);

            assertTrue(descriptor.isBeanConstrained());
            PropertyDescriptor name = descriptor.getConstraintsForProperty("name");
            assertNotNull(name);
            assertTrue(name.hasConstraints());
            assertEquals(String.class, name.getElementClass());

            PropertyDescriptor tags = descriptor.getConstraintsForProperty("tags");
            assertNotNull(tags);
            assertTrue(tags.hasConstraints());
            assertFalse(tags.getConstrainedContainerElementTypes().isEmpty());

            PropertyDescriptor child = descriptor.getConstraintsForProperty("child");
            assertNotNull(child);
            assertTrue(child.isCascaded());

            MethodDescriptor method = descriptor.getConstraintsForMethod("handle", String.class);
            assertNotNull(method);
            assertTrue(method.hasConstrainedParameters());
            assertTrue(method.hasConstrainedReturnValue());
        }
    }

    @Test
    void rejectsXmlConstraintMappingsExplicitly() {
        FluxzeroValidationConfiguration configuration = Validation.byProvider(FluxzeroValidationProvider.class)
                .configure()
                .addMapping(new ByteArrayInputStream("<constraint-mappings/>".getBytes(StandardCharsets.UTF_8)));

        assertThrows(jakarta.validation.ValidationException.class, configuration::buildValidatorFactory);
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> messages(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record SimpleBean(@NotBlank String name) {
    }

    private record ScheduledEvent(@Future Instant deadline) {
    }

    private record FactoryBean(@FactoryCreated String value) {
    }

    private record WrappedText(Box<@NotBlank String> text) {
    }

    private record WrappedChild(Box<@Valid Child> child) {
    }

    private record Box<T>(T value) {
    }

    private record MetadataBean(@Size(min = 2) List<@NotBlank String> tags,
                                @Valid @NotNull Child child,
                                @NotBlank String name) {
        @NotBlank
        String handle(@NotBlank String input) {
            return input;
        }
    }

    private record Child(@NotNull String value) {
    }

    private static final class ConstructedBean {
        @NotNull
        private final String name;

        @Valid
        private ConstructedBean(@NotBlank String name) {
            this.name = name;
        }
    }

    private static final class ExecutableService {
        @NotBlank
        String handle(@NotBlank String input) {
            return input;
        }
    }

    private static final class FixedParameterNameProvider implements ParameterNameProvider {
        @Override
        public List<String> getParameterNames(Constructor<?> constructor) {
            return List.of("payload");
        }

        @Override
        public List<String> getParameterNames(Method method) {
            return List.of("payload");
        }
    }

    private static final class BoxValueExtractor implements ValueExtractor<Box<@ExtractedValue ?>> {
        @Override
        public void extractValues(Box<?> originalValue, ValueReceiver receiver) {
            receiver.value("value", originalValue == null ? null : originalValue.value());
        }
    }

    private record FixedMessageInterpolator(String message) implements MessageInterpolator {
        @Override
        public String interpolate(String messageTemplate, Context context) {
            return message;
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale locale) {
            return message;
        }
    }

    private static final class FactoryCreatingValidatorFactory implements ConstraintValidatorFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            if (key == FactoryCreatedValidator.class) {
                return (T) new FactoryCreatedValidator(true);
            }
            try {
                Constructor<T> constructor = key.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new jakarta.validation.ValidationException(
                        "Could not instantiate constraint validator " + key.getName(), e);
            }
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
        }
    }

    @Target({FIELD, TYPE_USE})
    @Retention(RUNTIME)
    @Constraint(validatedBy = FactoryCreatedValidator.class)
    private @interface FactoryCreated {
        String message() default "factory not used";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    public static final class FactoryCreatedValidator implements ConstraintValidator<FactoryCreated, String> {
        private final boolean createdByFactory;

        public FactoryCreatedValidator() {
            this(false);
        }

        FactoryCreatedValidator(boolean createdByFactory) {
            this.createdByFactory = createdByFactory;
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return createdByFactory;
        }
    }
}
