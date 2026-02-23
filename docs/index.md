---
title: Fluxzero – Docs Index
summary: "Know the task, not the file name? Start here and jump straight to the right guide."
---

> **Tip 📚** — For lightning‑fast answers see the [Quick Reference](quick-ref.md). Everything else lives in the guides
> below.

---

## 🗺️ Task‑oriented map

### Getting started

- **I want to install Fluxzero** → [Installation guide](guides/installation.md)
- **I need a 60‑second cheat sheet** → [Quick Reference](quick-ref.md)
- **I need some code samples** → [Code samples](code-samples.md)

### Core message handling

- **I want to send & handle commands/events/queries** → [Message Handling](guides/message-handling.md)
- **I need parameter injection rules** → [Parameter Injection](guides/parameter-injection-with-custom-resolvers.md)
- **I’m building a long‑running workflow (saga)** → [Stateful & Sagas](guides/stateful-handlers.md)
- **I must schedule or cancel tasks** → [Scheduler & Periodic Tasks](guides/scheduling.md)

### Testing

- **I need fixture‑based unit tests** → [Testing](guides/testing-your-handlers.md)
- **I want end‑to‑end replay tests** → [Message Replays](guides/message-replays.md)

### Data & serialization

- **I need document search / projections** → [Document Search](guides/document-indexing-and-search.md)
- **I’m up‑/down‑casting events** → [Serialization](guides/serialization-upcasting-and-downcasting.md)

### Web & API development

- **I’m exposing REST / WebSocket endpoints** → [Web Gateway](guides/handling-web-requests.md)

### Monitoring & operations

- **I need to inspect the Dead‑Letter Queue** → [Dead‑Letter Queue](guides/dynamic-dead-letter-queue-dlq.md)
- **I’m on‑call and want health metrics** → [Monitoring & Ops](guides/metrics-messages.md)

### API reference

- **I need method signatures** → [Generated Javadoc](api/index.html)

---

## 📑 Alphabetical list of guides

| Guide                                                | Description                         |
|------------------------------------------------------|-------------------------------------|
| [Document Search](guides/document-indexing-and-search.md)         | Indexing & querying aggregates      |
| [Dead‑Letter Queue](guides/dynamic-dead-letter-queue-dlq.md)                   | Recover after failed messages       |
| [Installation](guides/installation.md)               | Add the BOM, configure connection   |
| [Message Handling](guides/message-handling.md)       | Commands, events, queries, routing  |
| [Monitoring & Ops](guides/metrics-messages.md)             | Metrics, tracing, dashboards        |
| [Parameter Injection](guides/parameter-injection-with-custom-resolvers.md) | Auto‑wiring handler arguments       |
| [Quick Reference](quick-ref.md)                      | 60‑sec cheat sheet                  |
| [Scheduler & Periodic Tasks](guides/scheduling.md)    | One‑off & recurring jobs            |
| [Serialization](guides/serialization-upcasting-and-downcasting.md)             | Revisions, up‑ & down‑casting       |
| [Stateful & Sagas](guides/stateful-handlers.md)         | Long‑running workflows              |
| [Testing](guides/testing-your-handlers.md)                         | Fixture DSL & replay tests          |
| [Web Gateway](guides/handling-web-requests.md)                         | REST, WebSocket & GraphQL endpoints |
