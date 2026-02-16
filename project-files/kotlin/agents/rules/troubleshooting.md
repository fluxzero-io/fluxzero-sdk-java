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

---

<a name="test-failures"></a>

## Test Failures

### Published messages did not match (test assertion)

**Cause**:
- The handler for the expected message is not registered in the `TestFixture`.
- The expectation is missing orchestrated follow-ups (e.g., you expect the aggregate event but forgot the command sent by a saga).

**Fix**:
- Ensure the fixture registers all relevant handlers: `TestFixture.create(BookingProcess::class, BookingHandler::class)`.
- Include both the primary event and any follow-up commands in `expectEvents(...)`.
- See: [Testing: TestFixture](./testing.md#testfixture)

---

<a name="entity-state-issues"></a>

## Entity & State Issues

### Stateful never created / no state found

**Cause**:
- Missing `companion object` creator method with `@HandleEvent` and `@JvmStatic` in the `@Stateful` class.

**Fix**:
- Add a `companion object` with a `@HandleEvent` method returning the new state instance, annotated with `@JvmStatic`.
- [//]: # (@formatter:off)
  ```kotlin
  companion object {
      @HandleEvent
      @JvmStatic
      fun start(event: CreateOrder): MySaga {
          return MySaga(event.orderId)
      }
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
  ```kotlin
  @HandleEvent
  @Association("orderId")
  fun on(event: PaymentAuthorized) { ... }
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
- Run `mvn clean compile` to force a full rebuild.

### Timeouts not firing

**Cause**:
- Using `Fluxzero.scheduleCommand` with an invalid duration string or not scheduling it at all.

**Fix**:
- Ensure ISO-8601 duration format (e.g., `PT10M` for 10 minutes).
- Use `Fluxzero.scheduleCommand(MyCmd(id), Duration.parse(ApplicationProperties.getProperty("key", "PT10M")))`.
- See: [Sending: Schedules](./sending.md#schedules)

### Validation errors on valid-looking data

**Cause**:
- Missing `@field:Valid` on nested objects or collections in the command/query data class.

**Fix**:
- Add `@field:Valid` to any property that contains a class with validation constraints.
- See: [Handling: Validation](./handling.md#payloads)
