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

package io.fluxzero.common.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.fluxzero.common.Leaf;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.serialization.DefaultTypeRegistry;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.serialization.TypeRegistry;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;

import java.beans.PropertyDescriptor;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.common.reflection.DefaultMemberInvoker.asInvoker;
import static java.beans.Introspector.getBeanInfo;
import static java.lang.Integer.compare;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.commons.lang3.ClassUtils.getAllInterfaces;
import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * Utility class for high-performance reflection-based operations across the Fluxzero Runtime.
 *
 * <p><strong>This class is intended for internal use only.</strong> It provides a wide range of methods for accessing
 * and manipulating object members via reflection, including:
 *
 * <ul>
 *   <li>Resolving annotated fields and methods (with support for meta-annotations)</li>
 *   <li>Accessing nested property paths via getters and setters (e.g., {@code "user/address/street"})</li>
 *   <li>Efficient member access using {@link DefaultMemberInvoker}, which leverages {@code LambdaMetafactory} for
 *       near-native invocation performance</li>
 *   <li>Generic type resolution, including collection element types and supertype inference</li>
 *   <li>Annotation hierarchy traversal and support for Kotlin nullability reflection (if available)</li>
 *   <li>Utilities for determining override relationships, class hierarchies, and bean property metadata</li>
 *   <li>Robust handling of {@link Class} loading and safe access to field/method/property values</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 * This class uses extensive caching and memoization to avoid redundant reflective operations at runtime. All public
 * methods are safe to use in high-performance contexts such as annotation scanning, handler dispatching, and JSON
 * schema validation.
 *
 * <h2>Modular Use</h2>
 * If Fluxzero adopts JPMS (Project Jigsaw) in the future, this utility would likely be placed in an internal module
 * and <strong>not exported</strong> to consumers, to preserve encapsulation and forward compatibility.
 *
 * @see MemberInvoker
 * @see DefaultMemberInvoker
 * @see JsonUtils
 * @see TypeRegistry
 */
public class ReflectionUtils {
    private static final int ACCESS_MODIFIERS = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;
    private static final List<Integer> ACCESS_ORDER = List.of(Modifier.PRIVATE, 0, Modifier.PROTECTED, Modifier.PUBLIC);

    private static final ClassValue<TypeMetadata> typeMetadataCache = new ClassValue<>() {
        @Override
        protected TypeMetadata computeValue(Class<?> type) {
            return new TypeMetadata(type);
        }
    };
    private static final Function<String, Optional<Class<?>>> classForFqnCache
            = memoize(ReflectionUtils::computeClassForFqn);
    private static final Supplier<TypeRegistry> typeRegistrySupplier = memoize(() -> new DefaultTypeRegistry());
    private static final Function<String, Optional<Class<?>>> classForNameCache
            = memoize(ReflectionUtils::computeClassForName);
    private static final BiFunction<Package, Boolean, Collection<? extends Annotation>> packageAnnotationsCache =
            memoize(ReflectionUtils::computePackageAnnotations);
    private static final Function<Parameter, Boolean> isNullableCache = memoize(
            parameter -> getParameterOverrideHierarchy(parameter).anyMatch(p -> {
                if (isKotlinReflectionSupported()) {
                    var kotlinParameter = KotlinReflectionUtils.asKotlinParameter(p);
                    if (kotlinParameter != null && kotlinParameter.getType().isMarkedNullable()) {
                        return true;
                    }
                }
                return stream(p.getAnnotations()).anyMatch(
                        a -> a.annotationType().getSimpleName().equals("Nullable"));
            })
    );

    @Getter
    private static final Comparator<Class<?>> classSpecificityComparator = (o1, o2)
            -> Objects.equals(o1, o2) ? 0
            : o1 == null ? 1 : o2 == null ? -1
            : o1.isAssignableFrom(o2) ? 1
            : o2.isAssignableFrom(o1) ? -1
            : o1.isInterface() && !o2.isInterface() ? 1
            : !o1.isInterface() && o2.isInterface() ? -1
            : specificity(o2) - specificity(o1);

    static int specificity(Class<?> type) {
        int depth = 0;
        Class<?> t = type;
        if (type.isInterface()) {
            while (t.getInterfaces().length > 0) {
                depth++;
                t = t.getInterfaces()[0];
            }
        } else {
            while (t != null) {
                depth++;
                t = t.getSuperclass();
            }
        }
        return depth;
    }


    public static Stream<Method> getMethodOverrideHierarchy(Method method) {
        return MethodUtils.getOverrideHierarchy(method, ClassUtils.Interfaces.INCLUDE).stream();
    }

    public static Stream<Parameter> getParameterOverrideHierarchy(Parameter parameter) {
        if (parameter.getDeclaringExecutable() instanceof Method method) {
            return getMethodOverrideHierarchy(method).flatMap(m -> Arrays.stream(m.getParameters())
                    .filter(p -> p.getName().equals(parameter.getName())));
        }
        return Stream.of(parameter);
    }

    public static boolean isKotlinReflectionSupported() {
        return ReflectionUtils.classExists("kotlin.reflect.full.KClasses");
    }

    /**
     * Determines and returns the {@link Class} of the provided object if it is either
     * an instance of {@link Class} or a Kotlin class that can be converted using Kotlin reflection utilities.
     * If the object does not satisfy these conditions or Kotlin reflection is not supported, returns {@code null}.
     *
     * @param value The object to check and convert to {@link Class}, if possible.
     * @return The {@link Class} of the provided object, or {@code null} if the object is not a class or cannot be converted.
     */
    public static Class<?> ifClass(Object value) {
        if (value instanceof Class<?> c) {
            return c;
        }
        if (isKotlinReflectionSupported()) {
            return KotlinReflectionUtils.convertIfKotlinClass(value);
        }
        return null;
    }

    public static boolean isClass(Object value) {
        return ifClass(value) != null;
    }

    public static Class<?> asClass(@NonNull Object value) {
        return ifClass(value) instanceof Class<?> c ? c : value.getClass();
    }

    public static TypeMetadata getTypeMetadata(@NonNull Class<?> type) {
        return typeMetadataCache.get(type);
    }

    public static List<Method> getAllMethods(Class<?> type) {
        return getTypeMetadata(type).methods();
    }

    public static Optional<Method> getMethod(Class<?> type, String name) {
        return getTypeMetadata(type).method(name);
    }

