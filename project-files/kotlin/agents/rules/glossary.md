# Glossary

Key terms and definitions used within the Fluxzero SDK and ecosystem.

---

### Model

An independently persisted immutable domain object whose boundary is defined by its own creation, changes, history,
retention or deletion lifecycle. Each `@Model` has an `@EntityId` and modelstream, plus its configured cache, snapshot
and current-document consequences. A meaningful domain identity is strong evidence for the boundary, while a
child-only identity can be parent-scoped. Related independent models form dynamic action boundaries and a temporal
graph through `@Parent`.

### Graph

A typed, lazy view around one Model and its temporal `@Parent` relationships. `Graph<T>` exposes current or historical
state, parents, descendants, functional and repository identity, previous revisions and staged transitions without
turning those Models into one persistence boundary. As the sole parameter of an event or notification handler, it is
also a subscription to durable changes anywhere below that root.

### Aggregate (legacy)

The Fluxzero 1.x shared-root persistence API. Keep it for existing persisted state only. New code uses `@Model`;
`@Model` with `@Member` covers the intentional shared-stream case.

### Apply (@Apply)

The mechanism for evolving model state. An `@Apply` method is a pure function that takes the current state
and a payload (event), and returns the new state.

### AssertLegal (@AssertLegal)

A mechanism for enforcing business invariants. These methods are executed before a command is applied. If an invariant
is violated, an exception is thrown and the command is rejected.

### InterceptApply (@InterceptApply)

A pre-processing hook that runs before `@AssertLegal` and `@Apply`. It can suppress an update (`null`/`Unit`), keep it
as-is (`this`), rewrite it (return a different payload), or expand it (return `Collection`/`Stream`/`Optional`).
Interceptors are applied recursively until they no longer transform the update.

### Command

A message that expresses an intent to change the state of the system (e.g., `CreateOrder`). Commands are imperative and
may return a result.

### Consumer

A named component that tracks and processes a stream of messages from the Fluxzero Runtime. Consumers can be configured
with multiple threads, retry policies, and error handlers.

### Consistent Hashing

The technique Fluxzero uses to distribute messages across segments. It ensures that messages with the same **Routing Key
** are always processed by the same consumer instance in the same order.

### Event

A message that represents a fact that has occurred in the past (e.g., `OrderCreated`). Events are typically the result
of applying a command to one or more models.

### Event Sourcing

A persistence strategy where model state is reconstructed from its modelstream events rather than loaded only from a
current document.
The current state is reconstructed by replaying these events.

### Fluxzero Runtime

The central message hub and persistence engine that coordinates communication between Fluxzero SDK applications. It
handles message routing, event storage, search indexing, and scheduling.

### Gateway

An entry point for sending messages into the Fluxzero ecosystem. There are specialized gateways for Commands, Queries,
Events, Errors, and Metrics.

### Handler

A method annotated with `@HandleCommand`, `@HandleQuery`, `@HandleEvent`, etc., that contains the logic to process a
specific type of message.

### Local Handler (@LocalHandler)

A handler that executes synchronously in the same thread as the message publication. This is common for Queries.

### Message

The fundamental unit of communication in Fluxzero. Every interaction is a message consisting of a **Payload** (the
domain data) and **Metadata** (contextual information). A Message also includes a `messageId` and `timestamp`.

### Metadata

A map of key-value pairs attached to a message envelope, containing contextual information like the sender, correlation
IDs, or security tokens.

### Namespace

A logical grouping or tenant identifier (configured via `FLUXZERO_NAMESPACE`). It ensures that messages and data from
different projects or environments remain isolated.

### Payload

The actual domain object or data carried by a message (e.g., a `CreateUser` data class).

### Query

A message that represents a request for information (e.g., `GetOrderDetails`). Queries are read-only and always return a
result.

### Routing Key

A value (often an ID) used to determine which **Segment** a message belongs to. Fluxzero ensures that messages with the
same routing key are processed sequentially.

### Saga (@Stateful)

A long-running business process or workflow that maintains its own state across multiple messages and time.

### Segment

A partition of the message stream. Fluxzero uses segments to scale processing horizontally. Each segment is processed by
exactly one active tracker instance at a time.

### Tracking

The process of asynchronously consuming messages from the Fluxzero Runtime. A tracker keeps track of its position (
index) in the message stream to ensure each message is processed exactly once.

### SerializedMessage

When a message is sent to the remote Fluxzero runtime, it is transformed into a **SerializedMessage**, which adds
infrastructure-level details:

- **Message Index**: A unique, sequential `long` assigned by the runtime.
- **Routing Segment**: An `int` used for distribution and ordering. Fluxzero uses **consistent hashing** to assign
  segments.
- **Routing Key**: A value extracted from a payload property (marked with `@RoutingKey`) or metadata, used to calculate
  the segment. This ensures that related messages (e.g., for the same Order ID) are always processed in the correct
  order by the same handler instance.
- **Source & Target**: Refers to the unique `clientId` of the app instance that sent the request. `Source` for requests
  and `Target` for responses.

### Upcasting

The process of transforming old versions of serialized data into the current version. This enables schema evolution
without breaking existing data.
