# Fluxzero AI Guidelines

**CRITICAL: Start by reading [Guidelines](rules/guidelines.md).**

These files are the **absolute source of truth** for this project. The Guidelines manual contains a 
**Task Decision Tree** to help you find specific implementation details in the other manuals as you need them.

## Recommended Reading Strategy

1. **[Guidelines](rules/guidelines.md)**: **Read this first.** It covers the philosophy, core principles, and how to use
   the rest of the documentation.
2. **[Glossary](rules/glossary.md)**: Consult this to understand key Fluxzero terminology.
3. **Task-Specific Manuals**: Use the Decision Tree in the Guidelines to jump to the relevant manual for your current
   task.

### Complete Manual List (Reference)

Check the folder `{project-root}/project-files/kotlin/agents/rules` for:

- **[Handling](rules/handling.md)**: Incoming messages (Commands, Queries, Events, Web).
- **[Sending](rules/sending.md)**: Dispatching messages and scheduling.
- **[Entities](rules/entities.md)**: Aggregates, state transitions, and business invariants.
- **[Sagas](rules/sagas.md)**: Stateful handlers and long-running workflows.
- **[Tracking](rules/tracking.md)**: Async consumption, replays, and reliability.
- **[Search](rules/search.md)**: Document store and search indexing.
- **[Testing](rules/testing.md)**: Verification using `TestFixture`.
- **[Validation](rules/validation.md)**: Security, authorization, and payload validation.
- **[Serialization](rules/serialization.md)**: Versioning and upcasting.
- **[Configuration](rules/configuration.md)**: SDK and application setup.
- **[Troubleshooting](rules/troubleshooting.md)**: Resolving common issues and errors.
- **[Imports](rules/fluxzero-fqns-grouped.md)**: Mandatory Kotlin FQNs for all SDK components.