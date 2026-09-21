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

package io.fluxzero.proxy;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;

/**
 * Preserves an explicit HTTP/1.1 request close decision when Jetty retries oversized response headers.
 * <p>
 * Workaround for <a href="https://github.com/jetty/jetty.project/issues/15840">Jetty #15840</a>:
 * the overflow retry resets the generator's persistence state. This covers explicit request close only;
 * other Jetty decisions, such as an early final response to {@code Expect: 100-continue}, require an upstream fix.
 * Revisit this customizer when upgrading to a Jetty version that preserves persistence across header growth.
 */
final class HeaderBufferConnectionCloseCustomizer implements HttpConfiguration.Customizer {
    @Override
    public Request customize(Request request, HttpFields.Mutable responseHeaders) {
        if (request.getConnectionMetaData().getHttpVersion() == HttpVersion.HTTP_1_1
            && request.getHeaders().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())) {
            request.addHttpStreamWrapper(stream -> new HttpStream.Wrapper(stream) {
                @Override
                public void send(MetaData.Request request, MetaData.Response response, boolean last,
                                 ByteBuffer content, Callback callback) {
                    super.send(request, withConnectionClose(response), last, content, callback);
                }
            });
        }
        return request;
    }

    private static MetaData.Response withConnectionClose(MetaData.Response response) {
        if (response == null || response instanceof MetaData.Failed || HttpStatus.isInformational(response.getStatus())
            || response.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())) {
            return response;
        }
        HttpFields fields = HttpFields.build(response.getHttpFields(), HttpFields.CONNECTION_CLOSE).asImmutable();
        return new MetaData.Response(response.getStatus(), response.getReason(), response.getHttpVersion(), fields,
                                     response.getContentLength(), response.getTrailersSupplier());
    }
}
