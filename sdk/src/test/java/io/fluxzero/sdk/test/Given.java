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

package io.fluxzero.sdk.test;

import io.fluxzero.common.ThrowingConsumer;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.Nullable;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.authentication.UserProvider;
import io.fluxzero.sdk.web.WebRequest;

import java.net.HttpCookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Defines the {@code given} phase of a behavioral Given-When-Then test using a {@link TestFixture}.
 * <p>
 * Use this interface to declare all prior context before executing the behavior you want to test. This can include
 * commands, events, schedules, documents, requests, and more. Any effects introduced during this phase
 * <strong>will not</strong> be included in the {@code then} phase assertions.
 * <p>
 * This interface extends {@link When}, allowing you to skip directly to the {@code when} phase if no prior activity is
 * required.
 * <p>
 * In all {@code givenXyz(...)} methods, any argument that is a {@link String} and ends with {@code .json} is
 * interpreted as the location of a JSON resource (e.g., {@code "/user/create-user.json"}). The resource will be loaded
 * and deserialized using {@link JsonUtils}.
 * <p>
 * The JSON file must include a {@code @class} property to indicate the fully qualified class name of the object to
 * deserialize:
 * <pre>{@code
 * {
 *   "@class": "com.example.CreateUser",
 *   ...
 * }
 * }</pre>
 * <p>
 * It is also possible to refer to a class using its simple name or partial package path if the class or one of its
 * ancestor packages is annotated with {@link io.fluxzero.common.serialization.RegisterType @RegisterType}. For example,
 * if {@code @RegisterType} is present on the {@code com.example} package, you may use:
 * <pre>{@code
 * {
 *   "@class": "CreateUser"
 * }
 * }
 * or
 * {
 *   "@class": "example.CreateUser"
 * }
 * }</pre>
 * <p>
 * JSON files can also extend other JSON files via an {@code @extends} property. The contents of the referenced file
 * will be recursively merged, with the extending file's values overriding nested properties as needed:
 * <pre>{@code
 * {
 *   "@extends": "/user/create-user.json",
 *   "details" : {
 *       "lastName": "Johnson"
 *   }
 * }
 * }</pre>
 * This behavior is described in more detail in {@link JsonUtils}.
 *
 * @see When
 * @see TestFixture
 * @see JsonUtils
 * @see io.fluxzero.common.serialization.RegisterType
 */
public interface Given<Self extends Given<Self>> extends When {

    /**
     * Specifies one or more commands that were issued before the behavior under test.
     * <p>
     * Each command can be a raw {@link Message} or a plain object. If the value is not a {@code Message}, it will be
     * wrapped with default metadata and used as the message payload.
     * <p>
     * You may also pass a {@code String} ending in {@code .json} to load a payload from a resource file.
     */
    Self givenCommands(Object... commands);

    /**
     * Specifies one or more commands that were issued by the specified {@code user} before the behavior under test.
     * <p>
     * The user may be a {@link User} object or an identifier. If an ID is provided, the {@link UserProvider} will
     * resolve it to a {@code User}.
     * <p>
     * Each command can be a raw {@link Message} or a plain object. If not a {@code Message}, it will be wrapped as a
     * payload.
     */
    Self givenCommandsByUser(Object user, Object... commands);

    /**
     * Specifies one or more requests that were sent to the given custom message {@code topic} before the behavior under
     * test.
     * <p>
     * Each request may be a {@link Message} or a plain object to be wrapped with default metadata.
     */
    Self givenCustom(String topic, Object... requests);

    /**
     * Specifies one or more requests that were sent to the given custom {@code topic} by the specified {@code user}.
     * <p>
     * The user may be a {@link User} object or an identifier, resolved via {@link UserProvider} if needed.
     * <p>
     * Each request may be a {@link Message} or a plain object to be wrapped with default metadata.
     */
    Self givenCustomByUser(Object user, String topic, Object... requests);

    /**
     * Simulates one or more events that were previously applied to a specific aggregate.
     * <p>
     * Events can be {@link Message}, {@link Data} (auto-deserialized and upcasted), or plain objects.
     * <p>
     * You may also pass a {@code String} ending in {@code .json} to load an event from a resource file.
     */
    Self givenAppliedEvents(Id<?> aggregateId, Object... events);

