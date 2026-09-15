Test outbound integrations through the Fluxzero web gateway boundary. Choose between observing a one-way request and
registering an in-fixture remote handler according to whether production awaits the HTTP response.

## One-way publication

For `WebRequestGateway.sendAndForget(...)`, assert the exact published request without contacting a server:

```java
fixture.whenCommand(trigger)
        .expectOnlyWebRequests(WebRequest.post("https://remote.test/actions")
                .contentType("application/json")
                .body(expectedEnvelope)
                .build());
```

Assert URL, method, headers/content type, correlation/idempotency reference, and exact typed body. Do not assert only
that some web request occurred.

## Awaited response without a real network

Exercise the actual local command/query from the outbound integration example and register only the remote stub.
Do not inject or mock an API-service bean, register the self-handling payload class, or add `@TrackSelf` to make the
fixture discover it. The real handler must build the request and interpret the response:

```java
@Consumer(name = "partner-api-stub")
static class PartnerApiStub {
    @HandleGet("https://partner.example/api/orders/{orderId}")
    WebResponse getOrder(@PathParam("orderId") UUID orderId) {
        return WebResponse.builder().status(200).contentType("application/json")
                .payload(Map.of("orderId", orderId, "status", "accepted"))
                .build();
    }

    @HandlePost("https://partner.example/api/orders")
    WebResponse placeOrder(OrderDetails details) {
        return WebResponse.builder().status(201).contentType("application/json")
                .payload(Map.of("orderId", "00000000-0000-0000-0000-000000000001", "status", "accepted"))
                .build();
    }
}

@Test
void readPartnerOrder() {
    var orderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    TestFixture.create(new PartnerApiStub())
            .withProperty("partner.api.token", "test-token")
            .whenQuery(new GetPartnerOrder(orderId))
            .expectResult(new PartnerOrder(orderId, "accepted"))
            .expectOnlyWebRequests(WebRequest.get("https://partner.example/api/orders/" + orderId)
                    .header("Authorization", "Bearer test-token").build());
}

@Test
void placePartnerOrder() {
    var details = new OrderDetails("book", 2);
    TestFixture.create(new PartnerApiStub())
            .withProperty("partner.api.token", "test-token")
            .whenCommand(new PlacePartnerOrder(details))
            .expectResult(new PartnerOrder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "accepted"))
            .expectOnlyWebRequests(WebRequest.post("https://partner.example/api/orders")
                    .header("Authorization", "Bearer test-token")
                    .contentType("application/json").body(details).build());
}
```

```kotlin
@Consumer(name = "partner-api-stub")
class PartnerApiStub {
    @HandleGet("https://partner.example/api/orders/{orderId}")
    fun getOrder(@PathParam("orderId") orderId: UUID): WebResponse =
        WebResponse.builder().status(200).contentType("application/json")
            .payload(mapOf("orderId" to orderId, "status" to "accepted"))
            .build()

    @HandlePost("https://partner.example/api/orders")
    fun placeOrder(details: OrderDetails): WebResponse =
        WebResponse.builder().status(201).contentType("application/json")
            .payload(mapOf("orderId" to "00000000-0000-0000-0000-000000000001", "status" to "accepted"))
            .build()
}

@Test
fun readPartnerOrder() {
    val orderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    TestFixture.create(PartnerApiStub())
        .withProperty("partner.api.token", "test-token")
        .whenQuery(GetPartnerOrder(orderId))
        .expectResult(PartnerOrder(orderId, "accepted"))
        .expectOnlyWebRequests(WebRequest.get("https://partner.example/api/orders/$orderId")
            .header("Authorization", "Bearer test-token").build())
}

@Test
fun placePartnerOrder() {
    val details = OrderDetails("book", 2)
    TestFixture.create(PartnerApiStub())
        .withProperty("partner.api.token", "test-token")
        .whenCommand(PlacePartnerOrder(details))
        .expectResult(PartnerOrder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "accepted"))
        .expectOnlyWebRequests(WebRequest.post("https://partner.example/api/orders")
            .header("Authorization", "Bearer test-token")
            .contentType("application/json").body(details).build())
}
```

The stub's `@Consumer` gives the simulated remote endpoint independent tracked processing, including in asynchronous
fixtures where the caller waits for its response. It belongs to the test endpoint, not to the production local
command/query. Use `TestFixture.createAsync(new PartnerApiStub())` in Java or
`TestFixture.createAsync(PartnerApiStub())` in Kotlin when the scenario needs asynchronous tracking. Local
self-handlers still execute locally in that fixture.

This checks the application's command/query behavior, request construction, URL routing and response mapping without
real network access. It does not prove DNS, TLS, proxy behavior or the third party's deployed contract. Add cases for
non-2xx responses, malformed payloads and relevant timeout/retry outcomes. Preserve unexpected technical failures
unless the application's contract explicitly maps them to an expected business outcome.

## Registration and interception hazards

Ordinary local self-handlers need no registration in production or in the fixture. If the application deliberately
uses a standalone or tracked handler, register that production handler too. A fixture-only remote stub must not be a
Spring production bean. Interceptors loaded by Java `ServiceLoader` are active in `TestFixture` too; if a
global interceptor rewrites or blocks remote requests, make that dependency explicit in the test or isolate it from the
test classpath.
