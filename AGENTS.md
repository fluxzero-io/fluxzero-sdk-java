# AGENTS.md

Instructions for coding agents working in this repository.

## Worktrees

- Make changes in a dedicated Git worktree under `.worktrees/<short-name>/` in the main checkout, starting from the latest local `main` commit unless instructed otherwise. Keep the main checkout available for other work.
- Use short descriptive branch names with prefixes such as `fix/`, `feature/`, `docs/`, or `chore/`; never use `codex/`.
- `.worktrees/` is ignored by the parent checkout. Each worktree keeps its own Git index, so this does not prevent tracking files inside the worktree.

## Project Shape

This is the Fluxzero Java SDK, built as a Maven multi-module project.

- `common`: shared protocol objects, serialization, reflection, utilities, websocket helpers, and low-level API types.
- `sdk`: the public Java SDK, including configuration, publishing, tracking, handlers, persistence, scheduling, web support, Spring integration, and the `TestFixture`.
- `test-server`: an in-memory Fluxzero server used for local and integration-style testing.
- `proxy`: the request-forwarding proxy server and its shaded runnable artifact.
- `fluxzero-bom`: dependency-management BOM for downstream SDK projects.
- `annotation-processor-tests`, `java-downstream-project`, and `kotlin-downstream-project`: compatibility checks that
  must keep working when annotations, reflection, handlers, serialization, public artifacts, or downstream project
  setup change.
- `docs/agents`: canonical, namespace-aware documentation graph for agents building applications with the SDK.

Keep durable product documentation, API guidance, and release-relevant decisions in this repository. Store feature
plans, progress notes, experiment journals, measurements, checkpoint history, and other backlog work records in the
owning todo dossier under `../work-backlog/docs/`; link to that dossier from commits or pull requests when useful rather
than copying those records into this repository.

## Regression Safety

Observable behavior and operational characteristics are compatibility contracts, even when Java API signatures do not
change. This includes returned values and failures; side effects, ordering, delivery guarantees, retries, and
idempotency; time and context propagation; synchronous and asynchronous completion; resource ownership and lifecycle;
persisted and wire formats; extension points; and throughput, latency, startup time, allocation, blocking, backpressure,
and broader resource consumption. Treat existing behavior outside the explicitly requested change as an invariant by
default.

Treat a change as high-risk when it affects shared infrastructure in `common`, a Fluxzero-wide execution path, stored or
exchanged data, security or contextual behavior, scheduling or time, concurrency or lifecycle, or code that runs for
most messages or requests. For high-risk changes:

1. Before editing, inspect the relevant public entry points, callers, tests, and extension points. Identify the intended
   change, the behavior that must remain unchanged, and the likely blast radius.
2. Prefer the narrowest implementation that satisfies the requirement. Keep unaffected inputs and flows on their
   existing path where practical. Do not assume an alternative implementation is equivalent merely because common
   happy-path results match.
3. Review the change across the applicable contract dimensions above, including uncommon but valid inputs, custom
   implementations and configuration, partial failure, cancellation, retries, concurrency, and shutdown.
4. Add focused tests for the new behavior and for preservation of important existing behavior. Exercise relevant public
   behavior and existing extension points instead of only implementation details. Consider both synchronous and
   asynchronous paths and downstream compatibility where applicable.
5. When complexity, materialization, copying, blocking, reflection, caching, I/O, or the execution model changes on a
   potentially hot path, perform a concrete performance and resource analysis. Add or run a benchmark when that
   analysis cannot confidently rule out a meaningful regression; a green functional suite alone is not sufficient.
6. After implementation, perform a separate adversarial review of the final diff before committing or pushing. A
   deliberate self-review focused only on regressions is the default. Use an independent reviewing agent when the
   complexity, blast radius, or risk of implementation bias makes a fresh context materially valuable.
7. Proactively correct obvious, important regressions within the requested scope. In the final report, state what was
   intentionally changed, which important behavior was preserved, how it was verified, and any plausible remaining
   risks or uncertainty.

Keep regression tests deterministic, focused on observable contracts, and proportionate in runtime. Do not make the
suite materially slower or flaky merely to increase coverage.