    /**
     * Simulates one or more events that were previously applied to a specific aggregate.
     * <p>
     * Events can be {@link Message}, {@link Data} (auto-deserialized and upcasted), or plain objects.
     */
    Self givenAppliedEvents(String aggregateId, Class<?> aggregateClass, Object... events);

    /**
     * Publishes one or more events that were emitted before the behavior under test.
     * <p>
     * Events may be {@link Message} or plain objects.
     */
    Self givenEvents(Object... events);

    /**
     * Stores a document in the search index before the behavior under test.
     * <p>
     * If the object is annotated with {@link Searchable}, the annotation metadata will determine how it's indexed.
     */
    Self givenDocument(Object document);

    /**
     * Stores a document in the given {@code collection} with a generated ID and no timestamps.
     */
    Self givenDocument(Object document, Object collection);

    /**
     * Stores a document in the given {@code collection} with a specific {@code id} and no timestamps.
     */
    Self givenDocument(Object document, Object id, Object collection);

    /**
     * Stores a document with a specific {@code id} and timestamp in the given {@code collection}. The timestamp is used
     * as both start and end time.
     */
    Self givenDocument(Object document, Object id, Object collection, Instant timestamp);

    /**
     * Stores a document with specific {@code id}, {@code collection}, and time range.
     */
    Self givenDocument(Object document, Object id, Object collection, Instant start, Instant end);

    /**
     * Stores multiple documents in the given {@code collection} with random IDs and no timestamps.
     */
    Self givenDocuments(Object collection, Object firstDocument, Object... otherDocuments);

    /**
     * Registers a stateful handler instance before the behavior under test.
     * <p>
     * Internally delegates to {@link #givenDocument(Object)}.
     *
     * @see io.fluxzero.sdk.tracking.handling.Stateful
     */
    Self givenStateful(Object stateful);

    /**
     * Schedules one or more commands before the behavior under test.
     */
    Self givenSchedules(Schedule... schedules);

    /**
     * Issues one or more scheduled commands before the behavior under test.
     */
    Self givenScheduledCommands(Schedule... commands);

    /**
     * Simulates schedules that have already expired and should be processed before the behavior under test is
     * executed.
     * <p>
     * Each provided object is interpreted as either a {@link Schedule} or as a schedule payload. Non-schedule objects
     * are wrapped in a {@link Schedule} with a generated technical identifier and the current test time as deadline.
     * <p>
     * All schedules are processed in order, and test time is advanced to the latest schedule deadline if necessary,
     * ensuring that any intermediate schedule effects are applied before the {@code when} phase begins.
     */
    Self givenExpiredSchedules(Object... schedules);

    /**
     * Advances the test clock to the specified {@code timestamp} before the behavior under test.
     * <p>
     * Time may be advanced in steps rather than a single jump if messages are set to expire before the target
     * timestamp, ensuring that intermediate schedules are processed in order. If handling those schedules triggers
     * additional schedules that are due before the target timestamp, they are fired as well.
     * <p>
     * This allows tests to simulate real-world scheduling behavior accurately.
     */
    Self givenTimeAdvancedTo(Instant timestamp);

    /**
     * Advances the test clock by the given {@code duration} before the behavior under test.
     * <p>
     * Time may be advanced in steps rather than a single jump if messages are set to expire before the target
     * timestamp, ensuring that intermediate schedules are processed in order. If handling those schedules triggers
     * additional schedules that are due before the target timestamp, they are fired as well.
     * <p>
     * This allows tests to simulate real-world scheduling behavior accurately.
     */
    Self givenElapsedTime(Duration duration);

    /**
     * Simulates a web request that was issued before the behavior under test.
     */
    Self givenWebRequest(WebRequest webRequest);

    /**
     * Simulates a web request that was issued by the given user before the behavior under test.
     * <p>
     * The user may be {@code null}, or a {@link User} object or an identifier. If an ID is provided, the
     * {@link UserProvider} will resolve it to a {@code User}.
     */
    Self givenWebRequestByUser(@Nullable Object user, WebRequest webRequest);

    /**
     * Simulates a POST request to the specified {@code path} with the given {@code payload}.
     */
    Self givenPost(String path, Object payload);

