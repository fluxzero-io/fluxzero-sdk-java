Use this before implementing tests from a product brief. Convert every material requirement into an observable boundary,
then choose the smallest scenario that would fail if that requirement were removed. A passing full suite is not evidence
for a row that no test can independently falsify.

## Map requirements to evidence

Keep the matrix near the tests while implementing. Replace the generic rows with the actual public contract, but retain
the distinction between evidence types:

| Requirement kind | Direct evidence | Common false positive |
| --- | --- | --- |
| Payload field validation | One invalid field with every control field valid; assert exception type and property path | One payload violates several constraints, so any one validator can make it pass |
| Authorization | Exact-role allow, inherited-role allow, adjacent-role deny, roleless deny, and anonymous deny where distinct | A constructed user bypasses the credential or provider boundary the product relies on |
| State transition | Success from every allowed source state and rejection from every material disallowed state | One terminal state is assumed to represent every other terminal state |
| Rejected command | Expected error plus unchanged public state and no new event, message, metric, schedule, or outbound request | The exception is asserted but partial effects are never inspected |
| Duplicate or stale delivery | Repeat the same delivery and deliver it after each relevant terminal path | One stale path is treated as proof for symmetric paths |
| Search field | A term that matches only that field, stable ordering including a tie, and the promised paging/count contract | Membership-only assertions hide in-memory filtering, unstable ties, or a silent cap |
| Projection write | Stable document identity, replacement on redelivery, public query mapping, and an injected storage failure | Calling `indexAndWait()` is treated as proof that consumer position cannot advance |
| Tracked failure | Selected error policy, surfaced failure, exact effect-attempt count, and tracker position around the failing index | Directly calling `ErrorHandler.handleError(...)` bypasses tracking |
| HTTP or socket operation | Routed request/frame with real binding, identity, response mapping, and a critical negative row | Directly invoking the endpoint method proves only ordinary Java behavior |
| Generated contract | Request the served document and inspect exact paths, operations, required fields, item schemas, and responses | Reflection over annotations or broad substring checks |
| Synthetic reconstruction | New default fixture seeded only with recorded durable inputs; prove lookup, correlations, active schedules, and no setup effects | Calling this a persistence restart even though the test supplied every durable artifact |
| Persistence restart | Store/runtime survives application instance A; instance B reconnects without manual reseeding and proves restored behavior | Cache eviction, `.andThen()`, or a new in-memory fixture |
| Concurrent invariant | Competing routed submissions, named winner/loser outcomes, lookup of every candidate ID/alias, and proof that only the winner's schedules/documents/outbound effects remain | Two sequential duplicate calls, a timing sleep, or one global count that does not identify the rejected owner |
| Derived routing identity | One trusted construction path, or adversarial mismatch rejection before routing, lookup, aliasing, and persistence | Only the HTTP adapter derives a key while the public message accepts an inconsistent key |

## Separate state, effects, and absence

For an accepted action, list every promised observation: returned value, persisted state, event, metric, active schedule,
outbound request, indexed document, and live delivery. Assert only effects the contract requires, but do not let one stand
in for another.

For a rejected action, invert that list. An exception plus `expectNoEvents()` still permits a schedule, document write, or
outbound request. Use the exact empty assertion for each material effect category and query the state afterward. When
the implementation coordinates more than one durable subsystem, add a failure after the earliest completed effect and
prove the documented retry, compensation, or partial-visibility contract.

## Prioritize without building a blind Cartesian product

Start with rows whose failure would corrupt state, skip durable work, violate authorization, expose the wrong public
contract, or create duplicate external effects. Then cover independent validation and transition branches. Combine rows
only when the same mutation would break them for the same reason; otherwise keep them separate so a failure identifies
one missing guarantee.

Use test names that name the boundary and outcome. After implementation, perform a mutation review: mentally remove each
validation annotation, routing key, source-state branch, cancellation, error policy, sort tie-break, and response mapping.
Every product-critical removal should have one obvious failing test. If it does not, the matrix still contains an
unproven row.
