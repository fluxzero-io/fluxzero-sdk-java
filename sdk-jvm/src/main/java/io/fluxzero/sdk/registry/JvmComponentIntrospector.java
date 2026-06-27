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

package io.fluxzero.sdk.registry;

import io.fluxzero.common.handling.ExecutableInvocation;
import io.fluxzero.common.handling.ExecutableInvocationBackend;
import io.fluxzero.common.reflection.MemberInvoker;
import io.fluxzero.common.reflection.ReflectionUtils;
import io.fluxzero.common.reflection.ReflectionUtils.TypeMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reflection-backed metadata adapter for JVM Fluxzero execution.
 * <p>
 * This class deliberately contains reflection details so higher-level Fluxzero logic can move toward metadata-based
 * semantics without forking JVM and browser behavior.
 */
public final class JvmComponentIntrospector implements
        ComponentIntrospector<Class<?>, Executable, AccessibleObject>,
        PropertyAccess<Class<?>, AccessibleObject>,
        ExecutableInvoker<Executable> {

    private static final JvmComponentIntrospector INSTANCE = new JvmComponentIntrospector();
    private final ExecutableInvocationBackend executableInvocationBackend = this::prepareInvocation;

    private JvmComponentIntrospector() {
    }

    /**
     * Returns the shared JVM metadata adapter.
     */
    public static JvmComponentIntrospector getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the class represented by the supplied value, or {@code null} when it is not a class-like value.
     */
    public Class<?> ifClass(Object value) {
        return ReflectionUtils.ifClass(value);
    }

    /**
     * Returns the runtime class for either a class literal or an instance.
     */
    public Class<?> asClass(Object value) {
        return ReflectionUtils.asClass(value);
    }

    /**
     * Returns the supplied instance, or creates one when a class literal is supplied.
     */
    public <T> T asInstance(Object classOrInstance) {
        return ReflectionUtils.asInstance(classOrInstance);
    }

    /**
     * Returns cached JVM metadata for a class.
     */
    public TypeMetadata getTypeMetadata(Class<?> type) {
        return ReflectionUtils.getTypeMetadata(type);
    }

    /**
     * Returns all usable methods for a class.
     */
    public List<Method> getAllMethods(Class<?> type) {
        return ReflectionUtils.getAllMethods(type);
    }

    /**
     * Returns the default constructor declared by the supplied type, if present.
     */
    public Optional<Constructor<?>> getDefaultConstructor(Class<?> type) {
        return ReflectionUtils.getDefaultConstructor(type);
    }

    /**
     * Returns all interfaces implemented by the supplied type.
     */
    public List<Class<?>> getAllInterfaces(Class<?> type) {
        return ReflectionUtils.getAllInterfaces(type);
    }

    /**
     * Converts primitive classes to wrappers and leaves other classes untouched.
     */
    public Class<?> box(Class<?> type) {
        return ReflectionUtils.box(type);
    }

    /**
     * Resolves a reflective type to a raw class.
     */
    public Class<?> rawClass(Type type) {
        return ReflectionUtils.rawClass(type);
    }

    /**
     * Returns a method by name when present.
     */
    public Optional<Method> getMethod(Class<?> type, String name) {
        return ReflectionUtils.getMethod(type, name);
    }

    /**
     * Returns all properties annotated with the supplied annotation.
     */
    public List<? extends AccessibleObject> getAnnotatedProperties(
            Class<?> target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedProperties(target, annotation);
    }

    /**
     * Returns the first annotated property on a class or instance.
     */
    public Optional<? extends AccessibleObject> getAnnotatedProperty(
            Object target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedProperty(target, annotation);
    }

    /**
     * Returns a cached property invoker for the first annotated property.
     */
    public Optional<MemberInvoker> getAnnotatedPropertyInvoker(
            Class<?> target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedPropertyInvoker(target, annotation);
    }

    /**
     * Returns the JVM executable invocation backend used by SDK runtime handler configuration.
     */
    public ExecutableInvocationBackend executableInvocationBackend() {
        return executableInvocationBackend;
    }

    /**
     * Prepares an optimized JVM invocation handle for a method or constructor.
     */
    public ExecutableInvocation prepareInvocation(Executable executable) {
        MemberInvoker invoker = getTypeMetadata(executable.getDeclaringClass())
                .invoker((Member) executable, true);
        return invoker::invoke;
    }

    /**
     * Returns the value of the first annotated property on an instance.
     */
    public Optional<Object> getAnnotatedPropertyValue(Object target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedPropertyValue(target, annotation);
    }

    /**
     * Returns values for all properties annotated with the supplied annotation.
     */
    public Collection<Object> getAnnotatedPropertyValues(Object target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedPropertyValues(target, annotation);
    }

    /**
     * Returns the property name for the first annotated property.
     */
    public Optional<String> getAnnotatedPropertyName(Object target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedPropertyName(target, annotation);
    }

    /**
     * Returns annotated methods for a class.
     */
    public List<Method> getAnnotatedMethods(Class<?> target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedMethods(target, annotation);
    }

    /**
     * Returns annotated methods for an instance.
     */
    public List<Method> getAnnotatedMethods(Object target, Class<? extends Annotation> annotation) {
        return ReflectionUtils.getAnnotatedMethods(target, annotation);
    }

    /**
     * Returns whether the class has the supplied annotation, including supported meta-annotations.
     */
    public boolean isAnnotationPresent(Class<?> type, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.isAnnotationPresent(type, annotationType);
    }

    /**
     * Returns whether a parameter has the supplied annotation.
     */
    public boolean isAnnotationPresent(Parameter parameter, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.isAnnotationPresent(parameter, annotationType);
    }

    /**
     * Returns a type annotation using JVM annotation semantics.
     */
    public <A extends Annotation> A getTypeAnnotation(Class<?> type, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.getTypeAnnotation(type, annotationType);
    }

    /**
     * Returns all type annotations using JVM annotation semantics.
     */
    public Collection<? extends Annotation> getTypeAnnotations(Class<?> type) {
        return ReflectionUtils.getTypeAnnotations(type);
    }

    /**
     * Returns direct annotations on a JVM annotated element.
     */
    public List<Annotation> getAnnotations(AnnotatedElement element) {
        return ReflectionUtils.getAnnotations(element);
    }

    /**
     * Returns a package annotation using JVM package semantics.
     */
    public <A extends Annotation> Optional<A> getPackageAnnotation(Package p, Class<A> annotationType) {
        return ReflectionUtils.getPackageAnnotation(p, annotationType);
    }

    /**
     * Returns package annotations, including ancestors.
     */
    public Collection<? extends Annotation> getPackageAnnotations(Package p) {
        return ReflectionUtils.getPackageAnnotations(p);
    }

    /**
     * Returns package annotations with explicit recursive behavior.
     */
    public Collection<? extends Annotation> getPackageAnnotations(Package p, boolean recursive) {
        return ReflectionUtils.getPackageAnnotations(p, recursive);
    }

    /**
     * Reads a property path from an object.
     */
    @Override
    public <T> Optional<T> readProperty(String propertyPath, Object target) {
        return ReflectionUtils.readProperty(propertyPath, target);
    }

    /**
     * Returns the first type argument from a parameterized type.
     */
    public <T extends Type> T getFirstTypeArgument(Type genericType) {
        return ReflectionUtils.getFirstTypeArgument(genericType);
    }

    /**
     * Returns whether a property path exists on an object.
     */
    @Override
    public boolean hasProperty(String propertyPath, Object target) {
        return ReflectionUtils.hasProperty(propertyPath, target);
    }

    /**
     * Returns whether an executable has a non-void return type.
     */
    public boolean hasReturnType(Executable executable) {
        return ReflectionUtils.hasReturnType(executable);
    }

    /**
     * Reads a field or no-arg method value.
     */
    public Object getValue(AccessibleObject fieldOrMethod, Object target, boolean forceAccess) {
        return ReflectionUtils.getValue(fieldOrMethod, target, forceAccess);
    }

    /**
     * Reads a field or no-arg method value with forced access.
     */
    public Object getValue(AccessibleObject fieldOrMethod, Object target) {
        return ReflectionUtils.getValue(fieldOrMethod, target);
    }

    /**
     * Returns the member name for a field or method.
     */
    public String getName(AccessibleObject fieldOrMethod) {
        return ReflectionUtils.getName(fieldOrMethod);
    }

    /**
     * Returns the logical property name represented by a field or no-arg getter.
     */
    public String getPropertyName(AccessibleObject property) {
        return ReflectionUtils.getPropertyName(property);
    }

    /**
     * Returns the property type represented by a field or method.
     */
    public Class<?> getPropertyType(AccessibleObject fieldOrMethod) {
        return ReflectionUtils.getPropertyType(fieldOrMethod);
    }

    /**
     * Returns the collection element type represented by a field or method.
     */
    public Optional<Class<?>> getCollectionElementType(AccessibleObject fieldOrMethod) {
        return ReflectionUtils.getCollectionElementType(fieldOrMethod);
    }

    /**
     * Writes a property path on an object when it exists.
     */
    @Override
    public void writeProperty(String propertyPath, Object target, Object value) {
        ReflectionUtils.writeProperty(propertyPath, target, value);
    }

    /**
     * Returns whether an annotation is, or is meta-annotated with, the supplied annotation type.
     */
    public boolean isOrHas(Annotation annotation, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.isOrHas(annotation, annotationType);
    }

    /**
     * Returns whether a type is, or is annotated with, the supplied annotation type.
     */
    public boolean isOrHas(Class<?> type, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.isOrHas(type, annotationType);
    }

    /**
     * Returns a field by name when present.
     */
    public Optional<Field> getField(Class<?> owner, String name) {
        return ReflectionUtils.getField(owner, name);
    }

    /**
     * Returns the first non-JDK caller class.
     */
    public Class<?> getCallerClass() {
        return StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
                .walk(frames -> {
                    Iterator<StackWalker.StackFrame> iterator = frames.skip(1).iterator();
                    Class<?> invoker = null;
                    while (iterator.hasNext()) {
                        Class<?> frameClass = iterator.next().getDeclaringClass();
                        if (!frameClass.equals(JvmComponentIntrospector.class)) {
                            invoker = frameClass;
                            break;
                        }
                    }
                    while (iterator.hasNext()) {
                        StackWalker.StackFrame frame = iterator.next();
                        var frameClass = frame.getDeclaringClass();
                        if (!frameClass.equals(invoker)
                            && !frameClass.getName().startsWith("java.")) {
                            return frameClass;
                        }
                    }
                    return null;
                });
    }

    /**
     * Returns whether a parameter is nullable according to JVM/Kotlin annotation semantics.
     */
    public boolean isNullable(Parameter parameter) {
        return ReflectionUtils.isNullable(parameter);
    }

    /**
     * Returns common ancestors for the supplied runtime values.
     */
    public List<Class<?>> determineCommonAncestors(Collection<?> elements) {
        return ReflectionUtils.determineCommonAncestors(elements);
    }

    /**
     * Returns all package ancestors known to the JVM.
     */
    public List<Package> getPackageAndParentPackages(Package p) {
        return ReflectionUtils.getPackageAndParentPackages(p);
    }

    /**
     * Returns whether an executable is static.
     */
    public boolean isStatic(Executable method) {
        return ReflectionUtils.isStatic(method);
    }

    /**
     * Returns whether a value should be treated as a terminal scalar.
     */
    public boolean isLeafValue(Object value) {
        return ReflectionUtils.isLeafValue(value);
    }

    /**
     * Resolves a generic supertype from the supplied candidate class.
     */
    public Type getGenericType(Class<?> candidate, Class<?> wantedClass) {
        return ReflectionUtils.getGenericType(candidate, wantedClass);
    }

    /**
     * Ensures a JVM member is accessible.
     */
    public <T extends AccessibleObject> T ensureAccessible(T member) {
        return ReflectionUtils.ensureAccessible(member);
    }

    /**
     * Returns all no-argument annotation attributes.
     */
    public Map<String, Object> getAnnotationAttributes(Annotation annotation) {
        return ReflectionUtils.getAnnotationAttributes(annotation);
    }

    /**
     * Returns a single annotation attribute value.
     */
    public <T> Optional<T> getAnnotationAttribute(Annotation annotation, String name, Class<T> expectedType) {
        return ReflectionUtils.getAnnotationAttribute(annotation, name, expectedType);
    }

    /**
     * Returns whether an annotation attribute has a non-default value.
     */
    public boolean hasNonDefaultAnnotationAttribute(Annotation annotation, String name) {
        return ReflectionUtils.hasNonDefaultAnnotationAttribute(annotation, name);
    }

    /**
     * Returns an annotation from an annotated element, including supported meta-annotations.
     */
    public <A extends Annotation> Optional<A> getAnnotation(AnnotatedElement element, Class<A> annotationType) {
        return ReflectionUtils.getAnnotation(element, annotationType);
    }

    /**
     * Projects an annotation from a type to another return type.
     */
    public <T> Optional<T> getAnnotationAs(
            Class<?> target, Class<? extends Annotation> annotationType, Class<T> returnType) {
        return ReflectionUtils.getAnnotationAs(target, annotationType, returnType);
    }

    /**
     * Projects an annotation from an annotated element to another return type.
     */
    public <T> Optional<T> getAnnotationAs(
            AnnotatedElement member, Class<? extends Annotation> annotationType, Class<? extends T> returnType) {
        return ReflectionUtils.getAnnotationAs(member, annotationType, returnType);
    }

    /**
     * Projects an annotation instance to another return type.
     */
    public <T> Optional<T> getAnnotationAs(
            Annotation annotation, Class<? extends Annotation> targetAnnotation, Class<? extends T> returnType) {
        return ReflectionUtils.getAnnotationAs(annotation, targetAnnotation, returnType);
    }

    /**
     * Converts an annotation instance to another metadata shape.
     */
    public <T> T convertAnnotation(Annotation annotation, Class<? extends T> returnType) {
        return ReflectionUtils.convertAnnotation(annotation, returnType);
    }

    /**
     * Returns whether a method/parameter has the supplied annotation.
     */
    public boolean has(Class<? extends Annotation> annotationClass, Method method) {
        return ReflectionUtils.has(annotationClass, method);
    }

    public boolean has(Class<? extends Annotation> annotationClass, Parameter parameter) {
        return ReflectionUtils.has(annotationClass, parameter);
    }

    /**
     * Returns a method annotation, including supported overridden-method semantics.
     */
    public <A extends Annotation> Optional<A> getMethodAnnotation(
            Executable executable, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.getMethodAnnotation(executable, annotationType);
    }

    /**
     * Returns whether an executable has the supplied annotation.
     */
    public boolean isMethodAnnotationPresent(Executable executable, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.isMethodAnnotationPresent(executable, annotationType);
    }

    /**
     * Returns method annotations, including supported overridden-method semantics.
     */
    public <A extends Annotation> List<A> getMethodAnnotations(
            Executable executable, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.getMethodAnnotations(executable, annotationType);
    }

    /**
     * Returns the override hierarchy for a method.
     */
    public Stream<Method> getMethodOverrideHierarchy(Method method) {
        return ReflectionUtils.getMethodOverrideHierarchy(method);
    }

    /**
     * Returns the override hierarchy for a parameter.
     */
    public Stream<Parameter> getParameterOverrideHierarchy(Parameter parameter) {
        return ReflectionUtils.getParameterOverrideHierarchy(parameter);
    }

    /**
     * Returns whether Kotlin reflection support is present.
     */
    public boolean isKotlinReflectionSupported() {
        return ReflectionUtils.isKotlinReflectionSupported();
    }

    /**
     * Returns the JVM class-specificity comparator.
     */
    public Comparator<Class<?>> getClassSpecificityComparator() {
        return ReflectionUtils.getClassSpecificityComparator();
    }

    /**
     * Copies fields from one instance to another instance of the same class.
     */
    public <V> V copyFields(V source, V target) {
        return ReflectionUtils.copyFields(source, target);
    }

    /**
     * Loads a class by Fluxzero type name or fully-qualified class name.
     */
    public Class<?> classForName(String type) {
        return ReflectionUtils.classForName(type);
    }

    /**
     * Loads a class by Fluxzero type name or returns the supplied default.
     */
    public Class<?> classForName(String type, Class<?> defaultClass) {
        return ReflectionUtils.classForName(type, defaultClass);
    }

    /**
     * Returns whether a type name can be resolved.
     */
    public boolean classExists(String className) {
        return ReflectionUtils.classExists(className);
    }

    /**
     * Returns the simple name of a class.
     */
    public String getSimpleName(Class<?> c) {
        return ReflectionUtils.getSimpleName(c);
    }

    /**
     * Returns the simple name of a package.
     */
    public String getSimpleName(Package p) {
        return ReflectionUtils.getSimpleName(p);
    }

    /**
     * Returns the simple name of a fully-qualified name.
     */
    public String getSimpleName(String fullyQualifiedName) {
        return ReflectionUtils.getSimpleName(fullyQualifiedName);
    }

    /**
     * Returns the runtime component type for either a class literal or instance.
     */
    public Class<?> typeOf(Object target) {
        return ReflectionUtils.asClass(target);
    }

    /**
     * Creates or returns an instance using the JVM component instantiation semantics.
     */
    @SuppressWarnings("unchecked")
    public <T> T instantiate(Class<?> type) {
        return (T) ReflectionUtils.asInstance(type);
    }

    /**
     * Returns the supplied package and all known parent packages.
     */
    public List<Package> packageAndParentPackages(Package p) {
        return ReflectionUtils.getPackageAndParentPackages(p);
    }

    @Override
    public List<AnnotationDescriptor> typeAnnotations(Class<?> type) {
        return ReflectionUtils.getTypeAnnotations(type).stream()
                .map(JvmComponentIntrospector::annotationDescriptor).toList();
    }

    @Override
    public List<AnnotationDescriptor> executableAnnotations(Executable executable) {
        return ReflectionUtils.getAnnotations(executable).stream()
                .map(JvmComponentIntrospector::annotationDescriptor).toList();
    }

    @Override
    public List<AnnotationDescriptor> propertyAnnotations(AccessibleObject property) {
        return ReflectionUtils.getAnnotations(property).stream()
                .map(JvmComponentIntrospector::annotationDescriptor).toList();
    }

    @Override
    public List<AnnotationDescriptor> packageAnnotations(Class<?> type) {
        return type == null || type.getPackage() == null ? List.of()
                : ReflectionUtils.getPackageAnnotations(type.getPackage()).stream()
                        .map(JvmComponentIntrospector::annotationDescriptor).toList();
    }

    @Override
    public <A extends Annotation, R> Optional<R> typeAnnotationAs(
            Class<?> type, Class<A> annotationType, Class<R> projectionType) {
        return ReflectionUtils.getAnnotationAs(type, annotationType, projectionType);
    }

    /**
     * Returns the type annotation using JVM annotation semantics.
     */
    public <A extends Annotation> Optional<A> typeAnnotation(Class<?> type, Class<A> annotationType) {
        return Optional.ofNullable(ReflectionUtils.getTypeAnnotation(type, annotationType));
    }

    @Override
    public <A extends Annotation, R> Optional<R> packageAnnotationAs(
            Class<?> type, Class<A> annotationType, Class<R> projectionType) {
        return type == null || type.getPackage() == null ? Optional.empty()
                : ReflectionUtils.getAnnotationAs(type.getPackage(), annotationType, projectionType);
    }

    /**
     * Returns the package annotation using JVM package annotation semantics.
     */
    public <A extends Annotation> Optional<A> packageAnnotation(Package p, Class<A> annotationType) {
        return ReflectionUtils.getPackageAnnotation(p, annotationType);
    }

    @Override
    public <A extends Annotation, R> Optional<R> executableAnnotationAs(
            Executable executable, Class<A> annotationType, Class<R> projectionType) {
        return ReflectionUtils.getAnnotationAs(executable, annotationType, projectionType);
    }

    /**
     * Returns the executable annotation using JVM annotation semantics, including supported meta-annotations.
     */
    public <A extends Annotation> Optional<A> executableAnnotation(Executable executable, Class<A> annotationType) {
        return ReflectionUtils.getAnnotation(executable, annotationType);
    }

    @Override
    public <A extends Annotation, R> List<R> executableAnnotationsAs(
            Executable executable, Class<A> annotationType, Class<R> projectionType) {
        return ReflectionUtils.getMethodAnnotations(executable, annotationType).stream()
                .flatMap(annotation -> ReflectionUtils.getAnnotationAs(
                        annotation, annotationType, projectionType).stream())
                .toList();
    }

    @Override
    public Comparator<Class<?>> typeSpecificityComparator() {
        return ReflectionUtils.getClassSpecificityComparator();
    }

    /**
     * Returns whether the executable has the supplied annotation using JVM method annotation semantics.
     */
    public boolean isExecutableAnnotationPresent(Executable executable, Class<? extends Annotation> annotationType) {
        return ReflectionUtils.isMethodAnnotationPresent(executable, annotationType);
    }

    @Override
    public <A extends Annotation> Optional<AccessibleObject> annotatedProperty(
            Class<?> type, Class<A> annotationType) {
        return ReflectionUtils.getAnnotatedProperty(type, annotationType)
                .map(property -> (AccessibleObject) property);
    }

    @Override
    public <A extends Annotation> List<AccessibleObject> annotatedProperties(
            Class<?> type, Class<A> annotationType) {
        return ReflectionUtils.getAnnotatedProperties(type, annotationType).stream()
                .map(AccessibleObject.class::cast)
                .toList();
    }

    @Override
    public <A extends Annotation> Optional<String> annotatedPropertyName(
            Class<?> type, Class<A> annotationType) {
        return ReflectionUtils.getAnnotatedProperty(type, annotationType)
                .map(ReflectionUtils::getPropertyName);
    }

    @Override
    public <A extends Annotation> Optional<Object> annotatedPropertyValue(
            Object target, Class<A> annotationType) {
        return ReflectionUtils.getAnnotatedPropertyValue(target, annotationType);
    }

    @Override
    public Object propertyValue(AccessibleObject property, Object target, boolean forceAccess) {
        return ReflectionUtils.getValue(property, target, forceAccess);
    }

    @Override
    public String propertyName(AccessibleObject property) {
        return ReflectionUtils.getPropertyName(property);
    }

    @Override
    public Class<?> propertyType(AccessibleObject property) {
        return ReflectionUtils.getPropertyType(property);
    }

    @Override
    public Optional<Class<?>> collectionElementType(AccessibleObject property) {
        return ReflectionUtils.getCollectionElementType(property);
    }

    @Override
    public Object invoke(Executable executable, Object target, List<?> arguments) {
        if (!(executable instanceof Method) && !(executable instanceof Constructor<?>)) {
            throw new ComponentRegistryException("Unsupported executable: " + executable);
        }
        Object[] args = arguments == null ? new Object[0] : arguments.toArray();
        return prepareInvocation(executable).invoke(target, args.length, i -> args[i]);
    }

    /**
     * Invokes a JVM executable with varargs while keeping invocation behind the platform backend.
     */
    public Object invoke(Executable executable, Object target, Object... arguments) {
        return invoke(executable, target, arguments == null ? List.of() : Arrays.asList(arguments));
    }

    /**
     * Creates an instance through the JVM executable backend.
     */
    @SuppressWarnings("unchecked")
    public <T> T instantiate(Constructor<?> constructor, Object... arguments) {
        return (T) invoke(constructor, null, arguments);
    }

    private static AnnotationDescriptor annotationDescriptor(Annotation annotation) {
        return annotationDescriptor(annotation, new LinkedHashSet<>());
    }

    private static AnnotationDescriptor annotationDescriptor(
            Annotation annotation, Set<Class<? extends Annotation>> visited) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        Arrays.stream(annotation.annotationType().getDeclaredMethods())
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> attributes.put(method.getName(), values(invokeAttribute(method, annotation))));
        visited.add(annotation.annotationType());
        List<AnnotationDescriptor> metaAnnotations = Arrays.stream(annotation.annotationType().getAnnotations())
                .filter(metaAnnotation -> includeMetaAnnotation(metaAnnotation, visited))
                .map(metaAnnotation -> annotationDescriptor(metaAnnotation, new LinkedHashSet<>(visited)))
                .toList();
        return new AnnotationDescriptor(
                annotation.annotationType().getSimpleName(), annotation.annotationType().getName(), attributes,
                metaAnnotations);
    }

    private static boolean includeMetaAnnotation(
            Annotation annotation, Set<Class<? extends Annotation>> visited) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        return !annotationType.getName().startsWith("java.lang.annotation.")
               && !visited.contains(annotationType);
    }

    private static Object invokeAttribute(Method method, Annotation annotation) {
        try {
            return method.invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new ComponentRegistryException("Failed to read annotation metadata: " + annotation, e);
        }
    }

    private static List<String> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.add(value(Array.get(value, i)));
            }
            return result;
        }
        return List.of(value(value));
    }

    private static String value(Object value) {
        if (value instanceof Class<?> type) {
            return type.getName();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Annotation annotation) {
            return "@" + annotation.annotationType().getName();
        }
        return String.valueOf(value);
    }
}