    /**
     * Simulates a POST request by the given user to the specified {@code path} with the given {@code payload}.
     * <p>
     * The user may be {@code null}, or a {@link User} object or an identifier. If an ID is provided, the
     * {@link UserProvider} will resolve it to a {@code User}.
     */
    Self givenPostByUser(Object user, String path, Object payload);

    /**
     * Simulates a PUT request to the specified {@code path} with the given {@code payload}.
     */
    Self givenPut(String path, Object payload);

    /**
     * Simulates a PUT request by the given user to the specified {@code path} with the given {@code payload}.
     * <p>
     * The user may be {@code null}, or a {@link User} object or an identifier. If an ID is provided, the
     * {@link UserProvider} will resolve it to a {@code User}.
     */
    Self givenPutByUser(Object user, String path, Object payload);

    /**
     * Simulates a PATCH request to the specified {@code path} with the given {@code payload}.
     */
    Self givenPatch(String path, Object payload);

    /**
     * Simulates a PATCH request by the given user to the specified {@code path} with the given {@code payload}.
     * <p>
     * The user may be {@code null}, or a {@link User} object or an identifier. If an ID is provided, the
     * {@link UserProvider} will resolve it to a {@code User}.
     */
    Self givenPatchByUser(Object user, String path, Object payload);

    /**
     * Simulates a DELETE request to the specified {@code path}.
     */
    Self givenDelete(String path);

    /**
     * Simulates a DELETE request by the given user to the specified {@code path} with the given {@code payload}.
     * <p>
     * The user may be {@code null}, or a {@link User} object or an identifier. If an ID is provided, the
     * {@link UserProvider} will resolve it to a {@code User}.
     */
    Self givenDeleteByUser(Object user, String path);

    /**
     * Simulates a GET request to the specified {@code path}.
     */
    Self givenGet(String path);

    /**
     * Simulates a GET request by the given user to the specified {@code path} with the given {@code payload}.
     * <p>
     * The user may be {@code null}, or a {@link User} object or an identifier. If an ID is provided, the
     * {@link UserProvider} will resolve it to a {@code User}.
     */
    Self givenGetByUser(Object user, String path);

    /**
     * Performs any arbitrary precondition using the {@link Fluxzero} instance directly.
     */
    Self given(ThrowingConsumer<Fluxzero> condition);

    /**
     * Returns the {@link Fluxzero} instance used in this test fixture.
     */
    Fluxzero getFluxzero();

    /**
     * Returns the clock used by this test fixture.
     */
    default Clock getClock() {
        return getFluxzero().clock();
    }

    /**
     * Returns the current time according to the test fixture's clock.
     */
    default Instant getCurrentTime() {
        return getClock().instant();
    }

    /**
     * Adds a cookie to be used in subsequent simulated web requests.
     */
    default Self withCookie(String name, String value) {
        return withCookie(new HttpCookie(name, value));
    }

    /**
     * Adds a cookie to be used in subsequent simulated web requests.
     * <p>
     * Expired cookies will remove any matching previously added cookie.
     */
    Self withCookie(HttpCookie cookie);

    /**
     * Adds or removes headers to be used in subsequent simulated web requests.
     * <p>
     * If {@code headerValues} is empty, the header will be removed.
     */
    Self withHeader(String headerName, String... headerValues);

    /**
     * Removes a previously set header for simulated web requests.
     */
    default Self withoutHeader(String headerName) {
        return withHeader(headerName);
    }

    /**
     * Overrides the test fixture's clock. Use this to simulate time-based behavior explicitly.
     */
    Self withClock(Clock clock);

    /**
     * Fixes the time at which the test fixture starts.
     */
    Self atFixedTime(Instant time);

    /**
     * Sets an application property for the duration of the test fixture.
     * <p>
     * Only effective if your components resolve properties through {@link ApplicationProperties}.
     */
    Self withProperty(String name, Object value);

    /**
     * Registers a singleton (injected) bean to be used during the test fixture.
     * <p>
     * The bean will be available for injection into handlers that use
     * {@link org.springframework.beans.factory.annotation.Autowired}.
     */
    Self withBean(Object bean);

    /**
     * Instructs the test fixture to ignore any exceptions that occur during the {@code Given} phase.
     * <p>
     * Errors in a later phase will still be recorded.
     */
    Self ignoringErrors();
}
