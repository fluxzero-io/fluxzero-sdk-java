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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.handling.ExecutableView;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.common.handling.ParameterResolver;
import io.fluxzero.common.handling.ParameterView;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.correlation.DefaultCorrelationDataProvider;
import io.fluxzero.sdk.registry.JvmComponentIntrospector;
import io.fluxzero.sdk.registry.MetadataExecutableAnnotationResolver;
import io.fluxzero.sdk.tracking.Tracker;
import lombok.AllArgsConstructor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Resolves parameters annotated with {@link Trigger} by loading the original trigger message that caused the current
 * handler method to execute. This allows handlers to access the originating message of a scheduled or chained
 * invocation.
 *
 * <p>This class functions both as a {@link ParameterResolver} and a {@link MessageFilter}, enabling it to:
 * <ul>
 *   <li>Filter which messages should invoke the method based on trigger metadata (e.g., original class, message type, consumer).</li>
 *   <li>Inject the original triggering message (or just its payload) into parameters annotated with {@code @Trigger}.</li>
 * </ul>
 *
 * <p>The resolver extracts correlation metadata from the message, such as:
 * <ul>
 *   <li>{@code _trigger}: The fully qualified class name of the triggering message’s payload.</li>
 *   <li>{@code _triggerType}: The {@link MessageType} of the trigger (e.g., COMMAND, EVENT, etc.).</li>
 *   <li>{@code _consumer}: (Optional) Name of the client that originally consumed the trigger.</li>
 *   <li>{@code _correlation}: A message index pointing to the trigger message in the Runtime.</li>
 * </ul>
 *
 * <p>The trigger message is then looked up and injected into the handler parameter as:
 * <ul>
 *   <li>A {@link DeserializingMessage} if the parameter is of that type.</li>
 *   <li>A {@link Message} if the parameter type implements {@link HasMessage}.</li>
 *   <li>The original payload otherwise (i.e., when using the concrete payload type).</li>
 * </ul>
 *
 * <p>
 * If trigger information is missing, does not match the {@code @Trigger} filter, or cannot be resolved,
 * the parameter will be set to {@code null}.
 *
 * @see Trigger
 */
@AllArgsConstructor
public class TriggerParameterResolver implements ParameterResolver<HasMessage>, MessageFilter<HasMessage> {
    private final Client client;
    private final Serializer serializer;
    private final DefaultCorrelationDataProvider correlationDataProvider = DefaultCorrelationDataProvider.INSTANCE;

    /**
     * Evaluates whether the given message should be accepted by the handler method based on the associated
     * {@link Trigger} annotation.
     *
     * <p>This method checks whether the message contains valid trigger metadata and whether it matches
     * the filtering constraints declared on the handler method's {@code @Trigger} annotation.
     *
     * @param message           the incoming message being evaluated
     * @param executable        the handler method being considered
     * @param handlerAnnotation the annotation type used to mark handler methods (e.g., {@code @HandleCommand})
     * @return {@code true} if the message matches the filter criteria, {@code false} otherwise
     */
    @Override
    public boolean test(HasMessage message, Executable executable, Class<? extends Annotation> handlerAnnotation,
                        Class<?> targetClass) {
        return TriggerMetadata.of(targetClass).triggerFilter(executable).test(message);
    }

    @Override
    public boolean test(HasMessage message, ExecutableView executable,
                        Class<? extends Annotation> handlerAnnotation, Class<?> targetClass) {
        Optional<Executable> method = executable.executable();
        if (method.isPresent()) {
            return test(message, method.orElseThrow(), handlerAnnotation, targetClass);
        }
        return triggerFilter(executable.annotation(Trigger.class).orElse(null)).test(message);
    }

    @Override
    public MessageFilter<? super HasMessage> prepare(Executable executable,
                                                     Class<? extends Annotation> handlerAnnotation,
                                                     Class<?> targetClass) {
        Trigger trigger = TriggerMetadata.of(targetClass).trigger(executable).orElse(null);
        if (trigger == null) {
            return MessageFilter.allowAll();
        }
        Predicate<HasMessage> filter = triggerFilter(trigger);
        return (message, e, a, t) -> filter.test(message);
    }

    @Override
    public MessageFilter<? super HasMessage> prepare(ExecutableView executable,
                                                     Class<? extends Annotation> handlerAnnotation,
                                                     Class<?> targetClass) {
        Optional<Executable> method = executable.executable();
        if (method.isPresent()) {
            return prepare(method.orElseThrow(), handlerAnnotation, targetClass);
        }
        Trigger trigger = executable.annotation(Trigger.class).orElse(null);
        if (trigger == null) {
            return MessageFilter.allowAll();
        }
        Predicate<HasMessage> filter = triggerFilter(trigger);
        return new MessageFilter<>() {
            @Override
            public boolean test(HasMessage message, Executable method,
                                Class<? extends Annotation> annotation, Class<?> type) {
                return filter.test(message);
            }

            @Override
            public boolean test(HasMessage message, ExecutableView method,
                                Class<? extends Annotation> annotation, Class<?> type) {
                return filter.test(message);
            }
        };
    }