    /*
       Adopted from https://stackoverflow.com/questions/28400408/what-is-the-new-way-of-getting-all-methods-of-a-class-including-inherited-defau
    */
    private static List<Method> computeAllMethods(Class<?> type) {
        Predicate<Method> include = m -> !m.getDeclaringClass().isHidden() && !m.isSynthetic() &&
                                         Character.isJavaIdentifierStart(m.getName().charAt(0))
                                         && m.getName().chars().skip(1).allMatch(Character::isJavaIdentifierPart);

        Set<Method> methods = new LinkedHashSet<>();
        Collections.addAll(methods, type.getMethods());
        methods.removeIf(include.negate());
        Stream.of(type.getDeclaredMethods()).filter(include).forEach(methods::add);

        final int access = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;

        Package p = type.getPackage();
        Map<Object, Set<Package>> types = new HashMap<>();
        final Set<Package> pkgIndependent = Collections.emptySet();
        for (Method m : methods) {
            int acc = m.getModifiers() & access;
            if (acc == Modifier.PRIVATE) {
                continue;
            }
            if (acc != 0) {
                types.put(methodKey(m), pkgIndependent);
            } else {
                types.computeIfAbsent(methodKey(m), x -> new HashSet<>()).add(p);
            }
        }
        include = include.and(m -> {
            int acc = m.getModifiers() & access;
            return acc != 0 ? acc == Modifier.PRIVATE
                              || types.putIfAbsent(methodKey(m), pkgIndependent) == null :
                    noPkgOverride(m, types, pkgIndependent);
        });
        for (type = type.getSuperclass(); type != null; type = type.getSuperclass()) {
            Stream.of(type.getDeclaredMethods()).filter(include).forEach(methods::add);
        }
        return new ArrayList<>(methods);
    }

    private static boolean noPkgOverride(
            Method m, Map<Object, Set<Package>> types, Set<Package> pkgIndependent) {
        Set<Package> pkg = types.computeIfAbsent(methodKey(m), key -> new HashSet<>());
        return pkg != pkgIndependent && pkg.add(m.getDeclaringClass().getPackage());
    }

    private static Object methodKey(Method m) {
        return Arrays.asList(m.getName(),
                             MethodType.methodType(m.getReturnType(), m.getParameterTypes()));
    }

