Start from an existing Fluxzero project or generate one with the Fluxzero CLI. For a new workspace, follow CLI project generation, select the Java or Kotlin template and Maven or Gradle, then validate the generated build before writing domain code. The starter is not the product: replace its generic behavior, names, routes, and tests with the requested application.

Then use a minimal package layout:

- `...domain.api` for command/query payloads and typed IDs.
- `...domain.api.model` for aggregates, entities, and value objects.
- `...domain` for handlers and endpoints.

Keep the generated Spring Boot main class and package-level registration/security annotations. Use the Fluxzero dev
server for the local runtime instead of adding a test-classpath bootstrap. Then create one aggregate, one typed ID, one
command, and one behavior test. Do not start by creating HTTP endpoints.

Keep the first slice small: create a thing, load or search it, and verify the behavior with `TestFixture`. Add authentication only if the user needs accounts, ownership, permissions, teams, or protected demo endpoints.

Keep the finished surface equally focused. Implement only product-requested commands, queries, endpoints, roles, and reusable abstractions. Do not add speculative role mutation, admin operations, custom wrapper annotations used once, or unrelated framework demonstrations. Planning checklists may guide the build, but do not leave backlog/planning artifacts in the delivered application unless the user requested them.
