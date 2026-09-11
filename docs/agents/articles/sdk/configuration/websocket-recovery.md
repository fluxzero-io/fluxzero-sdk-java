Use the configured `WebSocketClient` and SDK properties for connection recovery. Do not add application-level
reconnect loops around individual command/query calls; distinguish transport reconnection from business retries.

## Reconnect backoff

Compatibility mode retries failed WebSocket connections every second. Enable capped exponential equal-jitter
backoff explicitly:

```properties
fluxzero.websocket.reconnectBackoff.enabled=true
```

The environment variable is `FLUXZERO_WEBSOCKET_RECONNECT_BACKOFF_ENABLED`. The behavior is also enabled by
`fluxzero.defaults.version >= 2026.09.09`. An explicit `false` overrides the defaults version and retains fixed retries.
With the default reconnect delay, backoff grows with consecutive failures, is jittered, and is capped at sixteen
seconds. A custom initial delay above sixteen seconds also raises that ceiling. Successful connection recovery
resets the failure sequence. Avoid asserting one exact randomized delay in an application test.

Transport diagnostics are single-flight per client on a dedicated timeboxed worker. A failure to publish diagnostic
metrics must not queue unbounded work on result-completion workers. Reconnect behavior should therefore remain bounded
even while both the connection and its diagnostic publication fail.

## Task and client identity

`FLUXZERO_TASK_ID` identifies the platform task/pod. It supplies authoritative `$taskId` correlation metadata and is
used as a prefix for generated client IDs. It is not itself the complete unique client identity.

`FLUXZERO_CLIENT_ID` can set an explicitly unique client/process ID. Otherwise the SDK generates a task-ID-prefixed
UUID, or a random UUID when no task ID is configured. Preserve uniqueness when several clients run in the same task.
Readiness or correlation logic that requires the exact client ID must use that ID rather than equate it to the task ID.

Use `ApplicationProperties` or the component's configured `PropertySource` for these settings. Keep explicit client
builder configuration near bootstrap; application handlers should use the SDK facade and typed messages.
