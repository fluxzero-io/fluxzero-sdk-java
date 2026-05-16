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

package io.fluxzero.sdk.common.exception;

import org.junit.jupiter.api.Test;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.publishing.GatewayException;
import io.fluxzero.sdk.tracking.TrackingException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluxzeroErrorReportTest {

    @Test
    void formatsStableCodeSectionsContextAndDocs() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        FluxzeroErrorReport report = FluxzeroErrors.requestTimedOut(
                "command", "com.example.CreateUser", "message-1", 12, "RESULT", Duration.ofSeconds(2));

        String message = report.format();

        assertEquals("FZ-SDK-0002", report.getErrorCode());
        assertEquals("https://fluxzero.io/docs/errors#FZ-SDK-0002", report.getDocumentationUrl());
        assertTrue(message.startsWith("FluxzeroError FZ-SDK-0002: Request timed out"));
        assertTrue(message.contains("What happened:"));
        assertTrue(message.contains("Why this happened:"));
        assertTrue(message.contains("How to fix:"));
        assertTrue(message.contains("Payload type:"));
        assertTrue(message.contains("com.example.CreateUser"));
        assertTrue(message.contains("Docs:"));
    }

    @Test
    void exposesCodeAndDocsOnTechnicalExceptionsCreatedFromReports() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        TechnicalException exception = new TechnicalException(FluxzeroErrors.handlerInvocationFailed(
                "UserHandler.handle", "CreateUser", new IllegalStateException("boom")));

        assertEquals("FZ-SDK-0003", exception.getErrorCode());
        assertEquals("https://fluxzero.io/docs/errors#FZ-SDK-0003",
                     exception.getDocumentationUrl());
        assertTrue(exception.getMessage().contains("Handler failed while processing a message"));
        assertTrue(exception.getMessage().contains("Cause:"));
    }

    @Test
    void exposesCodeAndDocsOnGatewayTrackingAndTimeoutExceptionsCreatedFromReports() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        GatewayException gatewayException = new GatewayException(FluxzeroErrors.messageDispatchFailed(
                MessageType.COMMAND, "billing", 2, new IllegalStateException("closed")), null);
        TrackingException trackingException = new TrackingException(FluxzeroErrors.trackingConfigurationInvalid(
                "No consumer configuration found for handler", "No consumer matched FooHandler",
                "Add @Consumer or a matching ConsumerConfiguration.", "FooHandler", null));
        io.fluxzero.sdk.publishing.TimeoutException timeoutException =
                new io.fluxzero.sdk.publishing.TimeoutException(FluxzeroErrors.requestTimedOut(
                        "query", "FindUser", "message-2", null, "RESULT", Duration.ofSeconds(1)));

        assertEquals("FZ-SDK-0006", gatewayException.getErrorCode());
        assertEquals("FZ-SDK-0008", trackingException.getErrorCode());
        assertEquals("FZ-SDK-0002", timeoutException.getErrorCode());
        assertTrue(gatewayException.getDocumentationUrl().endsWith("#FZ-SDK-0006"));
        assertTrue(trackingException.getDocumentationUrl().endsWith("#FZ-SDK-0008"));
        assertTrue(timeoutException.getDocumentationUrl().endsWith("#FZ-SDK-0002"));
    }

    @Test
    void exposesTrackingRuntimeCode() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        TrackingException exception = new TrackingException(FluxzeroErrors.trackingRuntimeFailed(
                "storing tracker position", "payments-1", new int[]{0, 16}, 42L,
                new IllegalStateException("client closed")));

        assertEquals("FZ-SDK-0010", exception.getErrorCode());
        assertTrue(exception.getDocumentationUrl().endsWith("#FZ-SDK-0010"));
        assertTrue(exception.getMessage().contains("Segment:"));
        assertTrue(exception.getMessage().contains("[0, 16]"));
    }

    @Test
    void suppressesFullDetailsAfterRenderLimitForSameCode() {
        FluxzeroErrorReport.resetFormatCountsForTesting();
        FluxzeroErrorReport report = FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.RESPONSE_DISPATCH_FAILED, "Response dispatch failed in test")
                .whatHappened("Full detail line")
                .whyThisHappened("Reason line")
                .howToFix("Fix line")
                .context("Response", "CreateUserResult")
                .context("Request id", 42)
                .build();

        String hundredth = "";
        for (int i = 0; i < 100; i++) {
            hundredth = report.format();
        }
        String hundredAndFirst = report.format();

        assertTrue(hundredth.contains("What happened:"));
        assertTrue(hundredAndFirst.contains("FZ-SDK-0004"));
        assertTrue(hundredAndFirst.contains("Context:"));
        assertTrue(hundredAndFirst.contains("Response=CreateUserResult"));
        assertTrue(hundredAndFirst.contains("Request id=42"));
        assertTrue(hundredAndFirst.contains("Full error details suppressed after 100 occurrences"));
        assertTrue(hundredAndFirst.contains("docs/errors#FZ-SDK-0004"));
        assertFalse(hundredAndFirst.contains("What happened:"));
    }

    @Test
    void contextRenderingDoesNotMaskOriginalErrorWhenToStringFails() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        FluxzeroErrorReport report = FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.TRACKING_CONFIGURATION_INVALID, "Bad context test")
                .whatHappened("Something failed")
                .context("Handler", new UnsafeToString())
                .build();

        String message = report.format();

        assertTrue(message.contains("FluxzeroError FZ-SDK-0008"));
        assertTrue(message.contains("Handler:"));
        assertTrue(message.contains(FluxzeroErrorReport.UNSAFE_VALUE_PLACEHOLDER));
    }

    @Test
    void causeRenderingDoesNotMaskOriginalErrorWhenCauseMessageFails() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        TechnicalException exception = new TechnicalException(FluxzeroErrors.handlerInvocationFailed(
                "UserHandler.handle", "CreateUser", new UnsafeMessageException()));

        assertEquals("FZ-SDK-0003", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Cause:"));
        assertTrue(exception.getMessage().contains(UnsafeMessageException.class.getName()));
        assertTrue(exception.getMessage().contains(FluxzeroErrorReport.UNSAFE_VALUE_PLACEHOLDER));
    }

    @Test
    void exceptionMessageFallsBackWhenReportFormattingFails() {
        FluxzeroErrorReport.resetFormatCountsForTesting();

        FluxzeroErrorReport report = new FluxzeroErrorReport(
                FluxzeroErrorCode.HANDLER_INVOCATION_FAILED, "Broken report",
                List.of("This line cannot be rendered"), List.of(), List.of(), null,
                "https://fluxzero.io/docs/errors#FZ-SDK-0003");

        TechnicalException exception = new TechnicalException(report);

        assertEquals("FZ-SDK-0003", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("FluxzeroError FZ-SDK-0003: Broken report"));
        assertTrue(exception.getMessage().contains("Rich error details could not be rendered:"));
        assertTrue(exception.getMessage().contains("Docs: https://fluxzero.io/docs/errors#FZ-SDK-0003"));
    }

    private static final class UnsafeToString {
        @Override
        public String toString() {
            throw new IllegalStateException("toString failed");
        }
    }

    private static final class UnsafeMessageException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage failed");
        }
    }
}
