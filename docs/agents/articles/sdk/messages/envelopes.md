# Logical messages and transport envelopes

A `Message` contains payload, metadata, message ID and timestamp. A `SerializedMessage` additionally carries
transport fields such as source, target, request ID and log index. Converting a received wrapper to a logical
Message and sending it again is a new dispatch, not transparent forwarding of the original envelope.

| Route for a command/query | Supplied logical message ID | Transport source |
| --- | --- | --- |
| Serialize, edit envelope, then deserialize | Preserved | Preserved on that serialized wrapper |
| Local in-process handling / synchronous TestFixture | Preserved unless deliberately replaced | No transport source |
| Tracked request via LocalClient / asynchronous TestFixture | Preserved unless deliberately replaced | Sending client's ID |
| Tracked request across WebSocket clients | Preserved unless deliberately replaced | Sending client's ID |

These rows apply equally to future-returning and blocking calls. `queryAndWait` and `sendCommandAndWait` can
wait for a remote consumer; `send` can complete locally. Async TestFixture can also contain explicitly local
handlers, so fixture mode alone is not proof that a specific message crossed transport.

Request transport assigns source for response correlation. Do not depend on a manually supplied source surviving
redispatch, and do not treat source or a caller-selected message ID as authenticated identity. Use application
metadata for a deliberately forwarded correlation value and preserve the appropriate trusted user context.

## Replacements and interceptors

For a logical identity change, return a replacement rather than mutating a cached serialized representation:

```java
Message replacement = incoming.toMessage().withMessageId("new-logical-id");
DeserializingMessage view = incoming.withMessage(replacement);
Fluxzero.sendCommand(view);
```

```kotlin
val replacement = incoming.toMessage().withMessageId("new-logical-id")
val view = incoming.withMessage(replacement)
Fluxzero.sendCommand(view)
```

For an edited serialized envelope, finish edits before calling `serializer.deserializeMessage(...)`.
Treat input as read-only afterwards: a `DeserializingMessage` lazily caches its logical Message, and later
serialized mutations do not synchronize that existing value. Deserialize again after deliberate envelope editing.
Do not mutate a submitted message while dispatch/serialization may still be using it.

`DispatchInterceptor.interceptDispatch` returns logical replacements for local and external dispatch.
`modifySerializedMessage` concerns the serialized publication path and is not a general local-handler hook;
request transport can still assign its own correlation fields afterwards. A metadata/payload-only replacement
should retain the existing identity unless a genuinely distinct message is intended.

Absolute HTTP stubs in TestFixture run normal handlers, not a real native HTTP client or proxy. They are unsuitable
for proving a remote source/target envelope. Direct native HTTP carries HTTP headers and bodies, not a Fluxzero
command/query envelope; mapping extra metadata into HTTP must be explicit.
