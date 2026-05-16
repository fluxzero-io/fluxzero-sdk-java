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

import io.fluxzero.common.MessageType;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Factory methods for human-readable SDK errors.
 */
public final class FluxzeroErrors {

    private FluxzeroErrors() {
    }

    /**
     * Creates the report used when code calls Fluxzero without an active instance.
     */
    public static FluxzeroErrorReport fluxzeroInstanceMissing() {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.FLUXZERO_INSTANCE_MISSING,
                        "No Fluxzero instance is available for this call")
                .whatHappened("Fluxzero.get() was called, but no Fluxzero instance is bound to the current thread "
                              + "and no application instance has been registered.")
                .whyThisHappened("Fluxzero static helpers such as Fluxzero.sendCommand(...) need an active "
                                 + "Fluxzero instance to know which client, gateways, serializers, and stores to use.")
                .howToFix("Create a Fluxzero instance and run the code inside fluxzero.apply(...).",
                          "For application startup, assign Fluxzero.applicationInstance before using static helpers.",
                          "Inside tests, use TestFixture.create(...) or TestFixture.createAsync(...) so the fixture "
                          + "binds Fluxzero for the duration of the interaction.")
                .build();
    }

    /**
     * Creates the report used when a request does not receive a response in time.
     */
    public static FluxzeroErrorReport requestTimedOut(
            String requestDescription, String payloadType, String messageId, Object requestId,
            String responseDescription, Duration timeout) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.REQUEST_TIMED_OUT,
                        "Request timed out while waiting for " + article(responseDescription) + " "
                        + responseDescription + " response")
                .whatHappened("Fluxzero dispatched " + article(requestDescription) + " " + requestDescription
                              + " of type `" + display(payloadType) + "`, but no matching `"
                              + display(responseDescription) + "` response arrived before the timeout elapsed.")
                .whyThisHappened("The most common cause is that no matching handler is registered or running.",
                                 "Another common cause is a passive handler, a handler that returned no result for a "
                                 + "request, or a handler that was filtered out by consumer, routing, topic, segment, "
                                 + "or authentication rules.")
                .howToFix("Register a matching handler annotation for this request, for example @HandleCommand, "
                          + "@HandleQuery, or @HandleWeb.",
                          "If the handler runs in another process, make sure that process and its Fluxzero consumer "
                          + "are started and using the same namespace and topic.",
                          "If this request is intentionally fire-and-forget, use sendAndForget or mark the handler "
                          + "passive instead of waiting for a result.")
                .context("Request type", requestDescription)
                .context("Payload type", payloadType)
                .context("Message id", messageId)
                .context("Request id", requestId)
                .context("Expected response", responseDescription)
                .context("Timeout", timeout)
                .build();
    }

    /**
     * Creates a {@link TimeoutException} with a human-readable Fluxzero error report as its message.
     */
    public static TimeoutException requestTimeoutException(
            String requestDescription, String payloadType, String messageId, Object requestId,
            String responseDescription, Duration timeout) {
        return new TimeoutException(requestTimedOut(
                requestDescription, payloadType, messageId, requestId, responseDescription, timeout).format());
    }

    /**
     * Creates the report used when a handler failed while processing a message.
     */
    public static FluxzeroErrorReport handlerInvocationFailed(
            String handlerDescription, String messageDescription, Throwable cause) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.HANDLER_INVOCATION_FAILED,
                        "Handler failed while processing a message")
                .whatHappened("Handler `" + display(handlerDescription)
                              + "` threw an exception while processing `" + display(messageDescription) + "`.")
                .whyThisHappened("The handler was selected for the message, but the user code or an interceptor "
                                 + "failed before Fluxzero could complete handling.")
                .howToFix("Inspect the original cause and stack trace in the logs.",
                          "Verify the handler assumptions, resolved parameters, authentication context, and payload "
                          + "shape for this message.",
                          "If this is an expected business rejection, throw a FunctionalException such as "
                          + "IllegalCommandException so callers receive a functional error instead of a technical one.")
                .context("Handler", handlerDescription)
                .context("Message", messageDescription)
                .context("Cause", causeDescription(cause))
                .build();
    }

    /**
     * Creates the report used when Fluxzero cannot dispatch a response.
     */
    public static FluxzeroErrorReport responseDispatchFailed(
            String responseDescription, String target, Object requestId, Throwable cause) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.RESPONSE_DISPATCH_FAILED,
                        "Failed to dispatch a response")
                .whatHappened("Fluxzero handled a request, but failed while publishing the response `"
                              + display(responseDescription) + "` back to the requester.")
                .whyThisHappened("The response could not be serialized, intercepted, or appended to the response log.")
                .howToFix("Check the original cause in this exception.",
                          "Verify that the response payload is serializable by the configured serializer.",
                          "Inspect custom dispatch interceptors and the Fluxzero connection to the result log.")
                .context("Response", responseDescription)
                .context("Target", target)
                .context("Request id", requestId)
                .context("Cause", causeDescription(cause))
                .build();
    }

    /**
     * Creates the report used when a message cannot be dispatched.
     */
    public static FluxzeroErrorReport messageDispatchFailed(
            MessageType messageType, String topic, int messageCount, Throwable cause) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.MESSAGE_DISPATCH_FAILED,
                        "Failed to dispatch messages")
                .whatHappened("Fluxzero tried to publish " + messageCount + " message(s) to the `"
                              + display(messageType) + "` log, but dispatch failed before the messages were accepted.")
                .whyThisHappened("The failure can happen during dispatch interception, serialization, or append to "
                                 + "the Fluxzero client.")
                .howToFix("Check the original cause in this exception.",
                          "Verify custom dispatch interceptors for this message type.",
                          "Check that the Fluxzero client is configured and connected for this namespace and topic.")
                .context("Message type", messageType)
                .context("Topic", topic)
                .context("Message count", messageCount)
                .context("Cause", causeDescription(cause))
                .build();
    }

    /**
     * Creates the report used when a message cannot attach user metadata.
     */
    public static FluxzeroErrorReport userProviderMissing() {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.USER_PROVIDER_MISSING,
                        "No UserProvider is configured")
                .whatHappened("Message.addUser(...) was called, but Fluxzero could not find a configured "
                              + "UserProvider.")
                .whyThisHappened("Fluxzero uses UserProvider to serialize user information into message metadata. "
                                 + "Without it, the SDK does not know how to represent the user on the message.")
                .howToFix("Configure a UserProvider on the Fluxzero instance.",
                          "For simple applications, set UserProvider.defaultUserProvider before adding users to "
                          + "messages.",
                          "If the message does not need user metadata, remove the addUser(...) call.")
                .build();
    }

    /**
     * Creates the report used when tracking cannot be configured or started.
     */
    public static FluxzeroErrorReport trackingConfigurationInvalid(
            String title, String whatHappened, String howToFix, Object handler, Object consumer) {
        return FluxzeroErrorReport.builder(FluxzeroErrorCode.TRACKING_CONFIGURATION_INVALID, title)
                .whatHappened(whatHappened)
                .whyThisHappened("Fluxzero needs every tracking handler to match exactly one valid consumer "
                                 + "configuration unless the handler is explicitly configured otherwise.")
                .howToFix(howToFix)
                .context("Handler", handler)
                .context("Consumer", consumer)
                .build();
    }

    /**
     * Creates the report used when tracking is requested for an unsupported message type.
     */
    public static FluxzeroErrorReport trackingNotSupported(MessageType messageType) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.TRACKING_CONFIGURATION_INVALID,
                        "Tracking is not supported for this message type")
                .whatHappened("Fluxzero.tracking(...) was called for `" + display(messageType)
                              + "`, but this Fluxzero instance has no tracking component for that message type.")
                .whyThisHappened("Tracking is only available for message types that are backed by a configured tracking "
                                 + "client and consumer registry.")
                .howToFix("Use one of the configured tracking message types for this Fluxzero instance.",
                          "If this should be trackable, configure the corresponding tracking component when building "
                          + "the Fluxzero instance.")
                .context("Message type", messageType)
                .build();
    }

    /**
     * Creates the report used when a tracker fails while managing its runtime state.
     */
    public static FluxzeroErrorReport trackingRuntimeFailed(
            String operationDescription, Object tracker, int[] segment, Long index, Throwable cause) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.TRACKING_RUNTIME_FAILED,
                        "Tracking failed while " + display(operationDescription))
                .whatHappened("A Fluxzero tracker failed while " + display(operationDescription) + ".")
                .whyThisHappened("The tracker could not complete a required runtime operation such as storing its "
                                 + "position, disconnecting, or checking message-log maintenance state.")
                .howToFix("Check the original cause in this exception.",
                          "Verify that the Fluxzero client and message store are reachable.",
                          "If the tracker was shutting down, check whether the interruption or disconnect was expected.")
                .context("Operation", operationDescription)
                .context("Tracker", tracker)
                .context("Segment", segment == null ? null : Arrays.toString(segment))
                .context("Index", index)
                .context("Cause", causeDescription(cause))
                .build();
    }

    /**
     * Creates the report used when a periodic schedule is not valid.
     */
    public static FluxzeroErrorReport periodicScheduleInvalid(Class<?> payloadType) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.PERIODIC_SCHEDULE_INVALID,
                        "Periodic schedule has no valid period")
                .whatHappened("@Periodic on type `" + display(payloadType == null ? null : payloadType.getName())
                              + "` does not define a cron expression or a positive delay.")
                .whyThisHappened("A periodic schedule needs either `cron` or `delay` so Fluxzero can calculate the "
                                 + "first deadline.")
                .howToFix("Set a cron expression, for example @Periodic(cron = \"0 * * * *\").",
                          "Or set a positive delay, for example @Periodic(delay = 60, timeUnit = TimeUnit.SECONDS).",
                          "Use @Periodic(cron = Periodic.DISABLED) when the schedule should be disabled by "
                          + "configuration.")
                .context("Payload type", payloadType == null ? null : payloadType.getName())
                .build();
    }

    /**
     * Creates the report used when code asks Fluxzero to schedule a value periodically without @Periodic.
     */
    public static FluxzeroErrorReport periodicScheduleAnnotationMissing(Class<?> payloadType) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.PERIODIC_SCHEDULE_INVALID,
                        "Periodic schedule annotation is missing")
                .whatHappened("MessageScheduler.schedulePeriodic(...) was called for type `"
                              + display(payloadType == null ? null : payloadType.getName())
                              + "`, but that type is not annotated with @Periodic.")
                .whyThisHappened("schedulePeriodic(...) reads @Periodic from the payload type to determine the cron "
                                 + "expression, delay, timezone, and schedule id.")
                .howToFix("Add @Periodic to the payload type.",
                          "Or use schedule(...) directly when the deadline is calculated by application code.")
                .context("Payload type", payloadType == null ? null : payloadType.getName())
                .build();
    }

    /**
     * Creates the report used when a blocking wait is interrupted.
     */
    public static FluxzeroErrorReport threadInterrupted(
            String operationDescription, String messageId, String payloadType) {
        return FluxzeroErrorReport.builder(
                        FluxzeroErrorCode.THREAD_INTERRUPTED,
                        "Thread interrupted while waiting for Fluxzero")
                .whatHappened("The current thread was interrupted while Fluxzero was waiting for "
                              + operationDescription + " to complete.")
                .whyThisHappened("The application, test runner, or container interrupted the thread before a response "
                                 + "could be returned.")
                .howToFix("Check the caller that owns this thread for cancellation, shutdown, or timeout behavior.",
                          "If interruption is expected, handle it at the application boundary and decide whether the "
                          + "request should be retried.")
                .context("Operation", operationDescription)
                .context("Message id", messageId)
                .context("Payload type", payloadType)
                .build();
    }

    private static String display(Object value) {
        return Objects.toString(FluxzeroErrorReport.safeString(value), "unknown");
    }

    private static String causeDescription(Throwable cause) {
        if (cause == null) {
            return null;
        }
        return cause.getClass().getName() + ": " + safeCauseMessage(cause);
    }

    private static String safeCauseMessage(Throwable cause) {
        try {
            return Objects.toString(cause.getMessage(), "");
        } catch (RuntimeException e) {
            return FluxzeroErrorReport.UNSAFE_VALUE_PLACEHOLDER;
        }
    }

    private static String article(String value) {
        if (value == null || value.isBlank()) {
            return "a";
        }
        char first = Character.toLowerCase(value.charAt(0));
        return first == 'a' || first == 'e' || first == 'i' || first == 'o' || first == 'u' ? "an" : "a";
    }
}
