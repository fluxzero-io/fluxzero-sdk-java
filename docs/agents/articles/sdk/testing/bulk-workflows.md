Use this when one public command accepts multiple items, especially imports that create or update more than one aggregate. The test contract must name the consistency and continuation semantics; the word "bulk" does not imply one transaction.

## Choose the consistency boundary first

| Shape | Meaning | Required result/test |
| --- | --- | --- |
| One aggregate, one transition | The entire batch belongs to one aggregate and is validated/applied as one state change | Prove invalid input leaves that aggregate unchanged |
| Cross-aggregate fail-fast | Child commands run sequentially and dispatch stops on the first failure | Prove the observed visibility of every earlier and later child; do not assume rollback or partial commit |
| Cross-aggregate continue-and-report | Every item is attempted and the caller receives a correlated success/failure outcome per item | Prove failures do not stop later valid items and no outcome is lost |
| Staged all-or-nothing | A deliberately modeled staging/commit workflow hides all changes unless every item can commit | Prove no target becomes visible after any early or late failure at the real deployment boundary |

Fail-fast is not best effort: fail-fast stops after an error, while best effort continues and reports every item. Fail-fast also does not by itself define transaction visibility. A nested child handled locally can share the outer invocation's completion and roll back with an outer failure. A child dispatched across an application/runtime boundary is a separate request and may have committed before a later item fails. Aggregate commit policy and dispatch guarantees matter too.

Therefore never promise either rollback or partial progress from the loop shape alone. State the required contract, test the visibility of every target, and add an integration scenario at the deployed boundary when production dispatch differs from the fixture's local-handler path. Prevalidating malformed items and duplicate IDs is useful, but it does not prove later child dispatches are atomic: stored state, authorization, legality, or a race can still reject a later item.

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

## Keep true single-aggregate batches inside one aggregate

When every update genuinely targets the same loaded aggregate or one of its members, use the collection overload instead of dispatching cross-aggregate child commands:

```java
@HandleCommand
Project handle() {
    return Fluxzero.loadAggregate(projectId())
            .assertAndApply(updates())
            .get();
}
```

`assertAndApply(Collection<?>)` evaluates the updates sequentially, so a later update sees earlier state. If any update is illegal, Fluxzero rolls that entity back for the failing invocation. Do not use this shape when the items actually own different aggregate IDs.

## Derive a scenario matrix

For every public bulk operation, select the applicable rows and test the outer command rather than only its child command:

| Dimension | Concrete scenarios |
| --- | --- |
| Success | Two valid items; query both targets and assert any returned per-item results |
| Empty input | Accepted no-op or explicit validation failure, according to the product contract |
| Duplicate in request | The same business ID appears twice in one payload |
| Existing state | A later item conflicts with an aggregate created before this request |
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
        .expectExceptionalResult(ProjectNotFound.class)
        .andThen()
        .whenQuery(new GetProject(existing))
        .expectResult(project -> project.name().equals("Existing"))
        .andThen()
        .whenQuery(new GetProject(notAttempted))
        .expectExceptionalResult(ProjectNotFound.class);
```

The `first` expectation above measures the local nested-handler path: the outer failure can roll back that first child even though its handler returned successfully. If the required contract is partial progress across an external application or namespace, repeat the scenario as an integration test at that boundary and expect `first` to remain visible there. Do not relabel one result as the other behavior.

Add a separate two-valid-item scenario. Without it, a duplicate/failure test does not prove that the outer handler imports more than one item successfully.

For continue-and-report behavior, return an explicit result shape such as:

```java
record ImportOutcome(ProjectId projectId, ImportStatus status, String problem) {
}
```

Catch and represent each named, contract-approved item failure deliberately, use the same item order, expect three correlated outcomes, and query both successful IDs including the valid item after the failure. Merely mapping `sendCommandAndWait` over a stream is fail-fast, not best effort. For staged all-or-nothing behavior, expect none of the new IDs to be queryable after the late failure. Name tests after the chosen semantics so reviewers do not have to infer them from a loop.

Add a separate failure-classification test: make a child handler throw `TechnicalException`, expect the outer bulk command to fail with that type, and assert that no ordinary per-item rejection hides it. Also prove that an unauthorized caller fails before the loop and that expected item outcomes contain stable public codes rather than raw technical messages.

Finally, assert the public side effects. Query visibility proves state, but it does not prove an `@HandleEvent` projection, generated release note, outgoing request, or WebSocket notification. Drive the import through Fluxzero and observe each product-required boundary separately.