## Versioned Defaults

Use `fluxzero.defaults.version` to introduce a better default without silently changing applications that upgrade the
SDK. Treat the absence of this property as compatibility mode: existing behavior remains active until an application
opts into a defaults version at or after the change's threshold.

- Give each changed default a new ISO date threshold represented by a `LocalDate`. Thresholds are immutable and
  monotonic: never reuse an existing date for different behavior or change what an existing threshold means.
- Gate runtime behavior with `ApplicationProperties.defaultsVersionAtLeast(LocalDate)` only when the active Fluxzero
  application context is the intended property source. During builder or configuration resolution, use
  `ApplicationProperties.defaultsVersionAtLeast(PropertySource, LocalDate)` with that component's explicit source so
  multiple applications, namespaces, and tests cannot affect one another.
- Provide a dedicated feature property for every material default change. An explicitly configured feature property
  takes precedence in both directions: it can enable the new behavior on older defaults or retain compatibility
  behavior on newer defaults. Derive the value from `fluxzero.defaults.version` only when the feature property is
  absent.
- Let invalid non-empty defaults versions fail with the configuration error from `ApplicationProperties`; do not
  silently fall back to compatibility behavior.

The usual selection pattern is:

```java
private static final LocalDate FEATURE_DEFAULTS_VERSION = LocalDate.of(2026, 8, 4);

String configured = propertySource.get(FEATURE_PROPERTY);
boolean enabled = configured != null
        ? Boolean.parseBoolean(configured.trim())
        : ApplicationProperties.defaultsVersionAtLeast(propertySource, FEATURE_DEFAULTS_VERSION);
```

Resolve versioned defaults at the configuration or lifecycle boundary where possible. If a value is needed on a hot
path, do not repeatedly parse the date or allocate configuration state; resolve it once or use a lifecycle-bound cache
that is keyed by the actual feature property and defaults version. Never use a static cache that conflates distinct
applications or property sources.

For stored or exchanged data, plan the transition as a mixed-version migration. New defaults may write the new form,
but new readers should continue to accept the old form where practical, and the dedicated feature property should
provide a rollback path. Review security, retries, replay, queued messages, and independently upgraded producers and
consumers explicitly.

Document every threshold and its override in both the `ApplicationProperties.DEFAULTS_VERSION_PROPERTY` Javadoc table
and the README's Versioned Defaults table, plus the affected feature documentation. Add focused tests for an absent
defaults version, an older version, the exact threshold, a newer version, and explicit overrides in both directions.
For wire or persisted formats, also test old-data reads and new-data round trips.

## Delivery And Release Authorization

- By default, deliver fixes, features, backports, and instruction changes as verified local commits in a dedicated
  worktree, or as a pull request when appropriate to the requested scope. Requests such as "pick up this issue",
  "fix this", "backport this", "use a separate worktree", or "open a PR" do not authorize merging or publication.
- Merge pull requests, enable auto-merge, push to release branches, create release tags, trigger deployment workflows,
  or publish packages, images, documentation, or releases only when the user explicitly authorizes that action and
  its target release line. A push or PR merge that automatically starts a release is also a publication action.
  Successful tests, an approved review, or a completed backlog item do not provide release authorization.
- Apply this boundary equally to the active major and maintenance branches. Preparing a backport does not imply
  permission to release it, and publication is not required to finish an implementation task unless requested.
- When the user authorizes publication of a fix affecting both the active major and a maintenance line, coordinate
  those releases: publish the active-major fix first, then the maintenance patch, unless the user explicitly requests
  a maintenance-only release or a different order. Do not publish 1.x while the corresponding 2.x fix is still only
  local or in an unmerged PR merely because the maintenance workflow can be triggered independently.
- Existing explicit authorization remains valid within its stated scope; do not ask for the same permission again.

## Maintenance Releases

- For every bug fix on `main`, explicitly assess whether the same defect affects supported `1.x` behavior.
  If it does, prepare and qualify the narrow backport locally or in a separate PR, without copying unrelated 2.x
  features or defaults. Record the applicability decision and the local commit or PR reference in the owning backlog
  dossier. Publish only within the explicit authorization described above.
