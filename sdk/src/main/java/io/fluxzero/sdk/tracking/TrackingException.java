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

package io.fluxzero.sdk.tracking;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.fluxzero.sdk.common.exception.FluxzeroErrorReport;

/**
 * Exception thrown during message tracking in Fluxzero.
 * <p>
 * This exception indicates a configuration, startup, or runtime problem when assigning handlers to consumers,
 * starting tracking, or keeping tracker state in sync.
 */
public class TrackingException extends RuntimeException {
    private final FluxzeroErrorReport fluxzeroErrorReport;

    /**
     * Constructs a new {@code TrackingException} with the specified message and cause.
     *
     * @param message the detail message
     * @param cause   the root cause
     */
    public TrackingException(String message, Throwable cause) {
        super(message, cause);
        this.fluxzeroErrorReport = null;
    }

    /**
     * Constructs a new {@code TrackingException} with the specified message.
     *
     * @param message the detail message
     */
    public TrackingException(String message) {
        super(message);
        this.fluxzeroErrorReport = null;
    }

    public TrackingException(FluxzeroErrorReport fluxzeroErrorReport) {
        super(fluxzeroErrorReport.formatSafely());
        this.fluxzeroErrorReport = fluxzeroErrorReport;
    }

    public TrackingException(FluxzeroErrorReport fluxzeroErrorReport, Throwable cause) {
        super(fluxzeroErrorReport.formatSafely(), cause);
        this.fluxzeroErrorReport = fluxzeroErrorReport;
    }

    @JsonIgnore
    public FluxzeroErrorReport getFluxzeroErrorReport() {
        return fluxzeroErrorReport;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getErrorCode() {
        return fluxzeroErrorReport == null ? null : fluxzeroErrorReport.getErrorCode();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDocumentationUrl() {
        return fluxzeroErrorReport == null ? null : fluxzeroErrorReport.getDocumentationUrl();
    }
}
