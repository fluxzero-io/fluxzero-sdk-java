# Glossary

Key terms and definitions used within the Fluxzero SDK and ecosystem.

---

### Aggregate
A cluster of domain objects (Entities and Value Objects) that can be treated as a single unit for data changes. Every aggregate has a root entity, identified by an `@EntityId`. Aggregates are typically "dumb" immutable state holders in Fluxzero.

### Apply (@Apply)
The mechanism for evolving the state of an Aggregate. An `@Apply` method is a pure function that takes the current state and a payload (event), and returns the new state.

### AssertLegal (@AssertLegal)
A mechanism for enforcing business invariants. These methods are executed before a command is applied. If an invariant is violated, an exception is thrown and the command is rejected.

### Command
A message that expresses an intent to change the state of the system (e.g., `CreateOrder`). Commands are imperative and may return a result.

### Consumer
A named component that tracks and processes a stream of messages from the Fluxzero Runtime. Consumers can be configured with multiple threads, retry policies, and error handlers.

### Consistent Hashing
The technique Fluxzero uses to distribute messages across segments. It ensures that messages with the same **Routing Key** are always processed by the same consumer instance in the same order.

### Event
A message that represents a fact that has occurred in the past (e.g., `OrderCreated`). Events are typically the result of applying a Command to an Aggregate.

### Event Sourcing
A persistence strategy where the state of an aggregate is not stored as a single snapshot, but as a sequence of events. The current state is reconstructed by replaying these events.

### Fluxzero Runtime
The central message hub and persistence engine that coordinates communication between Fluxzero SDK applications. It handles message routing, event storage, search indexing, and scheduling.

### Gateway
An entry point for sending messages into the Fluxzero ecosystem. There are specialized gateways for Commands, Queries, Events, Errors, and Metrics.

### Handler
A method annotated with `@HandleCommand`, `@HandleQuery`, `@HandleEvent`, etc., that contains the logic to process a specific type of message.

### Local Handler (@LocalHandler)
A handler that executes synchronously in the same thread as the message publication. This is common for Queries.

### Message
The fundamental unit of communication in Fluxzero. Every interaction is a message consisting of a **Payload** (the domain data) and **Metadata** (contextual information).

### Metadata
A map of key-value pairs attached to a message envelope, containing contextual information like the sender, correlation IDs, or security tokens.

### Namespace
A logical grouping or tenant identifier (configured via `FLUXZERO_NAMESPACE`). It ensures that messages and data from different projects or environments remain isolated.

### Payload
The actual domain object or data carried by a message (e.g., a `CreateUser` record).

### Query
A message that represents a request for information (e.g., `GetOrderDetails`). Queries are read-only and always return a result.

### Routing Key
A value (often an ID) used to determine which **Segment** a message belongs to. Fluxzero ensures that messages with the same routing key are processed sequentially.

### Saga (@Stateful)
A long-running business process or workflow that maintains its own state across multiple messages and time.

### Segment
A partition of the message stream. Fluxzero uses segments to scale processing horizontally. Each segment is processed by exactly one active tracker instance at a time.

### Tracking
The process of asynchronously consuming messages from the Fluxzero Runtime. A tracker keeps track of its position (index) in the message stream to ensure each message is processed exactly once.

### Upcasting
The process of transforming old versions of serialized data into the current version. This enables schema evolution without breaking existing data.
