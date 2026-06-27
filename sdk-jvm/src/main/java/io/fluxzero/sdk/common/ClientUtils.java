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

package io.fluxzero.sdk.common;

import io.fluxzero.common.DefaultMemoizingBiFunction;
import io.fluxzero.common.DefaultMemoizingFunction;
import io.fluxzero.common.DefaultMemoizingSupplier;
import io.fluxzero.common.MemoizingBiFunction;
import io.fluxzero.common.MemoizingFunction;
import io.fluxzero.common.MemoizingSupplier;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.serialization.Revision;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.SearchParameters;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.registry.AnnotationDescriptor;
import io.fluxzero.sdk.registry.ComponentMetadataLookup;
import io.fluxzero.sdk.registry.ComponentMetadataLookups;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.TemporalUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoUnit.DAYS;

/**
 * Utility class offering client-side support functions for working with Fluxzero.
 * <p>
 * Unlike {@link io.fluxzero.common.ObjectUtils}, this class is specifically intended for use within Fluxzero client
 * applications and can make use of infrastructure components such as
 * {@link io.fluxzero.sdk.Fluxzero}.
 * <p>
 * It provides convenience methods for:
 * <ul>
 *   <li>Memoization with lifespans via Fluxzero's clock</li>
 *   <li>Topic resolution for annotated handler methods</li>
 *   <li>Determining whether handlers are local or self-tracking</li>
 *   <li>Time-bound execution blocking</li>
 *   <li>Safe annotation parsing and revision introspection</li>
 *   <li>Truncating {@link java.time.temporal.Temporal} values</li>
 * </ul>
 */
@Slf4j
public class ClientUtils {
    private static final ClassValue<LocalHandlerAnnotationCache> localHandlerAnnotationCache = new ClassValue<>() {
        @Override
        protected LocalHandlerAnnotationCache computeValue(Class<?> type) {
            return new LocalHandlerAnnotationCache(type);
        }
    };

    /**
     * A marker used to denote specific log entries that should be ignored or treated differently, typically to bypass
     * error handling in logging frameworks.
     * <p>
     * This marker is created using the {@code MarkerFactory} with the identifier "ignoreError". It can be useful in
     * scenarios where certain log messages need to be flagged for exclusion from error-reporting workflows.
     */
    public static final Marker ignoreMarker = MarkerFactory.getMarker("ignoreError");