- A request to fix or backport an issue on `1.x` normally ends with verified local commits or a PR, just like work on
  `main`. It does not by itself authorize merging the backport or publishing a maintenance patch.
- The following release steps apply only after the user has authorized maintenance publication.
- Keep `.github/release-major` at `1` on `1.x`. Choose an unused explicit patch version after inspecting the
  latest published 1.x tag. Preserve protected-branch checks and release only the merged, qualified commit.
- Trigger `Deploy` explicitly on `1.x` with that version; ordinary maintenance-branch pushes do not publish.
  Run the release version and publication policy checks, including validation of any existing tag on reruns.
- Maintenance releases use package channel `1.x` and Javadoc destination `javadoc/1.x`. Never move the main
  `latest` image channel, mark a maintenance release as GitHub Latest, or dispatch the public SDK website update.
- Verify the completed workflow, immutable tag, artifacts and release contents before reporting publication.
  Confirm that the released commit contains the backport and record the release reference in the owning backlog
  dossier. Keep the fix on the active major as well when applicable.

## Build And Test

- For changes confined to repository instructions (`AGENTS.md`) or README prose, review the diff and links and run
  `git diff --check`; do not run the full Maven build solely for those edits. Documentation consumed by builds,
  executable examples, or release artifacts still requires its relevant validation.
- Use the Maven wrapper: `./mvnw`.
- SDK v2 compiles with `maven.compiler.release=25`; building requires Java 25.0.3 or newer to avoid JDK-8370887. Use the JDK pinned in `mise.toml` with `mise exec -- ./mvnw`; CI and Docker images use updated Java 25 builds.
- Packaging the agent documentation ZIP also requires Python 3.9+; see `docs/agents/README.md` for source-archive builds.
- Full PR-equivalent verification is `./mvnw -B install`.
- For focused work, prefer targeted Maven runs such as `./mvnw -pl sdk -am test` or `./mvnw -pl proxy -am -Dtest=ProxyServerTest test`.
- Apply the Regression Safety workflow for code changes and run checks proportionate to the affected modules, execution paths, and downstream projects.
- Before changes that affect release packaging, generated artifacts, or Maven Central metadata, run `./mvnw -B install` and qualify `./mvnw -B -Dgpg.skip -DskipTests deploy -DaltDeploymentRepository=fluxzero::file:///absolute/path/to/temporary-repository`. Sources and Javadoc are built by default; the `sign` profile adds signatures. Never use the public destination for a packaging check.
- Javadoc/site work should be checked with `./mvnw -B site -Pjavadoc`.

## Build Performance Is A Regression Contract

- Build and test duration must not regress. Treat a reproducible slowdown as a defect to fix before delivery;
  do not accept it merely because the build is green or new tests were added. An intentional, unavoidable
  trade-off requires explicit user agreement with measured cost and the alternatives considered.
- For changes to tests, fixtures, shared execution paths, dependencies, build configuration or CI, inspect
  the slowest affected suites and compare before/after timings under equivalent conditions: same JDK,
  hardware, command, fork/worker limits and cache state. Distinguish full clean CI builds from warm local
  runs. Repeat measurements when runner noise could explain the result; never claim an improvement from
  one favorable run or compare a local timing with a GitHub timing.
- Preserve every existing test case, assertion, workload, transport, persistence/recovery boundary and
  artifact/release check. Do not obtain speed by skipping coverage, reducing evidence, weakening deadlines,
  adding retries, suppressing failures or disabling optimizing JIT tiers. Compare test identities and skip
  states as well as counts when changing discovery, scheduling or forks.
- Fix redundant setup, unnecessary waiting, resource leaks and contention first. Prefer observable readiness
  and deterministic synchronization over sleeps. Parallelize only independently owned state with bounded
  workers, heap, containers and guaranteed cleanup; repeat full-suite qualification to detect interference.
- Review new slow tests before committing: shared setup must retain isolation, and waiting must end as soon
  as the required evidence exists. Check the full suite for cumulative costs; a fast isolated test does not
  excuse a slow or unstable complete build.
