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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.AggregateCommitPolicy;
import io.fluxzero.sdk.modeling.DefaultEntityHelper;
import io.fluxzero.sdk.registry.GeneratedOnlyMetadataMode;
import io.fluxzero.sdk.registry.JvmComponentMetadataLookup;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import static io.fluxzero.sdk.persisting.repository.DefaultAggregateRepository.AGGREGATE_COMMIT_POLICY_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultAggregateRepositoryCommitPolicyTest {

    @Test
    void defaultPolicyFallsBackToSyncAfterBatchCommit() {
        assertEquals(AggregateCommitPolicy.SYNC_AFTER_BATCH, resolve(DefaultAggregate.class));
    }

    @Test
    void explicitAggregatePolicyWinsOverProperties() {
        expectResolved(AggregateCommitPolicy.ASYNC_AFTER_HANDLER, ExplicitAggregate.class,
                       AGGREGATE_COMMIT_POLICY_PROPERTY, "sync_after_batch",
                       ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.09");
    }

    @Test
    void propertyOverridesDefaultPolicy() {
        expectResolved(AggregateCommitPolicy.ASYNC_AFTER_HANDLER, DefaultAggregate.class,
                       AGGREGATE_COMMIT_POLICY_PROPERTY, "async-after-handler");
    }

    @Test
    void propertySupportsAsyncAfterHandlerAwaitAfterBatchPolicy() {
        expectResolved(AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, DefaultAggregate.class,
                       AGGREGATE_COMMIT_POLICY_PROPERTY, "async-after-handler-await-after-batch");
    }

    @Test
    void defaultPropertyContinuesToDefaultsVersion() {
        expectResolved(AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, DefaultAggregate.class,
                       AGGREGATE_COMMIT_POLICY_PROPERTY, "default",
                       ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.09");
    }

    @Test
    void defaultPropertyFallsBackToSyncAfterBatchWhenDefaultsVersionIsAbsent() {
        expectResolved(AggregateCommitPolicy.SYNC_AFTER_BATCH, DefaultAggregate.class,
                       AGGREGATE_COMMIT_POLICY_PROPERTY, "default");
    }

    @Test
    void newerDefaultsVersionUsesAsyncAfterHandlerAwaitAfterBatch() {
        expectResolved(AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, DefaultAggregate.class,
                       ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2026.06.09");
    }

    @Test
    void generatedOnlyModeDoesNotUseAggregatePolicyWithoutRegistryMetadata() {
        GeneratedOnlyMetadataMode.run(() ->
                assertEquals(AggregateCommitPolicy.SYNC_AFTER_BATCH, resolve(ExplicitAggregate.class)));
    }

    @Test
    void generatedOnlyModeUsesAggregatePolicyFromRegistryMetadata() {
        try {
            TestFixture.create().getFluxzero().registerComponentRegistry(
                    JvmComponentMetadataLookup.scan(ExplicitAggregate.class).registry());

            GeneratedOnlyMetadataMode.run(() ->
                    assertEquals(AggregateCommitPolicy.ASYNC_AFTER_HANDLER, resolve(ExplicitAggregate.class)));
        } finally {
            TestFixture.shutDownActiveFixtures();
        }
    }

    private static AggregateCommitPolicy resolve(Class<?> type) {
        return DefaultAggregateRepository.resolveCommitPolicy(DefaultEntityHelper.getRootAnnotation(type));
    }

    private static void expectResolved(
            AggregateCommitPolicy expected, Class<?> type, String... propertyKeysAndValues) {
        if (propertyKeysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected pairs of keys and values");
        }
        TestFixture fixture = TestFixture.create();
        for (int i = 0; i < propertyKeysAndValues.length; i += 2) {
            fixture.withProperty(propertyKeysAndValues[i], propertyKeysAndValues[i + 1]);
        }
        fixture.whenApplying(fc -> resolve(type)).expectResult(expected);
    }

    @Aggregate
    private static class DefaultAggregate {
    }

    @Aggregate(commitPolicy = AggregateCommitPolicy.ASYNC_AFTER_HANDLER)
    private static class ExplicitAggregate {
    }
}
