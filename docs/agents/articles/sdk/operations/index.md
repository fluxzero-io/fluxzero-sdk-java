Use these articles when an agent must inspect, recover, or deliberately change a running Fluxzero application's
durable state. These are application operations executed through the SDK inside the configured namespace; they are
not PostgreSQL, Kubernetes, cluster, or Fluxzero platform administration.

## Operational boundary

An application connection can publish, track, search, schedule, and maintain data in its namespace. A namespace is a
data-isolation boundary, not an application-level authorization decision. Before an agent performs a destructive or
replay operation, it must identify the environment and namespace, obtain explicit approval for the concrete action,
record the target identifiers and current state, and define a success check.

Prefer the highest-level SDK API that represents the complete intent:

- use `AggregateRepository.deleteAggregate(...)` instead of deleting only an event stream;
- use a new `@Consumer` name for a new projection instead of resetting an existing consumer;
- use `DocumentStore` and `Search` operations instead of constructing search protocol commands;
- use `Client` subsystem access only when no application-level facade expresses the operation.

Wait for returned futures with an appropriate guarantee before reporting success. An acknowledgement proves that the
runtime accepted or stored the operation; verify the resulting application state separately. Never manipulate the
managed database or runtime deployment to approximate an SDK operation.

## Safety classification

| Class | Examples | Default agent behavior |
| --- | --- | --- |
| Observe | read metrics, inspect a position, read a bounded log range | Safe when credentials and output handling are appropriate |
| Recover | disconnect a tracker, repair relationships, replay into a new consumer | Explain effect and verify after execution |
| Destructive | reset a live consumer, delete an aggregate or collection, truncate a custom log | Require explicit approval, preserve before-state evidence, and check for dependent effects |

Keep operational code out of aggregate `@Apply` methods and ordinary request handlers. Put repeatable maintenance in a
dedicated operator command, controlled job, or one-off tool with explicit authorization and audit output.
