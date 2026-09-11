# Handler Dispatch And Parameters

The following table summarizes how handlers are categorized and configured:

| Type                            | Pattern                                 | Configuration                                       |
|:--------------------------------|:----------------------------------------|:----------------------------------------------------|
| **Tracking** (Async/Persistent) | **Standalone**: `@Component`            | Use `@Consumer` to configure threads, retries, etc. |
|                                 | **Self-Handling**: `@TrackSelf`         | Isolated via `@Consumer`.                           |
|                                 | **Stateful**: `@Stateful`               | For sagas and long-running processes.               |
| **Local** (Sync/In-thread)      | **Standalone**: `@LocalHandler`         | Handled in the publication thread.                  |
|                                 | **Self-Handling**: Plain `@HandleQuery` | Optionally add `@LocalHandler` for settings.        |

## Parameters

Handlers can inject various context parameters:

- **Payload**: The message object itself.
- **Sender**: The user/system that sent the message. User context MUST be injected via `Sender`; command/query payloads
  MUST NOT contain user IDs.
- **Metadata**: Key-value pairs attached to the message.
- **Instant**: The message timestamp.
- **Graph<T> or T for an `@Model`**: Every handler kind can load a directly addressed model from the message payload
  or metadata. `@HandleEvent` and `@HandleNotification` use the exact persisted model-commit boundary; other handlers
  use the current repository context.
- **Ancestor model parameters**: Once a descendant is addressed, its parent, grandparent, or further ancestor can be
  injected without repeating ancestor IDs. Use parameter-level `@Association("pathOrIdProperty")` to select another
  payload/metadata ID or to qualify an ambiguous ancestor path; `excludeMetadata = true` limits lookup to payload and
  graph state.
- **Graph<T> for context or optional state**: Use `Graph<T>` when the model may not exist or code needs parents,
  descendants, history or update operations. Resolution is lazy: relationship state is read only when traversed.
- **WebRequest / WebResponse / Schedule**: These extend `Message` and can be injected directly into handler methods when
  transport/scheduling metadata is needed.
- **@Autowired**: Standard Spring beans.

---

<a name="payloads"></a>
