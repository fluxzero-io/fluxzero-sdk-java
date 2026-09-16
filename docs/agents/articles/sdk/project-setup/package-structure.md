Group application code by business domain first. Within each domain, use `api` for commands, queries and typed IDs,
and `api.model` for state and value objects. A domain is a cohesive product area such as `catalog` or `ordering`;
`<domain>` is a placeholder for that name, not a literal application-wide `domain` package. Several related models
may belong to one domain. A package is not a requirement to create a separate service, module or deployment.

## Choose the layout before creating files

For a new application, follow this convention unless the user or repository specifies another structure. Before
adding the first product classes, inspect the generated or existing tree, identify the relevant business domains,
and map the first commands, queries, IDs, models and handlers to concrete package paths. For an existing application,
follow its established conventions and make a deliberate migration only when the task calls for it. This is an
implementation check, not a request for user approval or a new planning document.

| Kind | Package | Example |
| --- | --- | --- |
| Command or query payload | `<root>.<domain>.api` | `com.example.shop.ordering.api.PlaceOrder` |
| Typed ID | `<root>.<domain>.api` | `com.example.shop.ordering.api.OrderId` |
| Model, details, status or other value object | `<root>.<domain>.api.model` | `com.example.shop.ordering.api.model.Order` |
| Separate handler, orchestration or endpoint | `<root>.<domain>` | `com.example.shop.ordering.OrderQueries` |
| Behavior tests | Same domain under the test source root | `com.example.shop.ordering.OrderTest` |
| JSON test resources | Flat files grouped per domain under `src/test/resources` | `ordering/place-order.json` |

Here `api` means the domain's message and model contract; it does not mean HTTP routes. A self-handling command or
query stays in `api` even when it contains an `@Apply`, `@HandleCommand` or `@HandleQuery` method. Do not add a
pass-through handler or a duplicate DTO merely to fill out this tree. Add endpoints only when the product needs them.

## Example tree

This Java example shows two domains so the boundary is visible. Only create the classes needed for the current feature.

```text
src/main/java/com/example/shop/
├── App.java
├── package-info.java
├── catalog/
│   ├── ProductQueries.java
│   └── api/
│       ├── CreateProduct.java
│       ├── GetProduct.java
│       ├── ProductId.java
│       └── model/
│           ├── Product.java
│           └── ProductDetails.java
└── ordering/
    ├── OrderQueries.java
    └── api/
        ├── PlaceOrder.java
        ├── GetOrder.java
        ├── OrderId.java
        └── model/
            ├── Order.java
            └── OrderDetails.java

src/test/java/com/example/shop/
├── catalog/ProductTest.java
└── ordering/OrderTest.java

src/test/resources/
├── catalog/create-product.json
└── ordering/place-order.json
```

Kotlin uses the same package names under `src/main/kotlin` and `src/test/kotlin`, with `.kt` files. Keep any Java
`package-info.java` under `src/main/java` at the matching package path. A later order HTTP adapter would be
`com.example.shop.ordering.OrderEndpoint` (`OrderEndpoint.java` or `OrderEndpoint.kt`).

## Keep domains cohesive

Avoid application-wide `commands`, `queries`, `models`, `domain`, `handlers` or `services` buckets that mix several
business domains. For example, put `PlaceOrder`, `OrderId` and `Order` together under `ordering.api` and
`ordering.api.model`, rather than splitting them across `shop.commands`, `shop.ids` and `shop.domain`.
Additional subpackages inside a large domain are useful when they describe a real responsibility; an owning domain
must remain recognizable. An external integration may form such a responsibility, with its message contracts in its
own `api` package. Package placement alone does not select local versus tracked handling.

## Review before finishing

Inspect the changed production and test paths before committing:

- Each new type has an identifiable owning domain; related types have not drifted into global technical buckets.
- Commands, queries and typed IDs follow that domain's `api` convention; state and values use its `api.model`.
- Existing separate handlers and endpoints remain near their domain; no unnecessary layers were added.
- Tests mirror the owning domains, and package declarations, imports and package-level annotations match the paths.

Apply this check to code you add or change. Do not automatically rename an existing application's persisted payload
classes for cosmetic consistency. Moving a class can affect serialized type names, registration, inherited security,
web routing and consumer discovery. Preserve these contracts with the version's migration/type-alias facilities and
focused behavior or reconstruction tests when a move is explicitly in scope.
