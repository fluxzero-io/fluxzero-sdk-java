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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.AggregateCommitPolicy;
import io.fluxzero.sdk.modeling.DefaultEntityHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static io.fluxzero.sdk.persisting.repository.DefaultAggregateRepository.AGGREGATE_COMMIT_POLICY_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock("systemProperties")
class DefaultAggregateRepositoryCommitPolicyTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(AGGREGATE_COMMIT_POLICY_PROPERTY);
        System.clearProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY);
    }

    @Test
    void defaultPolicyFallsBackToSyncAfterBatchCommit() {
        assertEquals(AggregateCommitPolicy.SYNC_AFTER_BATCH, resolve(DefaultAggregate.class));
    }

    @Test
    void explicitAggregatePolicyWinsOverProperties() {
        System.setProperty(AGGREGATE_COMMIT_POLICY_PROPERTY, "sync_after_batch");
        System.setProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.09");

        assertEquals(AggregateCommitPolicy.ASYNC_AFTER_HANDLER, resolve(ExplicitAggregate.class));
    }

    @Test
    void propertyOverridesDefaultPolicy() {
        System.setProperty(AGGREGATE_COMMIT_POLICY_PROPERTY, "async-after-handler");

        assertEquals(AggregateCommitPolicy.ASYNC_AFTER_HANDLER, resolve(DefaultAggregate.class));
    }

    @Test
    void defaultPropertyContinuesToDefaultsVersion() {
        System.setProperty(AGGREGATE_COMMIT_POLICY_PROPERTY, "default");
        System.setProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.09");

        assertEquals(AggregateCommitPolicy.ASYNC_AFTER_BATCH, resolve(DefaultAggregate.class));
    }

    @Test
    void defaultPropertyFallsBackToSyncAfterBatchWhenDefaultsVersionIsAbsent() {
        System.setProperty(AGGREGATE_COMMIT_POLICY_PROPERTY, "default");

        assertEquals(AggregateCommitPolicy.SYNC_AFTER_BATCH, resolve(DefaultAggregate.class));
    }

    @Test
    void newerDefaultsVersionUsesAsyncAfterBatch() {
        System.setProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.09");

        assertEquals(AggregateCommitPolicy.ASYNC_AFTER_BATCH, resolve(DefaultAggregate.class));
    }

    private static AggregateCommitPolicy resolve(Class<?> type) {
        return DefaultAggregateRepository.resolveCommitPolicy(DefaultEntityHelper.getRootAnnotation(type));
    }

    @Aggregate
    private static class DefaultAggregate {
    }

    @Aggregate(commitPolicy = AggregateCommitPolicy.ASYNC_AFTER_HANDLER)
    private static class ExplicitAggregate {
    }
}
