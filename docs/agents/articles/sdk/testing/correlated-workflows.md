Build a symmetric scenario matrix for workflows with two independently correlated components. A happy path plus one failure is not enough: code can accidentally route both message types through one reference, compensate only one component, or resend an old compensation on an unrelated later event.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

## Core matrix

| Dimension | Required rows |
| --- | --- |
| Confirmation order | A then B; B then A |
| Rejection timing | A rejects while B pending/confirmed; B rejects while A pending/confirmed |
| Terminal trigger | rejection; expiry; cancellation |
| Late first decision | each component confirms after terminal; each rejects after terminal |
| Repeated decision | duplicate same decision; conflicting second decision for each component |
| Correlation | two workflows with interleaved A/B messages; unknown; root ID; wrong component; fully-prefixed primary-ID collision |
| Ordering | concurrent/opposite A and B decisions enter one primary-ID transition consumer; no lost cancellation or compensation intent |
| Synthetic reconstruction | primary lookup, each correlation, and active deadline in a new default fixture seeded with recorded artifacts |

For each row, assert the component statuses and stable terminal status, not just one top-level enum.

## Logical-once compensation matrix

Every terminal row should assert exactly which new compensation request appears. Include this regression explicitly:

1. component B is confirmed;
2. cancellation, expiry, or component A rejection makes the workflow terminal and requests B compensation;
3. a later first rejection for A is recorded;
4. no second B compensation is published.

Repeat the mirror row with A and B exchanged. Add late confirmation after terminal and deliver it twice; the first creates one compensation request and the duplicate creates none.

## Correlation isolation

Start at least two workflows, retain all generated references, and interleave messages so neither workflow completes in a simple contiguous block. Assert each message changes only the intended component on the intended workflow.

Negative controls should include:

- an unknown secondary reference creates no state and no effect;
- the primary workflow ID presented as a secondary reference does not route;
- an A message carrying B's raw reference does not route;
- equal raw strings in distinct prefixed alias namespaces remain component-specific;
- workflow A has reference `abc` while workflow B's unrestricted public ID is exactly `caption-ref-abc`; B's internal root key remains `asset-job-id-caption-ref-abc`, A's decision still resolves through alias `caption-ref-abc`, and neither a wrong mutation nor a dropped valid decision occurs; repeat for B's artwork alias family;
- a duplicate start returns or preserves the first references and creates no new request/deadline.

Alias-prefix-only coverage is insufficient. Give the typed primary ID a disjoint internal repository prefix, then retain the persisted-field equality guard for every resolver. Drive A and B decisions in both opposite orders through their tracked resolver paths and assert that the resulting primary-ID internal commands expose that same internal typed key as `@RoutingKey` and produce every required derived cancellation/compensation intent exactly once. If the implementation uses separate transition consumers, the test has exposed an ordering design defect rather than something to paper over with timing sleeps.

## Deadlines and reconstruction

Pin start time. Assert pending immediately before the deadline and expired at the exact deadline. Confirm/fail/cancel before the deadline and prove cancellation of the active schedule; then deliver a stale deadline and assert no change.

Finally create a new default fixture from recorded aggregate events or a stateful document plus supplied schedules.
Verify primary lookup, every alias/association independently, deadline behavior, and absence of setup side effects. This
is synthetic reconstruction because the fixture uses new in-memory stores and the test supplies every artifact. Read
reconstruction testing for the correct Given APIs and the separate persistence-backed restart boundary.

Keep outbound assertions exact: URL, POST method, JSON content type, typed body, component reference, and count. This matrix protects both domain state and the integration contract.

Use the general behavior matrix to enumerate every message/reference-type pairing and mirror each terminal path. Do not stop after one late-caption or artwork-confirmed expiry example; symmetric omissions are where wrong-alias and lost-intent defects survive.