- Record commands, environment, baseline/candidate timings, slow-suite changes and coverage comparison in
  the owning backlog dossier. Keep the previous performance baseline discoverable and update it only after
  repeated successful qualification. Do not silently reset the baseline to a slower build.

## Documentation Teaching Structure

- Keep overview pages at the level of their parent section: explain the whole, its main parts and their relationship,
  then point to the next level. The Developer Guides overview covers concepts, tutorials, how-to guides and reference;
  task lists belong in the how-to overview. Mirror that hierarchy in headings and navigation. Do not promote a recent
  addition or one subsection into the organizing principle of its parent page.
- Human developer documentation under `docs/developer/` teaches what the SDK makes possible and how to use it.
  Explain the problem the capability solves, then show the smallest concrete example that demonstrates it.
  Let readers see a useful result before adding more concepts; avoid hidden helper behavior, placeholder-only
  implementations and speculative fields in introductory examples. Introduce the ordinary workflow before
  options, guarantees, compatibility notes, edge cases, or internals. Apply this order recursively within sections:
  refinements belong below the concept they refine, with deeper details in subordinate sections only when useful.
- A how-to guide answers one developer task with one minimal working path. Move task walkthroughs out of reference
  chapters into focused guides and link both ways. Do not add optional variants or a troubleshooting catalogue to
  every guide; keep essential prerequisites next to the example and let other topics have their own page.
- Keep human guides selective. Explain enough for a developer to understand the capability and make the next decision;
  put exhaustive overloads, parameter/default inventories, lifecycle paths and exact API contracts in Javadoc.
  Link to the owning guide or API reference instead of duplicating its explanation. A long chapter is justified by
  useful teaching and worked examples, not by covering every implementation branch.
- Preserve technical truth while simplifying. Include a brief caveat next to an example when omitting it would make
  that example misleading or unsafe; place the fuller explanation later under the relevant topic. Do not turn an
  opening into a defensive list of guarantees and exceptions, and do not bury an essential prerequisite.
- When repairing documentation, inspect Git history (including the website history before the SDK import) to recover
  the original teaching structure and identify later insertions. Use that evidence to understand changes, then judge
  all text by its teaching value, including the original;
  do not treat length alone as a defect or infer human/agent authorship from Git author names. Preserving the original
  structure does not establish completeness or accuracy: compare inventories and claims with current APIs and publishers.
- Reuse existing Astro/Starlight components in moderation: Java/Kotlin tabs, useful asides and diagrams that explain
  a relationship. Use the existing `Jdoclink` component for focused API links and inline Javadoc previews rather than
  reproducing reference text in the guide. Confirm the referenced type is available to the website's Javadoc loader.
- Integrate additions into the existing learning sequence. Re-read the whole affected chapter, consolidate repeated
  explanations, and remove obsolete or redundant detail. Do not prepend a recent fix, append a release narrative,
  or copy regression-test cases and implementation notes into a guide. Preserve useful Java and Kotlin examples,
  stable page routes and incoming section links when restructuring.
- The agent graph under `docs/agents/` uses the same progressive structure: concise orientation and capability choices
  in parent articles, with explicit links to focused deeper articles. The deepest leaves may match or exceed Javadoc
  detail when cross-API behavior, constraints or examples help agents implement correctly. Keep that detail discoverable
  through manifest links and symbols; do not repeat it in every ancestor or strip useful leaf detail merely for brevity.
- Reviewing all documentation surfaces means giving each audience the appropriate explanation, not copying the same
  technical text into README, human guides, agent articles and Javadoc. A human-guide edit does not automatically
  require an agent-graph rewrite. Verify changed examples against the SDK and check MDX and links; assess the final
  chapter as a learning path, not just the added paragraphs in the diff.


## Coding Guidelines

