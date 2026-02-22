---
name: search-readmodels
description: Implement Fluxzero document and search read models with correct indexing, filtering, sorting, projections, and consistency-window handling. Use when adding queries, documents, or search-based views.
---

# Search And Read Models

Design read models around Fluxzero search primitives.

## Workflow

1. Define searchable document/aggregate fields and annotations.
2. Implement filtering and sorting in `Fluxzero.search(...)`.
3. Use projections (`exclude`/`includeOnly`) for efficient read shaping.
4. Consider consistency window for post-command reads.
5. Add tests for constraints, ordering, and expected projection output.

## Guardrails

- Do not re-implement filtering/sorting in client app code.
- Avoid assumptions of immediate search consistency after write handling.
- Keep read model updates replay-safe.

## References

- ../../rules/search.md
- ../../rules/handling.md
- ../../rules/testing.md
- ../../rules/tracking.md
