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

import io.fluxzero.common.reflection.ReflectionUtils;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.CrossParameterDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.ExecutableDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.ReturnValueDescriptor;
import jakarta.validation.metadata.Scope;
import jakarta.validation.metadata.ValidateUnwrappedValue;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class DefaultValidationMetadata {
    private DefaultValidationMetadata() {
    }

    static BeanDescriptor of(Class<?> type) {
        return DefaultValidationBeanDescriptor.of(type);
    }

record DefaultValidationBeanDescriptor(Class<?> type, Set<ConstraintDescriptor<?>> constraints,
                            Set<PropertyDescriptor> properties,
                            Set<MethodDescriptor> methods,
                            Set<ConstructorDescriptor> constructors)
        implements BeanDescriptor {
    static BeanDescriptor of(Class<?> type) {
        BeanValidationMetadata metadata = BeanValidationMetadata.of(type);
        Set<PropertyDescriptor> properties = metadata.members().stream()
                .map(DefaultValidationPropertyDescriptor::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<MethodDescriptor> methods = ReflectionUtils.getAllMethods(type).stream()
                .filter(DefaultValidationBeanDescriptor::hasExecutableValidation)
                .map(method -> new DefaultValidationMethodDescriptor(method, ExecutableValidationMetadata.of(method)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<ConstructorDescriptor> constructors = Arrays.stream(type.getDeclaredConstructors())
                .filter(DefaultValidationBeanDescriptor::hasExecutableValidation)
                .map(constructor -> new DefaultValidationConstructorDescriptor(constructor, ExecutableValidationMetadata.of(constructor)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new DefaultValidationBeanDescriptor(type, DefaultValidationMetadataSupport.descriptors(metadata.classConstraints()), properties, methods,
                                        constructors);
    }

    private static boolean hasExecutableValidation(Executable executable) {
        ExecutableValidationMetadata metadata = ExecutableValidationMetadata.of(executable);
        return !metadata.crossParameterConstraints().isEmpty()
               || metadata.parameters().stream().anyMatch(DefaultValidationMetadataSupport::hasValidation)
               || metadata.returnValue().hasValidation();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isBeanConstrained() {
        return hasConstraints() || !properties.isEmpty() || !methods.isEmpty() || !constructors.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public PropertyDescriptor getConstraintsForProperty(String propertyName) {
        return properties.stream().filter(property -> property.getPropertyName().equals(propertyName))
                .findFirst().orElse(null);
    }

    /** {@inheritDoc} */
    @Override
    public Set<PropertyDescriptor> getConstrainedProperties() {
        return properties;
    }

    /** {@inheritDoc} */
    @Override
    public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
        return methods.stream()
                .filter(method -> method.getName().equals(methodName)
                                  && Arrays.equals(parameterTypes(method), parameterTypes))
                .findFirst().orElse(null);
    }

    /** {@inheritDoc} */
    @Override
    public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
        Set<MethodType> requested = new LinkedHashSet<>();
        requested.add(methodType);
        requested.addAll(Arrays.asList(methodTypes));
        return methods.stream().filter(method -> requested.contains(methodType(method)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** {@inheritDoc} */
    @Override
    public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
        return constructors.stream()
                .filter(constructor -> Arrays.equals(parameterTypes(constructor), parameterTypes))
                .findFirst().orElse(null);
    }

    /** {@inheritDoc} */
    @Override
    public Set<ConstructorDescriptor> getConstrainedConstructors() {
        return constructors;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasConstraints() {
        return !constraints.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public Class<?> getElementClass() {
        return type;
    }

    /** {@inheritDoc} */
    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return constraints;
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(constraints);
    }

    private static Class<?>[] parameterTypes(ExecutableDescriptor descriptor) {
        return descriptor.getParameterDescriptors().stream()
                .map(ElementDescriptor::getElementClass).toArray(Class<?>[]::new);
    }

    private static MethodType methodType(MethodDescriptor descriptor) {
        return descriptor.getName().startsWith("get") || descriptor.getName().startsWith("is")
               || descriptor.getName().startsWith("has") ? MethodType.GETTER : MethodType.NON_GETTER;
    }
}

record DefaultValidationPropertyDescriptor(BeanValidationMetadata.MemberMetadata member) implements PropertyDescriptor {
    /** {@inheritDoc} */
    @Override
    public String getPropertyName() {
        return member.propertyName();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCascaded() {
        return member.cascaded();
    }

    /** {@inheritDoc} */
    @Override
    public Set<GroupConversionDescriptor> getGroupConversions() {
        return DefaultValidationMetadataSupport.groupConversions(member.conversions());
    }

    /** {@inheritDoc} */
    @Override
    public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
        return DefaultValidationMetadataSupport.containerDescriptors(member.typeUse());
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasConstraints() {
        return !member.constraints().isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public Class<?> getElementClass() {
        return DefaultValidationMetadataSupport.elementClass(member.member());
    }

    /** {@inheritDoc} */
    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return DefaultValidationMetadataSupport.descriptors(member.constraints());
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(getConstraintDescriptors());
    }
}

sealed interface DefaultValidationExecutableDescriptor extends ExecutableDescriptor
        permits DefaultValidationMethodDescriptor, DefaultValidationConstructorDescriptor {
    Executable executable();

    ExecutableValidationMetadata metadata();

    @Override
    default String getName() {
        return metadata().name();
    }

    @Override
    default List<ParameterDescriptor> getParameterDescriptors() {
        Parameter[] parameters = executable().getParameters();
        List<ExecutableValidationMetadata.ParameterMetadata> metadata = metadata().parameters();
        return java.util.stream.IntStream.range(0, metadata.size())
                .mapToObj(i -> new DefaultValidationParameterDescriptor(parameters[i], metadata.get(i)))
                .map(ParameterDescriptor.class::cast)
                .toList();
    }

    @Override
    default CrossParameterDescriptor getCrossParameterDescriptor() {
        return new DefaultValidationCrossParameterDescriptor(metadata().crossParameterConstraints());
    }

    @Override
    default ReturnValueDescriptor getReturnValueDescriptor() {
        return new DefaultValidationReturnValueDescriptor(executable(), metadata().returnValue());
    }

    @Override
    default boolean hasConstrainedParameters() {
        return !metadata().crossParameterConstraints().isEmpty()
               || metadata().parameters().stream().anyMatch(DefaultValidationMetadataSupport::hasValidation);
    }

    @Override
    default boolean hasConstrainedReturnValue() {
        return metadata().returnValue().hasValidation();
    }

    @Override
    default boolean hasConstraints() {
        return !metadata().crossParameterConstraints().isEmpty();
    }

    @Override
    default Class<?> getElementClass() {
        return executable() instanceof Method method ? method.getReturnType() : executable().getDeclaringClass();
    }

    @Override
    default Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return DefaultValidationMetadataSupport.descriptors(metadata().crossParameterConstraints());
    }

    @Override
    default ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(getConstraintDescriptors());
    }
}

record DefaultValidationMethodDescriptor(Method executable, ExecutableValidationMetadata metadata)
        implements MethodDescriptor, DefaultValidationExecutableDescriptor {
}

record DefaultValidationConstructorDescriptor(Constructor<?> executable, ExecutableValidationMetadata metadata)
        implements ConstructorDescriptor, DefaultValidationExecutableDescriptor {
}

record DefaultValidationParameterDescriptor(Parameter parameter, ExecutableValidationMetadata.ParameterMetadata metadata) implements ParameterDescriptor {
    @Override
    public int getIndex() {
        return metadata.index();
    }

    @Override
    public String getName() {
        return metadata.name();
    }

    @Override
    public boolean isCascaded() {
        return metadata.cascaded();
    }

    @Override
    public Set<GroupConversionDescriptor> getGroupConversions() {
        return DefaultValidationMetadataSupport.groupConversions(metadata.conversions());
    }

    @Override
    public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
        return DefaultValidationMetadataSupport.containerDescriptors(metadata.typeUse());
    }

    @Override
    public boolean hasConstraints() {
        return !metadata.constraints().isEmpty();
    }

    @Override
    public Class<?> getElementClass() {
        return parameter.getType();
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return DefaultValidationMetadataSupport.descriptors(metadata.constraints());
    }

    @Override
    public ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(getConstraintDescriptors());
    }
}

record DefaultValidationReturnValueDescriptor(Executable executable, ExecutableValidationMetadata.ReturnValueMetadata metadata)
        implements ReturnValueDescriptor {
    @Override
    public boolean isCascaded() {
        return metadata.cascaded();
    }

    @Override
    public Set<GroupConversionDescriptor> getGroupConversions() {
        return DefaultValidationMetadataSupport.groupConversions(metadata.conversions());
    }

    @Override
    public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
        return DefaultValidationMetadataSupport.containerDescriptors(metadata.typeUse());
    }

    @Override
    public boolean hasConstraints() {
        return !metadata.constraints().isEmpty();
    }

    @Override
    public Class<?> getElementClass() {
        return executable instanceof Method method ? method.getReturnType() : executable.getDeclaringClass();
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return DefaultValidationMetadataSupport.descriptors(metadata.constraints());
    }

    @Override
    public ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(getConstraintDescriptors());
    }
}

record DefaultValidationCrossParameterDescriptor(List<ConstraintMeta> constraints) implements CrossParameterDescriptor {
    @Override
    public boolean hasConstraints() {
        return !constraints.isEmpty();
    }

    @Override
    public Class<?> getElementClass() {
        return Object[].class;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return DefaultValidationMetadataSupport.descriptors(constraints);
    }

    @Override
    public ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(getConstraintDescriptors());
    }
}

record DefaultValidationContainerElementTypeDescriptor(int index, TypeUseValidationMetadata metadata)
        implements ContainerElementTypeDescriptor {
    @Override
    public Integer getTypeArgumentIndex() {
        return index;
    }

    @Override
    public Class<?> getContainerClass() {
        return Object.class;
    }

    @Override
    public boolean isCascaded() {
        return metadata.cascaded();
    }

    @Override
    public Set<GroupConversionDescriptor> getGroupConversions() {
        return DefaultValidationMetadataSupport.groupConversions(metadata.conversions());
    }

    @Override
    public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
        return DefaultValidationMetadataSupport.containerDescriptors(metadata);
    }

    @Override
    public boolean hasConstraints() {
        return !metadata.constraints().isEmpty();
    }

    @Override
    public Class<?> getElementClass() {
        return Object.class;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return DefaultValidationMetadataSupport.descriptors(metadata.constraints());
    }

    @Override
    public ConstraintFinder findConstraints() {
        return new DefaultValidationConstraintFinder(getConstraintDescriptors());
    }
}

record DefaultValidationGroupConversionDescriptor(ValidationAnnotationUtils.GroupConversion conversion) implements GroupConversionDescriptor {
    @Override
    public Class<?> getFrom() {
        return conversion.from();
    }

    @Override
    public Class<?> getTo() {
        return conversion.to();
    }
}

record DefaultValidationConstraintFinder(Set<ConstraintDescriptor<?>> constraints) implements ElementDescriptor.ConstraintFinder {
    @Override
    public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
        if (groups == null || groups.length == 0) {
            return this;
        }
        Predicate<ConstraintDescriptor<?>> matches = descriptor -> descriptor.getGroups().stream()
                .anyMatch(group -> Arrays.stream(groups).anyMatch(group::isAssignableFrom));
        return new DefaultValidationConstraintFinder(constraints.stream().filter(matches)
                                                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    @Override
    public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
        return this;
    }

    @Override
    public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
        return this;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return constraints;
    }

    @Override
    public boolean hasConstraints() {
        return !constraints.isEmpty();
    }
}

record DefaultConstraintDescriptor<T extends Annotation>(ConstraintMeta meta)
        implements ConstraintDescriptor<T> {
    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public T getAnnotation() {
        return (T) meta.annotation();
    }

    /** {@inheritDoc} */
    @Override
    public String getMessageTemplate() {
        return meta.messageTemplate();
    }

    /** {@inheritDoc} */
    @Override
    public Set<Class<?>> getGroups() {
        return meta.groups();
    }

    /** {@inheritDoc} */
    @Override
    public Set<Class<? extends Payload>> getPayload() {
        return meta.payload();
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintTarget getValidationAppliesTo() {
        return meta.validationAppliesTo();
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Class<? extends ConstraintValidator<T, ?>>> getConstraintValidatorClasses() {
        return (List) meta.validatorClasses();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getAttributes() {
        return meta.attributes();
    }

    /** {@inheritDoc} */
    @Override
    public Set<ConstraintDescriptor<?>> getComposingConstraints() {
        return meta.composingConstraints().stream()
                .map(DefaultConstraintDescriptor::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReportAsSingleViolation() {
        return meta.reportAsSingleViolation();
    }

    /** {@inheritDoc} */
    @Override
    public ValidateUnwrappedValue getValueUnwrapping() {
        return ValidateUnwrappedValue.DEFAULT;
    }

    /** {@inheritDoc} */
    @Override
    public <U> U unwrap(Class<U> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new jakarta.validation.ValidationException("Cannot unwrap to " + type.getName());
    }
}

record DefaultConstraintViolation<T>(String message, String messageTemplate, T rootBean,
                                     Class<T> rootBeanClass, Object invalidValue,
                                     ValidationPath propertyPath, boolean customMessage,
                                     ConstraintDescriptor<?> descriptor)
        implements ConstraintViolation<T> {
    String format(boolean fullPath) {
        if (customMessage) {
            return message;
        }
        String path = fullPath ? propertyPath.toString() : ValidationPath.propertyPath(this, false);
        return String.format("%s %s", path, message).trim();
    }

    /** {@inheritDoc} */
    @Override
    public String getMessage() {
        return message;
    }

    /** {@inheritDoc} */
    @Override
    public String getMessageTemplate() {
        return messageTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public T getRootBean() {
        return rootBean;
    }

    /** {@inheritDoc} */
    @Override
    public Class<T> getRootBeanClass() {
        return rootBeanClass;
    }

    /** {@inheritDoc} */
    @Override
    public Object getLeafBean() {
        return rootBean;
    }

    /** {@inheritDoc} */
    @Override
    public Object[] getExecutableParameters() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public Object getExecutableReturnValue() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public ValidationPath getPropertyPath() {
        return propertyPath;
    }

    /** {@inheritDoc} */
    @Override
    public Object getInvalidValue() {
        return invalidValue;
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
        return descriptor;
    }

    /** {@inheritDoc} */
    @Override
    public <U> U unwrap(Class<U> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new jakarta.validation.ValidationException("Cannot unwrap to " + type.getName());
    }
}

static final class DefaultConstraintValidatorContext implements ConstraintValidatorContext {
    private final ConstraintMeta meta;
    private final ValidationPath basePath;
    private final ClockProvider clockProvider;
    private final List<ContextViolation> violationTemplates = new ArrayList<>();
    private boolean defaultConstraintViolationEnabled = true;

    DefaultConstraintValidatorContext(ConstraintMeta meta, ValidationPath basePath, ClockProvider clockProvider) {
        this.meta = meta;
        this.basePath = basePath;
        this.clockProvider = clockProvider;
    }

    boolean defaultConstraintViolationEnabled() {
        return defaultConstraintViolationEnabled;
    }

    List<ContextViolation> violationTemplates() {
        return violationTemplates;
    }

    /** {@inheritDoc} */
    @Override
    public void disableDefaultConstraintViolation() {
        defaultConstraintViolationEnabled = false;
    }

    /** {@inheritDoc} */
    @Override
    public String getDefaultConstraintMessageTemplate() {
        return meta.messageTemplate();
    }

    /** {@inheritDoc} */
    @Override
    public ClockProvider getClockProvider() {
        return clockProvider;
    }

    /** {@inheritDoc} */
    @Override
    public ConstraintViolationBuilder buildConstraintViolationWithTemplate(String messageTemplate) {
        return new Builder(messageTemplate, basePath);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new jakarta.validation.ValidationException("Cannot unwrap to " + type.getName());
    }

    private final class Builder implements ConstraintViolationBuilder,
            ConstraintViolationBuilder.NodeBuilderCustomizableContext,
            ConstraintViolationBuilder.NodeContextBuilder,
            ConstraintViolationBuilder.NodeBuilderDefinedContext,
            ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext,
            ConstraintViolationBuilder.LeafNodeContextBuilder,
            ConstraintViolationBuilder.LeafNodeBuilderDefinedContext,
            ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext,
            ConstraintViolationBuilder.ContainerElementNodeContextBuilder,
            ConstraintViolationBuilder.ContainerElementNodeBuilderDefinedContext {
        private final String template;
        private ValidationPath path;

        private Builder(String template, ValidationPath path) {
            this.template = template;
            this.path = path;
        }

        /** {@inheritDoc} */
        @Override
        public Builder addNode(String name) {
            path = path.appendProperty(name);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ConstraintViolationBuilder.NodeBuilderCustomizableContext addPropertyNode(String name) {
            path = path.appendProperty(name);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext addBeanNode() {
            path = path.appendBean();
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext addContainerElementNode(
                String name, Class<?> containerType, Integer typeArgumentIndex) {
            path = path.appendContainerElement(name, null, null);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ConstraintViolationBuilder.NodeBuilderDefinedContext addParameterNode(int index) {
            path = path.appendParameter("arg" + index, index);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Builder inIterable() {
            path = path.iterableElement(null, null);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Builder inContainer(Class<?> containerType, Integer typeArgumentIndex) {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Builder atKey(Object key) {
            path = path.iterableElement(null, key);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Builder atIndex(Integer index) {
            path = path.iterableElement(index, null);
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            violationTemplates.add(new ContextViolation(template, path));
            return DefaultConstraintValidatorContext.this;
        }
    }

    record ContextViolation(String template, ValidationPath path) {
    }
}

final class DefaultValidationMetadataSupport {
    private DefaultValidationMetadataSupport() {
    }

    static Set<ConstraintDescriptor<?>> descriptors(List<ConstraintMeta> metas) {
        return metas.stream().map(DefaultConstraintDescriptor::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<GroupConversionDescriptor> groupConversions(List<ValidationAnnotationUtils.GroupConversion> conversions) {
        return conversions.stream().map(DefaultValidationGroupConversionDescriptor::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<ContainerElementTypeDescriptor> containerDescriptors(TypeUseValidationMetadata metadata) {
        Set<ContainerElementTypeDescriptor> result = metadata.typeArguments().stream()
                .map(typeArgument -> new DefaultValidationContainerElementTypeDescriptor(typeArgument.index(),
                                                                              typeArgument.metadata()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (metadata.componentType() != null && metadata.componentType().hasValidation()) {
            result.add(new DefaultValidationContainerElementTypeDescriptor(0, metadata.componentType()));
        }
        return result;
    }

    static Class<?> elementClass(Object element) {
        if (element instanceof Field field) {
            return field.getType();
        }
        if (element instanceof Method method) {
            return method.getReturnType();
        }
        if (element instanceof Parameter parameter) {
            return parameter.getType();
        }
        return Object.class;
    }

    static boolean hasValidation(ExecutableValidationMetadata.ParameterMetadata metadata) {
        return !metadata.constraints().isEmpty() || metadata.cascaded() || metadata.typeUse().hasValidation();
    }
}
}