    private static List<Field> computeAllFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Collections.addAll(result, current.getDeclaredFields());
        }
        return result;
    }

    public static List<? extends AccessibleObject> getAnnotatedProperties(Class<?> target,
                                                                          Class<? extends Annotation> annotation) {
        return getTypeMetadata(target).annotatedProperties(annotation);
    }

    public static Optional<? extends AccessibleObject> getAnnotatedProperty(Object target,
                                                                            Class<? extends Annotation> annotation) {
        return target == null ? Optional.empty() : getAnnotatedProperty(asClass(target), annotation);
    }

    public static Optional<? extends AccessibleObject> getAnnotatedProperty(Class<?> target,
                                                                            Class<? extends Annotation> annotation) {
        return getTypeMetadata(target).annotatedProperty(annotation);
    }

    public static Optional<MemberInvoker> getAnnotatedPropertyInvoker(
            Class<?> target, Class<? extends Annotation> annotation) {
        return getTypeMetadata(target).annotatedPropertyInvoker(annotation);
    }

    public static TypeMetadata.PropertyPathMetadata getPropertyPathMetadata(Class<?> target, String propertyPath) {
        return target == null || isEmpty(propertyPath)
                ? TypeMetadata.PropertyPathMetadata.missing()
                : getTypeMetadata(target).propertyPath(propertyPath);
    }

    public static Optional<Object> getAnnotatedPropertyValue(Object target, Class<? extends Annotation> annotation) {
        return getAnnotatedProperty(target, annotation).map(m -> getValue(m, target, false));
    }

    public static Collection<Object> getAnnotatedPropertyValues(Object target, Class<? extends Annotation> annotation) {
        if (target == null) {
            return emptyList();
        }
        List<Object> results = new ArrayList<>();
        for (AccessibleObject location : getAnnotatedProperties(target.getClass(), annotation)) {
            Object value = getValue(location, target, false);
            if (value != null) {
                results.add(value);
            }
        }
        return results;
    }

    public static Optional<String> getAnnotatedPropertyName(Object target, Class<? extends Annotation> annotation) {
        return getAnnotatedProperty(target, annotation).map(ReflectionUtils::getPropertyName);
    }

    public static String getPropertyName(AccessibleObject property) {
        if (property instanceof Field field) {
            return field.getName();
        }
        if (property instanceof Method method) {
            String name = method.getName();
            if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
                char[] c = name.toCharArray();
                c[3] = Character.toLowerCase(c[3]);
                name = String.valueOf(c, 3, c.length - 3);
            } else if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                char[] c = name.toCharArray();
                c[2] = Character.toLowerCase(c[2]);
                name = String.valueOf(c, 2, c.length - 2);
            }
            return name;
        }
        throw new UnsupportedOperationException("Not a property: " + property);
    }

    public static List<Method> getAnnotatedMethods(Class<?> target, Class<? extends Annotation> annotation) {
        return getTypeMetadata(target).annotatedMethods(annotation);
    }

    public static List<Method> getAnnotatedMethods(Object target, Class<? extends Annotation> annotation) {
        return target == null ? List.of() : getAnnotatedMethods(target.getClass(), annotation);
    }

    public static boolean isMethodAnnotationPresent(Executable method, Class<? extends Annotation> annotation) {
        return getMethodAnnotation(method, annotation).isPresent();
    }

    public static List<Field> getAnnotatedFields(Class<?> target, Class<? extends Annotation> annotation) {
        return getTypeMetadata(target).annotatedFields(annotation);
    }

    public static List<Field> getAnnotatedFields(Object target, Class<? extends Annotation> annotation) {
        return target == null ? emptyList() : getAnnotatedFields(asClass(target), annotation);
    }

    public static boolean isAnnotationPresent(Class<?> type, Class<? extends Annotation> annotationType) {
        return getTypeAnnotation(type, annotationType) != null;
    }

    @SuppressWarnings("unchecked")
    public static <A extends Annotation> A getTypeAnnotation(Class<?> type,
                                                             Class<? extends Annotation> annotationType) {
        if (type == null) {
            return null;
        }
        A result = (A) getTypeMetadata(type).typeAnnotation(annotationType);
        return result != null ? result : getTypeAnnotation(type.getEnclosingClass(), annotationType);
    }

    public static Collection<? extends Annotation> getTypeAnnotations(Class<?> type) {
        return getTypeMetadata(type).typeAnnotations();
    }

    @SuppressWarnings("unchecked")
    public static <A extends Annotation> Optional<A> getPackageAnnotation(Package p, Class<A> annotationType) {
        return getPackageAnnotations(p).stream()
                .filter(a -> a.annotationType().equals(annotationType))
                .map(a -> (A) a).findFirst();
    }

    public static Collection<? extends Annotation> getPackageAnnotations(Package p) {
        return getPackageAnnotations(p, true);
    }

    public static Collection<? extends Annotation> getPackageAnnotations(Package p, boolean recursive) {
        return packageAnnotationsCache.apply(p, recursive);
    }

    private static Collection<? extends Annotation> computePackageAnnotations(Package p, boolean recursive) {
        if (p == null) {
            return emptyList();
        }
        Stream<Annotation> stream = stream(p.getAnnotations());
        if (recursive) {
            stream = Stream.concat(stream,
                                   computePackageAnnotations(getFirstKnownAncestorPackage(p.getName()), true).stream());
            return stream.collect(toMap(Annotation::annotationType, Function.identity(),
                                        (a, b) -> a, LinkedHashMap::new)).values();
        } else {
            return stream.toList();
        }
    }

    /*
        Read a property
     */

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> readProperty(String propertyPath, Object target) {
        if (target == null) {
            return Optional.empty();
        }
        if (propertyPath == null || propertyPath.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(getTypeMetadata(target.getClass()).getter(propertyPath).apply(target))
                    .map(v -> (T) v);
        } catch (PropertyNotFoundException ignored) {
            return Optional.empty();
        }
    }

    public static List<Type> getTypeArguments(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            return Arrays.asList(pt.getActualTypeArguments());
        }
        return emptyList();
    }

    public static <T extends Type> T getFirstTypeArgument(Type genericType) {
        return getTypeArgument(genericType, 0);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Type> T getTypeArgument(Type genericType, int index) {
        if (genericType instanceof ParameterizedType pt) {
            return (T) pt.getActualTypeArguments()[index];
        }
        throw new IllegalArgumentException("Type is raw and does not define arguments");
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getPropertyAnnotation(String propertyPath, Object target) {
        if (target == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(getTypeMetadata(target.getClass()).getter(propertyPath).apply(target))
                    .map(v -> (T) v);
        } catch (PropertyNotFoundException ignored) {
            return Optional.empty();
        }
    }

    public static boolean hasProperty(String propertyPath, Object target) {
        if (target == null || propertyPath == null) {
            return false;
        }
        try {
            getTypeMetadata(target.getClass()).getter(propertyPath).apply(target);
            return true;
        } catch (PropertyNotFoundException ignored) {
            return false;
        }
    }

    public static boolean hasReturnType(Executable executable) {
        return !(executable instanceof Method m) || !void.class.equals(m.getReturnType());
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getFieldValue(String fieldName, Object target) {
        return target == null ? Optional.empty() :
                getField(asClass(target), fieldName).map(f -> (T) getValue(f, target, true));
    }

    @SneakyThrows
    public static Object getValue(AccessibleObject fieldOrMethod, Object target, boolean forceAccess) {
        if (fieldOrMethod instanceof Method) {
            return asInvoker((Method) fieldOrMethod, forceAccess).invoke(target);
        }
        if (fieldOrMethod instanceof Field) {
            return asInvoker((Field) fieldOrMethod, forceAccess).invoke(target);
        }
        throw new IllegalStateException("Object property should be field or method: " + fieldOrMethod);
    }

    @SneakyThrows
    public static Object getValue(AccessibleObject fieldOrMethod, Object target) {
        return getValue(fieldOrMethod, target, true);
    }

    @SneakyThrows
    public static String getName(AccessibleObject fieldOrMethod) {
        if (fieldOrMethod instanceof Member) {
            return ((Member) fieldOrMethod).getName();
        }
        throw new IllegalStateException("Object property should be field or method: " + fieldOrMethod);
    }

    @SneakyThrows
    public static Class<?> getEnclosingClass(AccessibleObject fieldOrMethod) {
        if (fieldOrMethod instanceof Member) {
            return ((Member) fieldOrMethod).getDeclaringClass();
        }
        throw new IllegalStateException("Object property should be field or method: " + fieldOrMethod);
    }

    public static Class<?> getPropertyType(AccessibleObject fieldOrMethod) {
        if (fieldOrMethod instanceof Method) {
            return ((Method) fieldOrMethod).getReturnType();
        }
        if (fieldOrMethod instanceof Field) {
            return ((Field) fieldOrMethod).getType();
        }
        throw new IllegalStateException("Object property should be field or method: " + fieldOrMethod);
    }

    public static Type getGenericPropertyType(AccessibleObject fieldOrMethod) {
        if (fieldOrMethod instanceof Method) {
            return ((Method) fieldOrMethod).getGenericReturnType();
        }
        if (fieldOrMethod instanceof Field) {
            return ((Field) fieldOrMethod).getGenericType();
        }
        throw new IllegalStateException("Object property should be field or method: " + fieldOrMethod);
    }

    /*
        Write a property
     */

    public static void writeProperty(String propertyPath, Object target, Object value) {
        if (target != null) {
            try {
                getTypeMetadata(target.getClass()).setter(propertyPath).accept(target, value);
            } catch (PropertyNotFoundException ignored) {
            }
        }
    }

    @SneakyThrows
    private static void setValue(Member fieldOrMethod, Object target, Object value) {
        asInvoker(fieldOrMethod).invoke(target, value);
    }

    public static boolean isOrHas(Annotation annotation, Class<? extends Annotation> annotationType) {
        return annotation != null && (Objects.equals(annotation.annotationType(), annotationType)
                                      || annotation.annotationType().isAnnotationPresent(annotationType));
    }

    public static boolean isOrHas(Class<?> type, Class<? extends Annotation> annotationType) {
        return type != null && (Objects.equals(type, annotationType) || type.isAnnotationPresent(annotationType));
    }

    public static Optional<Field> getField(Class<?> owner, String name) {
        return getTypeMetadata(owner).field(name);
    }

    public static Class<?> getCallerClass() {
        return StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
                .walk(s -> {
                    Iterator<StackWalker.StackFrame> iterator = s.skip(1).iterator();
                    Class<?> invoker = iterator.next().getDeclaringClass();
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

    public static boolean isNullable(Parameter parameter) {
        return isNullableCache.apply(parameter);
    }

    @SuppressWarnings("unchecked")
    public static <T> T asInstance(Object classOrInstance) {
        if (ifClass(classOrInstance) instanceof Class<?> c) {
            try {
                return (T) ensureAccessible(c.getDeclaredConstructor()).newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(format(
                        "Failed to create an instance of class %s. Does it have an accessible default constructor?",
                        classOrInstance), e);
            }
        }
        return (T) classOrInstance;
    }

    public static int getParameterIndex(Parameter parameter) {
        var executable = parameter.getDeclaringExecutable();
        for (int i = 0; i < executable.getParameters().length; i++) {
            if (executable.getParameters()[i].equals(parameter)) {
                return i;
            }
        }
        throw new IllegalStateException("Could not get parameter index of " + parameter);
    }

    /*
    Based on this SO question https://stackoverflow.com/questions/9797212/finding-the-nearest-common-superclass-or-superinterface-of-a-collection-of-cla
     */
    public static List<Class<?>> determineCommonAncestors(Collection<?> elements) {
        return determineCommonAncestors(
                elements.stream().map(e -> e == null ? Void.class : e.getClass()).distinct()
                        .toArray(Class<?>[]::new));
    }

    static List<Class<?>> determineCommonAncestors(Class<?>... classes) {
        return switch (classes.length) {
            case 0 -> Collections.emptyList();
            case 1 -> List.of(classes);
            default -> {
                Set<Class<?>> rollingIntersect = new LinkedHashSet<>(getClassHierarchy(classes[0]));
                for (int i = 1; i < classes.length; i++) {
                    rollingIntersect.retainAll(getClassHierarchy(classes[i]));
                }
                yield rollingIntersect.isEmpty() ? List.of(Object.class) : new LinkedList<>(rollingIntersect);
            }
        };
    }

    static Set<Class<?>> getClassHierarchy(Class<?> clazz) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        Set<Class<?>> nextLevel = new LinkedHashSet<>();
        nextLevel.add(clazz);
        do {
            classes.addAll(nextLevel);
            Set<Class<?>> thisLevel = new LinkedHashSet<>(nextLevel);
            nextLevel.clear();
            for (Class<?> each : thisLevel) {
                Class<?> superClass = each.getSuperclass();
                if (superClass != null && superClass != Object.class) {
                    nextLevel.add(superClass);
                }
                Collections.addAll(nextLevel, each.getInterfaces());
            }
        } while (!nextLevel.isEmpty());
        return classes;
    }

    public static List<Package> getPackageAndParentPackages(Package p) {
        List<Package> result = new ArrayList<>();
        while (p != null) {
            result.add(p);
            p = getFirstKnownAncestorPackage(p.getName());
        }
        return result;
    }

    static Package getFirstKnownAncestorPackage(String childPackageName) {
        for (String parentName = getParentPackageName(childPackageName); parentName != null;
                parentName = getParentPackageName(parentName)) {
            Package result = tryGetPackage(parentName);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    static String getParentPackageName(String name) {
        int lastIndex = name.lastIndexOf(".");
        return lastIndex < 0 ? null : name.substring(0, lastIndex);
    }

    private static Package tryGetPackage(String name) {
        Package definedPackage = ReflectionUtils.class.getClassLoader().getDefinedPackage(name);
        if (definedPackage != null) {
            return definedPackage;
        }
        String packagePath = name.replace('.', '/');
        URL resource = ReflectionUtils.class.getClassLoader().getResource(packagePath);
        if (resource == null) {
            return null;
        }
        File packageDir = new File(resource.getFile());
        if (packageDir.exists() && packageDir.isDirectory()
            && new File(resource.getFile() + "/package-info.class").exists()) {
            try {
                var result = classForName(name + ".package-info");
                if (result != null) {
                    return result.getPackage();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static <A extends Annotation> Optional<A> getMemberAnnotation(Class<?> type, String memberName,
                                                                         Class<? extends Annotation> a) {
        return getAnnotatedMethods(type, a).stream().filter(m -> m.getName().equals(memberName)).findFirst()
                .flatMap(m -> ReflectionUtils.<A>getMethodAnnotation(m, a)).or(() -> {
                    String alias = memberName.startsWith("get") ? memberName.substring(3) :
                            memberName.startsWith("is") ? memberName.substring(2) : memberName;
                    return getAnnotatedFields(type, a).stream()
                            .filter(f -> f.getName().equalsIgnoreCase(memberName) || f.getName()
                                    .equalsIgnoreCase(alias)).findFirst()
                            .flatMap(f -> ReflectionUtils.getFieldAnnotation(f, a));
                });
    }

    public static boolean isStatic(Executable method) {
        return Modifier.isStatic(method.getModifiers());
    }

    private static final Set<Class<?>> leafValueTypes = Set.of(
            String.class, Number.class, Boolean.class, Character.class,
            java.util.UUID.class, java.net.URI.class,
            java.net.URL.class, java.util.Locale.class,
            java.util.Currency.class, java.time.temporal.Temporal.class,
            java.time.temporal.TemporalAmount.class
    );

    /**
     * Returns whether the given value should be treated as a terminal scalar during reflective traversal.
     * <p>
     * Leaf values are not recursively inspected for nested annotated properties. This is used by infrastructure such
     * as search indexing and data protection to decide whether a value should be processed as a whole or traversed
     * further.
     * <p>
     * A value is considered a leaf when it is:
     * <ul>
     *   <li>{@code null}</li>
     *   <li>one of the built-in scalar types known to Fluxzero, such as strings, numbers, booleans, temporal values,
     *       URIs, locales, and similar primitives/value objects</li>
     *   <li>an enum</li>
     *   <li>an instance of a type implementing {@link Leaf}</li>
     * </ul>
     *
     * @param value the value to inspect
     * @return {@code true} if the value should be treated as a leaf, {@code false} otherwise
     */
    public static boolean isLeafValue(Object value) {
        if (value == null) {
            return true;
        }
        Class<?> type = value.getClass();
        return leafValueTypes.stream().anyMatch(t -> t.isAssignableFrom(type))
               || type.isEnum()
               || Leaf.class.isAssignableFrom(type);
    }

    public static boolean isAnnotationPresent(Parameter parameter, Class<? extends Annotation> annotationType) {
        return getAnnotation(parameter, annotationType).isPresent();
    }

    public static Type getGenericType(Class<?> candidate, Class<?> wantedClass) {
        return GenericTypeResolver.getGenericType(candidate, wantedClass);
    }

    /**
     * Lazily computed reflection metadata for a single Java type.
     * <p>
     * This is the central cache for type-level reflection in Fluxzero. It keeps the structural model of a type
     * together in one place: methods, fields, property descriptors, type annotations, annotated members, cached
     * property-path accessors, and compiled {@link MemberInvoker} instances.
     */
    public static final class TypeMetadata {
        private final Class<?> type;
        private final List<Method> methods;
        private final List<Field> fields;
        private final Map<String, List<Method>> methodsByName;
        private final Map<String, Field> fieldsByName;
        private final Map<String, Field> declaredFieldsByName;
        private final Map<String, PropertyDescriptor> propertyDescriptors;
        private final Collection<? extends Annotation> typeAnnotations;

        private final ConcurrentHashMap<Class<? extends Annotation>, Optional<? extends Annotation>> typeAnnotationCache =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Class<? extends Annotation>, List<Field>> annotatedFields =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Class<? extends Annotation>, List<Method>> annotatedMethods =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Class<? extends Annotation>, List<? extends AccessibleObject>>
                annotatedProperties = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Class<? extends Annotation>, Optional<? extends AccessibleObject>>
                annotatedProperty = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Class<? extends Annotation>, Optional<MemberInvoker>>
                annotatedPropertyInvokers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<AnnotatedMemberKey, Optional<? extends Annotation>> fieldAnnotationCache =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<AnnotatedMemberKey, Optional<? extends Annotation>> methodAnnotationCache =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<AnnotatedMemberKey, List<? extends Annotation>> methodAnnotationsCache =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<AnnotationProjectionKey, Optional<?>> annotationAsCache =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Function<Object, Object>> getters = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, BiConsumer<Object, Object>> setters = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, PropertyPathMetadata> propertyPaths = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MemberInvokerKey, MemberInvoker> invokers = new ConcurrentHashMap<>();

        @SneakyThrows
        private TypeMetadata(Class<?> type) {
            this.type = type;
            this.methods = List.copyOf(computeAllMethods(type));
            this.fields = List.copyOf(computeAllFields(type));
            this.typeAnnotations = Stream.concat(stream(type.getAnnotations()),
                                                 getAllInterfaces(type).stream()
                                                         .flatMap(iType -> stream(iType.getAnnotations())))
                    .filter(ObjectUtils.distinctByKey(Annotation::annotationType))
                    .collect(toCollection(LinkedHashSet::new));

            LinkedHashMap<String, List<Method>> methodsByName = new LinkedHashMap<>();
            for (Method method : methods) {
                methodsByName.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(method);
            }
            this.methodsByName = methodsByName.entrySet().stream()
                    .collect(toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()), (a, b) -> a,
                                   LinkedHashMap::new));

            LinkedHashMap<String, Field> fieldsByName = new LinkedHashMap<>();
            for (Field field : fields) {
                fieldsByName.putIfAbsent(field.getName(), field);
            }
            this.fieldsByName = Map.copyOf(fieldsByName);

            LinkedHashMap<String, Field> declaredFieldsByName = new LinkedHashMap<>();
            for (Field field : type.getDeclaredFields()) {
                declaredFieldsByName.putIfAbsent(field.getName(), field);
            }
            this.declaredFieldsByName = Map.copyOf(declaredFieldsByName);

            this.propertyDescriptors = stream(getBeanInfo(type, null).getPropertyDescriptors())
                    .collect(toMap(PropertyDescriptor::getName, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        }

        public Class<?> type() {
            return type;
        }

        public List<Method> methods() {
            return methods;
        }

        public Optional<Method> method(String name) {
            return methods(name).stream().findFirst();
        }

        public List<Method> methods(String name) {
            return methodsByName.getOrDefault(name, List.of());
        }

        public List<Field> fields() {
            return fields;
        }

        public Optional<Field> field(String name) {
            return Optional.ofNullable(fieldsByName.get(name));
        }

        public boolean declaresField(String fieldName) {
            return !isEmpty(fieldName) && declaredFieldsByName.containsKey(fieldName);
        }

        public Collection<? extends Annotation> typeAnnotations() {
            return typeAnnotations;
        }

        @SuppressWarnings("unchecked")
        public <A extends Annotation> A typeAnnotation(Class<? extends Annotation> annotationType) {
            return (A) typeAnnotationCache.computeIfAbsent(annotationType, this::computeTypeAnnotation).orElse(null);
        }

        public List<Field> annotatedFields(Class<? extends Annotation> annotation) {
            return annotatedFields.computeIfAbsent(annotation, this::computeAnnotatedFields);
        }

        public List<Method> annotatedMethods(Class<? extends Annotation> annotation) {
            return annotatedMethods.computeIfAbsent(annotation, this::computeAnnotatedMethods);
        }

        public List<? extends AccessibleObject> annotatedProperties(Class<? extends Annotation> annotation) {
            return annotatedProperties.computeIfAbsent(annotation, this::computeAnnotatedProperties);
        }

        public Optional<? extends AccessibleObject> annotatedProperty(Class<? extends Annotation> annotation) {
            return annotatedProperty.computeIfAbsent(annotation, a -> annotatedProperties(a).stream().findFirst());
        }

        public Optional<MemberInvoker> annotatedPropertyInvoker(Class<? extends Annotation> annotation) {
            return annotatedPropertyInvokers.computeIfAbsent(annotation, a -> annotatedProperty(a)
                    .filter(Member.class::isInstance)
                    .map(Member.class::cast)
                    .map(DefaultMemberInvoker::asInvoker));
        }

        public Function<Object, Object> getter(String propertyPath) {
            return getters.computeIfAbsent(normalizePropertyPath(propertyPath), this::computeNestedGetter);
        }

        public BiConsumer<Object, Object> setter(String propertyPath) {
            return setters.computeIfAbsent(normalizePropertyPath(propertyPath), this::computeNestedSetter);
        }

        public PropertyPathMetadata propertyPath(String propertyPath) {
            String normalizedPath = normalizePropertyPath(propertyPath);
            PropertyPathMetadata cached = propertyPaths.get(normalizedPath);
            if (cached != null) {
                return cached;
            }
            PropertyPathMetadata computed = computePropertyPath(normalizedPath);
            PropertyPathMetadata existing = propertyPaths.putIfAbsent(normalizedPath, computed);
            return existing == null ? computed : existing;
        }

        public MemberInvoker invoker(Member member, boolean forceAccess) {
            return invokers.computeIfAbsent(new MemberInvokerKey(member, forceAccess),
                                            ignored -> new DefaultMemberInvoker(member, forceAccess));
        }

        @SuppressWarnings("unchecked")
        public <A extends Annotation> Optional<A> fieldAnnotation(Field field, Class<? extends Annotation> annotation) {
            return (Optional<A>) fieldAnnotationCache.computeIfAbsent(
                    new AnnotatedMemberKey(field, annotation),
                    ignored -> resolveFieldAnnotation(field, annotation));
        }

        @SuppressWarnings("unchecked")
        public <A extends Annotation> Optional<A> methodAnnotation(
                Executable executable, Class<? extends Annotation> annotation) {
            return (Optional<A>) methodAnnotationCache.computeIfAbsent(
                    new AnnotatedMemberKey(executable, annotation),
                    ignored -> resolveMethodAnnotation(executable, annotation));
        }

        @SuppressWarnings("unchecked")
        public <A extends Annotation> List<A> methodAnnotations(
                Executable executable, Class<? extends Annotation> annotation) {
            return (List<A>) methodAnnotationsCache.computeIfAbsent(
                    new AnnotatedMemberKey(executable, annotation),
                    ignored -> resolveMethodAnnotations(executable, annotation));
        }

        @SuppressWarnings("unchecked")
        public <T> Optional<T> annotationAs(AnnotatedElement member,
                                            Class<? extends Annotation> annotationType,
                                            Class<? extends T> returnType) {
            return (Optional<T>) annotationAsCache.computeIfAbsent(
                    new AnnotationProjectionKey(member, annotationType, returnType),
                    ignored -> computeAnnotationAs(member, annotationType, returnType));
        }

        private Optional<? extends Annotation> computeTypeAnnotation(Class<? extends Annotation> annotationType) {
            for (Annotation annotation : typeAnnotations) {
                if (annotation.annotationType().equals(annotationType)) {
                    return Optional.of(annotation);
                }
                for (Annotation metaAnnotation : getTypeMetadata(annotation.annotationType()).typeAnnotations()) {
                    if (metaAnnotation.annotationType().equals(annotationType)) {
                        return Optional.of(annotation);
                    }
                }
            }
            return Optional.empty();
        }

        private <T> Optional<T> computeAnnotationAs(AnnotatedElement member,
                                                    Class<? extends Annotation> annotationType,
                                                    Class<? extends T> returnType) {
            Annotation annotation = switch (member) {
                case Executable e -> methodAnnotation(e, annotationType).orElse(null);
                case Field f -> fieldAnnotation(f, annotationType).orElse(null);
                case Class<?> ignored -> typeAnnotation(annotationType);
                default -> getTopLevelAnnotation(member, annotationType);
            };
            return ReflectionUtils.getAnnotationAs(annotation, annotationType, returnType);
        }

        private List<Field> computeAnnotatedFields(Class<? extends Annotation> annotation) {
            return fields.stream().filter(field -> fieldAnnotation(field, annotation).isPresent()).toList();
        }

        private List<Method> computeAnnotatedMethods(Class<? extends Annotation> annotation) {
            return methods.stream().filter(method -> methodAnnotation(method, annotation).isPresent()).toList();
        }

        private List<? extends AccessibleObject> computeAnnotatedProperties(Class<? extends Annotation> annotation) {
            List<AccessibleObject> result = new ArrayList<>(annotatedFields(annotation));
            getAllInterfaces(type).forEach(i -> result.addAll(getTypeMetadata(i).annotatedFields(annotation)));
            result.addAll(annotatedMethods(annotation).stream()
                                  .filter(m -> m.getParameterCount() == 0)
                                  .filter(ReflectionUtils::hasReturnType)
                                  .filter(m -> !m.getDeclaringClass().isAssignableFrom(m.getReturnType()))
                                  .toList());
            getAllInterfaces(type).forEach(i -> result.addAll(getTypeMetadata(i).annotatedMethods(annotation).stream()
                                  .filter(m -> m.getParameterCount() == 0)
                                  .filter(ReflectionUtils::hasReturnType)
                                  .filter(m -> !m.getDeclaringClass().isAssignableFrom(m.getReturnType()))
                                  .toList()));
            LinkedHashMap<String, AccessibleObject> deduplicated = new LinkedHashMap<>();
            result.forEach(property -> deduplicated.putIfAbsent(getPropertyName(property), property));
            deduplicated.values().forEach(ReflectionUtils::ensureAccessible);
            return List.copyOf(deduplicated.values());
        }

        private PropertyPathMetadata computePropertyPath(String propertyPath) {
            String[] parts = splitPropertyPath(propertyPath);
            return parts.length == 0 ? PropertyPathMetadata.missing() : computePropertyPath(parts, 0);
        }

        private PropertyPathMetadata computePropertyPath(String[] parts, int index) {
            if (Map.class.isAssignableFrom(type) || ObjectNode.class.isAssignableFrom(type)) {
                return PropertyPathMetadata.dynamic(Object.class);
            }
            String propertyName = parts[index];
            Optional<Member> propertyMember = propertyMember(propertyName);
            if (propertyMember.isEmpty()) {
                return PropertyPathMetadata.missing();
            }
            Class<?> propertyType = getPropertyType((AccessibleObject) propertyMember.get());
            if (index == parts.length - 1) {
                return PropertyPathMetadata.declared(propertyType);
            }
            if (Collection.class.isAssignableFrom(propertyType)) {
                Optional<Class<?>> elementType = getCollectionElementType((AccessibleObject) propertyMember.get());
                if (elementType.isEmpty() || Object.class.equals(elementType.get())) {
                    return PropertyPathMetadata.dynamic(Object.class);
                }
                return PropertyPathMetadata.copyOf(
                        getTypeMetadata(elementType.get()).propertyPath(remainingPropertyPath(parts, index + 1)));
            }
            if (Object.class.equals(propertyType)) {
                return PropertyPathMetadata.dynamic(Object.class);
            }
            return PropertyPathMetadata.copyOf(
                    getTypeMetadata(propertyType).propertyPath(remainingPropertyPath(parts, index + 1)));
        }

        private Function<Object, Object> computeNestedGetter(String propertyPath) {
            String[] parts = splitPropertyPath(propertyPath);
            if (parts.length == 1) {
                return computeGetter(parts[0]);
            }
            return object -> {
                for (String part : parts) {
                    if (object == null) {
                        return null;
                    }
                    if (object instanceof Collection<?> collection) {
                        object = collection.stream().flatMap(
                                o -> {
                                    Object value = getTypeMetadata(o.getClass()).getter(part).apply(o);
                                    return value instanceof Collection<?> c ? c.stream() : Stream.of(value);
                                }).collect(toList());
                    } else {
                        object = getTypeMetadata(object.getClass()).getter(part).apply(object);
                    }
                }
                return object;
            };
        }

        private Function<Object, Object> computeGetter(String propertyName) {
            if (ObjectNode.class.isAssignableFrom(type)) {
                return target -> {
                    JsonNode path = ((ObjectNode) target).path(propertyName);
                    return switch (path) {
                        case TextNode n -> n.asText();
                        case NumericNode n -> n.numberValue();
                        case BooleanNode b -> b.booleanValue();
                        default -> path;
                    };
                };
            }
            if (Map.class.isAssignableFrom(type)) {
                return target -> ((Map<?, ?>) target).get(propertyName);
            }
            PropertyNotFoundException notFoundException = new PropertyNotFoundException(propertyName, type);
            return propertyMember(propertyName)
                    .map(DefaultMemberInvoker::asInvoker)
                    .<Function<Object, Object>>map(invoker -> invoker::invoke)
                    .orElseGet(() -> ignored -> {
                        throw notFoundException;
                    });
        }

        private BiConsumer<Object, Object> computeNestedSetter(String propertyPath) {
            String[] parts = splitPropertyPath(propertyPath);
            if (parts.length == 1) {
                return computeSetter(parts[0]);
            }
            Function<Object, Object> parentSupplier = getter(
                    Arrays.stream(parts).limit(parts.length - 1).collect(Collectors.joining("/")));
            return (object, value) -> {
                Object parent = parentSupplier.apply(object);
                if (parent != null) {
                    getTypeMetadata(parent.getClass()).setter(parts[parts.length - 1]).accept(parent, value);
                }
            };
        }

        private BiConsumer<Object, Object> computeSetter(String propertyName) {
            if (ObjectNode.class.isAssignableFrom(type)) {
                return (target, propertyValue) -> ((ObjectNode) target).putPOJO(propertyName, propertyValue);
            }
            PropertyNotFoundException notFoundException = new PropertyNotFoundException(propertyName, type);
            return Optional.ofNullable(propertyDescriptors.get(propertyName))
                    .map(PropertyDescriptor::getWriteMethod)
                    .<Member>map(method -> method)
                    .or(() -> field(propertyName).map(field -> (Member) ensureAccessible(field)))
                    .map(DefaultMemberInvoker::asInvoker)
                    .<BiConsumer<Object, Object>>map(invoker -> invoker::invoke)
                    .orElseGet(() -> (target, value) -> {
                        throw notFoundException;
                    });
        }

        private Optional<Member> propertyMember(String propertyName) {
            return method("get" + StringUtils.capitalize(propertyName))
                    .filter(ReflectionUtils::hasReturnType).map(m -> (Member) m)
                    .or(() -> method(propertyName).filter(ReflectionUtils::hasReturnType).map(m -> (Member) m))
                    .or(() -> field(propertyName).map(field -> (Member) field));
        }

        @Getter
        public static final class PropertyPathMetadata {
            private final boolean exists;
            private final boolean dynamic;
            private final Class<?> leafType;
            private final AtomicBoolean missingTimestampWarningLogged = new AtomicBoolean();
            private final AtomicBoolean unsupportedTimestampWarningLogged = new AtomicBoolean();

            private PropertyPathMetadata(boolean exists, boolean dynamic, Class<?> leafType) {
                this.exists = exists;
                this.dynamic = dynamic;
                this.leafType = leafType;
            }

            private static PropertyPathMetadata missing() {
                return new PropertyPathMetadata(false, false, null);
            }

            private static PropertyPathMetadata declared(Class<?> leafType) {
                return new PropertyPathMetadata(true, false, leafType);
            }

            private static PropertyPathMetadata dynamic(Class<?> leafType) {
                return new PropertyPathMetadata(true, true, leafType);
            }

            private static PropertyPathMetadata copyOf(PropertyPathMetadata source) {
                if (!source.exists) {
                    return missing();
                }
                return source.dynamic ? dynamic(source.leafType) : declared(source.leafType);
            }

            public boolean supportsTimeConversion() {
                return dynamic || isTimePropertyType(leafType);
            }

            public boolean exists() {
                return exists;
            }

            public boolean dynamic() {
                return dynamic;
            }

            public boolean shouldLogMissingTimestampWarning() {
                return !exists && missingTimestampWarningLogged.compareAndSet(false, true);
            }

            public boolean shouldLogUnsupportedTimestampWarning() {
                return unsupportedTimestampWarningLogged.compareAndSet(false, true);
            }
        }
    }

    private record MemberInvokerKey(Member member, boolean forceAccess) {
    }

    private record AnnotatedMemberKey(AnnotatedElement element, Class<? extends Annotation> annotationType) {
    }

    private record AnnotationProjectionKey(
            AnnotatedElement element, Class<? extends Annotation> annotationType, Class<?> returnType) {
    }

    @Value
    private static class PropertyNotFoundException extends RuntimeException {
        @NonNull
        String propertyName;
        @NonNull
        Class<?> type;
    }

    public static Optional<Class<?>> getCollectionElementType(AccessibleObject fieldOrMethod) {
        if (fieldOrMethod instanceof Method) {
            return getCollectionElementType(((Method) fieldOrMethod).getGenericReturnType());
        }
        if (fieldOrMethod instanceof Field) {
            return getCollectionElementType(((Field) fieldOrMethod).getGenericType());
        }
        throw new IllegalStateException("Object property should be field or method: " + fieldOrMethod);
    }

    public static Optional<Class<?>> getCollectionElementType(Type parameterizedType) {
        if (parameterizedType instanceof ParameterizedType) {
            Type elementType;
            Type rawType = ((ParameterizedType) parameterizedType).getRawType();
            if (rawType instanceof Class<?> && Map.class.isAssignableFrom((Class<?>) rawType)) {
                elementType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[1];
            } else {
                elementType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            }
            if (elementType instanceof WildcardType) {
                Type[] upperBounds = ((WildcardType) elementType).getUpperBounds();
                elementType = upperBounds.length > 0 ? upperBounds[0] : null;
            }
            return Optional.of(elementType instanceof Class<?> ? (Class<?>) elementType : Object.class);
        }
        return Optional.empty();
    }

    public static boolean declaresField(Class<?> target, String fieldName) {
        return getTypeMetadata(target).declaresField(fieldName);
    }

    @SneakyThrows
    public static void setField(Field field, Object target, Object value) {
        ensureAccessible(field).set(target, value);
    }

    @SneakyThrows
    public static void setField(String fieldName, Object target, Object value) {
        setField(target.getClass().getDeclaredField(fieldName), target, value);
    }

    public static <T extends AccessibleObject> T ensureAccessible(T member) {
        member.setAccessible(true);
        return member;
    }

    /*
        Returns meta annotation if desired
     */

    public static <A extends Annotation> Optional<A> getAnnotation(AnnotatedElement m, Class<A> a) {
        return getAnnotationAs(m, a, a);
    }

    /*
        Returns any object
     */
    public static <T> Optional<T> getAnnotationAs(Class<?> target, Class<? extends Annotation> annotationType,
                                                  Class<T> returnType) {
        if (target == null) {
            return Optional.empty();
        }
        Annotation annotation = getTypeAnnotation(target, annotationType);
        return getAnnotationAs(annotation, annotationType, returnType);
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getAnnotationAs(AnnotatedElement member,
                                                  Class<? extends Annotation> annotationType,
                                                  Class<? extends T> returnType) {
        return switch (member) {
            case null -> Optional.empty();
            case Executable e -> getTypeMetadata(e.getDeclaringClass()).annotationAs(e, annotationType, returnType);
            case Field f -> getTypeMetadata(f.getDeclaringClass()).annotationAs(f, annotationType, returnType);
            case Class<?> c -> getAnnotationAs(c, annotationType, (Class<T>) returnType);
            case Package p -> getAnnotationAs((Annotation) getPackageAnnotation(p, annotationType).orElse(null),
                                              annotationType, returnType);
            default -> getAnnotationAs((Annotation) getTopLevelAnnotation(member, annotationType),
                                       annotationType, returnType);
        };
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getAnnotationAs(Annotation annotation,
                                                  Class<? extends Annotation> targetAnnotation,
                                                  Class<? extends T> returnType) {
        if (targetAnnotation == null) {
            return Optional.empty();
        }
        if (annotation == null) {
            return Optional.empty();
        }
        if (targetAnnotation.equals(returnType)) {
            if (annotation.annotationType().equals(returnType)) {
                return Optional.of((T) annotation);
            }
            return Optional.of((T) annotation.annotationType().getAnnotation(targetAnnotation));
        }
        Class<? extends Annotation> matchedType = annotation.annotationType();
        Map<String, Object> params = new HashMap<>();
        if (!matchedType.equals(targetAnnotation)) {
            var typeAnnotation = matchedType.getAnnotation(targetAnnotation);
            for (Method method : targetAnnotation.getDeclaredMethods()) {
                params.put(method.getName(), method.invoke(typeAnnotation));
            }
        }
        for (Method method : matchedType.getDeclaredMethods()) {
            params.put(method.getName(), method.invoke(annotation));
        }
        if (Map.class.equals(returnType)) {
            return Optional.of((T) params);
        }
        return Optional.of(JsonUtils.convertValue(params, returnType));
    }

    public static <T> T convertAnnotation(Annotation annotation, Class<? extends T> returnType) {
        return getAnnotationAs(annotation, annotation.annotationType(), returnType).orElseThrow();
    }

    public static boolean has(Class<? extends Annotation> annotationClass, Method method) {
        return getMethodAnnotation(method, annotationClass).isPresent();
    }

    public static boolean has(Class<? extends Annotation> annotationClass, Parameter parameter) {
        for (Annotation annotation : parameter.getAnnotations()) {
            if (isOrHas(annotation, annotationClass)) {
                return true;
            }
        }
        return false;
    }

    public static <A extends Annotation> Optional<A> getFieldAnnotation(Field f, Class<? extends Annotation> a) {
        return getTypeMetadata(f.getDeclaringClass()).fieldAnnotation(f, a);
    }

    /*
       Adopted from https://stackoverflow.com/questions/49105303/how-to-get-annotation-from-overridden-method-in-java/49164791

       Returns annotation or meta annotation.
    */
    public static <A extends Annotation> Optional<A> getMethodAnnotation(Executable m, Class<? extends Annotation> a) {
        return getTypeMetadata(m.getDeclaringClass()).methodAnnotation(m, a);
    }

    public static <A extends Annotation> List<A> getMethodAnnotations(Executable m, Class<? extends Annotation> a) {
        return getTypeMetadata(m.getDeclaringClass()).methodAnnotations(m, a);
    }

    private static boolean isTimePropertyType(Class<?> propertyType) {
        return propertyType != null && (LocalDate.class.isAssignableFrom(propertyType)
                                        || LocalDateTime.class.isAssignableFrom(propertyType)
                                        || TemporalAccessor.class.isAssignableFrom(propertyType)
                                        || String.class.isAssignableFrom(propertyType));
    }

    private static String normalizePropertyPath(String propertyPath) {
        return Arrays.stream(propertyPath.replace('.', '/').split("/"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("/"));
    }

    private static String[] splitPropertyPath(String propertyPath) {
        String normalized = normalizePropertyPath(propertyPath);
        return normalized.isEmpty() ? new String[0] : normalized.split("/");
    }

    private static String remainingPropertyPath(String[] parts, int startIndex) {
        return Arrays.stream(parts).skip(startIndex).collect(Collectors.joining("/"));
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> Optional<A> resolveFieldAnnotation(Field f, Class<? extends Annotation> a) {
        return Optional.ofNullable((A) f.getAnnotation(a)).or(() -> stream(f.getAnnotations())
                .filter(metaAnnotation -> metaAnnotation.annotationType().isAnnotationPresent(a))
                .findFirst().map(hit -> (A) hit));
    }

    private static <A extends Annotation> Optional<A> resolveMethodAnnotation(Executable m, Class<? extends Annotation> a) {
        A result = getTopLevelAnnotation(m, a);
        Class<?> c = m.getDeclaringClass();

        if (result == null) {
            for (Class<?> s = c; result == null && (s = s.getSuperclass()) != null; ) {
                result = getAnnotationOnSuper(m, s, a);
            }
            if (result == null && m instanceof Method) {
                for (Class<?> s : getAllInterfaces(c)) {
                    result = getAnnotationOnSuper(m, s, a);
                    if (result != null) {
                        break;
                    }
                }
            }
        }
        return Optional.ofNullable(result);
    }

    private static <A extends Annotation> List<A> resolveMethodAnnotations(Executable m, Class<? extends Annotation> a) {
        List<A> result = getTopLevelAnnotations(m, a);
        Class<?> c = m.getDeclaringClass();

        if (result.isEmpty()) {
            for (Class<?> s = c; result.isEmpty() && (s = s.getSuperclass()) != null; ) {
                result = getAnnotationsOnSuper(m, s, a);
            }
            if (result.isEmpty() && m instanceof Method) {
                for (Class<?> s : getAllInterfaces(c)) {
                    result = getAnnotationsOnSuper(m, s, a);
                    if (result != null) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A getTopLevelAnnotation(AnnotatedElement m, Class<? extends Annotation> a) {
        return Optional.ofNullable((A) m.getAnnotation(a)).orElseGet(() -> (A) stream(m.getAnnotations())
                .filter(metaAnnotation -> metaAnnotation.annotationType().isAnnotationPresent(a)).findFirst()
                .orElse(null));
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> List<A> getTopLevelAnnotations(AnnotatedElement m,
                                                                         Class<? extends Annotation> a) {
        return Optional.ofNullable(m.getAnnotation(a)).map(v -> Stream.of((A) v))
                .orElseGet(() -> stream(m.getAnnotations())
                        .filter(metaAnnotation -> metaAnnotation.annotationType().isAnnotationPresent(a))
                        .map(v -> (A) v))
                .collect(toCollection(ArrayList::new));
    }

    private static <A extends Annotation> A getAnnotationOnSuper(
            Executable m, Class<?> s, Class<? extends Annotation> a) {
        try {
            for (Method n : s.getDeclaredMethods()) {
                if (n.getName().equals(m.getName()) && n.getParameterCount() == m.getParameterCount()) {
                    n = s.getDeclaredMethod(m.getName(), m.getParameterTypes());
                    return overrides(m, n) ? getTopLevelAnnotation(n, a) : null;
                }
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private static <A extends Annotation> List<A> getAnnotationsOnSuper(
            Executable m, Class<?> s, Class<? extends Annotation> a) {
        try {
            for (Method n : s.getDeclaredMethods()) {
                if (n.getName().equals(m.getName()) && n.getParameterCount() == m.getParameterCount()) {
                    n = s.getDeclaredMethod(m.getName(), m.getParameterTypes());
                    return overrides(m, n) ? getTopLevelAnnotations(n, a) : emptyList();
                }
            }
        } catch (NoSuchMethodException ignored) {
        }
        return emptyList();
    }

    private static boolean overrides(Executable a, Executable b) {
        int modsA = a.getModifiers(), modsB = b.getModifiers();
        if (Modifier.isPrivate(modsA) || Modifier.isPrivate(modsB)) {
            return false;
        }
        if (Modifier.isStatic(modsA) || Modifier.isStatic(modsB)) {
            return false;
        }
        if (Modifier.isFinal(modsB)) {
            return false;
        }
        if (compareAccess(modsA, modsB) < 0) {
            return false;
        }
        return (notPackageAccess(modsA) && notPackageAccess(modsB))
               || a.getDeclaringClass().getPackage().equals(b.getDeclaringClass().getPackage());
    }

    private static boolean notPackageAccess(int mods) {
        return (mods & ACCESS_MODIFIERS) != 0;
    }

    private static int compareAccess(int lhs, int rhs) {
        return compare(ACCESS_ORDER.indexOf(lhs & ACCESS_MODIFIERS), ACCESS_ORDER.indexOf(rhs & ACCESS_MODIFIERS));
    }

    @SneakyThrows
    public static <V> V copyFields(V source, V target) {
        if (target == null || source == null) {
            return target;
        }
        if (!source.getClass().equals(target.getClass())) {
            throw new IllegalArgumentException("Source and target class should be equal");
        }
        Class<?> type = source.getClass();
        if (type.isPrimitive() || type.isArray()) {
            return source;
        }
        for (Field field : getTypeMetadata(type).fields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                ensureAccessible(field).set(target, field.get(source));
            }
        }
        return target;
    }

    @SneakyThrows
    public static Class<?> classForName(String type) {
        Optional<Class<?>> result = classForNameCache.apply(type);
        if (result.isPresent()) {
            return result.get();
        }
        throw new ClassNotFoundException(type);
    }

    public static Class<?> classForName(String type, Class<?> defaultClass) {
        return classForNameCache.apply(type).orElse(defaultClass);
    }

    public static boolean classExists(String className) {
        return classForNameCache.apply(className).isPresent();
    }

    public static String getSimpleName(Class<?> c) {
        return getSimpleName(c.getName());
    }

    public static String getSimpleName(Package p) {
        return p.getName().substring(p.getName().lastIndexOf('.') + 1);
    }

    public static String getSimpleName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.trim().isEmpty()) {
            throw new IllegalArgumentException("Fully qualified name cannot be null or empty");
        }
        int lastSeparatorIndex = Math.max(fullyQualifiedName.lastIndexOf('.'), fullyQualifiedName.lastIndexOf('$'));
        return (lastSeparatorIndex == -1) ? fullyQualifiedName : fullyQualifiedName.substring(lastSeparatorIndex + 1);
    }

    @SneakyThrows
    private static Optional<Class<?>> computeClassForFqn(String type) {
        try {
            return Optional.of(Class.forName(type.split("<")[0]));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Class<?>> computeClassForName(String name) {
        var fqnResult = classForFqnCache.apply(name);
        if (fqnResult.isPresent()) {
            return fqnResult;
        }
        return typeRegistrySupplier.get().getTypeName(name).flatMap(classForFqnCache);
    }
}
