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
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoLocalDate;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.PatternSyntaxException;

final class ConstraintValidators {
    private static final ConcurrentHashMap<Class<? extends ConstraintValidator<?, ?>>, ValidatorDescriptor>
            validatorDescriptorCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CacheKey, List<ConstraintValidator<Annotation, Object>>>
            initializedValidators = new ConcurrentHashMap<>();

    private ConstraintValidators() {
    }

    static Boolean validateBuiltIn(ConstraintMeta meta, Object value, ValidationSettings settings) {
        return BuiltInConstraintValidators.validate(meta.annotation(), value, settings.clockProvider());
    }

    static boolean isValid(ConstraintMeta meta, Object value, DefaultValidationMetadata.DefaultConstraintValidatorContext context,
                           ValidationSettings settings) {
        List<ConstraintValidator<Annotation, Object>> validators =
                initializedValidators.computeIfAbsent(new CacheKey(meta, settings.constraintValidatorFactory()),
                                                      ConstraintValidators::initializeValidators);
        if (validators.isEmpty()) {
            if (meta.composingConstraints().isEmpty()) {
                throw new UnexpectedTypeException("No validator found for "
                                                  + meta.annotation().annotationType().getName());
            }
            return true;
        }
        ConstraintValidator<Annotation, Object> validator = selectValidator(meta, validators, value);
        if (validator == null) {
            throw new UnexpectedTypeException("No validator found for "
                                              + meta.annotation().annotationType().getName()
                                              + " and value type "
                                              + (value == null ? "null" : value.getClass().getName()));
        }
        return validator.isValid(value, context);
    }

