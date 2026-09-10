Fluxzero SDK reports include stable `FZ-SDK-*` identifiers. Match diagnostics and support playbooks on the code; the
human-readable explanation can improve over time. Keep the original cause and structured context when reporting a
failure.

| Code | Meaning | Application-side checks |
| --- | --- | --- |
| `FZ-SDK-0001` | No active Fluxzero instance | Run inside a configured instance, set the application instance during bootstrap, or use `TestFixture`. |
| `FZ-SDK-0002` | Request timed out | Check matching handler discovery, namespace/topic, passive/result behavior, routing, authentication, and whether the remote consumer is running. |
| `FZ-SDK-0003` | Handler invocation failed | Inspect the cause, resolved parameters, payload shape, and user context. Use a `FunctionalException` only for an expected business rejection. |
| `FZ-SDK-0004` | Response dispatch failed | Check response serialization, custom dispatch interceptors, result/web-response connectivity, and the target request ID. |
| `FZ-SDK-0005` | Blocking wait was interrupted | Check application shutdown, caller cancellation, test-runner interruption, and whether retry is safe. Preserve the interrupt contract. |
| `FZ-SDK-0006` | Message dispatch failed | Check serialization, dispatch interceptors, client connection, namespace, message type, and topic. |
| `FZ-SDK-0007` | No `UserProvider` | Register the application user provider before adding a user to message metadata, or remove the user attachment if it is not required. |
| `FZ-SDK-0008` | Invalid tracking configuration | Check that each tracked handler matches one valid consumer, consumer names/configurations do not conflict, and handlers are registered before tracking starts. |
| `FZ-SDK-0009` | Invalid periodic schedule | Configure a cron expression or positive delay, add `@Periodic` when calling periodic scheduling, or use `Periodic.DISABLED` deliberately. |
| `FZ-SDK-0010` | Tracker runtime operation failed | Check the cause and managed-runtime connectivity; distinguish an expected shutdown/disconnect from failed position storage or tracker maintenance. |

## Preserve useful context

When surfacing a code in application logs or an operational incident, include only safe identifiers:

- application name and namespace;
- message type and custom topic when applicable;
- consumer and tracker ID;
- payload type, message/request ID, segment, and index when supplied by the report;
- original exception type and a sanitized message.

Do not log protected payload values, credentials, complete authorization headers, or encrypted configuration values.
An error code identifies a category; it does not prove the root cause. For example, `FZ-SDK-0002` can mean no handler,
a filtered handler, a handler that returned no result, or a running handler that exceeded the effective timeout.

## Decide who can act

Application teams can correct handler discovery, request/result contracts, consumer configuration, namespace/topic
selection, serializers, interceptors, user providers, and schedules. If those are correct and the supported Cloud
diagnostic reports a runtime or connectivity failure, preserve the report and escalate it. Do not translate a platform
failure into manual database, cluster, or internal-consumer operations.
