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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Function;

import static io.fluxzero.common.ObjectUtils.memoize;
import static io.fluxzero.sdk.scheduling.CronExpression.parseCronExpression;

final class PeriodicSchedulingDefaults {
    static final String CRON_SCHEMA_METADATA_KEY = "$periodic.cron";

    private static final LocalDate DEFAULT_INITIAL_DELAY_DEFAULTS_VERSION = LocalDate.of(2026, 5, 21);
    private static final DateTimeFormatter DEFAULTS_VERSION_FORMAT = DateTimeFormatter.ofPattern("uuuu.MM.dd");
    private static final Function<String, CronExpression> cronExpression = memoize(PeriodicSchedulingDefaults::parse);

    private PeriodicSchedulingDefaults() {
    }

    static Instant firstDeadline(Periodic periodic, Instant now) {
        if (isDisabledCron(periodic)) {
            return null;
        }
        if (periodic.initialDelay() >= 0) {
            return now.plusMillis(periodic.timeUnit().toMillis(periodic.initialDelay()));
        }
        if (!useDefaultInitialDelay()) {
            return now;
        }
        return nextDeadline(periodic, now);
    }

    static Instant schedulePeriodicDeadline(Periodic periodic, Instant now) {
        if (isDisabledCron(periodic)) {
            return null;
        }
        if (periodic.initialDelay() >= 0) {
            return now.plusMillis(periodic.timeUnit().toMillis(periodic.initialDelay()));
        }
        if (!periodic.cron().isBlank()) {
            return nextDeadline(periodic, now);
        }
        if (!useDefaultInitialDelay()) {
            return now;
        }
        return nextDeadline(periodic, now);
    }

    static Instant nextDeadline(Periodic periodic, Instant now) {
        if (periodic.cron().isBlank()) {
            return now.plusMillis(periodic.timeUnit().toMillis(periodic.delay()));
        }
        return cronExpression(periodic)
                .map(e -> e.nextTimeAfter(now.atZone(ZoneId.of(periodic.timeZone()))).toInstant())
                .orElse(null);
    }

    static boolean isDisabledCron(Periodic periodic) {
        return !periodic.cron().isBlank() && cronExpression(periodic).isEmpty();
    }

    static boolean isCronBased(Periodic periodic) {
        return periodic != null && !periodic.cron().isBlank() && !isDisabledCron(periodic);
    }

    static String cronSchema(Periodic periodic) {
        return "%s@%s".formatted(ApplicationProperties.substituteProperties(periodic.cron()), periodic.timeZone());
    }

    private static Optional<CronExpression> cronExpression(Periodic periodic) {
        String pattern = ApplicationProperties.substituteProperties(periodic.cron());
        return Periodic.DISABLED.equals(pattern) ? Optional.empty() : Optional.of(cronExpression.apply(pattern));
    }

    private static CronExpression parse(String pattern) {
        return parseCronExpression(pattern);
    }

    private static boolean useDefaultInitialDelay() {
        String configured = ApplicationProperties.getProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY);
        if (configured != null) {
            return Boolean.parseBoolean(configured.trim());
        }
        return Optional.ofNullable(defaultsVersion())
                .map(version -> !version.isBefore(DEFAULT_INITIAL_DELAY_DEFAULTS_VERSION))
                .orElse(false);
    }

    private static LocalDate defaultsVersion() {
        String value = ApplicationProperties.getProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DEFAULTS_VERSION_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "Property `%s` must use format `yyyy.MM.dd`, but found `%s`.".formatted(
                            ApplicationProperties.DEFAULTS_VERSION_PROPERTY, value), e);
        }
    }
}