- Keep the root README timeless and concise: explain the product, how to start, where to find documentation, and how to contribute. Do not add release-specific narratives, versioned feature lists, Model/Graph explanations, bug-fix details, or specialized configuration contracts. Put those in the owning guides, Javadocs, and release notes instead; review the README for relevance without appending a paragraph for every change. Current build prerequisites and stable documentation links may remain.
- This public SDK is used by many external projects. Small mistakes can propagate widely, so favor durable, well-tested solutions over quick patches, local workarounds, or behavior that is hard to explain.
- Do not paper over unclear failures. Understand the root cause, preserve invariants, and document any intentional trade-off in code, tests, or the commit body.
- Preserve the existing package boundaries. Put shared serialization/protocol/reflection behavior in `common`, public SDK behavior in `sdk`, and server-specific behavior in `test-server` or `proxy`.
- Follow existing Java style: Apache license headers on Java/XML files that use them, standard Java imports before static imports, Lombok for boilerplate such as constructors/getters/builders where it fits the local pattern, and small package-private tests where possible.
- Preserve supported public API compatibility by default, but treat it as a design trade-off rather than an overriding constraint. Java visibility alone does not make an API supported, and downstream use of an internal or implementation API does not require preserving it. During the final review, identify source- or binary-incompatible changes and verify whether they affect a supported or intentionally consumed extension point. Seek explicit user agreement before committing such a break to a supported API. Do not contort internal design to retain obsolete methods or constructors; if compatibility would materially harm correctness, reliability, performance, or maintainability, explain the trade-off instead.
- When a field is added to a data or value type, let an existing all-fields constructor evolve with the fields by default, especially when Lombok annotations such as `@AllArgsConstructor` own constructor generation. Do not add a legacy constructor overload merely to preserve the previous field set. During the final review, check whether the old constructor was a documented or intentionally supported external contract; raise the compatibility question only in that case. Add an overload only when it has independent domain meaning or the user explicitly requests it.
- Document new or changed public API surface. Prefer field/type/method Javadoc for data objects and Lombok-backed classes; constructor-level Javadoc may be omitted when Lombok or field documentation already makes the constructor contract clear.
- For every new or changed feature, review and update all relevant documentation surfaces together: the root `README.md`, human documentation under `docs/developer/`, and the graph under `docs/agents/`. Preserve both Java and Kotlin guidance and keep graph links/symbols discoverable. If a surface needs no change, verify that deliberately rather than overlooking it. Run `python3 .github/scripts/validate-agent-docs.py` after graph changes.
- In `docs/developer/`, present equivalent Java and Kotlin examples together in one Starlight `Tabs` group with a `TabItem` for each language. Keep their behavior and scope aligned; do not separate translations with prose or collect them in a later language-specific section. Standalone examples are appropriate only for explicitly language-specific guidance, such as `package-info.java`, Kotlin compiler configuration, or Gradle Kotlin DSL.
- Never create a feature-specific property utility or resolve configuration by reading environment variables, system properties, or property files directly. Always use the `ApplicationProperties` infrastructure; at builder or configuration boundaries, read from that component's configured `PropertySource` so application-local overrides and tests remain isolated. This shared path owns source precedence, conventional environment-variable normalization, placeholders, and decryption. Document the property key and conventional environment-variable name prominently, with builder methods presented as programmatic overrides or alternatives.
- Prefer existing extension points before adding new abstractions: interceptors, gateways, handlers, registries, parameter resolvers, clients, stores, and `TestFixture`.
- Prefer separate `@Model` types for new domain state: independent histories and evolving `@Parent` relationships
  allow models to move, gain relationships, and participate in atomic operations without expanding a shared root.
  `@Aggregate` is the legacy/migration API, deprecated for removal in SDK 3.0; retain its 2.x behavior and plan
  data migrations explicitly before upgrading to 3.0. `@Member` remains supported and is not deprecated,
  including within `@Stateful` handlers.
- Choose `@Model` versus `@Member` by domain lifecycle before storage or object shape. State with independent creation,
  changes, history, retention, or deletion is a separate Model connected with `@Parent`, even when it appears in a
  parent collection; a parent-scoped identity is sufficient. Use `@Member` only when all of those concerns deliberately
  belong to the root. Searchability, update frequency, storage strategy, and convenient embedding are not boundaries.