    static void release(jakarta.validation.ConstraintValidatorFactory factory) {
        initializedValidators.entrySet().removeIf(entry -> {
            if (entry.getKey().factory() == factory) {
                entry.getValue().forEach(factory::releaseInstance);
                return true;
            }
            return false;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ConstraintValidator<Annotation, Object>> initializeValidators(CacheKey key) {
        List<ConstraintValidator<Annotation, Object>> result = new ArrayList<>();
        for (Class<? extends ConstraintValidator<?, ?>> validatorClass : key.meta().validatorClasses()) {
            try {
                ConstraintValidator validator = key.factory().getInstance(validatorClass);
                validator.initialize(key.meta().annotation());
                result.add((ConstraintValidator<Annotation, Object>) validator);
            } catch (RuntimeException e) {
                throw new jakarta.validation.ValidationException(
                        "Could not instantiate constraint validator " + validatorClass.getName(), e);
            }
        }
        return List.copyOf(result);
    }

    private static ConstraintValidator<Annotation, Object> selectValidator(
            ConstraintMeta meta, List<ConstraintValidator<Annotation, Object>> validators, Object value) {
        List<ConstraintValidator<Annotation, Object>> candidates = validators.stream()
                .filter(validator -> descriptor(validatorClass(validator.getClass())).supports(value))
                .sorted(Comparator.comparing(
                        validator -> descriptor(validatorClass(validator.getClass())).validatedType(),
                        ReflectionUtils.getClassSpecificityComparator()))
                .toList();
        if (candidates.size() > 1) {
            Class<?> first = descriptor(validatorClass(candidates.get(0).getClass())).validatedType();
            Class<?> second = descriptor(validatorClass(candidates.get(1).getClass())).validatedType();
            if (Objects.equals(first, second)) {
                throw new ConstraintDefinitionException("Ambiguous validators for "
                                                        + meta.annotation().annotationType().getName());
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static ValidatorDescriptor descriptor(Class<? extends ConstraintValidator<?, ?>> validatorClass) {
        return validatorDescriptorCache.computeIfAbsent(validatorClass, ValidatorDescriptor::new);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Class<? extends ConstraintValidator<?, ?>> validatorClass(Class<?> validatorClass) {
        return (Class) validatorClass.asSubclass(ConstraintValidator.class);
    }

    record ValidatorDescriptor(Class<?> validatedType, boolean crossParameter) {
        ValidatorDescriptor(Class<? extends ConstraintValidator<?, ?>> validatorType) {
            this(validatedType(validatorType), crossParameter(validatorType));
        }

        boolean supports(Object value) {
            return value == null
                   || ReflectionUtils.box(validatedType).isAssignableFrom(ReflectionUtils.box(value.getClass()));
        }

        private static boolean crossParameter(Class<? extends ConstraintValidator<?, ?>> validatorType) {
            return ReflectionUtils.getTypeMetadata(validatorType).typeAnnotations().stream()
                    .filter(annotation -> annotation.annotationType().getName()
                            .equals("jakarta.validation.constraintvalidation.SupportedValidationTarget"))
                    .flatMap(annotation -> ValidationAnnotationUtils.annotationValue(annotation, "value", Object[].class)
                            .stream())
                    .flatMap(values -> Arrays.stream((Object[]) values))
                    .anyMatch(value -> String.valueOf(value).equals("PARAMETERS"));
        }

        private static Class<?> validatedType(Class<?> validatorType) {
            return validatedType(validatorType, new HashSet<>()).orElse(Object.class);
        }

        private static Optional<Class<?>> validatedType(Type type, Set<Type> visited) {
            if (type == null || !visited.add(type)) {
                return Optional.empty();
            }
            if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> rawType
                && ConstraintValidator.class.isAssignableFrom(rawType)) {
                Type[] arguments = parameterizedType.getActualTypeArguments();
                if (rawType.equals(ConstraintValidator.class) && arguments.length == 2) {
                    return Optional.of(ReflectionUtils.rawClass(arguments[1]));
                }
            }
            Class<?> raw = switch (type) {
                case Class<?> c -> c;
                case ParameterizedType p when p.getRawType() instanceof Class<?> c -> c;
                default -> null;
            };
            if (raw == null) {
                return Optional.empty();
            }
            for (Type interfaceType : raw.getGenericInterfaces()) {
                Optional<Class<?>> result = validatedType(interfaceType, visited);
                if (result.isPresent()) {
                    return result;
                }
            }
            return validatedType(raw.getGenericSuperclass(), visited);
        }
    }

    private record CacheKey(ConstraintMeta meta, jakarta.validation.ConstraintValidatorFactory factory) {
    }

private static final class BuiltInConstraintValidators {
    private static final int UNSUPPORTED_COMPARISON = Integer.MIN_VALUE;

    private BuiltInConstraintValidators() {
    }

    static Boolean validate(Annotation annotation, Object value, ClockProvider clockProvider) {
        return switch (annotation) {
            case Null ignored -> value == null;
            case NotNull ignored -> value != null;
            case NotBlank ignored -> value instanceof CharSequence sequence && hasNonWhitespace(sequence);
            case NotEmpty ignored -> value != null && sizeOf(value, annotation) > 0;
            default -> value == null && isJakartaConstraint(annotation) ? Boolean.TRUE
                    : validateNonNull(annotation, value, clockProvider);
        };
    }

    private static boolean isJakartaConstraint(Annotation annotation) {
        return annotation.annotationType().getName().startsWith("jakarta.validation.constraints.");
    }

    private static boolean hasNonWhitespace(CharSequence sequence) {
        for (int i = 0; i < sequence.length(); i++) {
            if (!Character.isWhitespace(sequence.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static Boolean validateNonNull(Annotation annotation, Object value,
                                           ClockProvider clockProvider) {
        return switch (annotation) {
            case AssertTrue ignored -> Boolean.TRUE.equals(value);
            case AssertFalse ignored -> Boolean.FALSE.equals(value);
            case Size size -> {
                int actual = sizeOf(value, annotation);
                yield actual >= size.min() && actual <= size.max();
            }
            case Min min -> compareToLong(value, min.value(), annotation) >= 0;
            case Max max -> compareToLong(value, max.value(), annotation) <= 0;
            case DecimalMin min -> {
                BigDecimal decimal = asBigDecimal(value, annotation);
                int comparison = decimal.compareTo(new BigDecimal(min.value()));
                yield min.inclusive() ? comparison >= 0 : comparison > 0;
            }
            case DecimalMax max -> {
                BigDecimal decimal = asBigDecimal(value, annotation);
                int comparison = decimal.compareTo(new BigDecimal(max.value()));
                yield max.inclusive() ? comparison <= 0 : comparison < 0;
            }
            case Positive ignored -> asBigDecimal(value, annotation).compareTo(BigDecimal.ZERO) > 0;
            case PositiveOrZero ignored -> asBigDecimal(value, annotation).compareTo(BigDecimal.ZERO) >= 0;
            case Negative ignored -> asBigDecimal(value, annotation).compareTo(BigDecimal.ZERO) < 0;
            case NegativeOrZero ignored -> asBigDecimal(value, annotation).compareTo(BigDecimal.ZERO) <= 0;
            case Digits digits -> digitsValid(asBigDecimal(value, annotation), digits);
            case Pattern pattern -> matches(pattern, annotation, value);
            case Email email -> emailValid(email, annotation, value);
            case Past ignored -> compareToNow(value, clockProvider) < 0;
            case PastOrPresent ignored -> compareToNow(value, clockProvider) <= 0;
            case Future ignored -> compareToNow(value, clockProvider) > 0;
            case FutureOrPresent ignored -> compareToNow(value, clockProvider) >= 0;
            default -> null;
        };
    }

    private static boolean matches(Pattern pattern, Annotation annotation, Object value) {
        if (!(value instanceof CharSequence sequence)) {
            throw unexpectedType(annotation, value);
        }
        return pattern(pattern).matcher(sequence).matches();
    }

    private static boolean emailValid(Email email, Annotation annotation, Object value) {
        if (!(value instanceof CharSequence sequence)) {
            throw unexpectedType(annotation, value);
        }
        if (!email.regexp().equals(".*") && !pattern(email).matcher(sequence).matches()) {
            return false;
        }
        return emailValid(sequence);
    }

    private static UnexpectedTypeException unexpectedType(Annotation annotation, Object value) {
        return new UnexpectedTypeException("Unsupported type " + value.getClass().getName()
                                           + " for " + annotation.annotationType().getName());
    }

    private static int sizeOf(Object value, Annotation annotation) {
        return switch (value) {
            case CharSequence sequence -> sequence.length();
            case Collection<?> collection -> collection.size();
            case Map<?, ?> map -> map.size();
            default -> {
                if (value.getClass().isArray()) {
                    yield Array.getLength(value);
                }
                throw unexpectedType(annotation, value);
            }
        };
    }

    private static BigDecimal asBigDecimal(Object value, Annotation annotation) {
        try {
            return switch (value) {
                case BigDecimal bigDecimal -> bigDecimal;
                case BigInteger bigInteger -> new BigDecimal(bigInteger);
                case Number number -> new BigDecimal(number.toString());
                case CharSequence sequence -> new BigDecimal(sequence.toString());
                default -> throw unexpectedType(annotation, value);
            };
        } catch (NumberFormatException e) {
            throw unexpectedType(annotation, value);
        }
    }

    private static int compareToLong(Object value, long target, Annotation annotation) {
        try {
            int comparison = switch (value) {
                case Byte number -> Long.compare(number.longValue(), target);
                case Short number -> Long.compare(number.longValue(), target);
                case Integer number -> Long.compare(number.longValue(), target);
                case Long number -> Long.compare(number, target);
                case BigInteger bigInteger -> bigInteger.compareTo(BigInteger.valueOf(target));
                case BigDecimal bigDecimal -> bigDecimal.compareTo(BigDecimal.valueOf(target));
                case Number number -> new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(target));
                case CharSequence sequence -> new BigDecimal(sequence.toString()).compareTo(BigDecimal.valueOf(target));
                default -> UNSUPPORTED_COMPARISON;
            };
            if (comparison == UNSUPPORTED_COMPARISON) {
                throw unexpectedType(annotation, value);
            }
            return comparison;
        } catch (NumberFormatException e) {
            throw unexpectedType(annotation, value);
        }
    }

    private static boolean digitsValid(BigDecimal value, Digits digits) {
        BigDecimal normalized = value.abs().stripTrailingZeros();
        int integerDigits = Math.max(0, normalized.precision() - Math.max(0, normalized.scale()));
        int fractionDigits = Math.max(0, normalized.scale());
        return integerDigits <= digits.integer() && fractionDigits <= digits.fraction();
    }

    private static java.util.regex.Pattern pattern(Pattern pattern) {
        int flags = 0;
        for (Pattern.Flag flag : pattern.flags()) {
            flags |= flag.getValue();
        }
        try {
            return java.util.regex.Pattern.compile(pattern.regexp(), flags);
        } catch (PatternSyntaxException e) {
            throw new jakarta.validation.ValidationException("Invalid pattern " + pattern.regexp(), e);
        }
    }

    private static java.util.regex.Pattern pattern(Email email) {
        int flags = 0;
        for (Pattern.Flag flag : email.flags()) {
            flags |= flag.getValue();
        }
        return java.util.regex.Pattern.compile(email.regexp(), flags);
    }

    private static boolean emailValid(CharSequence sequence) {
        String value = sequence.toString();
        int at = value.indexOf('@');
        return at > 0 && at == value.lastIndexOf('@') && at < value.length() - 1
               && value.indexOf('.', at + 2) > at + 1;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareToNow(Object value, ClockProvider clockProvider) {
        Clock clock = clockProvider.getClock();
        return switch (value) {
            case Instant instant -> instant.compareTo(clock.instant());
            case Date date -> date.toInstant().compareTo(clock.instant());
            case Calendar calendar -> calendar.toInstant().compareTo(clock.instant());
            case LocalDate date -> date.compareTo(LocalDate.now(clock));
            case LocalDateTime dateTime -> dateTime.compareTo(LocalDateTime.now(clock));
            case LocalTime time -> time.compareTo(LocalTime.now(clock));
            case OffsetDateTime dateTime -> dateTime.compareTo(OffsetDateTime.now(clock));
            case ZonedDateTime dateTime -> dateTime.compareTo(ZonedDateTime.now(clock));
            case Year year -> year.compareTo(Year.now(clock));
            case YearMonth yearMonth -> yearMonth.compareTo(YearMonth.now(clock));
            case MonthDay monthDay -> monthDay.compareTo(MonthDay.now(clock));
            case ChronoLocalDate date -> date.compareTo(ChronoLocalDate.from(LocalDate.now(clock)));
            case TemporalAccessor temporal when temporal instanceof Comparable comparable ->
                    comparable.compareTo(value.getClass().cast(LocalDateTime.now(clock)));
            default -> throw new UnexpectedTypeException("Unsupported temporal type " + value.getClass().getName());
        };
    }
}

}
