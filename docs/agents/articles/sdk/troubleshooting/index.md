Use this page when a Fluxzero application builds but a message, handler, consumer, schedule, or result does not behave
as expected. Start with the observable boundary that failed; do not change namespaces, consumer positions, guarantees,
or runtime state until the ordinary application wiring has been checked.

When a Fluxzero dev environment is active, begin with its structured view: `get_status`, bounded
`get_active_problems`, `get_test_status`, and then a filtered `get_logs` delta. After a fix, continue from the current
cursor with `wait_for_change`. Do not start a second app or wrapper build to diagnose an environment already owned by
the dev server.

## Request timed out

For a command, query, custom request, or web request that waits for a result, verify in this order:

1. The expected application instance is running in the same namespace and connected to the intended runtime.
2. The handler is discovered in production: a standalone Spring handler has `@Component`, or the instance is
   registered explicitly outside Spring.
3. The handler annotation matches the message type, custom topic, and payload class.
4. Authentication, `allowedClasses`, consumer filters, index bounds, and routing did not exclude the handler.
5. A request handler is not passive and actually returns a result.

Do not solve an unknown timeout by increasing it first. A longer wait does not repair a missing handler or a namespace
mismatch. Use built-in handler/tracker metrics and the stable SDK error code to distinguish no invocation from a slow
or failed invocation.

## Handler is present in tests but absent in production

`TestFixture.create(handler)` registers that handler only in the fixture. In Spring, an ordinary standalone handler
must be a bean, normally with `@Component`; `@Consumer` configures tracking but is not a Spring stereotype. Outside
Spring, register the instance on the configured `Fluxzero` client.

Also compare the exact serialized payload type. A handler for a subtype does not automatically handle a message that
was serialized as its base type. For `@Stateful`, `@SocketEndpoint`, and other class-discovered handlers, keep the
production discovery path and the fixture registration path independently verified.

## Stateful or member state is not found

- A new stateful instance normally starts from a matching static handler that returns the stateful type.
- Existing instances must have an `@EntityId` or a stable repository ID and a matching `@Association` name/value.
- A member create needs a route to its parent. A static member handler without a parent association does not identify
  which parent should receive the new member unless the association explicitly matches all parents.
- Returning `null` from a state-compatible instance handler deletes state. Returning an unrelated result does not.

Read the stateful member/lifecycle article before changing associations or return types.

## Published output differs from the test expectation

First decide whether the assertion is inclusive or exact. `expectEvents(...)` allows additional events;
`expectOnlyEvents(...)` rejects them. Given-phase outputs are processed but not collected by the next Then phase.
Use `andThen()` between actions and assert commands, events, metrics, schedules, web requests, and results in their own
channels.

If production behavior is missing, do not add messages to the expectation merely to make the test pass. Verify that
all intended handlers are registered and that the extra output is part of the product contract.

## Validation or authorization did not run

For nested values, `@Valid` cascades only into a present value; add `@NotNull`, collection-size constraints, and
non-null item constraints separately. Security annotations are resolved by specificity: method, class, package, then
super-package. A more specific annotation can replace a broader package rule.

Use `ValidationUtils.getConstraintViolations(...)` or a fixture assertion on `ViolationSummary.path()` to identify the
actual missing constraint. Do not treat a later `NullPointerException` as successful boundary validation.

## Escalate platform failures without operating internals

If SDK diagnostics show that correct application configuration cannot reach a managed runtime service, preserve the
error code, application name, namespace, message type/topic, consumer, timestamp, and relevant request/message IDs.
Use supported Cloud status and logs, then escalate the platform failure. Do not inspect or modify PostgreSQL,
Kubernetes, runtime storage, or internal platform consumers from an application-maintenance agent.