- Always use `ReflectionUtils` and its central `TypeMetadata` as the owner of class-scoped reflection caches. Extend that metadata instead of adding parallel `ClassValue` or class-keyed caches for methods, fields, annotations, or other structural reflection results. Keep computed values that capture runtime or instance state in a lifecycle-bound local cache; never place such values in the central class cache.
- Avoid adding dependencies casually. If a dependency is needed, manage versions from the root POM or the relevant BOM/module pattern.
- When changing message handling, tracking, scheduling, websocket, persistence, serialization, or reflection behavior, add focused tests in the owning module and consider both synchronous and asynchronous `TestFixture` paths.
- Do not commit build outputs from `target/`, generated local artifacts, or release-only zips.
- Keep this repository customer-neutral. Do not add customer or downstream-application names, captured production data,
  fixtures, logs, or application-specific diagnostics. Preserve generally applicable SDK evidence here and keep
  downstream-specific proof in the repository that owns that application.

## Release Note Format

- Follow [RELEASING.md](RELEASING.md#release-notes) for every release description, including manual edits,
  feature releases, maintenance releases and repairs of existing notes. The generated structure is mandatory.
- Use `VERSION – Mon D, YYYY` for release titles (for example `2.15.1 – Oct 5, 2026`), using the exact tag
  and original GitHub `published_at` date in UTC. Preserve this date on edits and reruns; use the shared
  `set-release-title.py` helper for authorized title changes. Start the body with the generated linked version/date heading.
  An optional short opening may follow that heading, but the categorized change lists must follow it.
- Preserve the generator's categories (`Features`, `Bug Fixes`, `Documentation`, etc.), HTML lists, commit/PR
  references and expandable `<details><summary>` explanations. A feature belongs in `Features`, even when it
  is the release's only change. Never replace the lists with a feature article, a Highlights section or
  GitHub's generic What's Changed list.
- Put longer feature explanations and migration/compatibility details inside the relevant item's expandable
  body. Keep essential upgrade warnings visible in the short opening as well. Correct superseded claims
  against the final tagged diff without discarding other changes or their references.
- Before any authorized release-description update, save the existing body and metadata, generate the exact
  tag range, and review the complete replacement. After updating, read the body back and check the rendered
  version/date heading, category lists and expandable details; verify tags, assets and release flags are unchanged.
  See RELEASING.md for the local generation command. Editing notes does not authorize a new release or Deploy rerun.

## Commit Messages

- Use ordinary Conventional Commit messages for documentation; do not add `[skip ci]`. Markdown/MDX, similar
  documentation text, documentation images and the agent graph manifest use lightweight CI. Source resources,
  fixtures, scripts, configuration and mixed changes retain full SDK qualification. The classifier in
  `.github/scripts/classify-changes.py` owns the exact boundary; do not infer it from whether a file is compiled.
- Documentation-only pushes to `main` validate the agent graph and archive contract without publishing an SDK
  version. Changes under `docs/developer/` still notify the website using the exact SDK commit. Versioned agent
  documentation includes these edits at the next SDK release; existing releases stay immutable.
  Manual dispatch retains the full release path and the explicit publication authorization described above.
- Classify documentation as `docs`, repository maintenance as `chore`, and tests as `test`; these types produce a
  patch release when a release runs, unless a `feat` commit since the previous release requires a minor bump.
- Use Conventional Commits with a clear domain scope for human-authored commits.
- Format: `<type>(<domain>): <short imperative summary>`.
- Good examples: `fix(tracking): avoid negative pause sleeps`, `refactor(handling): simplify payload resolver ordering`, `test(logging): cover async appender shutdown`.
- Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `deps`, `perf`, `chore`.
- Useful domains in this repo include `tracking`, `handling`, `modeling`, `entity`, `websocket`, `serialization`, `logging`, `test-server`, `proxy`, `spring`, `ci`, and `deps`.
- For non-trivial commits, include a body that explains why the change is needed, and the behavioral impact.
- Never use literal escape sequences such as `\n` or `\r\n` to represent line breaks in a commit message. Supply real line breaks, for example with separate `-m` arguments or a commit-message file, and inspect the resulting message to ensure it renders correctly.
