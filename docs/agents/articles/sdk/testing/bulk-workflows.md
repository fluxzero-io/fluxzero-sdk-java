Use this when one public command accepts multiple items, especially imports that create or update more than one Model. The test contract must name the consistency and continuation semantics; the word "bulk" does not imply one transaction.

## Choose the consistency boundary first

| Shape | Meaning | Required result/test |
| --- | --- | --- |
| One Model, one transition | The entire batch belongs to one Model and is validated/applied as one state change | Prove invalid input leaves that Model unchanged |
| Cross-Model fail-fast | Child commands run sequentially and dispatch stops on the first failure | Prove the observed visibility of every earlier and later child; do not assume rollback or partial commit |
| Cross-Model continue-and-report | Every item is attempted and the caller receives a correlated success/failure outcome per item | Prove failures do not stop later valid items and no outcome is lost |
| Atomic multi-Model action | One Model operation includes every update and its read dependencies | Prove no target becomes visible after any early or late failure at the real deployment boundary |

Fail-fast stops after an error; best effort continues and reports each item. Neither loop shape creates atomicity.
An explicit `Fluxzero.assertAndApply` or independent `Graph.assertAndApply` call completes its own durable commit;
a later failure cannot undo that commit. Separately dispatched commands also have their own completion boundaries.

For all-or-nothing changes across Models, submit one composite action whose applies or interceptor expansion run
inside one Model operation. Qualify rollback for a late assertion failure across every target. Prevalidation alone
cannot protect against concurrent state changes; use the operation's Model/Graph dependencies and conflict policy.

## Classify failures before continuing

Continue-and-report applies only to failures that the product deliberately defines as item outcomes. Classify them before writing a catch block:

| Failure category | Default behavior |
| --- | --- |
| Named item-specific domain rejection promised by the import contract | Catch that exact application exception and map it to a stable per-item code |
| Authentication or authorization failure for the outer operation | Abort the outer request; do not repeat it as an item rejection |
| Whole-request validation failure | Abort before orchestration; use item outcomes only when item-level rejection is explicitly modeled |
| `TechnicalException`, `GatewayException`, `TimeoutException`, storage/serialization failure, or unexpected programming error | Propagate; do not turn it into an ordinary rejected item |

`FunctionalException` denotes user-facing failures, but it is still too broad as a default catch because validation, unauthenticated, and unauthorized failures also belong to that family. `RuntimeException` and `Exception` are broader still and also hide technical or programming defects. Catch only named failures that the public bulk contract promises to correlate with an item.

```java
try {
    Fluxzero.sendCommandAndWait(draft.toCommand());
    outcomes.add(ImportOutcome.imported(draft.projectId()));
} catch (DuplicateProject expected) {
    outcomes.add(ImportOutcome.rejected(
            draft.projectId(), "duplicate-project"));
}
```

Use a stable application problem code or deliberately public message. Do not expose `error.getMessage()` from an arbitrary runtime or technical exception; it is unstable and may reveal operational details. If an external processor failure is intentionally reportable per item, translate that exact integration failure into an application-owned result at the integration boundary instead of swallowing every runtime failure in the bulk loop.

## One operation versus several explicit commits

`Graph.assertAndApply(Collection<?>)` loops over its inputs. For independent Models each call is durable before the
next starts; the collection is not an all-or-nothing transaction. Use it only when partial progress is intended.

For an atomic bulk action, model one payload with the required Model applies, or use `@InterceptApply` to expand one
payload into its constituent updates inside the same operation. A returned `Message` starts separately routed work;
do not confuse that with payload expansion. Keep external effects behind committed intent in either design.

## Derive a scenario matrix

For every public bulk operation, select the applicable rows and test the outer command rather than only its child command:

| Dimension | Concrete scenarios |
| --- | --- |
| Success | Two valid items; query both targets and assert any returned per-item results |
| Empty input | Accepted no-op or explicit validation failure, according to the product contract |
| Duplicate in request | The same business ID appears twice in one payload |
| Existing state | A later item conflicts with a Model created before this request |
| Position of failure | Invalid first item and invalid item after at least one valid item |
| Authorization | Allowed role, adjacent denied role, and unauthenticated/role-less caller when relevant |
| Observable aftermath | Query every affected ID; assert emitted events, indexed documents, and notifications promised by the product |
| Live consumers | If imports should update open views, open the socket first and prove delivery; use two distinct session IDs for broadcast requirements |

Use distinct IDs for "already stored" and "duplicated inside this request"; they exercise different rules. A test that fails on the first item cannot distinguish rollback from ordinary sequential dispatch.

For an HTTP bulk wrapper, also read request DTO validation and OpenAPI contracts. Test missing and empty outer lists, null items, and independently invalid nested fields at the routed boundary; a valid inner command does not prove the public wrapper or generated nested schema.

## Example: measure fail-fast visibility

Assume `ImportProjects` delegates to one `AddProject` command per ID and is intentionally fail-fast. Seed `existing`, then place that conflict after one valid item and before an item that must never be sent:

```java
fixture.givenCommands(new AddProject(existing, "Existing"))
        .whenCommand(new ImportProjects(List.of(
                new ProjectDraft(first, "First"),
                new ProjectDraft(existing, "Duplicate"),
                new ProjectDraft(notAttempted, "Later"))))
        .expectExceptionalResult(DuplicateProject.class)
        .andThen()
        .whenQuery(new GetProject(first))
        .expectResult(project -> project.name().equals("First"))
        .andThen()
        .whenQuery(new GetProject(existing))
        .expectResult(project -> project.name().equals("Existing"))
        .andThen()
        .whenQuery(new GetProject(notAttempted))
        .expectExceptionalResult(ProjectNotFound.class);
```

The first automatic Model command completed durably before the duplicate was attempted, so its Project remains. The later item was never submitted. Repeat against the deployed dispatch boundary when routing differs from the local fixture.

Add a separate two-valid-item scenario. Without it, a duplicate/failure test does not prove that the outer handler imports more than one item successfully.

For continue-and-report behavior, return an explicit result shape such as:

```java
record ImportOutcome(ProjectId projectId, ImportStatus status, String problem) {
}
```

Catch and represent each named, contract-approved item failure deliberately, use the same item order, expect three correlated outcomes, and query both successful IDs including the valid item after the failure. Merely mapping `sendCommandAndWait` over a stream is fail-fast, not best effort. For one atomic multi-Model action, expect none of the new IDs to be queryable after the late failure. Name tests after the chosen semantics so reviewers do not have to infer them from a loop.

Add a separate failure-classification test: make a child handler throw `TechnicalException`, expect the outer bulk command to fail with that type, and assert that no ordinary per-item rejection hides it. Also prove that an unauthorized caller fails before the loop and that expected item outcomes contain stable public codes rather than raw technical messages.

Finally, assert the public side effects. Query visibility proves state, but it does not prove an `@HandleEvent` projection, generated release note, outgoing request, or WebSocket notification. Drive the import through Fluxzero and observe each product-required boundary separately.
