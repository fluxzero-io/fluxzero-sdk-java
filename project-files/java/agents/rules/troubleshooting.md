---
title: Troubleshooting (Errors → Fix)
description: Map common failures to precise fixes and doc anchors.
---

# Troubleshooting

This guide helps you quickly resolve common issues encountered when building Fluxzero applications.

---

## Quick Navigation

- [Test Failures](#test-failures)
- [Entity & State Issues](#entity-state-issues)
- [Routing & Handlers](#routing-handlers)
- [System & Infrastructure](#system-infrastructure)
- [SDK Error Codes](#sdk-error-codes)

---

<a name="test-failures"></a>

## Test Failures

### Published messages did not match (test assertion)

**Cause**:
- The handler for the expected message is not registered in the `TestFixture`.
- The expectation is missing orchestrated follow-ups (e.g., you expect the aggregate event but forgot the command sent by a saga).

**Fix**:
- Ensure the fixture registers all relevant handlers: `TestFixture.create(BookingProcess.class, BookingHandler.class)`.
- Include both the primary event and any follow-up commands in `expectEvents(...)`.
- See: [Testing: TestFixture](./testing.md#testfixture)

---

<a name="entity-state-issues"></a>

## Entity & State Issues

### Stateful never created / no state found

**Cause**:
- Missing `static` `@HandleEvent` creator method in the `@Stateful` class.

**Fix**:
- Add a `static` `@HandleEvent` method that returns the new state instance for the starting event.
- [//]: # (@formatter:off)
  ```java
  @HandleEvent
  static MySaga start(CreateOrder event) {
      return new MySaga(event.orderId());
  }
  ```
  [//]: # (@formatter:on)
- See: [Sagas: Lifecycle](./sagas.md#lifecycle)

### Need to end a stateful saga

**Solution**:
- Return `null` from a `@HandleEvent` method on the terminal event.
- See: [Sagas: Lifecycle](./sagas.md#lifecycle)

---

<a name="routing-handlers"></a>

## Routing & Handlers

### Events not routed to the stateful saga

**Cause**:
- `@Association` is missing on the correlation key in the handler method.

**Fix**:
- Add `@Association` on the handler method or on a field in the stateful.
- [//]: # (@formatter:off)
  ```java
  @HandleEvent
  @Association("orderId")
  void on(PaymentAuthorized event) { ... }
  ```
  [//]: # (@formatter:on)
- See: [Sagas: Associations](./sagas.md#associations)

### Handler not invoked at runtime

**Cause**:
- Wrong package structure (not picked up by component scanning).
- Missing `@Component` annotation.
- Payload type mismatch (e.g., handling a subclass but message is published as base class).

**Fix**:
- Verify the class is in a subpackage of the application root.
- Ensure `@Component` is present.
- See: [Handling: Common Pitfalls](./handling.md#common-pitfalls)

---

<a name="system-infrastructure"></a>

## System & Infrastructure

### ClassNotFound after adding new types

**Cause**:
- The incremental compiler (IDE) or Maven didn't pick up the new generated classes.

**Fix**:
- Run `mvn clean` to force a full rebuild.

### Timeouts not firing

**Cause**:
- Using `Fluxzero.scheduleCommand` with an invalid duration string or not scheduling it at all.

**Fix**:
- Ensure ISO-8601 duration format (e.g., `PT10M` for 10 minutes).
- Use `Fluxzero.scheduleCommand(new MyCmd(id), Duration.parse(ApplicationProperties.getProperty("key", "PT10M")))`.
- See: [Sending: Schedules](./sending.md#schedules)

### Missing validation errors on invalid data

**Cause**:
- Missing `@Valid` on nested objects or collections in the command/query record.

**Fix**:
- Add `@Valid` to any field that contains a class with validation constraints.
- See: [Handling: Validation](./handling.md#payloads)

---

<a name="sdk-error-codes"></a>

## SDK Error Codes

Fluxzero SDK error messages can include stable codes. Prefer matching on the code in logs and support notes, while using
the human-readable sections to choose the fix.
Published codes are stable support identifiers: do not assume the explanatory text is fixed, and do not reuse an
existing code for a different category. After 100 full renders of the same code in one JVM, the SDK emits a shorter
message with the code and docs URL (`https://fluxzero.io/docs/errors#<code>`) to avoid flooding logs.

| Code          | Meaning                                    | Fix |
|---------------|--------------------------------------------|-----|
| `FZ-SDK-0001` | No Fluxzero instance is available          | Run inside `fluxzero.apply(...)`, set `Fluxzero.applicationInstance`, or use `TestFixture`. |
| `FZ-SDK-0002` | Request timed out                          | Register/start the matching handler and check namespace, topic, routing, passive handlers, and result return values. |
| `FZ-SDK-0003` | Handler invocation failed                  | Inspect the cause; use a `FunctionalException` for expected business failures. |
| `FZ-SDK-0004` | Response dispatch failed                   | Check response serialization, interceptors, and result/web-response connectivity. |
| `FZ-SDK-0005` | Thread interrupted while waiting           | Check shutdown/cancellation behavior and retry policy. |
| `FZ-SDK-0006` | Message dispatch failed                    | Check dispatch interceptors, serialization, namespace/topic, and client connectivity. |
| `FZ-SDK-0007` | Missing `UserProvider`                     | Configure `Fluxzero#userProvider`, set `UserProvider.defaultUserProvider`, or remove `Message.addUser(...)`. |
| `FZ-SDK-0008` | Invalid tracking consumer configuration    | Use unique consumer names, register handlers before tracking starts, or add a matching `@Consumer`/`ConsumerConfiguration`. |
| `FZ-SDK-0009` | Invalid `@Periodic` schedule configuration | Add `cron`, add a positive `delay`, or use `Periodic.DISABLED`. |
| `FZ-SDK-0010` | Tracking failed during runtime             | Check the cause, client/message-store connectivity, and expected shutdown/interruption behavior. |
