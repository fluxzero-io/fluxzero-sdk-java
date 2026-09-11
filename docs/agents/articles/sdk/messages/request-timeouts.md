Use `@Timeout` on a request payload when its command/query/custom/web gateway result has a product-specific maximum
wait. A caller timeout limits waiting for the response; it is not automatic cancellation or rollback of already
dispatched work.

```java
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
record ResolveReference(String reference) implements Request<Resolution> {
}
```

The annotation applies to asynchronous `send(...)` and blocking `sendAndWait(...)` request/response methods. Without
it, blocking waits retain the gateway's standard one-minute timeout while asynchronous requests use normal gateway
request handling. Put the policy on the request type so every sender uses the same expectation; do not scatter longer
blocking waits around call sites to mask a missing handler.

## Timeout and handler execution are separate

For runtime-indexed requests, the SDK records the effective timeout in metadata. A tracked handler may skip the
request when that deadline has already passed:

- `@HandleCommand(skipExpiredRequests = false)` is the default, preserving command execution even after the sender
  stopped waiting;
- `@HandleQuery(skipExpiredRequests = true)` is the default, avoiding work for a stale read result;
- ordinary HTTP web handlers also default to skipping expired indexed requests;
- local requests without a message-log index are not skipped by this mechanism.

Changing `skipExpiredRequests` changes execution semantics. Keep commands enabled unless the product explicitly says
expired intent must be discarded. Set it to `false` on a replay/debug query handler only when processing historical
requests is intentional.

## Diagnose and test the boundary

`FZ-SDK-0002` reports request timeout but does not identify the root cause. Check handler discovery, namespace/topic,
routing/filtering, passive handlers, missing results, and actual duration before increasing the annotation.
`IgnoreMessageEvent` with reason `expiredRequest` proves a tracked handler skipped a stale indexed request; absence of
a response alone does not.

Test these separately:

- a normal result before the deadline;
- no matching/result-producing handler;
- a slow result that exceeds the caller deadline;
- expired command behavior with the default execution policy;
- expired query behavior with the default skip policy;
- the deliberate override used by replay/debug code.

Use fixed/injected time and asynchronous fixture/local-runtime coordination, not a long sleep. Assert both the caller
result/error and observable application effects, because a timed-out command may still commit after the caller stops
waiting.
