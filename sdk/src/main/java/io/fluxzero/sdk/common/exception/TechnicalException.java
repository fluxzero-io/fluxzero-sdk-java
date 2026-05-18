/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.common.exception;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Exception indicating a technical or unexpected failure within the application.
 * <p>
 * Unlike {@link FunctionalException}, which is designed to communicate business or user-facing errors,
 * {@code TechnicalException} represents low-level, infrastructure, or unforeseen errors such as I/O issues,
 * serialization failures, or unexpected runtime failures.
 * </p>
 *
 * <p>
 * These exceptions are typically used to wrap application exceptions or surface errors that should not be transferred
 * to clients or external systems. However, they are still serialized cleanly (without stack traces) for logging or
 * fallback handling.
 * </p>
 *
 * <p>
 * Stack traces and suppressed exceptions are excluded to prevent information leakage during serialization.
 * </p>
 *
 * @see FunctionalException
 */
@JsonIgnoreProperties({"localizedMessage", "cause", "stackTrace", "suppressed"})
public class TechnicalException extends RuntimeException {
    private final FluxzeroErrorReport fluxzeroErrorReport;

    public TechnicalException() {
        super("An unexpected error occurred");
        this.fluxzeroErrorReport = null;
    }

    public TechnicalException(String message) {
        super(message);
        this.fluxzeroErrorReport = null;
    }

    public TechnicalException(String message, Throwable cause) {
        super(message, cause);
        this.fluxzeroErrorReport = null;
    }

    public TechnicalException(Throwable cause) {
        super(cause);
        this.fluxzeroErrorReport = null;
    }

    public TechnicalException(FluxzeroErrorReport fluxzeroErrorReport) {
        super(fluxzeroErrorReport.formatSafely());
        this.fluxzeroErrorReport = fluxzeroErrorReport;
    }

    public TechnicalException(FluxzeroErrorReport fluxzeroErrorReport, Throwable cause) {
        super(fluxzeroErrorReport.formatSafely(), cause);
        this.fluxzeroErrorReport = fluxzeroErrorReport;
    }

    public TechnicalException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.fluxzeroErrorReport = null;
    }

    /**
     * Returns the structured Fluxzero report backing this exception, if this exception was created from one.
     */
    @JsonIgnore
    public FluxzeroErrorReport getFluxzeroErrorReport() {
        return fluxzeroErrorReport;
    }

    /**
     * Returns the stable Fluxzero error code, if available.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getErrorCode() {
        return fluxzeroErrorReport == null ? null : fluxzeroErrorReport.getErrorCode();
    }

    /**
     * Returns the documentation URL for the error code, if available.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDocumentationUrl() {
        return fluxzeroErrorReport == null ? null : fluxzeroErrorReport.getDocumentationUrl();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TechnicalException that = (TechnicalException) o;
        return Objects.equals(getMessage(), that.getMessage());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getMessage());
    }
}