    /**
     * Checks if the given method parameter should be resolved by this resolver.
     *
     * <p>This method returns {@code true} if the parameter is annotated with {@link Trigger}.
     *
     * @param parameter        the parameter being checked
     * @param methodAnnotation the annotation present on the enclosing method
     * @param value            the message value to be injected (unused here)
     * @return {@code true} if the parameter can be resolved by this resolver, {@code false} otherwise
     */
    @Override
    public boolean matches(Parameter parameter, Annotation methodAnnotation, HasMessage value) {
        return JvmComponentIntrospector.getInstance().has(Trigger.class, parameter);
    }

    @Override
    public boolean matches(ParameterView parameter, Annotation methodAnnotation, HasMessage value) {
        Optional<Parameter> reflectionParameter = parameter.parameter();
        if (reflectionParameter.isPresent()) {
            return matches(reflectionParameter.orElseThrow(), methodAnnotation, value);
        }
        return parameter.annotation(Trigger.class).isPresent();
    }

    /**
     * Applies additional filtering logic based on the {@link Trigger} annotation on the parameter.
     *
     * <p>This includes verifying the presence and assignability of the triggering class, matching message type,
     * and (optionally) matching consumer.
     *
     * @param message   the incoming message being evaluated
     * @param parameter the handler method parameter
     * @return {@code true} if the trigger information matches the parameter's constraints, {@code false} otherwise
     */
    @Override
    public boolean test(HasMessage message, Parameter parameter) {
        Trigger trigger = parameter.getAnnotation(Trigger.class);
        if (!filterMessage(message, trigger, correlationDataProvider)) {
            return false;
        }
        var parameterType = HasMessage.class.isAssignableFrom(parameter.getType())
                ? Object.class : parameter.getType();
        return getTriggerClass(message, correlationDataProvider).filter(parameterType::isAssignableFrom).isPresent();
    }

    @Override
    public boolean test(HasMessage message, ParameterView parameter) {
        Optional<Parameter> reflectionParameter = parameter.parameter();
        if (reflectionParameter.isPresent()) {
            return test(message, reflectionParameter.orElseThrow());
        }
        Trigger trigger = parameter.annotation(Trigger.class).orElse(null);
        if (!filterMessage(message, trigger, correlationDataProvider)) {
            return false;
        }
        Class<?> parameterType = parameter.type()
                .filter(type -> !HasMessage.class.isAssignableFrom(type))
                .orElse(Object.class);
        return getTriggerClass(message, correlationDataProvider).filter(parameterType::isAssignableFrom).isPresent();
    }

    protected boolean filterMessage(HasMessage message, Trigger trigger) {
        return filterMessage(message, trigger, correlationDataProvider);
    }

    static Predicate<HasMessage> triggerFilter(Trigger trigger) {
        return trigger == null ? ObjectUtils.noOpPredicate()
                : message -> filterMessage(message, trigger, DefaultCorrelationDataProvider.INSTANCE);
    }

    static boolean filterMessage(HasMessage message, Trigger trigger,
                                 DefaultCorrelationDataProvider correlationDataProvider) {
        if (trigger == null) {
            return false;
        }
        if (trigger.messageType().length > 0 && getTriggerMessageType(message, correlationDataProvider)
                .filter(type -> Arrays.stream(trigger.messageType()).anyMatch(t -> t == type)).isEmpty()) {
            return false;
        }
        if (trigger.consumer().length > 0 && getConsumer(message, correlationDataProvider)
                .filter(type -> Arrays.asList(trigger.consumer()).contains(type)).isEmpty()) {
            return false;
        }
        return getTriggerClass(message, correlationDataProvider).filter(triggerClass -> {
            var allowedTypes = trigger.value();
            return (allowedTypes.length == 0
                    || Arrays.stream(allowedTypes).anyMatch(a -> a.isAssignableFrom(triggerClass)));
        }).isPresent();
    }

    /**
     * Resolves the value to inject into a parameter annotated with {@link Trigger}.
     *
     * <p>The method extracts correlation metadata from the message and attempts to:
     * <ul>
     *   <li>Read the original trigger message from the Runtime using its index and type</li>
     *   <li>Match the class and constraints in the {@code @Trigger} annotation</li>
     *   <li>Inject the trigger message as either:
     *       <ul>
     *           <li>A {@link DeserializingMessage}</li>
     *           <li>A {@link Message}</li>
     *           <li>Just the payload</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * @param p                the parameter to resolve
     * @param methodAnnotation the annotation present on the enclosing method
     * @return a function that retrieves the resolved parameter value from the current message context
     */
    @Override
    public Function<HasMessage, Object> resolve(Parameter p, Annotation methodAnnotation) {
        return m -> getTriggerMessage(m).<Object>map(triggerMessage -> {
                    var parameterType = p.getType();
                    if (DeserializingMessage.class.isAssignableFrom(parameterType)) {
                        return triggerMessage;
                    }
                    if (HasMessage.class.isAssignableFrom(parameterType)) {
                        return triggerMessage.toMessage();
                    }
                    return triggerMessage.getPayload();
                }).orElse(null);
    }

