Fluxzero messages are the public vocabulary of an app.

- Command: an intent to change state. Use `@HandleCommand`.
- Query: a read-only request with a typed return. Implement `Request<T>` and use `@HandleQuery`.
- Event: a fact that other handlers can react to asynchronously. Use `@HandleEvent`.
- Web request: an HTTP/websocket message adapted by `@HandleGet`, `@HandlePost`, or related annotations.

Commands and queries can be self-handling on the payload or handled by a Spring component. Web methods should stay thin and delegate to commands or queries when domain behavior is involved.

Runtime delivery details to keep in mind:

- Message logs assign stable increasing indexes when messages are appended; consumers resume after the last processed index.
- WebSocket commands with guarantee `STORED` or stronger are request-level idempotent by `(clientId, requestId)`. Completed duplicates replay the cached result and duplicates already in progress are not re-executed. `SENT` commands are not cached this way.
- Scheduled messages are delivered only when due. Reusing the same schedule ID overwrites the previous deadline unless the caller uses an if-absent schedule mode.
