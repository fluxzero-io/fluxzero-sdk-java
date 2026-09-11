Use this when a Fluxzero app defines `@SocketEndpoint` handlers. For most socket behavior, test with `TestFixture` and websocket-shaped `WebRequest` messages instead of launching a browser.

Register socket endpoint classes by class:

```java
TestFixture fixture = TestFixture.create();
fixture.registerHandlers(NotificationSocket.class);
```

For endpoint state, use Fluxzero's per-session instance lifecycle. A static `@HandleSocketOpen` factory can return a record or class containing that session's `SocketSession`. Fluxzero caches the returned instance by session ID and cleans it up on close. Do not register a singleton endpoint that stores one mutable `SocketSession` field; every later open would replace the previous viewer.

Model socket lifecycle frames as web requests. Include a stable `sessionId` in metadata so message, pong, and close frames target the same open socket instance.

```java
private WebRequest socket(String method, String sessionId, Object payload) {
    return WebRequest.builder()
            .method(method)
            .url("/api/ws/notifications")
            .metadata(Metadata.of("sessionId", sessionId))
            .payload(payload)
            .build();
}
```

Use the SDK websocket method constants from `HttpRequestMethod` when available, such as `WS_OPEN`, `WS_MESSAGE`, `WS_PONG`, `WS_CLOSE`, and `WS_HANDSHAKE`.

Recommended checks:

- Open creates or returns the socket endpoint instance and sends the expected initial snapshot payload when the product promises one.
- Message before open fails when the endpoint requires an open session.
- Message after open returns expected `WebResponse` output or publishes expected events.
- `@Association` routes events only to matching open socket instances.
- Alive checks send ping responses and close timed-out sessions when configured.
- `@RequiresUser` behavior is tested with the same user-provider fixture setup used for HTTP endpoints.

Use the behavior-matrix article for roles and the HTTP authentication boundary article for credential establishment. A user-aware `...ByUser` open proves authorization after `$user` exists; it does not prove that the real handshake cookie or bearer header creates that user. Add a raw authenticated open, then use a separate production-provider or local-proxy scenario to prove an anonymous open yields `UnauthenticatedException` and a close response. `@RequiresUser` alone is neither credential establishment nor a domain-role check.

For a live view, prove two separate sequences. First seed domain state, open the socket as the When step, and assert the complete initial snapshot. Then use a fresh fixture to open the socket as Given state, trigger the domain action as the When step, and assert the outgoing update to the same session:

```java
fixture.registerHandlers(ArticleHandlers.class, KnowledgeSocket.class)
        .givenWebRequest(socket(WS_OPEN, "knowledge-session", null))
        .whenCommand(new PublishArticle(articleId, "New article"))
        .expectWebResponse(response ->
                "knowledge-session".equals(response.getMetadata().get("sessionId"))
                        && response.getPayload() instanceof ArticlePublished);
```

The socket implementation must handle the resulting notification/event and send through its `SocketSession`. Do not replace this assertion with a query for a live-update projection; that checks stored state but not delivery to an already-open client.

An annotation or reflection check that finds `@HandleSocketOpen` is not a snapshot test. Assert the snapshot payload and its seeded items with `expectWebResponse`. Read complete lists and pagination before calling a capped first page the complete/current snapshot.

Use distinct stable session IDs to test more than one subscriber. For broadcast requirements, open `viewer-a` and `viewer-b` as Given requests, trigger one real command, and assert a response carrying each session ID. This test must fail if either open replaces the other. If routing is association-based, open two sessions with different associations and prove only the matching one receives the update.

```java
fixture
        .givenWebRequest(socket(WS_OPEN, "order-session", new OpenNotifications("order-1")))
        .whenWebRequest(socket(WS_MESSAGE, "order-session", "hello"))
        .expectWebResponses("ack");
```

For request/response sockets, assert both sides: the outgoing socket request, the incoming `SocketResponse.success(...)` or `SocketResponse.error(...)`, and timeout behavior by advancing fixture time. Do not sleep in tests.

Use a real local stack only for handshake, browser, proxy, serialization, or infrastructure smoke checks. `TestFixture` models socket lifecycle and delivery but does not open a network WebSocket; describe it as fixture-level socket behavior, not an end-to-end network test.
