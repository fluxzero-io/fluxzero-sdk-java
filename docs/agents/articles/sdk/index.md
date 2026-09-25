The Fluxzero SDK connects Java and Kotlin application code to Fluxzero, the cloud for AI-built apps. Local development and tests use the same programming model before the application is published on Fluxzero.

The Fluxzero Java SDK treats commands, queries, events, and web requests as messages. Most application code is plain
Java records or Kotlin data classes, immutable state, and annotated handler methods. Fluxzero supplies the messaging,
event-sourced persistence, search, scheduling, and web/runtime integration around that application code.

Use `@Model` for new state and `@Parent` for independent related Models. Read the Models and Graphs topic before
choosing storage settings. The SDK requires Java 25+ and a compatible Runtime.
