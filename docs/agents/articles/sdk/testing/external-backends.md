Test outbound integrations through the Fluxzero web gateway boundary. Choose between observing a one-way request and
registering an in-fixture remote handler according to whether production awaits the HTTP response.

## One-way publication

For `WebRequestGateway.sendAndForget(...)`, assert the exact published request without contacting a server:

```java
fixture.whenCommand(trigger)
        .expectOnlyWebRequests(request ->
                request.getMethod().equals("POST")
                && request.getPath().equals("https://remote.test/actions")
                && "application/json".equals(request.getContentType())
                && request.<ActionEnvelope>getPayloadAs(ActionEnvelope.class)
                        .equals(expectedEnvelope));
```

Assert URL, method, headers/content type, correlation/idempotency reference, and exact typed body. Do not assert only
that some web request occurred.

## Awaited response without a real network

When application code deliberately uses `sendAndWait`, register a handler for the absolute remote URL:

```java
final class RemoteBackend {
    @HandlePost("https://remote.test/checks")
    WebResponse check(CheckRequest request) {
        return WebResponse.ok(new CheckResponse("accepted"));
    }
}

TestFixture.create(new IntegrationHandler(), new RemoteBackend())
        .whenCommand(new StartCheck("check-42"))
        .expectOnlyEvents(CheckAccepted.class);
```

This exercises Fluxzero request serialization, URL routing, handler invocation, response mapping, and the integration
handler without network access. It is still a fixture integration test, not proof of DNS, TLS, proxy credentials, or a
third party's deployed contract.

Add independent remote rows for non-2xx responses, malformed payloads, timeout/no response, and a retryable technical
failure when the product promises recovery. Do not turn every remote failure into a business rejection; preserve
technical failures unless the application contract explicitly maps them.

## Registration and interception hazards

Register the same production integration handler instance/class that the application discovers. A fixture-only mock
must not be a Spring production bean. Interceptors loaded by Java `ServiceLoader` are active in `TestFixture` too; if a
global interceptor rewrites or blocks remote requests, make that dependency explicit in the test or isolate it from the
test classpath.