    @Override
    public Function<HasMessage, Object> resolve(ParameterView p, Annotation methodAnnotation) {
        Optional<Parameter> reflectionParameter = p.parameter();
        if (reflectionParameter.isPresent()) {
            return resolve(reflectionParameter.orElseThrow(), methodAnnotation);
        }
        return m -> getTriggerMessage(m).<Object>map(triggerMessage -> {
                    Class<?> parameterType = p.type().orElse(Object.class);
                    if (DeserializingMessage.class.isAssignableFrom(parameterType)) {
                        return triggerMessage;
                    }
                    if (HasMessage.class.isAssignableFrom(parameterType)) {
                        return triggerMessage.toMessage();
                    }
                    return triggerMessage.getPayload();
                }).orElse(null);
    }

    protected Optional<DeserializingMessage> getTriggerMessage(HasMessage message) {
        return ofNullable(message.getMetadata().get(correlationDataProvider.getCorrelationIdKey()))
                .flatMap(s -> {
                    try {
                        return Optional.of(Long.valueOf(s));
                    } catch (Exception ignored) {
                        return Optional.empty();
                    }
                }).flatMap(index -> getTriggerClass(message, correlationDataProvider).flatMap(
                        triggerClass -> getTriggerMessageType(message, correlationDataProvider).flatMap(
                                triggerType -> getTriggerMessage(index, triggerClass, triggerType))));
    }

    protected Optional<Class<?>> getTriggerClass(HasMessage message) {
        return getTriggerClass(message, correlationDataProvider);
    }

    static Optional<Class<?>> getTriggerClass(HasMessage message,
                                              DefaultCorrelationDataProvider correlationDataProvider) {
        return ofNullable(message.getMetadata().get(correlationDataProvider.getTriggerKey()))
                .flatMap(s -> Optional.ofNullable(JvmComponentIntrospector.getInstance().classForName(s, null)));
    }

    private static final class TriggerMetadata {
        private static final ClassValue<TriggerMetadata> cache = new ClassValue<>() {
            @Override
            protected TriggerMetadata computeValue(Class<?> type) {
                return new TriggerMetadata();
            }
        };

        private final MetadataExecutableAnnotationResolver annotationResolver =
                MetadataExecutableAnnotationResolver.create();
        private final ConcurrentHashMap<Executable, Optional<Trigger>> trigger = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Executable, Predicate<HasMessage>> triggerFilter = new ConcurrentHashMap<>();

        private static TriggerMetadata of(Class<?> targetClass) {
            return cache.get(targetClass);
        }

        private Predicate<HasMessage> triggerFilter(Executable executable) {
            Predicate<HasMessage> cached = triggerFilter.get(executable);
            if (cached != null) {
                return cached;
            }
            Predicate<HasMessage> computed = TriggerParameterResolver.triggerFilter(trigger(executable).orElse(null));
            Predicate<HasMessage> existing = triggerFilter.putIfAbsent(executable, computed);
            return existing != null ? existing : computed;
        }

        private Optional<Trigger> trigger(Executable executable) {
            Optional<Trigger> cached = trigger.get(executable);
            if (cached != null) {
                return cached;
            }
            Optional<Trigger> computed = annotationResolver.getAnnotation(executable, Trigger.class)
                    .map(Trigger.class::cast);
            Optional<Trigger> existing = trigger.putIfAbsent(executable, computed);
            return existing != null ? existing : computed;
        }
    }

    protected Optional<MessageType> getTriggerMessageType(HasMessage message) {
        return getTriggerMessageType(message, correlationDataProvider);
    }

    static Optional<MessageType> getTriggerMessageType(HasMessage message,
                                                       DefaultCorrelationDataProvider correlationDataProvider) {
        return ofNullable(message.getMetadata().get(correlationDataProvider.getTriggerTypeKey()))
                .flatMap(s -> {
                    try {
                        return Optional.of(MessageType.valueOf(s));
                    } catch (Exception ignored) {
                        return Optional.empty();
                    }
                });
    }

    protected Optional<String> getConsumer(HasMessage message) {
        return getConsumer(message, correlationDataProvider);
    }

    static Optional<String> getConsumer(HasMessage message,
                                        DefaultCorrelationDataProvider correlationDataProvider) {
        return ofNullable(message.getMetadata().get(correlationDataProvider.getConsumerKey()));
    }

    protected Optional<DeserializingMessage> getTriggerMessage(long index, Class<?> type, MessageType messageType) {
        var client = Tracker.current().map(tracker -> tracker.getConfiguration().getNamespace()).map(
                this.client::forNamespace).orElse(this.client);
        return client.getTrackingClient(messageType).readFromIndex(index, 1).stream()
                .flatMap(s -> serializer.deserializeMessages(Stream.of(s), messageType))
                .filter(d -> type.isAssignableFrom(d.getPayloadClass()))
                .findFirst();
    }

    @Override
    public boolean mayApply(Executable method, Class<?> targetClass) {
        for (Parameter parameter : method.getParameters()) {
            if (JvmComponentIntrospector.getInstance().has(Trigger.class, parameter)) {
                return true;
            }
        }
        return false;
    }
}
