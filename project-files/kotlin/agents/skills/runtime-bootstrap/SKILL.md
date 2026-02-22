---
name: runtime-bootstrap
description: Configure Fluxzero runtime integration via FluxzeroBuilder, client setup, namespace/application settings, consumers, interceptors, and parameter resolvers. Use when bootstrapping or changing runtime configuration.
---

# Runtime Bootstrap

Set up runtime behavior with builder-level configuration.

## Workflow

1. Select local or websocket client mode and core properties.
2. Configure application identity and namespace defaults.
3. Apply consumer configuration (annotation and builder-level) intentionally.
4. Add interceptors/resolvers only for explicit cross-cutting needs.
5. Validate configuration behavior with focused tests.

## Guardrails

- Prefer defaults unless behavior requires explicit tuning.
- If multiple builder consumer predicates match, first-registered config wins.
- Keep namespace and consumer overrides explicit to avoid hidden coupling.

## References

- ../../rules/configuration.md
- ../../rules/runtime-interaction.md
- ../../rules/tracking.md
