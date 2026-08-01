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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelCommitPolicyTest {

    @Test
    void defaultUsesAfterHandlerAwaitAfterBatchWithoutDefaultsVersion() {
        assertEquals(
                ModelCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH,
                ModelCommitPolicy.resolve(ModelCommitPolicy.DEFAULT));
    }

    @Test
    void oldDefaultsVersionDoesNotChangeTheIndependentModelDefault() {
        TestFixture.create()
                .withProperty(ApplicationProperties.DEFAULTS_VERSION_PROPERTY, "2020.01.01")
                .whenApplying(ignored -> ModelCommitPolicy.resolve(ModelCommitPolicy.DEFAULT))
                .expectResult(ModelCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH);
    }

    @Test
    void modelPropertyOverridesDefault() {
        TestFixture.create()
                .withProperty(ModelCommitPolicy.PROPERTY, "async-after-batch")
                .whenApplying(ignored -> ModelCommitPolicy.resolve(ModelCommitPolicy.DEFAULT))
                .expectResult(ModelCommitPolicy.ASYNC_AFTER_BATCH);
    }

    @Test
    void explicitAnnotationPolicyWinsOverProperty() {
        TestFixture.create()
                .withProperty(ModelCommitPolicy.PROPERTY, "sync_after_batch")
                .whenApplying(ignored ->
                        ModelCommitPolicy.resolve(ModelCommitPolicy.ASYNC_AFTER_HANDLER))
                .expectResult(ModelCommitPolicy.ASYNC_AFTER_HANDLER);
    }

    @Test
    void conflictingPoliciesUseTheDocumentedGuaranteeOrder() {
        List<ModelCommitPolicy> strongestFirst = List.of(
                ModelCommitPolicy.SYNC_AFTER_HANDLER,
                ModelCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH,
                ModelCommitPolicy.ASYNC_AFTER_HANDLER,
                ModelCommitPolicy.SYNC_AFTER_BATCH,
                ModelCommitPolicy.ASYNC_AFTER_BATCH);

        for (int stronger = 0; stronger < strongestFirst.size(); stronger++) {
            for (int weaker = stronger + 1; weaker < strongestFirst.size(); weaker++) {
                ModelCommitPolicy expected = strongestFirst.get(stronger);
                ModelCommitPolicy other = strongestFirst.get(weaker);
                assertEquals(expected, ModelCommitPolicy.merge(List.of(expected, other)));
                assertEquals(expected, ModelCommitPolicy.merge(List.of(other, expected)));
            }
        }
    }
}
