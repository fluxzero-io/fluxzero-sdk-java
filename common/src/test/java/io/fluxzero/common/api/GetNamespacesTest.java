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

package io.fluxzero.common.api;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GetNamespacesTest {
    @ParameterizedTest
    @EnumSource(WebSocketTransportFormat.class)
    void preservesCorrelationAndNames(WebSocketTransportFormat format) throws Exception {
        var codec = WebSocketTransportCodecs.forFormat(format, JsonMapper.builder().findAndAddModules().build());
        var request = new GetNamespaces();
        var decodedRequest = assertInstanceOf(GetNamespaces.class, codec.decode(codec.encode(request)));
        assertEquals(request.getRequestId(), decodedRequest.getRequestId());
        var result = new GetNamespacesResult(request.getRequestId(), List.of("public", "tenant-é", "with space"));
        var decoded = assertInstanceOf(GetNamespacesResult.class, codec.decode(codec.encode(result)));
        assertEquals(result.getRequestId(), decoded.getRequestId());
        assertEquals(result.getNamespaces(), decoded.getNamespaces());
        assertEquals(new GetNamespacesResult.Metric(3), decoded.toMetric());
    }
}
