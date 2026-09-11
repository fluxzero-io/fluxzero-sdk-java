/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import javax.annotation.processing.SupportedAnnotationTypes;

/**
 * SDK-local provider for independent Model indexing. Both SDK and common order their observer before their own
 * claiming processors, regardless of JAR order. Explicit processor lists must retain that ordering.
 */
@SupportedAnnotationTypes("*")
public final class ModelTypeProcessor extends io.fluxzero.common.modeling.ModelTypeProcessor {
}
