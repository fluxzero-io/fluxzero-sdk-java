# Invariants across Models

A query followed by a write does not protect a business invariant against concurrent writers. Express the state and
relationships needed for a decision as dependencies of one Model operation.

For example, a registration that claims a unique serial can use that serial as the identity of an independently
stored claim Model. Create the claim and update the inventory in one atomic multi-Model action. The claim's factory
must reject an occupied identity, and a conflict must retry or fail rather than silently overwrite the claimant.
On retry, assertions evaluate again against the selected boundary. Choose a permanent business identity with the
same normalization and collision-safe encoding for every claimant.

For a limit over children, inject `Graph<Parent>` and traverse the relevant child scope inside `@AssertLegal`.
Empty collections are read dependencies too. Concurrent additions/removals then participate in commit validation.
Search results, detached historical graphs and unrelated repository reads are not that transactional readset.

## Routing is an optimization, not the invariant

`@RoutingKey` can serialize equal keys within one named consumer and reduce conflicts. It does not protect against
another consumer or application writing the same state. Keep the invariant in the Model commit, including every
claim, release and reassignment path. A single tracker can impose global order but needlessly limits throughput when
per-key identities and atomic dependencies express the rule.

Do not accept a caller-controlled derived key independently from its source fields. Compute it from the source or
validate it in the constructor. Request-level deduplication is not permanent business uniqueness.

## Qualify the whole result

Test first claim, duplicate by the same caller, duplicate by another caller, concurrent contenders, release and retry.
After concurrent requests, exactly one owner must remain; query all candidate IDs and aliases and prove the rejected
request left no Model, document, schedule or external effect. Test multiple different keys as well, to avoid accidental
global serialization. Use deterministic barriers and observed completion, not sleeps.

For composite keys, include inputs containing delimiters and prove the scalar encoding is injective. For remote
effects, use durable intent and a recoverable workflow; the Model invariant cannot atomically control an external API.