    /**
     * Blocks until all futures are complete or the maximum duration has elapsed.
     * <p>
     * Useful for tracking batched async operations (e.g., event publishing or indexing).
     *
     * @param maxDuration the maximum time to wait
     * @param futures     the set of futures to wait on
     */
    public static void waitForResults(Duration maxDuration, Collection<? extends Future<?>> futures) {
        Instant deadline = Instant.now().plus(maxDuration);
        for (Future<?> f : futures) {
            try {
                f.get(Math.max(0, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Thread was interrupted before receiving all expected results", e);
                return;
            } catch (TimeoutException e) {
                log.warn("Timed out before having received all expected results", e);
                return;
            } catch (ExecutionException ignore) {
            }
        }
    }

    /**
     * Returns whether the specified method or its declaring class is marked with {@link TrackSelf}.
     *
     * @param target the handler class
     * @param method the method to inspect
     * @return {@code true} if marked for self-tracking, {@code false} otherwise
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isSelfTracking(Class<?> target, Executable method) {
        return getTrackSelfAnnotation(target, method).isPresent();
    }

    /**
     * Returns whether the specified class, one of its interfaces, or its package is marked with {@link TrackSelf}.
     *
     * @param target the handler class
     * @return {@code true} if marked for self-tracking, {@code false} otherwise
     */
    public static boolean isSelfTracking(Class<?> target) {
        return getTrackSelfAnnotation(target, null).isPresent();
    }

    /**
     * Retrieves the {@link LocalHandler} annotation associated with a given handler, from its method, its declaring
     * class, or ancestral package, if present.
     */
    public static Optional<LocalHandler> getLocalHandlerAnnotation(HandlerInvoker handlerInvoker) {
        return getLocalHandlerAnnotation(handlerInvoker.getTargetClass(), handlerInvoker.getMethod());
    }

    /**
     * Retrieves the {@link LocalHandler} annotation associated with a given method, its declaring class, or ancestral
     * package, if present.
     */
    public static Optional<LocalHandler> getLocalHandlerAnnotation(Class<?> target,
                                                                   java.lang.reflect.Executable method) {
        if (target == null) {
            return Optional.empty();
        }
        return localHandlerAnnotationCache.get(target).get(method);
    }

    private static Optional<LocalHandler> computeLocalHandlerAnnotation(Class<?> target,
                                                                        java.lang.reflect.Executable method) {
        Optional<LocalHandler> metadata = getLocalHandlerAnnotationFromMetadata(target, method);
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata.filter(LocalHandler::value);
        }
        return JvmComponentIntrospector.getInstance().getAnnotation(method, LocalHandler.class)
                .or(() -> Optional.ofNullable(
                        JvmComponentIntrospector.getInstance().getTypeAnnotation(target, LocalHandler.class)))
                .or(() -> JvmComponentIntrospector.getInstance().getPackageAnnotation(
                        target.getPackage(), LocalHandler.class))
                .filter(LocalHandler::value);
    }

    private static Optional<TrackSelf> getTrackSelfAnnotation(Class<?> target, Executable method) {
        Optional<TrackSelf> metadata = getTrackSelfAnnotationFromMetadata(target, method);
        if (metadata.isPresent() || ComponentMetadataLookups.generatedOnlyMode()) {
            return metadata;
        }
        return JvmComponentIntrospector.getInstance().getAnnotation(method, TrackSelf.class)
                .or(() -> Optional.ofNullable(
                        JvmComponentIntrospector.getInstance().getTypeAnnotation(target, TrackSelf.class)))
                .or(() -> JvmComponentIntrospector.getInstance().getPackageAnnotation(
                        target.getPackage(), TrackSelf.class));
    }

    private static Optional<LocalHandler> getLocalHandlerAnnotationFromMetadata(Class<?> target, Executable method) {
        return ComponentMetadataLookups.lookup(target)
                .flatMap(lookup -> {
                    Optional<LocalHandler> methodAnnotation = method == null
                            ? Optional.empty() : localHandler(
                            ComponentMetadataLookups.executableAnnotations(lookup, method));
                    return methodAnnotation
                            .or(() -> localHandler(lookup.typeAnnotations(target.getName())))
                            .or(() -> localHandler(lookup.packageAnnotations(target.getPackageName())));
                });
    }

    private static Optional<TrackSelf> getTrackSelfAnnotationFromMetadata(Class<?> target, Executable method) {
        return ComponentMetadataLookups.lookup(target)
                .flatMap(lookup -> {
                    Optional<TrackSelf> methodAnnotation = method == null
                            ? Optional.empty() : trackSelf(
                            ComponentMetadataLookups.executableAnnotations(lookup, method));
                    return methodAnnotation
                            .or(() -> trackSelf(lookup.typeAnnotations(target.getName())))
                            .or(() -> trackSelf(lookup.packageAnnotations(target.getPackageName())));
                });
    }

    private static Optional<LocalHandler> localHandler(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(LocalHandler.class.getName())
                                      || annotation.name().equals(LocalHandler.class.getSimpleName()))
                .findFirst()
                .map(annotation -> new LocalHandlerMetadata(
                        annotation.booleanValue("value", true),
                        annotation.booleanValue("logMessage", false),
                        annotation.booleanValue("logMetrics", false),
                        annotation.booleanValue("allowExternalMessages", false)));
    }

