# Diagnose Model relationships

Model values, `@Parent` edges and aliases are committed together. Diagnose a missing relationship by establishing
which identity, namespace and state boundary the reader uses before attempting a repair.

1. Load the typed primary Model ID and inspect its current parent and alias fields.
2. Load the parent Graph at the same boundary and inspect the expected relationship path and child type.
3. Compare with a deliberately current Graph if the original read was event-bound or already pinned.
4. Check alias prefixes, parent-scoped identity, registered Model contracts and whether a pathless relation was
   incorrectly expected to appear in composed JSON.
5. Distinguish authoritative Graph state from a materialized projection still catching up.

Changing a parent field in a normal Model transition changes the relationship atomically. A projection rebuild repairs
an out-of-date read view; it does not rewrite authoritative relationships. Changing the meaning of stored identity or
parent annotations requires a deliberate data evolution plan, not a manual reverse-index edit.

There is no general ModelRepository `repairRelationships(...)` operation. If durable values and authoritative edges
still disagree after reproducing with current state, retain the minimal non-sensitive evidence and investigate the
writer, schema interpretation and runtime contract before modifying data. Do not substitute another persistence API
or fabricate a protocol-level repair call.
