The Fluxzero Java SDK treats commands, queries, events, and web requests as messages. Most application code is plain
Java records or Kotlin data classes, immutable state, and annotated handler methods. Fluxzero supplies the messaging,
event-sourced persistence, search, scheduling, and web/runtime integration around that application code.

Use `@Model` for new state and `@Parent` for independent related Models. Read the Models and Graphs topic before
copying legacy entity examples. SDK v2 requires Java 25+, and standalone Models require a matching v2 Runtime.