    private static Optional<TrackSelf> trackSelf(List<AnnotationDescriptor> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.qualifiedName().equals(TrackSelf.class.getName())
                                      || annotation.name().equals(TrackSelf.class.getSimpleName()))
                .findFirst()
                .map(ignored -> new TrackSelfMetadata());
    }

    private record LocalHandlerMetadata(
            boolean value, boolean logMessage, boolean logMetrics, boolean allowExternalMessages)
            implements LocalHandler {
        @Override
        public Class<? extends Annotation> annotationType() {
            return LocalHandler.class;
        }
    }

    private record TrackSelfMetadata() implements TrackSelf {
        @Override
        public Class<? extends Annotation> annotationType() {
            return TrackSelf.class;
        }
    }

    private static class LocalHandlerAnnotationCache {
        private final Class<?> target;
        private final Optional<LocalHandler> classAnnotation;
        private final ConcurrentHashMap<Executable, Optional<LocalHandler>> methodAnnotations =
                new ConcurrentHashMap<>();
        private final Function<Executable, Optional<LocalHandler>> computer = this::compute;

        private LocalHandlerAnnotationCache(Class<?> target) {
            this.target = target;
            this.classAnnotation = computeLocalHandlerAnnotation(target, null);
        }

        Optional<LocalHandler> get(Executable method) {
            return method == null ? classAnnotation : methodAnnotations.computeIfAbsent(method, computer);
        }

        private Optional<LocalHandler> compute(Executable method) {
            return computeLocalHandlerAnnotation(target, method);
        }
    }

    /**
     * Determines if the specified handler method handles messages locally. A handler is considered a local if it is
     * explicitly annotated as such using {@link LocalHandler} or meets the criteria for local self-handling, i.e.:
     * {@link #isLocalSelfHandler} returns {@code true}.
     */
    public static boolean isLocalHandler(HandlerInvoker invoker, HasMessage message) {
        if (invoker.getMethod() == null) {
            return false;
        }
        return getLocalHandlerAnnotation(invoker.getTargetClass(), invoker.getMethod()).isPresent()
               || isLocalSelfHandler(invoker, message);
    }

    /**
     * Returns whether the handler method should only handle messages from the same instance ("self"). This is true when
     * the handler’s payload type equals the message type and no {@link TrackSelf} annotation is present.
     */
    public static boolean isLocalSelfHandler(HandlerInvoker invoker, HasMessage message) {
        return isSelfHandler(invoker, message)
               && !isSelfTracking(invoker.getTargetClass(), invoker.getMethod());
    }

    static boolean isSelfHandler(HandlerInvoker invoker, HasMessage message) {
        return Objects.equals(invoker.getTargetClass(), message.getPayloadClass());
    }

    @SuppressWarnings("SameParameterValue")
    static boolean isTrackingHandler(Class<?> target, java.lang.reflect.Executable method) {
        return getLocalHandlerAnnotation(target, method).map(LocalHandler::allowExternalMessages).orElse(true);
    }

    /**
     * Resolves the configured order for the given component.
     * <p>
     * This first checks Fluxzero's own {@link Order} annotation and then falls back to Spring's
     * {@code org.springframework.core.annotation.Order} when present on the classpath.
     *
     * @param component the component to inspect
     * @return the configured order value, or {@code 0} if no order annotation is present
     */
    public static int orderOf(Object component) {
        return computeOrder(component.getClass());
    }

    /**
     * Loads implementations of the given service using Java's {@link ServiceLoader}.
     * <p>
     * Misconfigured providers that are present in {@code META-INF/services} but missing from the current classpath are
     * skipped. This can happen, for example, when test resources contribute service descriptors without also exposing
     * the corresponding provider classes to downstream modules.
     *
     * @param serviceType the service interface to load
     * @param <T>         the service type
     * @return discovered service implementations, ordered by {@link #orderOf(Object)}
     */
    public static <T> java.util.List<T> loadServices(Class<T> serviceType) {
        java.util.List<T> services = new java.util.ArrayList<>();
        ServiceLoader<T> loader = ServiceLoader.load(serviceType);
        var iterator = loader.iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    return services.stream().sorted(java.util.Comparator.comparingInt(ClientUtils::orderOf)).toList();
                }
                services.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                log.warn("Skipping misconfigured service provider for {}", serviceType.getName(), e);
            }
        }
    }

    private static int computeOrder(Class<?> type) {
        return JvmComponentIntrospector.getInstance().typeAnnotation(type, Order.class)
                .map(Order::value)
                .or(() -> springOrderOf(type)).orElse(0);
    }

    private static Optional<Integer> springOrderOf(Class<?> type) {
        return JvmComponentIntrospector.getInstance().getTypeAnnotations(type).stream()
                .filter(annotation -> annotation.annotationType().getName()
                        .equals("org.springframework.core.annotation.Order"))
                .findFirst()
                .flatMap(ClientUtils::readOrderValue);
    }

    private static Optional<Integer> readOrderValue(Annotation annotation) {
        try {
            return Optional.of((Integer) annotation.annotationType().getMethod("value").invoke(annotation));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read Spring @Order value", e);
        }
    }

    /**
     * Memoizes the given supplier using a default memoization strategy.
     */
    public static <T> MemoizingSupplier<T> memoize(Supplier<T> supplier) {
        return ObjectUtils.memoize(supplier);
    }

    /**
     * Memoizes the given function using a default memoization strategy.
     */
    public static <K, V> MemoizingFunction<K, V> memoize(Function<K, V> supplier) {
        return ObjectUtils.memoize(supplier);
    }

    /**
     * Memoizes the given bi-function using a default memoization strategy.
     */
    public static <T, U, R> MemoizingBiFunction<T, U, R> memoize(BiFunction<T, U, R> supplier) {
        return ObjectUtils.memoize(supplier);
    }

    /**
     * Memoizes the given supplier using a time-based lifespan and Fluxzero’s internal clock.
     */
    public static <T> MemoizingSupplier<T> memoize(Supplier<T> supplier, Duration lifespan) {
        return new DefaultMemoizingSupplier<>(supplier, lifespan, Fluxzero.currentClock());
    }

    /**
     * Memoizes the given function using a time-based lifespan and Fluxzero's internal clock.
     */
    public static <K, V> MemoizingFunction<K, V> memoize(Function<K, V> supplier, Duration lifespan) {
        return new DefaultMemoizingFunction<>(supplier, lifespan, Fluxzero.currentClock());
    }

    /**
     * Memoizes the given bi-function using a time-based lifespan and Fluxzero's internal clock.
     */
    public static <T, U, R> MemoizingBiFunction<T, U, R> memoize(BiFunction<T, U, R> supplier,
                                                                 Duration lifespan) {
        return new DefaultMemoizingBiFunction<>(supplier, lifespan, Fluxzero.currentClock());
    }

    /**
     * Extracts the revision number from the {@link Revision} annotation on the given object’s class.
     *
     * @return the revision number, or 0 if not present
     */
    public static int getRevisionNumber(Object object) {
        return Optional.ofNullable(object).map(o -> o.getClass().getAnnotation(Revision.class))
                .map(Revision::value).orElse(0);
    }

    /**
     * Determines the collection name used for document indexing and search based on the annotated handler or document
     * type.
     */
    public static String determineSearchCollection(@NonNull Object c) {
        return JvmComponentIntrospector.getInstance().ifClass(c) instanceof Class<?> type
                ? getSearchParameters(type).getCollection() : c.toString();
    }

    /**
     * Returns the effective {@link SearchParameters} for the given type, using the {@link Searchable} annotation.
     * Defaults to a collection name matching the class’s simple name if not explicitly set.
     */
    public static SearchParameters getSearchParameters(Class<?> type) {
        return JvmComponentIntrospector.getInstance().getAnnotationAs(type, Searchable.class, SearchParameters.class)
                .map(SearchParameters::substituteProperties)
                .map(p -> p.getCollection() == null ? p.withCollection(type.getSimpleName()) : p)
                .orElseGet(() -> new SearchParameters(true, type.getSimpleName(), null, null));
    }

    /**
     * Extracts all topics associated with the given handler object and message type.
     * <p>
     * The topics are derived based on the handler's annotations or inferred class properties. This method delegates the
     * operation to another overload which processes a collection of handler classes.
     *
     * @param messageType the type of the message (e.g., DOCUMENT or CUSTOM)
     * @param handler     the handler object used to determine topics
     * @return a set of topic names associated with the handler and message type
     */
    public static Set<String> getTopics(MessageType messageType, Object handler) {
        return getTopics(messageType, Collections.singleton(JvmComponentIntrospector.getInstance().asClass(handler)));
    }

    /**
     * Extracts all topics from {@link HandleDocument} or {@link HandleCustom} annotated methods for the given classes.
     *
     * @param messageType    the type of message (DOCUMENT or CUSTOM)
     * @param handlerClasses the classes to inspect
     * @return a set of topic names
     */
    public static Set<String> getTopics(MessageType messageType, Collection<Class<?>> handlerClasses) {
        var annotationResolver = MetadataExecutableAnnotationResolver.create();
        return switch (messageType) {
            case DOCUMENT -> handlerClasses.stream()
                    .flatMap(handlerClass -> JvmComponentIntrospector.getInstance().getAllMethods(handlerClass).stream())
                    .flatMap(m -> annotationResolver.getAnnotation(m, HandleDocument.class)
                            .map(HandleDocument.class::cast)
                            .map(a -> getTopic(a, m)).stream())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            case CUSTOM -> handlerClasses.stream()
                    .flatMap(handlerClass -> JvmComponentIntrospector.getInstance().getAllMethods(handlerClass).stream())
                    .flatMap(m -> annotationResolver.getAnnotation(m, HandleCustom.class)
                            .map(HandleCustom.class::cast)
                            .filter(h -> !h.disabled())
                            .map(ClientUtils::getTopic).stream())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            default -> Collections.emptySet();
        };
    }

    /**
     * Extracts the topic associated with a given {@link HandleDocument} annotation or {@link Executable}. The topic is
     * determined based on the document collection name, the associated document class, or parameter type of the
     * executable.
     *
     * @param handleDocument the {@link HandleDocument} annotation containing metadata about the document handler. Can
     *                       specify the document collection or class.
     * @param executable     the {@link Executable} (e.g., method or constructor) used to infer the topic if the
     *                       annotation does not provide one. The parameter type of the executable may determine the
     *                       topic.
     * @return the topic as a String, or {@code null} if no valid topic is determined.
     */
    public static String getTopic(HandleDocument handleDocument, Executable executable) {
        return Optional.ofNullable(handleDocument)
                .filter(h -> !h.disabled())
                .flatMap(h -> Optional.ofNullable(h.value()).filter(s -> !s.isBlank())
                        .or(() -> Optional.ofNullable(h.collection()).filter(s -> !s.isBlank()))
                        .or(() -> Void.class.equals(h.documentClass()) ? Optional.empty() :
                                Optional.of(ClientUtils.determineSearchCollection(h.documentClass()))))
                .or(() -> Arrays.stream(executable.getParameters()).findFirst().map(Parameter::getType).map(
                        ClientUtils::determineSearchCollection))
                .filter(s -> !s.isBlank()).orElse(null);
    }

    private static String getTopic(HandleCustom handleCustom) {
        String topic = handleCustom.value().isBlank() ? handleCustom.topic() : handleCustom.value();
        return topic.isBlank() ? null : topic;
    }

    /**
     * Truncates a {@link Temporal} (e.g., {@link LocalDateTime}) to the specified unit (e.g., {@code MONTHS} or
     * {@code YEARS}). Automatically adjusts the truncation unit for compound units like {@code MONTHS -> DAYS}.
     *
     * @param timestamp the temporal value to truncate
     * @param unit      the unit to truncate to
     * @return a truncated version of the original value
     */
    @SuppressWarnings("unchecked")
    public static <T extends Temporal> T truncate(T timestamp, TemporalUnit unit) {
        T result = unit instanceof ChronoUnit chronoUnit ?
                switch (chronoUnit) {
                    case YEARS -> (T) timestamp.with(TemporalAdjusters.firstDayOfYear());
                    case MONTHS -> (T) timestamp.with(TemporalAdjusters.firstDayOfMonth());
                    default -> timestamp;
                } : timestamp;
        TemporalUnit truncateUnit = unit instanceof ChronoUnit chronoUnit ?
                switch (chronoUnit) {
                    case YEARS, MONTHS -> DAYS;
                    default -> chronoUnit;
                } : unit;

        if (result instanceof LocalDate) {
            return result;
        }
        if (result instanceof LocalDateTime r) {
            return (T) r.truncatedTo(truncateUnit);
        }
        if (result instanceof ZonedDateTime r) {
            return (T) r.truncatedTo(truncateUnit);
        }
        if (result instanceof OffsetDateTime r) {
            return (T) r.truncatedTo(truncateUnit);
        }
        if (result instanceof Instant r) {
            return (T) r.truncatedTo(truncateUnit);
        }
        throw new UnsupportedOperationException("Unsupported temporal type: " + result.getClass());
    }
}
