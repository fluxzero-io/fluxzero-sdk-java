# External HTTP stubs and consumers

An absolute `@HandleGet` or `@HandlePost` handler in TestFixture is an ordinary WEBREQUEST handler. Its URL
selects a route; it does not classify a handler as a remote service or give it a separate consumer.

If an application endpoint waits for an external HTTP response, give its test stub a separate explicit consumer.
Keep the application's configured consumers unchanged. Otherwise `perPackage` can put a same-package endpoint
and stub on one single-threaded consumer. The synchronous fixture detects that potential production deadlock;
the async fixture really uses tracking and can wait for work the occupied consumer cannot execute.

```java
class AppEndpoint {
    @HandleGet("/app")
    WebResponse handle() {
        return Fluxzero.get().webRequestGateway()
                .sendAndWait(WebRequest.get("https://api.example.com/check").build());
    }
}

@Consumer(name = "external-api-stub")
class ExternalApiStub {
    @HandleGet("https://api.example.com/check")
    WebResponse handle() {
        return WebResponse.builder().status(204).build();
    }
}

@Test
void callsExternalApi() {
    TestFixture.create(new AppEndpoint(), new ExternalApiStub())
            .whenGet("/app")
            .expectWebResult(response -> response.getStatus() == 204)
            .expectNoErrors();
}
```

```kotlin
class AppEndpoint {
    @HandleGet("/app")
    fun handle(): WebResponse = Fluxzero.get().webRequestGateway()
        .sendAndWait(WebRequest.get("https://api.example.com/check").build())
}

@Consumer(name = "external-api-stub")
class ExternalApiStub {
    @HandleGet("https://api.example.com/check")
    fun handle(): WebResponse = WebResponse.builder().status(204).build()
}

@Test
fun callsExternalApi() {
    TestFixture.create(AppEndpoint(), ExternalApiStub())
        .whenGet("/app")
        .expectWebResult { it.status == 204 }
        .expectNoErrors()
}
```

Repeat with `createAsync` when consumer/tracking behavior is relevant. A builder-level
`ConsumerConfiguration` with an exact handler filter is the alternative when the stub cannot be annotated.
Use unique names for different configurations. A same-name conflict reports the registration's handler types
without inspecting their instance values or rerunning custom filters; that list is context, not a claim that
every listed type belongs to both conflicting configurations.

## Defaults and registration

`fluxzero.tracking.unconfiguredHandlerConsumerMode`
(`FLUXZERO_TRACKING_UNCONFIGURED_HANDLER_CONSUMER_MODE`) explicitly selects the fallback; otherwise
`fluxzero.defaults.version` chooses it:

| Defaults version | Fallback for unconfigured handlers |
| --- | --- |
| Missing or before 2026.05.20 | `defaultAppConsumer`: shared application consumer |
| 2026.05.20 through 2026.07.26 | `perHandler`: one per handler class |
| 2026.07.27 or newer | `perPackage`: one per exact package and message type |

Explicit `@Consumer` and matching custom configurations retain their normal precedence. Do not change only
fixture defaults to hide a topology problem. Two handlers sharing a consumer must use compatible identical
configuration; additional handlers can be registered after tracking starts, but that does not rewind its position.
Registration is not a live reconfiguration API. Register stubs before sending requests that need them.

Namespaces and authorization/interceptors still apply to stubs. Configure the stub in the namespace used by the
outgoing gateway and test both allowed and denied callers. `...ByUser` supplies caller identity; it does not
prove real cookie/bearer authentication. Keep raw credential-establishment tests separate.
Configure a UserProvider that can resolve the supplied test identity; `addUser` metadata alone does not authenticate
a real remote HTTP server. Use that server's credential contract. In async HTTP tests a rejected downstream stub
can return an error WebResponse to the calling endpoint; the sync fixture may surface its exception directly.
Assert the boundary actually under test rather than assuming every downstream rejection fails the outer handler.

## What this test does not simulate

The fixture routes native-HTTP settings to registered handlers too. It preserves the retry count/status policy
without real retry delays; it does not test sockets, TLS, proxy behavior or remote transport envelopes.
`sendAndWait` only changes how the caller waits. It does not make a real request local. Use transport tests
for source/request-ID correlation and real HTTP tests for headers, body mapping and network failures.
